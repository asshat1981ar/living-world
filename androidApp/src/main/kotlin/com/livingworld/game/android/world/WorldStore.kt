package com.livingworld.game.android.world

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.livingworld.core.events.GameEvent
import org.livingworld.core.simulation.SimulationEngine
import org.livingworld.core.time.WorldDuration
import org.livingworld.core.world.DefaultVillageFactory
import org.livingworld.core.world.DefaultWorldReducer
import org.livingworld.core.world.WorldReducer
import org.livingworld.core.world.WorldState
import org.livingworld.persistence.SaveManager

/**
 * The one world the app has.
 *
 * Every screen reads and writes this state, so a trade in the market is a trade in the village, and a death in a duel is
 * a death for everybody. The world also lives on: it ticks while the app is in front, and it is saved and restored, so
 * the village is still there after the process is killed.
 */
class WorldStore(
    initial: WorldState = DefaultVillageFactory.marketVillage(),
    private val reducer: WorldReducer = DefaultWorldReducer(),
    private val engine: SimulationEngine = SimulationEngine(reducer)
) {
    private val _state = MutableStateFlow(initial)

    /** The world as it is now. */
    val state: StateFlow<WorldState> = _state.asStateFlow()

    /** Applies one event through the reducer; the whole village moves on. */
    fun apply(event: GameEvent) {
        _state.value = reducer.apply(_state.value, event)
    }

    /** Applies a chain of events as one step of the world (an attack that kills is two events). */
    fun applyChain(block: (WorldState) -> WorldState) {
        _state.value = block(_state.value)
    }

    /** Lets the village live: one step of the simulation. */
    suspend fun tick(duration: WorldDuration = WorldDuration.hours(1)) {
        _state.value = engine.advance(_state.value, duration)
    }

    /** The world as a save envelope, or null when it cannot be written. */
    fun save(): String? = runCatching { SaveManager.serializeEnvelope(_state.value) }.getOrNull()

    /** Restores a saved world; false when there is nothing to restore. */
    fun restore(envelope: String): Boolean {
        val restored = runCatching { SaveManager.deserializeEnvelope(envelope) }.getOrNull() ?: return false
        _state.value = restored.worldState
        return true
    }
}
