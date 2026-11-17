package com.yboard.keyboard

import com.yboard.keyboard.keyboard.Layouts
import com.yboard.keyboard.keyboard.Mode
import com.yboard.keyboard.settings.BgType
import com.yboard.keyboard.settings.OneHanded
import com.yboard.keyboard.settings.ThemePresets
import com.yboard.keyboard.settings.YboardSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {

    @Test
    fun settingsSurviveSaveAndLoad() {
        val s = YboardSettings(
            theme = ThemePresets.sunset.copy(
                bgType = BgType.IMAGE, bgImagePath = "/x/bg_1.jpg", bgOpacity = 0.4f,
                pressEffect = com.yboard.keyboard.settings.PressEffect.RIPPLE, rgbLights = true, dynamicColors = true,
                accentEnter = false, bgColorMid = 0xFF123456.toInt(),
                bgMotion = com.yboard.keyboard.settings.BgMotion.SNOW, keyShape = com.yboard.keyboard.settings.KeyShape.BUBBLE,
                blur = 22f, glassKeys = true,
            ),
            heightScale = 1.2f, numberRow = true, oneHanded = OneHanded.LEFT,
            hebrew = true, english = false, language = "he",
            savedThemes = listOf(ThemePresets.neon.copy(id = "custom_1", name = "Mine")),
            suggestions = false, autoCorrect = false, learnWords = false,
            swipeTyping = false, swipeTrail = false, backspaceSwipe = false, soundStyle = "wood", hideSystemBar = false, nextWords = false, emojiSuggest = false,
        )
        assertEquals(s, YboardSettings.fromJson(s.toJson()))
    }

    @Test
    fun brokenSettingsFallBackToDefaults() {
        assertEquals(YboardSettings(), YboardSettings.fromJson("{not json"))
    }

    @Test
    fun atLeastOneLanguageIsAlwaysActive() {
        val s = YboardSettings(english = false, hebrew = false, language = "he")
        assertEquals(listOf("en"), s.enabledLanguages)
        assertEquals("en", s.activeLanguage)
    }

    @Test
    fun everyRowFitsTheKeyboardWidth() {
        for (mode in Mode.entries) for (lang in listOf("en", "he")) for (numRow in listOf(true, false)) for (multi in listOf(true, false)) {
            val layout = Layouts.build(mode, lang, numRow, multi)
            layout.rows.forEach { row ->
                val width = row.keys.sumOf { it.width.toDouble() }
                assertTrue("$mode/$lang row is $width wide", width <= 10.0001)
            }
        }
    }
    @Test
    fun oldDarkThemeGetsGboardColorsButKeepsEffects() {
        // Settings saved by an older Yboard: the old dark colors, no style revision.
        val old = YboardSettings(
            theme = ThemePresets.dark.copy(
                bgColor = 0xFF1C1C1E.toInt(), keyColor = 0xFF3A3A3D.toInt(), accentEnter = true,
                rgbLights = true, bgOpacity = 0.7f,
            ),
        )
        val json = org.json.JSONObject(old.toJson()).apply { remove("styleRev") }.toString()
        val loaded = YboardSettings.fromJson(json).theme
        assertEquals(0xFF393939.toInt(), loaded.keyColor)
        assertEquals(0xFF1E1E1E.toInt(), loaded.bgColor)
        assertEquals(false, loaded.accentEnter)
        assertEquals(true, loaded.rgbLights)
        assertEquals(0.7f, loaded.bgOpacity)
    }

    @Test
    fun editedThemesAreNotTouchedByUpdates() {
        val mine = YboardSettings(theme = ThemePresets.dark.copy(id = "edited", keyColor = 0xFF123456.toInt()))
        val json = org.json.JSONObject(mine.toJson()).apply { remove("styleRev") }.toString()
        assertEquals(0xFF123456.toInt(), YboardSettings.fromJson(json).theme.keyColor)
    }

    @Test
    fun hebrewLayoutMatchesGboard() {
        val rows = Layouts.build(Mode.LETTERS, "he", false, true).rows
        fun labels(r: Int) = rows[r].keys.joinToString(" ") {
            if (it.type == com.yboard.keyboard.keyboard.KeyType.BACKSPACE) "⌫" else it.label
        }
        assertEquals("' - ק ר א ט ו ן ם פ", labels(0))
        assertEquals("ש ד ג כ ע י ח ל ך ף", labels(1))
        assertEquals("ז ס ב ה נ מ צ ת ץ ⌫", labels(2))
        assertEquals((1..9).map { "$it" } + "0", rows[0].keys.map { it.hint })
    }
    @Test
    fun resetCustomizationsKeepsYourOtherSettings() {
        val mine = YboardSettings(
            theme = ThemePresets.neon.copy(id = "edited", rgbLights = true, bgOpacity = 0.3f),
            heightScale = 1.25f, numberRow = true, oneHanded = OneHanded.LEFT,
            savedThemes = listOf(ThemePresets.ocean.copy(id = "custom_1")),
            language = "he", autoCorrect = false, soundStyle = "wood",
        )
        val lookOnly = mine.withDefaultLook(includeLayout = false)
        assertEquals(ThemePresets.default, lookOnly.theme)
        assertEquals(1.25f, lookOnly.heightScale)
        assertEquals(true, lookOnly.numberRow)

        val all = mine.withDefaultLook(includeLayout = true)
        assertEquals(ThemePresets.default, all.theme)
        assertEquals(1f, all.heightScale)
        assertEquals(false, all.numberRow)
        assertEquals(OneHanded.OFF, all.oneHanded)
        // Kept: saved themes, language, typing, sound.
        assertEquals(mine.savedThemes, all.savedThemes)
        assertEquals("he", all.language)
        assertEquals(false, all.autoCorrect)
        assertEquals("wood", all.soundStyle)
    }
}
