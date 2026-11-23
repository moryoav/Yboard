package com.yboard.keyboard

import com.yboard.keyboard.settings.ToolbarShortcut
import com.yboard.keyboard.ui.Screen
import com.yboard.keyboard.ui.SettingsIndex
import com.yboard.keyboard.ui.foldPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppUiTest {

    @Test
    fun longHelpFoldsAfterTheFirstSentence() {
        assertNull(foldPoint("Short help stays as it is."))
        val text = "Make a key at platform.openai.com → API keys. Yboard keeps it encrypted on this phone, " +
            "leaves it out of backups and clipboard history, and only ever sends it to OpenAI."
        assertEquals("Make a key at platform.openai.com → API keys.", text.substring(0, foldPoint(text)!!))
        // A long first sentence: everything folds behind the link.
        assertEquals(0, foldPoint("x".repeat(200)))
    }

    @Test
    fun searchFindsSettings() {
        assertEquals("Blur behind", SettingsIndex.search("blur").first().title)
        assertTrue(SettingsIndex.search("trail").map { it.title }.containsAll(listOf("Trail thickness", "Trail color", "Show swipe trail")))
        assertTrue(SettingsIndex.search("sound").all { it.screen == Screen.SOUND || it.words.contains("sound") || it.title.contains("sound", true) })
        assertEquals(Screen.MY_WORDS, SettingsIndex.search("dictionary").first().screen)
        assertEquals(Screen.LEARNED_WORDS, SettingsIndex.search("learned words").first().screen)
        val sprinkle = SettingsIndex.search("emoji sprinkle button").single()
        assertEquals(Screen.TOOLBAR, sprinkle.screen)
        assertEquals(ToolbarShortcut.SPRINKLE.label, sprinkle.target)
        assertEquals("Keyboard height", SettingsIndex.search("height").first().title)
        assertTrue(SettingsIndex.search("רקע").isNotEmpty())                // Hebrew words work too
        assertTrue(SettingsIndex.search("swipe color").any { it.title == "Trail color" })  // every word must match
        assertTrue(SettingsIndex.search("").isEmpty())
        assertTrue(SettingsIndex.search("qwzx").isEmpty())
    }

    @Test
    fun everySearchResultPointsAtARealSetting() {
        val ui = File("src/main/java/com/yboard/keyboard/ui").listFiles()!!.filter { it.extension == "kt" }
            .joinToString("\n") { it.readText() }
        SettingsIndex.all.mapNotNull { it.target }.forEach { target ->
            assertTrue("no setting titled \"$target\"", ui.contains("\"$target\"") || ToolbarShortcut.entries.any { it.label == target })
        }
    }
}
