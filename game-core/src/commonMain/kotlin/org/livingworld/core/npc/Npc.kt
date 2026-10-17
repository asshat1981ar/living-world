package org.livingworld.core.npc

import kotlinx.serialization.Serializable
import org.livingworld.core.beliefs.BeliefStore
import org.livingworld.core.ids.*
import org.livingworld.core.memory.MemoryStore
import org.livingworld.core.relationships.RelationshipGraph

@Serializable
data class Identity(
    val name: String,
    val occupation: String,
    val homeLocation: LocationId,
    val currentLocation: LocationId,
    val isAlive: Boolean = true
)

@Serializable
enum class Trait {
    GREEDY,
    HONEST,
    COWARDLY,
    BRAVE,
    VENGEFUL,
    COMPASSIONATE,
    SKEPTICAL,
    AMBITIOUS,
    LOYAL,
    OPPORTUNISTIC
}

@Serializable
data class Fear(
    val description: String,
    val intensity: Float = 0.5f
)

@Serializable
data class Value(
    val name: String,
    val weight: Float = 0.5f // -1.0 to 1.0
)

@Serializable
data class EmotionalState(
    val fear: Float = 0.0f,
    val anger: Float = 0.0f,
    val trust: Float = 0.0f,
    val grief: Float = 0.0f,
    val hope: Float = 0.5f,
    val guilt: Float = 0.0f,
    val suspicion: Float = 0.0f,
    val pride: Float = 0.0f
) {
    fun shifted(
        deltaFear: Float = 0f,
        deltaAnger: Float = 0f,
        deltaTrust: Float = 0f,
        deltaGrief: Float = 0f,
        deltaHope: Float = 0f,
        deltaGuilt: Float = 0f,
        deltaSuspicion: Float = 0f,
        deltaPride: Float = 0f
    ): EmotionalState = copy(
        fear = (fear + deltaFear).coerceIn(0f, 1f),
        anger = (anger + deltaAnger).coerceIn(0f, 1f),
        trust = (trust + deltaTrust).coerceIn(0f, 1f),
        grief = (grief + deltaGrief).coerceIn(0f, 1f),
        hope = (hope + deltaHope).coerceIn(0f, 1f),
        guilt = (guilt + deltaGuilt).coerceIn(0f, 1f),
        suspicion = (suspicion + deltaSuspicion).coerceIn(0f, 1f),
        pride = (pride + deltaPride).coerceIn(0f, 1f)
    )

    companion object {
        val NEUTRAL = EmotionalState()
    }
}

@Serializable
data class ResourceState(
    val coins: Float = 50f,
    val food: Float = 10f,
    val health: Float = 100f,
    val items: List<ItemId> = emptyList()
) {
    val isStarving: Boolean get() = food <= 0f
    val isDead: Boolean get() = health <= 0f
}

@Serializable
data class Plan(
    val id: String,
    val targetGoalId: String,
    val steps: List<String>,
    val currentStepIndex: Int = 0
) {
    val isCompleted: Boolean get() = currentStepIndex >= steps.size
}

@Serializable
data class Npc(
    val id: NpcId,
    val identity: Identity,
    val traits: List<Trait> = emptyList(),
    val goals: List<Goal> = emptyList(),
    val fears: List<Fear> = emptyList(),
    val values: List<Value> = emptyList(),
    val memories: MemoryStore = MemoryStore(),
    val beliefs: BeliefStore = BeliefStore(),
    val factions: Set<FactionId> = emptySet(),
    val emotionalState: EmotionalState = EmotionalState.NEUTRAL,
    val resources: ResourceState = ResourceState(),
    val currentPlan: Plan? = null
) {
    val isAlive: Boolean get() = identity.isAlive && !resources.isDead

    fun reputationToward(target: EntityId, relationships: RelationshipGraph): Float {
        val rel = relationships.get(id.toEntityId(), target)
        var rep = rel.trust * 0.4f + rel.respect * 0.3f + rel.affection * 0.3f - rel.resentment * 0.4f
        if (traits.contains(Trait.SKEPTICAL)) {
            rep -= 0.15f
        }
        return rep.coerceIn(-1.0f, 1.0f)
    }

    fun hasTrait(trait: Trait): Boolean = traits.contains(trait)
}
