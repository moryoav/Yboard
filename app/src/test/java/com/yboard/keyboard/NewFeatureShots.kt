package com.yboard.keyboard

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams
import com.yboard.keyboard.keyboard.EmojiPanel
import com.yboard.keyboard.keyboard.EmojiSearchBar
import com.yboard.keyboard.keyboard.KeyboardRootView
import com.yboard.keyboard.keyboard.Layouts
import com.yboard.keyboard.keyboard.Mode
import com.yboard.keyboard.settings.PressEffect
import com.yboard.keyboard.settings.ThemePresets
import com.yboard.keyboard.settings.YboardSettings
import com.yboard.keyboard.text.EmojiSearch
import org.junit.Rule
import org.junit.Test

class NewFeatureShots {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6,
        theme = "android:Theme.Material.NoActionBar",
        renderingMode = SessionParams.RenderingMode.SHRINK,
    )

    private fun board(s: YboardSettings, setup: (KeyboardRootView) -> Unit = {}): View {
        val ctx = paparazzi.context
        val frame = FrameLayout(ctx)
        frame.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF8EC5FC.toInt(), 0xFFB79CF7.toInt(), 0xFFF7A8C4.toInt()),
        )
        val root = KeyboardRootView(ctx, preview = true)
        root.apply(s)
        root.keyboard.setLayout(Layouts.build(Mode.LETTERS, s.activeLanguage, s.numberRow, true))
        frame.addView(root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
        ).apply { topMargin = (60 * ctx.resources.displayMetrics.density).toInt() })
        setup(root)
        return frame
    }

    @Test fun suggestionStrip() = paparazzi.snapshot(board(YboardSettings()) {
        it.toolbar.setSuggestions(listOf("“helo”", "hello", "help"), 1)
    })

    @Test fun suggestionStripHebrew() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.light, language = "he")) {
        it.toolbar.setSuggestions(listOf("שלומך", "שלום", "שלומי"), 1)
    })

    @Test fun rgbLights() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.amoled.copy(rgbLights = true))))

    @Test fun wallpaperColors() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.wallpaper)))

    @Test fun emojiSearch() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.aurora)) { root ->
        EmojiSearch.load(root.context)
        val bar = EmojiSearchBar(root.context, {}, {})
        root.showSearchBar(bar)
        bar.update("heart", EmojiSearch.search("heart"))
    })

    @Test fun emojiSearchHebrew() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.dark, language = "he")) { root ->
        EmojiSearch.load(root.context)
        val bar = EmojiSearchBar(root.context, {}, {})
        root.showSearchBar(bar)
        bar.update("לב", EmojiSearch.search("לב"))
    })

    @Test fun emojiPanelWithSearch() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.ocean)) { root ->
        root.showPanel(EmojiPanel(root.context, {}, {}, {}, {}, {}), null)
    })

    @Test fun emojiRanking() {
        EmojiSearch.load(paparazzi.context)
        val heart = EmojiSearch.search("heart")
        org.junit.Assert.assertEquals("❤️", heart.first())
        org.junit.Assert.assertTrue(EmojiSearch.search("לב").take(3).toString(), "❤️" in EmojiSearch.search("לב").take(3))
        org.junit.Assert.assertEquals("😂", EmojiSearch.search("lol").first())
        org.junit.Assert.assertTrue(EmojiSearch.kaomoji(paparazzi.context).size > 100)
        // Emoji suggestions next to typed words.
        org.junit.Assert.assertEquals("🍕", EmojiSearch.forWord("pizza"))
        org.junit.Assert.assertEquals("🍕", EmojiSearch.forWord("pizzas"))
        org.junit.Assert.assertEquals("🍕", EmojiSearch.forWord("פיצה"))
        org.junit.Assert.assertEquals(null, EmojiSearch.forWord("the"))
        org.junit.Assert.assertEquals(null, EmojiSearch.forWord("hand"))
        org.junit.Assert.assertTrue(EmojiSearch.forWord("dog") != null)
    }

    private fun swipePoints() = (0..80).map { i ->
        val t = i / 80f
        com.yboard.keyboard.text.Pt(120f + t * 840f, 380f + (kotlin.math.sin(t * 2 * Math.PI * 1.2) * 150).toFloat())
    }

    @Test fun swipeTrailThickRainbow() = paparazzi.snapshot(board(YboardSettings(
        theme = ThemePresets.dark, trailColor = com.yboard.keyboard.settings.TrailColor.RAINBOW, trailWidth = 14f,
    )) { it.keyboard.trailForTests(swipePoints()) })

    @Test fun swipeTrailDefault() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.liquidGlass)) {
        it.keyboard.trailForTests(swipePoints())
    })

    @Test fun hebrewToolbar() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.dark, language = "he")))

    @Test fun emailKeyboard() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.dark)) {
        it.keyboard.setLayout(Layouts.build(Mode.LETTERS, "en", numberRow = false, multiLang = false, email = true))
        it.toolbar.setSuggestions(listOf("@gmail.com", "dana.levi@gmail.com", ""), 1)
    })

    @Test fun emailKeyboardDomains() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.light)) {
        it.keyboard.setLayout(Layouts.build(Mode.LETTERS, "en", numberRow = false, multiLang = false, email = true))
        it.toolbar.setSuggestions(listOf("@walla.co.il", "@gmail.com", "@hotmail.com"), 1)
    })

    @Test fun emailKeyboardEmptyBox() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.midnight)) {
        it.keyboard.setLayout(Layouts.build(Mode.LETTERS, "en", numberRow = false, multiLang = false, email = true))
        it.toolbar.setSuggestions(listOf("", "dana.levi@gmail.com", ""), 1)
    })

    /** Where logins / codes go. On the phone Android draws the chips; plain pills stand in for them here. */
    @Test fun autofillStripMock() = paparazzi.snapshot(board(YboardSettings(theme = ThemePresets.dark)) { root ->
        val ctx = paparazzi.context
        val d = ctx.resources.displayMetrics.density
        val t = root.settings.theme
        fun pill(text: String) = android.widget.TextView(ctx).apply {
            this.text = text
            textSize = 15f
            setTextColor(t.textColor)
            gravity = Gravity.CENTER
            setPadding((14 * d).toInt(), 0, (14 * d).toInt(), 0)
            height = (34 * d).toInt()
            background = GradientDrawable().apply {
                cornerRadius = 17 * d
                setColor(com.yboard.keyboard.ime.AutofillChips.chipColor(t))
            }
        }
        root.showAutofill(listOf(pill("🔑 dana.levi@gmail.com  ••••••"), pill("dana_l  ••••••"), pill("💬 482913")), listOf(pill("🔑")))
    })

    @Test fun glowEffectTheme() = paparazzi.snapshot(board(YboardSettings(
        theme = ThemePresets.neon.copy(pressEffect = PressEffect.GLOW, rgbLights = false)
    )))
}
