package com.yboard.keyboard

import com.yboard.keyboard.settings.*
import com.yboard.keyboard.stickers.StickerImages
import com.yboard.keyboard.stickers.StickerRequestGate
import com.yboard.keyboard.usage.*
import org.junit.Assert.*
import org.junit.Test

class StickerGenerationTest {
    @Test fun oldSettingsGainAnImageModelWithoutChangingExistingChoices() {
        val old = YboardSettings(aiModels = AiModels().with(AiFeature.COMMAND, AiModel("custom-model", "high")))
        val restored = YboardSettings.fromJson(old.toJson())
        assertEquals("custom-model", restored.aiModels[AiFeature.COMMAND].model)
        assertEquals(AiModel("gpt-image-2.6-flare", "low"), restored.aiModels[AiFeature.STICKER])
        assertFalse(AiFeature.STICKER.text)
    }
    @Test fun selectedImageModelAndPlanningSurviveRestart() {
        val settings = YboardSettings(aiModels = AiModels().with(AiFeature.STICKER, AiModel("gpt-image-2.6-sunburst", "medium")))
        assertEquals(settings, YboardSettings.fromJson(settings.toJson()))
    }
    @Test fun imageRequestUsesChosenModelTransparencyAndOriginalMessage() {
        val message = "Hello \"world\"!\nשלום"
        val request = StickerImages.requestJson(message, AiModel("gpt-image-2.6-flare", "low"))
        assertEquals("gpt-image-2.6-flare", request.getString("model"))
        assertEquals("transparent", request.getString("background"))
        assertEquals("png", request.getString("output_format"))
        assertEquals("low", request.getString("complexity"))
        assertEquals(1, request.getInt("n"))
        assertTrue(request.getString("prompt").contains("שלום"))
        assertFalse(request.has("reasoning"))
    }
    @Test fun legacyImageModelDoesNotReceiveUnsupportedPlanning() {
        val request = StickerImages.requestJson("Hi", AiModel("gpt-image-2.5-flare", "low"))
        assertEquals("gpt-image-2.5-flare", request.getString("model"))
        assertFalse(request.has("complexity"))
    }
    @Test fun emptyOrOversizedMessagesAreRejectedBeforeARequest() {
        for (message in listOf(" ", "a".repeat(1501))) {
            assertThrows(IllegalArgumentException::class.java) { StickerImages.requestJson(message, AiModel("gpt-image-2.6-flare")) }
        }
    }
    @Test fun lateResultsCannotReplaceARecreationOrClosedSession() {
        val gate = StickerRequestGate()
        val first = gate.next(); val second = gate.next()
        assertFalse(gate.accepts(first)); assertTrue(gate.accepts(second))
        gate.close()
        assertFalse(gate.accepts(second))
        assertThrows(IllegalStateException::class.java) { gate.next() }
    }
    @Test fun imageInputAndOutputUseTheirImageApiRates() {
        val charge = UsagePricing.estimate("gpt-image-2.6-flare", TokenUsage(input = 1000, cached = 200, output = 2000))
        assertEquals(4_000_000L, charge.input)
        assertEquals(250_000L, charge.cached)
        assertEquals(60_000_000L, charge.output)
        assertFalse(charge.incomplete)
    }
}
