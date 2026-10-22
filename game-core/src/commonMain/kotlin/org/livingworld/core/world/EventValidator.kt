package org.livingworld.core.world

import org.livingworld.core.events.GameEvent
import org.livingworld.core.ids.EntityId
import org.livingworld.core.ids.ItemId
import org.livingworld.core.ids.NpcId

/**
 * What an event needs to be true before the reducer applies it: the entities it names must exist, the dead must not
 * act, and a trade needs a seller that holds the item and a buyer that can pay.
 *
 * The rules live here so the reducer, the save gate and the tests all ask the same question. An event that fails is not
 * recorded at all: the ledger must not contain a lie.
 */
object EventValidator {

    /** Why the event cannot be applied to this world (empty when it can). */
    fun reasons(state: WorldState, event: GameEvent): List<String> {
        val out = mutableListOf<String>()
        when (event) {
            is GameEvent.NpcKilled -> {
                if (state.npcs[event.victim] == null) out += "the victim ${event.victim.value} is not in the world"
                event.killer?.let { killer ->
                    if (killer is EntityId.Npc && state.npcs[killer.id] == null) {
                        out += "the killer ${killer.identifier} is not in the world"
                    }
                }
            }
            is GameEvent.ItemStolen -> {
                if (!exists(state, event.thief)) out += "the thief ${event.thief.identifier} is not in the world"
                val owner = event.owner
                if (owner == null) {
                    out += "the theft has no owner"
                } else {
                    if (!exists(state, owner)) out += "the owner ${owner.identifier} is not in the world"
                    else if (!holds(state, owner, event.item)) {
                        out += "the owner ${owner.identifier} does not hold ${event.item.value}"
                    }
                }
            }
            is GameEvent.TradeCompleted -> {
                if (event.price.isNaN() || event.price <= 0f) out += "the price ${event.price} is not a positive amount"
                if (!exists(state, event.buyer)) out += "the buyer ${event.buyer.identifier} is not in the world"
                if (!exists(state, event.seller)) out += "the seller ${event.seller.identifier} is not in the world"
                if (!alive(state, event.buyer)) out += "the buyer ${event.buyer.identifier} is dead"
                if (!alive(state, event.seller)) out += "the seller ${event.seller.identifier} is dead"
                if (exists(state, event.seller) && !holds(state, event.seller, event.item)) {
                    out += "the seller ${event.seller.identifier} does not hold ${event.item.value}"
                }
                if (exists(state, event.buyer) && !canPay(state, event.buyer, event.price)) {
                    out += "the buyer ${event.buyer.identifier} cannot pay ${event.price}"
                }
            }
            is GameEvent.RationPurchased -> {
                if (state.npcs[event.buyer] == null) out += "the buyer ${event.buyer.value} is not in the world"
                if (state.npcs[event.seller] == null) out += "the seller ${event.seller.value} is not in the world"
                if (!alive(state, event.buyer)) out += "the buyer ${event.buyer} is dead"
                if (!alive(state, event.seller)) out += "the seller ${event.seller} is dead"
            }
            is GameEvent.NpcRoutineCompleted -> {
                if (state.npcs[event.npc] == null) out += "the worker ${event.npc.value} is not in the world"
                else if (!alive(state, event.npc)) out += "the worker ${event.npc.value} is dead"
            }
            is GameEvent.AllegianceChanged -> {
                if (state.npcs[event.npc] == null) out += "the npc ${event.npc.value} is not in the world"
                else if (!alive(state, event.npc)) out += "the npc ${event.npc.value} is dead"
                if (state.factions[event.newFaction] == null) {
                    out += "the faction ${event.newFaction.value} does not exist"
                }
            }
            is GameEvent.ShopInherited -> {
                if (state.npcs[event.heir] == null) out += "the heir ${event.heir.value} is not in the world"
                else if (!alive(state, event.heir)) out += "the heir ${event.heir.value} is dead"
                if (state.npcs[event.predecessor] == null) {
                    out += "the predecessor ${event.predecessor.value} is not in the world"
                }
            }
            is GameEvent.RumorTransmitted -> {
                if (state.npcs[event.source] == null) out += "the teller ${event.source.value} is not in the world"
                if (state.npcs[event.target] == null) out += "the listener ${event.target.value} is not in the world"
                else if (!alive(state, event.target)) out += "the listener ${event.target.value} is dead"
            }
            is GameEvent.NpcAttacked -> {
                if (state.npcs[event.target] == null) out += "the target ${event.target.value} is not in the world"
                else if (!alive(state, event.target)) out += "the target ${event.target.value} is dead"
                val attacker = event.attacker
                if (attacker is EntityId.Npc && state.npcs[attacker.id] == null) {
                    out += "the attacker ${attacker.identifier} is not in the world"
                }
            }
            is GameEvent.RelationshipShifted -> {
                if (event.source is EntityId.Npc && state.npcs[event.source.id] == null) {
                    out += "the source ${event.source.identifier} is not in the world"
                }
                if (event.target is EntityId.Npc && state.npcs[event.target.id] == null) {
                    out += "the target ${event.target.identifier} is not in the world"
                }
            }
            is GameEvent.EntitiesMet -> {
                if (event.entityA is EntityId.Npc && state.npcs[event.entityA.id] == null) {
                    out += "the first party ${event.entityA.identifier} is not in the world"
                }
                if (event.entityB is EntityId.Npc && state.npcs[event.entityB.id] == null) {
                    out += "the second party ${event.entityB.identifier} is not in the world"
                }
            }
            is GameEvent.PlayerMoved -> {
                if (state.locations[event.to] == null) {
                    out += "the place ${event.to.value} does not exist"
                }
            }
            is GameEvent.PriceAdjusted -> {
                if (state.npcs[event.merchant] == null) out += "the merchant ${event.merchant.value} is not in the world"
            }
            is GameEvent.AiAdviceRecorded -> {
                if (state.npcs[event.npc] == null) out += "the advised npc ${event.npc.value} is not in the world"
            }
            is GameEvent.OpportunityDispatched -> {
                event.actors.filterIsInstance<EntityId.Npc>().forEach { actor ->
                    if (state.npcs[actor.id] == null) out += "the issuer ${actor.identifier} is not in the world"
                }
            }
            is GameEvent.FactionHostilityDeclared -> {
                if (state.factions[event.declaringFaction] == null) {
                    out += "the faction ${event.declaringFaction.value} does not exist"
                }
                if (state.factions[event.targetFaction] == null) {
                    out += "the faction ${event.targetFaction.value} does not exist"
                }
            }
        }
        return out
    }

    /** True when the event may be applied and recorded. */
    fun canApply(state: WorldState, event: GameEvent): Boolean = reasons(state, event).isEmpty()

    /** The player always exists; an NPC exists only while it is in the world. */
    private fun exists(state: WorldState, entity: EntityId): Boolean = when (entity) {
        is EntityId.Npc -> state.npcs.containsKey(entity.id)
        is EntityId.Player -> entity.identifier == state.player.id.identifier
        else -> true
    }

    /** An NPC that is in the world and still breathing. */
    private fun alive(state: WorldState, npcId: NpcId): Boolean = state.npcs[npcId]?.isAlive == true

    /** An NPC that is in the world and still breathing. */
    private fun alive(state: WorldState, entity: EntityId): Boolean = when (entity) {
        is EntityId.Npc -> alive(state, entity.id)
        else -> true
    }

    private fun holds(state: WorldState, entity: EntityId, item: ItemId): Boolean = when (entity) {
        is EntityId.Npc -> state.npcs[entity.id]?.resources?.items?.contains(item) == true
        is EntityId.Player -> state.player.inventory.contains(item)
        else -> false
    }

    private fun canPay(state: WorldState, entity: EntityId, price: Float): Boolean = when (entity) {
        is EntityId.Npc -> (state.npcs[entity.id]?.resources?.coins ?: 0f) >= price
        is EntityId.Player -> state.player.coins >= price
        else -> false
    }
}

