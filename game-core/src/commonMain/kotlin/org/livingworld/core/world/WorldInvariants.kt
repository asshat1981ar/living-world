package org.livingworld.core.world

import org.livingworld.core.events.GameEvent
import org.livingworld.core.ids.EntityId

/**
 * The rules a world must satisfy at rest. They are the contract the save gate and the tests check, and the
 * contract the reducers keep: a dead NPC has no health, the ledger is strictly ordered, every NPC an event names
 * exists, a quest has a living issuer, and nothing holds a negative amount.
 *
 * Coin conservation cannot be checked here (it needs the world's starting total), so it lives in the
 * characterisation test over a run.
 */
object WorldInvariants {

    /** Everything that is wrong with this world (empty when it is sound). */
    fun violations(state: WorldState): List<String> {
        val out = mutableListOf<String>()

        for ((id, npc) in state.npcs) {
            if (!npc.identity.isAlive && npc.resources.health > 0f) {
                out += "deceased NPC ${id.value} still has ${npc.resources.health} health"
            }
            if (npc.identity.isAlive && npc.resources.health <= 0f) {
                out += "NPC ${id.value} is alive with no health"
            }
            if (npc.resources.coins < 0f || npc.resources.food < 0f) {
                out += "NPC ${id.value} holds a negative amount"
            }
        }

        var last = Long.MIN_VALUE
        for (event in state.events.all()) {
            if (event.id.value <= last) {
                out += "event id ${event.id.value} is not after the previous one"
            }
            last = event.id.value
            for (reference in npcReferences(event)) {
                if (!state.npcs.containsKey(reference)) {
                    out += "event ${event.id.value} names the unknown NPC ${reference.value}"
                }
            }
        }

        for (quest in state.activeQuests) {
            val issuer = quest.issuer
            if (issuer is EntityId.Npc && !state.npcs.containsKey(issuer.id)) {
                out += "quest ${quest.id} is issued by the unknown NPC ${issuer.identifier}"
            }
        }

        if (state.economy.treasury < 0f) {
            out += "the treasury holds ${state.economy.treasury}"
        }
        return out
    }

    /** The NPCs an event names, in the order the event lists them. */
    private fun npcReferences(event: GameEvent): List<org.livingworld.core.ids.NpcId> = when (event) {
        is GameEvent.NpcKilled -> listOf(event.victim) + listOfNotNull((event.killer as? EntityId.Npc)?.id)
        is GameEvent.ItemStolen ->
            listOfNotNull((event.thief as? EntityId.Npc)?.id) + listOfNotNull((event.owner as? EntityId.Npc)?.id)
        is GameEvent.EntitiesMet ->
            listOfNotNull((event.entityA as? EntityId.Npc)?.id) + listOfNotNull((event.entityB as? EntityId.Npc)?.id)
        is GameEvent.RelationshipShifted ->
            listOfNotNull((event.source as? EntityId.Npc)?.id) + listOfNotNull((event.target as? EntityId.Npc)?.id)
        is GameEvent.RumorTransmitted -> listOf(event.source, event.target)
        is GameEvent.TradeCompleted ->
            listOfNotNull((event.buyer as? EntityId.Npc)?.id) + listOfNotNull((event.seller as? EntityId.Npc)?.id)
        is GameEvent.RationPurchased -> listOf(event.buyer, event.seller)
        is GameEvent.NpcAttacked -> listOfNotNull((event.attacker as? EntityId.Npc)?.id) + event.target
        is GameEvent.PriceAdjusted -> listOf(event.merchant)
        is GameEvent.ShopInherited -> listOf(event.heir)
        is GameEvent.AllegianceChanged -> listOf(event.npc)
        is GameEvent.NpcRoutineCompleted -> listOf(event.npc)
        is GameEvent.AiAdviceRecorded -> listOf(event.npc)
        is GameEvent.OpportunityDispatched -> event.actors.filterIsInstance<EntityId.Npc>().map { it.id }
        is GameEvent.PlayerMoved -> emptyList()
        is GameEvent.FactionHostilityDeclared -> emptyList()
    } + event.witnesses
}
