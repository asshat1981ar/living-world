package org.livingworld.core.events

import org.livingworld.core.beliefs.Proposition
import org.livingworld.core.ids.EventId
import org.livingworld.core.ids.NpcId
import org.livingworld.core.time.WorldTime

/**
 * Looks up the ledger event that a fact, quest or follow-up action stems from, so emitted events can carry a
 * `causalEventId` (Invariant G6). Every lookup returns the most recent matching event, or null when the fact has no
 * recorded origin; a missing link is never guessed.
 */
object CausalLinks {

    fun deathOf(ledger: EventLedger, victim: NpcId, at: WorldTime? = null): EventId? =
        ledger.all()
            .lastOrNull { it is GameEvent.NpcKilled && it.victim == victim && (at == null || it.time <= at) }
            ?.id

    /** The event that created what a belief is about, when the ledger holds one. */
    fun originOf(ledger: EventLedger, proposition: Proposition, at: WorldTime? = null): EventId? {
        // A cause can only be in the past: the latest matching event that is not in the future (G6).
        val past = ledger.all().filter { at == null || it.time <= at }
        return when (proposition) {
            is Proposition.EntityKilledNpc -> deathOf(ledger, proposition.victim, at)
            is Proposition.NpcDied -> deathOf(ledger, proposition.victim, at)
            is Proposition.PlayerStole ->
                past.lastOrNull { it is GameEvent.ItemStolen && it.item == proposition.item }?.id
            is Proposition.EntityStoleItem ->
                past.lastOrNull {
                    it is GameEvent.ItemStolen && it.item == proposition.item && it.thief == proposition.thief
                }?.id
            is Proposition.PriceChanged ->
                past.lastOrNull { it is GameEvent.PriceAdjusted && it.item == proposition.item }?.id
            is Proposition.ShopInherited ->
                past.lastOrNull { it is GameEvent.ShopInherited && it.heir == proposition.heir }?.id
            else -> null
        }
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
