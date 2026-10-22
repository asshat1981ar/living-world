package org.livingworld.core.world

import kotlinx.serialization.Serializable
import org.livingworld.core.events.EventLedger
import org.livingworld.core.events.GameEvent
import org.livingworld.core.events.isPublic
import org.livingworld.core.events.location
import org.livingworld.core.factions.Faction
import org.livingworld.core.factions.Ideology
import org.livingworld.core.factions.LeadershipState
import org.livingworld.core.ids.*
import org.livingworld.core.npc.*
import org.livingworld.core.relationships.RelationshipGraph
import org.livingworld.core.time.WorldTime

@Serializable
data class Location(
    val id: LocationId,
    val name: String,
    val description: String = "",
    val connectedLocations: List<LocationId> = emptyList(),
    val controllingFaction: FactionId? = null
)

@Serializable
data class EconomyState(
    val itemPrices: Map<String, Float> = emptyMap(),
    val scarcityFactors: Map<String, Float> = emptyMap(),
    val totalTradeVolume: Float = 0f,
    /** The village purse: wages are paid from it, upkeep and ration tax go into it. Coins are never minted. */
    val treasury: Float = STARTING_TREASURY
) {
    companion object {
        const val STARTING_TREASURY = 200f
    }

    fun getPrice(item: ItemId, basePrice: Float = 10f): Float {
        val currentPrice = itemPrices[item.value] ?: basePrice
        val scarcity = scarcityFactors[item.value] ?: 1.0f
        return (currentPrice * scarcity).coerceAtLeast(1.0f)
    }

    fun withPrice(item: ItemId, price: Float): EconomyState =
        copy(itemPrices = itemPrices + (item.value to price))
}

@Serializable
enum class QuestStatus {
    AVAILABLE,
    ACTIVE,
    COMPLETED,
    FAILED
}

@Serializable
data class QuestOpportunity(
    val id: String,
    val title: String,
    val description: String,
    val issuer: EntityId,
    val target: EntityId? = null,
    val reward: Float = 50f,
    val status: QuestStatus = QuestStatus.AVAILABLE
)

/**
 * Emergent systemic world problem detected from authoritative WorldState (Milestone quest-procedural-005).
 */
@Serializable
data class WorldProblem(
    val id: String,
    val category: String,
    val severity: Float,
    val location: LocationId,
    val summary: String
)

/**
 * Deterministic procedural quest & crisis derivation engine (Milestone quest-procedural-005).
 */
object ProceduralQuestEngine {

    fun deriveActiveProblems(state: WorldState): List<WorldProblem> {
        val problems = mutableListOf<WorldProblem>()

        // 1. Unsolved or unavenged villager deaths
        val deaths = state.events.deaths()
        for (death in deaths) {
            problems.add(
                WorldProblem(
                    id = "problem_death_${death.victim.value}",
                    category = "HOMICIDE",
                    severity = 0.90f,
                    location = death.location ?: LocationId("village_square"),
                    summary = "Homicide of ${death.victim.value} at ${(death.location ?: LocationId("village_square")).value}"
                )
            )
        }

        // 2. Active inter-faction warfare
        for ((factionId, faction) in state.factions.entries.sortedBy { it.key.value }) {
            for (enemyId in faction.enemies.sortedBy { it.value }) {
                if (factionId.value < enemyId.value) {
                    problems.add(
                        WorldProblem(
                            id = "problem_war_${factionId.value}_${enemyId.value}",
                            category = "FACTION_WAR",
                            severity = 0.85f,
                            location = LocationId("village_square"),
                            summary = "Open hostility between ${faction.name} and ${enemyId.value}"
                        )
                    )
                }
            }
        }

        // 3. Commodity scarcity spikes
        for ((itemKey, scarcity) in state.economy.scarcityFactors.entries.sortedBy { it.key }) {
            if (scarcity > 1.25f) {
                problems.add(
                    WorldProblem(
                        id = "problem_scarcity_$itemKey",
                        category = "SCARCITY",
                        severity = ((scarcity - 1.0f) / 1.5f).coerceIn(0.3f, 0.95f),
                        location = LocationId("market"),
                        summary = "Severe scarcity of $itemKey (multiplier: $scarcity)"
                    )
                )
            }
        }

        return problems.sortedByDescending { it.severity }
    }

    fun generateOpportunities(state: WorldState): List<QuestOpportunity> {
        val existingIds = state.activeQuests.map { it.id }.toSet()
        val generated = mutableListOf<QuestOpportunity>()

        for (problem in deriveActiveProblems(state)) {
            val questId = "quest_${problem.id.removePrefix("problem_")}"
            if (existingIds.contains(questId)) continue

            when (problem.category) {
                "HOMICIDE" -> {
                    val victimId = problem.id.removePrefix("problem_death_")
                    generated.add(
                        QuestOpportunity(
                            id = questId,
                            title = "Investigate the Murder of $victimId",
                            description = "${problem.summary}. Bring justice or uncover the killer's motive.",
                            issuer = NpcId("guard_captain").toEntityId(),
                            target = problem.location.toEntityId(),
                            reward = 75f,
                            status = QuestStatus.AVAILABLE
                        )
                    )
                }
                "FACTION_WAR" -> {
                    generated.add(
                        QuestOpportunity(
                            id = questId,
                            title = "Broker Ceasefire in Oakhaven",
                            description = "${problem.summary}. Restore order at the village square.",
                            issuer = NpcId("elder_rowan").toEntityId(),
                            target = problem.location.toEntityId(),
                            reward = 100f,
                            status = QuestStatus.AVAILABLE
                        )
                    )
                }
                "SCARCITY" -> {
                    val itemKey = problem.id.removePrefix("problem_scarcity_")
                    generated.add(
                        QuestOpportunity(
                            id = questId,
                            title = "Relieve $itemKey Shortage at Market",
                            description = "${problem.summary}. Deliver trade supplies to stabilize prices.",
                            issuer = NpcId("apprentice").toEntityId(),
                            target = LocationId("market").toEntityId(),
                            reward = 45f,
                            status = QuestStatus.AVAILABLE
                        )
                    )
                }
            }
        }

        return generated
    }
}

@Serializable
data class Skill(val name: String, val level: Int)

@Serializable
data class ValueScore(val name: String, val score: Float)

@Serializable
data class ReputationProfile(
    val villageReputation: String = "Neutral",
    val merchantReputation: String = "Neutral",
    val banditReputation: String = "Neutral"
)

@Serializable
data class PlayerState(
    val id: EntityId.Player = EntityId.Player("player"),
    val currentLocation: LocationId,
    val coins: Float = 100f,
    val inventory: List<ItemId> = emptyList(),
    val skills: List<Skill> = emptyList(),
    val values: List<ValueScore> = emptyList(),
    val reputation: ReputationProfile = ReputationProfile(),
    val factionMemberships: Set<FactionId> = emptySet(),
    val historicalActions: List<EventId> = emptyList()
)

@Serializable
data class WorldSnapshot(
    val time: WorldTime,
    val location: Location,
    val presentNpcs: List<NpcId>,
    val controllingFaction: FactionId?,
    val recentEvents: List<GameEvent>
)

@Serializable
data class WorldState(
    val seed: Long = 42L,
    val time: WorldTime = WorldTime.ZERO,
    val npcs: Map<NpcId, Npc> = emptyMap(),
    val factions: Map<FactionId, Faction> = emptyMap(),
    val locations: Map<LocationId, Location> = emptyMap(),
    val relationships: RelationshipGraph = RelationshipGraph(),
    val economy: EconomyState = EconomyState(),
    val events: EventLedger = EventLedger(),
    val activeQuests: List<QuestOpportunity> = emptyList(),
    val player: PlayerState
) {
    fun npc(id: NpcId): Npc =
        npcs[id] ?: throw IllegalArgumentException("Unknown NPC: ${id.value}")

    fun isDead(id: NpcId): Boolean {
        val character = npcs[id] ?: return true
        return !character.isAlive
    }

    fun activeProblems(): List<WorldProblem> =
        ProceduralQuestEngine.deriveActiveProblems(this)

    fun generateOpportunities(): List<QuestOpportunity> =
        ProceduralQuestEngine.generateOpportunities(this)

    fun snapshotFor(locationId: LocationId): WorldSnapshot {
        val loc = locations[locationId] ?: Location(locationId, "Unknown")
        val present = npcs.values
            .filter { it.isAlive && it.identity.currentLocation == locationId }
            .sortedBy { it.id.value }
            .map { it.id }
        val recent = events.all().takeLast(10)
        return WorldSnapshot(
            time = time,
            location = loc,
            presentNpcs = present,
            controllingFaction = loc.controllingFaction,
            recentEvents = recent
        )
    }

    /**
     * The events this NPC could know about: the ones it witnessed or took part in, and the public ones that happened
     * where it stands. The world's last events are not its knowledge (G2), so the AI advisor gets this and not the
     * global history.
     */
    fun knownEvents(npc: Npc): List<GameEvent> {
        val here = npc.identity.currentLocation
        val self = npc.id.toEntityId()
        return events.all().filter { event ->
            npc.id in event.witnesses ||
                self in event.actors ||
                (event.isPublic() && event.location() == here)
        }
    }

    /** The snapshot an advisor may see for this NPC: what it knows, not what the world knows. */
    fun snapshotFor(npc: Npc): WorldSnapshot {
        val loc = locations[npc.identity.currentLocation]
            ?: Location(npc.identity.currentLocation, "Unknown")
        val present = npcs.values
            .filter { it.isAlive && it.identity.currentLocation == npc.identity.currentLocation }
            .sortedBy { it.id.value }
            .map { it.id }
        return WorldSnapshot(
            time = time,
            location = loc,
            presentNpcs = present,
            controllingFaction = loc.controllingFaction,
            recentEvents = knownEvents(npc).takeLast(10)
        )
    }
}

object DefaultVillageFactory {
    fun marketVillage(): WorldState {
        val squareId = LocationId("village_square")
        val marketId = LocationId("market")
        val tavernId = LocationId("tavern")
        val mineId = LocationId("mine")
        val shrineId = LocationId("shrine")

        val ironGuildId = FactionId("iron_guild")
        val verdantOrderId = FactionId("verdant_order")

        val locations = mapOf(
            squareId to Location(
                id = squareId,
                name = "Oakhaven Village Square",
                description = "The bustling cobblestone hub of Oakhaven.",
                connectedLocations = listOf(marketId, tavernId, shrineId)
            ),
            marketId to Location(
                id = marketId,
                name = "Market & Sundries",
                description = "Village general trading post and smithy.",
                connectedLocations = listOf(squareId, mineId),
                controllingFaction = ironGuildId
            ),
            tavernId to Location(
                id = tavernId,
                name = "Hearthside Tavern",
                description = "Warm hearth where villagers gather.",
                connectedLocations = listOf(squareId)
            ),
            mineId to Location(
                id = mineId,
                name = "Deepdelve Iron Mine",
                description = "Echoing shafts rich with iron ore.",
                connectedLocations = listOf(marketId),
                controllingFaction = ironGuildId
            ),
            shrineId to Location(
                id = shrineId,
                name = "Shrine of Rowan",
                description = "Moss-draped stone sanctuary.",
                connectedLocations = listOf(squareId),
                controllingFaction = verdantOrderId
            )
        )

        val npcDefinitions = listOf(
            Triple("merchant", "Alden the Merchant", "Merchant"),
            Triple("apprentice", "Finn the Apprentice", "Shop Apprentice"),
            Triple("guard_captain", "Captain Vane", "Guard Captain"),
            Triple("guildmaster_titus", "Titus Ironforge", "Guildmaster"),
            Triple("elder_rowan", "Elder Rowan", "Village Elder"),
            Triple("blacksmith_krag", "Krag the Blacksmith", "Blacksmith"),
            Triple("innkeeper_mara", "Mara of the Hearth", "Innkeeper"),
            Triple("herbalist_lyra", "Lyra Greenleaf", "Herbalist"),
            Triple("hunter_bram", "Bram the Scout", "Hunter"),
            Triple("farmer_giles", "Giles Oatfield", "Farmer"),
            Triple("farmer_elena", "Elena Oatfield", "Farmer"),
            Triple("carpenter_jorin", "Jorin Woodshaper", "Carpenter"),
            Triple("miner_derek", "Derek Deepdelve", "Miner"),
            Triple("weaver_sela", "Sela Threadspinner", "Weaver"),
            Triple("priest_orion", "Father Orion", "Priest"),
            Triple("watchman_cole", "Cole Nightwatch", "Watchman"),
            Triple("baker_milo", "Milo the Baker", "Baker"),
            Triple("brewer_oscar", "Oscar Barleybrew", "Brewer"),
            Triple("scholar_erik", "Erik Chronicler", "Scholar"),
            Triple("tanner_garth", "Garth Hidesman", "Tanner")
        )

        val npcs = mutableMapOf<NpcId, Npc>()
        for ((idStr, name, occupation) in npcDefinitions) {
            val npcId = NpcId(idStr)
            val factionSet = when (idStr) {
                "guildmaster_titus", "blacksmith_krag", "miner_derek", "merchant" -> setOf(ironGuildId)
                "elder_rowan", "herbalist_lyra", "farmer_giles", "farmer_elena" -> setOf(verdantOrderId)
                else -> emptySet()
            }
            val traits = when (idStr) {
                "merchant" -> listOf(Trait.GREEDY, Trait.OPPORTUNISTIC)
                "apprentice" -> listOf(Trait.AMBITIOUS)
                "guard_captain" -> listOf(Trait.BRAVE, Trait.LOYAL)
                "miner_derek" -> listOf(Trait.VENGEFUL)
                "elder_rowan", "herbalist_lyra" -> listOf(Trait.COMPASSIONATE)
                else -> listOf(Trait.HONEST)
            }

            npcs[npcId] = Npc(
                id = npcId,
                identity = Identity(
                    name = name,
                    occupation = occupation,
                    homeLocation = squareId,
                    currentLocation = squareId,
                    isAlive = true
                ),
                traits = traits,
                goals = listOf(
                    Goal(
                        id = "survive_$idStr",
                        description = "Provide for self and family",
                        priority = 0.8f,
                        urgency = 0.5f
                    )
                ),
                factions = factionSet,
                resources = ResourceState(coins = 50f, food = 15f, health = 100f)
            )
        }

        val ironGuild = Faction(
            id = ironGuildId,
            name = "The Iron Guild",
            ideology = Ideology("Industry & Order", listOf("Prosperity", "Discipline")),
            members = setOf(NpcId("guildmaster_titus"), NpcId("blacksmith_krag"), NpcId("miner_derek"), NpcId("merchant")),
            leadership = LeadershipState(leader = NpcId("guildmaster_titus"), successionLine = listOf(NpcId("blacksmith_krag")))
        )
        val verdantOrder = Faction(
            id = verdantOrderId,
            name = "The Verdant Order",
            ideology = Ideology("Ecology & Community", listOf("Harmony", "Tradition")),
            members = setOf(NpcId("elder_rowan"), NpcId("herbalist_lyra"), NpcId("farmer_giles"), NpcId("farmer_elena")),
            leadership = LeadershipState(leader = NpcId("elder_rowan"), successionLine = listOf(NpcId("herbalist_lyra")))
        )

        var relGraph = RelationshipGraph()
        relGraph = relGraph.modify(
            from = NpcId("apprentice").toEntityId(),
            to = NpcId("merchant").toEntityId(),
            deltaTrust = 0.7f,
            deltaAffection = 0.6f,
            deltaRespect = 0.8f
        )

        return WorldState(
            seed = 10001L,
            time = WorldTime.ZERO,
            npcs = npcs,
            factions = mapOf(ironGuild.id to ironGuild, verdantOrder.id to verdantOrder),
            locations = locations,
            relationships = relGraph,
            economy = EconomyState(
                itemPrices = mapOf("bread" to 5f, "iron_sword" to 50f, "herb_bundle" to 8f, "iron_ingot" to 20f, "goods" to 15f)
            ),
            events = EventLedger(),
            player = PlayerState(
                currentLocation = squareId,
                coins = 100f,
                inventory = listOf(ItemId("iron_sword"))
            )
        )
    }
}
