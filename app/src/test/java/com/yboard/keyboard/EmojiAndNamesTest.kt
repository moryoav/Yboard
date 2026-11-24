package com.yboard.keyboard

import com.yboard.keyboard.settings.YboardSettings
import com.yboard.keyboard.text.EmojiText
import com.yboard.keyboard.text.MyWords
import com.yboard.keyboard.text.NameSpelling
import com.yboard.keyboard.voice.LocalCommands
import com.yboard.keyboard.voice.OpenAi
import com.yboard.keyboard.voice.Scripts
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiAndNamesTest {

    // ---------------------------------------------------- Hebrew and English only

    @Test
    fun otherAlphabetsAreCaught() {
        val both = listOf("he", "en")
        assertFalse(Scripts.hasDisallowed("שָׁלוֹם, hello! 👋 123", both))
        assertTrue(Scripts.hasDisallowed("مرحبا", both))
        assertTrue(Scripts.hasDisallowed("Привет", both))
        assertTrue(Scripts.hasDisallowed("Está bien", both))
        assertTrue(Scripts.hasDisallowed("Esta\u0301 bien", both))
        assertTrue(Scripts.hasDisallowed("١٢٣،", both))
        assertTrue(Scripts.hasDisallowed("hello שלום", listOf("en")))
        assertTrue(Scripts.hasDisallowed("שלום hello", listOf("he")))
        assertEquals("שלום world", Scripts.withoutDisallowed("שלום،مرحبا\tworld", both))
        assertEquals("hello world", Scripts.withoutDisallowed("hello abcمرحبا world", both))
        assertEquals("", Scripts.withoutDisallowed("مرحبا.", both))
    }

    @Test
    fun otherLanguagesInEnglishLettersAreCaughtToo() {
        val english = setOf("hello", "how", "are", "you", "i", "met", "today", "see", "soon")
        val isEnglish: (String) -> Boolean? = { it.lowercase() in english }
        assertTrue(Scripts.looksLikeOtherLatinLanguage("hola amigos como estas", isEnglish))
        assertFalse(Scripts.looksLikeOtherLatinLanguage("Hello, how are you?", isEnglish))
        assertFalse(Scripts.looksLikeOtherLatinLanguage("שלום מה נשמע", isEnglish))
        assertFalse(Scripts.looksLikeOtherLatinLanguage("I met Shmulik today", isEnglish))  // names don't count
        assertFalse(Scripts.looksLikeOtherLatinLanguage("see you yoavi", isEnglish, names = setOf("yoavi")))
        assertFalse(Scripts.looksLikeOtherLatinLanguage("lol", isEnglish))                  // too short to tell
        assertFalse(Scripts.looksLikeOtherLatinLanguage("hola amigos", { null }))           // no dictionary: can't tell
    }

    @Test
    fun voiceAsksForHebrewAndEnglishOnly() {
        assertEquals(listOf("he", "en"), YboardSettings(voiceLanguages = "auto").voiceLanguageList)
        assertEquals(listOf("he", "en"), YboardSettings(voiceLanguages = "auto", hebrew = false).voiceLanguageList)
        fun t(level: Int) = JSONObject(OpenAi.liveSessionJson(listOf("he", "en"), level))
            .getJSONObject("session").getJSONObject("audio").getJSONObject("input").getJSONObject("transcription")
        assertTrue(t(0).has("keywords") && t(0).has("languages"))
        assertTrue(t(0).getString("prompt").contains("never in any other language"))
        // When OpenAI rejects a setting, the less important ones go first; the languages stay longest.
        assertFalse(t(1).has("keywords")); assertTrue(t(1).has("prompt")); assertTrue(t(1).has("languages"))
        assertFalse(t(2).has("prompt")); assertTrue(t(2).has("languages"))
        assertFalse(t(OpenAi.LAST_SESSION_LEVEL).has("languages"))
        val onlyHebrew = OpenAi.speechPrompt(listOf("Yoav"), pairs, languages = listOf("he"))
        assertTrue(onlyHebrew.startsWith("Verbatim phone dictation in Hebrew, never in any other language."))
        assertTrue(onlyHebrew.contains("יואב"))
        assertFalse(onlyHebrew.contains("Yoav"))
    }

    // ------------------------------------------------------------ emojis

    @Test
    fun findsWholeEmojis() {
        assertEquals(listOf("👍🏽", "👩‍💻"), EmojiText.emojisIn("hi 👍🏽 and 👩‍💻!"))
        assertEquals("hi  and !", EmojiText.withoutEmojis("hi 👍🏽 and 👩‍💻!"))
    }

    @Test
    fun sprinkleNeverChangesYourWords() {
        assertEquals("happy birthday dana 🎉🎂🥳", EmojiText.sprinkleResult("happy birthday dana", "happy birthday dana 🎉🎂🥳"))
        assertEquals("נתראה מחר 🏖️ ", EmojiText.sprinkleResult("נתראה מחר ", "נתראה מחר 🏖️"))
        // The AI changed a word: yours stay, its emojis go at the end.
        assertEquals("see you 😊", EmojiText.sprinkleResult("see you", "See you soon 😊"))
        assertEquals("see you", EmojiText.sprinkleResult("see you", "  "))
    }

    @Test
    fun sprinkleRequestHasTheRightShape() {
        val o = JSONObject(OpenAi.sprinkleRequestJson("gpt-6.1-terra", "happy birthday", "none"))
        assertFalse(o.getBoolean("store"))
        val user = JSONObject(o.getJSONArray("input").getJSONObject(1).getString("content"))
        assertEquals("happy birthday", user.getString("text"))
        assertTrue(o.getJSONObject("text").getJSONObject("format").getBoolean("strict"))
    }

    // ------------------------------------------------- names in your language

    private val pairs = listOf("Yoav" to "יואב", "WhatsApp" to "וואטסאפ")

    @Test
    fun namesComeOutInTheLanguageYouSpeak() {
        assertEquals("אני עם יואב בבית", NameSpelling.fix("אני עם Yoav בבית", pairs, hebrewByDefault = false))
        assertEquals("אני הולך ליואב מחר", NameSpelling.fix("אני הולך ל-Yoav מחר", pairs, hebrewByDefault = false))
        assertEquals("שלח לי בוואטסאפ", NameSpelling.fix("שלח לי בWhatsApp", pairs, hebrewByDefault = false))
        assertEquals("I'm with Yoav now", NameSpelling.fix("I'm with יואב now", pairs, hebrewByDefault = true))
        // Just the name: the language you were already writing in decides.
        assertEquals("יואב", NameSpelling.fix("yoav", pairs, hebrewByDefault = true))
        assertEquals("Yoav", NameSpelling.fix("Yoav", pairs, hebrewByDefault = false))
        assertEquals("hello there", NameSpelling.fix("hello there", emptyList(), hebrewByDefault = true))
        // The speech model's spelling can be off by a letter.
        assertEquals("אני עם יואב", NameSpelling.fix("אני עם Yoab", pairs, hebrewByDefault = false))
        assertEquals("I'm with Yoav", NameSpelling.fix("I'm with יואבב", pairs, hebrewByDefault = true))
        // ...but ordinary words are left alone.
        assertEquals("I'm with you", NameSpelling.fix("I'm with you", pairs, hebrewByDefault = true))
    }

    @Test
    fun repairsUniqueSameScriptMissesWithoutReplacingDictionaryWords() {
        val pair = listOf("Tzoreli" to "צורלי")
        val knownWord: (String) -> Boolean = { it == "מורלי" }
        assertEquals("פגשתי את צורלי.", NameSpelling.fix(
            "פגשתי את הורלי.", pair, hebrewByDefault = false, isDictionaryWord = knownWord,
        ))
        assertEquals("צורלי", NameSpelling.fix("הורלי", pair, hebrewByDefault = false, isDictionaryWord = knownWord))
        assertEquals("מורלי", NameSpelling.fix("מורלי", pair, hebrewByDefault = false, isDictionaryWord = knownWord))
        assertEquals("צורלי", NameSpelling.fix("הורלי", emptyList(), true, listOf("צורלי"), knownWord))
        assertEquals("הורלי", NameSpelling.fix("הורלי", emptyList(), true, listOf("צורלי", "אורלי"), knownWord))
        assertEquals("הורלי", NameSpelling.fix("הורלי", pair, hebrewByDefault = true))
    }

    @Test
    fun pairsInMyWords() {
        assertEquals("Yoav" to "יואב", MyWords.parsePair("Yoav = יואב"))
        assertEquals("Yoav" to "יואב", MyWords.parsePair("יואב=Yoav"))
        assertNull(MyWords.parsePair("Yoav = Moran"))
        assertNull(MyWords.parsePair("Yoav"))
        var p = MyWords.withPair(emptyList(), "יואב", "Yoav")!!
        assertEquals(listOf("Yoav" to "יואב"), p)
        assertEquals("יואב", MyWords.pairOf("yoav", p))
        assertEquals("Yoav", MyWords.pairOf("יואב", p))
        p = MyWords.withPair(p, "Yoav", "יואבי")!!   // a new spelling replaces the old one
        assertEquals(listOf("Yoav" to "יואבי"), p)
        assertNull(MyWords.withPair(p, "Yoav", "Yoavi"))   // same language: not a pair
        assertTrue(MyWords.withoutPair(p, "Yoav").isEmpty())
        val s = YboardSettings(myWords = listOf("Yoav"), myWordPairs = p)
        assertEquals(s, YboardSettings.fromJson(s.toJson()))
    }

    @Test
    fun speechModelGetsBothSpellings() {
        val prompt = OpenAi.speechPrompt(listOf("Yoav"), pairs)
        assertTrue(prompt.contains("Yoav = יואב"))
        assertTrue(OpenAi.speechKeywords(listOf("Yoav"), pairs).containsAll(listOf("Yoav", "יואב", "וואטסאפ")))
        val spell = JSONObject(OpenAi.spellingRequestJson("m", listOf("Yoav"), listOf("Yoav", "יואב"), null))
        val user = JSONObject(spell.getJSONArray("input").getJSONObject(1).getString("content"))
        assertEquals("Yoav", user.getJSONArray("words").getString(0))
    }
}
