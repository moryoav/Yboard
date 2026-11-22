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

    @Test fun learnedUsageBreaksCloseGlideTiesAndPreservesClearWinners() {
        val user = UserWords.inMemory("en")
        user.learn("Automation", 7)
        val candidates = listOf(SwipeDecoder.Candidate("automaton", 0f), SwipeDecoder.Candidate("automation", 0f))
        val close = doubleArrayOf(-2.10, -2.20)
        assertEquals("automaton", GlideRanking.rank(candidates, close).first())
        assertEquals("automation", GlideRanking.rank(candidates, close, user).first())
        user.learn("automation", 100)
        assertEquals(0.25, UserWords.rankingBonus(user.count("automation")), 0.0)
        assertEquals("automaton", GlideRanking.rank(candidates, doubleArrayOf(-2.10, -2.40), user).first())
        val clearPath = listOf(candidates[0], candidates[1].copy(cost = 0.04f))
        assertEquals("automaton", GlideRanking.rank(clearPath, close, user).first())
    }

    @Test fun personalCompletionOnlyRanksMatchingCandidatesForTypedPrefixes() {
        val user = UserWords.inMemory("en")
        user.learn("automation", 7)
        user.learn("example", 100)
        val candidates = listOf(ScoredWord("automaton", -2.10), ScoredWord("automation", -2.20), ScoredWord("example", -2.21))
        assertEquals(listOf("automation", "automaton"), Suggester.modelCompletions("automat", candidates, null, user))
        assertEquals(listOf("Automaton", "Automation"), Suggester.modelCompletions("Automat", candidates, null, null))
        assertEquals(listOf("automaton", "automation", "example"), Suggester.modelCompletions("", candidates, null, user))
    }

    @Test fun hebrewCountsUseTheSameCompletionBonus() {
        val user = UserWords.inMemory("he")
        user.learn("בבית", 4)
        val candidates = listOf(ScoredWord("בבוקר", -2.10), ScoredWord("בבית", -2.20))
        assertEquals(listOf("בבית", "בבוקר"), Suggester.modelCompletions("בב", candidates, null, user))
        user.clear()
        assertEquals(listOf("בבוקר", "בבית"), Suggester.modelCompletions("בב", candidates, null, user))
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
