package com.yboard.keyboard.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.yboard.keyboard.R

val BrandStart = Color(0xFF6D5BFF)
val BrandEnd = Color(0xFFC04BF2)
val BrandBrush = Brush.linearGradient(listOf(BrandStart, Color(0xFF8A55FF), BrandEnd))

private val LightColors = lightColorScheme(
    primary = Color(0xFF5B4BEB), onPrimary = Color.White,
    primaryContainer = Color(0xFFE7E3FF), onPrimaryContainer = Color(0xFF1D1263),
    secondary = Color(0xFF7A5AF0), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDE6FF), onSecondaryContainer = Color(0xFF2A1868),
    background = Color(0xFFF6F5FB), onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFF6F5FB), onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE9E7F2), onSurfaceVariant = Color(0xFF5D5A6B),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFBFAFE),
    surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFF0EEF7),
    surfaceContainerHighest = Color(0xFFE9E7F2),
    outline = Color(0xFFCAC6D9), outlineVariant = Color(0xFFE4E1EE),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB4A9FF), onPrimary = Color(0xFF22176E),
    primaryContainer = Color(0xFF3A2E9E), onPrimaryContainer = Color(0xFFE7E3FF),
    secondary = Color(0xFFCDB8FF), onSecondary = Color(0xFF2E1A66),
    secondaryContainer = Color(0xFF3B2C73), onSecondaryContainer = Color(0xFFEDE6FF),
    background = Color(0xFF0F0F14), onBackground = Color(0xFFE6E4EE),
    surface = Color(0xFF0F0F14), onSurface = Color(0xFFE6E4EE),
    surfaceVariant = Color(0xFF26252F), onSurfaceVariant = Color(0xFFC6C3D3),
    surfaceContainerLowest = Color(0xFF0B0B10), surfaceContainerLow = Color(0xFF15151C),
    surfaceContainer = Color(0xFF1A1A22), surfaceContainerHigh = Color(0xFF22212B),
    surfaceContainerHighest = Color(0xFF2B2A35),
    outline = Color(0xFF4A4858), outlineVariant = Color(0xFF302F3A),
)

/** Google Sans (bundled, SIL Open Font License) for the whole app. */
val GoogleSans = FontFamily(
    Font(R.font.google_sans_regular, FontWeight.Normal),
    Font(R.font.google_sans_medium, FontWeight.Medium),
    Font(R.font.google_sans_bold, FontWeight.Bold),
)

private fun TextStyle.sans(weight: FontWeight? = null) =
    copy(fontFamily = GoogleSans, fontWeight = weight ?: fontWeight)

@Composable
fun YboardTheme(content: @Composable () -> Unit) {
    val b = Typography()
    val typography = Typography(
        displayLarge = b.displayLarge.sans(), displayMedium = b.displayMedium.sans(), displaySmall = b.displaySmall.sans(),
        headlineLarge = b.headlineLarge.sans(), headlineMedium = b.headlineMedium.sans(FontWeight.Medium),
        headlineSmall = b.headlineSmall.sans(FontWeight.Medium),
        titleLarge = b.titleLarge.sans(FontWeight.Medium), titleMedium = b.titleMedium.sans(FontWeight.Medium),
        titleSmall = b.titleSmall.sans(FontWeight.Medium),
        bodyLarge = b.bodyLarge.sans(), bodyMedium = b.bodyMedium.sans(), bodySmall = b.bodySmall.sans(),
        labelLarge = b.labelLarge.sans(FontWeight.Medium), labelMedium = b.labelMedium.sans(FontWeight.Medium),
        labelSmall = b.labelSmall.sans(FontWeight.Medium),
    )
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = typography,
        content = content,
    )
}
