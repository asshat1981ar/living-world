package org.livingworld.core

import kotlinx.coroutines.test.runTest
import org.livingworld.core.ids.*
import org.livingworld.core.time.WorldDuration
import org.livingworld.core.time.WorldTime
import org.livingworld.core.world.DefaultWorldReducer
import kotlin.test.*

/** G5/G8: replaying the ledger rebuilds the live world, and the same start gives the same 90 days. */
class ReplayDeterminismTest {
    @Test
    fun deterministic_event_replay_matches_live_simulation() = runTest {
        val initialWorld = TestWorld.create().state
        val engine = TestWorld.create().simulationEngine
        val reducer = DefaultWorldReducer()

        val liveEndState = engine.advance(initialWorld, WorldDuration.hours(6))
        val recordedEvents = liveEndState.events.all()
        assertTrue(recordedEvents.isNotEmpty(), "RT-1: Simulation ticks must record events in EventLedger")

        var replayedState = initialWorld
        for (ev in recordedEvents) {
            replayedState = reducer.apply(replayedState, ev)
        }

        assertEquals(liveEndState.time, replayedState.time, "RT-2: Replayed state time must equal live state time")
        assertEquals(liveEndState.npcs, replayedState.npcs, "RT-1: Replayed NPC states must match live simulation bit-for-bit")
        // The whole world must come back, not only the NPCs: the relationships, the economy, the quests, the
        // player and the ledger are what the simulation is.
        assertEquals(liveEndState.factions, replayedState.factions, "replayed factions differ")
        assertEquals(liveEndState.relationships, replayedState.relationships, "replayed relationships differ")
        assertEquals(liveEndState.economy, replayedState.economy, "replayed economy differs")
        assertEquals(liveEndState.activeQuests, replayedState.activeQuests, "replayed quests differ")
        assertEquals(liveEndState.player, replayedState.player, "replayed player differs")
        assertEquals(liveEndState.events.all(), replayedState.events.all(), "replayed ledger differs")
    }

    @Test
    fun ninety_day_soak_maintains_food_equilibrium_and_determinism() = runTest {
        val engine = TestWorld.create().simulationEngine
        val worldA = engine.advance(TestWorld.create().state, WorldDuration.days(90))
        val worldB = engine.advance(TestWorld.create().state, WorldDuration.days(90))

        assertEquals(90L * WorldTime.TICKS_PER_DAY, worldA.time.tick)
        assertEquals(worldA.npcs, worldB.npcs, "Invariant G8: 90-day soak must be bit-for-bit deterministic")
        assertEquals(worldA.factions, worldB.factions, "factions drifted")
        assertEquals(worldA.relationships, worldB.relationships, "relationships drifted")
        assertEquals(worldA.economy, worldB.economy, "the economy drifted")
        assertEquals(worldA.activeQuests, worldB.activeQuests, "the quests drifted")
        assertEquals(worldA.player, worldB.player, "the player drifted")
        assertEquals(worldA.events.all(), worldB.events.all(), "the ledger drifted")
        assertTrue(
            worldA.npcs.values.filter { it.isAlive }.all { it.resources.food >= 0.5f },
            "RT-14: All living NPCs must maintain non-zero food reserves over 90 days"
        )
    }
}
