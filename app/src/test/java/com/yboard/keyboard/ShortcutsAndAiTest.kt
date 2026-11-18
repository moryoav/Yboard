package com.yboard.keyboard

import com.yboard.keyboard.settings.YboardSettings
import com.yboard.keyboard.text.NextWords
import com.yboard.keyboard.text.Shortcuts
import com.yboard.keyboard.text.UserWords
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutsAndAiTest {

    @Test
    fun shortcutsExpand() {
        val list = Shortcuts.defaults + ("shalom" to "שלום לכולם!")
        assertEquals("On my way!", Shortcuts.find("omw", list))
        assertEquals("On my way!", Shortcuts.find("OMW", list))
        assertEquals("שלום לכולם!", Shortcuts.find("shalom", list))
        assertNull(Shortcuts.find("hello", list))
        assertTrue(Shortcuts.isValid("omw"))
        assertTrue(Shortcuts.isValid("בהצ"))
        assertFalse(Shortcuts.isValid("on my"))
        assertFalse(Shortcuts.isValid("b4"))
    }

    @Test
    fun shortcutsAreSaved() {
        val s = YboardSettings(shortcuts = listOf("gm" to "Good morning ☀️"), shortcutsOn = false, aiPredictions = true)
        assertEquals(s, YboardSettings.fromJson(s.toJson()))
        assertEquals(Shortcuts.defaults, YboardSettings().shortcuts)
        assertFalse(YboardSettings().aiPredictions)   // AI predictions are opt-in
    }

    @Test
    fun learnedWordsFeedTheAi() {
        val u = UserWords.inMemory("en")
        repeat(3) { u.learn("Dana") }
        u.learn("once")                               // only seen once: not sent
        assertEquals(listOf("Dana"), u.topWords(10))
        val n = NextWords.inMemory("en")
        repeat(2) { n.learn("love", "you") }
        n.learn("see", "ya")
        assertEquals(listOf("love you"), n.topPairs(10))
    }
}
