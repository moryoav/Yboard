package com.yboard.keyboard.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.yboard.keyboard.R
import com.yboard.keyboard.keyboard.Feedback
import com.yboard.keyboard.keyboard.KeySounds
import com.yboard.keyboard.text.UserWords
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yboard.keyboard.settings.OneHanded
import com.yboard.keyboard.settings.Prefs
import com.yboard.keyboard.settings.YboardSettings
import kotlin.math.roundToInt

@Composable
fun LayoutScreen(settings: YboardSettings, onBack: () -> Unit) {
    ScreenScaffold("Layout & size", onBack, preview = settings) {
        Section("Size") {
            SliderRow("Keyboard height", settings.heightScale, 0.75f..1.35f, pct(settings.heightScale)) { v ->
                Prefs.update { it.copy(heightScale = v) }
            }
            SliderRow("Lift keyboard", settings.bottomOffset, 0f..48f, "${settings.bottomOffset.roundToInt()} dp") { v ->
                Prefs.update { it.copy(bottomOffset = v) }
            }
            HintText("Lift adds space under the keyboard, handy with phone cases or gesture bars.")
        }
        Section("Keys") {
            SwitchRow("Number row", "Always show 1 – 0 above the letters", settings.numberRow) { b ->
                Prefs.update { it.copy(numberRow = b) }
            }
            SwitchRow("Number hints", "Small numbers on the top row (long-press to type)", settings.showHints) { b ->
                Prefs.update { it.copy(showHints = b) }
            }
            SwitchRow("Toolbar", "Shortcut icons above the keys", settings.toolbar) { b ->
                Prefs.update { it.copy(toolbar = b) }
            }
            if (android.os.Build.VERSION.SDK_INT >= 36) {
                SwitchRow(
                    "Hide Android's switch-keyboard button",
                    "Removes the strip Android adds under the keys, so nothing jumps to another keyboard. Swipe back to close the keyboard.",
                    settings.hideSystemBar,
                ) { b -> Prefs.update { it.copy(hideSystemBar = b) } }
            }
        }
        Section("One-handed mode", searchable = true) {
            SegmentRow(null, listOf("Off", "Left", "Right"), settings.oneHanded.ordinal) { i ->
                Prefs.update { it.copy(oneHanded = OneHanded.entries[i]) }
            }
            HintText("Shrinks the keys to one side so your thumb reaches everything.")
        }
    }
}

@Composable
fun TypingScreen(settings: YboardSettings, onBack: () -> Unit, onShortcuts: () -> Unit = {}, onMyWords: () -> Unit = {}) {
    val context = LocalContext.current
    val hasKey = remember { com.yboard.keyboard.voice.ApiKeyStore.get(context) != null }
    var confirmClear by remember { mutableStateOf(false) }
    ScreenScaffold("Typing", onBack) {
        Section("Suggestions") {
            SwitchRow("Word suggestions", "Show words in the strip above the keys as you type", settings.suggestions) { b ->
                Prefs.update { it.copy(suggestions = b) }
            }
            SwitchRow(
                "Autocorrect", "Fix typos when you press space. Backspace right after undoes it",
                settings.autoCorrect, enabled = settings.suggestions,
            ) { b -> Prefs.update { it.copy(autoCorrect = b) } }
            SwitchRow(
                "Next-word predictions", "After a space, suggest the word you'll probably type next",
                settings.nextWords, enabled = settings.suggestions,
            ) { b -> Prefs.update { it.copy(nextWords = b) } }
            SwitchRow(
                "AI predictions",
                if (hasKey) "Smarter next words in your own style, learned from how you write. Uses your OpenAI key"
                else "Needs your OpenAI key first (Yboard → Voice typing)",
                settings.aiPredictions, enabled = settings.suggestions && settings.nextWords && (hasKey || settings.aiPredictions),
            ) { b -> Prefs.update { it.copy(aiPredictions = b) } }
            if (settings.aiPredictions) {
                HintText(
                    "To guess your next words, Yboard sends OpenAI the last few sentences before the cursor and the words " +
                        "you use most. Never from password fields or private (incognito) fields. OpenAI bills your account a tiny amount."
                )
            }
            SwitchRow(
                "Emoji suggestions", "Type \"pizza\" or \"פיצה\" and 🍕 shows up in the strip",
                settings.emojiSuggest, enabled = settings.suggestions,
            ) { b -> Prefs.update { it.copy(emojiSuggest = b) } }
            SwitchRow(
                "Emoji sprinkle button",
                if (hasKey) "A ✨ button above the keys adds fitting emojis to your message. Uses your OpenAI key"
                else "Needs your OpenAI key first (Yboard → Voice typing)",
                settings.emojiSprinkle,
            ) { b -> Prefs.update { it.copy(emojiSprinkle = b) } }
            NavRow(R.drawable.ic_text_fields, "Text shortcuts", "\"omw\" becomes \"On my way!\" — make your own") { onShortcuts() }
            NavRow(R.drawable.ic_book, "My words", myWordsSummary(settings)) { onMyWords() }
            SwitchRow(
                "Learn my words", "Remember names and slang you type (stays on this phone)",
                settings.learnWords, enabled = settings.suggestions,
            ) { b -> Prefs.update { it.copy(learnWords = b) } }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                OutlinedButton(onClick = { confirmClear = true }) {
                    Icon(painterResource(R.drawable.ic_delete), null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Clear learned words")
                }
            }
            HintText("Hold a suggestion to make Yboard forget a word it learned.")
        }
        EmailAndPasswordsSection(settings)
        Section("Swipe") {
            SwitchRow("Swipe typing", "Slide your finger across the letters to type a word", settings.swipeTyping) { b ->
                Prefs.update { it.copy(swipeTyping = b) }
            }
            SwitchRow("Show swipe trail", "Draw a line where your finger goes", settings.swipeTrail, enabled = settings.swipeTyping) { b ->
                Prefs.update { it.copy(swipeTrail = b) }
            }
            if (settings.swipeTyping && settings.swipeTrail) SwipeTrailSettings(settings)
            SwitchRow("Swipe to delete words", "Slide left from backspace to select whole words, let go to delete", settings.backspaceSwipe) { b ->
                Prefs.update { it.copy(backspaceSwipe = b) }
            }
        }
        Section("Smart typing") {
            SwitchRow("Auto-capitalization", "Capital letter at the start of each sentence", settings.autoCaps) { b ->
                Prefs.update { it.copy(autoCaps = b) }
            }
            SwitchRow("Double-space period", "Tap space twice to add a period", settings.doubleSpacePeriod) { b ->
                Prefs.update { it.copy(doubleSpacePeriod = b) }
            }
        }
        Section("Gestures & keys") {
            SwitchRow("Space bar cursor", "Slide on the space bar to move the cursor", settings.spaceCursor) { b ->
                Prefs.update { it.copy(spaceCursor = b) }
            }
            SwitchRow("Key popup", "Show a bigger letter when you press a key", settings.keyPopup) { b ->
                Prefs.update { it.copy(keyPopup = b) }
            }
            SliderRow(
                "Long-press delay", settings.longPressDelay.toFloat(), 200f..700f,
                "${settings.longPressDelay} ms",
            ) { v -> Prefs.update { it.copy(longPressDelay = (v / 10).roundToInt() * 10) } }
            HintText("Long-press a letter for accents (é, ñ…) or numbers, the period for punctuation, and the comma for emoji.")
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear learned words?") },
            text = { Text("Yboard will forget the words it learned from your typing, in English and Hebrew. The built-in dictionary stays.") },
            confirmButton = {
                TextButton(onClick = {
                    UserWords.clearAll(context)
                    com.yboard.keyboard.text.NextWords.clearAll(context)
                    confirmClear = false
                    Toast.makeText(context, "Learned words cleared", Toast.LENGTH_SHORT).show()
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun SoundScreen(settings: YboardSettings, onBack: () -> Unit) {
    val context = LocalContext.current
    val feedback = remember { Feedback(context) }
    ScreenScaffold("Sound & vibration", onBack) {
        Section("Vibration") {
            SwitchRow("Vibrate on keypress", null, settings.haptics) { b -> Prefs.update { it.copy(haptics = b) } }
            SliderRow("Strength", settings.hapticStrength, 0.05f..1f, pct(settings.hapticStrength), enabled = settings.haptics) { v ->
                Prefs.update { it.copy(hapticStrength = v) }
            }
        }
        Section("Sound") {
            SwitchRow("Sound on keypress", null, settings.sound) { b -> Prefs.update { it.copy(sound = b) } }
            val styles = KeySounds.STYLES
            ChipRow(
                "Click sound", styles.map { it.second },
                styles.indexOfFirst { it.first == settings.soundStyle }.coerceAtLeast(0),
            ) { i ->
                val next = settings.copy(soundStyle = styles[i].first, sound = true)
                Prefs.update { next }
                feedback.preview(next)
            }
            SliderRow("Volume", settings.soundVolume, 0.05f..1f, pct(settings.soundVolume), enabled = settings.sound) { v ->
                Prefs.update { it.copy(soundVolume = v) }
            }
            HintText("Tap a sound to hear it. Key sounds follow your phone's ringer: they're quiet on silent or vibrate.")
        }
        QuietHoursSection(settings)
    }
}

@Composable
fun LanguagesScreen(settings: YboardSettings, onBack: () -> Unit) {
    ScreenScaffold("Languages", onBack) {
        Section("Keyboard languages") {
            SwitchRow("English", "QWERTY layout", settings.english) { b ->
                if (b || settings.hebrew) Prefs.update { it.copy(english = b) }
            }
            SwitchRow("עברית · Hebrew", "Standard Hebrew layout", settings.hebrew) { b ->
                if (b || settings.english) Prefs.update { it.copy(hebrew = b) }
            }
            HintText("With both on, tap the 🌐 key to switch. At least one language stays on.")
        }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember {
        try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (e: Exception) { null } ?: "1.1"
    }
    ScreenScaffold("About", onBack) {
        Column(
            Modifier.fillMaxWidth().padding(top = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LogoBadge(84)
            Spacer(Modifier.height(16.dp))
            Text("Yboard", style = MaterialTheme.typography.headlineMedium)
            Text("Version $version", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        UpdatesSection()
        Section("Privacy") {
            Text(
                "Everything you type with the keys, your clipboard history, learned words and themes stay on this phone. " +
                    "The AI features use the internet, with your own OpenAI key: voice typing (while the mic is on, " +
                    "your voice and, for commands, the text just before the cursor), AI predictions if you turn them on, " +
                    "and the ✨ emoji sprinkle when you tap it. Update checks contact GitHub without sending keyboard text or your OpenAI key.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        Section("Credits") {
            Text(
                "Font: Google Sans, SIL Open Font License 1.1.\n" +
                    "Dictionaries: Android Open Source Project keyboard word lists, Apache License 2.0. " +
                    "Includes Dictionaries © Lexiteria LLC. Used by permission.\n" +
                    "Emoji search words: Unicode CLDR, Unicode License v3.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        Section {
            Text(
                "A clean, customizable keyboard for English and Hebrew.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Start,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}
