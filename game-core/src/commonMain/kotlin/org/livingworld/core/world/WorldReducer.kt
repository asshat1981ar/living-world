package org.livingworld.core.world

import org.livingworld.core.beliefs.Belief
import org.livingworld.core.beliefs.KnowledgeSource
import org.livingworld.core.beliefs.Proposition
import org.livingworld.core.events.GameEvent
import org.livingworld.core.ids.EntityId
import org.livingworld.core.ids.EventId
import org.livingworld.core.ids.NpcId
import org.livingworld.core.memory.Memory
import org.livingworld.core.memory.RelationshipExperience
import org.livingworld.core.npc.Npc
import org.livingworld.core.npc.Trait
import org.livingworld.core.relationships.RelationshipGraph

interface WorldReducer {
    fun apply(
        state: WorldState,
        event: GameEvent
    ): WorldState
}

class DefaultWorldReducer : WorldReducer {

    override fun apply(
        state: WorldState,
        event: GameEvent
    ): WorldState {
        // Applying an event that is already in the ledger changes nothing. The ledger is the record of what
        // happened: re-applying it (a retry, a duplicated queue entry, a replay that overlaps) must not change the
        // world a second time (G5).
        if (state.events.find(event.id) != null) return state
        // An event that cannot happen to a dead NPC is not recorded either: the ledger must not contain a lie.
        if (!isApplicable(state, event)) return state
        val updatedLedger = state.events.record(event)

        val nextTime = if (event.time > state.time) event.time else state.time
        val baseState = state.copy(time = nextTime, events = updatedLedger)

        return when (event) {
            is GameEvent.NpcKilled -> applyNpcDeath(baseState, event)
            is GameEvent.ItemStolen -> applyTheft(baseState, event)
            is GameEvent.RumorTransmitted -> applyRumor(baseState, event)
            is GameEvent.RelationshipShifted -> applyRelationshipShift(baseState, event)
            is GameEvent.AllegianceChanged -> applyAllegianceChange(baseState, event)
            is GameEvent.TradeCompleted -> applyTrade(baseState, event)
            is GameEvent.RationPurchased -> applyRationPurchase(baseState, event)
            // A record of what an AI advised; the action it led to is its own event.
            is GameEvent.AiAdviceRecorded -> baseState
            is GameEvent.PriceAdjusted -> applyPriceAdjustment(baseState, event)
            is GameEvent.ShopInherited -> applyShopInherited(baseState, event)
            is GameEvent.FactionHostilityDeclared -> applyFactionHostility(baseState, event)
            is GameEvent.EntitiesMet -> applyEntitiesMet(baseState, event)
            is GameEvent.NpcAttacked -> applyNpcAttacked(baseState, event)
            is GameEvent.NpcRoutineCompleted -> applyRoutineCompleted(baseState, event)
            is GameEvent.OpportunityDispatched -> applyOpportunityDispatched(baseState, event)
        }
    }

    /**
     * Whether the event can happen at all in this world. The dead do not work, trade, speak, inherit or fight, so
     * such an event is not recorded: the ledger must not contain a lie (G4). An entity that is not in the world at all
     * keeps the old behaviour (the event is recorded, nothing changes) - that is a different gap.
     */
    private fun isApplicable(state: WorldState, event: GameEvent): Boolean = when (event) {
        is GameEvent.NpcRoutineCompleted -> isNotDead(state, event.npc)
        is GameEvent.TradeCompleted -> tradeIsPossible(state, event)
        is GameEvent.ItemStolen -> theftIsPossible(state, event)
        is GameEvent.RationPurchased -> isNotDead(state, event.buyer) && isNotDead(state, event.seller)
        is GameEvent.AllegianceChanged -> isNotDead(state, event.npc)
        is GameEvent.ShopInherited -> isNotDead(state, event.heir)
        is GameEvent.RumorTransmitted -> isNotDead(state, event.target)
        is GameEvent.NpcAttacked -> isNotDead(state, event.target)
        else -> true
    }

    /**
     * A trade needs two parties that exist, a seller that holds the item and a buyer that can pay the price. Without
     * this, a trade with an unknown seller destroyed the buyer's coins and a trade minted the seller's stock.
     */
    private fun tradeIsPossible(state: WorldState, event: GameEvent.TradeCompleted): Boolean {
        if (event.price.isNaN() || event.price <= 0f) return false
        val buyer = (event.buyer as? EntityId.Npc)?.id?.let { state.npcs[it] }
        val seller = (event.seller as? EntityId.Npc)?.id?.let { state.npcs[it] }
        val buyerIsPlayer = isPlayer(state, event.buyer)
        val sellerIsPlayer = isPlayer(state, event.seller)
        if (buyer == null && !buyerIsPlayer) return false
        if (seller == null && !sellerIsPlayer) return false
        if (buyer != null && !buyer.isAlive) return false
        if (seller != null && !seller.isAlive) return false
        val sellerHolds = if (seller != null) seller.resources.items.contains(event.item)
        else sellerIsPlayer && state.player.inventory.contains(event.item)
        if (!sellerHolds) return false
        val buyerCanPay = if (buyer != null) buyer.resources.coins >= event.price
        else buyerIsPlayer && state.player.coins >= event.price
        return buyerCanPay
    }

    /** A theft needs a thief that exists and an owner that holds the item; the item moves to the thief. */
    private fun theftIsPossible(state: WorldState, event: GameEvent.ItemStolen): Boolean {
        val thief = (event.thief as? EntityId.Npc)?.id?.let { state.npcs[it] }
        val thiefIsPlayer = isPlayer(state, event.thief)
        if (thief == null && !thiefIsPlayer) return false
        val owner = event.owner ?: return false
        val ownerNpc = (owner as? EntityId.Npc)?.id?.let { state.npcs[it] }
        val ownerIsPlayer = isPlayer(state, owner)
        if (ownerNpc == null && !ownerIsPlayer) return false
        val ownerHolds = if (ownerNpc != null) ownerNpc.resources.items.contains(event.item)
        else ownerIsPlayer && state.player.inventory.contains(event.item)
        return ownerHolds
    }

    /** An NPC that is in the world but dead cannot act; the player and unknown entities are not NPCs. */
    private fun isNotDead(state: WorldState, npcId: NpcId): Boolean = state.npcs[npcId]?.isAlive != false

    private fun isNotDead(state: WorldState, entity: EntityId): Boolean = when (entity) {
        is EntityId.Npc -> isNotDead(state, entity.id)
        else -> true
    }

    /** The world graph is the single source of truth for relationships (NPCs hold no private copy). */
    private fun modifyRelationship(
        relationships: RelationshipGraph,
        from: EntityId,
        to: EntityId,
        deltaTrust: Float = 0f,
        deltaAffection: Float = 0f,
        deltaRespect: Float = 0f,
        deltaFear: Float = 0f,
        deltaResentment: Float = 0f,
        eventId: EventId? = null
    ): RelationshipGraph = relationships.modify(
        from = from,
        to = to,
        deltaTrust = deltaTrust,
        deltaAffection = deltaAffection,
        deltaRespect = deltaRespect,
        deltaFear = deltaFear,
        deltaResentment = deltaResentment,
        event = eventId
    )

    private fun applyNpcDeath(state: WorldState, event: GameEvent.NpcKilled): WorldState {
        val victim = state.npcs[event.victim] ?: return state

        val updatedVictim = victim.copy(
            identity = victim.identity.copy(isAlive = false),
            resources = victim.resources.copy(health = 0f)
        )
        var nextNpcs = state.npcs + (victim.id to updatedVictim)
        var nextRelationships = state.relationships

        val nextFactions = state.factions.mapValues { (_, faction) ->
            if (faction.hasMember(victim.id)) faction.removeMember(victim.id) else faction
        }

        for (witnessId in event.witnesses) {
            val witness = nextNpcs[witnessId] ?: continue
            val affectionForVictim = nextRelationships.get(witnessId.toEntityId(), victim.id.toEntityId()).overallSentiment
            val caresAboutVictim = affectionForVictim > 0.2f || witness.reputationToward(victim.id.toEntityId(), nextRelationships) > 0.2f

            val updatedWitness = witnessOfDeath(witness, victim.id, event, caresAboutVictim)
            nextNpcs = nextNpcs + (witnessId to updatedWitness)

            if (event.killer != null) {
                nextRelationships = modifyRelationship(
                    relationships = nextRelationships,
                    from = witnessId.toEntityId(),
                    to = event.killer,
                    deltaTrust = if (caresAboutVictim) -0.8f else -0.4f,
                    deltaAffection = if (caresAboutVictim) -0.8f else -0.3f,
                    deltaRespect = -0.3f,
                    deltaFear = 0.45f,
                    deltaResentment = if (caresAboutVictim) 0.9f else 0.4f,
                    eventId = event.id
                )
            }
        }

        nextNpcs = spreadNewsOfDeath(nextNpcs, nextRelationships, victim, event)

        // G4: quests the dead issued can no longer be completed.
        val victimEntity = victim.id.toEntityId()
        val nextQuests = state.activeQuests.map { q ->
            if (q.issuer == victimEntity && (q.status == QuestStatus.AVAILABLE || q.status == QuestStatus.ACTIVE))
                q.copy(status = QuestStatus.FAILED) else q
        }

        return state.copy(
            npcs = nextNpcs,
            factions = nextFactions,
            relationships = nextRelationships,
            activeQuests = nextQuests
        )
    }

    /** G4: people who were not there still hear of it, if the victim mattered to them or shared a faction. */
    private fun spreadNewsOfDeath(
        npcs: Map<NpcId, Npc>,
        relationships: RelationshipGraph,
        victim: Npc,
        event: GameEvent.NpcKilled
    ): Map<NpcId, Npc> {
        var nextNpcs = npcs
        val firstWitness = event.witnesses.firstOrNull()
        for (listener in npcs.values.sortedBy { it.id.value }) {
            if (!listener.isAlive || listener.id == victim.id || listener.id in event.witnesses) continue
            val sentiment = relationships.get(listener.id.toEntityId(), victim.id.toEntityId()).overallSentiment
            val sharedFaction = victim.factions.filter { it in listener.factions }.minByOrNull { it.value }
            if (sentiment <= 0.2f && sharedFaction == null) continue
            val source = when {
                firstWitness != null -> KnowledgeSource.Hearsay(firstWitness)
                sharedFaction != null -> KnowledgeSource.FactionReport(sharedFaction)
                else -> KnowledgeSource.Observation(event.id)
            }
            nextNpcs = nextNpcs + (listener.id to hearOfDeath(listener, victim.id, event, source, sentiment > 0.2f))
        }
        return nextNpcs
    }

    private fun hearOfDeath(listener: Npc, victimId: NpcId, event: GameEvent.NpcKilled, source: KnowledgeSource, cares: Boolean): Npc {
        val proposition = if (event.killer != null) Proposition.EntityKilledNpc(event.killer, victimId)
        else Proposition.NpcDied(victimId)
        return listener.copy(
            beliefs = listener.beliefs.addOrUpdate(
                Belief(proposition, confidence = 0.85f, source = source, reliability = 0.8f, acquiredAt = event.time)
            ),
            memories = listener.memories.add(
                Memory.Episodic(eventId = event.id, importance = 0.7f, createdAt = event.time)
            ),
            emotionalState = listener.emotionalState.shifted(deltaGrief = if (cares) 0.4f else 0.1f)
        )
    }

    private fun witnessOfDeath(witness: Npc, victimId: NpcId, event: GameEvent.NpcKilled, caresAboutVictim: Boolean): Npc {
        var updatedMemories = witness.memories.add(
            Memory.Episodic(eventId = event.id, importance = 1.0f, createdAt = event.time)
        )
        var updatedBeliefs = witness.beliefs

        if (event.killer == null) {
            updatedBeliefs = updatedBeliefs.addOrUpdate(
                Belief(
                    proposition = Proposition.NpcDied(victimId),
                    confidence = 1.0f,
                    source = KnowledgeSource.DirectObservation,
                    reliability = 1.0f,
                    acquiredAt = event.time
                )
            )
        } else {
            updatedBeliefs = updatedBeliefs.addOrUpdate(
                Belief(
                    proposition = Proposition.EntityKilledNpc(event.killer, victimId),
                    confidence = 1.0f,
                    source = KnowledgeSource.DirectObservation,
                    reliability = 1.0f,
                    acquiredAt = event.time
                )
            )
            updatedMemories = updatedMemories.add(
                Memory.Relationship(
                    entity = event.killer,
                    experience = RelationshipExperience.ASSAULT,
                    importance = 0.95f,
                    createdAt = event.time
                )
            )
        }

        return witness.copy(
            memories = updatedMemories,
            beliefs = updatedBeliefs,
            emotionalState = witness.emotionalState.shifted(
                deltaFear = 0.35f,
                deltaGrief = if (caresAboutVictim) 0.6f else 0.15f,
                deltaAnger = if (caresAboutVictim) 0.45f else 0.1f
            )
        )
    }

    private fun applyTheft(state: WorldState, event: GameEvent.ItemStolen): WorldState {
        var nextNpcs = state.npcs
        var nextRelationships = state.relationships
        var nextPlayer = state.player

        val theftProposition = if (event.thief is EntityId.Player) {
            Proposition.PlayerStole(event.item)
        } else {
            Proposition.EntityStoleItem(event.thief, event.item)
        }

        for (witnessId in event.witnesses) {
            val witness = nextNpcs[witnessId] ?: continue
            nextNpcs = nextNpcs + (witnessId to witnessTheft(witness, event, theftProposition))

            nextRelationships = modifyRelationship(
                relationships = nextRelationships,
                from = witnessId.toEntityId(),
                to = event.thief,
                deltaTrust = -0.5f,
                deltaAffection = -0.3f,
                deltaResentment = 0.6f,
                eventId = event.id
            )
        }

        // The owner is looked up by kind, not by name: a player called "merchant" is not the merchant.
        val owner = event.owner
        when (owner) {
            is EntityId.Npc -> {
                val ownerNpc = nextNpcs[owner.id] ?: return state
                val wasWitness = event.witnesses.contains(ownerNpc.id)
                val updatedOwner = ownerNpc.copy(
                    resources = ownerNpc.resources.copy(items = ownerNpc.resources.items - event.item),
                    emotionalState = if (wasWitness) ownerNpc.emotionalState else ownerNpc.emotionalState.shifted(deltaSuspicion = 0.2f)
                )
                nextNpcs = nextNpcs + (ownerNpc.id to updatedOwner)
            }
            is EntityId.Player -> nextPlayer = nextPlayer.copy(inventory = nextPlayer.inventory - event.item)
            else -> Unit
        }

        // The stolen item moves to the thief: a theft that does not move anything is not a theft.
        when (val thief = event.thief) {
            is EntityId.Npc -> {
                val thiefNpc = nextNpcs[thief.id] ?: return state
                nextNpcs = nextNpcs + (thiefNpc.id to thiefNpc.copy(
                    resources = thiefNpc.resources.copy(items = thiefNpc.resources.items + event.item)
                ))
            }
            is EntityId.Player -> nextPlayer = nextPlayer.copy(inventory = nextPlayer.inventory + event.item)
            else -> Unit
        }

        return state.copy(npcs = nextNpcs, relationships = nextRelationships, player = nextPlayer)
    }

    /** A witness sees the theft, judges the thief opportunistic, remembers it and grows angry and suspicious. */
    private fun witnessTheft(witness: Npc, event: GameEvent.ItemStolen, theft: Proposition): Npc {
        val belief = Belief(
            proposition = theft,
            confidence = 0.95f,
            source = KnowledgeSource.DirectObservation,
            reliability = 0.95f,
            acquiredAt = event.time
        )
        val opportunisticBelief = Belief(
            proposition = Proposition.HasTrait(event.thief, Trait.OPPORTUNISTIC),
            confidence = 0.9f,
            source = KnowledgeSource.Observation(event.id),
            reliability = 0.95f,
            acquiredAt = event.time
        )
        val updatedMemories = witness.memories
            .add(Memory.Episodic(eventId = event.id, importance = 0.8f, createdAt = event.time))
            .add(Memory.Relationship(entity = event.thief, experience = RelationshipExperience.THEFT, importance = 0.85f, createdAt = event.time))

        return witness.copy(
            beliefs = witness.beliefs.addOrUpdate(belief).addOrUpdate(opportunisticBelief),
            memories = updatedMemories,
            emotionalState = witness.emotionalState.shifted(deltaAnger = 0.25f, deltaSuspicion = 0.3f)
        )
    }

    private fun applyRumor(state: WorldState, event: GameEvent.RumorTransmitted): WorldState {
        val target = state.npcs[event.target] ?: return state
        val updatedTarget = target.copy(
            beliefs = target.beliefs.addOrUpdate(event.transmittedBelief),
            memories = target.memories
                .add(Memory.Episodic(eventId = event.id, importance = 0.5f, createdAt = event.time))
                .add(Memory.Semantic(proposition = event.transmittedBelief.proposition, importance = event.transmittedBelief.confidence, createdAt = event.time))
        )
        val source = state.npcs[event.source]?.let {
            it.copy(
                memories = it.memories.add(
                    Memory.Told(target = event.target, proposition = event.transmittedBelief.proposition, importance = 0.3f, createdAt = event.time)
                )
            )
        }
        val withTarget = state.npcs + (target.id to updatedTarget)
        return state.copy(npcs = if (source != null) withTarget + (source.id to source) else withTarget)
    }

    private fun applyRelationshipShift(
        state: WorldState,
        event: GameEvent.RelationshipShifted
    ): WorldState {
        val updatedRels = modifyRelationship(
            relationships = state.relationships,
            from = event.source,
            to = event.target,
            deltaTrust = event.deltaTrust,
            deltaAffection = event.deltaAffection,
            deltaRespect = event.deltaRespect,
            deltaFear = event.deltaFear,
            deltaResentment = event.deltaResentment,
            eventId = event.id
        )

        val sourceNpcId = (event.source as? EntityId.Npc)?.id
        val sourceNpc = sourceNpcId?.let { state.npcs[it] }
        val finalNpcs = if (sourceNpc != null) {
            val experience = if (event.deltaTrust >= 0f && event.deltaResentment <= 0f) {
                RelationshipExperience.COOPERATION
            } else {
                RelationshipExperience.BETRAYAL
            }
            val memory = Memory.Relationship(
                entity = event.target,
                experience = experience,
                importance = 0.6f,
                createdAt = event.time
            )
            state.npcs + (sourceNpcId to sourceNpc.copy(memories = sourceNpc.memories.add(memory)))
        } else {
            state.npcs
        }

        return state.copy(relationships = updatedRels, npcs = finalNpcs)
    }

    private fun applyAllegianceChange(
        state: WorldState,
        event: GameEvent.AllegianceChanged
    ): WorldState {
        val npc = state.npcs[event.npc] ?: return state
        val updatedNpcFactions = (npc.factions - listOfNotNull(event.oldFaction)) + event.newFaction
        val updatedNpc = npc.copy(factions = updatedNpcFactions)

        var nextFactions = state.factions
        if (event.oldFaction != null) {
            val oldF = nextFactions[event.oldFaction]
            if (oldF != null) {
                nextFactions = nextFactions + (oldF.id to oldF.removeMember(npc.id))
            }
        }
        val newF = nextFactions[event.newFaction]
        if (newF != null) {
            nextFactions = nextFactions + (newF.id to newF.addMember(npc.id))
        }

        return state.copy(
            npcs = state.npcs + (npc.id to updatedNpc),
            factions = nextFactions
        )
    }

    private fun applyTrade(state: WorldState, event: GameEvent.TradeCompleted): WorldState {
        var nextNpcs = state.npcs
        var nextPlayer = state.player

        // Parties are looked up by kind: an NPC and a player that share a name are different parties.
        val buyerNpcId = (event.buyer as? EntityId.Npc)?.id
        val sellerNpcId = (event.seller as? EntityId.Npc)?.id

        val buyerNpc = buyerNpcId?.let { nextNpcs[it] }
        val sellerNpc = sellerNpcId?.let { nextNpcs[it] }

        // Coins are conserved: the seller is credited only what the buyer actually paid.
        // The pre-check already proved that both parties exist, that the seller holds the item and that the buyer
        // can pay, so the price moves in full.
        var paid = event.price
        if (buyerNpc != null) {
            val nextItems = buyerNpc.resources.items + event.item
            nextNpcs = nextNpcs + (buyerNpc.id to buyerNpc.copy(
                resources = buyerNpc.resources.copy(coins = buyerNpc.resources.coins - paid, items = nextItems)
            ))
        } else if (isPlayer(state, event.buyer)) {
            nextPlayer = nextPlayer.copy(
                coins = nextPlayer.coins - paid,
                inventory = nextPlayer.inventory + event.item,
                historicalActions = nextPlayer.historicalActions + event.id
            )
        }

        if (sellerNpc != null) {
            val nextCoins = sellerNpc.resources.coins + paid
            val nextItems = sellerNpc.resources.items - event.item
            nextNpcs = nextNpcs + (sellerNpc.id to sellerNpc.copy(
                resources = sellerNpc.resources.copy(coins = nextCoins, items = nextItems)
            ))
        } else if (isPlayer(state, event.seller)) {
            nextPlayer = nextPlayer.copy(
                coins = nextPlayer.coins + paid,
                inventory = nextPlayer.inventory - event.item,
                historicalActions = nextPlayer.historicalActions + event.id
            )
        }

        // RT-16: Complete matching scarcity quest when trade of that commodity occurs
        // Only a trade the player took part in earns the player the reward; failed quests stay failed.
        val playerInvolved = isPlayer(state, event.buyer) || isPlayer(state, event.seller)
        var bonusReward = 0f
        val nextQuests = state.activeQuests.map { q ->
            if (playerInvolved && q.status != QuestStatus.COMPLETED && q.status != QuestStatus.FAILED && q.id == "quest_scarcity_${event.item.value}") {
                bonusReward += q.reward
                q.copy(status = QuestStatus.COMPLETED)
            } else {
                q
            }
        }
        // The reward is paid from the treasury, so it is limited to what the treasury holds.
        val reward = minOf(bonusReward, state.economy.treasury).coerceAtLeast(0f)
        if (reward > 0f) {
            nextPlayer = nextPlayer.copy(coins = nextPlayer.coins + reward)
        }

        val nextEconomy = state.economy.copy(
            totalTradeVolume = state.economy.totalTradeVolume + paid,
            treasury = state.economy.treasury - reward
        )

        return state.copy(
            npcs = nextNpcs,
            player = nextPlayer,
            economy = nextEconomy,
            activeQuests = nextQuests
        )
    }

    /** The player is the player: an NPC that happens to be called "player" is not. */
    private fun isPlayer(state: WorldState, entity: EntityId): Boolean =
        entity is EntityId.Player && entity.identifier == state.player.id.identifier

    private fun applyPriceAdjustment(
        state: WorldState,
        event: GameEvent.PriceAdjusted
    ): WorldState {
        val sanitizedPrice = event.newPrice.coerceAtLeast(0.5f)
        return state.copy(economy = state.economy.withPrice(event.item, sanitizedPrice))
    }

    private fun applyShopInherited(
        state: WorldState,
        event: GameEvent.ShopInherited
    ): WorldState {
        val heir = state.npcs[event.heir] ?: return state
        val inheritanceBelief = Belief(
            proposition = Proposition.ShopInherited(event.heir, event.shopName),
            confidence = 1.0f,
            source = KnowledgeSource.DirectObservation,
            reliability = 1.0f,
            acquiredAt = event.time
        )
        // The village grants the new shopkeeper a start-up purse from the treasury (limited to what it holds).
        val grant = minOf(25.0f, state.economy.treasury).coerceAtLeast(0f)
        // The shop's stock goes with the shop: the new shopkeeper sells what the old one left behind.
        val predecessor = state.npcs[event.predecessor]
        val inheritedItems = predecessor?.resources?.items ?: emptyList()
        var nextNpcs = state.npcs
        if (predecessor != null && inheritedItems.isNotEmpty()) {
            nextNpcs = nextNpcs + (predecessor.id to predecessor.copy(
                resources = predecessor.resources.copy(items = predecessor.resources.items - inheritedItems)
            ))
        }
        val updatedHeir = heir.copy(
            identity = heir.identity.copy(occupation = "Shopkeeper (${event.shopName})"),
            resources = heir.resources.copy(
                coins = heir.resources.coins + grant,
                items = heir.resources.items + inheritedItems
            ),
            beliefs = heir.beliefs.addOrUpdate(inheritanceBelief)
        )
        return state.copy(
            npcs = nextNpcs + (heir.id to updatedHeir),
            economy = state.economy.copy(treasury = state.economy.treasury - grant)
        )
    }

    private fun applyFactionHostility(
        state: WorldState,
        event: GameEvent.FactionHostilityDeclared
    ): WorldState {
        val decl = state.factions[event.declaringFaction] ?: return state
        val target = state.factions[event.targetFaction] ?: return state

        val updatedDecl = decl.declareHostility(event.targetFaction)
        val updatedTarget = target.declareHostility(event.declaringFaction)

        return state.copy(
            factions = state.factions + (decl.id to updatedDecl) + (target.id to updatedTarget)
        )
    }

    private fun applyEntitiesMet(
        state: WorldState,
        event: GameEvent.EntitiesMet
    ): WorldState {
        // Only a pair with no history at all starts out NEUTRAL; an edge with any feeling or history (a grudge with no
        // trust or affection component, say) is left as it is.
        val currentRel = state.relationships.get(event.entityA, event.entityB)
        val updatedRels = if (currentRel == org.livingworld.core.relationships.Relationship.NEUTRAL) {
            state.relationships.set(event.entityA, event.entityB, org.livingworld.core.relationships.Relationship.NEUTRAL)
        } else {
            state.relationships
        }
        return state.copy(relationships = updatedRels)
    }

    private fun applyNpcAttacked(
        state: WorldState,
        event: GameEvent.NpcAttacked
    ): WorldState {
        val target = state.npcs[event.target] ?: return state
        val sanitizedDamage = event.damage.coerceAtLeast(0f)
        val remainingHealth = (target.resources.health - sanitizedDamage).coerceAtLeast(0f)

        val updatedTarget = target.copy(
            resources = target.resources.copy(health = remainingHealth),
            emotionalState = target.emotionalState.shifted(deltaFear = 0.2f, deltaAnger = 0.3f),
            memories = target.memories.add(
                Memory.Relationship(
                    entity = event.attacker,
                    experience = RelationshipExperience.ASSAULT,
                    importance = 0.9f,
                    createdAt = event.time
                )
            )
        )

        val updatedRels = modifyRelationship(
            relationships = state.relationships,
            from = target.id.toEntityId(),
            to = event.attacker,
            deltaTrust = -0.4f,
            deltaAffection = -0.4f,
            deltaFear = 0.25f,
            deltaResentment = 0.5f,
            eventId = event.id
        )

        return state.copy(
            npcs = state.npcs + (target.id to updatedTarget),
            relationships = updatedRels
        )
    }

    private fun applyRoutineCompleted(
        state: WorldState,
        event: GameEvent.NpcRoutineCompleted
    ): WorldState {
        val npc = state.npcs[event.npc] ?: return state
        val nextHealth = (npc.resources.health + event.deltaHealth).coerceIn(0f, 100f)
        // Coins are a transfer with the treasury (wage in, upkeep out), limited by what the paying side has.
        val coinShift = if (event.deltaCoins >= 0f) minOf(event.deltaCoins, state.economy.treasury.coerceAtLeast(0f))
            else -minOf(-event.deltaCoins, npc.resources.coins)
        val nextFood = (npc.resources.food + event.deltaFood).coerceIn(0.5f, 100f)
        val updatedNpc = npc.copy(
            resources = npc.resources.copy(health = nextHealth, coins = npc.resources.coins + coinShift, food = nextFood),
            emotionalState = npc.emotionalState.shifted(deltaFear = event.deltaFear)
        )
        val nextNpcs = state.npcs + (npc.id to updatedNpc)
        val economy = state.economy.copy(treasury = state.economy.treasury - coinShift)
        return state.copy(npcs = nextNpcs, economy = withBreadScarcity(economy, nextNpcs))
    }

    /**
     * Food moves from seller to buyer (the seller keeps the 0.5 floor) and coins from buyer to seller, limited by what
     * the buyer has; the tax share of what was paid goes to the treasury.
     */
    private fun applyRationPurchase(state: WorldState, event: GameEvent.RationPurchased): WorldState {
        val buyer = state.npcs[event.buyer] ?: return state
        val seller = state.npcs[event.seller] ?: return state
        val paid = minOf(event.price, buyer.resources.coins).coerceAtLeast(0f)
        val tax = minOf(event.tax, paid).coerceAtLeast(0f)
        val food = minOf(event.food, seller.resources.food - 0.5f).coerceAtLeast(0f)
        val nextNpcs = state.npcs +
            (buyer.id to buyer.copy(resources = buyer.resources.copy(
                coins = buyer.resources.coins - paid, food = (buyer.resources.food + food).coerceAtMost(100f)))) +
            (seller.id to seller.copy(resources = seller.resources.copy(
                coins = seller.resources.coins + (paid - tax), food = seller.resources.food - food)))
        val economy = state.economy.copy(treasury = state.economy.treasury + tax)
        return state.copy(npcs = nextNpcs, economy = withBreadScarcity(economy, nextNpcs))
    }

    private fun withBreadScarcity(economy: EconomyState, npcs: Map<NpcId, Npc>): EconomyState {
        val livingNpcs = npcs.values.filter { it.isAlive }
        val avgFood = if (livingNpcs.isNotEmpty()) {
            livingNpcs.sumOf { it.resources.food.toDouble() }.toFloat() / livingNpcs.size
        } else {
            15f
        }
        val breadScarcity = (15f / avgFood.coerceAtLeast(3f)).coerceIn(0.75f, 2.5f)
        return economy.copy(scarcityFactors = economy.scarcityFactors + ("bread" to breadScarcity))
    }

    /**
     * Sprint 5 (quest-procedural-005 & RT-15): Materializes dispatched quest opportunities into
     * WorldState.activeQuests with strict ID deduplication.
     */
    private fun applyOpportunityDispatched(
        state: WorldState,
        event: GameEvent.OpportunityDispatched
    ): WorldState {
        if (state.activeQuests.any { it.id == event.opportunityId }) {
            return state
        }
        val issuer = event.actors.firstOrNull() ?: NpcId("elder_rowan").toEntityId()
        val quest = QuestOpportunity(
            id = event.opportunityId,
            title = event.title,
            description = event.narrativeHook,
            issuer = issuer,
            target = event.location.toEntityId(),
            reward = (event.urgency * 100f).coerceIn(25f, 150f),
            status = QuestStatus.AVAILABLE
        )
        return state.copy(activeQuests = state.activeQuests + quest)
    }
}
