package org.livingworld.core.events

import kotlinx.serialization.Serializable
import org.livingworld.core.ids.EntityId
import org.livingworld.core.ids.EventId
import org.livingworld.core.ids.NpcId
import org.livingworld.core.time.WorldTime

@Serializable
data class EventLedger(
    private val eventsList: List<GameEvent> = emptyList(),
    private val nextEventNumber: Long = 1L
) {
    val size: Int get() = eventsList.size

    fun nextId(): EventId = EventId(nextEventNumber)

    fun all(): List<GameEvent> = eventsList

    fun append(eventFactory: (EventId) -> GameEvent): Pair<EventLedger, GameEvent> {
        val id = EventId(nextEventNumber)
        val event = eventFactory(id)
        val updatedLedger = copy(
            eventsList = eventsList + event,
            nextEventNumber = nextEventNumber + 1L
        )
        return Pair(updatedLedger, event)
    }

    /**
     * Records an already-constructed event into the append-only ledger while advancing nextEventNumber monotonically.
     */
    fun record(event: GameEvent): EventLedger {
        if (find(event.id) != null) return this
        val nextNum = maxOf(nextEventNumber, eventsList.size + 1L, event.id.value + 1L)
        return copy(
            eventsList = eventsList + event,
            nextEventNumber = nextNum
        )
    }

    fun find(id: EventId): GameEvent? =
        eventsList.find { it.id == id }

    fun containsDeath(victim: NpcId): Boolean =
        eventsList.any { it is GameEvent.NpcKilled && it.victim == victim }

    fun eventsForEntity(entity: EntityId): List<GameEvent> =
        eventsList.filter { it.actors.contains(entity) }

    fun eventsWitnessedBy(npc: NpcId): List<GameEvent> =
        eventsList.filter { it.witnesses.contains(npc) }

    fun eventsSince(time: WorldTime): List<GameEvent> =
        eventsList.filter { it.time >= time }

    fun eventsInWindow(start: WorldTime, end: WorldTime): List<GameEvent> =
        eventsList.filter { it.time in start..end }

    inline fun <reified T : GameEvent> ofType(): List<T> =
        eventsList.filterIsInstance<T>()

    /**
     * Reconstruct the causal chain leading up to a specific event.
     */
    fun traceCausalChain(targetEventId: EventId): List<GameEvent> {
        val chain = mutableListOf<GameEvent>()
        var current: GameEvent? = find(targetEventId)
        val visited = mutableSetOf<EventId>()

        while (current != null && visited.add(current.id)) {
            chain.add(current)
            val parentId = current.causalEventId ?: break
            current = find(parentId)
        }
        return chain.reversed()
    }
}
