package com.orion.proxy.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val DirectProxyDarkColorScheme = darkColorScheme(
    primary = PureWhite,
    onPrimary = ObsidianBackground,
    primaryContainer = ObsidianSurfaceElevated,
    onPrimaryContainer = CrispOffWhite,
    secondary = EmeraldActive,
    onSecondary = ObsidianBackground,
    secondaryContainer = EmeraldSurface,
    onSecondaryContainer = EmeraldActive,
    tertiary = AmberRecommended,
    onTertiary = ObsidianBackground,
    tertiaryContainer = AmberSurface,
    onTertiaryContainer = AmberRecommended,
    background = ObsidianBackground,
    onBackground = CrispOffWhite,
    surface = ObsidianSurface,
    onSurface = CrispOffWhite,
    surfaceVariant = ObsidianSurfaceElevated,
    onSurfaceVariant = SlateMuted,
    outline = ObsidianBorder,
    outlineVariant = ObsidianHairline,
    error = CoralError,
    onError = ObsidianBackground,
    errorContainer = CoralSurface,
    onErrorContainer = CoralError
)

private val DirectProxyShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun DirectProxyTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DirectProxyDarkColorScheme,
        typography = DirectProxyTypography,
        shapes = DirectProxyShapes,
        content = content
    )
}
