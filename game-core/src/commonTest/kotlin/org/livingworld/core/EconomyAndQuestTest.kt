package org.livingworld.core

import org.livingworld.core.events.GameEvent
import org.livingworld.core.ids.*
import org.livingworld.core.world.DefaultWorldReducer
import org.livingworld.core.world.QuestStatus
import kotlin.test.*

/** Trades conserve coins; scarcity raises quests that a player trade completes. */
class EconomyAndQuestTest {
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
}
