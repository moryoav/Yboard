package com.yboard.keyboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yboard.keyboard.R
import com.yboard.keyboard.settings.KeyboardTheme
import com.yboard.keyboard.settings.Prefs
import com.yboard.keyboard.settings.ThemePresets
import com.yboard.keyboard.settings.YboardSettings

@Composable
fun ThemesScreen(settings: YboardSettings, onBack: () -> Unit, onCustomize: () -> Unit) {
    ScreenScaffold("Themes", onBack, preview = settings) {
        Text(
            "Tap a theme to use it. Every theme can be tweaked in Customize.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, top = 14.dp, end = 8.dp),
        )
        Text(
            "Ready-made",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, top = 18.dp, bottom = 10.dp),
        )
        ThemeGrid(ThemePresets.visible, settings.theme.id, onDelete = null)

        Text(
            "My themes",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, top = 22.dp, bottom = 10.dp),
        )
        if (settings.savedThemes.isEmpty()) {
            Text(
                "Make a theme in Customize look and tap \"Save as my theme\" to keep it here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        } else {
            ThemeGrid(settings.savedThemes, settings.theme.id, onDelete = { t ->
                Prefs.update { s -> s.copy(savedThemes = s.savedThemes.filterNot { it.id == t.id }) }
            })
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = onCustomize, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(painterResource(R.drawable.ic_tune), null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
            Text("Customize this theme")
        }
    }
}

@Composable
private fun ThemeGrid(themes: List<KeyboardTheme>, selectedId: String, onDelete: ((KeyboardTheme) -> Unit)?) {
    val accent = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        themes.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { theme ->
                    Column(Modifier.weight(1f)) {
                        Box {
                            ThemeTile(
                                theme = theme,
                                selected = theme.id == selectedId,
                                selectionColor = accent,
                                modifier = Modifier.fillMaxWidth().aspectRatio(1.55f),
                            ) { Prefs.update { it.copy(theme = theme) } }
                            if (onDelete != null) {
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(6.dp)
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.55f))
                                        .clickable { onDelete(theme) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(painterResource(R.drawable.ic_delete), "Delete", tint = Color.White, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                        Text(
                            theme.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                        )
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
