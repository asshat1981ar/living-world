package org.livingworld.core.memory

import kotlinx.serialization.Serializable
import org.livingworld.core.beliefs.Proposition
import org.livingworld.core.ids.EntityId
import org.livingworld.core.ids.EventId
import org.livingworld.core.time.WorldDuration
import org.livingworld.core.time.WorldTime

@Serializable
enum class RelationshipExperience {
    KINDNESS,
    BETRAYAL,
    COOPERATION,
    ASSAULT,
    THEFT,
    TRADE,
    SHARED_DANGER
}

@Serializable
sealed interface Memory {
    val importance: Float
    val createdAt: WorldTime

    @Serializable
    data class Episodic(
        val eventId: EventId,
        override val importance: Float,
        override val createdAt: WorldTime
    ) : Memory

    @Serializable
    data class Relationship(
        val entity: EntityId,
        val experience: RelationshipExperience,
        override val importance: Float,
        override val createdAt: WorldTime
    ) : Memory

    @Serializable
    data class Semantic(
        val proposition: Proposition,
        override val importance: Float,
        override val createdAt: WorldTime
    ) : Memory
}

@Serializable
data class MemoryStore(
    private val entries: List<Memory> = emptyList()
) {
    val size: Int get() = entries.size

    fun all(): List<Memory> = entries

    fun add(memory: Memory): MemoryStore =
        copy(entries = entries + memory)

    fun hasEpisodicMemory(eventId: EventId): Boolean =
        entries.any { it is Memory.Episodic && it.eventId == eventId }

    fun memoriesOf(entity: EntityId): List<Memory> =
        entries.filter { it is Memory.Relationship && it.entity == entity }
}

data class MemoryContext(
    val currentTime: WorldTime,
    val focalEntities: Set<EntityId> = emptySet(),
    val focalPropositions: Set<Proposition> = emptySet()
)

interface MemoryRetriever {
    fun retrieve(
        memories: MemoryStore,
        context: MemoryContext,
        limit: Int
    ): List<Memory>
}

class DefaultMemoryRetriever : MemoryRetriever {
    override fun retrieve(
        memories: MemoryStore,
        context: MemoryContext,
        limit: Int
    ): List<Memory> {
        return memories.all()
            .map { memory ->
                val ageTicks = (context.currentTime.tick - memory.createdAt.tick).coerceAtLeast(0L)
                val daysOld = ageTicks / WorldTime.TICKS_PER_DAY.toFloat()
                // Recency decay factor: half-life of 14 days
                val recencyScore = 1.0f / (1.0f + 0.05f * daysOld)

                var relevanceScore = 1.0f
                when (memory) {
                    is Memory.Relationship -> {
                        if (context.focalEntities.contains(memory.entity)) {
                            relevanceScore += 2.0f
                        }
                    }
                    is Memory.Semantic -> {
                        if (context.focalPropositions.contains(memory.proposition)) {
                            relevanceScore += 2.0f
                        }
                    }
                    is Memory.Episodic -> {
                        // Episodic importance
                    }
                }

                val score = memory.importance * recencyScore * relevanceScore
                Pair(memory, score)
            }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }
}
