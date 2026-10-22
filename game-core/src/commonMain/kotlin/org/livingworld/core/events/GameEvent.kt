package org.livingworld.core.events

import kotlinx.serialization.Serializable
import org.livingworld.core.beliefs.Belief
import org.livingworld.core.ids.*
import org.livingworld.core.npc.Npc
import org.livingworld.core.npc.Trait
import org.livingworld.core.simulation.AdviceOutcome
import org.livingworld.core.time.WorldTime

/**
 * Authoritative tactical combat stances in game-core (zero UI/AI imports per Invariant G1/G9).
 * Bridges cleanly to SpriteManifest's CombatStance (STRIKE, WARD, FEINT).
 */
@Serializable
enum class TacticalStance {
    STRIKE,
    WARD,
    FEINT
}

/**
 * Outcome of a single tactical duel exchange:
 * - STRIKE beats FEINT (Counter-Strike: 1.35x damage)
 * - FEINT beats WARD (Guard Break: 1.35x damage)
 * - WARD beats STRIKE (Parry & Mitigate: 0.50x damage)
 * - Mirror match: Standard Exchange (1.0x damage)
 */
@Serializable
enum class DuelOutcome(val damageMultiplier: Float, val description: String) {
    COUNTER_STRIKE(1.35f, "Strike intercepted Feint for critical counter damage"),
    GUARD_BREAK(1.35f, "Feint bypassed Ward defense for unmitigated strike"),
    PARRIED(0.50f, "Ward deflected Strike and halved incoming damage"),
    STANDARD_EXCHANGE(1.00f, "Matched stances resulted in standard exchange")
}

@Serializable
data class DuelResolution(
    val attackerStance: TacticalStance,
    val defenderStance: TacticalStance,
    val outcome: DuelOutcome,
    val baseDamage: Float,
    val finalDamage: Float,
    val attackerWounded: Boolean,
    val defenderWounded: Boolean
)

/**
 * Pure deterministic tactical combat resolver (Invariant G8: strict bit-for-bit determinism).
 */
object TacticalCombatResolver {

    const val WOUNDED_HEALTH_THRESHOLD = 35f

    fun selectStance(npc: Npc, time: WorldTime, isAttacker: Boolean): TacticalStance {
        if (npc.resources.health < WOUNDED_HEALTH_THRESHOLD && !npc.hasTrait(Trait.VENGEFUL)) {
            return TacticalStance.WARD
        }
        if (npc.hasTrait(Trait.OPPORTUNISTIC) || npc.hasTrait(Trait.AMBITIOUS)) {
            return if (time.tick % 3L == 0L) TacticalStance.STRIKE else TacticalStance.FEINT
        }
        if (npc.hasTrait(Trait.BRAVE) || npc.hasTrait(Trait.VENGEFUL)) {
            return if (!isAttacker && time.tick % 4L == 0L) TacticalStance.WARD else TacticalStance.STRIKE
        }
        if (npc.hasTrait(Trait.COWARDLY) || npc.hasTrait(Trait.LOYAL)) {
            return TacticalStance.WARD
        }
        val hash = ((stableStringHash(npc.id.value) and 0x7FFFFFFFL) + time.tick) % 3L
        return when (hash) {
            0L -> TacticalStance.STRIKE
            1L -> TacticalStance.WARD
            else -> TacticalStance.FEINT
        }
    }

    /** The `31 * h + c` String hash with 32-bit wraparound, spelled out so it never depends on a platform hashCode(). */
    private fun stableStringHash(value: String): Long {
        var h = 0
        for (ch in value) h = 31 * h + ch.code
        return h.toLong()
    }

    /**
     * Evaluates the rock-paper-scissors stance interaction matrix (RT-11 hardened).
     */
    fun evaluateMatrix(attacker: TacticalStance, defender: TacticalStance): DuelOutcome {
        if (attacker == defender) return DuelOutcome.STANDARD_EXCHANGE
        return when (attacker) {
            TacticalStance.STRIKE -> if (defender == TacticalStance.FEINT) DuelOutcome.COUNTER_STRIKE else DuelOutcome.PARRIED
            TacticalStance.FEINT -> if (defender == TacticalStance.WARD) DuelOutcome.GUARD_BREAK else DuelOutcome.PARRIED
            TacticalStance.WARD -> if (defender == TacticalStance.STRIKE) DuelOutcome.PARRIED else DuelOutcome.GUARD_BREAK
        }
    }

    fun resolveExchange(
        attacker: Npc,
        defender: Npc,
        time: WorldTime,
        overrideAttackerStance: TacticalStance? = null,
        overrideDefenderStance: TacticalStance? = null
    ): DuelResolution {
        val atkStance = overrideAttackerStance ?: selectStance(attacker, time, isAttacker = true)
        val defStance = overrideDefenderStance ?: selectStance(defender, time, isAttacker = false)
        val outcome = evaluateMatrix(atkStance, defStance)

        val traitBase = when {
            attacker.hasTrait(Trait.BRAVE) -> 35f
            attacker.hasTrait(Trait.VENGEFUL) -> 30f
            attacker.hasTrait(Trait.COWARDLY) -> 18f
            else -> 25f
        }
        val weaponBonus = if (attacker.resources.items.any { it.value.contains("sword") }) 10f else 0f
        val baseDamage = traitBase + weaponBonus

        val finalDamage = (baseDamage * outcome.damageMultiplier).coerceAtLeast(1f)
        val postDefenderHealth = (defender.resources.health - finalDamage).coerceAtLeast(0f)

        return DuelResolution(
            attackerStance = atkStance,
            defenderStance = defStance,
            outcome = outcome,
            baseDamage = baseDamage,
            finalDamage = finalDamage,
            attackerWounded = attacker.resources.health < WOUNDED_HEALTH_THRESHOLD,
            defenderWounded = postDefenderHealth < WOUNDED_HEALTH_THRESHOLD
        )
    }
}

@Serializable
sealed interface GameEvent {
    val id: EventId
    val time: WorldTime
    val actors: List<EntityId>
    val witnesses: List<NpcId>
    val causalEventId: EventId?
    val summary: String

    @Serializable
    data class NpcKilled(
        override val id: EventId,
        override val time: WorldTime,
        val victim: NpcId,
        val killer: EntityId?,
        override val witnesses: List<NpcId>,
        override val causalEventId: EventId? = null,
        val location: LocationId? = null,
        val motive: String? = null
    ) : GameEvent {
        override val actors: List<EntityId> get() = listOfNotNull(killer, victim.toEntityId())
        override val summary: String get() = "NPC ${victim.value} was killed by ${killer?.identifier ?: "unknown"}"
    }

    @Serializable
    data class ItemStolen(
        override val id: EventId,
        override val time: WorldTime,
        val thief: EntityId,
        val item: ItemId,
        val owner: EntityId?,
        override val witnesses: List<NpcId>,
        override val causalEventId: EventId? = null,
        val location: LocationId? = null
    ) : GameEvent {
        override val actors: List<EntityId> get() = listOfNotNull(thief, owner)
        override val summary: String get() = "Item ${item.value} stolen by ${thief.identifier} from ${owner?.identifier ?: "unknown"}"
    }

    @Serializable
    data class EntitiesMet(
        override val id: EventId,
        override val time: WorldTime,
        val entityA: EntityId,
        val entityB: EntityId,
        val location: LocationId,
        override val witnesses: List<NpcId> = emptyList(),
        override val causalEventId: EventId? = null
    ) : GameEvent {
        override val actors: List<EntityId> get() = listOf(entityA, entityB)
        override val summary: String get() = "${entityA.identifier} met ${entityB.identifier} at ${location.value}"
    }

    @Serializable
    data class RelationshipShifted(
        override val id: EventId,
        override val time: WorldTime,
        val source: EntityId,
        val target: EntityId,
        val deltaTrust: Float,
        val deltaAffection: Float,
        val deltaRespect: Float,
        val deltaFear: Float,
        val deltaResentment: Float,
        val reason: String,
        override val witnesses: List<NpcId> = emptyList(),
        override val causalEventId: EventId? = null
    ) : GameEvent {
        override val actors: List<EntityId> get() = listOf(source, target)
        override val summary: String get() = "Relationship shifted between ${source.identifier} and ${target.identifier}: $reason"
    }

    @Serializable
    data class RumorTransmitted(
        override val id: EventId,
        override val time: WorldTime,
        val source: NpcId,
        val target: NpcId,
        val transmittedBelief: Belief,
        val distortion: Float,
        override val witnesses: List<NpcId> = emptyList(),
        override val causalEventId: EventId? = null
    ) : GameEvent {
        override val actors: List<EntityId> get() = listOf(source.toEntityId(), target.toEntityId())
        override val summary: String get() = "${source.value} shared rumor with ${target.value}: ${transmittedBelief.summary}"
    }

    @Serializable
    data class TradeCompleted(
        override val id: EventId,
        override val time: WorldTime,
        val buyer: EntityId,
        val seller: EntityId,
        val item: ItemId,
        val price: Float,
        override val witnesses: List<NpcId> = emptyList(),
        override val causalEventId: EventId? = null
    ) : GameEvent {
        override val actors: List<EntityId> get() = listOf(buyer, seller)
        override val summary: String get() = "${buyer.identifier} bought ${item.value} from ${seller.identifier} for $price coins"
    }

    /** A hungry worker buys food from a producer; [tax] of the [price] goes to the village treasury. */
    /** P9: what an advisory AI chose for [npc], or why the kernel fell back. Changes no state; lets the run be repeated. */
    @Serializable
    data class AiAdviceRecorded(
        override val id: EventId,
        override val time: WorldTime,
        val npc: NpcId,
        val source: String,
        val outcome: AdviceOutcome,
        val candidateIndex: Int? = null,
        val explanation: String? = null,
        override val witnesses: List<NpcId> = emptyList(),
        override val causalEventId: EventId? = null
    ) : GameEvent {
        override val actors: List<EntityId> get() = listOf(npc.toEntityId())
        override val summary: String get() = "${npc.value} weighed advice from $source: $outcome" + (candidateIndex?.let { " (candidate $it)" } ?: "")
    }

    @Serializable
    data class RationPurchased(
        override val id: EventId,
        override val time: WorldTime,
        val buyer: NpcId,
        val seller: NpcId,
        val food: Float,
        val price: Float,
        val tax: Float,
        override val witnesses: List<NpcId> = emptyList(),
        override val causalEventId: EventId? = null
    ) : GameEvent {
        override val actors: List<EntityId> get() = listOf(buyer.toEntityId(), seller.toEntityId())
        override val summary: String get() = "${buyer.value} bought $food food from ${seller.value} for $price coins"
    }

    @Serializable
    data class AllegianceChanged(
        override val id: EventId,
        override val time: WorldTime,
        val npc: NpcId,
        val oldFaction: FactionId?,
        val newFaction: FactionId,
        val reason: String,
        override val witnesses: List<NpcId> = emptyList(),
        override val causalEventId: EventId? = null
    ) : GameEvent {
        override val actors: List<EntityId> get() = listOf(npc.toEntityId(), newFaction.toEntityId())
        override val summary: String get() = "${npc.value} changed allegiance to ${newFaction.value}: $reason"
    }

    @Serializable
    data class NpcAttacked(
        override val id: EventId,
        override val time: WorldTime,
        val attacker: EntityId,
        val target: NpcId,
        val damage: Float,
        val location: LocationId,
        override val witnesses: List<NpcId> = emptyList(),
        override val causalEventId: EventId? = null,
        val attackerStance: TacticalStance = TacticalStance.STRIKE,
        val defenderStance: TacticalStance = TacticalStance.WARD,
        val duelOutcome: DuelOutcome = DuelOutcome.STANDARD_EXCHANGE
    ) : GameEvent {
        override val actors: List<EntityId> get() = listOf(attacker, target.toEntityId())
        override val summary: String
            get() = "${attacker.identifier} [${attackerStance.name}] attacked ${target.value} [${defenderStance.name}] -> ${duelOutcome.name} ($damage dmg) at ${location.value}"
    }

    @Serializable
    data class PriceAdjusted(
        override val id: EventId,
        override val time: WorldTime,
        val merchant: NpcId,
        val item: ItemId,
        val oldPrice: Float,
        val newPrice: Float,
        val reason: String,
        override val witnesses: List<NpcId> = emptyList(),
        override val causalEventId: EventId? = null
    ) : GameEvent {
        override val actors: List<EntityId> get() = listOf(merchant.toEntityId())
        override val summary: String get() = "${merchant.value} adjusted price of ${item.value} from $oldPrice to $newPrice ($reason)"
    }

    @Serializable
    data class ShopInherited(
        override val id: EventId,
        override val time: WorldTime,
        val heir: NpcId,
        val predecessor: NpcId,
        val shopName: String,
        override val witnesses: List<NpcId> = emptyList(),
        override val causalEventId: EventId? = null
    ) : GameEvent {
        override val actors: List<EntityId> get() = listOf(heir.toEntityId(), predecessor.toEntityId())
        override val summary: String get() = "${heir.value} inherited $shopName from ${predecessor.value}"
    }

    @Serializable
    data class FactionHostilityDeclared(
        override val id: EventId,
        override val time: WorldTime,
        val declaringFaction: FactionId,
        val targetFaction: FactionId,
        val reason: String,
        override val witnesses: List<NpcId> = emptyList(),
        override val causalEventId: EventId? = null
    ) : GameEvent {
        override val actors: List<EntityId> get() = listOf(declaringFaction.toEntityId(), targetFaction.toEntityId())
        override val summary: String get() = "Faction ${declaringFaction.value} declared hostility toward ${targetFaction.value}: $reason"
    }

    @Serializable
    data class PlayerAttacked(
        override val id: EventId,
        override val time: WorldTime,
        val attacker: NpcId,
        val damage: Float,
        val location: LocationId,
        override val witnesses: List<NpcId> = emptyList(),
        override val causalEventId: EventId? = null
    ) : GameEvent {
        override val actors: List<EntityId> get() = listOf(attacker.toEntityId(), EntityId.Player("player"))
        override val summary: String get() = "The player was struck by ${attacker.value} for $damage"
    }

    @Serializable
    data class PlayerMoved(
        override val id: EventId,
        override val time: WorldTime,
        val from: LocationId,
        val to: LocationId,
        override val witnesses: List<NpcId> = emptyList(),
        override val causalEventId: EventId? = null
    ) : GameEvent {
        override val actors: List<EntityId> get() = listOf(EntityId.Player("player"))
        override val summary: String get() = "The player walked from ${from.value} to ${to.value}"
    }

    @Serializable
    data class NpcRoutineCompleted(
        override val id: EventId,
        override val time: WorldTime,
        val npc: NpcId,
        val routineType: String,
        val location: LocationId,
        val deltaHealth: Float = 0f,
        val deltaCoins: Float = 0f,
        val deltaFood: Float = 0f,
        val deltaFear: Float = 0f,
        override val witnesses: List<NpcId> = emptyList(),
        override val causalEventId: EventId? = null
    ) : GameEvent {
        override val actors: List<EntityId> get() = listOf(npc.toEntityId())
        override val summary: String get() = "${npc.value} completed routine ($routineType) at ${location.value}"
    }

    @Serializable
    data class OpportunityDispatched(
        override val id: EventId,
        override val time: WorldTime,
        val opportunityId: String,
        val opportunityType: String,
        val title: String,
        val location: LocationId,
        val urgency: Float,
        val narrativeHook: String,
        override val actors: List<EntityId> = emptyList(),
        override val witnesses: List<NpcId> = emptyList(),
        override val causalEventId: EventId? = null
    ) : GameEvent {
        override val summary: String get() = "Progression Opportunity Dispatched: $title at ${location.value} (Urgency: $urgency)"
    }
}
