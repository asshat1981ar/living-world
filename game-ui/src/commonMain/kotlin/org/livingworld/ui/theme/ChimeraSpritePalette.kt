package org.livingworld.ui.theme

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/**
 * Multiplatform color value representation avoiding Android platform leaks in commonMain (Invariant G9).
 */
@JvmInline
@Serializable
value class ColorValue(val argb: Long) {
    val alpha: Float get() = ((argb shr 24) and 0xFF) / 255.0f
    val red: Float get() = ((argb shr 16) and 0xFF) / 255.0f
    val green: Float get() = ((argb shr 8) and 0xFF) / 255.0f
    val blue: Float get() = (argb and 0xFF) / 255.0f

    val hex: String get() = "#" + argb.toString(16).padStart(8, '0').uppercase()

    companion object {
        val Unspecified = ColorValue(0L)
    }
}

/**
 * Gothic manuscript / sumi-e ink-wash style palette for Chimera / Living World.
 * Grounded in SPRITE-DEVELOPMENT-PLAN style specification.
 */
object ChimeraSpritePalette {
    // Primary parchment backgrounds
    val PARCHMENT_DARK = ColorValue(0xFF1A1410L)  // Deep aged parchment
    val PARCHMENT_MID = ColorValue(0xFF2A2018L)   // Medium parchment
    val PARCHMENT_LIGHT = ColorValue(0xFF3A3028L) // Light parchment

    // Ink tones
    val INK_BLACK = ColorValue(0xFF0A0806L)       // Pure sumi ink
    val INK_CHARCOAL = ColorValue(0xFF1E1A16L)    // Charcoal wash
    val INK_GREY = ColorValue(0xFF4A4540L)        // Diluted ink

    // Accent colors (restrained use per gothic manuscript rules)
    val ACCENT_CRIMSON = ColorValue(0xFF8B2500L)  // Dried blood / seal wax
    val ACCENT_GOLD = ColorValue(0xFFB8941FL)     // Faded gold leaf
    val ACCENT_ASH = ColorValue(0xFF6B6B6BL)      // Ash grey
    val ACCENT_HOLLOW = ColorValue(0xFF2D1B69L)   // Faint purple (Hollow magic)

    // Expression-specific visual tints
    val TINT_NEUTRAL = ColorValue(0xFF000000L)    // No tint
    val TINT_TENSE = ColorValue(0xFF3A2010L)      // Warm tension
    val TINT_WOUNDED = ColorValue(0xFF4A1010L)    // Blood wash
    val TINT_GRATEFUL = ColorValue(0xFF1A3020L)   // Cool relief
    val TINT_HOSTILE = ColorValue(0xFF4A0A0AL)    // Deep crimson
    val TINT_OATHBOUND = ColorValue(0xFF1A1A3AL)  // Cool blue binding
    val TINT_DECEASED = ColorValue(0xFF2A2A2AL)   // Somber cold ash
}
