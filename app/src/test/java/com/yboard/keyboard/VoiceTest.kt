package com.yboard.keyboard

import com.yboard.keyboard.settings.YboardSettings
import com.yboard.keyboard.voice.ApiKeyStore
import com.yboard.keyboard.voice.LocalCommands
import com.yboard.keyboard.voice.OpenAi
import com.yboard.keyboard.voice.Vad
import com.yboard.keyboard.voice.VoiceEdit
import com.yboard.keyboard.voice.VoiceEdits
import com.yboard.keyboard.voice.Wav
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class VoiceTest {

    private val rnd = Random(3)
    private fun noise(amp: Int) = ShortArray(480) { (rnd.nextInt(-amp, amp + 1)).toShort() }
    private fun voice(amp: Int, n: Int) = ShortArray(480) { i -> (amp * sin(2 * PI * 220 * (n * 480 + i) / 24000.0)).toInt().toShort() }

    private class Counter : Vad.Sink {
        var audio = 0; var ends = 0; var silences = 0
        override fun audio(frame: ShortArray) { audio++ }
        override fun phraseEnd() { ends++ }
        override fun longSilence() { silences++ }
        override fun level(level: Float) {}
    }

    @Test
    fun pauseDetectionFindsEachPhrase() {
        val vad = Vad()
        val c = Counter()
        repeat(50) { vad.process(noise(40), c) }            // 1 s of room noise
        repeat(40) { vad.process(voice(6000, it), c) }      // 0.8 s of talking
        repeat(40) { vad.process(noise(40), c) }            // pause
        assertEquals(1, c.ends)
        // The talking, plus a little before (pre-roll) and the pause that ended it.
        assertTrue("sent ${c.audio} frames", c.audio in 40..(40 + 15 + 25 + 1))
        repeat(30) { vad.process(voice(5000, it), c) }      // second phrase
        repeat(40) { vad.process(noise(40), c) }
        assertEquals(2, c.ends)
    }

    @Test
    fun commandsEndAfterAShortPause() {
        assertEquals(9, LocalCommands.quickEndFrames("New line"))            // nothing longer starts like this
        assertEquals(15, LocalCommands.quickEndFrames("Delete that"))        // "delete that word" might follow
        assertEquals(9, LocalCommands.quickEndFrames("question mark"))
        assertEquals(0, LocalCommands.quickEndFrames("I went to the"))
        // The VAD ends a command phrase after the short pause instead of the normal one.
        val vad = Vad()
        val c = Counter()
        repeat(50) { vad.process(noise(40), c) }
        repeat(30) { vad.process(voice(6000, it), c) }
        vad.quickEndFrames = 9
        repeat(10) { vad.process(noise(40), c) }
        assertEquals(1, c.ends)
    }

    @Test
    fun quietRoomSendsNothingAndStopsAfterEightSeconds() {
        val vad = Vad()
        val c = Counter()
        repeat(450) { vad.process(noise(60), c) }
        assertEquals(0, c.audio)
        assertEquals(0, c.ends)
        assertEquals(1, c.silences)
    }

    @Test
    fun removeOnlyDeletesTextThatIsReallyThere() {
        assertEquals(6, VoiceEdits.removeCount("I said hello", " hello"))
        assertEquals(5, VoiceEdits.removeCount("I said hello", "hello"))
        assertEquals(0, VoiceEdits.removeCount("abc", ""))
        assertEquals(-1, VoiceEdits.removeCount("I said hello", "goodbye"))
        assertEquals("I said".length, "I said hello".length - VoiceEdits.removeCount("I said hello", " hello"))
        // Trailing spaces in the field don't stop it.
        assertEquals(7, VoiceEdits.removeCount("I said hello ", " hello"))
        assertEquals(4, VoiceEdits.removeCount("שלום עולם", "עולם"))
    }

    @Test
    fun dictationGetsSpacesAndSensibleCapitals() {
        assertEquals("Hi there.", VoiceEdits.forInsert("", "Hi there."))
        assertEquals(" How are you?", VoiceEdits.forInsert("Hello.", "How are you?"))
        assertEquals(" and bought milk.", VoiceEdits.forInsert("I went to the store", "And bought milk."))
        assertEquals(" John said hi.", VoiceEdits.forInsert("I talked to", "John said hi."))
        assertEquals("?", VoiceEdits.forInsert("hi", "?"))
        assertEquals("there", VoiceEdits.forInsert("hi ", "there"))
        assertEquals(" מה שלומך?", VoiceEdits.forInsert("היי", "מה שלומך?"))
    }

    @Test
    fun onlyTrickyCommandsGoToTheAi() {
        listOf(
            "can you delete the last sentence please", "replace Monday with Tuesday in the message",
            "change dinner to lunch", "תחליף את שני בשלישי", "make that all caps",
        ).forEach { assertTrue(it, VoiceEdits.looksLikeCommand(it)) }
        listOf(
            "I went to the store yesterday with my friends", "hi", "see you soon",
            "we had a great time at the party last night", "אני הולך לבית הספר מחר בבוקר",
        ).forEach { assertFalse(it, VoiceEdits.looksLikeCommand(it)) }
    }

    @Test
    fun simpleCommandsWorkWithoutTheAi() {
        fun m(spoken: String, before: String, last: String? = null) = LocalCommands.match(spoken, before, last)
        assertEquals(VoiceEdit(" How are you?", ""), m("Delete that.", "Hi there. How are you?", " How are you?")!!.edit)
        assertEquals(VoiceEdit(" pizza", ""), m("delete that word", "I love pizza")!!.edit)
        assertEquals(VoiceEdit(" pizza", ""), m("Delete that", "I love pizza")!!.edit)  // nothing said yet: last word
        assertEquals(VoiceEdit(" pizza", ""), m("please delete the last word", "I love pizza")!!.edit)
        assertEquals(VoiceEdit(" How are you", ""), m("delete the last sentence", "Hi. How are you")!!.edit)
        assertEquals(VoiceEdit(" ", "\n"), m("New line.", "Hello ")!!.edit)
        assertEquals(VoiceEdit("", "?"), m("Question mark.", "hi")!!.edit)
        assertEquals(LocalCommands.Kind.CANCEL_PHRASE, m("see you soon scratch that", "hi")!!.kind)
        assertEquals(VoiceEdit(" מה קורה", ""), m("תמחק את זה", "שלום מה קורה", " מה קורה")!!.edit)
        assertEquals(VoiceEdit(" קורה", ""), m("תמחק את המילה האחרונה", "שלום מה קורה")!!.edit)
        assertEquals(VoiceEdit("", "\n"), m("שורה חדשה", "שלום")!!.edit)
        assertEquals(VoiceEdit("", "?"), m("סימן שאלה", "מה נשמע")!!.edit)
        assertEquals(null, m("I like pizza", "hi"))
        assertEquals(null, m("delete the photos from yesterday's trip", "hi"))
    }

    @Test
    fun saidDifferentlyStillWorks() {
        fun m(spoken: String, before: String, last: String? = null) = LocalCommands.match(spoken, before, last)
        assertEquals(VoiceEdit("", "?"), m("Add a question mark.", "hi")!!.edit)
        assertEquals(VoiceEdit("", "?"), m("put a question mark please", "hi")!!.edit)
        assertEquals(VoiceEdit("", ","), m("Insert a comma", "hi")!!.edit)
        assertEquals(VoiceEdit("", "\n"), m("Go to a new line", "hi")!!.edit)
        assertEquals(VoiceEdit("", "\n"), m("start a new line", "hi")!!.edit)
        assertEquals(VoiceEdit(" there", ""), m("Get rid of that.", "hi there", " there")!!.edit)
        assertEquals(VoiceEdit(" there", ""), m("No, delete that.", "hi there", " there")!!.edit)
        assertEquals(VoiceEdit(" there", ""), m("Oops, scratch that", "hi there", " there")!!.edit)
        assertEquals(VoiceEdit(" there", ""), m("delete what I just said", "hi there", " there")!!.edit)
        assertEquals(VoiceEdit("", "?"), m("תוסיף סימן שאלה", "מה נשמע")!!.edit)
        assertEquals(VoiceEdit(" נשמע", ""), m("תמחק את מה שאמרתי", "מה נשמע", " נשמע")!!.edit)
        // Not commands: these are typed as words.
        assertEquals(null, m("add milk to the list", "hi"))
        assertEquals(null, m("go to the store", "hi"))
    }

    @Test
    fun deleteEverythingInBothLanguages() {
        fun kind(spoken: String) = LocalCommands.match(spoken, "hello there", " there")?.kind
        listOf("Delete everything.", "delete everything please", "Clear everything", "Delete it all", "start over",
            "Delete every thing", "תמחק הכול", "תמחק הכל", "מחק את הכל", "תמחק את כל הטקסט", "תנקה הכל")
            .forEach { assertEquals(it, LocalCommands.Kind.DELETE_ALL, kind(it)) }
        // "undo" / "בטל" (brings everything back right after; otherwise removes the last phrase)
        listOf("Undo", "undo that", "בטל", "תבטל את זה", "bring it back", "תחזיר את זה")
            .forEach { assertEquals(it, LocalCommands.Kind.UNDO, kind(it)) }
        assertEquals(VoiceEdit(" there", ""), LocalCommands.match("undo", "hello there", " there")!!.edit)
        assertEquals(VoiceEdit("", ""), LocalCommands.match("bring it back", "hello there", " there")!!.edit)
        // Not commands.
        assertEquals(null, LocalCommands.match("everything is fine", "hi", null))
        assertEquals(null, LocalCommands.match("הכל בסדר", "hi", null))
    }

    @Test
    fun misheardCommandsStillWork() {
        fun m(spoken: String, before: String = "hi", last: String? = null) = LocalCommands.match(spoken, before, last)
        assertEquals(VoiceEdit("", "\n"), m("No line.")!!.edit)
        assertEquals(VoiceEdit("", "\n"), m("New lane")!!.edit)
        assertEquals(VoiceEdit("", "\n"), m("Knew line.")!!.edit)
        assertEquals(VoiceEdit(" there", ""), m("Delete hat.", "hi there", " there")!!.edit)
        assertEquals(VoiceEdit("", "?"), m("Question marc")!!.edit)
        // Ordinary phrases that only look a bit similar are typed as words.
        assertEquals(null, m("Next time."))
        assertEquals(null, m("Nice line."))
        assertEquals(null, m("Got it."))
        assertEquals(null, m("Delete the photos"))
        assertEquals(3, LocalCommands.distance("kitten", "sitting"))
    }

    @Test
    fun speechModelIsToldTheCommandWords() {
        val live = JSONObject(OpenAi.liveSessionJson(listOf("en"), full = true))
        val kw = live.getJSONObject("session").getJSONObject("audio").getJSONObject("input")
            .getJSONObject("transcription").getJSONArray("keywords")
        assertTrue((0 until kw.length()).map { kw.getString(it) }.containsAll(listOf("new line", "delete that", "שורה חדשה")))
    }

    @Test
    fun commandWordsStayOutOfTheTextField() {
        // While being said, commands aren't shown in the text field.
        listOf("delete", "Delete th", "delete that", "new", "New line", "question", "Question mark", "scratch that",
            "add a question", "תמחק", "תמחק את זה", "סימן שאלה", "replace Monday with")
            .forEach { assertEquals(it, null, LocalCommands.preview(it, smart = true)) }
        // Normal words are shown, and spoken punctuation is shown as the symbol.
        assertEquals("Hello there", LocalCommands.preview("Hello there", smart = true))
        assertEquals("hi?", LocalCommands.preview("hi question mark", smart = true))
        assertEquals("hi", LocalCommands.preview("hi question", smart = true))   // "mark" may follow
        assertEquals("New car", LocalCommands.preview("New car", smart = true))
        assertEquals("שלום", LocalCommands.preview("שלום סימן", smart = true))
    }

    @Test
    fun spokenPunctuationBecomesSymbols() {
        assertEquals("Hi?", LocalCommands.spokenPunctuation("Hi question mark"))
        assertEquals("Hi?", LocalCommands.spokenPunctuation("Hi, question mark."))
        assertEquals("hi? How are you", LocalCommands.spokenPunctuation("hi question mark how are you"))
        assertEquals("I need milk, eggs, and bread.", LocalCommands.spokenPunctuation("I need milk comma eggs comma and bread period"))
        assertEquals("The period of time was long", LocalCommands.spokenPunctuation("The period of time was long"))
        assertEquals("Wow!", LocalCommands.spokenPunctuation("Wow exclamation mark"))
        assertEquals("line one\nline two", LocalCommands.spokenPunctuation("line one new line line two"))
        assertEquals("שלום?", LocalCommands.spokenPunctuation("שלום סימן שאלה"))
        assertEquals("כן, בטח.", LocalCommands.spokenPunctuation("כן פסיק בטח נקודה"))
        assertEquals("Plain words stay the same.", LocalCommands.spokenPunctuation("Plain words stay the same."))
    }

    @Test
    fun readsTheAiAnswer() {
        val reply = JSONObject(
            """{"id":"r1","status":"completed","output":[{"type":"reasoning","summary":[]},
            {"type":"message","role":"assistant","content":[{"type":"output_text",
            "text":"{\"remove\":\" hello\",\"insert\":\"\"}"}]}]}"""
        )
        assertEquals(VoiceEdit(" hello", ""), OpenAi.parseEdit(OpenAi.outputText(reply)!!))
    }

    @Test
    fun requestsHaveTheRightShape() {
        val cmd = JSONObject(OpenAi.commandRequestJson("gpt-6.1-terra", "I said hello", " hello", "delete that", "en", "none"))
        assertEquals("gpt-6.1-terra", cmd.getString("model"))
        assertEquals(false, cmd.getBoolean("store"))
        assertFalse(JSONObject(OpenAi.commandRequestJson("m", "", "", "x", "en", null)).has("reasoning"))
        assertEquals("none", cmd.getJSONObject("reasoning").getString("effort"))
        val fmt = cmd.getJSONObject("text").getJSONObject("format")
        assertEquals("json_schema", fmt.getString("type"))
        assertTrue(fmt.getBoolean("strict"))
        val user = JSONObject(cmd.getJSONArray("input").getJSONObject(1).getString("content"))
        assertEquals("delete that", user.getString("spoken"))

        val live = JSONObject(OpenAi.liveSessionJson(listOf("en", "he"), full = true))
        assertEquals("session.update", live.getString("type"))
        val session = live.getJSONObject("session")
        assertEquals("transcription", session.getString("type"))
        val input = session.getJSONObject("audio").getJSONObject("input")
        assertEquals(24000, input.getJSONObject("format").getInt("rate"))
        assertEquals("audio/pcm", input.getJSONObject("format").getString("type"))
        assertEquals("gpt-live-transcribe", input.getJSONObject("transcription").getString("model"))
        assertEquals(2, input.getJSONObject("transcription").getJSONArray("languages").length())
        val prompt = input.getJSONObject("transcription").getString("prompt")
        assertTrue(prompt.contains("I bought milk, bread, and eggs."))
        assertTrue(prompt.contains("אם ירד גשם, אשאר בבית."))
        assertTrue(prompt.contains("Keep spoken editing commands and punctuation names as words"))
        assertTrue(input.isNull("turn_detection"))
        val basic = JSONObject(OpenAi.liveSessionJson(listOf("en"), full = false))
        assertFalse(basic.getJSONObject("session").getJSONObject("audio").getJSONObject("input")
            .getJSONObject("transcription").has("languages"))
    }

    @Test
    fun wavFileIsValid() {
        val pcm = ByteArray(4800)
        val wav = Wav.encode(pcm, 24000)
        assertEquals(44 + 4800, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        val rate = (wav[24].toInt() and 0xff) or (wav[25].toInt() and 0xff shl 8) or (wav[26].toInt() and 0xff shl 16)
        assertEquals(24000, rate)
    }

    @Test
    fun apiKeysStayOutOfClipboardHistory() {
        assertTrue(ApiKeyStore.looksLikeSecret(("sk-proj-" + "x".repeat(32) + "wXy4")))
        assertTrue(ApiKeyStore.looksLikeSecret(("AIza" + "a".repeat(35))))
        assertFalse(ApiKeyStore.looksLikeSecret("see you tomorrow at the park"))
        assertFalse(ApiKeyStore.looksLikeSecret("https://example.com"))
        assertEquals("sk-abc123", ApiKeyStore.clean("  \"sk-abc\n123\" "))
        assertEquals("sk-pr…wXy4", ApiKeyStore.masked(("sk-proj-" + "x".repeat(32) + "wXy4")))
    }

    @Test
    fun voiceSettingsAreSaved() {
        val s = YboardSettings(voiceLanguages = "he", voiceCommands = false)
        assertEquals(s, YboardSettings.fromJson(s.toJson()))
        assertEquals(listOf("he"), s.voiceLanguageList)
        assertEquals(listOf("he", "en"), YboardSettings().voiceLanguageList)  // "Both": Hebrew and English only
    }
}
