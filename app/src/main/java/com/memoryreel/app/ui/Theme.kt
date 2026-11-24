package com.memoryreel.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Bg = Color(0xFF14101A)
val Card = Color(0xFF221B2B)
val CardHigh = Color(0xFF2E2539)
val Pink = Color(0xFFFF6F91)
val Peach = Color(0xFFFF9E7A)
val Gold = Color(0xFFFFC75F)
val Plum = Color(0xFF5B2A86)
val Ink = Color(0xFFF3EDF7)
val Muted = Color(0xFFB8AEC4)

@Composable
fun MemoryTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Pink,
            onPrimary = Color(0xFF2A0E1B),
            secondary = Gold,
            onSecondary = Color(0xFF2A1E00),
            background = Bg,
            onBackground = Ink,
            surface = Bg,
            onSurface = Ink,
            surfaceVariant = Card,
            onSurfaceVariant = Muted,
            surfaceContainerHigh = CardHigh,
            outline = Color(0xFF5A4E66),
        ),
        content = content,
    )
}
