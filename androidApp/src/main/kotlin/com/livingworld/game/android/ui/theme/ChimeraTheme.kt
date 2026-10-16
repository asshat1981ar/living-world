package com.livingworld.game.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import org.livingworld.ui.theme.ChimeraSpritePalette
import org.livingworld.ui.theme.ColorValue

fun ColorValue.toComposeColor(): Color = Color(argb)

val DarkParchmentScheme = darkColorScheme(
    primary = ChimeraSpritePalette.ACCENT_GOLD.toComposeColor(),
    onPrimary = ChimeraSpritePalette.INK_BLACK.toComposeColor(),
    secondary = ChimeraSpritePalette.ACCENT_CRIMSON.toComposeColor(),
    onSecondary = Color.White,
    background = ChimeraSpritePalette.PARCHMENT_DARK.toComposeColor(),
    onBackground = Color(0xFFF5F3E8),
    surface = ChimeraSpritePalette.PARCHMENT_MID.toComposeColor(),
    onSurface = Color(0xFFE8E5D8),
    outline = ChimeraSpritePalette.INK_GREY.toComposeColor()
)

@Composable
fun ChimeraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkParchmentScheme,
        content = content
    )
}
