package com.yboard.keyboard

import com.yboard.keyboard.text.*
import org.junit.Assert.*
import org.junit.Test

class LocalPredictionTest {
    @Test fun fullSentenceSurvivesTheOldContextLimit() {
        val sentence = "I remember " + "the very long conversation about our plans ".repeat(30)
        val input = SentenceContext.capture(sentence + "tom", sentence.length + 3)
        assertEquals(sentence, input.context)
        assertEquals("tom", input.prefix)
        assertTrue(input.context.length > 300)
    }
    @Test fun retainsPreviousSentenceAndCurrentSentenceInBothLanguages() {
        val text = "Discard this. Keep this sentence! Current unfinished wor"
        val input = SentenceContext.capture(text, text.length)
        assertEquals("Keep this sentence! Current unfinished ", input.context)
        assertEquals("wor", input.prefix)
        val hebrew = "משפט ישן. אני בדרך הביתה. נתראה מחר בב"
        assertEquals("אני בדרך הביתה. נתראה מחר ", SentenceContext.capture(hebrew, hebrew.length).context)
        assertEquals("בב", SentenceContext.capture(hebrew, hebrew.length).prefix)
    }
    @Test fun onlyCompatiblePrefixUpdatesShareAWordAnchor() {
        val first = SentenceContext.capture("See you ", 8)
        assertTrue(first.sameWord(SentenceContext.capture("See you tom", 11)))
        assertFalse(first.sameWord(SentenceContext.capture("See you ", 25)))
        assertFalse(first.sameWord(SentenceContext.capture("See me t", 8)))
        assertEquals("don't", SentenceContext.capture("I don't", 7).prefix)
    }
    @Test fun modelContextCanResolveAmbiguousGeometry() {
        val candidates = listOf(SwipeDecoder.Candidate("three", 0.1f), SwipeDecoder.Candidate("there", 0.2f))
        assertEquals("there", GlideRanking.rank(candidates, doubleArrayOf(-8.0, -2.0)).first())
        assertEquals("three", GlideRanking.rank(candidates, doubleArrayOf(-2.0, -8.0)).first())
    }
    @Test fun geometryStillRejectsImpossibleModelChoicesAndInvalidScores() {
        val candidates = listOf(SwipeDecoder.Candidate("there", 0f), SwipeDecoder.Candidate("unrelated", 9f), SwipeDecoder.Candidate("bad", 0.2f))
        assertEquals(listOf("there"), GlideRanking.rank(candidates, doubleArrayOf(-8.0, -0.01, Double.NaN)))
    }
    @Test fun productionGestureCostsDoNotUseWordFrequency() {
        val centers = "qwertyuiop".mapIndexed { i, c -> c to Pt(i.toFloat(), 0f) }.toMap() +
            "asdfghjkl".mapIndexed { i, c -> c to Pt(i + 0.5f, 1f) }.toMap() +
            "zxcvbnm".mapIndexed { i, c -> c to Pt(i + 1.5f, 2f) }.toMap()
        val path = "there".map { centers.getValue(it) }
        fun decode(frequency: Int) = SwipeDecoder { c -> if (c == 't') listOf(WordFreq("there", frequency)) else emptyList() }
            .decodeScored(path, centers, 1f)
        assertEquals(decode(1), decode(255))
        assertEquals("there", decode(1).first().word)
    }
}
