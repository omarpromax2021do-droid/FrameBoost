package com.framewise.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object FramewiseColors {
    val ObsidianBg = Color(0xFF0A0E14)
    val SurfaceDark = Color(0xFF121822)
    val SurfaceElevated = Color(0xFF1A2230)
    val BorderSubtle = Color(0xFF283448)
    val NeonCyan = Color(0xFF00E5FF)
    val ElectricEmerald = Color(0xFF00E676)
    val AmberWarning = Color(0xFFFFB300)
    val CoralError = Color(0xFFFF5252)
    val TextPrimary = Color(0xFFEAF2F8)
    val TextSecondary = Color(0xFF9FB0C4)
}

private val FramewiseDarkColorScheme = darkColorScheme(
    primary = FramewiseColors.NeonCyan,
    onPrimary = Color(0xFF002026),
    primaryContainer = Color(0xFF003844),
    onPrimaryContainer = FramewiseColors.NeonCyan,
    secondary = FramewiseColors.ElectricEmerald,
    onSecondary = Color(0xFF002914),
    secondaryContainer = Color(0xFF0B3B24),
    onSecondaryContainer = FramewiseColors.ElectricEmerald,
    tertiary = FramewiseColors.AmberWarning,
    onTertiary = Color(0xFF2B1D00),
    background = FramewiseColors.ObsidianBg,
    onBackground = FramewiseColors.TextPrimary,
    surface = FramewiseColors.SurfaceDark,
    onSurface = FramewiseColors.TextPrimary,
    surfaceVariant = FramewiseColors.SurfaceElevated,
    onSurfaceVariant = FramewiseColors.TextSecondary,
    outline = FramewiseColors.BorderSubtle,
    error = FramewiseColors.CoralError,
    onError = Color(0xFF2B0000)
)

private val FramewiseTypography = Typography(
    headlineSmall = TextStyle(
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.2.sp
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 17.sp
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold
    )
)

@Composable
fun FramewiseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FramewiseDarkColorScheme,
        typography = FramewiseTypography,
        content = content
    )
}
