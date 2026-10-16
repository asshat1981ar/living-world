package org.livingworld.ui.adapters

import org.livingworld.core.events.DuelOutcome
import org.livingworld.core.events.GameEvent
import org.livingworld.core.events.TacticalCombatResolver
import org.livingworld.core.events.TacticalStance
import org.livingworld.core.ids.*
import org.livingworld.core.npc.Npc
import org.livingworld.core.relationships.Relationship
import org.livingworld.core.world.EconomyState
import org.livingworld.core.world.Location
import org.livingworld.core.world.WorldState
import org.livingworld.ui.sprites.*
import org.livingworld.ui.theme.ChimeraSpritePalette
import org.livingworld.ui.theme.ColorValue

data class NpcVisualModel(
    val npcId: NpcId,
    val displayName: String,
    val occupation: String,
    val currentLocation: LocationId,
    val isAlive: Boolean,
    val healthPercent: Float,
    val expression: PortraitExpression,
    val portraitSprite: SpriteRef?,
    val tokenSprite: SpriteRef?,
    val tintColor: ColorValue,
    val needsInkWashOverlay: Boolean,
    val statusBadges: List<String>
)

data class DuelExchangeVisualModel(
    val attackerName: String,
    val defenderName: String,
    val attackerStance: CombatStance,
    val defenderStance: CombatStance,
    val attackerSprite: SpriteRef?,
    val defenderSprite: SpriteRef?,
    val outcomeLabel: String,
    val damageDealt: Float,
    val borderTint: ColorValue
)

data class MapNodeVisualModel(
    val locationId: LocationId,
    val displayName: String,
    val state: MapNodeState,
    val nodeSprite: SpriteRef?,
    val occupantTokens: List<SpriteRef>,
    val occupantCount: Int,
    val isPlayerPresent: Boolean
)

data class EventVisualModel(
    val eventId: EventId,
    val timeFormatted: String,
    val eventType: String,
    val summary: String,
    val primaryActorSprite: SpriteRef?,
    val causalEventId: EventId?,
    val isViolentOrCritical: Boolean,
    val tintColor: ColorValue
)

data class ItemVisualModel(
    val itemId: ItemId,
    val displayName: String,
    val itemSprite: SpriteRef?,
    val rarity: ItemRarity,
    val raritySealSprite: SpriteRef?,
    val currentPrice: Float,
    val scarcityFactor: Float
)

data class VillageDashboardVisualModel(
    val formattedTime: String,
    val totalNpcs: Int,
    val aliveNpcs: Int,
    val deadNpcs: Int,
    val mapNodes: List<MapNodeVisualModel>,
    val recentEvents: List<EventVisualModel>,
    val criticalAlerts: List<String>
)

object TacticalDuelVisualAdapter {
    fun toUiStance(stance: TacticalStance): CombatStance = when (stance) {
        TacticalStance.STRIKE -> CombatStance.STRIKE
        TacticalStance.WARD -> CombatStance.WARD
        TacticalStance.FEINT -> CombatStance.FEINT
    }

    fun createDuelVisual(
        event: GameEvent.NpcAttacked,
        world: WorldState,
        manifest: SpriteManifest
    ): DuelExchangeVisualModel {
        val atkUiStance = toUiStance(event.attackerStance)
        val defUiStance = toUiStance(event.defenderStance)
        val isPlayerAttacker = event.attacker is EntityId.Player

        val attackerNpc = (event.attacker as? EntityId.Npc)?.id?.let { world.npcs[it] }
        val defenderNpc = world.npcs[event.target]

        val atkWounded = attackerNpc != null && attackerNpc.resources.health < TacticalCombatResolver.WOUNDED_HEALTH_THRESHOLD
        val defWounded = defenderNpc != null && defenderNpc.resources.health < TacticalCombatResolver.WOUNDED_HEALTH_THRESHOLD

        val atkSprite = manifest.resolveCombatStance(isPlayer = isPlayerAttacker, stance = atkUiStance, wounded = atkWounded)
        val defSprite = manifest.resolveCombatStance(isPlayer = false, stance = defUiStance, wounded = defWounded)

        val borderTint = when (event.duelOutcome) {
            DuelOutcome.COUNTER_STRIKE, DuelOutcome.GUARD_BREAK -> ChimeraSpritePalette.ACCENT_CRIMSON
            DuelOutcome.PARRIED -> ChimeraSpritePalette.ACCENT_GOLD
            DuelOutcome.STANDARD_EXCHANGE -> ChimeraSpritePalette.TINT_HOSTILE
        }

        return DuelExchangeVisualModel(
            attackerName = attackerNpc?.identity?.name ?: event.attacker.identifier,
            defenderName = defenderNpc?.identity?.name ?: event.target.value,
            attackerStance = atkUiStance,
            defenderStance = defUiStance,
            attackerSprite = atkSprite,
            defenderSprite = defSprite,
            outcomeLabel = event.duelOutcome.description,
            damageDealt = event.damage,
            borderTint = borderTint
        )
    }
}

object NpcVisualAdapter {
    fun createVisualModel(
        npc: Npc,
        manifest: SpriteManifest,
        observerRelationship: Relationship? = null
    ): NpcVisualModel {
        val isAlive = npc.isAlive
        val healthPct = (npc.resources.health / 100.0f).coerceIn(0.0f, 1.0f)
        val badges = mutableListOf<String>()

        val expression = when {
            !isAlive -> {
                badges.add("Deceased")
                PortraitExpression.DECEASED
            }
            healthPct < 0.35f -> {
                badges.add("Wounded")
                PortraitExpression.WOUNDED
            }
            observerRelationship != null -> {
                when {
                    observerRelationship.overallSentiment > 0.65f ||
                    observerRelationship.obligations.any { it.debtor == npc.id.toEntityId() && it.weight >= 1.0f } -> {
                        badges.add("Oathbound")
                        PortraitExpression.OATHBOUND
                    }
                    observerRelationship.overallSentiment > 0.25f -> {
                        badges.add("Friendly")
                        PortraitExpression.GRATEFUL
                    }
                    observerRelationship.resentment > 0.6f || observerRelationship.overallSentiment < -0.5f -> {
                        badges.add("Hostile")
                        PortraitExpression.HOSTILE
                    }
                    observerRelationship.fear > 0.45f || observerRelationship.overallSentiment < -0.15f -> {
                        badges.add("Suspicious")
                        PortraitExpression.TENSE
                    }
                    else -> {
                        badges.add("Neutral")
                        PortraitExpression.NEUTRAL
                    }
                }
            }
            else -> {
                when {
                    npc.emotionalState.anger > 0.6f -> {
                        badges.add("Angry")
                        PortraitExpression.HOSTILE
                    }
                    npc.emotionalState.fear > 0.5f || npc.emotionalState.suspicion > 0.5f -> {
                        badges.add("Wary")
                        PortraitExpression.TENSE
                    }
                    npc.emotionalState.hope > 0.7f && npc.emotionalState.grief < 0.2f -> {
                        badges.add("Receptive")
                        PortraitExpression.GRATEFUL
                    }
                    else -> {
                        badges.add("Neutral")
                        PortraitExpression.NEUTRAL
                    }
                }
            }
        }

        val tint = when (expression) {
            PortraitExpression.DECEASED -> ChimeraSpritePalette.TINT_DECEASED
            PortraitExpression.WOUNDED -> ChimeraSpritePalette.TINT_WOUNDED
            PortraitExpression.HOSTILE -> ChimeraSpritePalette.TINT_HOSTILE
            PortraitExpression.TENSE -> ChimeraSpritePalette.TINT_TENSE
            PortraitExpression.GRATEFUL -> ChimeraSpritePalette.TINT_GRATEFUL
            PortraitExpression.OATHBOUND -> ChimeraSpritePalette.TINT_OATHBOUND
            PortraitExpression.NEUTRAL -> ChimeraSpritePalette.TINT_NEUTRAL
        }

        val needsInkWash = expression == PortraitExpression.WOUNDED || expression == PortraitExpression.HOSTILE

        val portrait = manifest.resolveNpcPortrait(npc.id.value, expression)
        val token = manifest.resolveNpcToken(npc.id.value)

        return NpcVisualModel(
            npcId = npc.id,
            displayName = npc.identity.name,
            occupation = npc.identity.occupation,
            currentLocation = npc.identity.currentLocation,
            isAlive = isAlive,
            healthPercent = healthPct,
            expression = expression,
            portraitSprite = portrait,
            tokenSprite = token,
            tintColor = tint,
            needsInkWashOverlay = needsInkWash,
            statusBadges = badges
        )
    }
}

object MapVisualAdapter {
    fun createNodeModel(
        location: Location,
        worldState: WorldState,
        manifest: SpriteManifest
    ): MapNodeVisualModel {
        val npcsAtLocation = worldState.npcs.values.filter {
            it.isAlive && it.identity.currentLocation == location.id
        }

        val isPlayerPresent = worldState.player.currentLocation == location.id

        val state = when {
            worldState.activeQuests.any { it.target == location.id.toEntityId() && it.status.name == "COMPLETED" } -> MapNodeState.COMPLETED
            worldState.activeQuests.any { it.target == location.id.toEntityId() && it.status.name == "FAILED" } -> MapNodeState.FAILED
            location.controllingFaction != null && worldState.factions[location.controllingFaction]?.enemies?.contains(worldState.player.factionMemberships.firstOrNull()) == true -> MapNodeState.BLOCKED
            else -> MapNodeState.ACTIVE
        }

        val nodeSprite = manifest.resolveMapNode(location.id.value, state)
        val tokens = npcsAtLocation.mapNotNull { manifest.resolveNpcToken(it.id.value) }

        return MapNodeVisualModel(
            locationId = location.id,
            displayName = location.name,
            state = state,
            nodeSprite = nodeSprite,
            occupantTokens = tokens,
            occupantCount = npcsAtLocation.size,
            isPlayerPresent = isPlayerPresent
        )
    }
}

object EventVisualAdapter {
    fun createEventModel(
        event: GameEvent,
        manifest: SpriteManifest
    ): EventVisualModel {
        val isViolent = event is GameEvent.NpcKilled || event is GameEvent.NpcAttacked
        val primaryActor = event.actors.firstOrNull()?.identifier
        val primaryActorSprite = primaryActor?.let { manifest.resolveNpcPortrait(it, PortraitExpression.NEUTRAL) }

        val tint = when (event) {
            is GameEvent.NpcKilled -> ChimeraSpritePalette.TINT_WOUNDED
            is GameEvent.NpcAttacked -> ChimeraSpritePalette.TINT_HOSTILE
            is GameEvent.FactionHostilityDeclared -> ChimeraSpritePalette.ACCENT_CRIMSON
            is GameEvent.ItemStolen -> ChimeraSpritePalette.TINT_TENSE
            is GameEvent.TradeCompleted -> ChimeraSpritePalette.ACCENT_GOLD
            else -> ChimeraSpritePalette.TINT_NEUTRAL
        }

        val day = event.time.day
        val hour = event.time.hour
        val timeFormatted = "Day " + day + ", " + hour.toString().padStart(2, '0') + ":00"

        return EventVisualModel(
            eventId = event.id,
            timeFormatted = timeFormatted,
            eventType = event::class.simpleName ?: "Event",
            summary = event.summary,
            primaryActorSprite = primaryActorSprite,
            causalEventId = event.causalEventId,
            isViolentOrCritical = isViolent,
            tintColor = tint
        )
    }
}

object ItemVisualAdapter {
    fun createItemModel(
        itemId: ItemId,
        economy: EconomyState,
        manifest: SpriteManifest,
        rarity: ItemRarity = ItemRarity.COMMON
    ): ItemVisualModel {
        val price = economy.getPrice(itemId)
        val scarcity = economy.scarcityFactors[itemId.value] ?: 1.0f
        val sprite = manifest.resolveCampItem(itemId.value)
        val seal = manifest.resolveRaritySeal(rarity)
        val displayName = itemId.value.replace("_", " ").split(" ")
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

        return ItemVisualModel(
            itemId = itemId,
            displayName = displayName,
            itemSprite = sprite,
            rarity = rarity,
            raritySealSprite = seal,
            currentPrice = price,
            scarcityFactor = scarcity
        )
    }
}

object VillageDashboardAdapter {
    fun createDashboard(
        worldState: WorldState,
        manifest: SpriteManifest
    ): VillageDashboardVisualModel {
        val total = worldState.npcs.size
        val alive = worldState.npcs.values.count { it.isAlive }
        val dead = total - alive

        val nodeModels = worldState.locations.values.map { loc ->
            MapVisualAdapter.createNodeModel(loc, worldState, manifest)
        }

        val recentEvents = worldState.events.all().takeLast(10).reversed().map { ev ->
            EventVisualAdapter.createEventModel(ev, manifest)
        }

        val alerts = mutableListOf<String>()
        if (dead > 0) {
            alerts.add("$dead villager(s) deceased")
        }
        for ((_, faction) in worldState.factions) {
            if (faction.enemies.isNotEmpty()) {
                alerts.add("Faction '${faction.name}' is actively hostile toward " + faction.enemies.map { it.value }.joinToString(", "))
            }
        }

        return VillageDashboardVisualModel(
            formattedTime = worldState.time.formatted(),
            totalNpcs = total,
            aliveNpcs = alive,
            deadNpcs = dead,
            mapNodes = nodeModels,
            recentEvents = recentEvents,
            criticalAlerts = alerts
        )
    }
}
