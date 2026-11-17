package com.yboard.keyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.yboard.keyboard.keyboard.StickerPanel
import com.yboard.keyboard.settings.ThemePresets
import com.yboard.keyboard.settings.YboardSettings
import com.yboard.keyboard.stickers.*
import java.io.ByteArrayOutputStream
import java.io.File

object StickerLocalChecks {
    fun run(context: Context, assets: android.content.res.AssetManager): String {
        val fixture = assets.open("stickers/coffee_test.webp").use { it.readBytes() }
        val input = BitmapFactory.decodeByteArray(fixture, 0, fixture.size)
        val png = ByteArrayOutputStream().also { input.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        input.recycle()
        val result = StickerCodec.encode(context, png, "fixture-model")
        try {
            check(result.bitmap.width == 512 && result.bitmap.height == 512 && result.file.length() <= StickerCodec.MAX_BYTES)
            check(android.graphics.Color.alpha(result.bitmap.getPixel(0, 0)) == 0)
            val uri = StickerShare.uri(context, result.file)
            check(context.contentResolver.getType(uri) == "image/webp")
            val decoded = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
            check(decoded.width == 512 && decoded.hasAlpha()); decoded.recycle()
            val opaque = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
            val bad = ByteArrayOutputStream().also { opaque.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            opaque.recycle()
            check(runCatching { StickerCodec.encode(context, bad, "fixture") }.exceptionOrNull() is IllegalArgumentException)
            check(runCatching { StickerCodec.encode(context, byteArrayOf(1, 2, 3), "fixture") }.isFailure)
            var sends = 0; var recreates = 0; var closes = 0
            val panel = StickerPanel(context, "Hello! שלום", "fixture-model", { closes++ }, { recreates++ }, { sends++ })
            val output = File(context.getExternalFilesDir(null), "sticker-ui-checks").apply { mkdirs() }
            fun named(view: View, name: String): TextView? {
                if (view is TextView && view.text.toString() == name) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) named(view.getChildAt(i), name)?.let { return it }
                return null
            }
            fun capture(name: String) {
                val width = (400 * context.resources.displayMetrics.density).toInt()
                val height = (280 * context.resources.displayMetrics.density).toInt()
                panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                panel.layout(0, 0, width, height)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                panel.draw(Canvas(bitmap))
                File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            panel.applyTheme(YboardSettings()); panel.setBackgroundColor(ThemePresets.default.bgColor); capture("loading-dark")
            check(named(panel, "Send")?.isEnabled == false && named(panel, "Recreate")?.isEnabled == false)
            panel.show(result); capture("preview-dark")
            check(named(panel, "Send")?.isEnabled == true)
            named(panel, "Recreate")!!.performClick(); named(panel, "Send")!!.performClick(); named(panel, "Back")!!.performClick()
            check(sends == 1 && recreates == 1 && closes == 1)
            panel.loading("fixture-model"); check(named(panel, "Send")?.isEnabled == false)
            panel.error("The image service is busy. Try recreating it.")
            check(named(panel, "Send")?.isEnabled == true)
            val light = ThemePresets.all.first { com.yboard.keyboard.keyboard.ThemeRender.isLight(it.bgColor) }
            panel.applyTheme(YboardSettings(theme = light)); panel.setBackgroundColor(light.bgColor)
            capture("error-light"); panel.clearPreview()
            return "Sticker PNG decoding, WebP size/transparency, private sharing URI, opaque/invalid rejection and preview controls passed; captures: $output"
        } finally { result.bitmap.recycle(); result.file.delete() }
    }
}
