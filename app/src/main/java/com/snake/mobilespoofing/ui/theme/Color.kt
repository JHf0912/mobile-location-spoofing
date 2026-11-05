package com.snake.mobilespoofing.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic palette. Everything the UI draws should come from here rather than a literal colour, so
 * light/dark mode and the accent family stay consistent.
 */
data class AppColors(
    val background: Color,
    val surface: Color,
    val surfaceMuted: Color,
    /** Selected/elevated control surface; lighter than [surfaceMuted] in both themes. */
    val surfaceElevated: Color,
    val ink: Color,
    val muted: Color,
    val hairline: Color,
    val primary: Color,
    val onPrimary: Color,
    val accentAmber: Color,
    val accentCyan: Color,
    val accentMint: Color,
    val onAccentMint: Color,
    val danger: Color,
    val dangerSoft: Color,
    val amberSoft: Color,
    val cyanSoft: Color,
)

val LightAppColors = AppColors(
    background = Color(0xFFF4F6F7),
    surface = Color(0xFFFFFFFF),
    surfaceMuted = Color(0xFFEDF0F2),
    surfaceElevated = Color(0xFFFFFFFF),
    ink = Color(0xFF171B1E),
    muted = Color(0xFF616A70),
    hairline = Color(0xFFE2E6E8),
    primary = Color(0xFF151B1F),
    onPrimary = Color(0xFFFFFFFF),
    accentAmber = Color(0xFFFFD43B),
    accentCyan = Color(0xFF29C0D2),
    accentMint = Color(0xFFDFF5E9),
    onAccentMint = Color(0xFF14532D),
    danger = Color(0xFFC2413A),
    dangerSoft = Color(0xFFF7E4E2),
    amberSoft = Color(0xFFFFF4CC),
    cyanSoft = Color(0xFFDDF6F9),
)

val DarkAppColors = AppColors(
    background = Color(0xFF101416),
    surface = Color(0xFF171C1F),
    surfaceMuted = Color(0xFF1F2529),
    surfaceElevated = Color(0xFF242C31),
    ink = Color(0xFFF2F5F6),
    muted = Color(0xFF9AA4AA),
    hairline = Color(0xFF2A3135),
    primary = Color(0xFFF2F5F6),
    onPrimary = Color(0xFF101416),
    accentAmber = Color(0xFFFFD43B),
    accentCyan = Color(0xFF29C0D2),
    accentMint = Color(0xFF173A2A),
    onAccentMint = Color(0xFFDFF5E9),
    danger = Color(0xFFE07A72),
    dangerSoft = Color(0xFF3B1F1D),
    amberSoft = Color(0xFF3A3014),
    cyanSoft = Color(0xFF10343A),
)

val LocalAppColors = staticCompositionLocalOf { LightAppColors }
