package com.yboard.keyboard.ime

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.ImageViewStyle
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import androidx.core.graphics.ColorUtils
import com.yboard.keyboard.R
import com.yboard.keyboard.settings.KeyboardTheme
import kotlin.math.roundToInt

/**
 * "Passwords & codes": asks Android's autofill service (Google, a password manager) for
 * logins and codes from texts, styled like Yboard's keys. Android draws the chips itself
 * and fills the field when one is tapped, so the passwords never pass through Yboard.
 */
@RequiresApi(Build.VERSION_CODES.R)
object AutofillChips {

    fun request(context: Context, theme: KeyboardTheme): InlineSuggestionsRequest {
        val d = context.resources.displayMetrics.density
        fun dp(v: Float) = (v * d).roundToInt()
        val text = theme.textColor or 0xFF000000.toInt()
        val chip = chipColor(theme)
        val background = Icon.createWithResource(context, R.drawable.autofill_chip).setTint(chip)
        val style = InlineSuggestionUi.newStyleBuilder()
            .setSingleIconChipStyle(
                ViewStyle.Builder().setBackground(background).setPadding(dp(10f), 0, dp(10f), 0).build()
            )
            .setChipStyle(
                ViewStyle.Builder().setBackground(background).setPadding(dp(14f), 0, dp(14f), 0).build()
            )
            .setStartIconStyle(
                ImageViewStyle.Builder().setLayoutMargin(0, 0, dp(6f), 0).setMaxWidth(dp(20f)).setMaxHeight(dp(20f)).build()
            )
            .setTitleStyle(
                TextViewStyle.Builder().setLayoutMargin(0, 0, 0, 0).setTextColor(text).setTextSize(15f).build()
            )
            .setSubtitleStyle(
                TextViewStyle.Builder().setLayoutMargin(dp(6f), 0, 0, 0)
                    .setTextColor(ColorUtils.setAlphaComponent(text, 0xA0)).setTextSize(13f).build()
            )
            .setEndIconStyle(
                ImageViewStyle.Builder().setLayoutMargin(dp(6f), 0, 0, 0).setMaxWidth(dp(20f)).setMaxHeight(dp(20f)).build()
            )
            .build()
        val styles = UiVersions.newStylesBuilder().addStyle(style).build()
        val spec = InlinePresentationSpec.Builder(Size(dp(40f), dp(34f)), Size(dp(320f), dp(34f)))
            .setStyle(styles)
            .build()
        return InlineSuggestionsRequest.Builder(listOf(spec))
            .setMaxSuggestionCount(8)
            .build()
    }

    /** The key color laid over the background, made solid (the chip is drawn by Android, over our window). */
    fun chipColor(theme: KeyboardTheme): Int {
        val bg = theme.bgColor or 0xFF000000.toInt()
        val key = ColorUtils.setAlphaComponent(
            theme.keyColor, (Color.alpha(theme.keyColor) * theme.keyOpacity.coerceIn(0f, 1f)).roundToInt()
        )
        return ColorUtils.compositeColors(key, bg)
    }

    /** Turns the suggestions into views, in order. Pinned ones (like a key icon) go separately. */
    fun inflate(context: Context, list: List<InlineSuggestion>, done: (chips: List<View>, pinned: List<View>) -> Unit) {
        if (list.isEmpty()) { done(emptyList(), emptyList()); return }
        val views = arrayOfNulls<View>(list.size)
        var left = list.size
        val size = Size(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        list.forEachIndexed { i, s ->
            try {
                s.inflate(context, size, context.mainExecutor) { v ->
                    views[i] = v
                    if (--left == 0) finish(list, views, done)
                }
            } catch (e: Exception) {
                if (--left == 0) finish(list, views, done)
            }
        }
    }

    private fun finish(list: List<InlineSuggestion>, views: Array<View?>, done: (List<View>, List<View>) -> Unit) {
        val chips = ArrayList<View>()
        val pinned = ArrayList<View>()
        list.forEachIndexed { i, s -> views[i]?.let { if (s.info.isPinned) pinned += it else chips += it } }
        done(chips, pinned)
    }
}
