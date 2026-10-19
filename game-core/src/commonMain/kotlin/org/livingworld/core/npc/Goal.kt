package org.livingworld.core.npc

import kotlinx.serialization.Serializable
import org.livingworld.core.ids.*

@Serializable
sealed interface Condition {
    val description: String

    @Serializable
    data class HasCoins(val minAmount: Float) : Condition {
        override val description: String get() = "Has at least $minAmount coins"
    }

    @Serializable
    data class AtLocation(val location: LocationId) : Condition {
        override val description: String get() = "At location ${location.value}"
    }

    @Serializable
    data class TargetIsAlive(val target: NpcId) : Condition {
        override val description: String get() = "Target ${target.value} is alive"
    }

    @Serializable
    data class MinimumTrust(val target: EntityId, val minTrust: Float) : Condition {
        override val description: String get() = "Trust in ${target.identifier} >= $minTrust"
    }

    @Serializable
    data class IsMemberOfFaction(val faction: FactionId) : Condition {
        override val description: String get() = "Is member of ${faction.value}"
    }

    @Serializable
    data class CustomCondition(val key: String, override val description: String) : Condition
}

@Serializable
data class Risk(
    val description: String,
    val probability: Float, // 0.0 to 1.0
    val severity: Float     // 0.0 to 1.0
) {
    val expectedLoss: Float get() = probability * severity
}

@Serializable
data class ExpectedEffect(
    val description: String,
    val utilityDelta: Float
)

@Serializable
data class Goal(
    val id: String,
    val description: String,
    val priority: Float, // 0.0 to 1.0 (inherent importance)
    val urgency: Float,  // 0.0 to 1.0 (temporal pressure)
    val conditions: List<Condition> = emptyList(),
    val successConditions: List<Condition> = emptyList(),
    val failureConditions: List<Condition> = emptyList()
) {
    val effectiveScore: Float get() = priority * (0.6f + 0.4f * urgency)
}
