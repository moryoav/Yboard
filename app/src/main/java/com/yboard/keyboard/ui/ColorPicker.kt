package com.yboard.keyboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import android.graphics.Color as AColor

private val swatches = listOf(
    0xFFFFFFFF, 0xFFF1F1F3, 0xFFB0B0B8, 0xFF5A5A60, 0xFF3A3A3D, 0xFF1C1C1E, 0xFF0D0D12, 0xFF000000,
    0xFFEF5350, 0xFFFF7043, 0xFFFFCA28, 0xFF66BB6A, 0xFF26A69A, 0xFF29B6F6, 0xFF5C6BC0, 0xFF7E57C2,
    0xFFEC407A, 0xFFA8C7FA, 0xFF0B57D0, 0xFF6D5BFF, 0xFFC04BF2, 0xFF00E5FF, 0xFFFF2BD6, 0xFF8D6E63,
).map { it.toInt() }

private fun hsvToColor(h: Float, s: Float, v: Float): Int = AColor.HSVToColor(floatArrayOf(h, s, v))

@Composable
fun ColorPickerDialog(initial: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val start = remember { FloatArray(3).also { AColor.colorToHSV(initial, it) } }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var sat by remember { mutableFloatStateOf(start[1]) }
    var value by remember { mutableFloatStateOf(start[2]) }
    var hex by remember { mutableStateOf(toHex(initial).removePrefix("#")) }
    val color = hsvToColor(hue, sat, value)

    fun setFrom(c: Int) {
        val hsv = FloatArray(3)
        AColor.colorToHSV(c, hsv)
        hue = hsv[0]; sat = hsv[1]; value = hsv[2]
        hex = toHex(c).removePrefix("#")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(color or 0xFF000000.toInt()) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Pick a color") },
        text = {
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(color))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                )
                Spacer(Modifier.height(14.dp))
                swatches.chunked(8).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { c ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .clip(CircleShape)
                                    .background(Color(c))
                                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), CircleShape)
                                    .clickable { setFrom(c) }
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Spacer(Modifier.height(6.dp))
                GradientSlider(
                    "Hue", hue, 0f..360f,
                    Brush.horizontalGradient((0..6).map { Color(hsvToColor(it * 60f, 1f, 1f)) }),
                ) { hue = it; hex = toHex(hsvToColor(hue, sat, value)).removePrefix("#") }
                GradientSlider(
                    "Saturation", sat, 0f..1f,
                    Brush.horizontalGradient(listOf(Color(hsvToColor(hue, 0f, value)), Color(hsvToColor(hue, 1f, value)))),
                ) { sat = it; hex = toHex(hsvToColor(hue, sat, value)).removePrefix("#") }
                GradientSlider(
                    "Brightness", value, 0f..1f,
                    Brush.horizontalGradient(listOf(Color.Black, Color(hsvToColor(hue, sat, 1f)))),
                ) { value = it; hex = toHex(hsvToColor(hue, sat, value)).removePrefix("#") }
                OutlinedTextField(
                    value = hex,
                    onValueChange = { input ->
                        val clean = input.removePrefix("#").filter { it.isLetterOrDigit() }.take(6)
                        hex = clean
                        if (clean.length == 6) {
                            runCatching { AColor.parseColor("#$clean") }.getOrNull()?.let { c ->
                                val hsv = FloatArray(3)
                                AColor.colorToHSV(c, hsv)
                                hue = hsv[0]; sat = hsv[1]; value = hsv[2]
                            }
                        }
                    },
                    prefix = { Text("#") },
                    label = { Text("Hex code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        },
    )
}

@Composable
private fun GradientSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    brush: Brush,
    onChange: (Float) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.fillMaxWidth().height(38.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(brush)
            )
            Slider(
                value = value, onValueChange = onChange, valueRange = range,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                ),
            )
        }
    }
}
