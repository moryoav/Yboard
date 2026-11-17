package com.yboard.keyboard.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.yboard.keyboard.R
import com.yboard.keyboard.settings.Prefs
import com.yboard.keyboard.settings.YboardSettings
import com.yboard.keyboard.voice.ApiKeyStore
import com.yboard.keyboard.voice.OpenAi
import com.yboard.keyboard.voice.TranscriptionQuality
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun VoiceScreen(settings: YboardSettings, onBack: () -> Unit) {
    val context = LocalContext.current
    val preview = LocalInspectionMode.current
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(if (preview) null else ApiKeyStore.get(context)) }
    var input by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var micAllowed by remember {
        mutableStateOf(
            !preview && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micAllowed = it }

    fun check(key: String) {
        checking = true
        result = null
        scope.launch {
            val problem = withContext(Dispatchers.IO) { OpenAi.checkKey(key) }
            checking = false
            result = if (problem == null) true to "The key works. You're ready to talk." else false to problem
        }
    }

    ScreenScaffold("Voice typing", onBack) {
        Section("OpenAI API key", searchable = true) {
            val current = saved
            if (current != null) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.ic_check_circle), null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(10.dp))
                    Text("Key saved: ${ApiKeyStore.masked(current)}", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = {
                        ApiKeyStore.clear(context)
                        saved = null
                        result = null
                    }) { Text("Remove") }
                }
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it; result = null },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(14.dp),
                singleLine = true,
                label = { Text(if (current == null) "Paste your key (starts with sk-)" else "Paste a new key to replace it") },
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
                trailingIcon = {
                    TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") }
                },
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = {
                        val key = ApiKeyStore.clean(input)
                        if (key.length < 20) {
                            result = false to "That doesn't look like a whole key. Copy it again from OpenAI."
                        } else {
                            ApiKeyStore.set(context, key)
                            saved = key
                            input = ""
                            showKey = false
                            check(key)
                        }
                    },
                    enabled = input.isNotBlank() && !checking,
                    modifier = Modifier.weight(1f),
                ) { Text("Save key") }
                OutlinedButton(
                    onClick = { saved?.let { check(it) } },
                    enabled = saved != null && !checking,
                    modifier = Modifier.weight(1f),
                ) { Text(if (checking) "Checking…" else "Test key") }
            }
            result?.let { (ok, text) ->
                Text(
                    text,
                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }
            HintText(
                "Make a key at platform.openai.com → API keys. Yboard keeps it encrypted on this phone, " +
                    "leaves it out of backups and clipboard history, and only ever sends it to OpenAI."
            )
        }

        Section("Microphone", searchable = true) {
            if (micAllowed) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_check_circle), null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(10.dp))
                    Text("Yboard can use the microphone", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Button(onClick = { askMic.launch(Manifest.permission.RECORD_AUDIO) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(painterResource(R.drawable.ic_mic), null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Allow microphone")
                    }
                }
                HintText("Yboard only listens while the mic button on the keyboard is on.")
            }
        }

        Section("Send by voice", searchable = true) {
            var helperOn by remember { mutableStateOf(!preview && com.yboard.keyboard.voice.SendButtonService.isOn(context)) }
            val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
            androidx.compose.runtime.DisposableEffect(lifecycle) {
                val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                    if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME && !preview) {
                        helperOn = com.yboard.keyboard.voice.SendButtonService.isOn(context)
                    }
                }
                lifecycle.addObserver(obs)
                onDispose { lifecycle.removeObserver(obs) }
            }
            if (helperOn) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_check_circle), null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(10.dp))
                    Text("\"Tap Send for me\" is on", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                Text(
                    "1. Tap the button below, then \"Yboard: tap Send for me\", and turn it on.\n" +
                        "2. If Android says \"Restricted setting\", tap \"Unlock it\" below, then the ⋮ menu (top right) " +
                        "→ \"Allow restricted settings\", and do step 1 again.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Turn on") }
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    .setData(android.net.Uri.parse("package:" + context.packageName))
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Unlock it") }
                }
            }
            HintText(
                "Say \"send\" or \"תשלח\" and Yboard taps the app's Send button (WhatsApp, Telegram...). Android warns that it " +
                    "can see your screen: Yboard only looks for the Send button, only when you say \"send\", and never saves anything. " +
                    "Quicker for WhatsApp only: in WhatsApp, turn on Settings → Chats → \"Enter is send\"."
            )
        }

        Section("Voice typing") {
            val opts = listOf("auto", "en", "he")
            SegmentRow("Languages you speak", listOf("Both", "English", "עברית"), opts.indexOf(settings.voiceLanguages).coerceAtLeast(0)) { i ->
                Prefs.update { it.copy(voiceLanguages = opts[i]) }
            }
            SwitchRow(
                "Smart commands",
                "Editing, punctuation and send commands in English and Hebrew. When off, their words are typed literally.",
                settings.voiceCommands,
            ) { b -> Prefs.update { it.copy(voiceCommands = b) } }
        }

        Section("Listening preferences", searchable = true) {
            ChipRow("Transcription", TranscriptionQuality.entries.map { it.label }, settings.voiceQuality.ordinal) { i ->
                Prefs.update { it.copy(voiceQuality = TranscriptionQuality.entries[i]) }
            }
            HintText(when (settings.voiceQuality) {
                TranscriptionQuality.FASTER -> "Words appear sooner, with less audio context."
                TranscriptionQuality.BALANCED -> "Balances quick results with more context for accuracy."
                TranscriptionQuality.ACCURATE -> "Waits for more audio context. Words may take longer to appear."
            })
            SliderRow("Phrase pause", settings.voicePauseMs.toFloat(), 250f..1500f,
                "${settings.voicePauseMs} ms", steps = 24) { value ->
                Prefs.update { it.copy(voicePauseMs = (value / 50).roundToInt() * 50) }
            }
            HintText("A longer pause gives you more time to think before a phrase is finalized. Clear commands can finish sooner.")
            val silenceChoices = listOf(8, 15, 30, 0)
            ChipRow("Stop after silence", listOf("8 sec", "15 sec", "30 sec", "Never"),
                silenceChoices.indexOf(settings.voiceSilenceSeconds)) { i ->
                Prefs.update { it.copy(voiceSilenceSeconds = silenceChoices[i]) }
            }
            HintText("Never keeps the mic on until you stop it, type a key, move the cursor, or close the keyboard.")
        }

        Section("How to use it", searchable = true) {
            Text(
                "Tap the mic on the keyboard and talk. Your words appear as you say them. " +
                    "Tap the mic again to finish the last words. Typing a key, moving the cursor, or closing the keyboard stops it immediately.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
            )
            Fold("Things you can say", Modifier.padding(start = 18.dp, end = 20.dp, bottom = 8.dp)) {
                Text(
                    "Choose when it stops after silence in Listening preferences. Commands below work when Smart commands is on.\n\n" +
                    "Try saying:\n" +
                    "•  \"hi question mark\" → hi?\n" +
                    "•  \"delete that\" → removes what you just said\n" +
                    "•  \"delete that word\" → removes the last word\n" +
                    "•  \"new line\"\n" +
                    "•  \"send\" / \"תשלח\" → sends the message\n" +
                    "•  \"delete everything\" / \"תמחק הכול\" → clears the box (say \"undo\" right after to bring it back)\n" +
                    "•  \"תמחק את זה\" / \"סימן שאלה\"",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 2.dp, top = 8.dp),
                )
            }
        }

        Section("Privacy") {
            FoldingText(
                "Only the AI parts of Yboard use the internet, with your own key. While the mic is on, your voice " +
                    "goes to OpenAI to be turned into text. For commands, the text just before the cursor goes too. " +
                    "AI predictions send the text before the cursor only when they're turned on. " +
                    "Sticker creation sends your message when you tap Sticker or Recreate. " +
                    "OpenAI charges your account for what you use.",
                MaterialTheme.typography.bodyMedium,
                MaterialTheme.colorScheme.onSurface,
                Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}
