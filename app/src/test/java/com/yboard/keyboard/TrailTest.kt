package com.yboard.keyboard

import com.yboard.keyboard.keyboard.TrailPainter
import com.yboard.keyboard.settings.TrailColor
import com.yboard.keyboard.settings.YboardSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailTest {

    @Test
    fun trailSettingsAreSaved() {
        val s = YboardSettings(trailWidth = 14f, trailColor = TrailColor.CUSTOM, trailCustomColor = 0xFF22CC88.toInt(), trailGlow = false)
        assertEquals(s, YboardSettings.fromJson(s.toJson()))
        // Settings saved before these existed still load, with the new defaults.
        val old = YboardSettings.fromJson("""{"swipeTrail":true}""")
        assertEquals(TrailColor.THEME, old.trailColor)
        assertTrue(old.trailGlow)
    }

    @Test
    fun thickerByDefaultAndKeptInRange() {
        // The old trail was 7 dp at the finger; the new default is easier to see.
        assertTrue(YboardSettings().trailWidth >= 9f)
        assertEquals(TrailPainter.MAX_WIDTH, TrailPainter.widthDp(YboardSettings(trailWidth = 99f)), 0f)
        assertEquals(TrailPainter.MIN_WIDTH, TrailPainter.widthDp(YboardSettings(trailWidth = 0f)), 0f)
    }
}
