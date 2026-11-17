package com.yboard.keyboard.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import com.yboard.keyboard.settings.KeyStyle
import com.yboard.keyboard.settings.KeyboardTheme
import kotlin.math.max
import kotlin.math.min

/** A small picture of a keyboard in a given theme. Used in theme pickers. */
class ThemeTileView(context: Context) : View(context) {

    var theme: KeyboardTheme? = null
        set(v) { field = v; invalidate() }
    var selectedTile = false
        set(v) { field = v; invalidate() }
    var selectionColor = 0xFF6D5BFF.toInt()
        set(v) { field = v; invalidate() }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private val painter = BackgroundPainter()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val clip = Path()
    private val r = RectF()

    override fun onDraw(canvas: Canvas) {
        val t = DynamicColors.resolve(context, theme ?: return)
        val w = width.toFloat()
        val h = height.toFloat()
        val rad = dp(14f)
        clip.reset()
        clip.addRoundRect(RectF(0f, 0f, w, h), rad, rad, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)

        // A colourful "wallpaper" behind see-through themes so the transparency is visible.
        if (t.bgOpacity < 0.98f) {
            paint.shader = LinearGradient(0f, 0f, w, h, 0xFFFF9A8B.toInt(), 0xFF6A88F7.toInt(), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
            paint.color = 0x55FFFFFF
            canvas.drawCircle(w * 0.8f, h * 0.25f, h * 0.35f, paint)
        }
        painter.draw(canvas, width, height, t)

        val pad = w * 0.045f
        val unit = (w - pad * 2) / 10f
        val rowH = (h - pad * 2) / 4f
        val gap = unit * 0.14f
        val keyRad = min(dp(t.cornerRadius) * 0.35f, rowH * 0.4f)
        val rows = listOf(
            List(10) { 1f to 0 },
            List(9) { 1f to 0 },
            listOf(1.5f to 1) + List(7) { 1f to 0 } + listOf(1.5f to 1),
            listOf(1.5f to 1, 1f to 0, 5f to 0, 1f to 0, 1.5f to 2),
        )
        rows.forEachIndexed { ri, row ->
            val total = row.sumOf { it.first.toDouble() }.toFloat()
            var x = pad + (10f - total) * unit / 2f
            val y = pad + ri * rowH
            row.forEach { (kw, kind) ->
                r.set(x + gap / 2, y + gap, x + kw * unit - gap / 2, y + rowH - gap)
                val color = when (kind) {
                    2 -> t.accentColor
                    1 -> ThemeRender.withAlpha(t.specialKeyColor, t.keyOpacity)
                    else -> ThemeRender.withAlpha(t.keyColor, t.keyOpacity)
                }
                val rr = if (kind == 2) min(r.height() / 2, keyRad * 3) else keyRad
                if (kind == 2 || t.keyStyle != KeyStyle.FLAT) {
                    paint.color = color
                    canvas.drawRoundRect(r, rr, rr, paint)
                }
                if (t.keyStyle == KeyStyle.OUTLINED && kind != 2) {
                    stroke.color = t.borderColor
                    stroke.strokeWidth = max(dp(0.8f), dp(t.borderWidth) * 0.6f)
                    canvas.drawRoundRect(r, rr, rr, stroke)
                } else if (kind == 0 && t.keyStyle == KeyStyle.FLAT) {
                    paint.color = ThemeRender.withAlpha(t.textColor, 0.55f)
                    canvas.drawCircle(r.centerX(), r.centerY(), min(r.width(), r.height()) * 0.12f, paint)
                }
                x += kw * unit
            }
        }
        canvas.restore()

        if (selectedTile) {
            stroke.color = selectionColor
            stroke.strokeWidth = dp(3f)
            canvas.drawRoundRect(RectF(dp(1.5f), dp(1.5f), w - dp(1.5f), h - dp(1.5f)), rad, rad, stroke)
        } else {
            stroke.color = 0x22000000
            stroke.strokeWidth = dp(1f)
            canvas.drawRoundRect(RectF(dp(0.5f), dp(0.5f), w - dp(0.5f), h - dp(0.5f)), rad, rad, stroke)
        }
    }
}
