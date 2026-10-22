package org.livingworld.core.events

import org.livingworld.core.ids.LocationId

/** Where an event happened, when it names a place. */
fun GameEvent.location(): LocationId? = when (this) {
    is GameEvent.NpcKilled -> location
    is GameEvent.ItemStolen -> location
    is GameEvent.EntitiesMet -> location
    is GameEvent.NpcAttacked -> location
    is GameEvent.NpcRoutineCompleted -> location
    is GameEvent.OpportunityDispatched -> location
    else -> null
}

/**
 * Whether an event happens in the open, so that everyone standing there can see it. A killing, a theft and a duel
 * are not: the only people who know about them are the ones that were there (G2).
 */
fun GameEvent.isPublic(): Boolean = when (this) {
    is GameEvent.EntitiesMet,
    is GameEvent.PriceAdjusted,
    is GameEvent.OpportunityDispatched,
    is GameEvent.ShopInherited,
    is GameEvent.FactionHostilityDeclared -> true
    else -> false
}
