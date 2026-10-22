package org.livingworld.core.simulation

import org.livingworld.core.events.GameEvent
import org.livingworld.core.ids.NpcId
import org.livingworld.core.world.WorldReducer
import org.livingworld.core.world.WorldState

/**
 * The one place hunger becomes events. The reducer only applies events, so the damage is a recorded STARVE routine and
 * a fatal one is followed by an NpcKilled (no killer) that names the routine as its cause (G4/G5), exactly once.
 */
object StarvationEvents {
    fun apply(reducer: WorldReducer, state: WorldState, id: NpcId, damage: Float): WorldState {
        val npc = state.npcs[id] ?: return state
        if (!npc.isAlive || npc.resources.food > 0f) return state
        val hit = GameEvent.NpcRoutineCompleted(
            id = state.events.nextId(),
            time = state.time,
            npc = id,
            routineType = "STARVE",
            location = npc.identity.currentLocation,
            deltaHealth = -damage
        )
        val afterHit = reducer.apply(state, hit)
        val starved = afterHit.npcs[id] ?: return afterHit
        // identity.isAlive is false only once an NpcKilled has been applied, so a death is never recorded twice.
        if (starved.resources.health > 0f || !starved.identity.isAlive) return afterHit
        val kill = GameEvent.NpcKilled(
            id = afterHit.events.nextId(),
            time = state.time,
            victim = id,
            killer = null,
            witnesses = emptyList(),
            causalEventId = hit.id,
            location = npc.identity.currentLocation,
            motive = "Starvation"
        )
        return reducer.apply(afterHit, kill)
    }
}
