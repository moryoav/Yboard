package com.yboard.keyboard.ui

import android.view.View
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.yboard.keyboard.R
import com.yboard.keyboard.keyboard.DynamicColors
import com.yboard.keyboard.keyboard.ThemeRender
import com.yboard.keyboard.keyboard.ToolbarView
import com.yboard.keyboard.settings.Prefs
import com.yboard.keyboard.settings.ToolbarShortcut
import com.yboard.keyboard.settings.ToolbarShortcuts
import com.yboard.keyboard.settings.YboardSettings

@Composable
fun ToolbarScreen(settings: YboardSettings, onBack: () -> Unit) {
    val shortcuts = settings.toolbarShortcuts
    ScreenScaffold(
        title = Screen.TOOLBAR.title,
        onBack = onBack,
        actions = {
            TextButton(
                enabled = shortcuts != ToolbarShortcuts(),
                onClick = { Prefs.update { it.copy(toolbarShortcuts = ToolbarShortcuts()) } },
            ) { Text("Reset") }
        },
    ) {
        Spacer(Modifier.height(8.dp))
        if (shortcuts.visible.isEmpty()) {
            HintText("All shortcuts are hidden.")
        } else {
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(16.dp)),
                factory = { context ->
                    ToolbarView(context).apply {
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        contentDescription = null
                    }
                },
                update = { toolbar ->
                    val theme = DynamicColors.resolve(toolbar.context, settings.theme)
                    toolbar.setBackgroundColor(ThemeRender.dominantColor(theme))
                    toolbar.applySettings(settings.copy(theme = theme))
                },
            )
        }
        Spacer(Modifier.height(6.dp))
        HintText("Use the arrows to set the order from left to right. Turn off a shortcut to hide it.")
        if (!settings.toolbar) HintText("Turn on Toolbar in Layout & size to see these shortcuts on the keyboard.")

        Section {
            shortcuts.order.forEachIndexed { index, shortcut ->
                key(shortcut) {
                    if (index > 0) RowDivider()
                    ToolbarShortcutRow(shortcut, index, shortcuts)
                }
            }
        }
    }
}

@Composable
private fun ToolbarShortcutRow(shortcut: ToolbarShortcut, index: Int, shortcuts: ToolbarShortcuts) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(shortcut.icon), contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(shortcut.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        IconButton(
            enabled = index > 0,
            onClick = { Prefs.update { it.copy(toolbarShortcuts = it.toolbarShortcuts.move(shortcut, -1)) } },
        ) {
            Icon(painterResource(R.drawable.ic_chevron_up), contentDescription = "Move ${shortcut.label} up")
        }
        IconButton(
            enabled = index < shortcuts.order.lastIndex,
            onClick = { Prefs.update { it.copy(toolbarShortcuts = it.toolbarShortcuts.move(shortcut, 1)) } },
        ) {
            Icon(painterResource(R.drawable.ic_chevron_down), contentDescription = "Move ${shortcut.label} down")
        }
        Switch(
            checked = shortcut !in shortcuts.hidden,
            onCheckedChange = { visible ->
                Prefs.update { it.copy(toolbarShortcuts = it.toolbarShortcuts.withVisibility(shortcut, visible)) }
            },
            modifier = Modifier.semantics { contentDescription = "Show ${shortcut.label}" },
        )
    }
}
