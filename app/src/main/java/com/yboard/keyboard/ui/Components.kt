package com.yboard.keyboard.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yboard.keyboard.R
import com.yboard.keyboard.settings.YboardSettings
import com.yboard.keyboard.ui.theme.BrandBrush

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    onBack: () -> Unit,
    preview: YboardSettings? = null,
    actions: @Composable RowScope.() -> Unit = {},
    scrollContent: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = "Back")
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (preview != null) {
                KeyboardPreview(preview, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }
            val scrolling = if (scrollContent) Modifier.verticalScroll(rememberScrollState()) else Modifier
            Column(
                Modifier
                    .weight(1f)
                    .then(scrolling)
                    .padding(horizontal = 16.dp)
            ) {
                content()
                if (scrollContent) Spacer(Modifier.height(28.dp))
            }
        }
    }
}

@Composable
fun Section(title: String? = null, searchable: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().searchTarget(if (searchable) title else null)) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp, top = 22.dp, bottom = 8.dp),
            )
        } else {
            Spacer(Modifier.height(14.dp))
        }
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        ) {
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), content = content)
        }
    }
}

@Composable
fun RowDivider() {
    HorizontalDivider(
        Modifier.padding(horizontal = 20.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
    )
}

@Composable
fun IconBadge(icon: Int, size: Int = 40) {
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size * 0.32f).dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon), contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size((size * 0.55f).dp),
        )
    }
}

@Composable
fun LogoBadge(size: Int = 52) {
    // The app icon (a keyboard key with a Y) on its dark navy background.
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size * 0.3f).dp))
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF3B3F62), Color(0xFF1B1D2E)))),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Image(
            painterResource(R.drawable.ic_launcher_foreground), contentDescription = null,
            modifier = Modifier.requiredSize((size * 1.55f).dp),
        )
    }
}

@Composable
fun NavRow(icon: Int, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .searchTarget(title)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            painterResource(R.drawable.ic_chevron_right), contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .searchTarget(title)
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                FoldingText(subtitle, MaterialTheme.typography.bodyMedium, MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    enabled: Boolean = true,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().searchTarget(title).padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, steps = steps, enabled = enabled)
    }
}

@Composable
fun ColorRow(title: String, color: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .searchTarget(title)
            .clickable { open = true }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            toHex(color), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
        )
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(Color(color))
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
        )
    }
    if (open) {
        ColorPickerDialog(initial = color, onDismiss = { open = false }, onConfirm = { onPick(it); open = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentRow(title: String?, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().searchTarget(title).padding(horizontal = 20.dp, vertical = 10.dp)) {
        if (title != null) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 8.dp))
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, label ->
                SegmentedButton(
                    selected = i == selected,
                    onClick = { onSelect(i) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                ) { Text(label, maxLines = 1) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipRow(title: String, options: List<String>, selected: Int, wrap: Boolean = false, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().searchTarget(title).padding(vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 20.dp))
        val chips: @Composable () -> Unit = {
            options.forEachIndexed { i, label ->
                FilterChip(selected = i == selected, onClick = { onSelect(i) }, label = { Text(label) })
            }
        }
        val padding = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        val spacing = Arrangement.spacedBy(8.dp)
        if (wrap) {
            FlowRow(Modifier.fillMaxWidth().then(padding), horizontalArrangement = spacing) { chips() }
        } else {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).then(padding),
                horizontalArrangement = spacing) { chips() }
        }
    }
}

@Composable
fun HintText(text: String) {
    FoldingText(
        text,
        MaterialTheme.typography.bodySmall,
        MaterialTheme.colorScheme.onSurfaceVariant,
        Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 10.dp),
    )
}

/** Help longer than this folds away behind "How it works". */
const val FOLD_LIMIT = 110

/**
 * Where long help text folds: after its first sentence when that's short, else right away (0).
 * Null = short enough to show in full.
 */
fun foldPoint(text: String, limit: Int = FOLD_LIMIT): Int? {
    if (text.length <= limit) return null
    val m = Regex("[.!?…](\\s|$)").find(text) ?: return 0
    val end = m.range.first + 1
    return if (end <= limit && end < text.length) end else 0
}

/** Text that shows its first sentence and folds the rest behind a "How it works" link. */
@Composable
fun FoldingText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    label: String = "How it works",
) {
    val at = foldPoint(text)
    if (at == null) {
        Text(text, style = style, color = color, modifier = modifier)
        return
    }
    var open by rememberSaveable(text) { mutableStateOf(false) }
    Column(modifier.animateContentSize()) {
        val shown = if (open) text else text.substring(0, at).trim()
        if (shown.isNotEmpty()) Text(shown, style = style, color = color)
        FoldToggle(open, label) { open = !open }
    }
}

/** Content hidden behind a link ("Things you can say ▾"), opened with a tap. */
@Composable
fun Fold(label: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(label) { mutableStateOf(false) }
    Column(modifier.animateContentSize()) {
        if (open) content()
        FoldToggle(open, label) { open = !open }
    }
}

@Composable
private fun FoldToggle(open: Boolean, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(top = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (open) "Show less" else label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Icon(
            painterResource(if (open) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
    }
}

fun toHex(color: Int): String = String.format("#%06X", color and 0xFFFFFF)

fun pct(v: Float): String = "${(v * 100).toInt()}%"
