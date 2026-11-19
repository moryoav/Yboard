package com.yboard.keyboard

import android.app.Instrumentation
import android.os.SystemClock
import android.view.MotionEvent
import com.yboard.keyboard.ime.YboardIME
import com.yboard.keyboard.keyboard.KeyDef
import com.yboard.keyboard.keyboard.KeyType
import com.yboard.keyboard.keyboard.KeyboardRootView
import com.yboard.keyboard.keyboard.KeyboardView
import com.yboard.keyboard.settings.Prefs
import com.yboard.keyboard.text.Pt
import com.yboard.keyboard.text.SentenceContext
import org.json.JSONObject

/** Real service, separate app editor and actual local model. Synthetic text only. */
internal class PredictionImeChecks(private val instrumentation: Instrumentation) {
    private lateinit var ime: YboardIME
    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
    private fun field(name: String) = YboardIME::class.java.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST") private fun slots() = field("slotWords").get(ime) as List<String>
    private fun pendingSwipes() = (field("pendingSwipes").get(ime) as Collection<*>).size
    private fun text() = ime.currentInputConnection?.getTextBeforeCursor(SentenceContext.READ_LIMIT, 0)?.toString().orEmpty()
    private fun touchGlide(word: String) {
        val keyboard = (field("root").get(ime) as KeyboardRootView).keyboard
        val method = KeyboardView::class.java.getDeclaredMethod("keyCenters").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val centers = method.invoke(keyboard) as Map<Char, Pt>
        val started = SystemClock.uptimeMillis()
        fun send(action: Int, point: Pt) {
            val event = MotionEvent.obtain(started, SystemClock.uptimeMillis(), action, point.x, point.y, 0)
            try { check(keyboard.onTouchEvent(event)) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, centers.getValue(word.first()))
        word.drop(1).forEach { send(MotionEvent.ACTION_MOVE, centers.getValue(it)) }
        send(MotionEvent.ACTION_UP, centers.getValue(word.last()))
    }
    private fun await(label: String, timeoutMs: Long = 20_000, condition: () -> Boolean): Long {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            var passed = false
            onMain { passed = condition() }
            if (passed) return SystemClock.elapsedRealtime() - start
            SystemClock.sleep(25)
        }
        var details = ""
        onMain { details = "text=${text()}; slots=${slots()}; pending=${pendingSwipes()}; status=${com.yboard.keyboard.local.LocalQwen.status.value}" }
        error("Timed out: $label; $details")
    }
    private fun launch(value: String, inputType: Int = 131073, options: Int = 0): Boolean {
        val encoded = java.util.Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
        val command = "am start -W -n com.yboard.keyboard.probe/com.yboard.keyboard.test.EditorProbeActivity -f 0x10008000 --es text64 $encoded --ei inputType $inputType --ei imeOptions $options"
        instrumentation.uiAutomation.executeShellCommand(command).use { fd -> java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() } }
        SystemClock.sleep(1000)
        var found = false
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (!found && SystemClock.elapsedRealtime() < deadline) {
            onMain {
                val type = Class.forName("android.app.ActivityThread")
                val thread = type.getDeclaredMethod("currentActivityThread").invoke(null)
                val services = type.getDeclaredField("mServices").apply { isAccessible = true }.get(thread) as Map<*, *>
                services.values.filterIsInstance<YboardIME>().firstOrNull { it.isInputViewShown && it.currentInputConnection != null }
                    ?.let { ime = it; found = true }
            }
            if (!found) SystemClock.sleep(100)
        }
        // OnePlus routes password fields to its protected system keyboard.
        if (!found && inputType == 129) return false
        check(found) { "IME service was not started by the probe editor" }
        await("synthetic editor connection") { text() == value }
        return true
    }
    fun run(): String {
        Prefs.init(instrumentation.targetContext)
        val saved = Prefs.current
        val report = JSONObject()
        try {
            onMain { Prefs.update { it.copy(language = "en", suggestions = true, nextWords = true,
                learnWords = false, autoCorrect = true, autoCaps = false, swipeTyping = true) } }
            launch("See you tom")
            report.put("completionWaitMs", await("prefix completion") { slots().contains("tomorrow") })
            onMain {
                check(slots().filter { it.isNotEmpty() }.all { it.startsWith("tom", true) })
                ime.onSuggestion(slots().indexOf("tomorrow"))
            }
            await("accepted completion") { text() == "See you tomorrow " }
            report.put("nextWordWaitMs", await("next word") { slots().any { it.isNotEmpty() } })
            onMain { ime.onSuggestion(slots().indexOfFirst { it.isNotEmpty() }) }
            await("second suggestion tap") { text().length > "See you tomorrow ".length }
            launch("teh")
            onMain { ime.onKey(KeyDef(KeyType.SPACE, " ")) }
            await("literal typing") { text() == "teh " }
            val longSentence = "I remember " + "the conversation about our plans ".repeat(20)
            launch(longSentence + "tom")
            onMain {
                val input = YboardIME::class.java.getDeclaredMethod("localInput").apply { isAccessible = true }.invoke(ime) as SentenceContext.Input
                check(input.context == longSentence) { "Full sentence was truncated" }
            }
            launch("I will be ")
            val centers = "qwertyuiop".mapIndexed { i, c -> c to Pt(i * 100f, 0f) }.toMap() +
                "asdfghjkl".mapIndexed { i, c -> c to Pt((i + .5f) * 100, 140f) }.toMap() +
                "zxcvbnm".mapIndexed { i, c -> c to Pt((i + 1.5f) * 100, 280f) }.toMap()
            fun glide(word: String) {
                ime.onSwipeStart()
                ime.onSwipe(word.map { centers.getValue(it) }, centers, 100f)
            }
            onMain { glide("there") }
            report.put("swipeWaitMs", await("contextual swipe") { text() == "I will be there" })
            onMain { check(slots().contains("there")) }
            launch("I will be ")
            onMain {
                touchGlide("there")
                touchGlide("again")
                touchGlide("tomorrow")
                check(pendingSwipes() == 3) { "All three released gestures should be queued" }
                check(text() == "I will be ")
            }
            report.put("queuedSwipeWaitMs", await("three swipes in order", timeoutMs = 60_000) {
                text() == "I will be there again tomorrow"
            })
            onMain { check(slots().contains("tomorrow")) }
            launch("Move the cursor ")
            onMain {
                glide("there")
                glide("again")
                check(pendingSwipes() == 2)
                ime.currentInputConnection.setSelection(4, 4)
            }
            await("swipes discarded after cursor move") { pendingSwipes() == 0 }
            onMain { check(text() == "Move" && ime.currentInputConnection.getTextAfterCursor(80, 0).toString() == " the cursor ") }
            launch("A new message ")
            onMain {
                ime.onSwipe("there".map { centers.getValue(it) }, centers, 100f)
                ime.onKey(KeyDef(KeyType.CHAR, "x"))
            }
            SystemClock.sleep(1000)
            onMain { check(text() == "A new message x") }
            launch("Private ", options = 0x1000000)
            onMain {
                val allowed = YboardIME::class.java.getDeclaredMethod("localAllowed").apply { isAccessible = true }.invoke(ime) as Boolean
                check(!allowed && slots().isEmpty())
            }
            val ownPassword = launch("secret", inputType = 129)
            onMain {
                if (ownPassword) { check(slots().isEmpty()); check(!(field("learningAllowed").get(ime) as Boolean)) }
                else check(!com.yboard.keyboard.text.EditorPolicy.suggestions(129))
            }
            if (!ownPassword) {
                val dump = instrumentation.uiAutomation.executeShellCommand("dumpsys input_method").use { fd ->
                    java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
                }
                val current = Regex("mCurId=(\\S+)").find(dump)?.groupValues?.get(1).orEmpty()
                check(current.isNotEmpty() && !current.startsWith("com.yboard.keyboard/")) { "No replacement password keyboard was active" }
            }
            report.put("passwordKeyboard", if (ownPassword) "Yboard guarded" else "OnePlus protected keyboard")
            launch("Finished ")
            report.put("passed", "prefix completion, consecutive taps, literal typing, full sentence, Qwen-ranked swipes, ordered glide queue, cursor and tap cancellation, private/password guards")
            return report.toString()
        } finally { onMain { Prefs.update { saved } } }
    }
}
