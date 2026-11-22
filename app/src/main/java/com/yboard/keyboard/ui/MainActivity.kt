package com.yboard.keyboard.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.yboard.keyboard.settings.Prefs
import com.yboard.keyboard.ui.theme.YboardTheme
import com.yboard.keyboard.update.AppUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen(val title: String) {
    HOME("Yboard"),
    THEMES("Themes"),
    CUSTOMIZE("Customize look"),
    LAYOUT("Layout & size"),
    TOOLBAR("Toolbar shortcuts"),
    TYPING("Typing"),
    SOUND("Sound & vibration"),
    LANGUAGES("Languages"),
    VOICE("Voice typing"),
    AI_MODELS("AI models"),
    SHORTCUTS("Text shortcuts"),
    MY_WORDS("My words"),
    ABOUT("About"),
}

class MainActivity : ComponentActivity() {
    /** A screen the keyboard asked to open (e.g. Voice typing when the key is missing). */
    private val openRequest = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        lifecycleScope.launch(Dispatchers.IO) {
            AppUpdater.removeInstalledUpdateApks(applicationContext)
        }
        enableEdgeToEdge()
        openRequest.value = intent?.getStringExtra(EXTRA_OPEN)
        intent?.removeExtra(EXTRA_OPEN)
        setContent {
            YboardTheme {
                YboardSettingsApp(openRequest)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_OPEN)?.let { openRequest.value = it }
        intent.removeExtra(EXTRA_OPEN)
        setIntent(intent)
    }

    companion object {
        const val EXTRA_OPEN = "open"
        const val OPEN_VOICE = "voice"
        const val OPEN_ABOUT = "about"
        const val OPEN_UPDATES = "updates"
        const val OPEN_AI_MODELS = "ai_models"
        const val OPEN_TYPING = "typing"
    }
}

@Composable
fun YboardSettingsApp(openRequest: MutableState<String?> = mutableStateOf(null)) {
    val settings by Prefs.state.collectAsState()
    var stackNames by rememberSaveable { mutableStateOf(listOf(Screen.HOME.name)) }
    val stack = stackNames.map { Screen.valueOf(it) }
    val top = stack.last()
    val below = stack.getOrNull(stack.size - 2)
    // Keeps each screen's scroll position etc. while another screen is on top of it.
    val saved = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    /** 0 = the top screen is in place; 1 = it has slid off and the screen below shows. */
    val slide = remember { Animatable(0f) }
    /** 1 = slides to the right (back swipe from the left edge), -1 = to the left (from the right edge). */
    var side by remember { mutableFloatStateOf(1f) }
    var moving by remember { mutableStateOf(false) }
    var searchTarget by remember { mutableStateOf<String?>(null) }
    var updateCheckRequested by rememberSaveable { mutableStateOf(false) }

    fun open(s: Screen, target: String? = null) {
        if (moving || top == s) return
        searchTarget = target
        side = 1f
        stackNames = stackNames + s.name
        moving = true
        scope.launch {
            try {
                slide.snapTo(1f)
                slide.animateTo(0f, tween(SLIDE_IN_MS, easing = FastOutSlowInEasing))
            } finally {
                moving = false
            }
        }
    }

    fun back() {
        if (moving || stackNames.size <= 1) return
        moving = true
        side = 1f
        scope.launch {
            slide.animateTo(1f, tween(SLIDE_OUT_MS, easing = FastOutSlowInEasing))
            stackNames = stackNames.dropLast(1)
            searchTarget = null
            slide.snapTo(0f)
            moving = false
        }
    }

    // Swipe back: the screen follows your finger, showing the one you're going back to.
    // (Plain back presses come through here too, without a swipe.)
    PredictiveBackHandler(enabled = stack.size > 1) { events ->
        moving = true
        try {
            events.collect { e ->
                side = if (e.swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
                slide.snapTo(e.progress * 0.85f)
            }
            slide.animateTo(1f, tween(SLIDE_OUT_MS, easing = FastOutSlowInEasing))
            stackNames = stackNames.dropLast(1)
            searchTarget = null
            slide.snapTo(0f)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { slide.animateTo(0f, tween(SLIDE_OUT_MS)) }
            throw e
        } finally {
            moving = false
        }
    }

    val request = openRequest.value
    LaunchedEffect(request) {
        if (request == MainActivity.OPEN_TYPING) {
            stackNames = listOf(Screen.HOME.name, Screen.TYPING.name)
            openRequest.value = null
        }
        if (request == MainActivity.OPEN_AI_MODELS) {
            stackNames = listOf(Screen.HOME.name, Screen.AI_MODELS.name)
            openRequest.value = null
        }
        if (request == MainActivity.OPEN_ABOUT || request == MainActivity.OPEN_UPDATES) {
            stackNames = listOf(Screen.HOME.name, Screen.ABOUT.name)
            if (request == MainActivity.OPEN_UPDATES) {
                searchTarget = "Updates"
                updateCheckRequested = true
            }
            openRequest.value = null
        }
        if (request == MainActivity.OPEN_VOICE) {
            stackNames = listOf(Screen.HOME.name, Screen.VOICE.name)
            openRequest.value = null
        }
    }

    @Composable
    fun ScreenContent(screen: Screen) {
        when (screen) {
            Screen.HOME -> HomeScreen(settings, onOpen = { open(it) }, onSearchPick = { open(it.screen, it.target) })
            Screen.THEMES -> ThemesScreen(settings, onBack = ::back, onCustomize = { open(Screen.CUSTOMIZE) })
            Screen.CUSTOMIZE -> CustomizeScreen(settings, onBack = ::back)
            Screen.LAYOUT -> LayoutScreen(settings, onBack = ::back, onToolbar = { open(Screen.TOOLBAR) })
            Screen.TOOLBAR -> ToolbarScreen(settings, onBack = ::back)
            Screen.TYPING -> TypingScreen(
                settings, onBack = ::back, onShortcuts = { open(Screen.SHORTCUTS) }, onMyWords = { open(Screen.MY_WORDS) },
            )
            Screen.SHORTCUTS -> ShortcutsScreen(settings, onBack = ::back)
            Screen.MY_WORDS -> MyWordsScreen(settings, onBack = ::back)
            Screen.SOUND -> SoundScreen(settings, onBack = ::back)
            Screen.LANGUAGES -> LanguagesScreen(settings, onBack = ::back)
            Screen.VOICE -> VoiceScreen(settings, onBack = ::back)
            Screen.AI_MODELS -> AiModelsScreen(settings, onBack = ::back)
            Screen.ABOUT -> AboutScreen(
                onBack = ::back, updateCheckRequested = updateCheckRequested,
                onUpdateCheckHandled = { updateCheckRequested = false },
            )
        }
    }

    val background = MaterialTheme.colorScheme.background
    BoxWithConstraints(Modifier.fillMaxSize().background(background)) {
        val width = constraints.maxWidth.toFloat()
        val v = slide.value
        // The screen underneath, while the top one slides: it drifts in a little and brightens.
        if (v > 0f && below != null) {
            key(below) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationX = -(1f - v) * width * 0.25f * side }
                        .background(background)
                ) {
                    saved.SaveableStateProvider(below.name) { ScreenContent(below) }
                }
            }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.22f * (1f - v))))
        }
        key(top) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = v * width * side
                        if (v > 0f) {
                            val sc = 1f - 0.06f * v
                            scaleX = sc
                            scaleY = sc
                            shadowElevation = 18.dp.toPx()
                            shape = RoundedCornerShape(28.dp)
                            clip = true
                        }
                    }
                    .background(background)
            ) {
                CompositionLocalProvider(LocalSearchTarget provides searchTarget) {
                    saved.SaveableStateProvider(top.name) { ScreenContent(top) }
                }
            }
        }
    }
}

private const val SLIDE_IN_MS = 340
private const val SLIDE_OUT_MS = 260
