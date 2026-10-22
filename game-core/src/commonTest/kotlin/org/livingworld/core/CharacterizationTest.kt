package org.livingworld.core

import kotlinx.coroutines.test.runTest
import org.livingworld.core.time.WorldDuration
import org.livingworld.core.world.DefaultWorldReducer
import kotlin.test.*

/**
 * Characterisation of the behaviour the kernel is supposed to keep: coins are conserved, the ledger is strictly
 * ordered, and replaying the ledger rebuilds the whole world (not just its NPCs).
 *
 * These are guards, not wishes: if one of them fails, something in the kernel changed for the worse. The known
 * gaps that these tests do NOT cover are listed in `docs/reports/adversarial-2026-10-22.md`.
 */
class CharacterizationTest {

    private fun totalCoins(world: org.livingworld.core.world.WorldState): Double =
        world.npcs.values.sumOf { it.resources.coins.toDouble() } +
            world.player.coins + world.economy.treasury

    @Test
    fun coins_are_conserved_over_thirty_days() = runTest {
        val world = TestWorld.create()
        val before = totalCoins(world.state)
        world.advance(30)
        assertEquals(before, totalCoins(world.state), 0.001, "the closed economy lost or created coins")
    }

    @Test
    fun the_ledger_is_strictly_ordered_and_unique() = runTest {
        val world = TestWorld.create()
        world.advance(30)
        val ids = world.state.events.all().map { it.id.value }
        assertTrue(ids.isNotEmpty(), "the run produced no events")
        assertEquals(ids.size, ids.toSet().size, "the ledger holds a duplicate event id")
        assertEquals(ids.sorted(), ids, "the ledger is not in increasing id order")
    }

    @Test
    fun replaying_the_ledger_rebuilds_the_whole_world() = runTest {
        val world = TestWorld.create()
        val initial = world.state
        world.advance(30)
        val live = world.state

        val reducer = DefaultWorldReducer()
        var replayed = initial
        for (event in live.events.all()) {
            replayed = reducer.apply(replayed, event)
        }

        assertEquals(live.npcs, replayed.npcs, "replayed NPCs differ")
        assertEquals(live.factions, replayed.factions, "replayed factions differ")
        assertEquals(live.relationships, replayed.relationships, "replayed relationships differ")
        assertEquals(live.economy, replayed.economy, "replayed economy differs")
        assertEquals(live.activeQuests, replayed.activeQuests, "replayed quests differ")
        assertEquals(live.player, replayed.player, "replayed player differs")
        assertEquals(live.events.all(), replayed.events.all(), "replayed ledger differs")
        assertEquals(live.time, replayed.time, "replayed time differs")
    }

    @Test
    fun a_thirty_day_run_leaves_every_npc_alive_and_accounted_for() = runTest {
        val world = TestWorld.create()
        world.advance(30)
        val state = world.state
        assertEquals(20, state.npcs.size)
        assertTrue(state.npcs.values.all { it.isAlive }, "an NPC died in a quiet 30-day run")
        // The treasury is only a transfer point: it can shrink, but it is never created and never negative.
        assertTrue(state.economy.treasury in 0f..200f, "the treasury left its range: ${state.economy.treasury}")
    }
}
