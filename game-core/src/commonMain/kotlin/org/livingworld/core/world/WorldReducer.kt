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
        val updatedLedger = if (state.events.find(event.id) == null) {
            state.events.record(event)
        } else {
            state.events
        }

        val nextTime = if (event.time > state.time) event.time else state.time
        val baseState = state.copy(time = nextTime, events = updatedLedger)

        return when (event) {
            is GameEvent.NpcKilled -> applyNpcDeath(baseState, event)
            is GameEvent.ItemStolen -> applyTheft(baseState, event)
            is GameEvent.RumorTransmitted -> applyRumor(baseState, event)
            is GameEvent.RelationshipShifted -> applyRelationshipShift(baseState, event)
            is GameEvent.AllegianceChanged -> applyAllegianceChange(baseState, event)
            is GameEvent.TradeCompleted -> applyTrade(baseState, event)
            is GameEvent.PriceAdjusted -> applyPriceAdjustment(baseState, event)
            is GameEvent.ShopInherited -> applyShopInherited(baseState, event)
            is GameEvent.FactionHostilityDeclared -> applyFactionHostility(baseState, event)
            is GameEvent.EntitiesMet -> applyEntitiesMet(baseState, event)
            is GameEvent.NpcAttacked -> applyNpcAttacked(baseState, event)
            is GameEvent.NpcRoutineCompleted -> applyRoutineCompleted(baseState, event)
            is GameEvent.OpportunityDispatched -> applyOpportunityDispatched(baseState, event)
        }
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

        return state.copy(
            npcs = nextNpcs,
            factions = nextFactions,
            relationships = nextRelationships
        )
    }

    private fun witnessOfDeath(witness: Npc, victimId: NpcId, event: GameEvent.NpcKilled, caresAboutVictim: Boolean): Npc {
        var updatedMemories = witness.memories.add(
            Memory.Episodic(eventId = event.id, importance = 1.0f, createdAt = event.time)
        )
        var updatedBeliefs = witness.beliefs

        if (event.killer != null) {
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

        val theftProposition = if (event.thief is EntityId.Player) {
            Proposition.PlayerStole(event.item)
        } else {
            Proposition.EntityStoleItem(event.thief, event.item)
        }

        for (witnessId in event.witnesses) {
            val witness = nextNpcs[witnessId] ?: continue
            val belief = Belief(
                proposition = theftProposition,
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

            val updatedWitness = witness.copy(
                beliefs = witness.beliefs.addOrUpdate(belief).addOrUpdate(opportunisticBelief),
                memories = updatedMemories,
                emotionalState = witness.emotionalState.shifted(deltaAnger = 0.25f, deltaSuspicion = 0.3f)
            )
            nextNpcs = nextNpcs + (witnessId to updatedWitness)

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

        val ownerNpcId = (event.owner as? EntityId.Npc)?.id ?: event.owner?.identifier?.let { NpcId(it) }
        val ownerNpc = ownerNpcId?.let { nextNpcs[it] }
        if (ownerNpc != null) {
            val updatedItems = ownerNpc.resources.items - event.item
            val wasWitness = event.witnesses.contains(ownerNpc.id)
            val updatedOwner = ownerNpc.copy(
                resources = ownerNpc.resources.copy(items = updatedItems),
                emotionalState = if (wasWitness) ownerNpc.emotionalState else ownerNpc.emotionalState.shifted(deltaSuspicion = 0.2f)
            )
            nextNpcs = nextNpcs + (ownerNpc.id to updatedOwner)
        }

        return state.copy(npcs = nextNpcs, relationships = nextRelationships)
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

        val sourceNpcId = (event.source as? EntityId.Npc)?.id ?: NpcId(event.source.identifier)
        val sourceNpc = state.npcs[sourceNpcId]
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

        val buyerNpcId = (event.buyer as? EntityId.Npc)?.id ?: NpcId(event.buyer.identifier)
        val sellerNpcId = (event.seller as? EntityId.Npc)?.id ?: NpcId(event.seller.identifier)

        val buyerNpc = nextNpcs[buyerNpcId]
        val sellerNpc = nextNpcs[sellerNpcId]

        // Coins are conserved: the seller is credited only what the buyer actually paid.
        var paid = 0f
        if (buyerNpc != null) {
            paid = minOf(event.price, buyerNpc.resources.coins)
            val nextItems = buyerNpc.resources.items + event.item
            nextNpcs = nextNpcs + (buyerNpc.id to buyerNpc.copy(
                resources = buyerNpc.resources.copy(coins = buyerNpc.resources.coins - paid, items = nextItems)
            ))
        } else if (event.buyer is EntityId.Player || event.buyer.identifier == state.player.id.identifier) {
            paid = minOf(event.price, nextPlayer.coins)
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
        } else if (event.seller is EntityId.Player || event.seller.identifier == state.player.id.identifier) {
            nextPlayer = nextPlayer.copy(
                coins = nextPlayer.coins + paid,
                inventory = nextPlayer.inventory - event.item,
                historicalActions = nextPlayer.historicalActions + event.id
            )
        }

        // RT-16: Complete matching scarcity quest when trade of that commodity occurs
        var bonusReward = 0f
        val nextQuests = state.activeQuests.map { q ->
            if (q.status != QuestStatus.COMPLETED && q.id == "quest_scarcity_${event.item.value}") {
                bonusReward += q.reward
                q.copy(status = QuestStatus.COMPLETED)
            } else {
                q
            }
        }
        if (bonusReward > 0f) {
            nextPlayer = nextPlayer.copy(coins = nextPlayer.coins + bonusReward)
        }

        val nextEconomy = state.economy.copy(
            totalTradeVolume = state.economy.totalTradeVolume + paid
        )

        return state.copy(
            npcs = nextNpcs,
            player = nextPlayer,
            economy = nextEconomy,
            activeQuests = nextQuests
        )
    }

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
        val updatedHeir = heir.copy(
            identity = heir.identity.copy(occupation = "Shopkeeper (${event.shopName})"),
            resources = heir.resources.copy(coins = heir.resources.coins + 25.0f),
            beliefs = heir.beliefs.addOrUpdate(inheritanceBelief)
        )
        return state.copy(npcs = state.npcs + (heir.id to updatedHeir))
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
        val currentRel = state.relationships.get(event.entityA, event.entityB)
        val updatedRels = if (currentRel.trust == 0f && currentRel.affection == 0f) {
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
        val nextCoins = (npc.resources.coins + event.deltaCoins).coerceAtLeast(0f)
        val nextFood = (npc.resources.food + event.deltaFood).coerceIn(0.5f, 100f)
        val updatedNpc = npc.copy(
            resources = npc.resources.copy(health = nextHealth, coins = nextCoins, food = nextFood),
            emotionalState = npc.emotionalState.shifted(deltaFear = event.deltaFear)
        )
        val nextNpcs = state.npcs + (npc.id to updatedNpc)

        val livingNpcs = nextNpcs.values.filter { it.isAlive }
        val avgFood = if (livingNpcs.isNotEmpty()) {
            livingNpcs.sumOf { it.resources.food.toDouble() }.toFloat() / livingNpcs.size
        } else {
            15f
        }
        val breadScarcity = (15f / avgFood.coerceAtLeast(3f)).coerceIn(0.75f, 2.5f)
        val nextEconomy = state.economy.copy(
            scarcityFactors = state.economy.scarcityFactors + ("bread" to breadScarcity)
        )

        return state.copy(npcs = nextNpcs, economy = nextEconomy)
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
