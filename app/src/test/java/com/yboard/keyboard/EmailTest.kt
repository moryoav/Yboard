package com.yboard.keyboard

import com.yboard.keyboard.keyboard.KeyType
import com.yboard.keyboard.keyboard.Layouts
import com.yboard.keyboard.keyboard.Mode
import com.yboard.keyboard.settings.YboardSettings
import com.yboard.keyboard.text.EmailHelper
import com.yboard.keyboard.text.EmailHelper.Chip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailTest {

    private val mine = "dana.levi@gmail.com"

    @Test
    fun addressBeingTyped() {
        assertEquals("dana@gm", EmailHelper.token("hi dana@gm"))
        assertEquals("b@x.com", EmailHelper.token("a@x.com, b@x.com"))
        assertEquals("", EmailHelper.token("a@x.com "))
    }

    @Test
    fun yourEmailInTheStrip() {
        // Empty box: your address, ready in one tap.
        assertEquals(listOf(null, Chip(mine), null), EmailHelper.chips("", mine))
        assertEquals(listOf(null, null, null), EmailHelper.chips("", ""))
        // Typing its start: the chip finishes it (replacing what you typed).
        assertEquals(listOf(Chip("@gmail.com"), Chip(mine, "da"), null), EmailHelper.chips("da", mine))
        assertEquals(Chip(mine, "DANA"), EmailHelper.chips("DANA", mine)[1])
        // Something else: the usual endings, @gmail.com in the middle.
        assertEquals(listOf(Chip("@walla.co.il"), Chip("@gmail.com"), Chip("@hotmail.com")), EmailHelper.chips("yossi", mine))
    }

    @Test
    fun domainsAfterTheAt() {
        assertEquals(listOf(Chip("walla.co.il"), Chip("gmail.com"), Chip("hotmail.com")), EmailHelper.chips("dana@", ""))
        assertEquals(listOf(null, Chip("hotmail.com", "ho"), null), EmailHelper.chips("dana@ho", ""))
        assertEquals(listOf(null, Chip("gmail.com", "G"), null), EmailHelper.chips("dana@G", ""))
        assertEquals(listOf(Chip(".co.il"), Chip(".com"), Chip(".net")), EmailHelper.chips("dana@company", ""))
        assertEquals(listOf(null, null, null), EmailHelper.chips("dana@gmail.com", ""))
        assertEquals(listOf(null, null, null), EmailHelper.chips("dana@company.co", ""))
    }

    @Test
    fun emailKeys() {
        val row = Layouts.build(Mode.LETTERS, "en", numberRow = false, multiLang = false, email = true).rows.last().keys
        val labels = row.map { it.label }
        assertTrue("@" in labels)
        assertTrue(".com" in labels)
        assertFalse(row.any { it.type == KeyType.COMMA || it.type == KeyType.GLOBE })
        assertEquals(10f, row.sumOf { it.width.toDouble() }.toFloat(), 0.001f)
        // Normal boxes keep the comma.
        val normal = Layouts.build(Mode.LETTERS, "en", numberRow = false, multiLang = true).rows.last().keys
        assertTrue(normal.any { it.type == KeyType.COMMA })
        assertFalse(normal.any { it.label == ".com" })
    }

    @Test
    fun settingsAreSaved() {
        val s = YboardSettings(myEmail = mine, emailHelpers = false, autofillChips = false)
        assertEquals(s, YboardSettings.fromJson(s.toJson()))
        assertTrue(YboardSettings().emailHelpers)
        assertTrue(YboardSettings().autofillChips)
        assertEquals("", YboardSettings().myEmail)
    }
}
