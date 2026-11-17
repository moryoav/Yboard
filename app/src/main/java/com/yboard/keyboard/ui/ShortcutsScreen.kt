package com.yboard.keyboard.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yboard.keyboard.R
import com.yboard.keyboard.settings.Prefs
import com.yboard.keyboard.settings.YboardSettings
import com.yboard.keyboard.text.Shortcuts

@Composable
fun ShortcutsScreen(settings: YboardSettings, onBack: () -> Unit) {
    var short by remember { mutableStateOf("") }
    var full by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }

    ScreenScaffold("Text shortcuts", onBack) {
        Section {
            SwitchRow(
                "Expand shortcuts",
                "Type a shortcut and a space (or tap it in the strip). Backspace right after puts the shortcut back.",
                settings.shortcutsOn,
            ) { b -> Prefs.update { it.copy(shortcutsOn = b) } }
        }

        Section("Add a shortcut") {
            OutlinedTextField(
                value = short,
                onValueChange = { short = it.trim(); problem = null },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(14.dp),
                singleLine = true,
                label = { Text("Shortcut (letters only, like omw)") },
            )
            OutlinedTextField(
                value = full,
                onValueChange = { full = it; problem = null },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(14.dp),
                label = { Text("Becomes (like On my way!)") },
            )
            problem?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Button(
                    onClick = {
                        when {
                            !Shortcuts.isValid(short) -> problem = "Use one word of letters for the shortcut (no spaces or numbers)."
                            full.isBlank() -> problem = "Write what the shortcut should become."
                            else -> {
                                val s = short; val f = full.trim()
                                Prefs.update { st ->
                                    st.copy(shortcuts = st.shortcuts.filterNot { it.first.equals(s, ignoreCase = true) } + (s to f))
                                }
                                short = ""; full = ""
                            }
                        }
                    },
                    enabled = short.isNotBlank() && full.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Add shortcut") }
            }
        }

        Section("Your shortcuts") {
            if (settings.shortcuts.isEmpty()) {
                HintText("No shortcuts yet. Add one above.")
            }
            settings.shortcuts.forEach { (s, f) ->
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(s, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(f, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { Prefs.update { st -> st.copy(shortcuts = st.shortcuts.filterNot { it.first == s }) } }) {
                        Icon(painterResource(R.drawable.ic_delete), contentDescription = "Remove $s", modifier = Modifier.size(22.dp))
                    }
                }
            }
            if (settings.shortcuts != Shortcuts.defaults) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        Prefs.update { st -> st.copy(shortcuts = (st.shortcuts + Shortcuts.defaults).distinctBy { it.first.lowercase() }) }
                    }) { Text("Add the starter shortcuts back") }
                }
            }
        }
    }
}
