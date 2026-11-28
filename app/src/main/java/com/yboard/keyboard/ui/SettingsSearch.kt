package com.yboard.keyboard.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.yboard.keyboard.R
import com.yboard.keyboard.settings.ToolbarShortcut
import kotlinx.coroutines.delay

/**
 * One setting the home screen's search can find. [target] is the row's title on its screen
 * (that row gets scrolled to and flashes), or null to just open the screen.
 */
data class SettingEntry(
    val title: String,
    val screen: Screen,
    val where: String,
    val words: String = "",
    val target: String? = title,
)

object SettingsIndex {
    private const val LOOK = "Customize look"
    private const val LAYOUT = "Layout & size"
    private const val TYPING = "Typing"
    private const val SOUND = "Sound & vibration"
    private const val VOICE = "Voice typing"

    val all: List<SettingEntry> = listOf(
        // Themes and look
        SettingEntry("Themes", Screen.THEMES, "Ready-made looks", "theme look style liquid glass dark light gradient ערכת נושא עיצוב", null),
        SettingEntry("Keyboard background", Screen.CUSTOMIZE, "$LOOK › Transparency", "transparent see-through opacity שקיפות רקע"),
        SettingEntry("Keys", Screen.CUSTOMIZE, "$LOOK › Transparency", "key transparency opacity see-through שקיפות"),
        SettingEntry("Blur behind", Screen.CUSTOMIZE, "$LOOK › Transparency", "blur frosted glass טשטוש"),
        SettingEntry("Glass keys", Screen.CUSTOMIZE, "$LOOK › Transparency", "liquid glass shiny see-through זכוכית"),
        SettingEntry("Background", Screen.CUSTOMIZE, LOOK, "background color gradient photo picture image wallpaper רקע תמונה"),
        SettingEntry("Background color", Screen.CUSTOMIZE, "$LOOK › Background", "color רקע צבע"),
        SettingEntry("Key style", Screen.CUSTOMIZE, "$LOOK › Keys", "filled outline flat"),
        SettingEntry("Key shape", Screen.CUSTOMIZE, "$LOOK › Keys", "round bubble square rounded shape צורה"),
        SettingEntry("Corner roundness", Screen.CUSTOMIZE, "$LOOK › Keys", "corners radius round"),
        SettingEntry("Space between keys", Screen.CUSTOMIZE, "$LOOK › Keys", "gap spacing"),
        SettingEntry("Space between rows", Screen.CUSTOMIZE, "$LOOK › Keys", "gap spacing rows"),
        SettingEntry("Border", Screen.CUSTOMIZE, "$LOOK › Keys", "outline edge stroke"),
        SettingEntry("Key shadows", Screen.CUSTOMIZE, "$LOOK › Keys", "shadow depth 3d"),
        SettingEntry("When you press a key", Screen.CUSTOMIZE, "$LOOK › Effects", "effect pop glow ripple press animation אפקט"),
        SettingEntry("RGB lights", Screen.CUSTOMIZE, "$LOOK › Effects", "rainbow lights border gaming"),
        SettingEntry("Moving background", Screen.CUSTOMIZE, "$LOOK › Effects", "animation snow stars bubbles flow moving animated"),
        SettingEntry("Match wallpaper colors", Screen.CUSTOMIZE, "$LOOK › Colors", "material you dynamic wallpaper"),
        SettingEntry("Letter keys", Screen.CUSTOMIZE, "$LOOK › Colors", "key color צבע"),
        SettingEntry("Function keys", Screen.CUSTOMIZE, "$LOOK › Colors", "shift backspace key color"),
        SettingEntry("Text & icons", Screen.CUSTOMIZE, "$LOOK › Colors", "letters text color font color"),
        SettingEntry("Colored Enter key", Screen.CUSTOMIZE, "$LOOK › Colors", "enter accent"),
        SettingEntry("Accent (Enter key, swipe trail)", Screen.CUSTOMIZE, "$LOOK › Colors", "accent color enter"),
        SettingEntry("Letter size", Screen.CUSTOMIZE, "$LOOK › Text", "font size text big bigger letters גודל"),
        SettingEntry("Bold letters", Screen.CUSTOMIZE, "$LOOK › Text", "bold font weight"),
        SettingEntry("Font", Screen.CUSTOMIZE, "$LOOK › Text", "typeface letters style גופן"),
        // Layout
        SettingEntry("Keyboard height", Screen.LAYOUT, LAYOUT, "size tall short bigger smaller גובה גודל"),
        SettingEntry("Lift keyboard", Screen.LAYOUT, LAYOUT, "bottom space gesture bar phone case raise"),
        SettingEntry("Number row", Screen.LAYOUT, "$LAYOUT › Keys", "numbers digits 123 מספרים"),
        SettingEntry("Number hints", Screen.LAYOUT, "$LAYOUT › Keys", "numbers long press"),
        SettingEntry("Toolbar", Screen.LAYOUT, "$LAYOUT › Keys", "icons bar top"),
        SettingEntry("Toolbar shortcuts", Screen.TOOLBAR, LAYOUT, "icons order reorder hide show clipboard sticker emoji palette undo mic", null),
        SettingEntry(ToolbarShortcut.SPRINKLE.label, Screen.TOOLBAR, "$LAYOUT › Toolbar shortcuts", "button sparkle ai add emojis ✨ אימוג'י"),
        SettingEntry(STICKERS_WITH_SUGGESTIONS, Screen.TOOLBAR, "Toolbar shortcuts › While typing", "sticker icon hide completions predictions"),
        SettingEntry("Hide Android's switch-keyboard button", Screen.LAYOUT, "$LAYOUT › Keys", "gboard globe switch bar bottom"),
        SettingEntry("One-handed mode", Screen.LAYOUT, LAYOUT, "one hand left right thumb small יד אחת"),
        // Typing
        SettingEntry("Typing diagnostics", Screen.TYPING, TYPING, "debug logging record export glide suggestions predictions corrections"),
        SettingEntry("Word suggestions", Screen.TYPING, "$TYPING › Suggestions", "suggestions strip words הצעות"),
        SettingEntry("Next-word predictions", Screen.TYPING, "$TYPING › Suggestions", "predict next word"),
        SettingEntry("Local predictions", Screen.AI_MODELS, Screen.AI_MODELS.title, "qwen gemma llm ai offline download model typing swipe prediction context"),
        SettingEntry("AI models", Screen.AI_MODELS, "AI models", "model reasoning thinking effort prediction voice transcription", null),
        SettingEntry("AI usage & cost", Screen.AI_MODELS, "AI models › Usage & estimated cost", "tokens input output cached cost price day week month spending usage", "Usage & estimated cost"),
        SettingEntry("Text shortcuts", Screen.SHORTCUTS, TYPING, "shortcuts omw abbreviation expand קיצורים", null),
        SettingEntry("My words", Screen.MY_WORDS, TYPING, "dictionary names slang add word personal מילים מילון", null),
        SettingEntry("Learn my words", Screen.TYPING, "$TYPING › Suggestions", "learn names slang"),
        SettingEntry(VIEW_LEARNED_WORDS, Screen.LEARNED_WORDS, "$TYPING › Suggestions", "forget delete remove learned words מילים", null),
        SettingEntry("Email helpers", Screen.TYPING, "$TYPING › Email & passwords", "email @ .com gmail"),
        SettingEntry("Your email", Screen.TYPING, "$TYPING › Email & passwords", "email address gmail מייל", "Email helpers"),
        SettingEntry("Passwords & codes", Screen.TYPING, "$TYPING › Email & passwords", "autofill password login sms code otp סיסמה"),
        SettingEntry("Swipe typing", Screen.TYPING, "$TYPING › Swipe", "swipe glide gesture slide החלקה"),
        SettingEntry("Show swipe trail", Screen.TYPING, "$TYPING › Swipe", "trail line swipe"),
        SettingEntry("Trail thickness", Screen.TYPING, "$TYPING › Swipe", "trail thick width line"),
        SettingEntry("Trail color", Screen.TYPING, "$TYPING › Swipe", "trail rainbow color"),
        SettingEntry("Trail glow", Screen.TYPING, "$TYPING › Swipe", "trail glow", "Glow"),
        SettingEntry("Swipe to delete words", Screen.TYPING, "$TYPING › Swipe", "backspace delete slide"),
        SettingEntry("Auto-capitalization", Screen.TYPING, "$TYPING › Smart typing", "capital caps uppercase"),
        SettingEntry("Double-space period", Screen.TYPING, "$TYPING › Smart typing", "dot full stop space"),
        SettingEntry("Space bar cursor", Screen.TYPING, "$TYPING › Gestures & keys", "cursor move arrows space"),
        SettingEntry("Key popup", Screen.TYPING, "$TYPING › Gestures & keys", "popup preview bigger letter"),
        SettingEntry("Long-press delay", Screen.TYPING, "$TYPING › Gestures & keys", "hold long press time"),
        SettingEntry("Expand shortcuts", Screen.SHORTCUTS, "Text shortcuts", "shortcuts omw expand"),
        // Sound
        SettingEntry("Vibrate on keypress", Screen.SOUND, SOUND, "vibration haptic buzz רטט"),
        SettingEntry("Vibration strength", Screen.SOUND, SOUND, "vibration strength haptic רטט", "Strength"),
        SettingEntry("Sound on keypress", Screen.SOUND, SOUND, "sound click audio צליל"),
        SettingEntry("Click sound", Screen.SOUND, SOUND, "sound style click wood typewriter צליל"),
        SettingEntry("Sound volume", Screen.SOUND, SOUND, "volume loud quiet", "Volume"),
        SettingEntry("Quiet hours", Screen.SOUND, SOUND, "schedule night bedtime mute silence vibration quiet hours שעות שקטות", "Use quiet hours"),
        // Languages
        SettingEntry("English", Screen.LANGUAGES, "Languages", "language english אנגלית"),
        SettingEntry("Hebrew keyboard", Screen.LANGUAGES, "Languages", "language hebrew ivrit עברית", "עברית · Hebrew"),
        // Voice
        SettingEntry("OpenAI API key", Screen.VOICE, VOICE, "openai key api sk מפתח"),
        SettingEntry("Microphone", Screen.VOICE, VOICE, "mic microphone permission מיקרופון"),
        SettingEntry("Send by voice", Screen.VOICE, VOICE, "send whatsapp tap send שלח"),
        SettingEntry("Languages you speak", Screen.VOICE, VOICE, "voice language hebrew english דיבור"),
        SettingEntry("Smart commands", Screen.VOICE, VOICE, "voice commands delete that new line פקודות"),
        SettingEntry("How to use voice typing", Screen.VOICE, VOICE, "voice talk dictate help קול", "How to use it"),
        // About
        SettingEntry("About Yboard", Screen.ABOUT, "About", "version privacy credits", null),
        SettingEntry("Check for updates", Screen.ABOUT, "About › Updates", "update upgrade apk github release install version", "Updates"),
    )

    /** Settings matching [query]: every word must appear in the name, place or keywords. Best first. */
    fun search(query: String, limit: Int = 12): List<SettingEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val words = q.split(Regex("\\s+"))
        return all.asSequence()
            .map { e -> e to "${e.title} ${e.where} ${e.words}".lowercase() }
            .filter { (_, hay) -> words.all { hay.contains(it) } }
            .sortedBy { (e, _) ->
                val t = e.title.lowercase()
                when {
                    t == q -> 0
                    t.startsWith(q) -> 1
                    t.contains(q) -> 2
                    else -> 3
                }
            }
            .map { it.first }
            .take(limit)
            .toList()
    }
}

/** The setting to scroll to and flash on the screen that was just opened from search. */
val LocalSearchTarget = compositionLocalOf<String?> { null }

/** Marks a row as a search target: when opened from search, it scrolls into view and flashes. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.searchTarget(title: String?): Modifier = composed {
    val target = LocalSearchTarget.current
    if (title == null || target == null || target != title) return@composed Modifier
    val requester = remember { BringIntoViewRequester() }
    val flash = remember { Animatable(0f) }
    val color = MaterialTheme.colorScheme.primary
    LaunchedEffect(target) {
        delay(380)  // let the screen slide in first
        requester.bringIntoView()
        repeat(2) {
            flash.animateTo(1f, tween(220))
            flash.animateTo(0.25f, tween(360))
        }
        flash.animateTo(0f, tween(700))
    }
    Modifier
        .bringIntoViewRequester(requester)
        .drawBehind {
            if (flash.value > 0f) drawRoundRect(color.copy(alpha = 0.2f * flash.value), cornerRadius = CornerRadius(16.dp.toPx()))
        }
}

@Composable
fun SettingsSearchField(query: String, onQuery: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = { onQuery(it.take(40)) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        singleLine = true,
        placeholder = { Text("Search settings (blur, sound, trail…)") },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null, modifier = Modifier.size(22.dp)) },
        trailingIcon = if (query.isEmpty()) null else {
            { IconButton(onClick = { onQuery("") }) { Text("✕", style = MaterialTheme.typography.titleMedium) } }
        },
    )
}

@Composable
fun SettingsSearchResults(query: String, onPick: (SettingEntry) -> Unit) {
    val results = remember(query) { SettingsIndex.search(query) }
    Section(if (results.isEmpty()) null else "Settings") {
        if (results.isEmpty()) {
            Text(
                "Nothing matches “${query.trim()}”. Try another word, like “color” or “voice”.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        }
        results.forEachIndexed { i, e ->
            if (i > 0) RowDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(e) }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(e.title, style = MaterialTheme.typography.bodyLarge)
                    Text(e.where, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(
                    painterResource(R.drawable.ic_chevron_right), contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
