package com.yboard.keyboard

import com.yboard.keyboard.settings.YboardSettings
import com.yboard.keyboard.text.Dictionary
import com.yboard.keyboard.text.MyWords
import com.yboard.keyboard.text.Suggester
import com.yboard.keyboard.text.UserWords
import com.yboard.keyboard.voice.LocalCommands
import com.yboard.keyboard.voice.OpenAi
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class MyWordsAndSendTest {

    companion object {
        lateinit var en: Dictionary
        lateinit var he: Dictionary

        @BeforeClass
        @JvmStatic
        fun setup() {
            en = Dictionary.fromLines("en", File("src/main/assets/dict/en.tsv").readLines(Charsets.UTF_8).asSequence())
            he = Dictionary.fromLines("he", File("src/main/assets/dict/he.tsv").readLines(Charsets.UTF_8).asSequence())
        }
    }

    @After
    fun noWordsLeftBehind() = UserWords.setMyWords(emptyList())

    // ------------------------------------------------------------------ send

    private fun kind(spoken: String) = LocalCommands.match(spoken, "see you soon", null)?.kind

    @Test
    fun sendSaidOnItsOwn() {
        listOf("Send.", "send it", "Send it now!", "OK, send.", "Send, please.", "And send.", "Go ahead and send it.",
            "שלח.", "תשלח", "תשלח את זה בבקשה", "ושלח", "סנד")
            .forEach { assertEquals(it, LocalCommands.Kind.SEND, kind(it)) }
        // How the speech model sometimes hears a lone "send".
        listOf("Sent.", "Sand.", "Sent it.", "Cent").forEach { assertEquals(it, LocalCommands.Kind.SEND, kind(it)) }
        // "hi, send" is "hi" + send, not "hit send".
        assertNull(kind("Hi, send"))
        assertNull(kind("Sent you the file"))
    }

    @Test
    fun sendAtTheEndOfASentence() {
        assertEquals("See you tomorrow.", LocalCommands.trailingSend("See you tomorrow. Send."))
        assertEquals("See you tomorrow", LocalCommands.trailingSend("See you tomorrow, send it."))
        assertEquals("See you tomorrow.", LocalCommands.trailingSend("See you tomorrow. Okay, send."))
        assertEquals("on my way", LocalCommands.trailingSend("on my way send it"))
        assertEquals("Thanks", LocalCommands.trailingSend("Thanks, send."))
        assertEquals("אני בדרך.", LocalCommands.trailingSend("אני בדרך. שלח."))
        assertEquals("נתראה מחר", LocalCommands.trailingSend("נתראה מחר תשלח"))
        // "send" that is part of the sentence stays as words.
        listOf(
            "I'll send it", "Can you send it?", "When should I send", "Did you send it.", "Please send",
            "I sent it", "Done. Sent.", "אבא שלח.", "מתי היא תשלח", "Send", "Sending it now",
        ).forEach { assertNull(it, LocalCommands.trailingSend(it)) }
    }

    @Test
    fun sendButtonsInOtherApps() {
        fun ok(label: String?) = com.yboard.keyboard.voice.SendButtonService.looksLikeSendButton(label)
        // ChatGPT and other AI chats.
        assertTrue(ok("Send prompt"))
        assertTrue(ok("Send message"))
        assertTrue(ok("שלח הודעה"))
        // Never money, files, photos or voice.
        assertFalse(ok("Send money"))
        assertFalse(ok("Send payment"))
        assertFalse(ok("Send photo"))
        assertFalse(ok("Send voice message"))
        assertFalse(ok("שלח כסף"))
        assertFalse(ok("Sender: Dana"))  // "send" must be a whole word
        assertFalse(ok(null))
    }

    @Test
    fun speechModelExpectsSend() {
        val live = JSONObject(OpenAi.liveSessionJson(listOf("en", "he"), full = true))
        val t = live.getJSONObject("session").getJSONObject("audio").getJSONObject("input").getJSONObject("transcription")
        val kw = (0 until t.getJSONArray("keywords").length()).map { t.getJSONArray("keywords").getString(it) }
        assertTrue(kw.containsAll(listOf("send", "שלח")))
        assertTrue(t.getString("prompt").contains("send"))
    }

    // ------------------------------------------------------------- my words

    @Test
    fun typingWordsIntoTheApp() {
        assertEquals(listOf("Yoav", "Moran", "Tel-Aviv"), MyWords.parse(" Yoav, Moran  Tel-Aviv. "))
        assertEquals(listOf("צ׳יפס", "WhatsApp"), MyWords.parse("צ׳יפס / WhatsApp"))
        assertNull(MyWords.problem("Yoav"))
        assertNull(MyWords.problem("יואב"))
        assertNull(MyWords.problem("rock'n'roll"))
        assertNotNull(MyWords.problem("a"))
        assertNotNull(MyWords.problem("R2D2"))
        assertNotNull(MyWords.problem("Yoavיואב"))
        assertEquals("he", MyWords.languageOf("יואב"))
        assertEquals("en", MyWords.languageOf("Yoav"))
    }

    @Test
    fun addingAndRemoving() {
        var list = MyWords.add(emptyList(), listOf("Yoav", "Moran"))
        assertEquals(listOf("Yoav", "Moran"), list)
        list = MyWords.add(list, listOf("WhatsApp", "yoav"))   // newest first, no duplicates
        assertEquals(listOf("WhatsApp", "yoav", "Moran"), list)
        assertEquals(listOf("WhatsApp", "Moran"), MyWords.remove(list, "YOAV"))
    }

    @Test
    fun savedWithSettings() {
        val s = YboardSettings(myWords = listOf("Yoav", "יואב"))
        assertEquals(s, YboardSettings.fromJson(s.toJson()))
        assertTrue(YboardSettings().myWords.isEmpty())
    }

    @Test
    fun yourWordsShowUpWhileTyping() {
        UserWords.setMyWords(listOf("Yoavi", "Shmulik", "WhatsApp", "יואבי"))
        val user = UserWords.inMemory("en")
        assertTrue(user.isKnown("yoavi"))
        assertFalse(UserWords.inMemory("he").isKnown("yoavi"))   // each word goes to its own language
        assertTrue(UserWords.inMemory("he").isKnown("יואבי"))

        val s = Suggester(en, user, "en")
        // A completion as you type it...
        assertTrue(s.suggest("shmu").suggestions.contains("Shmulik"))
        // ...never "corrected" away...
        val r = s.suggest("Shmulik")
        assertTrue(r.known)
        assertNull(r.autoCorrect)
        // ...a typo fixes to it, and small letters become your spelling.
        assertEquals("Shmulik", s.suggest("Shmulok").autoCorrect)
        assertEquals("WhatsApp", s.suggest("whatsapp").autoCorrect)

        val h = Suggester(he, UserWords.inMemory("he"), "he")
        assertTrue(h.suggest("יואב").suggestions.contains("יואבי") || h.suggest("יוא").suggestions.contains("יואבי"))
    }

    @Test
    fun everydayWordsKeepTheirSmallLetters() {
        UserWords.setMyWords(listOf("Apple"))
        val s = Suggester(en, UserWords.inMemory("en"), "en")
        assertNull(s.suggest("apple").autoCorrect)
    }

    @Test
    fun voiceTypingKnowsYourWords() {
        val live = JSONObject(OpenAi.liveSessionJson(listOf("en", "he"), full = true, vocabulary = listOf("Shmulik", "יואבי")))
        val t = live.getJSONObject("session").getJSONObject("audio").getJSONObject("input").getJSONObject("transcription")
        val kw = (0 until t.getJSONArray("keywords").length()).map { t.getJSONArray("keywords").getString(it) }
        assertTrue(kw.containsAll(listOf("Shmulik", "יואבי", "new line")))
        assertTrue(t.getString("prompt").contains("Shmulik, יואבי"))
        // Too many words: only the newest go along.
        val many = (1..200).map { "Word" + "abcdefghij"[it % 10] + it.toString().map { c -> 'a' + (c - '0') }.joinToString("") }
        val big = JSONObject(OpenAi.liveSessionJson(listOf("en"), full = true, vocabulary = many))
        val bt = big.getJSONObject("session").getJSONObject("audio").getJSONObject("input").getJSONObject("transcription")
        assertTrue(bt.getJSONArray("keywords").length() <= OpenAi.SPEECH_KEYWORDS.size + 60)
    }
}
