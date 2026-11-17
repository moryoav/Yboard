package com.yboard.keyboard.keyboard

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import com.yboard.keyboard.settings.BgMotion
import com.yboard.keyboard.settings.KeyboardTheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Moving backgrounds, drawn behind the keys: soft flowing color, falling snow, twinkling stars,
 * rising bubbles. Positions come from the time alone (no stored state), so it's cheap and smooth.
 */
class MotionPainter(private val density: Float) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private class Particle(val x: Float, val y: Float, val size: Float, val speed: Float, val phase: Float, val alpha: Float)

    private var forW = 0
    private var forH = 0
    private var forMotion = BgMotion.NONE
    private var particles: List<Particle> = emptyList()
    private var blobs: List<Pair<RadialGradient, Float>> = emptyList()
    private var blobKey = 0

    private fun dp(v: Float) = v * density

    /** Draws one frame for time [ms]. */
    fun draw(canvas: Canvas, w: Int, h: Int, theme: KeyboardTheme, ms: Long) {
        if (w <= 0 || h <= 0 || theme.bgMotion == BgMotion.NONE) return
        prepare(w, h, theme)
        val t = ms / 1000f
        // Light backgrounds get dark particles so they still show.
        val ink = if (ThemeRender.isLight(ThemeRender.dominantColor(theme)) && theme.bgOpacity > 0.4f) theme.textColor else Color.WHITE
        when (theme.bgMotion) {
            BgMotion.FLOW -> flow(canvas, w, h, t)
            BgMotion.SNOW -> snow(canvas, w, h, t, ink)
            BgMotion.STARS -> stars(canvas, w, h, t, ink)
            BgMotion.BUBBLES -> bubbles(canvas, w, h, t, ink)
            BgMotion.NONE -> Unit
        }
    }

    private fun prepare(w: Int, h: Int, theme: KeyboardTheme) {
        if (w != forW || h != forH || theme.bgMotion != forMotion) {
            forW = w; forH = h; forMotion = theme.bgMotion
            val rnd = Random(42)
            val count = when (theme.bgMotion) {
                BgMotion.SNOW -> (w * h / (dp(48f) * dp(48f))).toInt().coerceIn(30, 110)
                BgMotion.STARS -> (w * h / (dp(40f) * dp(40f))).toInt().coerceIn(30, 120)
                BgMotion.BUBBLES -> (w / dp(28f)).toInt().coerceIn(8, 22)
                else -> 0
            }
            particles = List(count) {
                Particle(
                    x = rnd.nextFloat() * w, y = rnd.nextFloat() * h,
                    size = 0.3f + rnd.nextFloat() * 0.7f, speed = 0.5f + rnd.nextFloat(),
                    phase = rnd.nextFloat() * 2f * PI.toFloat(), alpha = 0.35f + rnd.nextFloat() * 0.6f,
                )
            }
        }
        // Flow: three big soft color glows from the theme's own colors.
        val key = (theme.accentColor * 31 + (theme.bgColorMid ?: theme.bgColor2)) * 31 + theme.keyColor + w * 7 + h
        if (theme.bgMotion == BgMotion.FLOW && (blobs.isEmpty() || key != blobKey)) {
            blobKey = key
            val r = max(w, h) * 0.55f
            val colors = listOf(theme.accentColor, shiftHue(theme.accentColor, 70f), theme.bgColorMid ?: shiftHue(theme.accentColor, -60f))
            blobs = colors.map { c ->
                RadialGradient(0f, 0f, r, ThemeRender.withAlpha(c, 0.55f), Color.TRANSPARENT, Shader.TileMode.CLAMP) to r
            }
        }
    }

    private fun shiftHue(c: Int, deg: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[0] = (hsv[0] + deg + 360f) % 360f
        hsv[1] = max(hsv[1], 0.45f)
        hsv[2] = max(hsv[2], 0.6f)
        return Color.HSVToColor(hsv)
    }

    private fun flow(canvas: Canvas, w: Int, h: Int, t: Float) {
        blobs.forEachIndexed { i, (shader, r) ->
            val sp = 0.07f + i * 0.023f
            val x = w * (0.5f + 0.42f * sin(t * sp * 2f * PI.toFloat() + i * 2.1f))
            val y = h * (0.5f + 0.38f * sin(t * sp * 1.3f * 2f * PI.toFloat() + i * 1.3f + 1f))
            paint.shader = shader
            canvas.save()
            canvas.translate(x, y)
            canvas.drawCircle(0f, 0f, r, paint)
            canvas.restore()
        }
        paint.shader = null
    }

    private fun snow(canvas: Canvas, w: Int, h: Int, t: Float, ink: Int) {
        val fall = dp(26f)
        for (p in particles) {
            val total = h + dp(10f)
            val y = ((p.y + t * fall * p.speed) % total) - dp(5f)
            val x = p.x + sin(t * 0.9f * p.speed + p.phase) * dp(9f) * p.size
            paint.color = ThemeRender.withAlpha(ink, p.alpha * 0.85f)
            canvas.drawCircle(((x % w) + w) % w, y, dp(1.1f + 2.2f * p.size), paint)
        }
    }

    private fun stars(canvas: Canvas, w: Int, h: Int, t: Float, ink: Int) {
        particles.forEachIndexed { i, p ->
            val tw = 0.5f + 0.5f * sin(t * (1.1f + p.speed * 1.6f) + p.phase)
            val a = p.alpha * (0.15f + 0.85f * tw * tw * tw)
            paint.color = ThemeRender.withAlpha(ink, a)
            val r = dp(0.6f + 1.4f * p.size)
            canvas.drawCircle(p.x, p.y, r, paint)
            if (i % 9 == 0 && tw > 0.6f) {  // a few sparkle as a little cross
                val len = r * (2.5f + 3f * tw)
                stroke.color = ThemeRender.withAlpha(ink, a * 0.8f)
                stroke.strokeWidth = dp(0.8f)
                canvas.drawLine(p.x - len, p.y, p.x + len, p.y, stroke)
                canvas.drawLine(p.x, p.y - len, p.x, p.y + len, stroke)
            }
        }
    }

    private fun bubbles(canvas: Canvas, w: Int, h: Int, t: Float, ink: Int) {
        val rise = dp(20f)
        for (p in particles) {
            val r = dp(4f + 10f * p.size)
            val total = h + r * 2
            val y = h + r - ((p.y + t * rise * p.speed) % total)
            val x = p.x + sin(t * 0.7f + p.phase) * dp(6f)
            stroke.color = ThemeRender.withAlpha(ink, 0.18f + 0.2f * p.alpha)
            stroke.strokeWidth = dp(1.2f)
            canvas.drawCircle(x, y, r, stroke)
            paint.color = ThemeRender.withAlpha(ink, 0.05f + 0.06f * p.alpha)
            canvas.drawCircle(x, y, r, paint)
            paint.color = ThemeRender.withAlpha(ink, 0.35f * p.alpha)
            canvas.drawCircle(x - r * 0.35f, y - r * 0.35f, max(dp(1f), r * 0.18f), paint)
        }
    }

    companion object {
        /** How often to draw a new frame (ms). Kept modest to save battery. */
        fun frameDelay(motion: BgMotion): Long = when (motion) {
            BgMotion.NONE -> 0L
            BgMotion.FLOW -> 50L
            else -> 33L
        }

        @Suppress("unused")
        private fun clamp(v: Float) = min(1f, abs(v))
    }
}
