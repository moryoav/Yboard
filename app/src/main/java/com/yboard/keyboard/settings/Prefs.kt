package com.yboard.keyboard.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Single source of truth for settings. The keyboard service and the settings app run
 * in the same process, so changes made in the app show up on the keyboard instantly.
 */
object Prefs {
    private const val FILE = "yboard"
    private const val KEY_SETTINGS = "settings"
    private const val KEY_CLIPS = "clips"
    private const val KEY_RECENT_EMOJI = "recent_emoji"

    private var sp: SharedPreferences? = null
    private val _state = MutableStateFlow(YboardSettings())
    val state: StateFlow<YboardSettings> get() = _state
    val current: YboardSettings get() = _state.value

    private val listeners = CopyOnWriteArraySet<(YboardSettings) -> Unit>()

    fun init(context: Context) {
        if (sp != null) return
        val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        sp = prefs
        _state.value = YboardSettings.fromJson(prefs.getString(KEY_SETTINGS, null))
    }

    fun update(transform: (YboardSettings) -> YboardSettings) {
        val next = transform(_state.value)
        if (next == _state.value) return
        _state.value = next
        sp?.edit()?.putString(KEY_SETTINGS, next.toJson())?.apply()
        listeners.forEach { it(next) }
    }

    fun updateTheme(transform: (KeyboardTheme) -> KeyboardTheme) =
        update { s -> s.copy(theme = transform(s.theme)) }

    fun addListener(l: (YboardSettings) -> Unit) { listeners.add(l) }
    fun removeListener(l: (YboardSettings) -> Unit) { listeners.remove(l) }

    // ---- Clipboard history & recent emoji (small string lists) ----

    fun loadList(key: String): MutableList<String> {
        val raw = sp?.getString(key, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { arr.getString(it) }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun saveList(key: String, list: List<String>) {
        sp?.edit()?.putString(key, JSONArray(list).toString())?.apply()
    }

    fun clips() = loadList(KEY_CLIPS)
    fun saveClips(list: List<String>) = saveList(KEY_CLIPS, list)
    fun recentEmoji() = loadList(KEY_RECENT_EMOJI)
    fun saveRecentEmoji(list: List<String>) = saveList(KEY_RECENT_EMOJI, list)
}
