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

    private fun intents(world: TestWorld, who: NpcId) = engine.generateCandidates(world.npc(who), world.state).map { it.action }

    @Test
    fun apprentice_inherits_only_after_learning_of_the_death() {
        val unwitnessed = TestWorld.create()
        unwitnessed.kill(merchant, killer = player)
        assertTrue(intents(unwitnessed, apprentice).none { it is Intent.InheritShop }, "death is not known to the apprentice")

        val witnessed = TestWorld.create()
        witnessed.kill(merchant, killer = player, witnesses = listOf(apprentice))
        assertTrue(intents(witnessed, apprentice).any { it is Intent.InheritShop })
    }

    @Test
    fun apprentice_learning_by_rumor_also_unlocks_inheritance() {
        val world = TestWorld.create()
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
}
