package com.yboard.keyboard.text

import android.text.InputType
import android.view.inputmethod.EditorInfo

/** App flags are hints, not a switch for the user's glide-typing preference. */
object EditorPolicy {
    fun glide(inputType: Int): Boolean {
        if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        return inputType and InputType.TYPE_MASK_VARIATION !in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )
    }

    fun suggestions(inputType: Int): Boolean = glide(inputType) &&
        inputType and InputType.TYPE_MASK_VARIATION !in setOf(
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI,
        )

    fun personalized(inputType: Int, imeOptions: Int): Boolean = suggestions(inputType) &&
        imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0
}
