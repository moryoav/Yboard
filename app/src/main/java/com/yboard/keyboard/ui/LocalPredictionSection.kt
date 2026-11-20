package com.yboard.keyboard.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yboard.keyboard.local.LocalQwen
import com.yboard.keyboard.local.QwenModel

@Composable
fun LocalPredictionSection() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val catalog by QwenModel.state.collectAsState()
    val engine by LocalQwen.status.collectAsState()
    val downloadInProgress = catalog.models.values.any { it.downloading }
    var showGemmaTerms by rememberSaveable { mutableStateOf(false) }
    Section("Local predictions") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text("The selected model powers word suggestions and swipe typing offline.", style = MaterialTheme.typography.bodyMedium)
        }
        Column(Modifier.selectableGroup()) {
            QwenModel.Variant.entries.forEachIndexed { index, variant ->
                if (index > 0) RowDivider()
                val model = catalog[variant]
                val selected = catalog.selected == variant
                val engineStatus = engine.takeIf { selected && it.model == variant }
                val progress = (model.bytes.toFloat() / variant.size).coerceIn(0f, 1f)
                val percent = (progress * 100).toInt()
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(selected, enabled = model.ready, role = Role.RadioButton) {
                            if (QwenModel.requiresTerms(variant)) showGemmaTerms = true else QwenModel.select(variant)
                        }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected, onClick = null, enabled = model.ready)
                    Column {
                        Text(variant.label, style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                model.downloading -> if (percent == 100) "Verifying download…" else "Downloading · $percent%"
                                model.ready && engineStatus?.loading == true -> "Loading…"
                                model.ready && selected -> "In use"
                                model.ready -> "Downloaded"
                                model.bytes > 0 && model.error == null -> "Paused · $percent%"
                                else -> "${(variant.size + 500_000) / 1_000_000} MB download"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!model.ready || engineStatus?.error != null) {
                    Column(
                        Modifier.fillMaxWidth().padding(start = 52.dp, end = 20.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (model.downloading) {
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        }
                        (model.error ?: engineStatus?.error)?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (model.downloading) {
                            OutlinedButton(onClick = { QwenModel.pause(variant) }) { Text("Pause download") }
                        } else if (!model.ready) {
                            Button(onClick = {
                                if (QwenModel.requiresTerms(variant)) showGemmaTerms = true else QwenModel.download(context, variant)
                            }, enabled = !downloadInProgress) {
                                Text(if (model.bytes > 0) "Resume download" else "Download model")
                            }
                        }
                    }
                }
            }
        }
    }
    if (showGemmaTerms) {
        val variant = QwenModel.Variant.GEMMA
        val downloaded = catalog[variant].ready
        AlertDialog(
            onDismissRequest = { showGemmaTerms = false },
            title = { Text("Gemma Terms of Use") },
            text = {
                Column {
                    Text("Using Gemma requires agreeing to Google's Gemma Terms of Use, including its use restrictions.")
                    TextButton(onClick = { uriHandler.openUri(QwenModel.GEMMA_TERMS_URL) }) { Text("Read the terms") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    QwenModel.acceptGemmaTerms()
                    showGemmaTerms = false
                    if (downloaded) QwenModel.select(variant) else QwenModel.download(context, variant)
                }) { Text(if (downloaded) "Agree and use" else "Agree and download") }
            },
            dismissButton = { TextButton(onClick = { showGemmaTerms = false }) { Text("Cancel") } },
        )
    }
}
