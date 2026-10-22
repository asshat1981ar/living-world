package org.livingworld.core

import kotlinx.coroutines.test.runTest
import org.livingworld.core.events.GameEvent
import org.livingworld.core.ids.*
import org.livingworld.core.npc.Npc
import org.livingworld.core.simulation.Intent
import org.livingworld.core.simulation.NpcDecisionEngine
import org.livingworld.core.simulation.SimulationEngine
import org.livingworld.core.simulation.ValidatedAction
import org.livingworld.core.time.WorldTime
import org.livingworld.core.world.DefaultWorldReducer
import org.livingworld.core.world.StateDigest
import org.livingworld.core.world.WorldInvariants
import org.livingworld.core.world.WorldState
import kotlin.test.*

/**
 * Starvation is the engine's doing, like upkeep: at the day boundary an NPC with no food takes damage as a recorded
 * routine, and a fatal one is followed by an NpcKilled that names it as its cause. The reducer only applies events.
 */
class StarvationTest {
    private class AllRest : NpcDecisionEngine {
        override suspend fun decide(npc: Npc, world: WorldState) = ValidatedAction(Intent.Rest)
    }

    private val baker = NpcId("baker_milo")
    private val scholar = NpcId("scholar_erik")

    private fun lateEvening(): WorldState {
        val s = TestWorld.create().state
        return s.copy(npcs = s.npcs.filterKeys { it == baker || it == scholar }, time = WorldTime(23L * WorldTime.TICKS_PER_HOUR))
    }

    private fun WorldState.edit(id: NpcId, change: (Npc) -> Npc): WorldState = copy(npcs = npcs + (id to change(npc(id))))

    private fun WorldState.starving(id: NpcId, health: Float) =
        edit(id) { it.copy(resources = it.resources.copy(food = 0f, health = health)) }

    private fun engine() = SimulationEngine(DefaultWorldReducer(), AllRest())

    private fun WorldState.routines(type: String) =
        events.all().filterIsInstance<GameEvent.NpcRoutineCompleted>().filter { it.routineType == type }

    @Test
    fun a_starving_npc_is_hurt_once_a_day_and_a_fed_one_is_not() = runTest {
        val before = lateEvening().starving(baker, health = 60f)
        val after = engine().tick(before)
        val hits = after.routines("STARVE")
        assertEquals(listOf(baker), hits.map { it.npc })
        assertEquals(-SimulationEngine.STARVATION_DAMAGE, hits.single().deltaHealth)
        // Rest heals 10 first (the tick's action), then the day boundary takes the damage.
        assertEquals(70f - SimulationEngine.STARVATION_DAMAGE, after.npc(baker).resources.health)
        assertTrue(after.npc(baker).isAlive)
        assertTrue(after.events.all().none { it is GameEvent.NpcKilled })
        // The next hour is not a day boundary.
        assertEquals(1, engine().tick(after).routines("STARVE").size)
    }

    @Test
    fun a_fatal_hunger_records_one_killerless_death_caused_by_the_hit() = runTest {
        val dead = engine().tick(lateEvening().starving(baker, health = 5f))
        val hit = dead.routines("STARVE").single()
        val kill = dead.events.all().filterIsInstance<GameEvent.NpcKilled>().single()
        assertEquals(baker, kill.victim)
        assertNull(kill.killer)
        assertEquals("Starvation", kill.motive)
        assertEquals(hit.id, kill.causalEventId)
        assertTrue(kill.id.value > hit.id.value)
        assertFalse(dead.npc(baker).isAlive)
        assertEquals(emptyList(), WorldInvariants.violations(dead))

        // The dead are not starved again, and nothing else dies.
        val later = engine().tick(engine().tick(dead.copy(time = WorldTime(47L * WorldTime.TICKS_PER_HOUR))))
        assertEquals(1, later.events.all().count { it is GameEvent.NpcKilled })
        assertEquals(1, later.routines("STARVE").size)
        assertTrue(later.npc(scholar).isAlive)
    }

    @Test
    fun the_ledger_replays_to_the_same_world_and_coins_are_not_lost() = runTest {
        val start = lateEvening().starving(baker, health = 5f)
        val live = engine().tick(start)
        var replayed = start
        val reducer = DefaultWorldReducer()
        for (event in live.events.all()) replayed = reducer.apply(replayed, event)
        assertEquals(StateDigest.of(live), StateDigest.of(replayed))

        fun coins(s: WorldState) = s.npcs.values.sumOf { it.resources.coins.toDouble() } + s.economy.treasury
        assertEquals(coins(start), coins(live), 1e-3, "starvation neither mints nor destroys coins")
    }

    @Test
    fun running_out_of_food_is_not_yet_dying() = runTest {
        val hungry = lateEvening().edit(baker) { it.copy(resources = it.resources.copy(food = 1f)) }  // the tick's Rest takes 0.25, leaving some
        val after = engine().tick(hungry)
        assertTrue(after.routines("STARVE").isEmpty())
    }
}
