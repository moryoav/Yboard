package com.yboard.keyboard

import com.yboard.keyboard.settings.YboardSettings
import com.yboard.keyboard.voice.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VoiceReliabilityTest {
    @Test fun oldSettingsGainUsableDefaultsWithoutLosingCommandChoice() {
        val settings = YboardSettings.fromJson("{\"voiceCommands\":false,\"voiceLanguages\":\"he\"}")
        assertFalse(settings.voiceCommands)
        assertEquals(listOf("he"), settings.voiceLanguageList)
        assertEquals(TranscriptionQuality.BALANCED, settings.voiceQuality)
        assertEquals(15, settings.voiceSilenceSeconds)
    }

    @Test fun listeningPreferencesSurviveRestartIncludingNoAutoStop() {
        val settings = YboardSettings(voiceQuality = TranscriptionQuality.ACCURATE, voicePauseMs = 1250, voiceSilenceSeconds = 0)
        val loaded = YboardSettings.fromJson(settings.toJson())
        assertEquals(settings, loaded)
        assertEquals(0, loaded.voiceOptions.idleFrames)
        assertEquals(62, loaded.voiceOptions.commandPauseFrames)
    }

    @Test fun invalidPersistedTimingsAreBounded() {
        val loaded = YboardSettings.fromJson("{\"voicePauseMs\":-50,\"voiceSilenceSeconds\":999,\"voiceQuality\":\"unknown\"}")
        assertEquals(250, loaded.voicePauseMs)
        assertEquals(60, loaded.voiceSilenceSeconds)
        assertEquals(TranscriptionQuality.BALANCED, loaded.voiceQuality)
    }

    @Test fun qualityActuallyReachesRealtimeConfiguration() {
        for (quality in TranscriptionQuality.entries) {
            val config = JSONObject(OpenAi.liveSessionJson(listOf("he", "en"), 0, model = "gpt-live-transcribe", delay = quality.delay))
            val transcription = config.getJSONObject("session").getJSONObject("audio").getJSONObject("input").getJSONObject("transcription")
            assertEquals(quality.delay, transcription.getString("delay"))
            assertEquals(2, transcription.getJSONArray("languages").length())
        }
    }

    @Test fun disablingCommandsShowsTheLiteralWordsIncludingSend() {
        for (words in listOf("send", "delete everything", "new line", "hi question mark", "תמחק הכל")) {
            assertEquals(words, LocalCommands.preview(words, smart = false))
        }
    }

    @Test fun editorCanCoalesceOrDelayOurSelectionUpdates() {
        val selection = VoiceSelection(0)
        selection.expect(4)
        selection.expect(0)
        selection.expect(6)
        assertFalse(selection.moved(4, 4))
        assertFalse(selection.moved(6, 6))
        assertFalse(selection.moved(6, 6))
        assertTrue(selection.moved(2, 2))
    }

    @Test fun selectingTextStopsVoiceEvenAtAnExpectedEndpoint() {
        val selection = VoiceSelection(5)
        selection.expect(9)
        assertTrue(selection.moved(5, 9))
    }

    @Test fun disabledSilenceTimeoutNeverFires() {
        val vad = Vad(idleFrames = VoiceOptions(silenceSeconds = 0).idleFrames)
        var stops = 0
        val sink = object : Vad.Sink {
            override fun audio(frame: ShortArray) {}
            override fun phraseEnd() {}
            override fun longSilence() { stops++ }
            override fun level(level: Float) {}
        }
        repeat(4000) { vad.process(ShortArray(480), sink) }
        assertEquals(0, stops)
    }
}
