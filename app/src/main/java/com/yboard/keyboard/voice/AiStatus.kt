package com.yboard.keyboard.voice

import com.yboard.keyboard.settings.AiFeature
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Request health only. Never stores typed text, predictions or API keys. */
object AiStatus {
    private val state = MutableStateFlow<Map<AiFeature, String>>(emptyMap())
    val results = state.asStateFlow()
    fun report(feature: AiFeature, status: String) { state.update { it + (feature to status) } }
}
