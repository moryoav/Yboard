package com.yboard.keyboard.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.yboard.keyboard.keyboard.TrailPainter
import com.yboard.keyboard.settings.Prefs
import com.yboard.keyboard.settings.TrailColor
import com.yboard.keyboard.settings.YboardSettings
import com.yboard.keyboard.text.Pt
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** Typing → Swipe: how the swipe trail looks (thickness, color, glow), with a live preview. */
@Composable
fun SwipeTrailSettings(settings: YboardSettings) {
    TrailPreview(settings)
    SliderRow(
        "Trail thickness", TrailPainter.widthDp(settings), TrailPainter.MIN_WIDTH..TrailPainter.MAX_WIDTH,
        "${TrailPainter.widthDp(settings).roundToInt()}",
    ) { v -> Prefs.update { it.copy(trailWidth = v) } }
    SegmentRow("Trail color", listOf("Theme", "Rainbow", "My color"), settings.trailColor.ordinal) { i ->
        Prefs.update { it.copy(trailColor = TrailColor.values()[i]) }
    }
    if (settings.trailColor == TrailColor.CUSTOM) {
        ColorRow("My trail color", settings.trailCustomColor) { c -> Prefs.update { it.copy(trailCustomColor = c) } }
    }
    SwitchRow("Glow", "A soft glow around the trail, so it's easier to see", settings.trailGlow) { b ->
        Prefs.update { it.copy(trailGlow = b) }
    }
}

/** A sample swipe drawn exactly like on the keyboard, over the theme's background color. */
@Composable
fun TrailPreview(settings: YboardSettings) {
    val density = LocalDensity.current.density
    val painter = remember(density) { TrailPainter(density) }
    val rainbow = settings.trailColor == TrailColor.RAINBOW
    val time by produceState(0L, rainbow) {
        if (rainbow) while (true) value = withFrameMillis { it }
    }
    Canvas(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .height(92.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(settings.theme.bgColor).copy(alpha = 1f))
    ) {
        val w = size.width
        val h = size.height
        val pts = (0..80).map { i ->
            val t = i / 80f
            Pt(w * 0.08f + t * w * 0.84f, h * 0.5f + (sin(t * 2 * PI * 1.3) * h * 0.26f).toFloat())
        }
        drawIntoCanvas { painter.draw(it.nativeCanvas, pts, settings, 1f, time) }
    }
}
