package com.yboard.keyboard.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yboard.keyboard.ime.TypingDiagnostics
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@Composable
internal fun TypingDiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by TypingDiagnostics.status.collectAsState()
    var working by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { destination ->
        if (destination == null) return@rememberLauncherForActivityResult
        scope.launch {
            working = true
            try {
                TypingDiagnostics.export(context, destination)
                Toast.makeText(context, "Typing log saved", Toast.LENGTH_SHORT).show()
            } catch (_: IOException) {
                Toast.makeText(context, "Couldn’t save the typing log", Toast.LENGTH_LONG).show()
            } catch (_: SecurityException) {
                Toast.makeText(context, "Couldn’t open the export location", Toast.LENGTH_LONG).show()
            } finally {
                working = false
            }
        }
    }

    ScreenScaffold("Typing diagnostics", onBack) {
        Section("Recording") {
            SwitchRow("Record typing log", "Glide paths, suggestions, typed text, and edits", status.recording != null) {
                TypingDiagnostics.setEnabled(it)
            }
            HintText("Use test text while recording. The log can include text already in the field. Yboard skips password fields.")
        }
        Section("Log file") {
            HintText(
                when {
                    status.writeFailed -> "Yboard had trouble writing to the log. Some events may be missing."
                    !status.hasLog -> "No log recorded yet."
                    else -> "Yboard keeps recent events on this device. Turning recording off leaves the log available to export."
                }
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(enabled = status.hasLog && !working, onClick = {
                    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
                    export.launch("Yboard-typing-$stamp.jsonl")
                }) { Text("Export log") }
                OutlinedButton(enabled = status.hasLog && !working, onClick = { confirmClear = true }) {
                    Text("Clear log")
                }
                if (working) CircularProgressIndicator(Modifier.size(28.dp))
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear typing log?") },
            text = { Text("This deletes the log stored by Yboard.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        working = true
                        try {
                            TypingDiagnostics.clear()
                        } catch (_: IOException) {
                            Toast.makeText(context, "Couldn’t clear the typing log", Toast.LENGTH_LONG).show()
                        } finally {
                            working = false
                        }
                    }
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
