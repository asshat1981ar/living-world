package org.livingworld.core.beliefs

import kotlinx.serialization.Serializable
import org.livingworld.core.ids.*
import org.livingworld.core.npc.Trait
import org.livingworld.core.time.WorldTime

@Serializable
sealed interface Proposition {
    fun describe(): String

    @Serializable
    data class PlayerStole(val item: ItemId) : Proposition {
        override fun describe(): String = "PlayerStole(${item.value})"
    }

    @Serializable
    data class EntityStoleItem(val thief: EntityId, val item: ItemId) : Proposition {
        override fun describe(): String = "${thief.identifier} stole item ${item.value}"
    }

    @Serializable
    data class EntityKilledNpc(val killer: EntityId, val victim: NpcId) : Proposition {
        override fun describe(): String = "${killer.identifier} killed ${victim.value}"
    }

    @Serializable
    data class EntityIsHostile(val entity: EntityId) : Proposition {
        override fun describe(): String = "${entity.identifier} is hostile"
    }

    @Serializable
    data class HasTrait(val entity: EntityId, val trait: Trait) : Proposition {
        override fun describe(): String = "${entity.identifier} has trait ${trait.name}"
    }

    @Serializable
    data class NpcLoyalToFaction(val npc: NpcId, val faction: FactionId) : Proposition {
        override fun describe(): String = "${npc.value} is loyal to faction ${faction.value}"
    }

    @Serializable
    data class ItemPrice(val item: ItemId, val price: Float) : Proposition {
        override fun describe(): String = "Price of ${item.value} is $price"
    }

    @Serializable
    data class PriceChanged(val item: ItemId, val oldPrice: Float, val newPrice: Float) : Proposition {
        override fun describe(): String = "Price of ${item.value} changed from $oldPrice to $newPrice"
    }

    @Serializable
    data class ShopInherited(val heir: NpcId, val shopName: String) : Proposition {
        override fun describe(): String = "${heir.value} inherited $shopName"
    }

    @Serializable
    data class GeneralFact(val key: String, val detail: String) : Proposition {
        override fun describe(): String = "[$key] $detail"
    }
}

@Serializable
sealed interface KnowledgeSource {
    @Serializable
    data object DirectObservation : KnowledgeSource

    @Serializable
    data class Observation(val eventId: EventId? = null) : KnowledgeSource

    @Serializable
    data class Hearsay(val sourceNpc: NpcId) : KnowledgeSource

    @Serializable
    data object Deduction : KnowledgeSource

    @Serializable
    data class FactionReport(val faction: FactionId) : KnowledgeSource

    companion object {
        fun Rumor(sourceNpc: NpcId): KnowledgeSource = Hearsay(sourceNpc)
    }
}

@Serializable
data class Belief(
    val proposition: Proposition,
    val confidence: Float,
    val source: KnowledgeSource,
    val reliability: Float,
    val acquiredAt: WorldTime
) {
    val summary: String get() = proposition.describe()
}

@Serializable
data class BeliefStore(
    private val beliefs: Map<String, Belief> = emptyMap()
) {
    fun all(): Collection<Belief> = beliefs.values

    fun get(proposition: Proposition): Belief? = beliefs[proposition.describe()]

    fun knows(proposition: Proposition, minConfidence: Float = 0.5f): Boolean {
        val b = get(proposition) ?: return false
        return b.confidence >= minConfidence
    }

    fun add(belief: Belief): BeliefStore = addOrUpdate(belief)

    fun addOrUpdate(belief: Belief): BeliefStore {
        val key = belief.proposition.describe()
        val existing = beliefs[key]
        val clampedConfidence = if (belief.confidence.isNaN()) 0f else belief.confidence.coerceIn(0f, 1f)
        val clampedReliability = if (belief.reliability.isNaN()) 0f else belief.reliability.coerceIn(0f, 1f)
        val sanitizedBelief = belief.copy(confidence = clampedConfidence, reliability = clampedReliability)

        val updated = if (existing == null) {
            sanitizedBelief
        } else {
            val combinedConfidence = (existing.confidence * existing.reliability + sanitizedBelief.confidence * sanitizedBelief.reliability) /
                    (existing.reliability + sanitizedBelief.reliability).coerceAtLeast(0.01f)
            existing.copy(
                confidence = combinedConfidence.coerceIn(0f, 1f),
                reliability = maxOf(existing.reliability, sanitizedBelief.reliability),
                source = sanitizedBelief.source,
                acquiredAt = maxOf(existing.acquiredAt, sanitizedBelief.acquiredAt)
            )
        }
        return copy(beliefs = beliefs + (key to updated))
    }
}
