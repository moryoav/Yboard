package com.yboard.keyboard.voice

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * "Tap Send for me": lets the voice command "send" tap a chat app's own Send button
 * (WhatsApp's Send button isn't part of the keyboard, so a keyboard can't press it otherwise).
 *
 * It does nothing on its own. Only when you say "send" does it look in the app on screen for
 * a Send button and tap it. It never stores or shares what's on the screen.
 */
class SendButtonService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "Tap Send for me: connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Taps the Send button of the app on screen. True if one was found and tapped. */
    fun tapSend(): Boolean {
        // The app you're typing in first (the active window), then any other app window on screen.
        val roots = ArrayList<AccessibilityNodeInfo>()
        try { rootInActiveWindow?.let { roots.add(it) } } catch (_: Exception) {}
        try {
            windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.forEach { w ->
                w.root?.let { r -> if (roots.none { it.windowId == r.windowId }) roots.add(r) }
            }
        } catch (_: Exception) {
        }
        for (root in roots) {
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg == packageName) continue  // never inside Yboard itself
            for (button in findSend(root)) {
                if (click(button)) {
                    Log.i(TAG, "tapped Send in $pkg")
                    return true
                }
            }
        }
        Log.i(TAG, "no Send button found in ${roots.map { it.packageName }.distinct()}")
        return false
    }

    /** Clicks [node], or the nearest clickable view around it. */
    private fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null && hops <= 4) {
            if (n.isClickable && n.isEnabled && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
            hops++
        }
        return false
    }

    /** Visible Send buttons in [root], most likely first. */
    private fun findSend(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val pkg = root.packageName?.toString().orEmpty()
        val out = ArrayList<AccessibilityNodeInfo>()
        for (id in KNOWN_IDS) {
            if (!id.startsWith("$pkg:")) continue
            try {
                root.findAccessibilityNodeInfosByViewId(id).filterTo(out) { it.isVisibleToUser }
            } catch (_: Exception) {
            }
        }
        // Otherwise a button whose label is exactly "Send" (in English or Hebrew).
        for (label in LABELS) {
            try {
                root.findAccessibilityNodeInfosByText(label).filterTo(out) { n ->
                    n.isVisibleToUser && (
                        n.contentDescription?.toString()?.trim().equals(label, ignoreCase = true) ||
                            (n.isClickable && n.text?.toString()?.trim().equals(label, ignoreCase = true))
                        )
                }
            } catch (_: Exception) {
            }
        }
        // Last: a button just above the keyboard whose label starts with "Send" (ChatGPT: "Send prompt",
        // other AI chats: "Send message"), never one for money, files or photos. Apps built with
        // Jetpack Compose (like ChatGPT) don't answer the search above, so walk through the screen.
        if (out.isEmpty()) {
            val buttons = bottomButtons(root)
            out.addAll(buttons.filter { (_, label) -> looksLikeSendButton(label) || LABELS.any { it.equals(label, ignoreCase = true) } }.map { it.first })
            if (out.isEmpty()) {
                // So a miss can be fixed: the names of the buttons near the bottom (button names only, never chat text).
                Log.i(TAG, "buttons near the bottom of $pkg: ${buttons.map { it.second }.distinct().take(12)}")
            }
        }
        return out
    }

    /** Visible, clickable things in the lower part of the screen, with their names (short ones only). */
    private fun bottomButtons(root: AccessibilityNodeInfo): List<Pair<AccessibilityNodeInfo, String>> {
        val screenH = resources.displayMetrics.heightPixels
        val rect = android.graphics.Rect()
        val found = ArrayList<Pair<AccessibilityNodeInfo, String>>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < 3000) {
            val n = queue.removeFirst()
            seen++
            try {
                if (n.isVisibleToUser) {
                    n.getBoundsInScreen(rect)
                    val label = (n.contentDescription ?: n.text)?.toString()?.trim().orEmpty()
                    val inside = rect.top > screenH * 0.3f && rect.height() < screenH * 0.15f
                    // The name may sit on the button itself or on the icon inside it.
                    if (inside && label.isNotEmpty() && label.length <= 30 && (n.isClickable || n.parent?.isClickable == true)) {
                        found.add(n to label)
                    }
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
            } catch (_: Exception) {
            }
        }
        return found
    }

    companion object {
        private const val TAG = "YboardVoice"
        @Volatile var instance: SendButtonService? = null
            private set

        private val KNOWN_IDS = listOf(
            "com.whatsapp:id/send", "com.whatsapp.w4b:id/send",
            "org.telegram.messenger:id/chat_send_button",
            "org.thoughtcrime.securesms:id/send_button",
            "com.google.android.apps.messaging:id/send_message_button_icon",
            "com.google.android.apps.messaging:id/send_message_button_container",
        )
        private val LABELS = listOf(
            "Send", "Send message", "Send prompt", "Send SMS", "Send MMS", "Send encrypted message", "Send RCS message",
            "שליחה", "שלח", "שלחי", "שליחת הודעה", "שלח הודעה", "שליחת הנחיה", "שלח הנחיה",
        )

        /** Never tap these, even though they start with "Send". */
        private val NOT_SEND = listOf(
            "money", "pay", "payment", "file", "photo", "image", "picture", "location", "contact", "gift", "voice", "audio",
            "כסף", "תשלום", "קובץ", "תמונה", "מיקום", "איש קשר", "מתנה", "קולית", "הקלטה",
        )

        /**
         * A label like "Send prompt" or "שלח הודעה": a short label starting with "send" that isn't about
         * money, files, photos or voice.
         */
        fun looksLikeSendButton(label: String?): Boolean {
            val l = label?.trim()?.lowercase() ?: return false
            if (l.isEmpty() || l.length > 30) return false
            val first = l.split(' ', ':', ',').first()
            val starts = first in setOf("send", "שלח", "שלחי", "שליחה", "שליחת")
            return starts && NOT_SEND.none { l.contains(it) }
        }

        /** Whether "Tap Send for me" is turned on in Android's accessibility settings. */
        fun isOn(context: Context): Boolean {
            val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(context, SendButtonService::class.java)
            return list.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
