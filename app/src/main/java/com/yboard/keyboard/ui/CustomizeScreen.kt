package com.yboard.keyboard.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import android.widget.Toast
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.yboard.keyboard.R
import com.yboard.keyboard.settings.BgType
import com.yboard.keyboard.settings.FontStyle
import com.yboard.keyboard.settings.KeyStyle
import com.yboard.keyboard.settings.PressEffect
import com.yboard.keyboard.keyboard.DynamicColors
import com.yboard.keyboard.settings.KeyboardTheme
import com.yboard.keyboard.settings.Prefs
import com.yboard.keyboard.settings.ThemePresets
import com.yboard.keyboard.settings.YboardSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Editing a preset turns it into an "edited" copy so the original stays untouched. */
private fun KeyboardTheme.edited(): KeyboardTheme =
    if (id == "edited" || id.startsWith("custom_")) this else copy(id = "edited", name = "$name (edited)")

private fun edit(transform: (KeyboardTheme) -> KeyboardTheme) =
    Prefs.updateTheme { transform(it).edited() }

/** Copies the picked photo into app storage, scaled down to a sensible size. */
private fun saveBackgroundImage(context: Context, uri: Uri): String? = try {
    val maxSide = 1440
    val bitmap: Bitmap? = if (Build.VERSION.SDK_INT >= 28) {
        val src = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = maxSide / max(w, h).toFloat()
            if (scale < 1f) decoder.setTargetSize((w * scale).roundToInt(), (h * scale).roundToInt())
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
    if (bitmap == null) null else {
        val file = File(context.filesDir, "bg_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        file.absolutePath
    }
} catch (e: Exception) {
    null
}

/** Removes background photos that no theme uses any more. */
private fun cleanupImages(context: Context, settings: YboardSettings) {
    val used = (settings.savedThemes + settings.theme).mapNotNull { it.bgImagePath }.toSet()
    context.filesDir.listFiles { f -> f.name.startsWith("bg_") && f.name.endsWith(".jpg") }
        ?.filter { it.absolutePath !in used }
        ?.forEach { it.delete() }
}

@Composable
fun CustomizeScreen(settings: YboardSettings, onBack: () -> Unit) {
    val context = LocalContext.current
    // Show the real colors even for "wallpaper colors" themes.
    val t = DynamicColors.resolve(context, settings.theme)
    // Picking a color by hand stops following the wallpaper.
    fun editColor(transform: (KeyboardTheme) -> KeyboardTheme) =
        edit { transform(DynamicColors.resolve(context, it).copy(dynamicColors = false)) }
    val scope = rememberCoroutineScope()
    var showSave by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val path = withContext(Dispatchers.IO) { saveBackgroundImage(context, uri) }
            if (path != null) {
                edit { it.copy(bgType = BgType.IMAGE, bgImagePath = path) }
                withContext(Dispatchers.IO) { cleanupImages(context, Prefs.current) }
            }
        }
    }

    ScreenScaffold(
        "Customize look", onBack, preview = settings,
        actions = {
            TextButton(onClick = { showReset = true }) {
                Icon(painterResource(R.drawable.ic_restore), null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Reset")
            }
        },
    ) {
        Section("Transparency") {
            SliderRow("Keyboard background", t.bgOpacity, 0f..1f, pct(t.bgOpacity)) { v -> edit { it.copy(bgOpacity = v) } }
            SliderRow("Keys", t.keyOpacity, 0f..1f, pct(t.keyOpacity)) { v -> edit { it.copy(keyOpacity = v) } }
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                SliderRow("Blur behind", t.blur, 0f..40f, if (t.blur < 0.5f) "Off" else "${t.blur.roundToInt()}") { v ->
                    edit { it.copy(blur = if (v < 0.5f) 0f else v) }
                }
            }
            SwitchRow("Glass keys", "See-through keys with a shiny edge that moves when you tilt the phone", t.glassKeys) { b ->
                edit { it.copy(glassKeys = b) }
            }
            HintText("Lower values let the app behind the keyboard show through. Blur softens it, like frosted or liquid glass (your phone may turn blur off in battery saver).")
        }

        Section("Background", searchable = true) {
            SegmentRow(null, listOf("Color", "Gradient", "Photo"), t.bgType.ordinal) { i ->
                val type = BgType.entries[i]
                if (type == BgType.IMAGE && t.bgImagePath == null) {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                } else {
                    edit { it.copy(bgType = type) }
                }
            }
            when (t.bgType) {
                BgType.COLOR -> ColorRow("Background color", t.bgColor) { c -> editColor { it.copy(bgColor = c) } }
                BgType.GRADIENT -> {
                    ColorRow("Start color", t.bgColor) { c -> editColor { it.copy(bgColor = c) } }
                    ColorRow("End color", t.bgColor2) { c -> editColor { it.copy(bgColor2 = c) } }
                    SwitchRow("Middle color", "Three colors, like the gradient themes", t.bgColorMid != null) { on ->
                        edit {
                            it.copy(bgColorMid = if (on) com.yboard.keyboard.keyboard.ThemeRender.blend(it.bgColor, it.bgColor2, 0.5f) else null)
                        }
                    }
                    t.bgColorMid?.let { mid ->
                        ColorRow("Middle", mid) { c -> editColor { it.copy(bgColorMid = c) } }
                    }
                    SliderRow("Direction", t.gradientAngle.toFloat(), 0f..360f, "${t.gradientAngle}°") { v ->
                        edit { it.copy(gradientAngle = v.roundToInt()) }
                    }
                }
                BgType.IMAGE -> {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        FilledTonalButton(
                            onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(painterResource(R.drawable.ic_image), null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(if (t.bgImagePath == null) "Choose a photo" else "Change photo")
                        }
                    }
                    SliderRow("Darken photo", t.imageDim, 0f..0.85f, pct(t.imageDim)) { v -> edit { it.copy(imageDim = v) } }
                    SwitchRow("Blur photo", "Softens the photo so keys stand out", t.imageBlur) { b -> edit { it.copy(imageBlur = b) } }
                }
            }
        }

        Section("Keys") {
            SegmentRow("Key style", listOf("Filled", "Outline", "Flat"), t.keyStyle.ordinal) { i ->
                edit { it.copy(keyStyle = KeyStyle.entries[i]) }
            }
            ChipRow("Key shape", listOf("Rounded", "Round", "Bubble", "Square", "No gaps"), t.keyShape.ordinal) { i ->
                edit { it.copy(keyShape = com.yboard.keyboard.settings.KeyShape.entries[i]) }
            }
            if (t.keyShape == com.yboard.keyboard.settings.KeyShape.ROUNDED) {
                SliderRow("Corner roundness", t.cornerRadius, 0f..24f, "${t.cornerRadius.roundToInt()}") { v -> edit { it.copy(cornerRadius = v) } }
            }
            SliderRow("Space between keys", t.keyGapH, 0f..12f, "${t.keyGapH.roundToInt()}") { v -> edit { it.copy(keyGapH = v) } }
            SliderRow("Space between rows", t.keyGapV, 0f..22f, "${t.keyGapV.roundToInt()}") { v -> edit { it.copy(keyGapV = v) } }
            SliderRow("Border", t.borderWidth, 0f..4f, String.format("%.1f", t.borderWidth)) { v -> edit { it.copy(borderWidth = v) } }
            if (t.borderWidth > 0f || t.keyStyle == KeyStyle.OUTLINED) {
                ColorRow("Border color", t.borderColor) { c -> editColor { it.copy(borderColor = c) } }
            }
            SwitchRow("Key shadows", "A soft shadow under each key", t.keyShadow) { b -> edit { it.copy(keyShadow = b) } }
        }

        Section("Effects") {
            SegmentRow("When you press a key", listOf("None", "Pop", "Glow", "Ripple"), t.pressEffect.ordinal) { i ->
                edit { it.copy(pressEffect = PressEffect.entries[i]) }
            }
            SwitchRow("RGB lights", "A rainbow glow that flows around the keys (uses a bit more battery)", t.rgbLights) { b ->
                edit { it.copy(rgbLights = b) }
            }
            ChipRow("Moving background", listOf("None", "Color flow", "Snow", "Stars", "Bubbles"), t.bgMotion.ordinal) { i ->
                edit { it.copy(bgMotion = com.yboard.keyboard.settings.BgMotion.entries[i]) }
            }
            HintText("Moving backgrounds only move while the keyboard is open, and use a little more battery.")
        }

        Section("Colors") {
            if (DynamicColors.available) {
                SwitchRow("Match wallpaper colors", "Colors follow your phone's wallpaper and dark mode", t.dynamicColors) { b ->
                    edit { it.copy(dynamicColors = b) }
                }
            }
            ColorRow("Letter keys", t.keyColor) { c -> editColor { it.copy(keyColor = c) } }
            ColorRow("Function keys", t.specialKeyColor) { c -> editColor { it.copy(specialKeyColor = c) } }
            ColorRow("Text & icons", t.textColor) { c -> editColor { it.copy(textColor = c) } }
            SwitchRow("Colored Enter key", "Off: Enter is gray like shift and backspace (Gboard dark)", t.accentEnter) { b ->
                edit { it.copy(accentEnter = b) }
            }
            ColorRow("Accent (Enter key, swipe trail)", t.accentColor) { c -> editColor { it.copy(accentColor = c) } }
            ColorRow("Accent text", t.accentTextColor) { c -> editColor { it.copy(accentTextColor = c) } }
        }

        Section("Text") {
            SliderRow("Letter size", t.fontScale, 0.7f..1.4f, pct(t.fontScale)) { v -> edit { it.copy(fontScale = v) } }
            SwitchRow("Bold letters", null, t.boldLabels) { b -> edit { it.copy(boldLabels = b) } }
            ChipRow("Font", listOf("Default", "Light", "Condensed", "Serif", "Mono", "Casual", "Phone font"), t.fontStyle.ordinal) { i ->
                edit { it.copy(fontStyle = FontStyle.entries[i]) }
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { showReset = true },
                modifier = Modifier.weight(1f).height(52.dp),
            ) {
                Icon(painterResource(R.drawable.ic_restore), null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Reset")
            }
            Button(onClick = { showSave = true }, modifier = Modifier.weight(1.4f).height(52.dp)) {
                Icon(painterResource(R.drawable.ic_save), null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Save as my theme")
            }
        }
    }

    if (showReset) {
        var alsoLayout by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { showReset = false },
            icon = { Icon(painterResource(R.drawable.ic_restore), null) },
            title = { Text("Reset customizations?") },
            text = {
                Column {
                    Text(
                        "The keyboard goes back to the default look (${ThemePresets.default.name}, like Gboard): " +
                            "colors, background, keys, effects and text. " +
                            "Your saved themes, languages, typing and sound settings stay."
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp).clickable { alsoLayout = !alsoLayout },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = alsoLayout, onCheckedChange = { alsoLayout = it })
                        Text("Also reset size & layout (height, lift, number row, one-handed)")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    Prefs.update { it.withDefaultLook(includeLayout = alsoLayout) }
                    showReset = false
                    scope.launch(Dispatchers.IO) { cleanupImages(context, Prefs.current) }
                    Toast.makeText(context, "Customizations reset", Toast.LENGTH_SHORT).show()
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { showReset = false }) { Text("Cancel") } },
        )
    }

    if (showSave) {
        var name by remember { mutableStateOf(t.name.removeSuffix(" (edited)").let { if (it.startsWith("My ")) it else "My $it" }) }
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text("Save theme") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it.take(24) }, singleLine = true, label = { Text("Name") })
            },
            confirmButton = {
                TextButton(onClick = {
                    val saved = Prefs.current.theme.copy(id = "custom_${System.currentTimeMillis()}", name = name.ifBlank { "My theme" })
                    Prefs.update { it.copy(theme = saved, savedThemes = it.savedThemes + saved) }
                    showSave = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showSave = false }) { Text("Cancel") } },
        )
    }
}
