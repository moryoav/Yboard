package com.yboard.keyboard

import com.yboard.keyboard.text.NextWords
import com.yboard.keyboard.voice.LocalCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictionTest {

    @Test
    fun commonPairsWorkFromTheStart() {
        val en = NextWords.inMemory("en")
        assertEquals("you", en.predict("thank", sentenceStart = false).first())
        assertEquals("morning", en.predict("good", sentenceStart = false).first())
        assertEquals("I", en.predict(null, sentenceStart = true).first())
        assertEquals(3, en.predict("xyzzy", sentenceStart = false).size)   // falls back to common words
        val he = NextWords.inMemory("he")
        assertEquals("טוב", he.predict("בוקר", sentenceStart = false).first())
        assertEquals("רבה", he.predict("תודה", sentenceStart = false).first())
    }

    @Test
    fun learnsYourOwnHabits() {
        val en = NextWords.inMemory("en")
        repeat(2) { en.learn("love", "pizza") }
        assertEquals("pizza", en.predict("love", sentenceStart = false).first())
        en.learn("talk", "Dana")                                            // names keep their capital
        assertTrue(en.predict("talk", sentenceStart = false).contains("Dana"))
        en.learnText("see you at the beach. bring snacks", before = "")
        assertEquals("beach", en.predict("the", sentenceStart = false).first())
        assertTrue(en.predict("beach", sentenceStart = false).none { it == "bring" })  // a new sentence started
    }

    @Test
    fun startOfSentencePredictionsAreCapitalized() {
        val en = NextWords.inMemory("en")
        en.learn(null, "maybe")
        en.learn(null, "maybe")
        assertEquals("Maybe", en.predict(null, sentenceStart = true).first())
    }

    @Test
    fun findsTheWordBeforeTheCursor() {
        assertEquals("there", NextWords.lastWordOf("hi there "))
        assertEquals("there", NextWords.lastWordOf("hi there, "))
        assertNull(NextWords.lastWordOf("Hello. "))
        assertNull(NextWords.lastWordOf(""))
        assertEquals("שלום", NextWords.lastWordOf("היי שלום "))
    }

    @Test
    fun sendByVoice() {
        listOf("Send", "send it.", "Send the message", "please send it", "תשלח", "תשלח את זה", "שלח הודעה")
            .forEach { assertEquals(it, LocalCommands.Kind.SEND, LocalCommands.match(it, "hi", null)?.kind) }
        // Talking about sending isn't a command.
        assertNull(LocalCommands.match("I will send it tomorrow", "hi", null))
    }
}
