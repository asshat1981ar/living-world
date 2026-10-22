package org.livingworld.ui.compose

import org.livingworld.core.events.GameEvent
import org.livingworld.core.events.TacticalCombatResolver
import org.livingworld.core.events.TacticalStance
import org.livingworld.core.ids.*
import org.livingworld.core.npc.Npc
import org.livingworld.core.relationships.Relationship
import org.livingworld.core.world.WorldState
import org.livingworld.ui.adapters.*
import org.livingworld.ui.sprites.*
import org.livingworld.ui.theme.ColorValue

/**
 * 2D normalized coordinate for spatial map layouts (0.0 to 1.0).
 */
data class NodePoint(val x: Float, val y: Float)

/**
 * Map node layout element with spatial coordinates and resident NPC tokens.
 */
data class CanvasMapNode(
    val locationId: LocationId,
    val label: String,
    val position: NodePoint,
    val state: MapNodeState,
    val spriteRef: SpriteRef?,
    val residentTokens: List<SpriteRef>,
    val isPlayerPresent: Boolean
)

/**
 * Pathway connection between two map nodes.
 */
data class CanvasPathConnection(
    val sourceId: LocationId,
    val targetId: LocationId,
    val sourcePoint: NodePoint,
    val targetPoint: NodePoint,
    val isBlocked: Boolean
)

/**
 * State container for the overhead village map canvas.
 */
data class VillageMapCanvasState(
    val title: String,
    val nodes: List<CanvasMapNode>,
    val connections: List<CanvasPathConnection>,
    val activeCrisisCount: Int
)

/**
 * State container for NPC portrait card UI.
 */
data class NpcPortraitCardState(
    val npcId: NpcId,
    val name: String,
    val roleTitle: String,
    val expression: PortraitExpression,
    val portraitSprite: SpriteRef?,
    val healthPercent: Float,
    val tint: ColorValue,
    val showInkWashBleed: Boolean,
    val badges: List<String>,
    val isDeceased: Boolean
)

/**
 * State container for causal event ledger card.
 */
data class CausalEventCardState(
    val eventId: EventId,
    val timestamp: String,
    val title: String,
    val summary: String,
    val actorPortrait: SpriteRef?,
    val causalParentId: EventId?,
    val causalDepth: Int,
    val borderTint: ColorValue,
    val isCritical: Boolean
)

/**
 * State container for market trade item cards.
 */
data class MarketItemCardState(
    val itemId: ItemId,
    val name: String,
    val currentPrice: Float,
    val basePrice: Float,
    val priceDeltaPercent: Float,
    val rarity: ItemRarity,
    val itemSprite: SpriteRef?,
    val sealSprite: SpriteRef?,
    val isScarce: Boolean
)

/**
 * State container for the interactive Tactical Duel screen (Sprint 6: compose-combat-screen-006).
 */
data class TacticalDuelCanvasState(
    val opponentId: NpcId,
    val opponentName: String,
    val opponentHealthPercent: Float,
    val opponentIsDeceased: Boolean,
    /** The player's wounds: a duel can be lost. */
    val playerHealthPercent: Float = 1f,
    val playerIsDefeated: Boolean = false,
    val playerStance: CombatStance,
    val opponentStance: CombatStance,
    val playerStanceSprite: SpriteRef?,
    val opponentStanceSprite: SpriteRef?,
    val lastOutcomeSummary: String,
    val roundCount: Int
)

/**
 * Multiplatform UI Factory & Layout Engine bridging WorldState to Compose UI States.
 */
object ChimeraUiLayoutEngine {

    val LOCATION_COORDINATES = mapOf(
        LocationId("village_square") to NodePoint(0.50f, 0.45f),
        LocationId("market") to NodePoint(0.30f, 0.35f),
        LocationId("tavern") to NodePoint(0.70f, 0.35f),
        LocationId("shrine") to NodePoint(0.50f, 0.15f),
        LocationId("ruins") to NodePoint(0.85f, 0.75f),
        LocationId("mine") to NodePoint(0.15f, 0.75f),
        LocationId("gate") to NodePoint(0.50f, 0.85f)
    )

    fun createMapCanvasState(world: WorldState, manifest: SpriteManifest): VillageMapCanvasState {
        val canvasNodes = world.locations.values.map { loc ->
            val pt = LOCATION_COORDINATES[loc.id] ?: NodePoint(0.5f, 0.5f)
            val model = MapVisualAdapter.createNodeModel(loc, world, manifest)
            CanvasMapNode(
                locationId = loc.id,
                label = model.displayName,
                position = pt,
                state = model.state,
                spriteRef = model.nodeSprite,
                residentTokens = model.occupantTokens,
                isPlayerPresent = model.isPlayerPresent
            )
        }

        val connections = mutableListOf<CanvasPathConnection>()
        val seenPairs = mutableSetOf<Pair<LocationId, LocationId>>()

        for (loc in world.locations.values) {
            val srcPt = LOCATION_COORDINATES[loc.id] ?: NodePoint(0.5f, 0.5f)
            for (tgtId in loc.connectedLocations) {
                val pair = if (loc.id.value < tgtId.value) loc.id to tgtId else tgtId to loc.id
                if (!seenPairs.contains(pair)) {
                    seenPairs.add(pair)
                    val tgtPt = LOCATION_COORDINATES[tgtId] ?: NodePoint(0.5f, 0.5f)
                    val isBlocked = loc.controllingFaction != null &&
                            world.factions[loc.controllingFaction]?.enemies?.isNotEmpty() == true
                    connections.add(
                        CanvasPathConnection(
                            sourceId = loc.id,
                            targetId = tgtId,
                            sourcePoint = srcPt,
                            targetPoint = tgtPt,
                            isBlocked = isBlocked
                        )
                    )
                }
            }
        }

        val hostileFactions = world.factions.values.count { it.enemies.isNotEmpty() }

        return VillageMapCanvasState(
            title = "Village Territory Map — ${world.time.formatted()}",
            nodes = canvasNodes,
            connections = connections,
            activeCrisisCount = hostileFactions
        )
    }

    fun createPortraitCardState(
        npc: Npc,
        manifest: SpriteManifest,
        observerRel: Relationship? = null
    ): NpcPortraitCardState {
        val visual = NpcVisualAdapter.createVisualModel(npc, manifest, observerRel)
        return NpcPortraitCardState(
            npcId = npc.id,
            name = visual.displayName,
            roleTitle = visual.occupation,
            expression = visual.expression,
            portraitSprite = visual.portraitSprite,
            healthPercent = visual.healthPercent,
            tint = visual.tintColor,
            showInkWashBleed = visual.needsInkWashOverlay,
            badges = visual.statusBadges,
            isDeceased = !visual.isAlive
        )
    }

    fun createCausalTimeline(world: WorldState, manifest: SpriteManifest, limit: Int = 10): List<CausalEventCardState> {
        val events = world.events.all().takeLast(limit).reversed()
        return events.map { ev ->
            val visual = EventVisualAdapter.createEventModel(ev, manifest)
            val chain = world.events.traceCausalChain(ev.id)
            val depth = (chain.size - 1).coerceAtLeast(0)

            CausalEventCardState(
                eventId = ev.id,
                timestamp = visual.timeFormatted,
                title = visual.eventType,
                summary = visual.summary,
                actorPortrait = visual.primaryActorSprite,
                causalParentId = ev.causalEventId,
                causalDepth = depth,
                borderTint = visual.tintColor,
                isCritical = visual.isViolentOrCritical
            )
        }
    }

    fun createMarketCatalog(
        itemIds: List<ItemId>,
        world: WorldState,
        manifest: SpriteManifest
    ): List<MarketItemCardState> {
        val basePrices = mapOf("bread" to 5.0f, "iron_sword" to 50.0f, "herb_bundle" to 8.0f, "iron_ingot" to 20.0f)
        return itemIds.map { itemId ->
            val base = basePrices[itemId.value] ?: 10.0f
            val current = world.economy.getPrice(itemId, base)
            val delta = if (base > 0f) ((current - base) / base) * 100f else 0f
            val rarity = when {
                itemId.value.contains("omen") -> ItemRarity.LEGENDARY
                itemId.value.contains("sword") -> ItemRarity.RARE
                else -> ItemRarity.COMMON
            }
            val visual = ItemVisualAdapter.createItemModel(itemId, world.economy, manifest, rarity)
            val isScarce = (world.economy.scarcityFactors[itemId.value] ?: 1.0f) > 1.2f

            MarketItemCardState(
                itemId = itemId,
                name = visual.displayName,
                currentPrice = current,
                basePrice = base,
                priceDeltaPercent = delta,
                rarity = rarity,
                itemSprite = visual.itemSprite,
                sealSprite = visual.raritySealSprite,
                isScarce = isScarce
            )
        }
    }

    fun createTacticalDuelState(
        world: WorldState,
        opponentId: NpcId,
        playerStance: TacticalStance,
        opponentStance: TacticalStance,
        manifest: SpriteManifest,
        lastSummary: String = "Select a combat stance (Strike, Ward, Feint) to engage.",
        roundCount: Int = 0
    ): TacticalDuelCanvasState {
        val opponent = world.npcs[opponentId]
        val oppHealthPct = if (opponent != null) (opponent.resources.health / 100f).coerceIn(0f, 1f) else 0f
        val oppWounded = opponent != null && opponent.resources.health < TacticalCombatResolver.WOUNDED_HEALTH_THRESHOLD
        val uiPlayerStance = TacticalDuelVisualAdapter.toUiStance(playerStance)
        val uiOppStance = TacticalDuelVisualAdapter.toUiStance(opponentStance)

        val playerHealthPct = (world.player.health / 100f).coerceIn(0f, 1f)

        return TacticalDuelCanvasState(
            opponentId = opponentId,
            opponentName = opponent?.identity?.name ?: opponentId.value,
            opponentHealthPercent = oppHealthPct,
            opponentIsDeceased = opponent == null || !opponent.isAlive,
            playerHealthPercent = playerHealthPct,
            playerIsDefeated = world.player.health <= 0f,
            playerStance = uiPlayerStance,
            opponentStance = uiOppStance,
            playerStanceSprite = manifest.resolveCombatStance(isPlayer = true, stance = uiPlayerStance, wounded = false),
            opponentStanceSprite = manifest.resolveCombatStance(isPlayer = false, stance = uiOppStance, wounded = oppWounded),
            lastOutcomeSummary = lastSummary,
            roundCount = roundCount
        )
    }
}
