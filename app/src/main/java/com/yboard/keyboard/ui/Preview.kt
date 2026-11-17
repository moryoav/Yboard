package com.yboard.keyboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.yboard.keyboard.keyboard.KeyDef
import com.yboard.keyboard.keyboard.KeyType
import com.yboard.keyboard.keyboard.KeyboardRootView
import com.yboard.keyboard.keyboard.KeyboardView
import com.yboard.keyboard.keyboard.Layouts
import com.yboard.keyboard.keyboard.Mode
import com.yboard.keyboard.keyboard.ShiftState
import com.yboard.keyboard.keyboard.ThemeTileView
import com.yboard.keyboard.settings.KeyboardTheme
import com.yboard.keyboard.settings.OneHanded
import com.yboard.keyboard.settings.Prefs
import com.yboard.keyboard.settings.YboardSettings

/** Makes the preview keyboard react to shift / symbols / language taps. Typed letters go nowhere. */
private class PreviewController(private val root: KeyboardRootView) : KeyboardView.Listener {
    var mode = Mode.LETTERS
    var shift = ShiftState.OFF
    var language: String? = null

    fun refresh(s: YboardSettings) {
        val lang = language?.takeIf { it in s.enabledLanguages } ?: s.activeLanguage
        val layout = Layouts.build(mode, lang, s.numberRow, s.enabledLanguages.size > 1)
        if (layout != root.keyboard.layoutDef) root.keyboard.setLayout(layout)
        root.keyboard.shiftState = shift
    }

    override fun onKey(key: KeyDef) {
        val s = root.settings
        when (key.type) {
            KeyType.SHIFT -> shift = if (shift == ShiftState.OFF) ShiftState.ON else ShiftState.OFF
            KeyType.SYMBOLS -> mode = Mode.SYMBOLS
            KeyType.SYMBOLS2 -> mode = Mode.SYMBOLS2
            KeyType.LETTERS -> mode = Mode.LETTERS
            KeyType.GLOBE -> {
                val langs = s.enabledLanguages
                val cur = language?.takeIf { it in langs } ?: s.activeLanguage
                language = langs[(langs.indexOf(cur) + 1) % langs.size]
            }
            KeyType.CHAR -> if (shift == ShiftState.ON) shift = ShiftState.OFF
            else -> Unit
        }
        refresh(s)
    }

    override fun onText(text: String) {
        if (shift == ShiftState.ON) { shift = ShiftState.OFF; refresh(root.settings) }
    }

    override fun onLongPress(key: KeyDef): Boolean {
        if (key.type == KeyType.SHIFT) { shift = ShiftState.LOCKED; refresh(root.settings); return true }
        return false
    }

    override fun onCursorMove(steps: Int) = Unit

    override fun onOneHanded(action: Int) {
        Prefs.update {
            it.copy(
                oneHanded = if (action == KeyboardView.ONE_HANDED_EXIT) OneHanded.OFF
                else if (it.oneHanded == OneHanded.LEFT) OneHanded.RIGHT else OneHanded.LEFT
            )
        }
    }
}

/** A colourful "chat wallpaper" so see-through keyboard themes are easy to judge. */
@Composable
private fun Backdrop(modifier: Modifier) {
    Box(
        modifier.background(
            Brush.linearGradient(listOf(Color(0xFF8EC5FC), Color(0xFFB79CF7), Color(0xFFF7A8C4)))
        )
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Bubble("Did you try Yboard yet? 😄", incoming = true)
            Bubble("Yes!! It looks so clean", incoming = false)
            Bubble("Make it see-through, it's 🔥", incoming = true)
            Bubble("Already did 😎", incoming = false)
            Bubble("Send me your theme!", incoming = true)
            Bubble("Sending it now ✨", incoming = false)
            Bubble("This is the best keyboard", incoming = true)
        }
    }
}

@Composable
private fun Bubble(text: String, incoming: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (incoming) Arrangement.Start else Arrangement.End) {
        Text(
            text,
            color = if (incoming) Color(0xFF1B1B21) else Color.White,
            fontSize = 14.sp,
            modifier = Modifier
                .widthIn(max = 240.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(if (incoming) Color.White.copy(alpha = 0.92f) else Color(0xFF5B4BEB))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/** The real keyboard view, live-updated with the current settings. */
@Composable
fun KeyboardPreview(settings: YboardSettings, modifier: Modifier = Modifier, topSpace: Dp = 0.dp) {
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))) {
        Backdrop(Modifier.matchParentSize())
        Column(Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(topSpace))
            AndroidView(
                modifier = Modifier.fillMaxWidth(),
                factory = { ctx ->
                    KeyboardRootView(ctx, preview = true).apply {
                        val controller = PreviewController(this)
                        keyboard.listener = controller
                        tag = controller
                    }
                },
                update = { view ->
                    view.apply(settings)
                    (view.tag as? PreviewController)?.refresh(settings)
                },
            )
        }
    }
}

@Composable
fun ThemeTile(
    theme: KeyboardTheme,
    selected: Boolean,
    selectionColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(modifier) {
        AndroidView(
            modifier = Modifier.matchParentSize(),
            factory = { ThemeTileView(it) },
            update = {
                it.theme = theme
                it.selectedTile = selected
                it.selectionColor = selectionColor.toArgb()
            },
        )
        Box(Modifier.matchParentSize().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick))
    }
}

@Composable
fun CenteredBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) { content() }
}
