package org.livingworld.core

import org.livingworld.core.events.GameEvent
import org.livingworld.core.ids.*
import org.livingworld.core.time.WorldTime
import org.livingworld.core.world.DefaultWorldReducer
import org.livingworld.core.world.WorldInvariants
import kotlin.test.*

/** The rules a world must satisfy at rest (the contract the save gate checks). */
class WorldInvariantsTest {

    private fun world() = TestWorld.create().state

    @Test
    fun a_healthy_world_breaks_no_rule() {
        assertEquals(emptyList(), WorldInvariants.violations(world()))
    }

    @Test
    fun a_dead_npc_with_health_is_a_violation() {
        val state = world()
        val merchant = state.npc(NpcId("merchant"))
        val broken = state.copy(
            npcs = state.npcs + (merchant.id to merchant.copy(
                identity = merchant.identity.copy(isAlive = false),
                resources = merchant.resources.copy(health = 50f)
            ))
        )
        assertTrue(WorldInvariants.violations(broken).any { it.contains("deceased NPC merchant") })
    }

    @Test
    fun an_alive_npc_without_health_is_a_violation() {
        val state = world()
        val merchant = state.npc(NpcId("merchant"))
        val broken = state.copy(
            npcs = state.npcs + (merchant.id to merchant.copy(resources = merchant.resources.copy(health = 0f)))
        )
        assertTrue(WorldInvariants.violations(broken).any { it.contains("alive with no health") })
    }

    @Test
    fun an_event_that_names_an_unknown_npc_is_never_recorded() {
        val reducer = DefaultWorldReducer()
        val state = reducer.apply(world(), GameEvent.NpcKilled(
            id = EventId(1), time = WorldTime(1), victim = NpcId("nobody"), killer = null, witnesses = emptyList()
        ))
        assertEquals(0, state.events.size, "a death of someone who never existed was recorded")
        assertEquals(emptyList(), WorldInvariants.violations(state), "the world should still be sound")
    }

    @Test
    fun a_ledger_that_does_not_increase_is_a_violation() {
        val reducer = DefaultWorldReducer()
        var state = world()
        state = reducer.apply(state, GameEvent.PriceAdjusted(
            id = EventId(5), time = WorldTime(1), merchant = NpcId("merchant"), item = ItemId("bread"),
            oldPrice = 5f, newPrice = 6f, reason = "t"
        ))
        state = reducer.apply(state, GameEvent.PriceAdjusted(
            id = EventId(4), time = WorldTime(2), merchant = NpcId("merchant"), item = ItemId("bread"),
            oldPrice = 6f, newPrice = 7f, reason = "t"
        ))
        assertTrue(WorldInvariants.violations(state).any { it.contains("not after the previous one") })
    }

    @Test
    fun a_negative_treasury_is_a_violation() {
        val state = world().copy(economy = world().economy.copy(treasury = -1f))
        assertTrue(WorldInvariants.violations(state).any { it.contains("treasury") })
    }
}
