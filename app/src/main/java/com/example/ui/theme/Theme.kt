package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val SaraDarkColorScheme = darkColorScheme(
    primary = CyanPlasma,
    onPrimary = VoidObsidian,
    primaryContainer = DeepIndigoCore,
    onPrimaryContainer = CyanGlowSoft,
    secondary = SapphirePulse,
    onSecondary = TextPrimary,
    secondaryContainer = HudCardElevated,
    onSecondaryContainer = TextPrimary,
    tertiary = AmberThinking,
    onTertiary = VoidObsidian,
    background = VoidObsidian,
    onBackground = TextPrimary,
    surface = HudMidnight,
    onSurface = TextPrimary,
    surfaceVariant = HudCardSurface,
    onSurfaceVariant = TextSecondary,
    outline = HudBorder,
    outlineVariant = HudBorderBright,
    error = CrimsonAlert,
    onError = VoidObsidian
)

val SaraShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun SaraTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = SaraDarkColorScheme,
        typography = SaraTypography,
        shapes = SaraShapes,
        content = content
    )
}
