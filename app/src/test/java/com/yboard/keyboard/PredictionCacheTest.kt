package com.yboard.keyboard

import com.yboard.keyboard.text.*
import org.junit.Assert.*
import org.junit.Test

class PredictionCacheTest {
    @Test fun completedWordsRemainAvailableAsPrefixGrows() {
        val cache = PredictionCache()
        val position = PredictionPosition("See you ", 8)
        cache.put(position, listOf("tomorrow morning", "later today", "soon"))
        assertEquals(listOf("tomorrow", "later", "soon"), cache.words(position))
        assertEquals(listOf("tomorrow"), cache.words(position.copy(prefix = "tom")))
        assertTrue(cache.words(position.copy(prefix = "xyz")).isEmpty())
        assertTrue(cache.complete(position))
    }

    @Test fun consecutiveAcceptedWordsUsePhraseTailsWithoutAnotherResponse() {
        val cache = PredictionCache()
        cache.put(PredictionPosition("I will ", 7), listOf("see you tomorrow", "call you later"))
        assertEquals(listOf("you"), cache.words(PredictionPosition("I will see ", 11)))
        assertEquals(listOf("tomorrow"), cache.words(PredictionPosition("I will see you ", 15)))
        assertTrue(cache.words(PredictionPosition("I will see you tomorrow ", 24)).isEmpty())
        assertTrue(cache.words(PredictionPosition("I will meet ", 12)).isEmpty())
    }

    @Test fun changedSentenceCursorAndSessionNeverReuseAnOldContinuation() {
        val cache = PredictionCache()
        cache.put(PredictionPosition("I will ", 7), listOf("see you tomorrow"))
        assertTrue(cache.words(PredictionPosition("I will see. ", 12)).isEmpty())
        assertTrue(cache.words(PredictionPosition("I will see\n", 11)).isEmpty())
        assertTrue(cache.words(PredictionPosition("I will see ", 50)).isEmpty())
        assertTrue(cache.words(PredictionPosition("We will see ", 12)).isEmpty())
        cache.clear()
        assertTrue(cache.words(PredictionPosition("I will see ", 11)).isEmpty())
    }

    @Test fun longEditorTailsRemainCompatibleWithoutWeakeningTheAnchorCheck() {
        val cache = PredictionCache()
        val before = "x".repeat(292) + " I will "
        cache.put(PredictionPosition(before, 300), listOf("see you tomorrow"))
        assertEquals(listOf("you"), cache.words(PredictionPosition((before + "see ").takeLast(300), 304)))
        assertTrue(cache.words(PredictionPosition((before + "see ").takeLast(300), 305)).isEmpty())
    }

    @Test fun cachesExpireAndFailedPartialResponsesCanBeRemoved() {
        var now = 1000L
        val cache = PredictionCache { now }
        val position = PredictionPosition("See you ", 8)
        cache.put(position, listOf("soon"), complete = false)
        assertFalse(cache.complete(position))
        cache.remove(position)
        assertTrue(cache.words(position).isEmpty())
        cache.put(position, listOf("soon"))
        now += 120_001
        assertFalse(cache.complete(position))
        assertTrue(cache.words(position).isEmpty())
    }
}
