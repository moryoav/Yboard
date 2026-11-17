package com.yboard.keyboard.stickers

import android.content.ClipDescription
import android.content.Context
import android.net.Uri
import android.util.Log
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
    const val WHATSAPP_MIME = "image/webp.wasticker"
    const val ASSET = "stickers/coffee_test.webp"
    enum class Result { ACCEPTED, UNSUPPORTED, REJECTED, FAILED }

    /** Use the receiving editor's sticker contract when it explicitly advertises one. */
    fun mimeTypeFor(accepted: List<String>): String? = when {
        WHATSAPP_MIME in accepted -> WHATSAPP_MIME
        accepted.any { it == MIME || it == "image/*" || it == "*/*" } -> MIME
        else -> null
    }

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
        val mime = mimeTypeFor(EditorInfoCompat.getContentMimeTypes(editor).toList()) ?: return Result.UNSUPPORTED
        return try {
            val content = InputContentInfoCompat(uri(context), ClipDescription("Yboard test sticker", arrayOf(mime)), null)
            val accepted = InputConnectionCompat.commitContent(connection, editor, content,
                InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
            Log.i("YboardSticker", "Handoff MIME=$mime accepted=$accepted")
            if (accepted) Result.ACCEPTED else Result.REJECTED
        } catch (error: Exception) {
            Log.w("YboardSticker", "Handoff failed: ${error.javaClass.simpleName}")
            Result.FAILED
        }
    }
}
