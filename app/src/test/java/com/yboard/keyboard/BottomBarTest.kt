package com.yboard.keyboard

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams
import com.yboard.keyboard.keyboard.KeyboardRootView
import com.yboard.keyboard.keyboard.Layouts
import com.yboard.keyboard.keyboard.Mode
import com.yboard.keyboard.settings.ThemePresets
import com.yboard.keyboard.settings.YboardSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

/**
 * The phone's bottom bar (gesture handle / back / switch-keyboard buttons) is drawn on top of
 * the keyboard window. These tests hand the keyboard the same "bar height" Android does and
 * check the keys end up above the bar (shown as a translucent red strip).
 */
class BottomBarTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6,
        theme = "android:Theme.Material.NoActionBar",
        renderingMode = SessionParams.RenderingMode.SHRINK,
    )

    /** Stands in for the keyboard window: always passes our fake insets down. */
    private class FakeWindow(ctx: Context, private val fake: WindowInsets) : FrameLayout(ctx) {
        override fun dispatchApplyWindowInsets(insets: WindowInsets): WindowInsets =
            super.dispatchApplyWindowInsets(fake)
    }

    private fun keyboardWithBar(s: YboardSettings, type: Int, barDp: Float): View {
        val ctx = paparazzi.context
        val px = (barDp * ctx.resources.displayMetrics.density).roundToInt()
        val fake = WindowInsetsCompat.Builder().setInsets(type, Insets.of(0, 0, 0, px)).build().toWindowInsets()!!
        val window = FakeWindow(ctx, fake)
        val root = KeyboardRootView(ctx, preview = false)
        root.apply(s)
        root.keyboard.setLayout(Layouts.build(Mode.LETTERS, s.activeLanguage, s.numberRow, true))
        window.addView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        window.addView(
            View(ctx).apply { setBackgroundColor(0x99FF3B30.toInt()) },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, px, Gravity.BOTTOM),
        )
        window.dispatchApplyWindowInsets(fake)
        assertEquals("keys must leave room for the bottom bar", px, root.paddingBottom)
        return window
    }

    @Test
    fun gestureNavigation() = paparazzi.snapshot(
        keyboardWithBar(YboardSettings(), WindowInsetsCompat.Type.captionBar(), 24f)
    )

    @Test
    fun threeButtonNavigationHebrew() = paparazzi.snapshot(
        keyboardWithBar(
            YboardSettings(theme = ThemePresets.light, language = "he"),
            WindowInsetsCompat.Type.navigationBars(), 48f,
        )
    )
}
