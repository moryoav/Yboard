package com.yboard.keyboard.settings

import com.yboard.keyboard.R
import org.json.JSONArray
import org.json.JSONObject

enum class ToolbarShortcut(val label: String, val icon: Int) {
    MENU("Settings", R.drawable.ic_grid),
    UNDO("Undo", R.drawable.ic_undo),
    THEMES("Themes", R.drawable.ic_palette),
    CLIPBOARD("Clipboard", R.drawable.ic_clipboard),
    EDIT("Text editing", R.drawable.ic_text_edit),
    SPRINKLE("Emoji sprinkle", R.drawable.ic_emoji_sparkle),
    EMOJI("Emoji picker", R.drawable.ic_emoji),
    STICKER("Stickers", R.drawable.ic_sticker),
    MIC("Voice typing", R.drawable.ic_mic);

    val staysWithSuggestions: Boolean get() = this == MENU || this == STICKER || this == MIC
}

data class ToolbarShortcuts(
    val order: List<ToolbarShortcut> = ToolbarShortcut.entries,
    val hidden: Set<ToolbarShortcut> = setOf(ToolbarShortcut.EMOJI),
) {
    val visible: List<ToolbarShortcut> get() = order.filterNot { it in hidden }

    fun withVisibility(shortcut: ToolbarShortcut, visible: Boolean): ToolbarShortcuts =
        copy(hidden = if (visible) hidden - shortcut else hidden + shortcut)

    fun move(shortcut: ToolbarShortcut, offset: Int): ToolbarShortcuts {
        val index = order.indexOf(shortcut)
        val destination = index + offset
        if (index < 0 || destination !in order.indices) return this
        return copy(order = order.toMutableList().apply { add(destination, removeAt(index)) })
    }

    fun toJson(): JSONObject = JSONObject()
        .put("order", JSONArray(order.map { it.name }))
        .put("hidden", JSONArray(hidden.map { it.name }))

    companion object {
        fun fromJson(json: JSONObject?, legacySprinkle: Boolean): ToolbarShortcuts {
            val defaults = ToolbarShortcuts().withVisibility(ToolbarShortcut.SPRINKLE, legacySprinkle)
            val savedOrder = json?.optJSONArray("order")?.shortcuts() ?: return defaults
            val savedHidden = json.optJSONArray("hidden")?.shortcuts()?.toSet() ?: return defaults
            val newShortcuts = defaults.order - savedOrder.toSet()
            return ToolbarShortcuts(
                order = savedOrder + newShortcuts,
                hidden = savedHidden + newShortcuts.filter { it in defaults.hidden },
            )
        }

        private fun JSONArray.shortcuts(): List<ToolbarShortcut> =
            (0 until length()).mapNotNull { index ->
                val name = optString(index)
                ToolbarShortcut.entries.firstOrNull { it.name == name }
            }.distinct()
    }
}
