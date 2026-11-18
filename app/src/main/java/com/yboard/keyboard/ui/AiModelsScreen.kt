package com.yboard.keyboard.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yboard.keyboard.R
import com.yboard.keyboard.settings.*
import com.yboard.keyboard.voice.ApiKeyStore
import com.yboard.keyboard.voice.OpenAi
import com.yboard.keyboard.voice.AiStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AiModelsScreen(settings: YboardSettings, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var available by remember { mutableStateOf(emptyList<String>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<AiFeature?>(null) }
    val statuses by AiStatus.results.collectAsState()
    ScreenScaffold("AI models", onBack) {
        LocalPredictionSection()
        AiUsageSection()
        HintText("Qwen handles word predictions and swipe ranking on your phone. Choose cloud models for the other AI features below.")
        Section("Text suggestions & editing") {
            AiFeature.entries.filter { it.text && it != AiFeature.PREDICTION }.forEach { feature ->
                val choice = settings.aiModels[feature]
                NavRow(R.drawable.ic_tune, feature.title,
                    "${choice.model} · Reasoning: ${reasoningLabel(choice)}") { editing = feature }
                statuses[feature]?.let { HintText(it) }
            }
        }
        Section("Speech recognition") {
            listOf(AiFeature.LIVE, AiFeature.FILE).forEach { feature ->
                NavRow(R.drawable.ic_mic, feature.title,
                    "${settings.aiModels[feature].model} · No reasoning control") { editing = feature }
            }
        }
        Section("Image generation") {
            val feature = AiFeature.STICKER
            val choice = settings.aiModels[feature]
            val planning = if (choice.model.startsWith("gpt-image-2.6") && choice.effort != "none") choice.effort else "model default"
            NavRow(R.drawable.ic_sticker, feature.title,
                "${choice.model} · Planning: $planning") { editing = feature }
            HintText("Creates a transparent sticker from your message. Preview, recreate and send it from the keyboard. Image generation uses your OpenAI key and is billed separately from text.")
            statuses[feature]?.let { HintText(it) }
        }
        HintText("Word predictions use local Qwen. Cloud text models are used only for the other features listed here. Speech models must support their transcription endpoint.")
        OutlinedButton(onClick = {
            val key = ApiKeyStore.get(context)
            if (key == null) error = "Add your API key in Voice typing first."
            else {
                loading = true
                error = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { OpenAi.models(key) } }
                    result.onSuccess { available = it }.onFailure { error = OpenAi.friendly(it) }
                    loading = false
                }
            }
        }, enabled = !loading, modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) {
            Text(if (loading) "Loading models…" else "Load models from my account")
        }
        if (available.isNotEmpty()) HintText("${available.size} models loaded. Open a feature to search them.")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
    }
    editing?.let { feature ->
        val choice = settings.aiModels[feature]
        var model by remember(feature) { mutableStateOf(choice.model) }
        var effort by remember(feature) { mutableStateOf(choice.effort) }
        var effortMenu by remember { mutableStateOf(false) }
        val noReasoning = !feature.text || model.startsWith("gpt-4")
        val image = feature == AiFeature.STICKER
        val imagePlanning = image && model.startsWith("gpt-image-2.6")
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(feature.title) },
            text = {
                Column {
                    OutlinedTextField(model, { model = it.trim() }, label = { Text("Model ID or search") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrect = false))
                    val suggested = if (image) listOf("gpt-image-2.6-flare", "gpt-image-2.6-sunburst", "gpt-image-2.5-flare", "gpt-image-2.5-sunburst") else emptyList()
                    val search = if (image && (model == choice.model || model in suggested)) "" else model
                    val matches = (listOf(feature.defaultModel, choice.model) + suggested + available.filter { !image || it.startsWith("gpt-image") })
                        .distinct().filter { it.contains(search, true) }
                    if (matches.isNotEmpty()) LazyColumn(Modifier.heightIn(max = 160.dp)) {
                        items(matches.take(40)) { id ->
                            Text(id, Modifier.fillMaxWidth().clickable { model = id }.padding(vertical = 12.dp),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(if (image) "Image planning" else "Reasoning", style = MaterialTheme.typography.labelLarge)
                    if (noReasoning && !imagePlanning) Text(if (image) "Uses the image model's default planning" else if (feature.text) "None (this model has no reasoning step)" else "Not supported for transcription")
                    else Box {
                        val shownEffort = if (imagePlanning && effort !in listOf("low", "medium", "high", "default")) "low" else effort
                        OutlinedButton(onClick = { effortMenu = true }) { Text(shownEffort.replaceFirstChar { it.uppercase() }) }
                        DropdownMenu(effortMenu, { effortMenu = false }) {
                            (if (imagePlanning) listOf("low", "medium", "high", "default") else AiModels.effortsFor(model)).forEach { value ->
                                DropdownMenuItem(text = { Text(if (value == "default") "Model default" else value.replaceFirstChar { it.uppercase() }) },
                                    onClick = { effort = value; effortMenu = false })
                            }
                        }
                    }
                    Text(if (image) "Use a GPT Image model. An unsupported model or planning level reports an API error; Yboard keeps your selection."
                        else "An unsupported model or reasoning level will report an API error. Yboard keeps your selection.",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val selectedEffort = if (imagePlanning) effort.takeIf { it in listOf("low", "medium", "high", "default") } ?: "low"
                        else if (noReasoning) "none" else effort
                    Prefs.update { it.copy(aiModels = it.aiModels.with(feature, AiModel(model, selectedEffort))) }
                    editing = null
                }, enabled = model.isNotBlank() && model.none { it.isWhitespace() }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

private fun reasoningLabel(choice: AiModel): String = when {
    choice.model.startsWith("gpt-4") -> "none"
    choice.effort == "default" -> "model default"
    else -> choice.effort
}
