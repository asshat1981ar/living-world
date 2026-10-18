package org.livingworld.core

import kotlinx.coroutines.test.runTest
import org.livingworld.core.beliefs.Belief
import org.livingworld.core.beliefs.KnowledgeSource
import org.livingworld.core.beliefs.Proposition
import org.livingworld.core.ids.*
import org.livingworld.core.simulation.DefaultNpcDecisionEngine
import org.livingworld.core.simulation.Intent
import kotlin.test.*

/** G2: NPCs decide from what they believe and observe, not from world truth or other minds. */
class BeliefDrivenDecisionTest {
    private val engine = DefaultNpcDecisionEngine()
    private val player = EntityId.Player("player")
    private val merchant = NpcId("merchant")
    private val apprentice = NpcId("apprentice")

    private fun fact(p: Proposition, world: TestWorld) = Belief(
        proposition = p, confidence = 0.9f, source = KnowledgeSource.DirectObservation, reliability = 1f, acquiredAt = world.state.time
    )

    /** The default fixture has the apprentice revering the merchant, so news of the death would reach them anyway. */
    private fun TestWorld.estrangeApprentice() {
        state = state.copy(relationships = org.livingworld.core.relationships.RelationshipGraph())
    }

    private fun intents(world: TestWorld, who: NpcId) = engine.generateCandidates(world.npc(who), world.state).map { it.action }

    @Test
    fun apprentice_inherits_only_after_learning_of_the_death() {
        val unwitnessed = TestWorld.create().also { it.estrangeApprentice() }
        unwitnessed.kill(merchant, killer = player)
        assertTrue(intents(unwitnessed, apprentice).none { it is Intent.InheritShop }, "death is not known to the apprentice")

        val witnessed = TestWorld.create()
        witnessed.kill(merchant, killer = player, witnesses = listOf(apprentice))
        assertTrue(intents(witnessed, apprentice).any { it is Intent.InheritShop })
    }

    @Test
    fun apprentice_learning_by_rumor_also_unlocks_inheritance() {
        val world = TestWorld.create().also { it.estrangeApprentice() }
        world.kill(merchant, killer = player, witnesses = listOf(NpcId("guard_captain")))
        val killing = fact(Proposition.EntityKilledNpc(player, merchant), world)
        world.state = world.state.copy(
            npcs = world.state.npcs + (NpcId("guard_captain") to world.npc(NpcId("guard_captain")).let { it.copy(beliefs = it.beliefs.addOrUpdate(killing)) })
        )
        assertTrue(intents(world, apprentice).none { it is Intent.InheritShop })

        world.spreadRumor(NpcId("guard_captain"), apprentice, killing)
        assertTrue(intents(world, apprentice).any { it is Intent.InheritShop })
    }

    @Test
    fun rumor_candidates_do_not_depend_on_what_the_listener_knows() {
        val world = TestWorld.create()
        val belief = fact(Proposition.GeneralFact("harvest", "good"), world)
        world.state = world.state.copy(
            npcs = world.state.npcs +
                (merchant to world.npc(merchant).let { it.copy(beliefs = it.beliefs.addOrUpdate(belief)) }) +
                (apprentice to world.npc(apprentice).let { it.copy(beliefs = it.beliefs.addOrUpdate(belief)) })
        )

        val shares = intents(world, merchant).filterIsInstance<Intent.ShareRumor>().filter { it.target == apprentice }
        assertEquals(1, shares.size, "merchant cannot see that the apprentice already knows")
    }

    @Test
    fun an_npc_does_not_repeat_a_rumor_it_already_told_someone() {
        val world = TestWorld.create()
        val belief = fact(Proposition.GeneralFact("harvest", "good"), world)
        world.state = world.state.copy(
            npcs = world.state.npcs + (merchant to world.npc(merchant).let { it.copy(beliefs = it.beliefs.addOrUpdate(belief)) })
        )
        world.spreadRumor(merchant, apprentice, belief)

        assertTrue(world.npc(merchant).memories.hasTold(apprentice, belief.proposition))
        val repeats = intents(world, merchant).filterIsInstance<Intent.ShareRumor>().filter { it.target == apprentice }
        assertTrue(repeats.isEmpty(), "merchant remembers telling the apprentice")
    }

    @Test
    fun trade_offer_does_not_peek_at_the_players_purse() = runTest {
        val world = TestWorld.create()
        world.state = world.state.copy(player = world.state.player.copy(coins = 0f))

        assertTrue(intents(world, merchant).any { it is Intent.Trade }, "the merchant cannot see the purse")
        val chosen = engine.decide(world.npc(merchant), world.state).intent
        assertFalse(chosen is Intent.Trade, "an unaffordable offer is rejected by the validator")
    }

    @Test
    fun scenario_with_a_killing_and_rumors_is_deterministic() = runTest {
        suspend fun run(): List<String> {
            val world = TestWorld.create()
            val killing = fact(Proposition.EntityKilledNpc(player, merchant), world)
            world.kill(merchant, killer = player, witnesses = listOf(NpcId("guard_captain"), apprentice))
            world.state = world.state.copy(
                npcs = world.state.npcs + (NpcId("guard_captain") to world.npc(NpcId("guard_captain")).let { it.copy(beliefs = it.beliefs.addOrUpdate(killing)) })
            )
            world.advance(5)
            return world.state.events.all().map { it.summary }
        }
        val first = run()
        assertTrue(first.any { it.contains("inherited") }, "scenario must exercise the changed paths")
        assertEquals(first, run())
    }

    private fun withBelief(world: TestWorld, who: NpcId, p: Proposition, confidence: Float = 1f, learnedAt: Long = 0L) {
        val b = Belief(p, confidence, KnowledgeSource.DirectObservation, 1f, org.livingworld.core.time.WorldTime(learnedAt))
        world.state = world.state.copy(npcs = world.state.npcs + (who to world.npc(who).let { it.copy(beliefs = it.beliefs.addOrUpdate(b)) }))
    }

    private fun rumorUtility(world: TestWorld, who: NpcId): Float? =
        engine.generateCandidates(world.npc(who), world.state)
            .filter { it.action is Intent.ShareRumor }.maxOfOrNull { it.estimatedUtility() }

    @Test
    fun fresh_certain_news_beats_routine_work_and_is_told() = runTest {
        val world = TestWorld.create()
        val guard = NpcId("guard_captain")
        withBelief(world, guard, Proposition.NpcDied(merchant))
        assertTrue(rumorUtility(world, guard)!! > 0.5f, "fresh news outranks Work (0.5)")
        assertTrue(engine.decide(world.npc(guard), world.state).intent is Intent.ShareRumor)
    }

    @Test
    fun stale_or_doubtful_beliefs_lose_to_work() = runTest {
        val stale = TestWorld.create()
        val guard = NpcId("guard_captain")
        withBelief(stale, guard, Proposition.NpcDied(merchant), learnedAt = 0L)
        stale.state = stale.state.copy(time = stale.state.time + org.livingworld.core.time.WorldDuration.days(5))
        assertTrue(rumorUtility(stale, guard)!! < 0.5f, "five-day-old news no longer outranks Work")

        val doubtful = TestWorld.create()
        withBelief(doubtful, guard, Proposition.NpcDied(merchant), confidence = 0.4f)
        assertTrue(rumorUtility(doubtful, guard)!! < 0.5f)
    }
}
