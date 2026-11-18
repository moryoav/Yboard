package com.yboard.keyboard.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.PopupWindow
import androidx.core.content.ContextCompat
import com.yboard.keyboard.R
import com.yboard.keyboard.settings.KeyShape
import com.yboard.keyboard.settings.KeyStyle
import com.yboard.keyboard.settings.OneHanded
import com.yboard.keyboard.settings.PressEffect
import com.yboard.keyboard.settings.YboardSettings
import com.yboard.keyboard.text.Pt
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The key grid. Draws every key on a Canvas so every part of the look (colours,
 * transparency, shape, gaps, fonts, effects) can be customised. Also handles swipe typing,
 * swipe-to-delete on backspace, key popups and long-press accents.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onKey(key: KeyDef)
        fun onText(text: String)
        /** Return true if the long-press was handled (emoji, caps lock...). */
        fun onLongPress(key: KeyDef): Boolean
        fun onCursorMove(steps: Int)
        fun onOneHanded(action: Int)
        /** A swipe-typing gesture finished. */
        fun onSwipe(path: List<Pt>, keyCenters: Map<Char, Pt>, keyWidth: Float) {}
        fun onSwipeStart() {}
        /** Finger slid left from backspace: select this many words back (0 = none). */
        fun onBackspaceSwipe(words: Int) {}
        /** Finger lifted after sliding on backspace; [apply] = delete the selected words. */
        fun onBackspaceSwipeEnd(apply: Boolean) {}
    }

    companion object {
        const val ONE_HANDED_EXIT = 0
        const val ONE_HANDED_SWAP = 1
        private const val BASE_ROW_DP = 54f
        private const val NUMBER_ROW_FACTOR = 0.78f
    }

    var listener: Listener? = null
    var settings: YboardSettings = YboardSettings()
        private set
    var layoutDef: LayoutDef = Layouts.build(Mode.LETTERS, "en", false, true)
        private set
    var shiftState = ShiftState.OFF
        set(v) { if (field != v) { field = v; invalidate() } }
    var enterType = EnterType.NEWLINE
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Set by the keyboard service: swipe typing allowed right now (setting on, normal text field). */
    var gestureTyping = false

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private class KeyBox(val key: KeyDef, val rect: RectF, val hit: RectF, val row: Int)

    private val boxes = ArrayList<KeyBox>()
    private val rowTops = ArrayList<Float>()
    private val rowBottoms = ArrayList<Float>()
    private var stripLeft = 0f
    private var stripRight = 0f
    private var letterKeyWidth = 1f

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val trailPainter = TrailPainter(resources.displayMetrics.density)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private var typeface: Typeface = Typeface.DEFAULT
    private val tmpRect = RectF()
    private val clipPath = Path()
    private val hsv = FloatArray(3)

    private val icons = HashMap<Int, Drawable>()
    private fun icon(res: Int): Drawable =
        icons.getOrPut(res) { ContextCompat.getDrawable(context, res)!!.mutate() }

    private val feedback = Feedback(context)
    private val handler = Handler(Looper.getMainLooper())

    init {
        isHapticFeedbackEnabled = false
    }

    // ------------------------------------------------------------------ setup

    fun applySettings(s: YboardSettings) {
        val heightChanged = s.heightScale != settings.heightScale || s.numberRow != settings.numberRow
        settings = s
        typeface = ThemeRender.typeface(context, s.theme)
        textPaint.typeface = typeface
        hintPaint.typeface = typeface
        measureFont()
        updateTiltSensor()
        if (heightChanged) requestLayout()
        computeBoxes(width, height)
        invalidate()
    }

    fun setLayout(l: LayoutDef) {
        cancelAllPointers()
        layoutDef = l
        computeBoxes(width, height)
        invalidate()
    }

    /** Extra height for the keys (space freed when Yboard hides Android's bar under the keyboard). */
    var extraHeight = 0
        set(v) { if (field != v) { field = v; requestLayout() } }

    fun desiredHeight(): Int {
        val pitch = dp(BASE_ROW_DP) * settings.heightScale
        val rows = 4f + if (settings.numberRow) NUMBER_ROW_FACTOR else 0f
        return (pitch * rows + dp(6f) + dp(4f)).roundToInt() + extraHeight
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), desiredHeight())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        computeBoxes(w, h)
    }

    private fun computeBoxes(w: Int, h: Int) {
        boxes.clear(); rowTops.clear(); rowBottoms.clear()
        if (w <= 0 || h <= 0) return
        val theme = settings.theme
        val oneHanded = settings.oneHanded
        val stripW = if (oneHanded != OneHanded.OFF) w * 0.16f else 0f
        val areaL = if (oneHanded == OneHanded.RIGHT) stripW else 0f
        val areaR = if (oneHanded == OneHanded.LEFT) w - stripW else w.toFloat()
        stripLeft = if (oneHanded == OneHanded.RIGHT) 0f else areaR
        stripRight = if (oneHanded == OneHanded.RIGHT) stripW else w.toFloat()
        if (oneHanded == OneHanded.OFF) { stripLeft = 0f; stripRight = 0f }

        val hPad = dp(3f)
        val availW = areaR - areaL - hPad * 2
        val padTop = dp(6f)
        val availH = h - padTop - dp(4f)
        val totalFactor = layoutDef.rows.sumOf { it.heightFactor.toDouble() }.toFloat()
        var y = padTop
        layoutDef.rows.forEachIndexed { ri, row ->
            val rh = availH * row.heightFactor / totalFactor
            val totalW = row.keys.sumOf { it.width.toDouble() }.toFloat()
            val unit = if (totalW > 10f) availW / totalW else availW / 10f
            val grid = theme.keyShape == KeyShape.GRID
            val gapH = if (grid) dp(1f) else min(dp(theme.keyGapH), unit * 0.45f)
            val gapV = if (grid) dp(1f) else min(dp(theme.keyGapV), rh * 0.45f)
            var x = areaL + hPad + (availW - totalW * unit) / 2f
            row.keys.forEach { k ->
                val kw = k.width * unit
                val hit = RectF(x, y, x + kw, y + rh)
                val r = RectF(x + gapH / 2f, y + gapV / 2f, x + kw - gapH / 2f, y + rh - gapV / 2f)
                // Round keys: single keys become circles (wide keys stay pills).
                if (theme.keyShape == KeyShape.ROUND && k.width <= 1.01f) {
                    val side = min(r.width(), r.height())
                    val cx = r.centerX(); val cy = r.centerY()
                    r.set(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f)
                }
                boxes.add(KeyBox(k, r, hit, ri))
                x += kw
            }
            rowTops.add(y); rowBottoms.add(y + rh)
            y += rh
        }
        val letters = boxes.filter { it.key.type == KeyType.CHAR && it.key.width == 1f }
        letterKeyWidth = if (letters.isEmpty()) availW / 10f else letters.map { it.hit.width() }.average().toFloat()
    }

    /** Centers of the single-letter keys on the current layout (for swipe typing). */
    private fun keyCenters(): Map<Char, Pt> {
        val out = HashMap<Char, Pt>()
        for (b in boxes) {
            if (b.key.type == KeyType.CHAR && b.key.label.length == 1) {
                out[b.key.label.lowercase()[0]] = Pt(b.hit.centerX(), b.hit.centerY())
            }
        }
        return out
    }

    // ---------------------------------------------------------------- drawing

    private val pressedBoxes = HashSet<KeyBox>()

    /** A key-press animation (pop / glow / ripple). */
    private class Fx(val box: KeyBox, val start: Long, val x: Float, val y: Float) {
        var release = 0L
    }

    private val effects = ArrayList<Fx>()

    // Swipe-typing trail.
    private val trail = ArrayList<Pt>()
    private var trailEnd = 0L

    override fun onDraw(canvas: Canvas) {
        val t = settings.theme
        val now = SystemClock.uptimeMillis()
        val popping = if (t.pressEffect == PressEffect.POP) effects.map { it.box }.toSet() else emptySet()
        for (b in boxes) if (b !in popping) drawKey(canvas, b, b in pressedBoxes, now)
        if (settings.oneHanded != OneHanded.OFF) drawStrip(canvas)
        val animating = drawEffects(canvas, now)
        val trailing = drawTrail(canvas, now)
        if (animating || trailing) postInvalidateOnAnimation()
        else if (t.rgbLights && isShown) postInvalidateDelayed(33)
    }

    /** Keys drawn in the darker "special" color. Like Gboard: comma/emoji and period too, but not the globe. */
    private fun isFunctional(type: KeyType) = when (type) {
        KeyType.SHIFT, KeyType.BACKSPACE, KeyType.SYMBOLS, KeyType.SYMBOLS2,
        KeyType.LETTERS, KeyType.COMMA, KeyType.PERIOD -> true
        else -> false
    }

    // Letter heights of the current font, as a fraction of the text size. Labels are centered on
    // these (like Gboard) so "a", "l" and "q" share one baseline and sit in the middle of the key.
    private var xHeight = 0.53f
    private var capHeight = 0.72f
    private var hebrewHeight = 0.61f

    private fun measureFont() {
        val p = Paint(textPaint).apply { textSize = 100f }
        val b = Rect()
        fun top(s: String, def: Float): Float {
            p.getTextBounds(s, 0, s.length, b)
            return if (b.height() > 0) -b.top / 100f else def
        }
        xHeight = top("x", 0.53f)
        capHeight = top("H", 0.72f)
        hebrewHeight = top("\u05E9", 0.61f)
    }

    private fun keyRadius(b: KeyBox): Float {
        val t = settings.theme
        val r = b.rect
        val pill = b.key.type == KeyType.ENTER || b.key.type == KeyType.SYMBOLS || b.key.type == KeyType.LETTERS
        val base = dp(t.cornerRadius)
        return when (t.keyShape) {
            KeyShape.SQUARE, KeyShape.GRID -> 0f
            KeyShape.ROUND -> min(r.width(), r.height()) / 2f
            KeyShape.BUBBLE -> min(r.width(), r.height()) * 0.42f
            KeyShape.ROUNDED -> when {
                t.cornerRadius <= 0f -> 0f
                pill -> min(r.height() / 2f, base * 5f)
                else -> min(base, r.height() / 2f)
            }
        }
    }

    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** -1..1: how far the phone is tilted sideways (moves the glass shine). */
    private var glassTilt = 0f

    /** The bright edge of a glass key, lit from the top-left and moving with the tilt. */
    private fun drawGlassRim(canvas: Canvas, r: RectF, radius: Float) {
        val ang = Math.toRadians(45.0 + glassTilt * 40.0)
        val dx = kotlin.math.cos(ang).toFloat()
        val dy = kotlin.math.sin(ang).toFloat()
        val half = (abs(r.width() * dx) + abs(r.height() * dy)) / 2f
        val cx = r.centerX(); val cy = r.centerY()
        rimPaint.shader = android.graphics.LinearGradient(
            cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half,
            intArrayOf(
                ThemeRender.withAlpha(Color.WHITE, 0.9f), ThemeRender.withAlpha(Color.WHITE, 0.2f),
                ThemeRender.withAlpha(Color.WHITE, 0.04f), ThemeRender.withAlpha(Color.WHITE, 0.5f),
            ),
            floatArrayOf(0f, 0.35f, 0.62f, 1f), android.graphics.Shader.TileMode.CLAMP,
        )
        rimPaint.strokeWidth = dp(1.1f)
        tmpRect.set(r); tmpRect.inset(dp(0.55f), dp(0.55f))
        canvas.drawRoundRect(tmpRect, radius, radius, rimPaint)
        rimPaint.shader = null
    }

    // Tilt sensor, only while glass keys are on screen.
    private var tiltOn = false
    private var lastTiltDraw = 0L
    private val tiltListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val g = (-e.values[0] / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)
            val next = glassTilt * 0.8f + g * 0.2f
            val now = SystemClock.uptimeMillis()
            if (abs(next - glassTilt) > 0.004f && now - lastTiltDraw > 33) {
                glassTilt = next
                lastTiltDraw = now
                invalidate()
            } else {
                glassTilt = next
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun updateTiltSensor() {
        val want = settings.theme.glassKeys && isAttachedToWindow && windowVisibility == View.VISIBLE
        if (want == tiltOn) return
        try {
            val sm = context.getSystemService(SensorManager::class.java) ?: return
            if (want) {
                val s = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
                sm.registerListener(tiltListener, s, SensorManager.SENSOR_DELAY_UI)
            } else {
                sm.unregisterListener(tiltListener)
            }
            tiltOn = want
        } catch (_: Exception) {
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateTiltSensor()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateTiltSensor()
    }

    /** Bubble keys: a soft shadow under the key and a shine on its top half. */
    private fun drawBubbleShine(canvas: Canvas, r: RectF, radius: Float, onAccent: Boolean) {
        val top = r.top + dp(1.5f)
        glossPaint.shader = android.graphics.LinearGradient(
            0f, top, 0f, r.top + r.height() * 0.55f,
            ThemeRender.withAlpha(Color.WHITE, if (onAccent) 0.32f else 0.42f), Color.TRANSPARENT,
            android.graphics.Shader.TileMode.CLAMP,
        )
        tmpRect.set(r.left + dp(2.5f), top, r.right - dp(2.5f), r.top + r.height() * 0.55f)
        val rr = max(0f, radius - dp(2.5f))
        canvas.drawRoundRect(tmpRect, rr, rr, glossPaint)
        glossPaint.shader = null
    }

    private fun drawKey(canvas: Canvas, b: KeyBox, pressed: Boolean, now: Long) {
        val t = settings.theme
        val k = b.key
        val r = b.rect
        val isEnter = k.type == KeyType.ENTER
        val functional = isFunctional(k.type)
        val radius = keyRadius(b)

        val accentKey = isEnter && t.accentEnter
        val fill = when {
            accentKey -> t.accentColor
            functional || isEnter -> ThemeRender.withAlpha(t.specialKeyColor, t.keyOpacity)
            else -> ThemeRender.withAlpha(t.keyColor, t.keyOpacity)
        }
        val drawFill = accentKey || t.keyStyle != KeyStyle.FLAT

        if ((t.keyShadow || t.keyShape == KeyShape.BUBBLE) && drawFill && t.keyStyle == KeyStyle.FILLED) {
            fillPaint.color = ThemeRender.withAlpha(Color.BLACK, 0.22f * max(t.keyOpacity, 0.4f))
            tmpRect.set(r.left, r.top + dp(1.3f), r.right, r.bottom + dp(1.3f))
            canvas.drawRoundRect(tmpRect, radius, radius, fillPaint)
        }
        if (drawFill && t.glassKeys) {
            // Glass: a little brighter at the top, clearer at the bottom.
            val a = Color.alpha(fill) / 255f
            glassPaint.shader = android.graphics.LinearGradient(
                0f, r.top, 0f, r.bottom,
                ThemeRender.withAlpha(fill, min(1f, a * 1.5f)), ThemeRender.withAlpha(fill, a * 0.7f),
                android.graphics.Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(r, radius, radius, glassPaint)
            glassPaint.shader = null
        } else if (drawFill) {
            fillPaint.color = fill
            canvas.drawRoundRect(r, radius, radius, fillPaint)
        }
        if (t.glassKeys) drawGlassRim(canvas, r, radius)
        val borderW = when {
            t.keyStyle == KeyStyle.OUTLINED -> max(dp(t.borderWidth), dp(1f))
            t.borderWidth > 0f && t.keyStyle == KeyStyle.FILLED -> dp(t.borderWidth)
            else -> 0f
        }
        if (borderW > 0f && !accentKey) {
            strokePaint.strokeWidth = borderW
            strokePaint.color = t.borderColor
            tmpRect.set(r); tmpRect.inset(borderW / 2f, borderW / 2f)
            canvas.drawRoundRect(tmpRect, radius, radius, strokePaint)
        }
        if (t.rgbLights) {
            // Rainbow that flows across the keyboard over time.
            val w = max(width, 1).toFloat()
            hsv[0] = ((r.centerX() / w) * 300f + (r.centerY() / max(height, 1)) * 60f + now * 0.12f) % 360f
            hsv[1] = 0.85f; hsv[2] = 1f
            strokePaint.strokeWidth = dp(1.6f)
            strokePaint.color = Color.HSVToColor(hsv)
            tmpRect.set(r); tmpRect.inset(dp(0.8f), dp(0.8f))
            canvas.drawRoundRect(tmpRect, radius, radius, strokePaint)
        }
        if (t.keyShape == KeyShape.BUBBLE && drawFill) drawBubbleShine(canvas, r, radius, accentKey)
        if (pressed) {
            fillPaint.color = ThemeRender.withAlpha(if (accentKey) t.accentTextColor else t.textColor, 0.17f)
            canvas.drawRoundRect(r, radius, radius, fillPaint)
        }

        val fg = if (accentKey) t.accentTextColor else t.textColor
        val h = r.height()
        val cx = r.centerX()
        val cy = r.centerY()
        val fs = t.fontScale
        // Icon sizes are boxes around the 24dp vector icons, sized to match Gboard's key icons.
        val maxIcon = dp(34f)
        when (k.type) {
            KeyType.CHAR -> {
                val label = displayLabel(k)
                val size = when {
                    layoutDef.rows.firstOrNull()?.keys?.size == 4 -> h * 0.46f
                    label.length > 1 -> h * 0.36f
                    else -> min(h * 0.55f, r.width() * 0.8f)
                } * fs
                drawLabel(canvas, label, cx, cy, size, fg)
                if (settings.showHints && k.hint != null) drawHint(canvas, k.hint, r)
            }
            KeyType.PERIOD -> drawPeriod(canvas, ".", r, fg)
            KeyType.COMMA -> {
                if (layoutDef.letters) {
                    drawIcon(canvas, R.drawable.ic_emoji, cx, r.top + h * 0.33f, min(h * 0.32f, dp(17f)), fg)
                    drawText(canvas, ",", cx, r.top + h * 0.71f, h * 0.36f * fs, fg)
                } else {
                    drawPeriod(canvas, ",", r, fg)
                }
            }
            KeyType.SPACE -> {
                if (layoutDef.letters) {
                    drawLabel(canvas, Layouts.languageName(layoutDef.language), cx, cy, h * 0.3f * fs, fg)
                }
            }
            KeyType.SHIFT -> {
                val res = when (shiftState) {
                    ShiftState.OFF -> R.drawable.ic_shift
                    ShiftState.ON -> R.drawable.ic_shift_on
                    ShiftState.LOCKED -> R.drawable.ic_shift_lock
                }
                drawIcon(canvas, res, cx, cy, min(h * 0.64f, maxIcon), fg)
            }
            KeyType.BACKSPACE -> drawIcon(canvas, R.drawable.ic_backspace, cx, cy, min(h * 0.6f, maxIcon), fg)
            KeyType.GLOBE -> drawIcon(canvas, R.drawable.ic_globe, cx, cy, min(h * 0.52f, dp(28f)), fg)
            KeyType.ENTER -> {
                val res = when (enterType) {
                    EnterType.NEWLINE -> R.drawable.ic_enter
                    EnterType.GO -> R.drawable.ic_arrow_forward
                    EnterType.NEXT -> R.drawable.ic_tab
                    EnterType.SEARCH -> R.drawable.ic_search
                    EnterType.SEND -> R.drawable.ic_send
                    EnterType.DONE -> R.drawable.ic_done
                }
                drawIcon(canvas, res, cx, cy, min(h * 0.6f, maxIcon), fg)
            }
            KeyType.SYMBOLS, KeyType.SYMBOLS2, KeyType.LETTERS ->
                drawLabel(canvas, k.label, cx, cy, h * 0.34f * fs, fg)
        }
    }

    /** Draws pop / glow / ripple animations. Returns true while something is still animating. */
    private fun drawEffects(canvas: Canvas, now: Long): Boolean {
        if (effects.isEmpty()) return false
        val t = settings.theme
        val iter = effects.iterator()
        while (iter.hasNext()) {
            val fx = iter.next()
            val held = fx.release == 0L
            val sinceDown = (now - fx.start).toFloat()
            val sinceUp = if (held) 0f else (now - fx.release).toFloat()
            val r = fx.box.rect
            val radius = keyRadius(fx.box)
            when (t.pressEffect) {
                PressEffect.POP -> {
                    val up = min(sinceDown / 70f, 1f)
                    val down = if (held) 0f else min(sinceUp / 130f, 1f)
                    val scale = 1f + 0.13f * up * (1f - down)
                    canvas.save()
                    canvas.scale(scale, scale, r.centerX(), r.centerY())
                    drawKey(canvas, fx.box, held, now)
                    canvas.restore()
                    if (!held && down >= 1f) iter.remove()
                }
                PressEffect.GLOW -> {
                    val a = min(sinceDown / 60f, 1f) * (if (held) 1f else (1f - min(sinceUp / 260f, 1f)))
                    for (i in 1..4) {
                        tmpRect.set(r); tmpRect.inset(-dp(1.6f * i), -dp(1.6f * i))
                        fillPaint.color = ThemeRender.withAlpha(t.accentColor, a * (0.30f - i * 0.06f))
                        canvas.drawRoundRect(tmpRect, radius + dp(1.6f * i), radius + dp(1.6f * i), fillPaint)
                    }
                    strokePaint.strokeWidth = dp(1.6f)
                    strokePaint.color = ThemeRender.withAlpha(t.accentColor, a)
                    canvas.drawRoundRect(r, radius, radius, strokePaint)
                    if (!held && sinceUp >= 260f) iter.remove()
                }
                PressEffect.RIPPLE -> {
                    val life = 420f
                    val p = min(sinceDown / life, 1f)
                    val maxR = hypot(r.width(), r.height())
                    clipPath.reset()
                    clipPath.addRoundRect(r, radius, radius, Path.Direction.CW)
                    canvas.save()
                    canvas.clipPath(clipPath)
                    fillPaint.color = ThemeRender.withAlpha(t.accentColor, 0.45f * (1f - p))
                    canvas.drawCircle(fx.x, fx.y, maxR * (0.15f + 0.85f * p), fillPaint)
                    canvas.restore()
                    if (p >= 1f && !held) iter.remove()
                    if (p >= 1f && held) { /* keep the key lit while held */
                        fillPaint.color = ThemeRender.withAlpha(t.accentColor, 0.12f)
                        canvas.drawRoundRect(r, radius, radius, fillPaint)
                    }
                }
                PressEffect.NONE -> iter.remove()
            }
        }
        return effects.isNotEmpty()
    }

    /** Screenshots: show a swipe trail along [points] (as if the finger were still down). */
    internal fun trailForTests(points: List<Pt>) {
        trail.clear()
        trail.addAll(points)
        trailEnd = 0L
        invalidate()
    }

    private fun drawTrail(canvas: Canvas, now: Long): Boolean {
        if (trail.size < 2 || !settings.swipeTrail) return false
        val fade = if (trailEnd == 0L) 1f else 1f - min((now - trailEnd) / 220f, 1f)
        if (fade <= 0f) { trail.clear(); return false }
        trailPainter.draw(canvas, trail, settings, fade, now)
        // A rainbow keeps moving while you swipe.
        return trailEnd != 0L || settings.trailColor == com.yboard.keyboard.settings.TrailColor.RAINBOW
    }

    private fun displayLabel(k: KeyDef): String =
        if (layoutDef.letters && layoutDef.language == "en" && shiftState != ShiftState.OFF && k.label.length == 1) k.label.uppercase() else k.label

    private fun drawText(canvas: Canvas, text: String, cx: Float, cy: Float, size: Float, color: Int) {
        textPaint.textSize = size
        textPaint.color = color
        val fm = textPaint.fontMetrics
        canvas.drawText(text, cx, cy - (fm.ascent + fm.descent) / 2f, textPaint)
    }

    /** Draws a key label centered on its letter height (x-height for lowercase, like Gboard). */
    private fun drawLabel(canvas: Canvas, text: String, cx: Float, cy: Float, size: Float, color: Int) {
        val ref = when {
            text.any { it in '\u0590'..'\u05FF' } -> hebrewHeight
            text.none { it.isLetterOrDigit() } -> return drawText(canvas, text, cx, cy, size, color)
            text.any { it.isUpperCase() || it.isDigit() } -> capHeight
            else -> xHeight
        }
        textPaint.textSize = size
        textPaint.color = color
        canvas.drawText(text, cx, cy + ref * size / 2f, textPaint)
    }

    /** Period and comma sit low in the key, on the text baseline, like Gboard. */
    private fun drawPeriod(canvas: Canvas, text: String, r: RectF, color: Int) {
        textPaint.textSize = r.height() * 0.5f * settings.theme.fontScale
        textPaint.color = color
        canvas.drawText(text, r.centerX(), r.top + r.height() * 0.73f, textPaint)
    }

    private fun drawHint(canvas: Canvas, hint: String, r: RectF) {
        val h = r.height()
        hintPaint.textSize = h * 0.22f * settings.theme.fontScale
        hintPaint.color = ThemeRender.withAlpha(settings.theme.textColor, 0.85f)
        canvas.drawText(hint, r.right - h * 0.1f, r.top + h * 0.1f + hintPaint.textSize * capHeight, hintPaint)
    }

    private fun drawIcon(canvas: Canvas, res: Int, cx: Float, cy: Float, size: Float, color: Int) {
        val d = icon(res)
        val half = size / 2f
        d.setBounds((cx - half).roundToInt(), (cy - half).roundToInt(), (cx + half).roundToInt(), (cy + half).roundToInt())
        d.setTint(color)
        d.draw(canvas)
    }

    private fun drawStrip(canvas: Canvas) {
        val t = settings.theme
        val cx = (stripLeft + stripRight) / 2f
        val midY = height / 2f
        val rad = min(dp(21f), (stripRight - stripLeft) * 0.38f)
        listOf(midY - dp(30f) to R.drawable.ic_expand, midY + dp(30f) to R.drawable.ic_swap).forEach { (y, res) ->
            fillPaint.color = ThemeRender.withAlpha(t.specialKeyColor, max(t.keyOpacity, 0.5f))
            canvas.drawCircle(cx, y, rad, fillPaint)
            drawIcon(canvas, res, cx, y, rad * 0.95f, t.textColor)
        }
    }

    // ------------------------------------------------------------ popups

    private inner class BubbleView : View(context) {
        var label = ""
        override fun onDraw(canvas: Canvas) {
            val t = settings.theme
            val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
            val rad = min(dp(max(t.cornerRadius, 6f) * 1.4f), width / 2f)
            fillPaint.color = ThemeRender.withAlpha(Color.BLACK, 0.18f)
            canvas.drawRoundRect(RectF(r.left, r.top + dp(2f), r.right, r.bottom), rad, rad, fillPaint)
            fillPaint.color = popupColor()
            canvas.drawRoundRect(RectF(r.left, r.top, r.right, r.bottom - dp(2f)), rad, rad, fillPaint)
            textPaint.textSize = height * 0.5f * t.fontScale
            textPaint.color = t.textColor
            val fm = textPaint.fontMetrics
            canvas.drawText(label, width / 2f, (height - dp(2f)) / 2f - (fm.ascent + fm.descent) / 2f, textPaint)
        }
    }

    private inner class AltView : View(context) {
        var items: List<String> = emptyList()
        var cols = 1
        var rows = 1
        var cellW = 0f
        var cellH = 0f
        var pad = 0f
        var selected = 0

        fun itemAt(row: Int, col: Int): Int {
            // Bottom row holds the first items so the most likely choice is closest to the finger.
            val idx = (rows - 1 - row) * cols + col
            return if (idx < items.size) idx else -1
        }

        override fun onDraw(canvas: Canvas) {
            val t = settings.theme
            val rad = dp(max(t.cornerRadius, 6f) * 1.3f)
            fillPaint.color = ThemeRender.withAlpha(Color.BLACK, 0.2f)
            canvas.drawRoundRect(RectF(0f, dp(2f), width.toFloat(), height.toFloat()), rad, rad, fillPaint)
            fillPaint.color = popupColor()
            canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height - dp(2f)), rad, rad, fillPaint)
            for (row in 0 until rows) for (col in 0 until cols) {
                val i = itemAt(row, col)
                if (i < 0) continue
                val l = pad + col * cellW
                val tp = pad + row * cellH
                val cell = RectF(l + dp(2f), tp + dp(2f), l + cellW - dp(2f), tp + cellH - dp(2f))
                var fg = t.textColor
                if (i == selected) {
                    fillPaint.color = t.accentColor
                    canvas.drawRoundRect(cell, rad * 0.7f, rad * 0.7f, fillPaint)
                    fg = t.accentTextColor
                }
                drawText(canvas, items[i], cell.centerX(), cell.centerY(), cellH * 0.45f * t.fontScale, fg)
            }
        }
    }

    private fun popupColor(): Int {
        val t = settings.theme
        // Popups are always solid so they stay readable on see-through themes.
        val base = if (t.keyOpacity < 0.5f || t.keyStyle == KeyStyle.FLAT) {
            if (ThemeRender.isLight(t.textColor)) Color.rgb(52, 52, 58) else Color.WHITE
        } else t.keyColor
        return ThemeRender.withAlpha(base, 1f)
    }

    private val bubble = BubbleView()
    private val bubblePopup = PopupWindow(bubble).apply {
        isClippingEnabled = false
        isTouchable = false
        isFocusable = false
        animationStyle = 0
        setBackgroundDrawable(null)
        inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
    }
    private val altView = AltView()
    private val altPopup = PopupWindow(altView).apply {
        isClippingEnabled = false
        isTouchable = false
        isFocusable = false
        animationStyle = 0
        setBackgroundDrawable(null)
        inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
    }
    private var altLeft = 0f
    private var altTop = 0f
    private val loc = IntArray(2)

    private fun showPreview(b: KeyBox) {
        if (!settings.keyPopup || windowToken == null) return
        if (b.key.type != KeyType.CHAR && b.key.type != KeyType.COMMA && b.key.type != KeyType.PERIOD) {
            hidePreview(); return
        }
        bubble.label = displayLabel(b.key)
        bubble.invalidate()
        val w = max(b.rect.width() + dp(8f), dp(40f)).roundToInt()
        val h = (b.rect.height() * 1.25f + dp(6f)).roundToInt()
        getLocationInWindow(loc)
        val x = (loc[0] + b.rect.centerX() - w / 2f).roundToInt()
        val y = (loc[1] + b.rect.top - h - dp(5f)).roundToInt()
        try {
            if (bubblePopup.isShowing) bubblePopup.update(x, y, w, h)
            else {
                bubblePopup.width = w; bubblePopup.height = h
                bubblePopup.showAtLocation(this, Gravity.TOP or Gravity.LEFT, x, y)
            }
        } catch (_: Exception) {
        }
    }

    private fun hidePreview() {
        if (bubblePopup.isShowing) try { bubblePopup.dismiss() } catch (_: Exception) {}
    }

    private fun showAlternates(b: KeyBox, items: List<String>, fingerX: Float) {
        if (windowToken == null) return
        val upper = layoutDef.letters && layoutDef.language == "en" && shiftState != ShiftState.OFF
        altView.items = if (upper) items.map { if (it.length == 1) it.uppercase() else it } else items
        altView.cols = min(items.size, 8)
        altView.rows = ceil(items.size / altView.cols.toFloat()).toInt()
        altView.cellW = max(min(b.rect.width(), dp(46f)), dp(34f))
        altView.cellH = min(b.rect.height(), dp(50f))
        altView.pad = dp(5f)
        val w = altView.cols * altView.cellW + altView.pad * 2
        val h = altView.rows * altView.cellH + altView.pad * 2 + dp(2f)
        altLeft = (b.rect.centerX() - altView.cellW / 2f - altView.pad)
            .coerceAtMost(width - w - dp(2f)).coerceAtLeast(dp(2f))
        altTop = b.rect.top - h - dp(4f)
        altView.selected = 0
        updateAltSelection(fingerX, b.rect.centerY())
        getLocationInWindow(loc)
        try {
            altPopup.width = w.roundToInt(); altPopup.height = h.roundToInt()
            altPopup.showAtLocation(this, Gravity.TOP or Gravity.LEFT,
                (loc[0] + altLeft).roundToInt(), (loc[1] + altTop).roundToInt())
        } catch (_: Exception) {
        }
    }

    private fun updateAltSelection(x: Float, y: Float) {
        val av = altView
        val col = ((x - altLeft - av.pad) / av.cellW).toInt().coerceIn(0, av.cols - 1)
        val row = ((y - altTop - av.pad) / av.cellH).toInt().coerceIn(0, av.rows - 1)
        var idx = av.itemAt(row, col)
        if (idx < 0) idx = av.itemAt(av.rows - 1, col)
        if (idx >= 0 && idx != av.selected) {
            av.selected = idx
            av.invalidate()
        }
    }

    private fun hideAlternates() {
        if (altPopup.isShowing) try { altPopup.dismiss() } catch (_: Exception) {}
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelAllPointers()
        updateTiltSensor()
    }

    // ------------------------------------------------------------- touch

    private inner class Pointer(var box: KeyBox?, val downX: Float, val downY: Float) {
        var consumed = false
        var altMode = false
        var cursorMode = false
        var lastCursorX = downX
        var lastX = downX
        var strip = -1
        var longPress: Runnable? = null
        var gesture = false
        var bsSwipe = false
        var bsSteps = 0
        var fx: Fx? = null
    }

    private val pointers = HashMap<Int, Pointer>()
    private var repeatKey: KeyDef? = null
    private var repeatPointer: Pointer? = null
    private var repeatCount = 0
    private val repeatRunnable = object : Runnable {
        override fun run() {
            val k = repeatKey ?: return
            repeatPointer?.consumed = true
            listener?.onKey(k)
            feedback.keyDown(k.type, settings, light = true)
            repeatCount++
            handler.postDelayed(this, if (repeatCount > 12) 32L else 60L)
        }
    }

    private fun isRolling(type: KeyType?) =
        type == KeyType.CHAR || type == KeyType.COMMA || type == KeyType.PERIOD || type == KeyType.SPACE

    private fun findKey(x: Float, y: Float): KeyBox? {
        if (boxes.isEmpty()) return null
        var row = rowTops.indices.firstOrNull { y < rowBottoms[it] } ?: (rowTops.size - 1)
        if (y < 0) row = 0
        var best: KeyBox? = null
        var bestD = Float.MAX_VALUE
        for (b in boxes) {
            if (b.row != row) continue
            if (x >= b.hit.left && x < b.hit.right) return b
            val d = min(abs(x - b.hit.left), abs(x - b.hit.right))
            if (d < bestD) { bestD = d; best = b }
        }
        return best
    }

    private fun inStrip(x: Float) = settings.oneHanded != OneHanded.OFF && x >= stripLeft && x < stripRight
    private fun stripButtonAt(y: Float) = if (y < height / 2f) ONE_HANDED_EXIT else ONE_HANDED_SWAP

    private fun gestureActive() = pointers.values.any { it.gesture }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (gestureActive()) return true  // ignore extra fingers mid-swipe
                val i = ev.actionIndex
                // Fast "rolling" typing: a new finger commits the previous key right away.
                for (p in pointers.values) {
                    if (!p.consumed && !p.altMode && !p.cursorMode && !p.gesture && isRolling(p.box?.key?.type)) {
                        p.box?.let { listener?.onKey(it.key) }
                        p.consumed = true
                        cancelLongPress(p)
                        p.box?.let { pressedBoxes.remove(it) }
                        p.fx?.release = SystemClock.uptimeMillis()
                    }
                }
                onDown(ev.getPointerId(i), ev.getX(i), ev.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until ev.pointerCount) {
                    val id = ev.getPointerId(i)
                    val p = pointers[id]
                    // Historical samples can contain the first turn before glide is detected.
                    // Feed them through detection too, rather than dropping that part of the word.
                    if (p != null) for (h in 0 until ev.historySize) {
                        onMove(id, ev.getHistoricalX(i, h), ev.getHistoricalY(i, h))
                    }
                    onMove(id, ev.getX(i), ev.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = ev.actionIndex
                for (h in 0 until ev.historySize) {
                    onMove(ev.getPointerId(i), ev.getHistoricalX(i, h), ev.getHistoricalY(i, h))
                }
                onUp(ev.getPointerId(i), ev.getX(i), ev.getY(i))
            }
            MotionEvent.ACTION_CANCEL -> cancelAllPointers()
        }
        return true
    }

    private fun onDown(id: Int, x: Float, y: Float) {
        if (inStrip(x)) {
            pointers[id] = Pointer(null, x, y).apply { strip = stripButtonAt(y) }
            feedback.keyDown(null, settings)
            return
        }
        val b = findKey(x, y) ?: return
        val p = Pointer(b, x, y)
        pointers[id] = p
        pressedBoxes.add(b)
        feedback.keyDown(b.key.type, settings)
        if (settings.theme.pressEffect != PressEffect.NONE) {
            val fx = Fx(b, SystemClock.uptimeMillis(), x, y)
            effects.add(fx)
            p.fx = fx
        }
        when (b.key.type) {
            KeyType.BACKSPACE -> {
                if (!settings.backspaceSwipe) {
                    listener?.onKey(b.key)
                    p.consumed = true
                }
                // Otherwise one tap deletes on release, so a slide can select words instead.
                repeatKey = b.key
                repeatPointer = p
                repeatCount = 0
                handler.removeCallbacks(repeatRunnable)
                handler.postDelayed(repeatRunnable, 380L)
            }
            KeyType.SHIFT -> {
                listener?.onKey(b.key)
                p.consumed = true
            }
            else -> showPreview(b)
        }
        scheduleLongPress(p)
        invalidate()
    }

    private fun scheduleLongPress(p: Pointer) {
        cancelLongPress(p)
        val r = Runnable { onLongPress(p) }
        p.longPress = r
        val delay = settings.longPressDelay.toLong() * if (p.box?.key?.type == KeyType.SPACE) 2 else 1
        handler.postDelayed(r, delay)
    }

    private fun cancelLongPress(p: Pointer) {
        p.longPress?.let { handler.removeCallbacks(it) }
        p.longPress = null
    }

    private fun onLongPress(p: Pointer) {
        p.longPress = null
        val b = p.box ?: return
        val k = b.key
        if (p.cursorMode || p.altMode || p.gesture || p.bsSwipe) return
        when {
            k.type == KeyType.BACKSPACE -> Unit
            k.type == KeyType.SHIFT -> listener?.onLongPress(k)
            (k.type == KeyType.CHAR || k.type == KeyType.PERIOD) && k.alternates.isNotEmpty() && !p.consumed -> {
                hidePreview()
                p.altMode = true
                feedback.keyDown(null, settings, light = true)
                showAlternates(b, k.alternates, p.lastX)
            }
            !p.consumed -> {
                if (listener?.onLongPress(k) == true) {
                    p.consumed = true
                    hidePreview()
                    pressedBoxes.remove(b)
                    invalidate()
                }
            }
        }
    }

    private fun onMove(id: Int, x: Float, y: Float) {
        val p = pointers[id] ?: return
        p.lastX = x
        if (p.strip >= 0) return
        if (p.altMode) { updateAltSelection(x, y); return }
        val b = p.box ?: return

        // Swipe typing.
        if (p.gesture) {
            trail.add(Pt(x, y))
            invalidate()
            return
        }
        if (gestureTyping && layoutDef.letters && pointers.size == 1 && !p.consumed &&
            b.key.type == KeyType.CHAR && hypot(x - p.downX, y - p.downY) > max(dp(26f), letterKeyWidth * 0.7f)
        ) {
            p.gesture = true
            listener?.onSwipeStart()
            cancelLongPress(p)
            hidePreview()
            pressedBoxes.clear()
            p.fx?.release = SystemClock.uptimeMillis()
            trail.clear()
            trailEnd = 0L
            trail.add(Pt(p.downX, p.downY))
            trail.add(Pt(x, y))
            invalidate()
            return
        }

        // Slide left on backspace to select whole words.
        if (b.key.type == KeyType.BACKSPACE && settings.backspaceSwipe && repeatCount == 0) {
            val dx = p.downX - x
            if (!p.bsSwipe && dx > dp(22f)) {
                p.bsSwipe = true
                p.consumed = true
                handler.removeCallbacks(repeatRunnable)
            }
            if (p.bsSwipe) {
                val steps = max(0, ((dx - dp(8f)) / dp(26f)).toInt() + if (dx > dp(22f)) 1 else 0)
                if (steps != p.bsSteps) {
                    p.bsSteps = steps
                    listener?.onBackspaceSwipe(steps)
                    feedback.keyDown(null, settings, light = true)
                }
            }
            return
        }

        if (b.key.type == KeyType.SPACE && settings.spaceCursor && !p.consumed) {
            if (!p.cursorMode && abs(x - p.downX) > dp(16f)) {
                p.cursorMode = true
                p.lastCursorX = x
                cancelLongPress(p)
            }
            if (p.cursorMode) {
                val step = dp(11f)
                val n = ((x - p.lastCursorX) / step).toInt()
                if (n != 0) {
                    listener?.onCursorMove(n)
                    p.lastCursorX += n * step
                    feedback.keyDown(null, settings, light = true)
                }
            }
            return
        }
        if (p.consumed || b.key.type == KeyType.BACKSPACE) return
        val nb = findKey(x, y) ?: return
        if (nb !== b && isRolling(nb.key.type) && nb.key.type != KeyType.SPACE &&
            isRolling(b.key.type) && b.key.type != KeyType.SPACE
        ) {
            pressedBoxes.remove(b)
            pressedBoxes.add(nb)
            p.box = nb
            scheduleLongPress(p)
            showPreview(nb)
            invalidate()
        }
    }

    private fun onUp(id: Int, x: Float, y: Float) {
        val p = pointers.remove(id) ?: return
        cancelLongPress(p)
        p.box?.let { pressedBoxes.remove(it) }
        p.fx?.release = SystemClock.uptimeMillis()
        val wasBackspace = p.box?.key?.type == KeyType.BACKSPACE
        if (wasBackspace) {
            handler.removeCallbacks(repeatRunnable)
            repeatKey = null
            repeatPointer = null
        }
        when {
            p.gesture -> {
                trail.add(Pt(x, y))
                trailEnd = SystemClock.uptimeMillis()
                listener?.onSwipe(trail.toList(), keyCenters(), letterKeyWidth)
            }
            p.strip >= 0 -> if (inStrip(x) && stripButtonAt(y) == p.strip) listener?.onOneHanded(p.strip)
            p.altMode -> {
                val sel = altView.items.getOrNull(altView.selected)
                hideAlternates()
                if (sel != null) listener?.onText(sel)
            }
            p.bsSwipe -> listener?.onBackspaceSwipeEnd(p.bsSteps > 0)
            !p.consumed && !p.cursorMode -> p.box?.let { listener?.onKey(it.key) }
        }
        repeatCount = 0
        val other = pointers.values.lastOrNull { it.box != null && !it.consumed && !it.altMode && !it.gesture }
        if (other?.box != null) showPreview(other.box!!) else hidePreview()
        invalidate()
    }

    fun cancelAllPointers() {
        pointers.values.forEach { cancelLongPress(it) }
        pointers.clear()
        pressedBoxes.clear()
        handler.removeCallbacks(repeatRunnable)
        repeatKey = null
        repeatPointer = null
        repeatCount = 0
        trail.clear()
        hidePreview()
        hideAlternates()
        invalidate()
    }
}
