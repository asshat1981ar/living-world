package org.livingworld.ui.sprites

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/**
 * Type-safe unique identifier for sprite assets.
 */
@JvmInline
@Serializable
value class SpriteId(val value: String) {
    override fun toString(): String = value
}

/**
 * Functional sprite categories mapping to directory layout and manifest entries.
 */
@Serializable
enum class SpriteCategory {
    NPC_PORTRAIT,
    NPC_TOKEN,
    COMBAT_PLAYER,
    COMBAT_OPPONENT,
    MAP_NODE,
    MAP_CONNECTION,
    CAMP_ITEM,
    CAMP_AMBIENT,
    CHARACTER_CREATION,
    PLAYER_CARD,
    SHARED_UI,
    SHARED_EFFECT
}

/**
 * Expression states for NPC portraits.
 * Directly driven by NPC emotional telemetry, physical injury, and social relationships.
 */
@Serializable
enum class PortraitExpression {
    NEUTRAL,
    TENSE,
    WOUNDED,
    GRATEFUL,
    HOSTILE,
    OATHBOUND,
    DECEASED;

    companion object {
        fun fromDisposition(disposition: Float): PortraitExpression = when {
            disposition > 0.7f -> OATHBOUND
            disposition > 0.3f -> GRATEFUL
            disposition > -0.2f -> NEUTRAL
            disposition > -0.5f -> TENSE
            disposition > -0.8f -> WOUNDED
            else -> HOSTILE
        }
    }
}

/**
 * Quest and interaction states for world map nodes.
 */
@Serializable
enum class MapNodeState {
    ACTIVE,
    HIDDEN,
    COMPLETED,
    FAILED,
    BLOCKED
}

/**
 * Rarity classifications for inventory and trade goods.
 */
@Serializable
enum class ItemRarity {
    COMMON,
    RARE,
    LEGENDARY
}

/**
 * Combat stance representations for player and opponent entities.
 */
@Serializable
enum class CombatStance(val label: String) {
    STRIKE("Strike"),
    WARD("Ward"),
    FEINT("Feint")
}

/**
 * Screen density qualifiers for multi-resolution assets.
 */
@Serializable
enum class DensityQualifier {
    MDPI,
    HDPI,
    XHDPI,
    XXHDPI,
    XXXHDPI
}

/**
 * Resolution variant description.
 */
@Serializable
data class SpriteVariant(
    val suffix: String = "",
    val resolution: Int = 128,
    val density: DensityQualifier = DensityQualifier.XHDPI
)

/**
 * Complete immutable sprite reference with resolution variants and transparency metadata.
 */
@Serializable
data class SpriteRef(
    val id: SpriteId,
    val category: SpriteCategory,
    val baseName: String,
    val hasTransparency: Boolean = true,
    val variants: List<SpriteVariant> = emptyList()
) {
    val primaryResolution: Int
        get() = variants.firstOrNull()?.resolution ?: 128
}
