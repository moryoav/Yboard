package com.yboard.keyboard

import com.yboard.keyboard.settings.YboardSettings
import com.yboard.keyboard.voice.ApiKeyStore
import com.yboard.keyboard.voice.DictatedTimes
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
    fun longHesitationsKeepTheSameTurnWithoutStreamingTheWholePause() {
        val vad = Vad()
        val c = Counter()
        repeat(50) { vad.process(noise(40), c) }            // 1 s of room noise
        repeat(40) { vad.process(voice(6000, it), c) }      // 0.8 s of talking
        repeat(275) { vad.process(noise(40), c) }           // 5.5 s hesitation
        assertEquals(0, c.ends)
        assertFalse(vad.inSpeech)
        assertTrue("sent ${c.audio} frames", c.audio in 40..(40 + 10 + 12))
        repeat(30) { vad.process(voice(5000, it), c) }
        repeat(140) { vad.process(noise(40), c) }
        assertEquals(0, c.ends)
        assertTrue(c.audio >= 70)
        vad.finish(c)
        vad.finish(c)
        assertEquals(1, c.ends)
    }

    @Test
    fun commandsEndAfterAShortPause() {
        assertEquals(9, LocalCommands.quickEndFrames("New line"))            // nothing longer starts like this
        assertEquals(15, LocalCommands.quickEndFrames("Delete that"))        // "delete that word" might follow
        assertEquals(9, LocalCommands.quickEndFrames("question mark"))
        assertEquals(0, LocalCommands.quickEndFrames("I went to the"))
        val vad = Vad()
        val c = Counter()
        repeat(50) { vad.process(noise(40), c) }
        repeat(30) { vad.process(voice(6000, it), c) }
        vad.quickEndFrames = 9
        repeat(10) { vad.process(noise(40), c) }
        assertEquals(1, c.ends)
    }

    @Test
    fun lateCommandHintClosesAnOpenTurnDuringSilence() {
        val vad = Vad()
        val c = Counter()
        repeat(30) { vad.process(voice(6000, it), c) }
        repeat(100) { vad.process(noise(40), c) }
        assertEquals(0, c.ends)
        vad.quickEndFrames = LocalCommands.quickEndFrames("Delete that")
        vad.process(noise(40), c)
        assertEquals(1, c.ends)
        repeat(30) { vad.process(voice(6000, it), c) }
        repeat(100) { vad.process(noise(40), c) }
        assertEquals(1, c.ends)
    }

    @Test
    fun bufferLimitKeepsAudioFromTheNextTurnAndTimeoutRemainsSeparate() {
        val vad = Vad(maxTurnFrames = 20, idleFrames = 25)
        val c = Counter()
        repeat(27) { vad.process(voice(6000, it), c) }
        assertEquals(27, c.audio)
        assertEquals(1, c.ends)
        repeat(25) { vad.process(noise(40), c) }
        assertEquals(1, c.ends)
        assertEquals(1, c.silences)
        vad.finish(c)
        assertEquals(2, c.ends)
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
        assertEquals(" And bought milk.", VoiceEdits.forInsert("I went to the store", "And bought milk."))
        assertEquals(" John said hi.", VoiceEdits.forInsert("I talked to", "John said hi."))
        assertEquals("?", VoiceEdits.forInsert("hi", "?"))
        assertEquals("there", VoiceEdits.forInsert("hi ", "there"))
        assertEquals(" מה שלומך?", VoiceEdits.forInsert("היי", "מה שלומך?"))
    }

    @Test
    fun continuationCasePreservesSentenceStartsNamesAndAcronyms() {
        fun after(before: String, words: String, names: Set<String> = emptySet()) =
            VoiceEdits.continuationCase(before, words, names)
        assertEquals("using the keyboard", after("I typed this", "Using the keyboard"))
        assertEquals("wrong words", after("It capitalized", "Wrong words"))
        assertEquals("And then?", after("That works.", "And then?"))
        assertEquals("And then?", after("“That works.” ", "And then?"))
        assertEquals("And then?", after("That works\n ", "And then?"))
        assertEquals("John used NASA data", after("I heard", "John used NASA data"))
        assertEquals("RIGHT keys", after("I used the", "RIGHT keys"))
        assertEquals("Right Now", after("I listened to", "Right Now", setOf("right")))
        assertEquals("I stayed", after("And", "I stayed"))
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
            "I need to replace the battery", "Instead, let's stay home", "Change is always hard",
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
    fun specificVoiceDeletionsKeepTheRestOfTheDraft() {
        fun apply(before: String, spoken: String): String {
            val match = LocalCommands.match(spoken, before, null)!!
            assertEquals(LocalCommands.Kind.REMOVE_TEXT, match.kind)
            assertTrue(before.endsWith(match.edit.remove))
            return before.removeSuffix(match.edit.remove) + match.edit.insert
        }
        assertEquals("I'm testing this.", apply("I'm testing this feature.", "Remove the word feature"))
        assertEquals("Feature is ready.", apply("Feature feature is ready.", "Remove the word feature"))
        assertEquals("We can go.", apply("We can go now.", "Instead of that, no, remove the word now"))
        assertEquals("One.", apply("One Two Three.", "Delete the last 2 words."))
        assertEquals("First step.", apply("First step. Green red blue.", "Erase the last three words."))
        assertEquals("Keep this.", apply("Keep this. Tail words can all go", "Remove the last three words. Remove everything after the last period."))
        assertEquals("No full stop here", apply("No full stop here", "Remove everything after the last period"))
        assertEquals("Only these two", apply("Only these two", "Remove the word absent"))
        assertEquals("Only these two", apply("Only these two", "Remove the last ten words"))
        assertEquals(null, LocalCommands.match("Remove the word feature from the title", "Feature is ready.", null))
        val dictation = "I said remove the word feature when we talked."
        assertEquals(dictation, LocalCommands.preview(dictation, smart = true))
        assertEquals(null, LocalCommands.trailingEdit(dictation))
        assertTrue(VoiceEdits.looksLikeCommand("Replace Monday with now"))
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
    fun speechModelIsToldTheCommandWordsInConfiguredLanguages() {
        fun keywords(languages: List<String>): List<String> {
            val live = JSONObject(OpenAi.liveSessionJson(languages, full = true))
            val kw = live.getJSONObject("session").getJSONObject("audio").getJSONObject("input")
                .getJSONObject("transcription").getJSONArray("keywords")
            return (0 until kw.length()).map { kw.getString(it) }
        }
        assertTrue(keywords(listOf("en", "he")).containsAll(listOf("new line", "delete that", "שורה חדשה")))
        assertFalse("שורה חדשה" in keywords(listOf("en")))
        assertFalse("new line" in keywords(listOf("he")))
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
        assertEquals("I typed this.", LocalCommands.preview("I typed this. Delete that.", smart = true))
        assertEquals("One sentence. Another.", LocalCommands.preview("One sentence. Another. Send.", smart = true))
        assertEquals("I will replace the batteries.", LocalCommands.preview("I will replace the batteries.", smart = true))
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
        assertEquals("Use e.g. milk, eggs and bread.", LocalCommands.spokenPunctuation("Use e.g. milk comma eggs and bread."))
        assertEquals("I typed. using the keyboard", LocalCommands.spokenPunctuation("I typed. using the keyboard"))
    }

    @Test
    fun spokenPeriodsWorkInsideContinuousDictation() {
        val examples = listOf(
            "I'll be there at six period see you soon." to "I'll be there at six. See you soon.",
            "I'll call you, period. See you soon." to "I'll call you. See you soon.",
            "I'll call you. Period. See you soon." to "I'll call you. See you soon.",
            "Have a nice day period" to "Have a nice day.",
            "We can stop now period I will email you period" to "We can stop now. I will email you.",
            "אני מגיע בשש נקודה נתראה בקרוב." to "אני מגיע בשש. נתראה בקרוב.",
        )
        for ((heard, expected) in examples) {
            assertEquals(heard, expected, LocalCommands.spokenPunctuation(heard))
            assertEquals(heard, expected, LocalCommands.preview(heard, smart = true))
        }
    }

    @Test
    fun literalPunctuationNamesStayInTheText() {
        val examples = listOf(
            "There is a 30-day trial period.",
            "It was the first period. We missed it.",
            "What period are you studying?",
            "We reviewed the entire period",
            "This period of time was too short.",
            "It was a long period, which ended yesterday.",
            "I said “period.”",
            "I forgot the question mark.",
            "A comma goes here and a colon goes there.",
            "The train came to a full stop.",
            "Use comma-separated values.",
            "זו נקודה חשובה.",
        )
        for (heard in examples) assertEquals(heard, heard, LocalCommands.spokenPunctuation(heard))
        assertEquals(null, LocalCommands.match("“period.”", "I said", null))
        assertEquals("“period.”", LocalCommands.preview("“period.”", smart = true))
    }

    @Test
    fun otherNamedMarksAndLayoutWorkDuringDictation() {
        val examples = listOf(
            "Bring these things colon milk comma eggs comma and bread period" to "Bring these things: milk, eggs, and bread.",
            "I waited semicolon we left." to "I waited; we left.",
            "Are you coming? Question mark. good." to "Are you coming? Good.",
            "He asked, “Are you coming question mark”" to "He asked, “Are you coming?”",
            "Yes exclamation point then we can go full stop" to "Yes! Then we can go.",
            "Well ellipsis I guess so." to "Well… I guess so.",
            "first line new line second line new paragraph third line" to "first line\nsecond line\n\nthird line",
            "הנה הרשימה נקודתיים לחם פסיק חלב נקודה" to "הנה הרשימה: לחם, חלב.",
            "אתה מגיע סימן שאלה בטח סימן קריאה" to "אתה מגיע? בטח!",
            "רגע שלוש נקודות אני בדרך" to "רגע… אני בדרך",
        )
        for ((heard, expected) in examples) assertEquals(heard, expected, LocalCommands.spokenPunctuation(heard))
        val standalone = mapOf("dot" to ".", "dash" to " -", "hyphen" to "-", "at sign" to "@", "מקף" to "-")
        for ((heard, expected) in standalone) {
            assertEquals(heard, expected, LocalCommands.match(heard, "text", null)?.edit?.insert)
        }
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
    fun formatsExplicitEnglishClockTimesAndPreservesPunctuation() {
        assertEquals("Yes, I'll need to pick her up at 22:00.",
            DictatedTimes.format("Yes, I'll need to pick her up at ten p.m."))
        assertEquals("Meet at 08:05 Monday, 16:45 Tuesday, or 17:30 tomorrow.",
            DictatedTimes.format("Meet at eight oh five a.m. Monday, four forty-five p.m. Tuesday, or 5:30pm tomorrow."))
        assertEquals("Midnight is 00:00. It ends at 12:00.",
            DictatedTimes.format("Midnight is twelve a.m. It ends at 12 p.m."))
        assertEquals("The call is at 22:00!", DictatedTimes.format("The call is at ten p.m.!"))
    }

    @Test
    fun formatsExplicitHebrewClockTimesAndKeepsAmbiguity() {
        assertEquals("אאסוף אותה ב-22:00 וניפגש ב-07:30.",
            DictatedTimes.format("אאסוף אותה בעשר בלילה וניפגש בשבע וחצי בבוקר."))
        assertEquals("ניפגש ב-00:00 או ב-14:15.",
            DictatedTimes.format("ניפגש בשתים עשרה בלילה או בשתיים ורבע אחר הצהריים."))
        val unchanged = listOf("See you at ten.", "I have ten tickets.", "Meet at 22:00.", "13:10 p.m.",
            "twenty one p.m.", "10 AM/PM", "ניפגש בעשר.", "ניפגש בשש בלילה.")
        for (text in unchanged) assertEquals(text, DictatedTimes.format(text))
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
        assertTrue(prompt.contains("Add punctuation automatically"))
        assertTrue(prompt.contains("Keep a sentence together through hesitations"))
        assertTrue(prompt.contains("I bought milk, bread, and eggs."))
        assertTrue(prompt.contains("אם ירד גשם, אשאר בבית."))
        assertTrue(prompt.contains("Use digits for explicit quantities and HH:mm for unambiguous clock times. Never infer AM or PM."))
        assertTrue(prompt.contains("I bought 3 tickets. I'll pick her up at 22:00."))
        assertTrue(prompt.contains("קניתי 3 כרטיסים. אאסוף אותה ב-22:00."))
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
