package com.yboard.keyboard.stickers

import android.content.ClipDescription
import android.content.Context
import android.net.Uri
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import java.io.File

class StickerFileProvider : FileProvider()

/** A bundled fixture for testing direct keyboard-to-chat sticker delivery. No cloud requests. */
object TestSticker {
    const val MIME = "image/webp"
    const val ASSET = "stickers/coffee_test.webp"
    enum class Result { ACCEPTED, UNSUPPORTED, REJECTED, FAILED }

    fun uri(context: Context): Uri {
        val directory = File(context.filesDir, "sticker_test").apply { mkdirs() }
        val file = File(directory, "coffee_test.webp")
        if (!file.exists()) {
            context.assets.open(ASSET).use { source -> file.outputStream().use { source.copyTo(it) } }
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.stickers", file)
    }

    fun send(context: Context, connection: InputConnection?, editor: EditorInfo?): Result {
        if (connection == null || editor == null) return Result.UNSUPPORTED
        val supported = EditorInfoCompat.getContentMimeTypes(editor).any { ClipDescription.compareMimeTypes(MIME, it) }
        if (!supported) return Result.UNSUPPORTED
        return try {
            val content = InputContentInfoCompat(uri(context), ClipDescription("Yboard test sticker", arrayOf(MIME)), null)
            if (InputConnectionCompat.commitContent(connection, editor, content,
                    InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)) Result.ACCEPTED else Result.REJECTED
        } catch (_: Exception) {
            Result.FAILED
        }
    }
}
