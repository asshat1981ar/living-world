package org.livingworld.core

import org.livingworld.core.beliefs.Belief
import org.livingworld.core.beliefs.BeliefStore
import org.livingworld.core.beliefs.KnowledgeSource
import org.livingworld.core.beliefs.Proposition
import org.livingworld.core.events.EventLedger
import org.livingworld.core.events.GameEvent
import org.livingworld.core.factions.Faction
import org.livingworld.core.factions.Ideology
import org.livingworld.core.factions.LeadershipState
import org.livingworld.core.ids.*
import org.livingworld.core.memory.MemoryStore
import org.livingworld.core.npc.*
import org.livingworld.core.relationships.Relationship
import org.livingworld.core.relationships.RelationshipGraph
import org.livingworld.core.simulation.SimulationEngine
import org.livingworld.core.time.WorldDuration
import org.livingworld.core.time.WorldTime
import org.livingworld.core.world.*

class TestWorld(
    var state: WorldState
) {
    private val reducer: WorldReducer = DefaultWorldReducer()
    val simulationEngine: SimulationEngine = SimulationEngine(reducer)

    val eventLedger: EventLedger get() = state.events
    val relationships: RelationshipGraph get() = state.relationships

    fun npc(id: NpcId): Npc = state.npc(id)
    fun isDead(id: NpcId): Boolean = state.isDead(id)

    fun steal(
        thief: EntityId,
        item: ItemId,
        owner: EntityId? = null,
        witnesses: List<NpcId> = emptyList()
    ): GameEvent.ItemStolen {
        val eventId = EventId(state.events.size + 1L)
        val event = GameEvent.ItemStolen(
            id = eventId,
            time = state.time,
            thief = thief,
            item = item,
            owner = owner,
            witnesses = witnesses,
            location = LocationId("village_square")
        )
        state = reducer.apply(state, event)
        return event
    }

    fun kill(
        victim: NpcId,
        killer: EntityId? = null,
        witnesses: List<NpcId> = emptyList(),
        causalEventId: EventId? = null,
        motive: String? = "Assault"
    ): GameEvent.NpcKilled {
        val eventId = EventId(state.events.size + 1L)
        val event = GameEvent.NpcKilled(
            id = eventId,
            time = state.time,
            victim = victim,
            killer = killer,
            witnesses = witnesses,
            causalEventId = causalEventId,
            location = state.npcs[victim]?.identity?.currentLocation ?: LocationId("village_square"),
            motive = motive
        )
        state = reducer.apply(state, event)
        return event
    }

    fun meet(entityA: EntityId, entityB: EntityId): GameEvent.EntitiesMet {
        val eventId = EventId(state.events.size + 1L)
        val event = GameEvent.EntitiesMet(
            id = eventId,
            time = state.time,
            entityA = entityA,
            entityB = entityB,
            location = LocationId("village_square")
        )
        state = reducer.apply(state, event)
        return event
    }

    fun trust(source: EntityId, target: EntityId, deltaTrust: Float, reason: String): GameEvent.RelationshipShifted {
        val eventId = EventId(state.events.size + 1L)
        val event = GameEvent.RelationshipShifted(
            id = eventId,
            time = state.time,
            source = source,
            target = target,
            deltaTrust = deltaTrust,
            deltaAffection = deltaTrust * 0.5f,
            deltaRespect = deltaTrust * 0.4f,
            deltaFear = 0f,
            deltaResentment = 0f,
            reason = reason
        )
        state = reducer.apply(state, event)
        return event
    }

    fun spreadRumor(source: NpcId, target: NpcId, belief: Belief): GameEvent.RumorTransmitted {
        val eventId = EventId(state.events.size + 1L)
        val srcNpc = state.npc(source)
        val tgtNpc = state.npc(target)
        val transmitted = simulationEngine.transmitBelief(srcNpc, tgtNpc, belief, state.time)
        val event = GameEvent.RumorTransmitted(
            id = eventId,
            time = state.time,
            source = source,
            target = target,
            transmittedBelief = transmitted,
            distortion = 0.05f
        )
        state = reducer.apply(state, event)
        return event
    }

    suspend fun advance(days: Int) {
        state = simulationEngine.advance(state, WorldDuration.days(days.toLong()))
    }

    companion object {
        fun create(): TestWorld {
            val villageLoc = Location(
                id = LocationId("village_square"),
                name = "Oakhaven Village Square",
                description = "The bustling cobblestone hub of Oakhaven."
            )
            val tavernLoc = Location(
                id = LocationId("tavern"),
                name = "The Weary Traveler Tavern",
                description = "Warm hearth where villagers gather."
            )
            val shopLoc = Location(
                id = LocationId("general_store"),
                name = "Alden's Sundries",
                description = "Village general trading post."
            )

            val locations = mapOf(
                villageLoc.id to villageLoc,
                tavernLoc.id to tavernLoc,
                shopLoc.id to shopLoc
            )

            // Factions
            val ironGuild = Faction(
                id = FactionId("iron_guild"),
                name = "The Iron Guild",
                ideology = Ideology("Industry & Order", listOf("Prosperity", "Discipline")),
                leadership = LeadershipState(leader = NpcId("guildmaster_titus"))
            )
            val verdantOrder = Faction(
                id = FactionId("verdant_order"),
                name = "The Verdant Order",
                ideology = Ideology("Ecology & Community", listOf("Harmony", "Tradition")),
                leadership = LeadershipState(leader = NpcId("elder_rowan"))
            )
            val factions = mapOf(
                ironGuild.id to ironGuild,
                verdantOrder.id to verdantOrder
            )

            // 20 Named NPCs
            val npcDefinitions = listOf(
                Pair("merchant", "Alden the Merchant") to "Merchant",
                Pair("apprentice", "Finn the Apprentice") to "Shop Apprentice",
                Pair("guard_captain", "Captain Vane") to "Guard Captain",
                Pair("guildmaster_titus", "Titus Ironforge") to "Guildmaster",
                Pair("elder_rowan", "Elder Rowan") to "Village Elder",
                Pair("blacksmith_krag", "Krag the Blacksmith") to "Blacksmith",
                Pair("innkeeper_mara", "Mara of the Hearth") to "Innkeeper",
                Pair("herbalist_lyra", "Lyra Greenleaf") to "Herbalist",
                Pair("hunter_bram", "Bram the Scout") to "Hunter",
                Pair("farmer_giles", "Giles Oatfield") to "Farmer",
                Pair("farmer_elena", "Elena Oatfield") to "Farmer",
                Pair("carpenter_jorin", "Jorin Woodshaper") to "Carpenter",
                Pair("miner_derek", "Derek Deepdelve") to "Miner",
                Pair("weaver_sela", "Sela Threadspinner") to "Weaver",
                Pair("priest_orion", "Father Orion") to "Priest",
                Pair("watchman_cole", "Cole Nightwatch") to "Watchman",
                Pair("baker_milo", "Milo the Baker") to "Baker",
                Pair("brewer_oscar", "Oscar Barleybrew") to "Brewer",
                Pair("scholar_erik", "Erik Chronicler") to "Scholar",
                Pair("tanner_garth", "Garth Hidesman") to "Tanner"
            )

            val npcs = mutableMapOf<NpcId, Npc>()
            for (((idStr, name), occupation) in npcDefinitions) {
                val npcId = NpcId(idStr)
                val factionSet = when (idStr) {
                    "guildmaster_titus", "blacksmith_krag", "miner_derek" -> setOf(ironGuild.id)
                    "elder_rowan", "herbalist_lyra", "farmer_giles" -> setOf(verdantOrder.id)
                    "merchant" -> setOf(ironGuild.id)
                    else -> emptySet()
                }
                val traits = when (idStr) {
                    "merchant" -> listOf(Trait.GREEDY, Trait.OPPORTUNISTIC)
                    "apprentice" -> listOf(Trait.AMBITIOUS)
                    "guard_captain" -> listOf(Trait.BRAVE, Trait.LOYAL)
                    else -> listOf(Trait.HONEST)
                }

                npcs[npcId] = Npc(
                    id = npcId,
                    identity = Identity(
                        name = name,
                        occupation = occupation,
                        homeLocation = villageLoc.id,
                        currentLocation = villageLoc.id,
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

            // Establish foundational relationships: apprentice respects merchant
            var relGraph = RelationshipGraph()
            relGraph = relGraph.modify(
                from = NpcId("apprentice").toEntityId(),
                to = NpcId("merchant").toEntityId(),
                deltaTrust = 0.7f,
                deltaAffection = 0.6f,
                deltaRespect = 0.8f
            )

            val player = PlayerState(
                currentLocation = villageLoc.id,
                coins = 100f,
                inventory = listOf(ItemId("iron_sword"))
            )

            val initialWorld = WorldState(
                seed = 10001L,
                time = WorldTime.ZERO,
                npcs = npcs,
                factions = factions,
                locations = locations,
                relationships = relGraph,
                economy = EconomyState(
                    itemPrices = mapOf("bread" to 5f, "iron_sword" to 50f, "goods" to 15f)
                ),
                events = EventLedger(),
                player = player
            )

            return TestWorld(initialWorld)
        }
    }
}
