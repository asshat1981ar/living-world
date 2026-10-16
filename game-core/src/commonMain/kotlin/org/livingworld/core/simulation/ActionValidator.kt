package org.livingworld.core.simulation

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import org.livingworld.core.beliefs.Belief
import org.livingworld.core.ids.*
import org.livingworld.core.npc.Condition
import org.livingworld.core.npc.ExpectedEffect
import org.livingworld.core.npc.Npc
import org.livingworld.core.npc.Risk
import org.livingworld.core.world.WorldState

@Serializable
sealed interface Intent {
    val description: String

    @Serializable
    data object Rest : Intent {
        override val description: String get() = "Rest to recover health and stamina"
    }

    @Serializable
    data class Work(val location: LocationId) : Intent {
        override val description: String get() = "Work at ${location.value}"
    }

    @Serializable
    data class Flee(val threat: EntityId) : Intent {
        override val description: String get() = "Flee from ${threat.identifier}"
    }

    @Serializable
    data class Socialize(val target: NpcId) : Intent {
        override val description: String get() = "Socialize with ${target.value}"
    }

    @Serializable
    data class ShareRumor(val target: NpcId, val belief: Belief) : Intent {
        override val description: String get() = "Share rumor with ${target.value}: ${belief.proposition.describe()}"
    }

    @Serializable
    data class Trade(val partner: EntityId, val item: ItemId, val price: Float) : Intent {
        override val description: String get() = "Trade ${item.value} with ${partner.identifier} for $price coins"
    }

    @Serializable
    data class ChangePrice(val item: ItemId, val newPrice: Float, val reason: String) : Intent {
        override val description: String get() = "Adjust price of ${item.value} to $newPrice ($reason)"
    }

    @Serializable
    data class ChangeAllegiance(val newFaction: FactionId, val reason: String) : Intent {
        override val description: String get() = "Switch allegiance to ${newFaction.value} ($reason)"
    }

    @Serializable
    data class Attack(val target: NpcId, val reason: String) : Intent {
        override val description: String get() = "Attack ${target.value} ($reason)"
    }

    @Serializable
    data class InheritShop(val deceased: NpcId, val shopName: String) : Intent {
        override val description: String get() = "Inherit shop $shopName from ${deceased.value}"
    }
}

data class ActionCandidate(
    val action: Intent,
    val requirements: List<Condition> = emptyList(),
    val risks: List<Risk> = emptyList(),
    val expectedEffects: List<ExpectedEffect> = emptyList()
) {
    fun estimatedUtility(): Float {
        val benefit = expectedEffects.sumOf { it.utilityDelta.toDouble() }.toFloat()
        val riskPenalty = risks.sumOf { it.expectedLoss.toDouble() }.toFloat()
        return benefit - riskPenalty
    }
}

data class AiProposal(
    val selectedAction: Intent,
    val confidence: Float,
    val explanation: String? = null
)

@JvmInline
value class ValidatedAction(val intent: Intent)

interface ActionValidator {
    fun validate(
        proposal: AiProposal,
        npc: Npc,
        world: WorldState
    ): Result<ValidatedAction>
}

class DefaultActionValidator : ActionValidator {

    override fun validate(
        proposal: AiProposal,
        npc: Npc,
        world: WorldState
    ): Result<ValidatedAction> {
        if (!npc.isAlive || world.isDead(npc.id)) {
            return Result.failure(IllegalStateException("Dead or unknown NPC cannot perform actions"))
        }
        if (proposal.confidence.isNaN() || proposal.confidence < 0f || proposal.confidence > 1.0f) {
            return Result.failure(IllegalArgumentException("Proposal confidence out of valid [0.0, 1.0] range"))
        }

        val action = proposal.selectedAction
        val error = when (action) {
            is Intent.Work -> validateWork(action, npc)
            is Intent.Trade -> validateTrade(action, npc, world)
            is Intent.Attack -> validateCoLocatedLivingTarget(action.target, npc, world, "attack")
            is Intent.Socialize -> validateCoLocatedLivingTarget(action.target, npc, world, "socialize")
            is Intent.ShareRumor -> validateShareRumor(action, npc, world)
            is Intent.ChangePrice -> validateChangePrice(action)
            is Intent.ChangeAllegiance -> validateChangeAllegiance(action, world)
            is Intent.InheritShop -> validateInheritShop(action, world)
            is Intent.Rest, is Intent.Flee -> null
        }

        return if (error != null) Result.failure(error) else Result.success(ValidatedAction(action))
    }

    private fun validateWork(action: Intent.Work, npc: Npc): Throwable? {
        if (action.location != npc.identity.currentLocation) {
            return IllegalStateException("Cannot work at ${action.location.value} while at ${npc.identity.currentLocation.value}")
        }
        return null
    }

    private fun validateTrade(action: Intent.Trade, npc: Npc, world: WorldState): Throwable? {
        if (action.partner == npc.id.toEntityId()) {
            return IllegalStateException("Cannot trade with self")
        }
        if (action.price.isNaN() || action.price <= 0f) {
            return IllegalStateException("Trade price must be positive")
        }
        return when (val partner = action.partner) {
            is EntityId.Player -> {
                if (world.player.currentLocation != npc.identity.currentLocation) {
                    IllegalStateException("Player is not at ${npc.identity.currentLocation.value}")
                } else if (world.player.coins < action.price) {
                    IllegalStateException("Buyer does not have enough coins (${world.player.coins} < ${action.price})")
                } else null
            }
            is EntityId.Npc -> {
                val buyerNpc = world.npcs[partner.id]
                if (buyerNpc == null || !buyerNpc.isAlive) {
                    IllegalStateException("Trade partner NPC is dead or missing")
                } else if (buyerNpc.identity.currentLocation != npc.identity.currentLocation) {
                    IllegalStateException("Trade partner NPC is not co-located")
                } else if (buyerNpc.resources.coins < action.price) {
                    IllegalStateException("Trade partner NPC lacks sufficient coins")
                } else null
            }
            else -> IllegalStateException("Invalid trade partner entity type")
        }
    }

    private fun validateCoLocatedLivingTarget(
        targetId: NpcId,
        npc: Npc,
        world: WorldState,
        verb: String
    ): Throwable? {
        if (targetId == npc.id) return IllegalStateException("Cannot $verb self")
        val target = world.npcs[targetId]
        if (target == null || !target.isAlive) return IllegalStateException("Target for $verb is dead or absent")
        if (target.identity.currentLocation != npc.identity.currentLocation) {
            return IllegalStateException("Target for $verb is not co-located")
        }
        return null
    }

    private fun validateShareRumor(action: Intent.ShareRumor, npc: Npc, world: WorldState): Throwable? {
        val targetErr = validateCoLocatedLivingTarget(action.target, npc, world, "share rumor with")
        if (targetErr != null) return targetErr
        if (!npc.beliefs.knows(action.belief.proposition, minConfidence = 0.1f)) {
            return IllegalStateException("NPC cannot share a rumor they do not hold in their BeliefStore (Invariant G2/G3)")
        }
        return null
    }

    private fun validateChangePrice(action: Intent.ChangePrice): Throwable? {
        if (action.newPrice.isNaN() || action.newPrice <= 0f || action.newPrice > 10_000f) {
            return IllegalStateException("Price must be positive and within bounded economy limits")
        }
        return null
    }

    private fun validateChangeAllegiance(action: Intent.ChangeAllegiance, world: WorldState): Throwable? {
        if (!world.factions.containsKey(action.newFaction)) {
            return IllegalStateException("Target faction ${action.newFaction.value} does not exist")
        }
        return null
    }

    private fun validateInheritShop(action: Intent.InheritShop, world: WorldState): Throwable? {
        val deceased = world.npcs[action.deceased]
        if (deceased == null || deceased.isAlive) {
            return IllegalStateException("Shop predecessor is not dead")
        }
        return null
    }
}
