package org.livingworld.core.events

import org.livingworld.core.beliefs.Proposition
import org.livingworld.core.ids.EventId
import org.livingworld.core.ids.NpcId

/**
 * Looks up the ledger event that a fact, quest or follow-up action stems from, so emitted events can carry a
 * `causalEventId` (Invariant G6). Every lookup returns the most recent matching event, or null when the fact has no
 * recorded origin; a missing link is never guessed.
 */
object CausalLinks {

    fun deathOf(ledger: EventLedger, victim: NpcId): EventId? =
        ledger.all().lastOrNull { it is GameEvent.NpcKilled && it.victim == victim }?.id

    /** The event that created what a belief is about, when the ledger holds one. */
    fun originOf(ledger: EventLedger, proposition: Proposition): EventId? = when (proposition) {
        is Proposition.EntityKilledNpc -> deathOf(ledger, proposition.victim)
        is Proposition.PlayerStole ->
            ledger.all().lastOrNull { it is GameEvent.ItemStolen && it.item == proposition.item }?.id
        is Proposition.EntityStoleItem ->
            ledger.all().lastOrNull {
                it is GameEvent.ItemStolen && it.item == proposition.item && it.thief == proposition.thief
            }?.id
        is Proposition.PriceChanged ->
            ledger.all().lastOrNull { it is GameEvent.PriceAdjusted && it.item == proposition.item }?.id
        is Proposition.ShopInherited ->
            ledger.all().lastOrNull { it is GameEvent.ShopInherited && it.heir == proposition.heir }?.id
        else -> null
    }

    /** The hostility declaration behind a `quest_war_<a>_<b>` opportunity (ids sorted, as the quest engine builds them). */
    fun warDeclarationFor(ledger: EventLedger, questId: String): EventId? =
        ledger.all().lastOrNull {
            it is GameEvent.FactionHostilityDeclared &&
                "quest_war_${minOf(it.declaringFaction.value, it.targetFaction.value)}_" +
                maxOf(it.declaringFaction.value, it.targetFaction.value) == questId
        }?.id

    /** The event behind a procedural quest: a murder or a war declaration. Scarcity has no single cause. */
    fun originOfQuest(ledger: EventLedger, questId: String): EventId? = when {
        questId.startsWith("quest_death_") -> deathOf(ledger, NpcId(questId.removePrefix("quest_death_")))
        questId.startsWith("quest_war_") -> warDeclarationFor(ledger, questId)
        else -> null
    }
}
