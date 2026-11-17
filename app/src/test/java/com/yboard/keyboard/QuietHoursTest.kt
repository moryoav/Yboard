package com.yboard.keyboard

import com.yboard.keyboard.settings.QuietHours
import com.yboard.keyboard.settings.YboardSettings
import com.yboard.keyboard.ui.Screen
import com.yboard.keyboard.ui.SettingsIndex
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalTime

class QuietHoursTest {
    private fun time(value: String) = LocalTime.parse(value)

    @Test fun existingSettingsKeepQuietHoursOff() {
        val old = YboardSettings(haptics = true, sound = true, soundStyle = "wood")
        val json = JSONObject(old.toJson()).apply { remove("quietHours") }
        assertEquals(old, YboardSettings.fromJson(json.toString()))
        assertFalse(QuietHours().isActiveAt(time("23:00")))
    }

    @Test fun overnightScheduleIncludesStartAndExcludesEnd() {
        val quiet = QuietHours(enabled = true)
        for (value in listOf("22:00", "23:59:59", "00:00", "06:59:59")) {
            assertTrue(value, quiet.isActiveAt(time(value)))
        }
        for (value in listOf("21:59:59", "07:00", "12:00")) {
            assertFalse(value, quiet.isActiveAt(time(value)))
        }
    }

    @Test fun daytimeScheduleIncludesStartAndExcludesEnd() {
        val quiet = QuietHours(enabled = true, startMinute = 9 * 60, endMinute = 17 * 60)
        for (value in listOf("09:00", "12:00", "16:59:59")) assertTrue(value, quiet.isActiveAt(time(value)))
        for (value in listOf("08:59:59", "17:00", "23:00", "00:00")) assertFalse(value, quiet.isActiveAt(time(value)))
    }

    @Test fun equalTimesMeanAllDayIncludingMidnight() {
        for (minute in listOf(0, 22 * 60)) {
            val quiet = QuietHours(enabled = true, startMinute = minute, endMinute = minute)
            for (value in listOf("00:00", "07:00", "22:00", "23:59:59")) assertTrue(quiet.isActiveAt(time(value)))
            assertFalse(quiet.copy(enabled = false).isActiveAt(time("22:00")))
        }
    }

    @Test fun soundAndVibrationCanBeSuppressedIndependentlyAndResumeAtEnd() {
        for (muteSound in listOf(true, false)) for (muteVibration in listOf(true, false)) {
            val quiet = QuietHours(enabled = true, muteSound = muteSound, muteVibration = muteVibration)
            assertEquals(muteSound, quiet.suppressesSoundAt(time("23:00")))
            assertEquals(muteVibration, quiet.suppressesVibrationAt(time("23:00")))
            assertFalse(quiet.suppressesSoundAt(time("07:00")))
            assertFalse(quiet.suppressesVibrationAt(time("07:00")))
            assertFalse(quiet.copy(enabled = false).suppressesSoundAt(time("23:00")))
            assertFalse(quiet.copy(enabled = false).suppressesVibrationAt(time("23:00")))
        }
    }

    @Test fun scheduleAndUsualFeedbackSettingsSurviveSavingAndLookReset() {
        val original = YboardSettings(
            sound = true, soundVolume = 0.7f, soundStyle = "wood", haptics = false,
            quietHours = QuietHours(true, 23 * 60 + 15, 6 * 60 + 45, muteSound = true, muteVibration = false),
        )
        val loaded = YboardSettings.fromJson(original.toJson())
        assertEquals(original, loaded)
        assertEquals(original.quietHours, loaded.withDefaultLook(true).quietHours)
        assertTrue(loaded.sound)
        assertFalse(loaded.haptics)
        assertEquals(0.7f, loaded.soundVolume)
        assertEquals("wood", loaded.soundStyle)
    }

    @Test fun invalidSavedTimesFallBackWithoutLosingOtherSettings() {
        val original = YboardSettings(soundStyle = "wood", hapticStrength = 0.9f)
        val json = JSONObject(original.toJson()).apply {
            put("quietHours", JSONObject("""{"enabled":true,"startMinute":-1,"endMinute":1440,"muteSound":false}"""))
        }
        val loaded = YboardSettings.fromJson(json.toString())
        assertEquals(QuietHours(enabled = true, muteSound = false), loaded.quietHours)
        assertEquals("wood", loaded.soundStyle)
        assertEquals(0.9f, loaded.hapticStrength)
        assertFalse(QuietHours(true, -1, 1440).isActiveAt(time("23:00")))
    }

    @Test fun settingsSearchFindsQuietHours() {
        for (query in listOf("quiet hours", "bedtime", "schedule", "שעות שקטות")) {
            assertTrue(query, SettingsIndex.search(query).any { it.title == "Quiet hours" && it.screen == Screen.SOUND })
        }
    }
}
