package com.yboard.keyboard.settings

import org.json.JSONObject

enum class AiFeature(val title: String, val defaultModel: String, val text: Boolean = true) {
    PREDICTION("Next-word predictions", "gpt-6-luna"),
    COMMAND("Voice commands", "gpt-6.1-terra"),
    SPRINKLE("Emoji sprinkle", "gpt-6.1-terra"),
    SPELLING("Name spellings", "gpt-6.1-terra"),
    LIVE("Live transcription", "gpt-live-transcribe", false),
    FILE("Recorded transcription fallback", "gpt-transcribe", false),
}

data class AiModel(val model: String, val effort: String = "none") {
    /** Non-reasoning models do not accept a reasoning parameter. */
    val reasoning: String? get() = effort.takeUnless {
        it == "default" || model.startsWith("gpt-4") || model.contains("transcrib") || model == "whisper-1"
    }
}

data class AiModels(val choices: Map<AiFeature, AiModel> = emptyMap()) {
    operator fun get(feature: AiFeature): AiModel = choices[feature] ?: AiModel(feature.defaultModel)
    fun with(feature: AiFeature, choice: AiModel) = copy(choices = choices + (feature to choice))
    fun toJson() = JSONObject().apply {
        choices.forEach { (feature, choice) ->
            put(feature.name, JSONObject().put("model", choice.model).put("effort", choice.effort))
        }
    }

    companion object {
        val efforts = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "default")
        fun effortsFor(model: String) = if (model.startsWith("gpt-6")) efforts.filterNot { it == "minimal" } else efforts
        fun fromJson(o: JSONObject?): AiModels {
            if (o == null) return AiModels()
            return AiModels(AiFeature.entries.mapNotNull { feature ->
                val entry = o.optJSONObject(feature.name) ?: return@mapNotNull null
                val model = entry.optString("model").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                feature to AiModel(model, entry.optString("effort", "none").takeIf { it in efforts } ?: "none")
            }.toMap())
        }
    }
}
