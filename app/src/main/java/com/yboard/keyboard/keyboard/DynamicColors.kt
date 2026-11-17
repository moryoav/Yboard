package com.yboard.keyboard.keyboard

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import com.yboard.keyboard.settings.KeyboardTheme

/**
 * "Wallpaper colors": on Android 12+ the phone builds a palette from the wallpaper
 * (Material You). Themes with [KeyboardTheme.dynamicColors] read it every time they are drawn,
 * so the keyboard follows wallpaper and dark-mode changes by itself.
 */
object DynamicColors {
    val available: Boolean get() = Build.VERSION.SDK_INT >= 31

    fun resolve(context: Context, t: KeyboardTheme): KeyboardTheme {
        if (!t.dynamicColors || Build.VERSION.SDK_INT < 31) return t
        return try {
            val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            fun c(id: Int) = context.getColor(id)
            if (night) {
                t.copy(
                    bgColor = c(android.R.color.system_neutral1_900),
                    bgColor2 = c(android.R.color.system_neutral2_800),
                    keyColor = c(android.R.color.system_neutral2_800),
                    specialKeyColor = c(android.R.color.system_neutral2_700),
                    accentColor = c(android.R.color.system_accent1_200),
                    accentTextColor = c(android.R.color.system_accent1_800),
                    textColor = c(android.R.color.system_neutral1_50),
                    borderColor = c(android.R.color.system_accent1_300),
                )
            } else {
                t.copy(
                    bgColor = c(android.R.color.system_neutral2_50),
                    bgColor2 = c(android.R.color.system_accent2_100),
                    keyColor = c(android.R.color.system_neutral1_0),
                    specialKeyColor = c(android.R.color.system_accent2_100),
                    accentColor = c(android.R.color.system_accent1_600),
                    accentTextColor = c(android.R.color.system_accent1_0),
                    textColor = c(android.R.color.system_neutral1_900),
                    borderColor = c(android.R.color.system_accent1_400),
                )
            }
        } catch (e: Exception) {
            t
        }
    }
}
