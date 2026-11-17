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
        HintText("Choose the model each feature uses. Changes apply to new requests. Swipe matching and learned word frequencies run on your phone.")
        Section("Text suggestions & editing") {
            AiFeature.entries.filter { it.text }.forEach { feature ->
                val choice = settings.aiModels[feature]
                NavRow(R.drawable.ic_tune, feature.title,
                    "${choice.model} · Reasoning: ${reasoningLabel(choice)}") { editing = feature }
                statuses[feature]?.let { HintText(it) }
            }
        }
        Section("Speech recognition") {
            AiFeature.entries.filterNot { it.text }.forEach { feature ->
                NavRow(R.drawable.ic_mic, feature.title,
                    "${settings.aiModels[feature].model} · No reasoning control") { editing = feature }
            }
        }
        HintText("Next-word predictions default to GPT-6 Luna with reasoning off. Other text models must support the Responses API and structured output. Speech models must support their transcription endpoint.")
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
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(feature.title) },
            text = {
                Column {
                    OutlinedTextField(model, { model = it.trim() }, label = { Text("Model ID or search") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrect = false))
                    val matches = (listOf(feature.defaultModel, choice.model) + available)
                        .distinct().filter { it.contains(model, true) }
                    if (matches.isNotEmpty()) LazyColumn(Modifier.heightIn(max = 160.dp)) {
                        items(matches.take(40)) { id ->
                            Text(id, Modifier.fillMaxWidth().clickable { model = id }.padding(vertical = 12.dp),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Reasoning", style = MaterialTheme.typography.labelLarge)
                    if (noReasoning) Text(if (feature.text) "None (this model has no reasoning step)" else "Not supported for transcription")
                    else Box {
                        OutlinedButton(onClick = { effortMenu = true }) { Text(effort.replaceFirstChar { it.uppercase() }) }
                        DropdownMenu(effortMenu, { effortMenu = false }) {
                            AiModels.effortsFor(model).forEach { value ->
                                DropdownMenuItem(text = { Text(if (value == "default") "Model default" else value.replaceFirstChar { it.uppercase() }) },
                                    onClick = { effort = value; effortMenu = false })
                            }
                        }
                    }
                    Text("An unsupported model or reasoning level will report an API error. Yboard keeps your selection.",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    Prefs.update { it.copy(aiModels = it.aiModels.with(feature, AiModel(model, if (noReasoning) "none" else effort))) }
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
