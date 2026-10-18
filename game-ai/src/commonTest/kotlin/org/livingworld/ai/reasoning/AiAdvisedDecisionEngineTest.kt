package org.livingworld.ai.reasoning

import kotlinx.coroutines.test.runTest
import org.livingworld.core.ids.*
import org.livingworld.core.npc.Identity
import org.livingworld.core.npc.Npc
import org.livingworld.core.simulation.AiProposal
import org.livingworld.core.simulation.DefaultNpcDecisionEngine
import org.livingworld.core.simulation.Intent
import org.livingworld.core.simulation.SimulationEngine
import org.livingworld.core.world.Location
import org.livingworld.core.world.PlayerState
import org.livingworld.core.world.WorldState
import kotlin.test.*

/** G1/G7: the AI port advises, the kernel validates, and any failure falls back to the deterministic decision. */
class AiAdvisedDecisionEngineTest {
    private val square = LocationId("village_square")
    private val merchant = NpcId("merchant")

    private fun npc(id: String, occupation: String) =
        Npc(id = NpcId(id), identity = Identity(id, occupation, square, square))

    private fun world() = WorldState(
        locations = mapOf(square to Location(square, "Village Square")),
        npcs = listOf(npc("merchant", "Merchant"), npc("guard", "Guard")).associateBy { it.id },
        player = PlayerState(currentLocation = square)
    )

    private class FixedPort(val pick: (ReasoningContext) -> AiProposal) : AiReasoningPort {
        override suspend fun reason(context: ReasoningContext) = pick(context)
    }

    private suspend fun baseDecision(w: WorldState) = DefaultNpcDecisionEngine().decide(w.npc(merchant), w).intent

    @Test
    fun valid_candidate_pick_is_used() = runTest {
        val w = world()
        val utilityPick = baseDecision(w)
        val other = DefaultNpcDecisionEngine().generateCandidates(w.npc(merchant), w).map { it.action }
            .first { it != utilityPick }
        val engine = AiAdvisedDecisionEngine(FixedPort { AiProposal(other, 0.9f, "ai") })
        assertEquals(other, engine.decide(w.npc(merchant), w).intent)
    }

    @Test
    fun invented_action_is_ignored() = runTest {
        val w = world()
        val engine = AiAdvisedDecisionEngine(FixedPort { AiProposal(Intent.Attack(NpcId("guard"), "murder"), 0.9f, "ai") })
        assertEquals(baseDecision(w), engine.decide(w.npc(merchant), w).intent)
    }

    @Test
    fun throwing_port_falls_back() = runTest {
        val w = world()
        val engine = AiAdvisedDecisionEngine(FixedPort { error("model offline") })
        assertEquals(baseDecision(w), engine.decide(w.npc(merchant), w).intent)
    }

    @Test
    fun out_of_range_confidence_is_rejected_by_the_validator() = runTest {
        val w = world()
        val engine = AiAdvisedDecisionEngine(FixedPort { AiProposal(Intent.Rest, 7f, "ai") })
        assertEquals(baseDecision(w), engine.decide(w.npc(merchant), w).intent)
    }

    @Test
    fun survival_rule_is_never_handed_to_the_ai() = runTest {
        val w = world()
        val hurt = w.npc(merchant).let {
            it.copy(
                resources = it.resources.copy(health = 10f),
                emotionalState = it.emotionalState.shifted(deltaFear = 0.9f)
            )
        }
        val hw = w.copy(npcs = w.npcs + (merchant to hurt))
        var asked = false
        val engine = AiAdvisedDecisionEngine(FixedPort { asked = true; AiProposal(Intent.Rest, 1f, "ai") })
        assertTrue(engine.decide(hurt, hw).intent is Intent.Flee)
        assertFalse(asked)
    }

    @Test
    fun deterministic_fallback_port_gives_identical_runs() = runTest {
        suspend fun run(): WorldState =
            SimulationEngine(decisionEngine = AiAdvisedDecisionEngine(DeterministicAiFallback())).advance(
                world(), org.livingworld.core.time.WorldDuration.days(5)
            )
        assertEquals(run(), run())
    }
}
