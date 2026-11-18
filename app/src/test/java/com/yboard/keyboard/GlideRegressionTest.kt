package com.yboard.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.yboard.keyboard.settings.*
import com.yboard.keyboard.text.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.random.Random

class GlideRegressionTest {
    @Test fun appSuggestionFlagsDoNotDisableGlide() {
        val plain = InputType.TYPE_CLASS_TEXT
        val keep = plain or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        for (type in listOf(plain, keep, plain or InputType.TYPE_TEXT_VARIATION_FILTER,
            plain or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT)) {
            assertTrue(EditorPolicy.glide(type))
            assertTrue(EditorPolicy.suggestions(type))
        }
        assertTrue(EditorPolicy.glide(plain or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertTrue(EditorPolicy.glide(plain or InputType.TYPE_TEXT_VARIATION_URI))
        assertFalse(EditorPolicy.glide(plain or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertFalse(EditorPolicy.glide(InputType.TYPE_CLASS_NUMBER))
        assertFalse(EditorPolicy.personalized(keep, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
    }

    @Test fun automationAndAffirmationRemainDistinctWithRoundedNoisyPaths() {
        val dict = Dictionary.fromLines("en", File("src/main/assets/dict/en.tsv").readLines().asSequence())
        val centers = Suggester.keyPositions("en").mapValues { (_, p) -> Pt(p.first * 100, p.second * 135 + 67) }
        val decoder = SwipeDecoder { dict.startingWith(it) }
        for (word in listOf("automation", "affirmation", "automatic", "application", "information", "confirmation")) {
            var top = 0
            var offered = 0
            val misses = mutableListOf<String>()
            repeat(30) { trial ->
                val path = SwipeDecoderTest.swipe(word, centers, Random(trial + 81), noise = 0.32f)
                val result = decoder.decode(path, centers, 100f)
                if (result.firstOrNull()?.lowercase() == word) top++
                else misses.add("$trial:$result")
                if (result.any { it.equals(word, true) }) offered++
            }
            println("$word: $top/30 first, $offered/30 offered $misses")
            assertTrue("$word first=$top", top >= 27)
            assertTrue("$word offered=$offered", offered >= 29)
        }
    }

    @Test fun personalUsageBoostsExistingDictionaryWordsAndCanBeDisabled() {
        val user = UserWords.inMemory("en")
        val base = listOf(WordFreq("automation", 100), WordFreq("affirmation", 87))
        repeat(8) { user.learn("automation") }
        assertEquals(164, user.swipeCandidates(base, 'a', true).first { it.word == "automation" }.freq)
        assertEquals(87, user.swipeCandidates(base, 'a', true).first { it.word == "affirmation" }.freq)
        assertEquals(base, user.swipeCandidates(base, 'a', false))
        user.learn("Yoav", 2)
        assertTrue(user.swipeCandidates(emptyList(), 'y', true).any { it.word == "Yoav" })
    }

    @Test fun everyAiFeatureSurvivesSettingsRoundTripAndUsesExplicitReasoning() {
        var models = AiModels()
        AiFeature.entries.forEach { models = models.with(it, AiModel("chosen-${it.name}", "low")) }
        val settings = YboardSettings(aiModels = models)
        assertEquals(settings, YboardSettings.fromJson(settings.toJson()))
        assertEquals("gpt-6-luna", AiModels()[AiFeature.PREDICTION].model)
        assertEquals("none", AiModels()[AiFeature.PREDICTION].reasoning)
        assertNull(AiModel("gpt-4.1-mini").reasoning)
        assertEquals("none", AiModel("gpt-5.4-nano").reasoning)
        assertNull(AiModel("gpt-5.4-nano", "default").reasoning)
    }
}
