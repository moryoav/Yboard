package com.yboard.keyboard.stickers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.min

data class StickerImage(val file: File, val bitmap: Bitmap, val model: String)

object StickerCodec {
    const val SIZE = 512
    const val MAX_BYTES = 100 * 1024

    fun encode(context: Context, bytes: ByteArray, model: String): StickerImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 16_777_216) {
            "The image model returned an unsupported image size. Try recreating it."
        }
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Couldn't read the generated image.")
        try {
            var transparent = false
            val row = IntArray(source.width)
            for (y in 0 until source.height) {
                source.getPixels(row, 0, source.width, 0, y, source.width, 1)
                if (row.any { Color.alpha(it) < 16 }) { transparent = true; break }
            }
            require(transparent) { "The image model returned an opaque background. Recreate it or choose another GPT Image model." }
            val sticker = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            val scale = min(480f / source.width, 480f / source.height)
            val width = source.width * scale
            val height = source.height * scale
            Canvas(sticker).drawBitmap(source, null, RectF((SIZE - width) / 2, (SIZE - height) / 2,
                (SIZE + width) / 2, (SIZE + height) / 2), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            try {
                var encoded: ByteArray? = null
                for (quality in listOf(90, 80, 70, 60, 50, 40, 30, 20, 10)) {
                    val output = ByteArrayOutputStream()
                    @Suppress("DEPRECATION")
                    val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                    check(sticker.compress(format, quality, output)) { "Couldn't encode the sticker." }
                    if (output.size() <= MAX_BYTES) { encoded = output.toByteArray(); break }
                }
                check(encoded != null) { "This artwork is too detailed for a sticker. Try recreating it." }
                val directory = File(context.cacheDir, "stickers").apply { mkdirs() }
                val file = File(directory, "${UUID.randomUUID()}.webp")
                try { file.writeBytes(encoded) } catch (error: java.io.IOException) {
                    file.delete()
                    throw IllegalStateException("Couldn't save the sticker. Check free storage and try recreating it.", error)
                }
                val preview = BitmapFactory.decodeByteArray(encoded, 0, encoded.size) ?: error("Couldn't read the encoded sticker.")
                // Immutable names keep files already handed to a chat app intact during recreation.
                directory.listFiles()?.filter { it != file && it.lastModified() < System.currentTimeMillis() - 7L * 86400000 }
                    ?.forEach { it.delete() }
                sticker.recycle()
                return StickerImage(file, preview, model)
            } catch (error: Throwable) { sticker.recycle(); throw error }
        } finally { source.recycle() }
    }
}
