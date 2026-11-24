package com.phishguard.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// "Inspector" palette
val Paper = Color(0xFFF4F2EC)
val Ink = Color(0xFF1C1B19)
val Muted = Color(0xFF6B675E)
val Danger = Color(0xFFB23A2E)
val Caution = Color(0xFFC98A1E)
val Safe = Color(0xFF2F6F4F)

// IBM Plex Mono isn't bundled; the system monospace is the fallback.
val Mono = FontFamily.Monospace

private val Colors = lightColorScheme(
    primary = Ink, onPrimary = Paper,
    background = Paper, onBackground = Ink,
    surface = Paper, onSurface = Ink,
    error = Danger,
)

private val Square = RoundedCornerShape(2.dp)

@Composable
fun PhishGuardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Colors,
        shapes = Shapes(Square, Square, Square, Square, Square),
        // No colors in text styles: a style color would override LocalContentColor and
        // make button labels ink-on-ink. Text color comes from the color scheme instead.
        typography = Typography(
            bodyLarge = TextStyle(fontSize = 16.sp),
            bodyMedium = TextStyle(fontSize = 14.sp),
            labelSmall = TextStyle(
                fontFamily = Mono, fontSize = 11.sp, letterSpacing = 1.sp,
                fontWeight = FontWeight.Medium,
            ),
        ),
        content = content,
    )
}
