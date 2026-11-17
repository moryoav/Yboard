package com.yboard.keyboard

import com.yboard.keyboard.settings.YboardSettings
import com.yboard.keyboard.text.NextWords
import com.yboard.keyboard.text.Shortcuts
import com.yboard.keyboard.text.UserWords
import com.yboard.keyboard.voice.OpenAi
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
    fun aiPredictionRequestAndAnswer() {
        val json = JSONObject(OpenAi.predictRequestJson("gpt-6-luna", "see you at the ", "en", listOf("Dana"), listOf("love you"), "none"))
        assertEquals("gpt-6-luna", json.getString("model"))
        assertEquals(false, json.getBoolean("store"))
        val user = JSONObject(json.getJSONArray("input").getJSONObject(1).getString("content"))
        assertEquals("see you at the ", user.getString("text_before_cursor"))
        assertEquals("Dana", user.getJSONArray("words_they_use_often").getString(0))
        assertEquals("json_schema", json.getJSONObject("text").getJSONObject("format").getString("type"))

        val answer = """{"next":["beach","park","\"mall\"","the","at the end of the day","!!!","Park"]}"""
        assertEquals(listOf("beach", "park", "mall"), OpenAi.parsePredictions(answer, lastWord = "the"))
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
