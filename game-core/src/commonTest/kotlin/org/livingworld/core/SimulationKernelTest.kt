package org.livingworld.core

import kotlinx.coroutines.test.runTest
import org.livingworld.core.beliefs.Belief
import org.livingworld.core.beliefs.KnowledgeSource
import org.livingworld.core.beliefs.Proposition
import org.livingworld.core.events.DuelOutcome
import org.livingworld.core.events.GameEvent
import org.livingworld.core.events.TacticalCombatResolver
import org.livingworld.core.events.TacticalStance
import org.livingworld.core.ids.*
import org.livingworld.core.inspector.SimulationInspector
import org.livingworld.core.simulation.DefaultNpcDecisionEngine
import org.livingworld.core.simulation.Intent
import org.livingworld.core.time.WorldDuration
import org.livingworld.core.time.WorldTime
import org.livingworld.core.world.DefaultWorldReducer
import org.livingworld.core.world.QuestStatus
import kotlin.test.*

class SimulationKernelTest {

    @Test
    fun npc_cannot_know_unwitnessed_theft() {
        val world = TestWorld.create()
        val player = EntityId.Player("player")
        val sword = ItemId("iron_sword")

        world.steal(
            thief = player,
            item = sword,
            owner = NpcId("merchant").toEntityId(),
            witnesses = emptyList()
        )

        val npc = world.npc(NpcId("merchant"))

        assertFalse(
            npc.beliefs.knows(Proposition.PlayerStole(sword)),
            "Unwitnessed theft must not produce omniscience in victim (Invariant G2)"
        )
        assertEquals(
            0f,
            world.relationships.get(NpcId("merchant").toEntityId(), player).resentment,
            "Unwitnessed theft must not shift relationship resentment toward unseen thief (Invariant G2)"
        )
    }

    @Test
    fun npc_witnessed_theft_creates_belief_and_syncs_relationship() {
        val world = TestWorld.create()
        val player = EntityId.Player("player")
        val sword = ItemId("iron_sword")
        val witness = NpcId("guard_captain")

        world.steal(
            thief = player,
            item = sword,
            owner = NpcId("merchant").toEntityId(),
            witnesses = listOf(witness)
        )

        val witnessNpc = world.npc(witness)

        assertTrue(
            witnessNpc.beliefs.knows(
                Proposition.PlayerStole(sword),
                minConfidence = 0.8f
            ),
            "Witnessed theft must form high-confidence belief in witness"
        )
        assertTrue(
            witnessNpc.reputationToward(player) < 0f,
            "RT-3: Witness local RelationshipGraph must synchronize with WorldState.relationships"
        )
    }

    @Test
    fun npc_death_is_persistent_and_triggers_apprentice_shop_inheritance_candidate() {
        val world = TestWorld.create()
        val merchantId = NpcId("merchant")
        val apprenticeId = NpcId("apprentice")

        world.kill(merchantId)

        assertTrue(
            world.isDead(merchantId),
            "Killed NPC must be persistently marked dead (Invariant 4)"
        )
        assertFalse(
            world.npc(merchantId).isAlive,
            "NPC isAlive property must reflect death"
        )

        val decisionEngine = DefaultNpcDecisionEngine()
        val candidates = decisionEngine.generateCandidates(world.npc(apprenticeId), world.state)
        assertTrue(
            candidates.any { it.action is Intent.InheritShop },
            "RT-5: Apprentice must generate InheritShop candidate when master merchant dies"
        )
    }

    @Test
    fun death_creates_social_consequences_and_hostility_candidates() {
        val world = TestWorld.create()
        val merchantId = NpcId("merchant")
        val apprenticeId = NpcId("apprentice")
        val killer = NpcId("miner_derek").toEntityId()

        world.kill(
            victim = merchantId,
            killer = killer,
            witnesses = listOf(apprenticeId)
        )

        assertTrue(
            world.eventLedger.containsDeath(merchantId),
            "Event ledger must record death event"
        )
        assertTrue(
            world.relationships.containsChangedRelationships(),
            "Relationships must change upon death of an associate"
        )

        val apprenticeRelToKiller = world.relationships.get(apprenticeId.toEntityId(), killer)
        assertTrue(
            apprenticeRelToKiller.resentment > 0.5f,
            "Apprentice must resent the killer of their master"
        )
        assertTrue(
            apprenticeRelToKiller.trust < 0f,
            "Apprentice trust in killer must plummet"
        )

        val decisionEngine = DefaultNpcDecisionEngine()
        val candidates = decisionEngine.generateCandidates(world.npc(apprenticeId), world.state)
        assertTrue(
            candidates.any { it.action is Intent.Attack && it.action.target == NpcId("miner_derek") },
            "RT-3: Apprentice must generate Attack candidate against master's killer"
        )
    }

    @Test
    fun rumor_propagation_distorts_and_avoids_echo_back_to_source() {
        val world = TestWorld.create()
        val source = NpcId("merchant")
        val target = NpcId("apprentice")

        val originalBelief = Belief(
            proposition = Proposition.EntityIsHostile(EntityId.Player("bandit_scout")),
            confidence = 0.9f,
            source = KnowledgeSource.DirectObservation,
            reliability = 0.95f,
            acquiredAt = WorldTime.ZERO
        )

        world.spreadRumor(source, target, originalBelief)

        val targetNpc = world.npc(target)
        val acquired = targetNpc.beliefs.get(originalBelief.proposition)

        assertNotNull(acquired, "Target must acquire rumor belief")
        assertTrue(
            acquired.source is KnowledgeSource.Hearsay,
            "Rumor source must be recorded as Hearsay"
        )
        assertEquals(source, (acquired.source as KnowledgeSource.Hearsay).sourceNpc)

        val candidates = DefaultNpcDecisionEngine().generateCandidates(targetNpc, world.state)
        assertFalse(
            candidates.any {
                it.action is Intent.ShareRumor &&
                it.action.target == source &&
                it.action.belief.proposition == originalBelief.proposition
            },
            "RT-4: NPC must not echo a hearsay rumor back to the original sourceNpc"
        )
    }

    @Test
    fun tactical_combat_stance_matrix_and_weapon_bonus_resolve_deterministically() {
        val world = TestWorld.create()
        val captain = world.npc(NpcId("guard_captain"))
        val merchant = world.npc(NpcId("merchant"))

        val counterExchange = TacticalCombatResolver.resolveExchange(
            attacker = captain,
            defender = merchant,
            time = WorldTime(60L),
            overrideAttackerStance = TacticalStance.STRIKE,
            overrideDefenderStance = TacticalStance.FEINT
        )
        assertEquals(DuelOutcome.COUNTER_STRIKE, counterExchange.outcome)
        assertTrue(counterExchange.finalDamage > counterExchange.baseDamage)

        val parriedExchange = TacticalCombatResolver.resolveExchange(
            attacker = captain,
            defender = merchant,
            time = WorldTime(60L),
            overrideAttackerStance = TacticalStance.STRIKE,
            overrideDefenderStance = TacticalStance.WARD
        )
        assertEquals(DuelOutcome.PARRIED, parriedExchange.outcome)
        assertEquals(counterExchange.baseDamage * 0.5f, parriedExchange.finalDamage)

        val armedCaptain = captain.copy(resources = captain.resources.copy(items = listOf(ItemId("iron_sword"))))
        val armedExchange = TacticalCombatResolver.resolveExchange(
            attacker = armedCaptain,
            defender = merchant,
            time = WorldTime(60L),
            overrideAttackerStance = TacticalStance.STRIKE,
            overrideDefenderStance = TacticalStance.STRIKE
        )
        assertEquals(45f, armedExchange.baseDamage)
    }

    @Test
    fun procedural_quest_engine_dispatches_and_completes_scarcity_quests() {
        val reducer = DefaultWorldReducer()
        var state = TestWorld.create().state.copy(
            economy = TestWorld.create().state.economy.copy(
                scarcityFactors = mapOf("bread" to 1.8f)
            )
        )

        val problems = state.activeProblems()
        assertTrue(problems.any { it.id == "problem_scarcity_bread" })

        val opportunities = state.generateOpportunities()
        assertEquals(1, opportunities.size)
        val opp = opportunities.first()
        assertEquals("quest_scarcity_bread", opp.id)

        // Dispatch via OpportunityDispatched event (RT-15 deduplication verified)
        val dispatchEv = GameEvent.OpportunityDispatched(
            id = state.events.nextId(),
            time = state.time,
            opportunityId = opp.id,
            opportunityType = "CRISIS_QUEST",
            title = opp.title,
            location = LocationId("market"),
            urgency = 0.6f,
            narrativeHook = opp.description,
            actors = listOf(opp.issuer)
        )
        state = reducer.apply(state, dispatchEv)
        state = reducer.apply(state, dispatchEv) // Duplicate should be ignored by RT-15 guard
        assertEquals(1, state.activeQuests.size)
        assertEquals(QuestStatus.AVAILABLE, state.activeQuests.first().status)

        // Complete scarcity quest via TradeCompleted (RT-16)
        val coinsBefore = state.player.coins
        val tradeEv = GameEvent.TradeCompleted(
            id = state.events.nextId(),
            time = state.time,
            buyer = NpcId("apprentice").toEntityId(),
            seller = state.player.id,
            item = ItemId("bread"),
            price = 9f
        )
        state = reducer.apply(state, tradeEv)
        assertEquals(QuestStatus.COMPLETED, state.activeQuests.first().status)
        assertTrue(state.player.coins > coinsBefore + 9f, "RT-16: Player must receive quest reward upon completing scarcity trade")
    }

    @Test
    fun trade_conserves_coins_when_buyer_cannot_afford_price() {
        val reducer = DefaultWorldReducer()
        val base = TestWorld.create().state
        val buyer = base.npc(NpcId("apprentice"))
        val seller = base.npc(NpcId("merchant"))
        val broke = base.copy(
            npcs = base.npcs + (buyer.id to buyer.copy(resources = buyer.resources.copy(coins = 4f)))
        )
        val coinsBefore = broke.npc(buyer.id).resources.coins + broke.npc(seller.id).resources.coins

        val after = reducer.apply(
            broke,
            GameEvent.TradeCompleted(
                id = broke.events.nextId(),
                time = broke.time,
                buyer = buyer.id.toEntityId(),
                seller = seller.id.toEntityId(),
                item = ItemId("bread"),
                price = 9f
            )
        )

        val coinsAfter = after.npc(buyer.id).resources.coins + after.npc(seller.id).resources.coins
        assertEquals(coinsBefore, coinsAfter, "A trade must move coins, never create them")
    }

    @Test
    fun trade_with_unknown_buyer_does_not_pay_seller() {
        val reducer = DefaultWorldReducer()
        val base = TestWorld.create().state
        val seller = base.npc(NpcId("merchant"))

        val after = reducer.apply(
            base,
            GameEvent.TradeCompleted(
                id = base.events.nextId(),
                time = base.time,
                buyer = NpcId("ghost").toEntityId(),
                seller = seller.id.toEntityId(),
                item = ItemId("bread"),
                price = 9f
            )
        )

        assertEquals(seller.resources.coins, after.npc(seller.id).resources.coins)
    }

    @Test
    fun deterministic_event_replay_matches_live_simulation() = runTest {
        val initialWorld = TestWorld.create().state
        val engine = TestWorld.create().simulationEngine
        val reducer = DefaultWorldReducer()

        val liveEndState = engine.advance(initialWorld, WorldDuration.hours(6))
        val recordedEvents = liveEndState.events.all()
        assertTrue(recordedEvents.isNotEmpty(), "RT-1: Simulation ticks must record events in EventLedger")

        var replayedState = initialWorld
        for (ev in recordedEvents) {
            replayedState = reducer.apply(replayedState, ev)
        }

        assertEquals(liveEndState.time, replayedState.time, "RT-2: Replayed state time must equal live state time")
        assertEquals(liveEndState.npcs, replayedState.npcs, "RT-1: Replayed NPC states must match live simulation bit-for-bit")
    }

    @Test
    fun ninety_day_soak_maintains_food_equilibrium_and_determinism() = runTest {
        val engine = TestWorld.create().simulationEngine
        val worldA = engine.advance(TestWorld.create().state, WorldDuration.days(90))
        val worldB = engine.advance(TestWorld.create().state, WorldDuration.days(90))

        assertEquals(90L * WorldTime.TICKS_PER_DAY, worldA.time.tick)
        assertEquals(worldA.npcs, worldB.npcs, "Invariant G8: 90-day soak must be bit-for-bit deterministic")
        assertTrue(
            worldA.npcs.values.filter { it.isAlive }.all { it.resources.food >= 0.5f },
            "RT-14: All living NPCs must maintain non-zero food reserves over 90 days"
        )
    }

    @Test
    fun explainable_narrative_arc_reconstruction() = runTest {
        val world = TestWorld.create()
        val merchantId = NpcId("merchant")
        val apprenticeId = NpcId("apprentice")
        val player = EntityId.Player("player")
        val ironGuild = FactionId("iron_guild")
        val verdantOrder = FactionId("verdant_order")
        val bread = ItemId("bread")

        world.state = world.state.copy(time = WorldTime(12L * WorldTime.TICKS_PER_DAY))
        world.meet(merchantId.toEntityId(), player)

        world.state = world.state.copy(time = WorldTime(14L * WorldTime.TICKS_PER_DAY))
        world.trust(merchantId.toEntityId(), player, 0.4f, "Player assisted with trade caravan")

        world.state = world.state.copy(time = WorldTime(21L * WorldTime.TICKS_PER_DAY))
        val rumorBelief = Belief(
            proposition = Proposition.NpcLoyalToFaction(NpcId("guildmaster_titus"), ironGuild),
            confidence = 0.8f,
            source = KnowledgeSource.DirectObservation,
            reliability = 0.9f,
            acquiredAt = world.state.time
        )
        val rumorEvent = world.spreadRumor(NpcId("elder_rowan"), merchantId, rumorBelief)

        world.state = world.state.copy(time = WorldTime(24L * WorldTime.TICKS_PER_DAY))
        val allegianceEvent = GameEvent.AllegianceChanged(
            id = EventId(world.eventLedger.size + 1L),
            time = world.state.time,
            npc = merchantId,
            oldFaction = ironGuild,
            newFaction = verdantOrder,
            reason = "Disillusionment following Elder Rowan's revelations",
            causalEventId = rumorEvent.id
        )
        world.state = DefaultWorldReducer().apply(world.state, allegianceEvent)

        world.state = world.state.copy(time = WorldTime(29L * WorldTime.TICKS_PER_DAY))
        val attackEvent = GameEvent.NpcAttacked(
            id = EventId(world.eventLedger.size + 1L),
            time = world.state.time,
            attacker = NpcId("miner_derek").toEntityId(),
            target = merchantId,
            damage = 80f,
            location = LocationId("village_square"),
            witnesses = listOf(apprenticeId),
            causalEventId = allegianceEvent.id,
            attackerStance = TacticalStance.STRIKE,
            defenderStance = TacticalStance.FEINT,
            duelOutcome = DuelOutcome.COUNTER_STRIKE
        )
        world.state = DefaultWorldReducer().apply(world.state, attackEvent)

        world.state = world.state.copy(time = WorldTime(30L * WorldTime.TICKS_PER_DAY))
        val deathEvent = world.kill(
            victim = merchantId,
            killer = NpcId("miner_derek").toEntityId(),
            witnesses = listOf(apprenticeId),
            causalEventId = attackEvent.id,
            motive = "Retribution for Guild betrayal"
        )

        world.state = world.state.copy(time = WorldTime(31L * WorldTime.TICKS_PER_DAY))
        val inheritEvent = GameEvent.ShopInherited(
            id = EventId(world.eventLedger.size + 1L),
            time = world.state.time,
            heir = apprenticeId,
            predecessor = merchantId,
            shopName = "Alden's Sundries",
            causalEventId = deathEvent.id
        )
        world.state = DefaultWorldReducer().apply(world.state, inheritEvent)

        world.state = world.state.copy(time = WorldTime(36L * WorldTime.TICKS_PER_DAY))
        val priceEvent = GameEvent.PriceAdjusted(
            id = EventId(world.eventLedger.size + 1L),
            time = world.state.time,
            merchant = apprenticeId,
            item = bread,
            oldPrice = 5f,
            newPrice = 12f,
            reason = "Supply shortage and apprentice debts",
            causalEventId = inheritEvent.id
        )
        world.state = DefaultWorldReducer().apply(world.state, priceEvent)

        world.state = world.state.copy(time = WorldTime(42L * WorldTime.TICKS_PER_DAY))
        val hostilityEvent = GameEvent.FactionHostilityDeclared(
            id = EventId(world.eventLedger.size + 1L),
            time = world.state.time,
            declaringFaction = verdantOrder,
            targetFaction = ironGuild,
            reason = "Murder of merchant Alden in village square",
            causalEventId = deathEvent.id
        )
        world.state = DefaultWorldReducer().apply(world.state, hostilityEvent)

        val inspector = SimulationInspector(world.state)
        assertTrue(inspector.inspectNpc(apprenticeId).contains("Alden's Sundries"))
        val chain = world.eventLedger.traceCausalChain(hostilityEvent.id)

        assertTrue(chain.size >= 4, "Causal chain must link back through antecedent events")
        assertEquals(rumorEvent.id, chain.first().id, "Chain root must be the rumor that caused the allegiance shift")
        assertEquals(allegianceEvent.id, chain[1].id, "Allegiance shift must follow the rumor")
        assertEquals(hostilityEvent.id, chain.last().id, "Chain terminus must be the hostility declaration")

        assertEquals("Shopkeeper (Alden's Sundries)", world.npc(apprenticeId).identity.occupation)
        assertEquals(12f, world.state.economy.getPrice(bread))
        assertTrue(world.state.factions[verdantOrder]!!.isEnemy(ironGuild))
    }
}
