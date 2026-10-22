package org.livingworld.core

import org.livingworld.core.events.GameEvent
import org.livingworld.core.ids.*
import org.livingworld.core.time.WorldTime
import org.livingworld.core.world.DefaultWorldReducer
import kotlin.test.*

/** G2: an NPC knows what it saw, not what the world did. */
class KnowledgeScopeTest {

    private fun world() = TestWorld.create().state

    @Test
    fun an_npc_does_not_know_about_a_killing_it_could_not_have_seen() {
        val reducer = DefaultWorldReducer()
        val state = world()
        val tavern = LocationId("tavern")
        val killed = reducer.apply(
            state,
            GameEvent.NpcKilled(
                id = state.events.nextId(), time = WorldTime(1), victim = NpcId("farmer_elena"),
                killer = NpcId("hunter_bram").toEntityId(), witnesses = emptyList(), location = tavern
            )
        )
        val bystander = killed.npc(NpcId("merchant"))    // stands in the village square

        val seenByBystander = killed.knownEvents(bystander).filterIsInstance<GameEvent.NpcKilled>()
        assertTrue(seenByBystander.isEmpty(), "the square knew about a killing at the tavern")
        assertTrue(
            killed.snapshotFor(bystander).recentEvents.filterIsInstance<GameEvent.NpcKilled>().isEmpty(),
            "the advisor's snapshot exposed a killing the NPC could not have seen"
        )
        assertNotEquals(tavern, bystander.identity.currentLocation, "precondition: the bystander was elsewhere")
    }

    @Test
    fun a_witness_knows_about_the_killing_it_saw() {
        val reducer = DefaultWorldReducer()
        val state = world()
        val square = LocationId("village_square")
        val killed = reducer.apply(
            state,
            GameEvent.NpcKilled(
                id = state.events.nextId(), time = WorldTime(1), victim = NpcId("farmer_elena"),
                killer = NpcId("hunter_bram").toEntityId(), witnesses = listOf(NpcId("guard_captain")),
                location = square
            )
        )
        val witness = killed.npc(NpcId("guard_captain"))
        val known = killed.knownEvents(witness).filterIsInstance<GameEvent.NpcKilled>()
        assertEquals(1, known.size)
        assertEquals(NpcId("farmer_elena"), known.first().victim)
    }

    @Test
    fun a_public_event_at_the_place_an_npc_stands_is_known() {
        val reducer = DefaultWorldReducer()
        val state = world()
        val square = LocationId("village_square")
        val priced = reducer.apply(
            state,
            GameEvent.PriceAdjusted(
                id = state.events.nextId(), time = WorldTime(1), merchant = NpcId("merchant"),
                item = ItemId("bread"), oldPrice = 5f, newPrice = 6f, reason = "grain tax"
            )
        )
        val merchant = priced.npc(NpcId("merchant"))
        assertTrue(priced.knownEvents(merchant).any { it is GameEvent.PriceAdjusted })
        assertEquals(square, merchant.identity.currentLocation)
    }
}
