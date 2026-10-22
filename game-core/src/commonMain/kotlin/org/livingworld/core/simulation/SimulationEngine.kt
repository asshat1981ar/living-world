package org.livingworld.core.simulation

import org.livingworld.core.beliefs.Belief
import org.livingworld.core.beliefs.KnowledgeSource
import org.livingworld.core.events.CausalLinks
import org.livingworld.core.events.GameEvent
import org.livingworld.core.events.TacticalCombatResolver
import org.livingworld.core.ids.EventId
import org.livingworld.core.ids.LocationId
import org.livingworld.core.ids.NpcId
import org.livingworld.core.npc.Npc
import org.livingworld.core.npc.Trait
import org.livingworld.core.relationships.RelationshipGraph
import org.livingworld.core.time.WorldDuration
import org.livingworld.core.time.WorldTime
import org.livingworld.core.world.DefaultWorldReducer
import org.livingworld.core.world.ProceduralQuestEngine
import org.livingworld.core.world.WorldReducer
import org.livingworld.core.world.WorldState

class SimulationEngine(
    private val reducer: WorldReducer = DefaultWorldReducer(),
    private val decisionEngine: NpcDecisionEngine = DefaultNpcDecisionEngine()
) {

    /**
     * Advance simulation by one tick (e.g. 1 hour = 60 ticks, or custom).
     * Strictly sorted by NpcId to guarantee bit-for-bit determinism (Invariant G8).
     */
    suspend fun tick(
        state: WorldState,
        stepDuration: WorldDuration = WorldDuration.hours(1)
    ): WorldState {
        if (stepDuration.ticks <= 0L) return state
        var current = advanceTime(state, stepDuration)
        val activeNpcs = selectActiveNpcs(current)

        for (npc in activeNpcs) {
            val freshNpc = current.npcs[npc.id] ?: continue
            if (!freshNpc.isAlive) continue

            val (action, advice) = decisionEngine.decideWithAdvice(freshNpc, current)
            // P9: an AI's part in the decision is recorded first, and the action names it as its cause.
            var cause: EventId? = null
            if (advice != null) {
                val record = GameEvent.AiAdviceRecorded(
                    id = current.events.nextId(),
                    time = current.time,
                    npc = freshNpc.id,
                    source = advice.source,
                    outcome = advice.outcome,
                    candidateIndex = advice.candidateIndex,
                    explanation = advice.explanation
                )
                current = reducer.apply(current, record)
                cause = record.id
            }
            current = executeValidatedAction(current, freshNpc, action, cause)
        }

        if (current.time.day > state.time.day) current = starveTheHungry(collectUpkeep(current))

        // Sprint 5 & RT-15: Evaluate emergent crises and dispatch non-duplicate QuestOpportunities
        current = dispatchEmergentOpportunities(current)

        return current
    }

    /**
     * Evaluates ProceduralQuestEngine opportunities and emits OpportunityDispatched events
     * only for newly emerged problems not yet present in activeQuests (RT-15 guard).
     */
    private fun dispatchEmergentOpportunities(state: WorldState): WorldState {
        var current = state
        val newOpportunities = ProceduralQuestEngine.generateOpportunities(current)
        for (opp in newOpportunities) {
            if (current.activeQuests.any { it.id == opp.id }) continue
            val targetLoc = (opp.target as? org.livingworld.core.ids.EntityId.Location)?.id
                ?: LocationId("village_square")
            val dispatchEvent = GameEvent.OpportunityDispatched(
                id = current.events.nextId(),
                time = current.time,
                opportunityId = opp.id,
                opportunityType = "CRISIS_QUEST",
                title = opp.title,
                location = targetLoc,
                urgency = (opp.reward / 100f).coerceIn(0.25f, 1.0f),
                narrativeHook = opp.description,
                actors = listOf(opp.issuer),
                causalEventId = CausalLinks.originOfQuest(current.events, opp.id)
            )
            current = reducer.apply(current, dispatchEvent)
        }
        return current
    }

    /**
     * Advance simulation by arbitrary duration (e.g. 1 day, 10 days, 90 days).
     */
    suspend fun advance(
        state: WorldState,
        duration: WorldDuration
    ): WorldState {
        if (duration.ticks <= 0L) return state
        val step = WorldDuration.hours(1)
        var remaining = duration.ticks
        var currentState = state

        while (remaining > 0) {
            val stepSize = minOf(remaining, step.ticks)
            currentState = tick(currentState, WorldDuration.ticks(stepSize))
            remaining -= stepSize
        }
        return currentState
    }

    /** Once a day every living NPC with coins pays upkeep into the treasury (the reducer takes only what they have). */
    private fun collectUpkeep(state: WorldState): WorldState {
        var current = state
        for (npc in current.npcs.values.filter { it.isAlive && it.resources.coins > 0f }.sortedBy { it.id.value }) {
            current = reducer.apply(current, GameEvent.NpcRoutineCompleted(
                id = current.events.nextId(),
                time = current.time,
                npc = npc.id,
                routineType = "UPKEEP",
                location = npc.identity.currentLocation,
                deltaCoins = -DAILY_UPKEEP
            ))
        }
        return current
    }

    /** Once a day every living NPC with no food takes [STARVATION_DAMAGE]; a fatal hit is recorded as a death. */
    private fun starveTheHungry(state: WorldState): WorldState {
        var current = state
        for (npc in current.npcs.values.filter { it.isAlive && it.resources.food <= 0f }.sortedBy { it.id.value }) {
            current = StarvationEvents.apply(reducer, current, npc.id, STARVATION_DAMAGE)
        }
        return current
    }

    /**
     * A worker who is hungry after a shift buys rations from the producer with the most food to spare (keeping
     * [RATION_SELLER_RESERVE] after the sale), ties broken by id. This is the market matching buyers and sellers,
     * not something the NPC knows; with no seller or too few coins the worker goes without.
     */
    private fun buyRationsIfHungry(state: WorldState, buyerId: NpcId): WorldState {
        val buyer = state.npcs[buyerId] ?: return state
        if (buyer.resources.food >= RATION_HUNGER_THRESHOLD || buyer.resources.coins < RATION_PRICE) return state
        val seller = state.npcs.values
            .filter { it.isAlive && it.id != buyerId && isFoodProducer(it.identity.occupation) && it.resources.food >= RATION_FOOD + RATION_SELLER_RESERVE }
            .sortedWith(compareByDescending<Npc> { it.resources.food }.thenBy { it.id.value })
            .firstOrNull() ?: return state
        return reducer.apply(state, GameEvent.RationPurchased(
            id = state.events.nextId(),
            time = state.time,
            buyer = buyerId,
            seller = seller.id,
            food = RATION_FOOD,
            price = RATION_PRICE,
            tax = RATION_TAX
        ))
    }

    private fun advanceTime(state: WorldState, duration: WorldDuration): WorldState {
        return state.copy(time = state.time + duration)
    }

    fun selectActiveNpcs(state: WorldState): List<Npc> {
        return state.npcs.values
            .filter { npc ->
                npc.isAlive && (
                    npc.resources.health < 80f ||
                    npc.goals.any { it.urgency > 0.4f } ||
                    npc.emotionalState.fear > 0.3f ||
                    npc.emotionalState.anger > 0.3f ||
                    state.time.phase.name in listOf("MORNING", "MIDDAY", "AFTERNOON")
                )
            }
            .sortedBy { it.id.value }
    }

    private fun isFoodProducer(occupation: String): Boolean {
        return occupation.contains("Farmer", ignoreCase = true) ||
               occupation.contains("Baker", ignoreCase = true) ||
               occupation.contains("Hunter", ignoreCase = true) ||
               occupation.contains("Herbalist", ignoreCase = true) ||
               occupation.contains("Brewer", ignoreCase = true)
    }

    private fun executeValidatedAction(
        state: WorldState,
        npc: Npc,
        validatedAction: ValidatedAction,
        cause: EventId? = null
    ): WorldState {
        val eventId = state.events.nextId()
        val time = state.time

        return when (val intent = validatedAction.intent) {
            is Intent.Rest -> {
                val routineEvent = GameEvent.NpcRoutineCompleted(
                    id = eventId,
                    time = time,
                    causalEventId = cause,
                    npc = npc.id,
                    routineType = "REST",
                    location = npc.identity.currentLocation,
                    deltaHealth = 10f,
                    deltaFood = -0.25f
                )
                reducer.apply(state, routineEvent)
            }
            is Intent.Work -> {
                // Producers grow food and earn by selling it; everyone else is paid a wage from the treasury.
                val producer = isFoodProducer(npc.identity.occupation)
                val wage = if (producer) 0f else minOf(WORK_WAGE, state.economy.treasury).coerceAtLeast(0f)
                val routineEvent = GameEvent.NpcRoutineCompleted(
                    id = eventId,
                    time = time,
                    causalEventId = cause,
                    npc = npc.id,
                    routineType = "WORK",
                    location = intent.location,
                    deltaCoins = wage,
                    deltaFood = if (producer) 1.5f else -0.25f
                )
                val worked = reducer.apply(state, routineEvent)
                if (producer) worked else buyRationsIfHungry(worked, npc.id)
            }
            is Intent.Socialize -> {
                val shiftEvent = GameEvent.RelationshipShifted(
                    id = eventId,
                    time = time,
                    causalEventId = cause,
                    source = npc.id.toEntityId(),
                    target = intent.target.toEntityId(),
                    deltaTrust = 0.05f,
                    deltaAffection = 0.05f,
                    deltaRespect = 0.02f,
                    deltaFear = 0f,
                    deltaResentment = -0.02f,
                    reason = "Friendly conversation in village"
                )
                reducer.apply(state, shiftEvent)
            }
            is Intent.ShareRumor -> {
                val targetNpc = state.npcs[intent.target]
                if (targetNpc == null || !targetNpc.isAlive) return state

                val transmitted = transmitBelief(npc, targetNpc, intent.belief, time, state.relationships)
                val rumorEvent = GameEvent.RumorTransmitted(
                    id = eventId,
                    time = time,
                    source = npc.id,
                    target = intent.target,
                    transmittedBelief = transmitted,
                    distortion = 0.1f,
                    causalEventId = CausalLinks.originOf(state.events, intent.belief.proposition, time)
                )
                reducer.apply(state, rumorEvent)
            }
            is Intent.Trade -> {
                val tradeEvent = GameEvent.TradeCompleted(
                    id = eventId,
                    time = time,
                    causalEventId = cause,
                    buyer = intent.partner,
                    seller = npc.id.toEntityId(),
                    item = intent.item,
                    price = intent.price
                )
                reducer.apply(state, tradeEvent)
            }
            is Intent.ChangePrice -> {
                val oldPrice = state.economy.getPrice(intent.item)
                val priceEvent = GameEvent.PriceAdjusted(
                    id = eventId,
                    time = time,
                    causalEventId = cause,
                    merchant = npc.id,
                    item = intent.item,
                    oldPrice = oldPrice,
                    newPrice = intent.newPrice,
                    reason = intent.reason
                )
                reducer.apply(state, priceEvent)
            }
            is Intent.ChangeAllegiance -> {
                val oldFaction = npc.factions.firstOrNull()
                val changeEvent = GameEvent.AllegianceChanged(
                    id = eventId,
                    time = time,
                    causalEventId = cause,
                    npc = npc.id,
                    oldFaction = oldFaction,
                    newFaction = intent.newFaction,
                    reason = intent.reason
                )
                reducer.apply(state, changeEvent)
            }
            is Intent.DeclareHostility -> {
                val warEvent = GameEvent.FactionHostilityDeclared(
                    id = eventId,
                    time = time,
                    causalEventId = CausalLinks.deathOf(state.events, intent.victim) ?: cause,
                    declaringFaction = intent.declaring,
                    targetFaction = intent.target,
                    reason = intent.reason
                )
                reducer.apply(state, warEvent)
            }
            is Intent.Attack -> executeAttack(state, npc, intent, eventId, cause)
            is Intent.InheritShop -> {
                val inheritEvent = GameEvent.ShopInherited(
                    id = eventId,
                    time = time,
                    heir = npc.id,
                    predecessor = intent.deceased,
                    shopName = intent.shopName,
                    causalEventId = CausalLinks.deathOf(state.events, intent.deceased)
                )
                reducer.apply(state, inheritEvent)
            }
            is Intent.Flee -> {
                val routineEvent = GameEvent.NpcRoutineCompleted(
                    id = eventId,
                    time = time,
                    causalEventId = cause,
                    npc = npc.id,
                    routineType = "FLEE",
                    location = npc.identity.currentLocation,
                    deltaFear = -0.1f
                )
                reducer.apply(state, routineEvent)
            }
        }
    }

    /** Resolves one duel exchange; everyone else alive at the attacker's location witnesses it. */
    private fun executeAttack(state: WorldState, npc: Npc, intent: Intent.Attack, eventId: EventId, cause: EventId?): WorldState {
        val time = state.time
        val target = state.npcs[intent.target] ?: return state
        if (!target.isAlive) return state

        val duel = TacticalCombatResolver.resolveExchange(npc, target, time)
        val witnesses = state.npcs.values
            .filter {
                it.isAlive && it.id != npc.id && it.id != intent.target &&
                it.identity.currentLocation == npc.identity.currentLocation
            }
            .sortedBy { it.id.value }
            .map { it.id }

        val attackEvent = GameEvent.NpcAttacked(
            id = eventId,
            time = time,
            causalEventId = cause,
            attacker = npc.id.toEntityId(),
            target = intent.target,
            damage = duel.finalDamage,
            location = npc.identity.currentLocation,
            witnesses = witnesses,
            attackerStance = duel.attackerStance,
            defenderStance = duel.defenderStance,
            duelOutcome = duel.outcome
        )
        return AttackEvents.apply(reducer, state, attackEvent, motive = "${intent.reason} (${duel.outcome.description})")
    }

    companion object {
        const val WORK_WAGE = 0.25f
        const val DAILY_UPKEEP = 3f
        const val STARVATION_DAMAGE = 30f
        const val RATION_FOOD = 8f
        const val RATION_PRICE = 3f
        const val RATION_TAX = 1f
        const val RATION_HUNGER_THRESHOLD = 5f
        const val RATION_SELLER_RESERVE = 5f
    }

    fun transmitBelief(
        source: Npc,
        target: Npc,
        belief: Belief,
        currentTime: WorldTime,
        relationships: RelationshipGraph
    ): Belief {
        val relScore = (target.reputationToward(source.id.toEntityId(), relationships) + 1.0f) / 2.0f
        val reliability = (relScore * belief.confidence * belief.reliability).coerceIn(0.1f, 0.95f)
        val distortionFactor = if (source.hasTrait(Trait.OPPORTUNISTIC)) 0.85f else 0.95f
        val newConfidence = (belief.confidence * reliability * distortionFactor).coerceIn(0.1f, 1.0f)

        return Belief(
            proposition = belief.proposition,
            confidence = newConfidence,
            source = KnowledgeSource.Hearsay(source.id),
            reliability = reliability,
            acquiredAt = currentTime
        )
    }
}
