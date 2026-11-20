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
import com.yboard.keyboard.settings.OneHanded
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

    enum class Action { MENU, UNDO, CLIPBOARD, THEMES, EDIT, ONE_HANDED, SPRINKLE, STICKER, MIC }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Keyboard tools"
    }

    interface Listener {
        fun onToolbar(action: Action)
        fun onSuggestion(index: Int) {}
        fun onSuggestionLongPress(index: Int) {}
    }

    var listener: Listener? = null
    var active: Action? = null
        set(v) { field = v; invalidate() }

    private var settings = YboardSettings()
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val feedback = Feedback(context)
    private val handler = Handler(Looper.getMainLooper())
    private var pressed: Action? = null
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

    // Same order and spacing as Gboard's bar (grid, undo, ..., clipboard, ..., mic).
    // One-handed mode lives in the app (Layout & size) and on the keyboard's side strip.
    private val baseItems = listOf(
        Action.MENU to R.drawable.ic_grid,
        Action.UNDO to R.drawable.ic_undo,
        Action.THEMES to R.drawable.ic_palette,
        Action.CLIPBOARD to R.drawable.ic_clipboard,
        Action.EDIT to R.drawable.ic_text_edit,
    )
    /** Plus the ✨ emoji sprinkle button when it's on. */
    private var items = baseItems
    private fun iconSize(res: Int) = dp(if (res == R.drawable.ic_clipboard || res == R.drawable.ic_palette) 22f else 24f)
    private val icons = HashMap<Int, Drawable>()
    private fun icon(res: Int) = icons.getOrPut(res) { ContextCompat.getDrawable(context, res)!!.mutate() }

    fun applySettings(s: YboardSettings) {
        settings = s
        items = if (s.emojiSprinkle) baseItems + (Action.SPRINKLE to R.drawable.ic_emoji_sparkle) else baseItems
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

    private val micR get() = dp(19f)
    private val micCx get() = width - dp(10f) - micR
    private val stickerCx get() = micCx - dp(46f)
    private val toolsRight get() = (if (voiceStatus == null) stickerCx else micCx) - micR - dp(6f)
    private val step get() = toolsRight / items.size
    private val slotsLeft get() = step
    private val slotsRight get() = toolsRight - dp(2f)

    override fun onDraw(canvas: Canvas) {
        val t = settings.theme
        val cy = height / 2f
        val voice = voiceStatus
        if (voice != null) {
            drawToolIcon(canvas, 0, Action.MENU, R.drawable.ic_grid, cy)
            drawVoiceStatus(canvas, voice, cy)
            drawVoiceMic(canvas, cy)
            return
        }
        if (showingSuggestions) {
            drawToolIcon(canvas, 0, Action.MENU, R.drawable.ic_grid, cy)
            drawSuggestions(canvas, cy)
        } else {
            items.forEachIndexed { i, (action, res) -> drawToolIcon(canvas, i, action, res, cy) }
        }
        drawSticker(canvas, cy)
        // Same look as the keys (see-through on see-through themes), like Gboard.
        paint.color = ThemeRender.withAlpha(t.keyColor, if (t.keyOpacity < 0.5f) min(t.keyOpacity * 1.4f, 0.5f) else t.keyOpacity)
        canvas.drawCircle(micCx, cy, micR, paint)
        if (pressed == Action.MIC) {
            paint.color = ThemeRender.withAlpha(t.textColor, 0.15f)
            canvas.drawCircle(micCx, cy, micR, paint)
        }
        drawIcon(canvas, R.drawable.ic_mic, micCx, cy, dp(24f), t.textColor)
    }

    private fun drawSticker(canvas: Canvas, cy: Float) {
        drawToolIconAt(canvas, stickerCx, Action.STICKER, R.drawable.ic_sticker, cy)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = javaClass.name
        if (voiceStatus == null) info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.action_create_sticker, "Create sticker from message"))
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == R.id.action_create_sticker && voiceStatus == null) {
            listener?.onToolbar(Action.STICKER)
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }

    private fun drawVoiceStatus(canvas: Canvas, status: String, cy: Float) {
        val t = settings.theme
        text.typeface = Fonts.medium(context)
        text.textSize = dp(15f)
        text.color = ThemeRender.withAlpha(t.textColor, 0.85f)
        val label = ellipsize(status, slotsRight - slotsLeft - dp(8f))
        val fm = text.fontMetrics
        canvas.drawText(label, (slotsLeft + slotsRight) / 2f, cy - (fm.ascent + fm.descent) / 2f, text)
    }

    /** The mic while listening: accent colored, with a ring that grows when you talk. */
    private fun drawVoiceMic(canvas: Canvas, cy: Float) {
        val t = settings.theme
        if (voiceListening) {
            paint.color = ThemeRender.withAlpha(t.accentColor, 0.28f)
            canvas.drawCircle(micCx, cy, min(micR * (1.04f + 0.2f * voiceLevel), height / 2f), paint)
        }
        paint.color = if (voiceListening) t.accentColor else ThemeRender.withAlpha(t.accentColor, 0.55f)
        canvas.drawCircle(micCx, cy, micR, paint)
        drawIcon(canvas, R.drawable.ic_mic, micCx, cy, dp(24f), t.accentTextColor)
    }

    private fun drawToolIcon(canvas: Canvas, i: Int, action: Action, res: Int, cy: Float) {
        drawToolIconAt(canvas, step * (i + 0.5f), action, res, cy)
    }

    private fun drawToolIconAt(canvas: Canvas, cx: Float, action: Action, res: Int, cy: Float) {
        val t = settings.theme
        val isActive = action == active || (action == Action.ONE_HANDED && settings.oneHanded != OneHanded.OFF)
        if (isActive) {
            paint.color = ThemeRender.withAlpha(t.accentColor, 0.32f)
            canvas.drawCircle(cx, cy, dp(19f), paint)
        } else if (action == pressed) {
            paint.color = ThemeRender.withAlpha(t.textColor, 0.12f)
            canvas.drawCircle(cx, cy, dp(19f), paint)
        }
        drawIcon(canvas, res, cx, cy, iconSize(res), ThemeRender.withAlpha(t.textColor, if (isActive) 1f else 0.92f))
    }

    /** Where the three slots start and end. Equal thirds, unless a long one (like an email) needs more room. */
    private var edges = FloatArray(0)
    /** Strip text size; a bit smaller when long chips (like email endings) wouldn't fit. */
    private var stripTextSize = 0f

    private fun slotEdges(): FloatArray {
        val l = slotsLeft
        val total = slotsRight - l
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

    private fun drawSuggestions(canvas: Canvas, cy: Float) {
        val t = settings.theme
        val e = slotEdges().also { edges = it }
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

    private fun actionAt(x: Float): Action? {
        if (x > micCx - micR - dp(6f)) return Action.MIC
        if (voiceStatus != null) return if (x < slotsLeft) Action.MENU else Action.MIC  // tap the bar to stop
        if (x > toolsRight) return Action.STICKER
        if (showingSuggestions) return if (x < slotsLeft) Action.MENU else null
        val i = (x / step).toInt()
        return items.getOrNull(i)?.first
    }

    private fun hasSlot(i: Int) = i >= 0 && suggestions.getOrNull(i)?.isNotEmpty() == true

    private fun slotAt(x: Float): Int {
        if (!showingSuggestions || x < slotsLeft || x > slotsRight) return -1
        val e = edges.takeIf { it.size == 4 } ?: slotEdges()
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
