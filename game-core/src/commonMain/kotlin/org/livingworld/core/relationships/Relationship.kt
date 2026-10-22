package org.livingworld.core.relationships

import kotlinx.serialization.Serializable
import org.livingworld.core.ids.EntityId
import org.livingworld.core.ids.EventId

@Serializable
data class Obligation(
    val id: String,
    val debtor: EntityId,
    val creditor: EntityId,
    val description: String,
    val weight: Float = 1.0f
)

@Serializable
data class Secret(
    val id: String,
    val description: String,
    val severity: Float = 0.5f,
    val holder: EntityId
)

@Serializable
data class Relationship(
    val trust: Float = 0.0f,       // -1.0 to 1.0
    val affection: Float = 0.0f,   // -1.0 to 1.0
    val respect: Float = 0.0f,     // -1.0 to 1.0
    val fear: Float = 0.0f,        // 0.0 to 1.0
    val resentment: Float = 0.0f,  // 0.0 to 1.0
    val sharedEvents: List<EventId> = emptyList(),
    val obligations: List<Obligation> = emptyList(),
    val secrets: List<Secret> = emptyList()
) {
    val overallSentiment: Float
        get() = (trust * 0.35f + affection * 0.25f + respect * 0.2f - resentment * 0.3f - fear * 0.1f)
            .coerceIn(-1.0f, 1.0f)

    fun shifted(
        deltaTrust: Float = 0f,
        deltaAffection: Float = 0f,
        deltaRespect: Float = 0f,
        deltaFear: Float = 0f,
        deltaResentment: Float = 0f,
        event: EventId? = null
    ): Relationship {
        val safeTrust = if (deltaTrust.isNaN()) 0f else deltaTrust
        val safeAffection = if (deltaAffection.isNaN()) 0f else deltaAffection
        val safeRespect = if (deltaRespect.isNaN()) 0f else deltaRespect
        val safeFear = if (deltaFear.isNaN()) 0f else deltaFear
        val safeResentment = if (deltaResentment.isNaN()) 0f else deltaResentment

        return copy(
            trust = (trust + safeTrust).coerceIn(-1.0f, 1.0f),
            affection = (affection + safeAffection).coerceIn(-1.0f, 1.0f),
            respect = (respect + safeRespect).coerceIn(-1.0f, 1.0f),
            fear = (fear + safeFear).coerceIn(0.0f, 1.0f),
            resentment = (resentment + safeResentment).coerceIn(0.0f, 1.0f),
            sharedEvents = if (event != null && !sharedEvents.contains(event)) sharedEvents + event else sharedEvents
        )
    }

    companion object {
        val NEUTRAL = Relationship()
    }
}

@Serializable
data class RelationshipGraph(
    private val edges: Map<String, Relationship> = emptyMap(),
    private val changeCount: Long = 0L
) {
    /**
     * Relationships are keyed by the kind *and* the name of both parties, so an NPC and a player that share a name
     * are different relationships (`npc:merchant->player:player`).
     */
    private fun key(from: EntityId, to: EntityId): String = "${from.typedId()}->${to.typedId()}"

    fun get(from: EntityId, to: EntityId): Relationship =
        edges[key(from, to)] ?: Relationship.NEUTRAL

    fun containsChangedRelationships(): Boolean = changeCount > 0L

    fun set(from: EntityId, to: EntityId, relationship: Relationship): RelationshipGraph {
        return copy(
            edges = edges + (key(from, to) to relationship),
            changeCount = changeCount + 1L
        )
    }

    fun modify(
        from: EntityId,
        to: EntityId,
        deltaTrust: Float = 0f,
        deltaAffection: Float = 0f,
        deltaRespect: Float = 0f,
        deltaFear: Float = 0f,
        deltaResentment: Float = 0f,
        event: EventId? = null
    ): RelationshipGraph {
        val current = get(from, to)
        val updated = current.shifted(deltaTrust, deltaAffection, deltaRespect, deltaFear, deltaResentment, event)
        return set(from, to, updated)
    }

    fun shift(
        source: EntityId,
        target: EntityId,
        deltaTrust: Float = 0f,
        deltaAffection: Float = 0f,
        deltaRespect: Float = 0f,
        deltaFear: Float = 0f,
        deltaResentment: Float = 0f,
        event: EventId? = null
    ): RelationshipGraph = modify(
        from = source,
        to = target,
        deltaTrust = deltaTrust,
        deltaAffection = deltaAffection,
        deltaRespect = deltaRespect,
        deltaFear = deltaFear,
        deltaResentment = deltaResentment,
        event = event
    )

    fun remove(entity: EntityId): RelationshipGraph {
        val id = entity.typedId()
        val prefix = "$id->"
        val suffix = "->$id"
        val filtered = edges.filterKeys { !it.startsWith(prefix) && !it.endsWith(suffix) }
        return copy(
            edges = filtered,
            changeCount = changeCount + 1L
        )
    }

    fun allEdges(): Map<String, Relationship> = edges

    /** Replaces the edges wholesale (used by the save migration that re-types old keys). */
    fun withEdges(newEdges: Map<String, Relationship>): RelationshipGraph =
        copy(edges = newEdges, changeCount = changeCount + 1L)
}
