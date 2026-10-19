package org.livingworld.core.events

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.livingworld.core.ids.EntityId
import org.livingworld.core.ids.EventId
import org.livingworld.core.ids.NpcId
import org.livingworld.core.time.WorldTime

@Serializable
data class EventLedger(
    private val eventsList: List<GameEvent> = emptyList(),
    private val nextEventNumber: Long = 1L
) {
    /**
     * Derived index of the deaths in this ledger, built on first use and carried forward by append/record so the
     * per-tick problem scan does not walk the whole history. Not part of the saved form or of equality.
     */
    @Transient
    private var deathIndex: List<GameEvent.NpcKilled>? = null

    val size: Int get() = eventsList.size

    fun deaths(): List<GameEvent.NpcKilled> =
        deathIndex ?: eventsList.filterIsInstance<GameEvent.NpcKilled>().also { deathIndex = it }

    private fun withEvent(next: EventLedger, event: GameEvent): EventLedger {
        deathIndex?.let { known -> next.deathIndex = if (event is GameEvent.NpcKilled) known + event else known }
        return next
    }

    fun nextId(): EventId = EventId(nextEventNumber)

    fun all(): List<GameEvent> = eventsList

    fun append(eventFactory: (EventId) -> GameEvent): Pair<EventLedger, GameEvent> {
        val id = EventId(nextEventNumber)
        val event = eventFactory(id)
        val updatedLedger = withEvent(copy(
            eventsList = eventsList + event,
            nextEventNumber = nextEventNumber + 1L
        ), event)
        return Pair(updatedLedger, event)
    }

    /**
     * Records an already-constructed event into the append-only ledger while advancing nextEventNumber monotonically.
     */
    fun record(event: GameEvent): EventLedger {
        if (find(event.id) != null) return this
        val nextNum = maxOf(nextEventNumber, eventsList.size + 1L, event.id.value + 1L)
        return withEvent(copy(
            eventsList = eventsList + event,
            nextEventNumber = nextNum
        ), event)
    }

    /**
     * O(1) in the normal case. Every recorded id is below the next event number, so a new id is known to be absent
     * without a scan, and ids are normally sequential so a recorded event sits at index id-1. Only an unusual ledger
     * (gaps or reordered ids) falls back to the linear scan, so the result is always the same as scanning.
     */
    fun find(id: EventId): GameEvent? {
        if (id.value >= maxOf(nextEventNumber, eventsList.size + 1L)) return null
        val guess = eventsList.getOrNull((id.value - 1L).toInt())
        if (guess != null && guess.id == id) return guess
        return eventsList.find { it.id == id }
    }

    fun containsDeath(victim: NpcId): Boolean =
        deaths().any { it.victim == victim }

    fun eventsForEntity(entity: EntityId): List<GameEvent> =
        eventsList.filter { it.actors.contains(entity) }

    fun eventsWitnessedBy(npc: NpcId): List<GameEvent> =
        eventsList.filter { it.witnesses.contains(npc) }

    fun eventsSince(time: WorldTime): List<GameEvent> =
        eventsList.filter { it.time >= time }

    fun eventsInWindow(start: WorldTime, end: WorldTime): List<GameEvent> =
        eventsList.filter { it.time in start..end }

    inline fun <reified T : GameEvent> ofType(): List<T> =
        all().filterIsInstance<T>()

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
