package com.yboard.keyboard

import com.yboard.keyboard.voice.PredictionStream
import com.yboard.keyboard.voice.OpenAi
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PredictionStreamTest {
    @Test fun noPartialWordOrEscapeIsPublished() {
        val json = """{"next":["tomorrow morning","later today","soon"]}"""
        for (length in 0..json.length) {
            val ready = PredictionStream.completeOptions(json.take(length), "you")
            if (ready.isNotEmpty()) assertTrue(length >= json.indexOf("morning\"") + 8)
            assertTrue(ready.all { it in listOf("tomorrow morning", "later today", "soon") })
        }
        assertEquals(emptyList<String>(), PredictionStream.completeOptions("""{"next":["\u05E""", null))
        assertEquals(listOf("שלום"), PredictionStream.completeOptions("""{"next":["\u05E9\u05DC\u05D5\u05DD""" + '"', null))
    }

    @Test fun streamedStructuredOutputPublishesBeforeTheTerminalUsageEvent() {
        val source = Buffer()
        val json = """{"next":["tomorrow morning","later today","soon"]}"""
        for (char in json) source.writeUtf8("data: ${JSONObject().put("type", "response.output_text.delta").put("delta", char.toString())}\n\n")
        source.writeUtf8("data: ${JSONObject().put("type", "response.completed").put("response", JSONObject().put("status", "completed").put("usage", JSONObject().put("input_tokens", 25).put("output_tokens", 12)))}\n\n")
        var updates = 0
        val root = PredictionStream.read(source, "you") {
            updates++
            assertFalse(source.exhausted())
            assertEquals("tomorrow morning", it.first())
        }
        assertEquals(3, updates)
        assertEquals(25, root.getJSONObject("usage").getInt("input_tokens"))
    }

    @Test fun incompleteResponseRetainsUsageForAccounting() {
        val source = Buffer().writeUtf8("data: {\"type\":\"response.incomplete\",\"response\":{\"status\":\"incomplete\",\"usage\":{\"output_tokens\":96}}}\n\n")
        assertEquals(96, PredictionStream.read(source, null) {}.getJSONObject("usage").getInt("output_tokens"))
    }

    @Test(expected = java.io.IOException::class) fun disconnectedStreamDoesNotPretendToBeComplete() {
        PredictionStream.read(Buffer().writeUtf8("data: {\"type\":\"response.created\"}\n\n"), null) {}
    }

    @Test(expected = OpenAi.ApiError::class) fun apiErrorEventStopsTheStream() {
        PredictionStream.read(Buffer().writeUtf8("data: {\"type\":\"error\",\"message\":\"busy\"}\n\n"), null) {}
    }

    @Test fun invalidOptionsAndRepeatedFirstWordsAreExcluded() {
        assertEquals(listOf("later today", "tomorrow"), PredictionStream.clean(listOf("later today", "later tonight", "http://bad", "!!!", "you", "tomorrow"), "you"))
    }
}
