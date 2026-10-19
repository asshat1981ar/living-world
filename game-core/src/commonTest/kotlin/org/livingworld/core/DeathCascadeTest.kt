package org.livingworld.core

import kotlinx.coroutines.test.runTest
import org.livingworld.core.beliefs.Proposition
import org.livingworld.core.ids.*
import org.livingworld.core.simulation.DefaultNpcDecisionEngine
import org.livingworld.core.simulation.Intent
import org.livingworld.core.world.QuestOpportunity
import org.livingworld.core.world.QuestStatus
import kotlin.test.*

/** G4: a death has consequences beyond the people who saw it. */
class DeathCascadeTest {
    private val merchant = NpcId("merchant")
    private val apprentice = NpcId("apprentice")
    private val player = EntityId.Player("player")

    private fun TestWorld.beliefs(id: NpcId) = npc(id).beliefs.all().map { it.proposition }

    @Test
    fun faction_mates_who_were_absent_hear_of_the_death() {
        val world = TestWorld.create()
        val mate = NpcId("guildmaster_titus")
        assertTrue(world.npc(mate).factions.any { it in world.npc(merchant).factions })
        world.kill(merchant, killer = player, witnesses = emptyList())
        assertTrue(Proposition.EntityKilledNpc(player, merchant) in world.beliefs(mate))
        val belief = world.npc(mate).beliefs.get(Proposition.EntityKilledNpc(player, merchant))!!
        assertTrue(belief.confidence < 1f, "second-hand news is less certain than seeing it")
    }

    @Test
    fun strangers_do_not_hear() {
        val world = TestWorld.create()
        val stranger = NpcId("tanner_garth")
        assertTrue(world.npc(stranger).factions.isEmpty())
        world.kill(merchant, killer = player)
        assertTrue(world.beliefs(stranger).none { it is Proposition.EntityKilledNpc })
    }

    @Test
    fun killerless_death_is_learned_and_unlocks_inheritance() {
        val world = TestWorld.create()
        world.kill(merchant, killer = null, witnesses = listOf(apprentice))
        assertTrue(Proposition.NpcDied(merchant) in world.beliefs(apprentice))
        val intents = DefaultNpcDecisionEngine().generateCandidates(world.npc(apprentice), world.state).map { it.action }
        assertTrue(intents.any { it is Intent.InheritShop })
    }

    @Test
    fun quests_issued_by_the_dead_fail() {
        val world = TestWorld.create()
        val quest = QuestOpportunity(
            id = "q", title = "t", description = "d", issuer = apprentice.toEntityId(),
            target = LocationId("market").toEntityId(), reward = 45f, status = QuestStatus.AVAILABLE
        )
        world.state = world.state.copy(activeQuests = world.state.activeQuests + quest)
        world.kill(apprentice, killer = player)
        assertEquals(QuestStatus.FAILED, world.state.activeQuests.first { it.id == "q" }.status)
    }

    private suspend fun scenario(): TestWorld {
        val world = TestWorld.create()
        world.kill(merchant, killer = player, witnesses = listOf(NpcId("guard_captain")))
        world.kill(NpcId("tanner_garth"), killer = null)
        world.kill(apprentice, killer = NpcId("miner_derek").toEntityId(), witnesses = listOf(NpcId("elder_rowan")))
        world.advance(10)
        return world
    }

    /** The 30-day fingerprint run has no deaths, so this is the determinism evidence for the cascade. */
    @Test
    fun cascade_is_deterministic_over_ten_days() = runTest {
        val a = scenario()
        val b = scenario()
        assertEquals(a.state, b.state)
        assertEquals(a.state.events.size, b.state.events.size)
    }

    private fun tradeWorld(buyer: EntityId, seller: EntityId): TestWorld {
        val world = TestWorld.create()
        val quest = QuestOpportunity(
            id = "quest_scarcity_bread", title = "t", description = "d", issuer = apprentice.toEntityId(),
            target = LocationId("market").toEntityId(), reward = 45f, status = QuestStatus.AVAILABLE
        )
        world.state = world.state.copy(activeQuests = listOf(quest))
        val event = org.livingworld.core.events.GameEvent.TradeCompleted(
            id = EventId(world.state.events.size + 1L), time = world.state.time,
            buyer = buyer, seller = seller, item = ItemId("bread"), price = 5f
        )
        world.state = org.livingworld.core.world.DefaultWorldReducer().apply(world.state, event)
        return world
    }

    @Test
    fun npc_to_npc_trade_does_not_pay_the_player() {
        val world = tradeWorld(NpcId("guard_captain").toEntityId(), merchant.toEntityId())
        assertEquals(100f, world.state.player.coins)
        assertEquals(QuestStatus.AVAILABLE, world.state.activeQuests.first().status)
    }

    @Test
    fun player_trade_still_pays_the_reward() {
        val world = tradeWorld(player, merchant.toEntityId())
        assertEquals(QuestStatus.COMPLETED, world.state.activeQuests.first().status)
        assertEquals(100f - 5f + 45f, world.state.player.coins)
    }

    @Test
    fun npc_death_is_persistent_and_triggers_apprentice_shop_inheritance_candidate() {
        val world = TestWorld.create()
        val merchantId = NpcId("merchant")
        val apprenticeId = NpcId("apprentice")

        // G2: the apprentice only considers the inheritance once it knows of the death (here: it witnessed it).
        world.kill(merchantId, killer = NpcId("miner_derek").toEntityId(), witnesses = listOf(apprenticeId))

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
}
