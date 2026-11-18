package com.yboard.keyboard.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yboard.keyboard.local.LocalQwen
import com.yboard.keyboard.local.QwenModel

@Composable
fun LocalPredictionSection() {
    val context = LocalContext.current
    val model by QwenModel.state.collectAsState()
    val engine by LocalQwen.status.collectAsState()
    Section("Local predictions") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text("Qwen3 · Typing & swipe", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(when {
                model.downloading -> "Downloading: ${model.bytes * 100 / QwenModel.SIZE}%"
                model.ready && engine.loading -> "Loading Qwen…"
                model.ready -> "Downloaded · Works offline"
                model.bytes > 0 -> "Download paused: ${model.bytes * 100 / QwenModel.SIZE}%"
                else -> "One-time download · 397 MB"
            }, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text("Qwen supplies all word suggestions and ranks swipe matches using the full sentence. Prediction text stays on this phone and needs no API key.",
                style = MaterialTheme.typography.bodyMedium)
            if (model.downloading) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(progress = { (model.bytes.toFloat() / QwenModel.SIZE).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
            (model.error ?: engine.error)?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            if (!model.ready) {
                Spacer(Modifier.height(12.dp))
                if (model.downloading) OutlinedButton(onClick = QwenModel::pause) { Text("Pause download") }
                else Button(onClick = { QwenModel.download(context) }) { Text(if (model.bytes > 0) "Resume download" else "Download Qwen") }
            }
        }
    }
}
