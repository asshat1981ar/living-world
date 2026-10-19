package org.livingworld.ui.sprites

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SpriteManifestEntry(
    val id: String,
    val category: String,
    val baseName: String,
    val npcId: String? = null,
    val expression: String? = null,
    val stance: String? = null,
    val nodeType: String? = null,
    val state: String? = null,
    val itemId: String? = null,
    val transparent: Boolean = true,
    val variants: List<SpriteVariantEntry> = emptyList()
) {
    fun toSpriteRef(): SpriteRef {
        val cat = try {
            SpriteCategory.valueOf(category)
        } catch (_: Exception) {
            SpriteCategory.SHARED_UI
        }
        val vars = variants.map { v ->
            val dens = try {
                DensityQualifier.valueOf(v.density.uppercase())
            } catch (_: Exception) {
                DensityQualifier.XHDPI
            }
            SpriteVariant(suffix = v.suffix, resolution = v.resolution, density = dens)
        }
        return SpriteRef(
            id = SpriteId(id),
            category = cat,
            baseName = baseName,
            hasTransparency = transparent,
            variants = vars
        )
    }
}

@Serializable
data class SpriteVariantEntry(
    val suffix: String = "",
    val resolution: Int = 128,
    val density: String = "XHDPI"
)

@Serializable
data class SpriteManifestDocument(
    val version: String = "1.0.0",
    val style: String = "gothic_manuscript_inkwash",
    val generated: String = "",
    val total_assets: Int = 0,
    val categories: List<SpriteCategorySection> = emptyList()
)

@Serializable
data class SpriteCategorySection(
    val category: String,
    val description: String = "",
    val entries: List<SpriteManifestEntry> = emptyList()
)

/**
 * Manifest repository providing O(1) resolution and contextual fallbacks for all sprite assets.
 * Pure Kotlin Multiplatform implementation (Invariant G9).
 */
class SpriteManifest(
    initialEntries: Map<SpriteId, SpriteRef> = emptyMap()
) {
    private val spriteMap: MutableMap<SpriteId, SpriteRef> = initialEntries.toMutableMap()

    fun count(): Int = spriteMap.size

    fun register(ref: SpriteRef) {
        spriteMap[ref.id] = ref
    }

    fun resolve(id: SpriteId): SpriteRef? = spriteMap[id]

    /**
     * Resolves an NPC portrait with emotional expression.
     * Falls back to NEUTRAL if the requested expression variant is not in the manifest.
     */
    fun resolveNpcPortrait(npcId: String, expression: PortraitExpression): SpriteRef? {
        val specificKey = SpriteId("npc_" + npcId.lowercase() + "_" + expression.name.lowercase())
        val specific = resolve(specificKey)
        if (specific != null) return specific

        val neutralKey = SpriteId("npc_" + npcId.lowercase() + "_neutral")
        val neutral = resolve(neutralKey)
        if (neutral != null) return neutral

        return resolve(SpriteId("npc_generic_neutral"))
    }

    /**
     * Resolves an overhead map token for an NPC.
     */
    fun resolveNpcToken(npcId: String): SpriteRef? {
        val key = SpriteId("npc_" + npcId.lowercase() + "_token")
        return resolve(key) ?: resolve(SpriteId("npc_generic_token"))
    }

    /**
     * Resolves a map node sprite for a specific location type and quest/event state.
     */
    fun resolveMapNode(nodeType: String, state: MapNodeState): SpriteRef? {
        val specificKey = SpriteId("map_" + nodeType.lowercase() + "_" + state.name.lowercase())
        val specific = resolve(specificKey)
        if (specific != null) return specific

        val activeKey = SpriteId("map_" + nodeType.lowercase() + "_active")
        val active = resolve(activeKey)
        if (active != null) return active

        return resolve(SpriteId("map_ruins_active"))
    }

    /**
     * Resolves combat stance sprites.
     */
    fun resolveCombatStance(isPlayer: Boolean, stance: CombatStance, wounded: Boolean = false): SpriteRef? {
        val prefix = if (isPlayer) "combat_player" else "combat_opponent"
        val woundedSuffix = if (wounded) "_wounded" else ""
        val key = SpriteId(prefix + "_" + stance.name.lowercase() + woundedSuffix)
        val resolved = resolve(key)
        if (resolved != null) return resolved

        if (wounded) {
            val unwoundedKey = SpriteId(prefix + "_" + stance.name.lowercase())
            val unwounded = resolve(unwoundedKey)
            if (unwounded != null) return unwounded
        }

        return resolve(SpriteId(prefix + "_strike"))
    }

    /**
     * Resolves camp / inventory item icon.
     */
    fun resolveCampItem(itemId: String): SpriteRef? {
        val sanitized = itemId.lowercase().replace(" ", "_")
        val key = SpriteId("camp_item_" + sanitized)
        return resolve(key) ?: resolve(SpriteId("camp_item_generic"))
    }

    /**
     * Resolves UI chrome element (e.g. rarity seal, frame).
     */
    fun resolveRaritySeal(rarity: ItemRarity): SpriteRef? {
        val key = SpriteId("ui_seal_" + rarity.name.lowercase())
        return resolve(key)
    }

    fun resolveFrame(variant: String = "gold"): SpriteRef? {
        return resolve(SpriteId("ui_frame_" + variant))
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        fun fromJsonString(jsonContent: String): SpriteManifest {
            val doc = json.decodeFromString<SpriteManifestDocument>(jsonContent)
            val map = mutableMapOf<SpriteId, SpriteRef>()
            for (category in doc.categories) {
                for (entry in category.entries) {
                    val ref = entry.copy(
                        category = if (entry.category.isBlank()) category.category else entry.category
                    ).toSpriteRef()
                    map[ref.id] = ref
                }
            }
            return SpriteManifest(map)
        }

        /**
         * Creates a standard manifest pre-populated with baseline Chimera / Living World assets
         * including generic fallbacks so resolveNpcPortrait/resolveNpcToken/resolveCampItem never return null on unknown IDs.
         */
        fun createDefault(): SpriteManifest {
            val manifest = SpriteManifest()

            // Generic fallbacks
            manifest.register(
                SpriteRef(
                    id = SpriteId("npc_generic_neutral"),
                    category = SpriteCategory.NPC_PORTRAIT,
                    baseName = "npc_generic_neutral",
                    hasTransparency = true,
                    variants = listOf(SpriteVariant(resolution = 128, density = DensityQualifier.XHDPI))
                )
            )
            manifest.register(
                SpriteRef(
                    id = SpriteId("npc_generic_token"),
                    category = SpriteCategory.NPC_TOKEN,
                    baseName = "npc_generic_token",
                    hasTransparency = true,
                    variants = listOf(SpriteVariant(resolution = 64, density = DensityQualifier.XHDPI))
                )
            )
            manifest.register(
                SpriteRef(
                    id = SpriteId("camp_item_generic"),
                    category = SpriteCategory.CAMP_ITEM,
                    baseName = "camp_item_generic",
                    hasTransparency = true,
                    variants = listOf(SpriteVariant(resolution = 128, density = DensityQualifier.XHDPI))
                )
            )

            // NPC Portraits
            val npcs = listOf(
                "elara", "thorne", "merchant", "apprentice", "guard_captain",
                "miner_derek", "elder_rowan", "guildmaster_titus", "blacksmith_krag",
                "innkeeper_mara", "herbalist_lyra", "hunter_bram"
            )
            for (npc in npcs) {
                for (expr in PortraitExpression.entries) {
                    manifest.register(
                        SpriteRef(
                            id = SpriteId("npc_" + npc + "_" + expr.name.lowercase()),
                            category = SpriteCategory.NPC_PORTRAIT,
                            baseName = "npc_" + npc + "_" + expr.name.lowercase(),
                            hasTransparency = true,
                            variants = listOf(SpriteVariant(resolution = 128, density = DensityQualifier.XHDPI))
                        )
                    )
                }
                manifest.register(
                    SpriteRef(
                        id = SpriteId("npc_" + npc + "_token"),
                        category = SpriteCategory.NPC_TOKEN,
                        baseName = "npc_" + npc + "_token",
                        hasTransparency = true,
                        variants = listOf(SpriteVariant(resolution = 64, density = DensityQualifier.XHDPI))
                    )
                )
            }

            // Map Nodes
            val nodeTypes = listOf("village_square", "ruins", "shrine", "market", "tavern", "mine", "gate")
            for (node in nodeTypes) {
                for (state in MapNodeState.entries) {
                    manifest.register(
                        SpriteRef(
                            id = SpriteId("map_" + node + "_" + state.name.lowercase()),
                            category = SpriteCategory.MAP_NODE,
                            baseName = "map_" + node + "_" + state.name.lowercase(),
                            hasTransparency = true,
                            variants = listOf(SpriteVariant(resolution = 96, density = DensityQualifier.XHDPI))
                        )
                    )
                }
            }

            // Combat Stances
            for (isPlayer in listOf(true, false)) {
                val prefix = if (isPlayer) "combat_player" else "combat_opponent"
                for (stance in CombatStance.entries) {
                    manifest.register(
                        SpriteRef(
                            id = SpriteId(prefix + "_" + stance.name.lowercase()),
                            category = if (isPlayer) SpriteCategory.COMBAT_PLAYER else SpriteCategory.COMBAT_OPPONENT,
                            baseName = prefix + "_" + stance.name.lowercase(),
                            hasTransparency = true,
                            variants = listOf(SpriteVariant(resolution = 180, density = DensityQualifier.XHDPI))
                        )
                    )
                    manifest.register(
                        SpriteRef(
                            id = SpriteId(prefix + "_" + stance.name.lowercase() + "_wounded"),
                            category = if (isPlayer) SpriteCategory.COMBAT_PLAYER else SpriteCategory.COMBAT_OPPONENT,
                            baseName = prefix + "_" + stance.name.lowercase() + "_wounded",
                            hasTransparency = true,
                            variants = listOf(SpriteVariant(resolution = 180, density = DensityQualifier.XHDPI))
                        )
                    )
                }
            }

            // Camp Items
            val items = listOf("iron_sword", "bread", "herb_bundle", "iron_ingot", "omen_stone", "healing_salve", "goods")
            for (item in items) {
                manifest.register(
                    SpriteRef(
                        id = SpriteId("camp_item_" + item),
                        category = SpriteCategory.CAMP_ITEM,
                        baseName = "camp_item_" + item,
                        hasTransparency = true,
                        variants = listOf(SpriteVariant(resolution = 128, density = DensityQualifier.XHDPI))
                    )
                )
            }

            // Shared UI
            for (rarity in ItemRarity.entries) {
                manifest.register(
                    SpriteRef(
                        id = SpriteId("ui_seal_" + rarity.name.lowercase()),
                        category = SpriteCategory.SHARED_UI,
                        baseName = "ui_seal_" + rarity.name.lowercase(),
                        hasTransparency = true,
                        variants = listOf(SpriteVariant(resolution = 32, density = DensityQualifier.XHDPI))
                    )
                )
            }

            manifest.register(
                SpriteRef(
                    id = SpriteId("ui_frame_gold"),
                    category = SpriteCategory.SHARED_UI,
                    baseName = "ui_frame_gold",
                    hasTransparency = true,
                    variants = listOf(SpriteVariant(resolution = 512, density = DensityQualifier.XHDPI))
                )
            )

            return manifest
        }
    }
}
