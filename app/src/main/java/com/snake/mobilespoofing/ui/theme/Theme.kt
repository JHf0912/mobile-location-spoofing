package com.snake.mobilespoofing.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

private val LightScheme = lightColorScheme(
    primary = LightAppColors.primary,
    onPrimary = LightAppColors.onPrimary,
    primaryContainer = LightAppColors.surfaceMuted,
    onPrimaryContainer = LightAppColors.ink,
    secondary = LightAppColors.accentCyan,
    onSecondary = LightAppColors.ink,
    background = LightAppColors.background,
    onBackground = LightAppColors.ink,
    surface = LightAppColors.surface,
    onSurface = LightAppColors.ink,
    surfaceVariant = LightAppColors.surfaceMuted,
    onSurfaceVariant = LightAppColors.muted,
    outline = LightAppColors.hairline,
    outlineVariant = LightAppColors.hairline,
    error = LightAppColors.danger,
    onError = Color.White,
    errorContainer = LightAppColors.dangerSoft,
    onErrorContainer = LightAppColors.danger,
)

private val DarkScheme = darkColorScheme(
    primary = DarkAppColors.primary,
    onPrimary = DarkAppColors.onPrimary,
    primaryContainer = DarkAppColors.surfaceMuted,
    onPrimaryContainer = DarkAppColors.ink,
    secondary = DarkAppColors.accentCyan,
    onSecondary = Color(0xFF101416),
    background = DarkAppColors.background,
    onBackground = DarkAppColors.ink,
    surface = DarkAppColors.surface,
    onSurface = DarkAppColors.ink,
    surfaceVariant = DarkAppColors.surfaceMuted,
    onSurfaceVariant = DarkAppColors.muted,
    outline = DarkAppColors.hairline,
    outlineVariant = DarkAppColors.hairline,
    error = DarkAppColors.danger,
    onError = Color(0xFF101416),
    errorContainer = DarkAppColors.dangerSoft,
    onErrorContainer = DarkAppColors.danger,
)

@Composable
fun MockLocationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val appColors = if (darkTheme) DarkAppColors else LightAppColors
    val scheme = if (darkTheme) DarkScheme else LightScheme

    CompositionLocalProvider(LocalAppColors provides appColors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AppTypography,
            content = content,
        )
    }
}

val MaterialTheme.appColors: AppColors
    @Composable
    @ReadOnlyComposable
    get() = LocalAppColors.current
