package com.wusper.x86.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val BrandPrimary = Color(0xFF1B6EF3)
private val BrandSecondary = Color(0xFF00C2A8)
private val BrandTertiary = Color(0xFF8B7CFF)

private val DarkColors = darkColorScheme(
    primary = BrandPrimary,
    onPrimary = Color.White,
    secondary = BrandSecondary,
    onSecondary = Color(0xFF003730),
    tertiary = BrandTertiary,
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE8EEF7),
    surface = Color(0xFF1A1F27),
    onSurface = Color(0xFFE8EEF7),
    surfaceVariant = Color(0xFF242B36),
    onSurfaceVariant = Color(0xFF9AA7B8),
    outline = Color(0xFF2A3340),
)

private val LightColors = lightColorScheme(
    primary = BrandPrimary,
    onPrimary = Color.White,
    secondary = BrandSecondary,
    tertiary = BrandTertiary,
    background = Color(0xFFF5F7FB),
    onBackground = Color(0xFF10141A),
    surface = Color.White,
    onSurface = Color(0xFF10141A),
    surfaceVariant = Color(0xFFE8EEF7),
    onSurfaceVariant = Color(0xFF5A6573),
)

@Composable
fun X86Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(),
        content = content,
    )
}
