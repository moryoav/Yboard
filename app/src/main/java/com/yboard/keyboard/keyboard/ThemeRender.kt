package com.yboard.keyboard.keyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.yboard.keyboard.settings.BgType
import com.yboard.keyboard.settings.FontStyle
import com.yboard.keyboard.settings.KeyboardTheme
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

object ThemeRender {

    fun withAlpha(color: Int, alpha: Float): Int {
        val a = (Color.alpha(color) * alpha.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    fun luminance(color: Int): Double {
        fun ch(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch(Color.red(color)) + 0.7152 * ch(Color.green(color)) + 0.0722 * ch(Color.blue(color))
    }

    fun isLight(color: Int) = luminance(color) > 0.45

    fun typeface(context: Context, theme: KeyboardTheme): Typeface {
        if (theme.fontStyle == FontStyle.DEFAULT) {
            return if (theme.boldLabels) Fonts.bold(context) else Fonts.regular(context)
        }
        val family = when (theme.fontStyle) {
            FontStyle.LIGHT -> "sans-serif-light"
            FontStyle.CONDENSED -> "sans-serif-condensed"
            FontStyle.SERIF -> "serif"
            FontStyle.MONO -> "monospace"
            FontStyle.CASUAL -> "casual"
            else -> "sans-serif"
        }
        return Typeface.create(family, if (theme.boldLabels) Typeface.BOLD else Typeface.NORMAL)
    }

    /** The colour the keyboard "reads as", used for things like nav bar icon contrast. */
    fun dominantColor(theme: KeyboardTheme): Int = when (theme.bgType) {
        BgType.GRADIENT -> theme.bgColorMid ?: blend(theme.bgColor, theme.bgColor2, 0.5f)
        else -> theme.bgColor
    }

    fun blend(a: Int, b: Int, t: Float): Int {
        val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * t).roundToInt()
        val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * t).roundToInt()
        val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).roundToInt()
        return Color.rgb(r, g, bl)
    }
}

/** Draws the keyboard background (solid, gradient or photo) with the chosen transparency. */
class BackgroundPainter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dst = RectF()

    fun draw(canvas: Canvas, w: Int, h: Int, theme: KeyboardTheme, blurWorks: Boolean = true) {
        if (w <= 0 || h <= 0) return
        // A blur theme on a phone that can't blur right now (battery saver...): less see-through.
        val opacity = if (theme.blur > 0f && !blurWorks) max(theme.bgOpacity, 0.86f) else theme.bgOpacity.coerceIn(0f, 1f)
        paint.shader = null
        paint.alpha = 255
        when (theme.bgType) {
            BgType.COLOR -> {
                paint.color = ThemeRender.withAlpha(theme.bgColor, opacity)
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            }
            BgType.GRADIENT -> {
                val a = Math.toRadians(theme.gradientAngle.toDouble())
                val dx = cos(a).toFloat()
                val dy = sin(a).toFloat()
                val half = (abs(w * dx) + abs(h * dy)) / 2f
                val cx = w / 2f
                val cy = h / 2f
                val mid = theme.bgColorMid
                paint.shader = if (mid == null) {
                    LinearGradient(
                        cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half,
                        theme.bgColor, theme.bgColor2, Shader.TileMode.CLAMP
                    )
                } else {
                    // Three colors, like Gboard's gradient themes: the end color fills the last part.
                    LinearGradient(
                        cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half,
                        intArrayOf(theme.bgColor, mid, theme.bgColor2), floatArrayOf(0f, 0.5f, 0.86f),
                        Shader.TileMode.CLAMP
                    )
                }
                paint.alpha = (opacity * 255).roundToInt()
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
                paint.shader = null
            }
            BgType.IMAGE -> {
                val bmp = BackgroundImages.get(theme.bgImagePath, theme.imageBlur)
                if (bmp == null) {
                    paint.color = ThemeRender.withAlpha(theme.bgColor, opacity)
                    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
                } else {
                    // Center-crop the photo to fill the keyboard.
                    val scale = max(w / bmp.width.toFloat(), h / bmp.height.toFloat())
                    val sw = (w / scale).roundToInt().coerceAtMost(bmp.width)
                    val sh = (h / scale).roundToInt().coerceAtMost(bmp.height)
                    val sx = (bmp.width - sw) / 2
                    val sy = (bmp.height - sh) / 2
                    src.set(sx, sy, sx + sw, sy + sh)
                    dst.set(0f, 0f, w.toFloat(), h.toFloat())
                    paint.alpha = (opacity * 255).roundToInt()
                    canvas.drawBitmap(bmp, src, dst, paint)
                    if (theme.imageDim > 0f) {
                        paint.color = ThemeRender.withAlpha(Color.BLACK, theme.imageDim * opacity)
                        canvas.drawRect(dst, paint)
                    }
                }
            }
        }
        if (theme.glassKeys || theme.blur > 0f) drawSheen(canvas, w, h)
    }

    /** Liquid glass: a bright edge along the top and a soft light that fades downwards. */
    private fun drawSheen(canvas: Canvas, w: Int, h: Int) {
        val d = android.content.res.Resources.getSystem().displayMetrics.density
        paint.shader = LinearGradient(0f, 0f, 0f, 40f * d, ThemeRender.withAlpha(Color.WHITE, 0.10f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        paint.alpha = 255
        canvas.drawRect(0f, 0f, w.toFloat(), 40f * d, paint)
        paint.shader = null
        paint.color = ThemeRender.withAlpha(Color.WHITE, 0.42f)
        canvas.drawRect(0f, 0f, w.toFloat(), max(1f, 0.8f * d), paint)
    }
}

/** Loads (and caches) the user's background photo, optionally blurred. */
object BackgroundImages {
    private var key: String? = null
    private var bitmap: Bitmap? = null

    @Synchronized
    fun get(path: String?, blur: Boolean): Bitmap? {
        if (path.isNullOrEmpty()) return null
        val file = File(path)
        if (!file.exists()) return null
        val k = "$path|$blur|${file.lastModified()}"
        if (k == key) return bitmap
        val decoded = decode(file) ?: return null
        val result = if (blur) softBlur(decoded) else decoded
        key = k
        bitmap = result
        return result
    }

    private fun decode(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 900) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Cheap, good-looking blur: shrink a lot, then scale back up with filtering. */
    private fun softBlur(src: Bitmap): Bitmap {
        val smallW = (src.width / 18).coerceAtLeast(4)
        val smallH = (src.height / 18).coerceAtLeast(4)
        val small = Bitmap.createScaledBitmap(src, smallW, smallH, true)
        val mid = Bitmap.createScaledBitmap(small, smallW * 4, smallH * 4, true)
        return Bitmap.createScaledBitmap(mid, src.width / 2, src.height / 2, true)
    }
}
