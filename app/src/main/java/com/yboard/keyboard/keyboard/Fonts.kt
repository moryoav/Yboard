package com.yboard.keyboard.keyboard

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.yboard.keyboard.R

/**
 * Google Sans, the font Gboard uses, bundled under the SIL Open Font License.
 * Trimmed to Latin + Hebrew + symbols. Falls back to the phone font if loading fails.
 */
object Fonts {
    private var regular: Typeface? = null
    private var medium: Typeface? = null
    private var bold: Typeface? = null

    private fun load(context: Context, id: Int, fallbackStyle: Int): Typeface =
        try {
            ResourcesCompat.getFont(context, id)
        } catch (e: Exception) {
            null
        } ?: Typeface.create("sans-serif", fallbackStyle)

    fun regular(context: Context): Typeface =
        regular ?: load(context, R.font.google_sans_regular, Typeface.NORMAL).also { regular = it }

    fun medium(context: Context): Typeface =
        medium ?: load(context, R.font.google_sans_medium, Typeface.NORMAL).also { medium = it }

    fun bold(context: Context): Typeface =
        bold ?: load(context, R.font.google_sans_bold, Typeface.BOLD).also { bold = it }
}
