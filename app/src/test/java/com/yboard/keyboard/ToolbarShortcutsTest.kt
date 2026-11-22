package com.yboard.keyboard

import com.yboard.keyboard.settings.ToolbarShortcut.*
import com.yboard.keyboard.settings.ToolbarShortcuts
import com.yboard.keyboard.settings.YboardSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarShortcutsTest {
    @Test
    fun existingSettingsKeepTheirShortcutOrderAndSprinkleVisibility() {
        val oldOrder = listOf(MENU, UNDO, THEMES, CLIPBOARD, EDIT, SPRINKLE, STICKER, MIC)
        assertEquals(oldOrder, YboardSettings.fromJson("{}").toolbarShortcuts.visible)
        val migrated = YboardSettings.fromJson("""{"emojiSprinkle":false}""")
        assertEquals(oldOrder - SPRINKLE, migrated.toolbarShortcuts.visible)
        assertEquals(migrated, YboardSettings.fromJson(migrated.toJson()))
    }

    @Test
    fun hidingAndShowingKeepsTheNewOrderAcrossReloads() {
        val custom = ToolbarShortcuts()
            .move(CLIPBOARD, -3)
            .withVisibility(CLIPBOARD, false)
            .move(STICKER, -1)
            .withVisibility(EMOJI, true)
        val restored = YboardSettings.fromJson(YboardSettings(toolbarShortcuts = custom).toJson())
        assertEquals(custom, restored.toolbarShortcuts)
        val visible = restored.toolbarShortcuts.withVisibility(CLIPBOARD, true).visible
        assertEquals(listOf(CLIPBOARD, MENU, UNDO, THEMES, EDIT, SPRINKLE, STICKER, EMOJI, MIC), visible)
    }

    @Test
    fun allShortcutsCanStayHiddenAcrossReloads() {
        val defaults = ToolbarShortcuts()
        val settings = YboardSettings(toolbarShortcuts = defaults.copy(hidden = defaults.order.toSet()))
        assertTrue(YboardSettings.fromJson(settings.toJson()).toolbarShortcuts.visible.isEmpty())
    }

    @Test
    fun savedOrderIgnoresDuplicatesAndUnknownShortcuts() {
        val restored = YboardSettings.fromJson(
            """{"toolbarShortcuts":{"order":["STICKER","future","STICKER","MENU"],"hidden":["MENU","future"]}}""",
        ).toolbarShortcuts
        val knownOrder = listOf(STICKER, MENU)
        assertEquals(knownOrder + (ToolbarShortcuts().order - knownOrder.toSet()), restored.order)
        assertEquals(setOf(MENU, EMOJI), restored.hidden)
    }
}
