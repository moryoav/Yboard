package com.yboard.keyboard.keyboard

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import com.yboard.keyboard.settings.TrailColor
import com.yboard.keyboard.settings.YboardSettings
import com.yboard.keyboard.text.Pt
import kotlin.math.max

/**
 * Draws the swipe-typing trail: thin and faint at the tail, thick and bright at your finger.
 * Thickness, color (theme / rainbow / your own) and glow come from settings.
 * Used by the keyboard and by the live preview in the app.
 */
class TrailPainter(private val density: Float) {

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        if (Build.VERSION.SDK_INT >= 28) maskFilter = BlurMaskFilter(6f * density, BlurMaskFilter.Blur.NORMAL)
    }
    private val hsv = FloatArray(3)

    /** [fade] 1 = fully visible (fades to 0 after you lift your finger). [timeMs] moves the rainbow. */
    fun draw(canvas: Canvas, trail: List<Pt>, s: YboardSettings, fade: Float, timeMs: Long) {
        val n = trail.size
        if (n < 2 || fade <= 0f) return
        val start = max(1, n - MAX_POINTS)
        val head = widthDp(s) * density
        val tail = head * 0.3f
        // The line is drawn solid (see-through pieces would overlap into dots) and faded as a whole.
        val layer = if (fade < 1f) canvas.saveLayerAlpha(null, (fade * 255).toInt().coerceIn(0, 255)) else -1
        if (s.trailGlow) {
            for (i in start until n) {
                val f = (i - start + 1).toFloat() / (n - start)
                glow.strokeWidth = (tail + (head - tail) * f) * 2.3f
                glow.color = ThemeRender.withAlpha(colorAt(s, i, timeMs), 0.3f * f)
                canvas.drawLine(trail[i - 1].x, trail[i - 1].y, trail[i].x, trail[i].y, glow)
            }
        }
        for (i in start until n) {
            val f = (i - start + 1).toFloat() / (n - start)
            line.strokeWidth = tail + (head - tail) * f
            line.color = colorAt(s, i, timeMs)
            canvas.drawLine(trail[i - 1].x, trail[i - 1].y, trail[i].x, trail[i].y, line)
        }
        if (layer >= 0) canvas.restoreToCount(layer)
    }

    /** The trail's color at point [i] (the rainbow shifts along the line and over time). */
    fun colorAt(s: YboardSettings, i: Int, timeMs: Long): Int = when (s.trailColor) {
        TrailColor.THEME -> s.theme.accentColor
        TrailColor.CUSTOM -> s.trailCustomColor or 0xFF000000.toInt()
        TrailColor.RAINBOW -> {
            hsv[0] = ((timeMs / 8 + i * 6) % 360).toFloat()
            hsv[1] = 0.7f
            hsv[2] = 1f
            Color.HSVToColor(hsv)
        }
    }

    companion object {
        const val MAX_POINTS = 90
        const val MIN_WIDTH = 3f
        const val MAX_WIDTH = 18f

        fun widthDp(s: YboardSettings) = s.trailWidth.coerceIn(MIN_WIDTH, MAX_WIDTH)
    }
}
