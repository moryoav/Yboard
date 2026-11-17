package com.yboard.keyboard.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yboard.keyboard.R
import com.yboard.keyboard.settings.Prefs
import com.yboard.keyboard.settings.YboardSettings
import androidx.compose.material3.TextButton

private fun isYboardEnabled(ctx: Context): Boolean = try {
    val imm = ctx.getSystemService(InputMethodManager::class.java)
    imm?.enabledInputMethodList?.any { it.packageName == ctx.packageName } == true
} catch (e: Exception) {
    false
}

/** Names of other keyboard apps that are turned on (e.g. Gboard). */
private fun otherKeyboards(ctx: Context): List<String> = try {
    val imm = ctx.getSystemService(InputMethodManager::class.java)
    imm?.enabledInputMethodList
        ?.filter { it.packageName != ctx.packageName }
        ?.map { it.loadLabel(ctx.packageManager).toString() }
        ?: emptyList()
} catch (e: Exception) {
    emptyList()
}

private fun isYboardSelected(ctx: Context): Boolean = try {
    val id = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
    id != null && id.startsWith(ctx.packageName + "/")
} catch (e: Exception) {
    false
}

@Composable
fun HomeScreen(
    settings: YboardSettings,
    onOpen: (Screen) -> Unit,
    onSearchPick: (SettingEntry) -> Unit = { onOpen(it.screen) },
) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(isYboardEnabled(context)) }
    var selected by remember { mutableStateOf(isYboardSelected(context)) }
    var others by remember { mutableStateOf(otherKeyboards(context)) }
    val preview = androidx.compose.ui.platform.LocalInspectionMode.current
    var sendHelperOn by remember { mutableStateOf(preview || com.yboard.keyboard.voice.SendButtonService.isOn(context)) }

    // Re-check setup whenever the user comes back (from system settings or the picker).
    val view = LocalView.current
    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused) {
                enabled = isYboardEnabled(context)
                selected = isYboardSelected(context)
                others = otherKeyboards(context)
                if (!preview) sendHelperOn = com.yboard.keyboard.voice.SendButtonService.isOn(context)
            }
        }
        view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        onDispose { view.viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            LogoBadge(52)
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Yboard", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Your keyboard, your style",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(18.dp))

        // Search: type "blur" or "trail" and jump straight to that setting.
        var query by rememberSaveable { mutableStateOf("") }
        SettingsSearchField(query) { query = it }
        Spacer(Modifier.height(16.dp))
        if (query.isNotBlank()) {
            SettingsSearchResults(query, onPick = onSearchPick)
        } else {

        val askSendHelper = !sendHelperOn && settings.sendHelperPrompt
        if (!enabled || !selected || askSendHelper) {
            SetupCard(
                enabled = enabled,
                selected = selected,
                sendHelperOn = sendHelperOn,
                showSendStep = settings.sendHelperPrompt || sendHelperOn,
                onEnable = {
                    context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                onSelect = { context.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker() },
                onSendHelper = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                onUnlock = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.parse("package:" + context.packageName))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                },
                onNotNow = { Prefs.update { it.copy(sendHelperPrompt = false) } },
            )
            Spacer(Modifier.height(16.dp))
        } else if (others.isNotEmpty() && !(android.os.Build.VERSION.SDK_INT >= 36 && settings.hideSystemBar)) {
            OnlyYboardCard(others) {
                context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            Spacer(Modifier.height(16.dp))
        } else {
            ReadyBanner()
            Spacer(Modifier.height(14.dp))
        }

        KeyboardPreview(settings, topSpace = 92.dp)

        Spacer(Modifier.height(14.dp))
        var tryText by remember { mutableStateOf("") }
        OutlinedTextField(
            value = tryText,
            onValueChange = { tryText = it },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            placeholder = { Text("Tap here to try Yboard…") },
        )

        Section("Personalize") {
            NavRow(R.drawable.ic_palette, "Themes", "${com.yboard.keyboard.settings.ThemePresets.visible.size} ready-made looks, plus your own") { onOpen(Screen.THEMES) }
            RowDivider()
            NavRow(R.drawable.ic_tune, "Customize look", "Transparency, colors, effects, photo, fonts") { onOpen(Screen.CUSTOMIZE) }
            RowDivider()
            NavRow(R.drawable.ic_height, "Layout & size", "Height, number row, one-handed mode") { onOpen(Screen.LAYOUT) }
        }
        Section("Typing") {
            NavRow(R.drawable.ic_text_fields, "Typing", "Suggestions, autocorrect, swipe typing") { onOpen(Screen.TYPING) }
            RowDivider()
            NavRow(R.drawable.ic_book, "My words", myWordsSummary(settings)) { onOpen(Screen.MY_WORDS) }
            RowDivider()
            NavRow(R.drawable.ic_vibration, "Sound & vibration", "Vibration and click sounds") { onOpen(Screen.SOUND) }
            RowDivider()
            NavRow(R.drawable.ic_language, "Languages", "English and עברית") { onOpen(Screen.LANGUAGES) }
            RowDivider()
            NavRow(R.drawable.ic_mic, "Voice typing (AI)", "Talk instead of typing: your OpenAI key, microphone") { onOpen(Screen.VOICE) }
            RowDivider()
            NavRow(R.drawable.ic_tune, "AI models", "Models and reasoning for predictions, voice and editing") { onOpen(Screen.AI_MODELS) }
        }
        Section {
            NavRow(R.drawable.ic_info, "About Yboard", "Version, privacy") { onOpen(Screen.ABOUT) }
        }
        }
        Spacer(Modifier.height(24.dp))
    }
    }
}

@Composable
private fun SetupCard(
    enabled: Boolean, selected: Boolean, sendHelperOn: Boolean, showSendStep: Boolean,
    onEnable: () -> Unit, onSelect: () -> Unit, onSendHelper: () -> Unit, onUnlock: () -> Unit, onNotNow: () -> Unit,
) {
    val basicsDone = enabled && selected
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(
                if (basicsDone) "One more step" else "Set up Yboard",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                if (basicsDone) "You're typing with Yboard. This last one is optional."
                else "Two quick steps and you're typing.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
            Spacer(Modifier.height(16.dp))
            SetupStep(1, "Turn on Yboard", "In your phone's keyboard settings", done = enabled, active = !enabled, button = "Turn on", onClick = onEnable)
            Spacer(Modifier.height(12.dp))
            SetupStep(2, "Switch to Yboard", "Make it your keyboard", done = selected, active = enabled && !selected, button = "Switch", onClick = onSelect)
            if (showSendStep) {
                Spacer(Modifier.height(12.dp))
                SetupStep(
                    3, "Send by voice (optional)", "Say \"send\" and Yboard taps WhatsApp's Send button",
                    done = sendHelperOn, active = basicsDone && !sendHelperOn, button = "Turn on", onClick = onSendHelper,
                )
                if (basicsDone && !sendHelperOn) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Tap Turn on, then \"Yboard: tap Send for me\". If Android says \"Restricted setting\", tap " +
                            "Unlock it, then ⋮ (top right) → \"Allow restricted settings\", and tap Turn on again. " +
                            "Android warns Yboard could see your screen; it only looks for the Send button when you say \"send\".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onNotNow) { Text("Not now") }
                        TextButton(onClick = onUnlock) { Text("Unlock it") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupStep(
    number: Int, title: String, subtitle: String,
    done: Boolean, active: Boolean, button: String, onClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(painterResource(R.drawable.ic_done), null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
            } else {
                Text("$number", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f))
        }
        if (!done) {
            if (active) Button(onClick = onClick) { Text(button) }
            else FilledTonalButton(onClick = onClick, enabled = false) { Text(button) }
        }
    }
}

/** Shown when Yboard is active but other keyboards (like Gboard) are still turned on. */
@Composable
fun OnlyYboardCard(others: List<String>, onOpen: () -> Unit) {
    Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_check_circle), null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Yboard is on",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Want only Yboard? While ${others.joinToString(", ")} " +
                    (if (others.size == 1) "is" else "are") +
                    " still turned on, Android shows its own switch-keyboard button under the keys. " +
                    "Turn ${if (others.size == 1) "it" else "them"} off and that button goes away.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
            )
            Spacer(Modifier.height(14.dp))
            Button(onClick = onOpen) { Text("Open keyboard list") }
        }
    }
}

@Composable
private fun ReadyBanner() {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(painterResource(R.drawable.ic_check_circle), null, tint = MaterialTheme.colorScheme.primary)
            Text("Yboard is your keyboard. Enjoy!", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** The setup card with the first two steps done (for screenshots). */
@Composable
internal fun SetupCardForTests() = SetupCard(
    enabled = true, selected = true, sendHelperOn = false, showSendStep = true,
    onEnable = {}, onSelect = {}, onSendHelper = {}, onUnlock = {}, onNotNow = {},
)
