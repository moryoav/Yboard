package com.yboard.keyboard.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.yboard.keyboard.R
import com.yboard.keyboard.settings.ToolbarShortcut
import com.yboard.keyboard.settings.ToolbarShortcut.*
import com.yboard.keyboard.settings.YboardSettings
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The strip above the keys (like Gboard's top bar): shortcut icons, or word suggestions
 * while you type.
 */
class ToolbarView(context: Context) : View(context) {

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Keyboard tools"
    }

    interface Listener {
        fun onToolbar(action: ToolbarShortcut)
        fun onSuggestion(index: Int) {}
        fun onSuggestionLongPress(index: Int) {}
    }

    var listener: Listener? = null
    var active: ToolbarShortcut? = null
        set(value) {
            field = value
            invalidate()
        }

    private var settings = YboardSettings()
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val feedback = Feedback(context)
    private val handler = Handler(Looper.getMainLooper())
    private var pressed: ToolbarShortcut? = null
    private var pressedSlot = -1
    val suggestionTouchActive: Boolean get() = pressedSlot >= 0
    private var longPressed = false

    fun cancelSuggestionPress() {
        if (pressedSlot < 0) return
        handler.removeCallbacks(longPressRunnable)
        pressedSlot = -1
        invalidate()
    }

    private var suggestions: List<String> = emptyList()
    private var primary = -1

    // AI voice typing: status text, mic loudness (0..1), and whether it's listening.
    private var voiceStatus: String? = null
    private var voiceLevel = 0f
    private var voiceListening = false

    /** [status] null = voice typing is off. */
    fun setVoice(status: String?, level: Float, listening: Boolean) {
        if (status == voiceStatus && level == voiceLevel && listening == voiceListening) return
        voiceStatus = status
        voiceLevel = level
        voiceListening = listening
        invalidate()
    }

    val voiceOn: Boolean get() = voiceStatus != null

    private var shortcuts = settings.toolbarShortcuts.visible
    private fun iconSize(res: Int) = dp(if (res == R.drawable.ic_clipboard || res == R.drawable.ic_palette) 22f else 24f)
    private val icons = HashMap<Int, Drawable>()
    private fun icon(res: Int) = icons.getOrPut(res) { ContextCompat.getDrawable(context, res)!!.mutate() }

    fun applySettings(s: YboardSettings) {
        if (s.toolbarShortcuts != settings.toolbarShortcuts) {
            cancelSuggestionPress()
            pressed = null
            shortcuts = s.toolbarShortcuts.visible
            laidOutWidth = -1
        }
        settings = s
        invalidate()
    }

    /** Show word suggestions (up to 3) instead of the icons; empty list = icons again. */
    fun setSuggestions(list: List<String>, primaryIndex: Int) {
        val l = list.take(3)
        if (l == suggestions && primaryIndex == primary) return
        suggestions = l
        primary = primaryIndex
        invalidate()
    }

    val showingSuggestions: Boolean get() = suggestions.any { it.isNotEmpty() } && active == null && voiceStatus == null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(46f).roundToInt())
    }

    private data class Slot(
        val shortcut: ToolbarShortcut?,
        val left: Float,
        val right: Float,
        private val iconCenterFraction: Float = 0.5f,
    ) {
        val width: Float get() = right - left
        val iconCenter: Float get() = left + width * iconCenterFraction
    }

    private val micR get() = dp(19f)
    private var slots = emptyList<Slot>()
    private var textSlot: Slot? = null
    private var laidOutWidth = -1
    private var laidOutShortcuts = emptyList<ToolbarShortcut>()
    private var laidOutVoice = false
    private var laidOutSuggestions = false

    /** The same slots drive drawing and hit testing. A null shortcut holds text. */
    private fun updateSlots() {
        val suggest = showingSuggestions
        if (laidOutWidth == width && laidOutShortcuts == shortcuts &&
            laidOutVoice == voiceOn && laidOutSuggestions == suggest
        ) return
        laidOutWidth = width
        laidOutShortcuts = shortcuts
        laidOutVoice = voiceOn
        laidOutSuggestions = suggest

        if (!voiceOn && !suggest) {
            val count = shortcuts.size.toFloat()
            slots = shortcuts.mapIndexed { index, shortcut ->
                Slot(shortcut, width * index / count, width * (index + 1) / count)
            }
            textSlot = null
            return
        }

        val items = if (voiceOn) buildList {
            if (MENU in shortcuts) add(MENU)
            add(null)
            add(MIC)
        } else buildList {
            // Suggestions go where the first shortcut that they replace used to be.
            val keeps = settings.toolbarShortcuts::keepsDuringSuggestions
            val firstReplaced = shortcuts.indexOfFirst { !keeps(it) }
            val split = if (firstReplaced >= 0) firstReplaced else if (shortcuts.firstOrNull() == MENU) 1 else 0
            addAll(shortcuts.take(split))
            add(null)
            addAll(shortcuts.drop(split).filter(keeps))
        }
        val flexibleCount = shortcuts.count { it != MIC && it != STICKER }.coerceAtLeast(3)
        val reserved = (if (MIC in items) dp(54f) else 0f) + (if (STICKER in items) dp(46f) else 0f)
        val fixedScale = if (reserved > 0f) {
            min(1f, (width - dp(28f) * flexibleCount).coerceAtLeast(width / 2f) / reserved)
        } else 1f
        val freeWidth = width - reserved * fixedScale
        val ordinaryWidth = freeWidth / flexibleCount
        val textWidth = freeWidth - if (MENU in items) ordinaryWidth else 0f
        var left = 0f
        slots = items.map { shortcut ->
            val slotWidth = when (shortcut) {
                MIC -> dp(54f) * fixedScale
                STICKER -> dp(46f) * fixedScale
                null -> textWidth
                else -> ordinaryWidth
            }
            val iconCenterFraction = when (shortcut) {
                MIC -> 25f / 54f
                STICKER -> 25f / 46f
                else -> 0.5f
            }
            Slot(shortcut, left, left + slotWidth, iconCenterFraction).also { left = it.right }
        }
        textSlot = slots.firstOrNull { it.shortcut == null }
    }

    override fun onDraw(canvas: Canvas) {
        updateSlots()
        val cy = height / 2f
        val voice = voiceStatus
        for (slot in slots) {
            when (val shortcut = slot.shortcut) {
                null -> if (voice != null) drawVoiceStatus(canvas, voice, slot, cy) else drawSuggestions(canvas, slot, cy)
                MIC -> if (voice != null) drawVoiceMic(canvas, slot, cy) else drawIdleMic(canvas, slot, cy)
                else -> drawToolIcon(canvas, slot, shortcut, cy)
            }
        }
    }

    private fun drawIdleMic(canvas: Canvas, slot: Slot, cy: Float) {
        val t = settings.theme
        val cx = slot.iconCenter
        val radius = min(micR, slot.width / 2f)
        // Same look as the keys (see-through on see-through themes), like Gboard.
        paint.color = ThemeRender.withAlpha(t.keyColor, if (t.keyOpacity < 0.5f) min(t.keyOpacity * 1.4f, 0.5f) else t.keyOpacity)
        canvas.drawCircle(cx, cy, radius, paint)
        if (pressed == MIC) {
            paint.color = ThemeRender.withAlpha(t.textColor, 0.15f)
            canvas.drawCircle(cx, cy, radius, paint)
        }
        drawIcon(canvas, MIC.icon, cx, cy, min(dp(24f), slot.width), t.textColor)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = javaClass.name
        if (listener != null && showsSticker()) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.action_create_sticker, "Create sticker from message"))
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == R.id.action_create_sticker && showsSticker()) {
            listener?.let {
                it.onToolbar(STICKER)
                return true
            }
        }
        return super.performAccessibilityAction(action, arguments)
    }

    private fun showsSticker(): Boolean {
        updateSlots()
        return slots.any { it.shortcut == STICKER }
    }

    private fun drawVoiceStatus(canvas: Canvas, status: String, slot: Slot, cy: Float) {
        val t = settings.theme
        text.typeface = Fonts.medium(context)
        text.textSize = dp(15f)
        text.color = ThemeRender.withAlpha(t.textColor, 0.85f)
        val label = ellipsize(status, slot.width - dp(10f))
        val fm = text.fontMetrics
        canvas.drawText(label, (slot.left + slot.right - dp(2f)) / 2f, cy - (fm.ascent + fm.descent) / 2f, text)
    }

    /** The mic while listening: accent colored, with a ring that grows when you talk. */
    private fun drawVoiceMic(canvas: Canvas, slot: Slot, cy: Float) {
        val t = settings.theme
        val cx = slot.iconCenter
        val radius = min(micR, slot.width / 2f)
        if (voiceListening) {
            paint.color = ThemeRender.withAlpha(t.accentColor, 0.28f)
            canvas.drawCircle(cx, cy, min(radius * (1.04f + 0.2f * voiceLevel), height / 2f), paint)
        }
        paint.color = if (voiceListening) t.accentColor else ThemeRender.withAlpha(t.accentColor, 0.55f)
        canvas.drawCircle(cx, cy, radius, paint)
        drawIcon(canvas, MIC.icon, cx, cy, min(dp(24f), slot.width), t.accentTextColor)
    }

    private fun drawToolIcon(canvas: Canvas, slot: Slot, shortcut: ToolbarShortcut, cy: Float) {
        val t = settings.theme
        val cx = slot.iconCenter
        val radius = min(micR, slot.width / 2f)
        val isActive = shortcut == active
        if (isActive) {
            paint.color = ThemeRender.withAlpha(t.accentColor, 0.32f)
            canvas.drawCircle(cx, cy, radius, paint)
        } else if (shortcut == pressed) {
            paint.color = ThemeRender.withAlpha(t.textColor, 0.12f)
            canvas.drawCircle(cx, cy, radius, paint)
        }
        val size = min(iconSize(shortcut.icon), slot.width)
        drawIcon(canvas, shortcut.icon, cx, cy, size, ThemeRender.withAlpha(t.textColor, if (isActive) 1f else 0.92f))
    }

    /** Strip text size; a bit smaller when long chips (like email endings) wouldn't fit. */
    private var stripTextSize = 0f

    /** Where the three slots start and end. Equal thirds, unless a long one (like an email) needs more room. */
    private fun slotEdges(slot: Slot): FloatArray {
        val l = slot.left
        val total = (slot.width - dp(2f)).coerceAtLeast(0f)
        val third = total / 3f
        text.typeface = Fonts.medium(context)
        fun needs(size: Float): FloatArray {
            text.textSize = size
            return FloatArray(3) { i ->
                suggestions.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { text.measureText(it) + dp(16f) } ?: 0f
            }
        }
        stripTextSize = dp(16.5f)
        var need = needs(stripTextSize)
        if (need.all { it <= third }) return floatArrayOf(l, l + third, l + 2 * third, l + total)
        if (need.sum() > total) {
            stripTextSize *= (total / need.sum()).coerceIn(0.75f, 1f)
            need = needs(stripTextSize)
        }
        val sum = need.sum()
        val widths = if (sum <= total) FloatArray(3) { need[it] + (total - sum) / 3f }  // all fit: share the spare room
        else FloatArray(3) { total * need[it] / sum }                                     // too long: share by length
        return floatArrayOf(l, l + widths[0], l + widths[0] + widths[1], l + total)
    }

    private fun drawSuggestions(canvas: Canvas, slot: Slot, cy: Float) {
        val t = settings.theme
        if (slot.width <= dp(2f)) return
        val e = slotEdges(slot)
        val even = abs((e[1] - e[0]) - (e[2] - e[1])) < 1f
        for (i in 0 until 3) {
            val left = e[i]
            val w = e[i + 1] - e[i]
            if (i == pressedSlot && hasSlot(i)) {
                paint.color = ThemeRender.withAlpha(t.textColor, 0.12f)
                canvas.drawRoundRect(left + dp(2f), dp(5f), left + w - dp(2f), height - dp(5f), dp(12f), dp(12f), paint)
            }
            if (i > 0 && (even || (hasSlot(i) && hasSlot(i - 1)))) {
                paint.color = ThemeRender.withAlpha(t.textColor, 0.18f)
                canvas.drawRect(left - dp(0.5f), cy - dp(9f), left + dp(0.5f), cy + dp(9f), paint)
            }
            val s = suggestions.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: continue
            text.typeface = if (i == primary) Fonts.medium(context) else Fonts.regular(context)
            text.textSize = stripTextSize
            text.color = if (i == primary) t.textColor else ThemeRender.withAlpha(t.textColor, 0.85f)
            val label = ellipsize(s, w - dp(10f))
            val fm = text.fontMetrics
            canvas.drawText(label, left + w / 2f, cy - (fm.ascent + fm.descent) / 2f, text)
        }
    }

    private fun ellipsize(s: String, maxW: Float): String {
        if (text.measureText(s) <= maxW) return s
        var end = s.length
        while (end > 1 && text.measureText(s.substring(0, end) + "…") > maxW) end--
        return s.substring(0, end) + "…"
    }

    private fun drawIcon(canvas: Canvas, res: Int, cx: Float, cy: Float, size: Float, color: Int) {
        val d = icon(res)
        val h = size / 2f
        d.setBounds((cx - h).roundToInt(), (cy - h).roundToInt(), (cx + h).roundToInt(), (cy + h).roundToInt())
        d.setTint(color)
        d.draw(canvas)
    }

    private fun actionAt(x: Float): ToolbarShortcut? {
        updateSlots()
        val slot = slots.firstOrNull { x >= it.left && x < it.right } ?: return null
        return slot.shortcut ?: MIC.takeIf { voiceOn }  // Tap the voice status to stop.
    }

    private fun hasSlot(i: Int) = i >= 0 && suggestions.getOrNull(i)?.isNotEmpty() == true

    private fun slotAt(x: Float): Int {
        if (!showingSuggestions) return -1
        updateSlots()
        val slot = textSlot ?: return -1
        if (x < slot.left || x >= slot.right - dp(2f)) return -1
        val e = slotEdges(slot)
        return (0 until 3).firstOrNull { x < e[it + 1] } ?: 2
    }

    private val longPressRunnable = Runnable {
        if (hasSlot(pressedSlot)) {
            longPressed = true
            feedback.keyDown(null, settings, light = true)
            listener?.onSuggestionLongPress(pressedSlot)
            pressedSlot = -1
            invalidate()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressed = false
                pressedSlot = slotAt(event.x)
                pressed = if (pressedSlot >= 0) null else actionAt(event.x)
                if (pressed != null || hasSlot(pressedSlot)) {
                    feedback.keyDown(null, settings)
                }
                if (pressedSlot >= 0) handler.postDelayed(longPressRunnable, 500L)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                val slot = pressedSlot
                val p = pressed
                pressed = null
                pressedSlot = -1
                invalidate()
                if (longPressed) return true
                if (slot >= 0) {
                    if (slotAt(event.x) == slot && hasSlot(slot)) listener?.onSuggestion(slot)
                } else {
                    val a = actionAt(event.x)
                    if (a != null && a == p) listener?.onToolbar(a)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                pressed = null; pressedSlot = -1; invalidate()
            }
        }
        return true
    }
}
