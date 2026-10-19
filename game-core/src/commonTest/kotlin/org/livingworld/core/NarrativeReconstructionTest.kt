package org.livingworld.core

import kotlinx.coroutines.test.runTest
import org.livingworld.core.beliefs.Belief
import org.livingworld.core.beliefs.KnowledgeSource
import org.livingworld.core.beliefs.Proposition
import org.livingworld.core.events.DuelOutcome
import org.livingworld.core.events.GameEvent
import org.livingworld.core.events.TacticalStance
import org.livingworld.core.ids.*
import org.livingworld.core.inspector.SimulationInspector
import org.livingworld.core.time.WorldTime
import org.livingworld.core.world.DefaultWorldReducer
import kotlin.test.*

/** G6: a story can be rebuilt from the ledger alone through causal links and the inspector. */
class NarrativeReconstructionTest {
    @Test
    fun explainable_narrative_arc_reconstruction() = runTest {
        val world = TestWorld.create()
        val merchantId = NpcId("merchant")
        val apprenticeId = NpcId("apprentice")
        val player = EntityId.Player("player")
        val ironGuild = FactionId("iron_guild")
        val verdantOrder = FactionId("verdant_order")
        val bread = ItemId("bread")

        world.state = world.state.copy(time = WorldTime(12L * WorldTime.TICKS_PER_DAY))
        world.meet(merchantId.toEntityId(), player)

        world.state = world.state.copy(time = WorldTime(14L * WorldTime.TICKS_PER_DAY))
        world.trust(merchantId.toEntityId(), player, 0.4f, "Player assisted with trade caravan")

        world.state = world.state.copy(time = WorldTime(21L * WorldTime.TICKS_PER_DAY))
        val rumorBelief = Belief(
            proposition = Proposition.NpcLoyalToFaction(NpcId("guildmaster_titus"), ironGuild),
            confidence = 0.8f,
            source = KnowledgeSource.DirectObservation,
            reliability = 0.9f,
            acquiredAt = world.state.time
        )
        val rumorEvent = world.spreadRumor(NpcId("elder_rowan"), merchantId, rumorBelief)

        world.state = world.state.copy(time = WorldTime(24L * WorldTime.TICKS_PER_DAY))
        val allegianceEvent = GameEvent.AllegianceChanged(
            id = EventId(world.eventLedger.size + 1L),
            time = world.state.time,
            npc = merchantId,
            oldFaction = ironGuild,
            newFaction = verdantOrder,
            reason = "Disillusionment following Elder Rowan's revelations",
            causalEventId = rumorEvent.id
        )
        world.state = DefaultWorldReducer().apply(world.state, allegianceEvent)

        world.state = world.state.copy(time = WorldTime(29L * WorldTime.TICKS_PER_DAY))
        val attackEvent = GameEvent.NpcAttacked(
            id = EventId(world.eventLedger.size + 1L),
            time = world.state.time,
            attacker = NpcId("miner_derek").toEntityId(),
            target = merchantId,
            damage = 80f,
            location = LocationId("village_square"),
            witnesses = listOf(apprenticeId),
            causalEventId = allegianceEvent.id,
            attackerStance = TacticalStance.STRIKE,
            defenderStance = TacticalStance.FEINT,
            duelOutcome = DuelOutcome.COUNTER_STRIKE
        )
        world.state = DefaultWorldReducer().apply(world.state, attackEvent)

        world.state = world.state.copy(time = WorldTime(30L * WorldTime.TICKS_PER_DAY))
        val deathEvent = world.kill(
            victim = merchantId,
            killer = NpcId("miner_derek").toEntityId(),
            witnesses = listOf(apprenticeId),
            causalEventId = attackEvent.id,
            motive = "Retribution for Guild betrayal"
        )

        world.state = world.state.copy(time = WorldTime(31L * WorldTime.TICKS_PER_DAY))
        val inheritEvent = GameEvent.ShopInherited(
            id = EventId(world.eventLedger.size + 1L),
            time = world.state.time,
            heir = apprenticeId,
            predecessor = merchantId,
            shopName = "Alden's Sundries",
            causalEventId = deathEvent.id
        )
        world.state = DefaultWorldReducer().apply(world.state, inheritEvent)

        world.state = world.state.copy(time = WorldTime(36L * WorldTime.TICKS_PER_DAY))
        val priceEvent = GameEvent.PriceAdjusted(
            id = EventId(world.eventLedger.size + 1L),
            time = world.state.time,
            merchant = apprenticeId,
            item = bread,
            oldPrice = 5f,
            newPrice = 12f,
            reason = "Supply shortage and apprentice debts",
            causalEventId = inheritEvent.id
        )
        world.state = DefaultWorldReducer().apply(world.state, priceEvent)

        world.state = world.state.copy(time = WorldTime(42L * WorldTime.TICKS_PER_DAY))
        val hostilityEvent = GameEvent.FactionHostilityDeclared(
            id = EventId(world.eventLedger.size + 1L),
            time = world.state.time,
            declaringFaction = verdantOrder,
            targetFaction = ironGuild,
            reason = "Murder of merchant Alden in village square",
            causalEventId = deathEvent.id
        )
        world.state = DefaultWorldReducer().apply(world.state, hostilityEvent)

        val inspector = SimulationInspector(world.state)
        assertTrue(inspector.inspectNpc(apprenticeId).contains("Alden's Sundries"))
        val chain = world.eventLedger.traceCausalChain(hostilityEvent.id)

        assertTrue(chain.size >= 4, "Causal chain must link back through antecedent events")
        assertEquals(rumorEvent.id, chain.first().id, "Chain root must be the rumor that caused the allegiance shift")
        assertEquals(allegianceEvent.id, chain[1].id, "Allegiance shift must follow the rumor")
        assertEquals(hostilityEvent.id, chain.last().id, "Chain terminus must be the hostility declaration")

        assertEquals("Shopkeeper (Alden's Sundries)", world.npc(apprenticeId).identity.occupation)
        assertEquals(12f, world.state.economy.getPrice(bread))
        assertTrue(world.state.factions[verdantOrder]!!.isEnemy(ironGuild))
    }
}
