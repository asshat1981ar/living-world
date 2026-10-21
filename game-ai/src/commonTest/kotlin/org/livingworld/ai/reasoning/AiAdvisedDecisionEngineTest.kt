package org.livingworld.ai.reasoning

import kotlinx.coroutines.test.runTest
import org.livingworld.core.ids.*
import org.livingworld.core.npc.Identity
import org.livingworld.core.npc.Npc
import org.livingworld.core.events.GameEvent
import org.livingworld.core.simulation.AdviceOutcome
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

    @Test
    fun the_advice_says_what_the_ai_did_with_each_decision() = runTest {
        val w = world()
        val candidates = DefaultNpcDecisionEngine().generateCandidates(w.npc(merchant), w).map { it.action }
        suspend fun adviceFrom(port: AiReasoningPort) = AiAdvisedDecisionEngine(port, source = "test").decideWithAdvice(w.npc(merchant), w).second

        val followed = adviceFrom(FixedPort { AiProposal(candidates[1], 0.9f, "why not") })!!
        assertEquals(AdviceOutcome.FOLLOWED, followed.outcome)
        assertEquals(1, followed.candidateIndex)
        assertEquals("why not", followed.explanation)
        assertEquals("test", followed.source)
        assertEquals(AdviceOutcome.NOT_A_CANDIDATE, adviceFrom(FixedPort { AiProposal(Intent.Attack(NpcId("guard"), "murder"), 0.9f, "ai") })!!.outcome)
        assertEquals(AdviceOutcome.PORT_FAILED, adviceFrom(FixedPort { error("model offline") })!!.outcome)
    }

    /** An unpredictable advisor: sometimes picks a candidate, sometimes invents an action, sometimes fails. */
    private class RandomPort(seed: Int) : AiReasoningPort {
        private val random = kotlin.random.Random(seed)
        override suspend fun reason(context: ReasoningContext): AiProposal = when (random.nextInt(10)) {
            0 -> error("model hiccup")
            1 -> AiProposal(Intent.Attack(NpcId("nobody"), "invented"), 0.5f, "made up")
            else -> AiProposal(context.candidates[random.nextInt(context.candidates.size)].action, 0.8f, "seed pick")
        }
    }

    private suspend fun runWith(port: AiReasoningPort): WorldState =
        SimulationEngine(decisionEngine = AiAdvisedDecisionEngine(port, source = "random")).advance(
            world(), org.livingworld.core.time.WorldDuration.days(3)
        )

    @Test
    fun a_run_steered_by_an_unpredictable_ai_repeats_exactly_from_its_recorded_advice() = runTest {
        val original = runWith(RandomPort(seed = 7))
        val records = original.events.all().filterIsInstance<GameEvent.AiAdviceRecorded>()
        assertTrue(records.size > 10)
        assertTrue(records.map { it.outcome }.toSet().size >= 3, "the run exercised following, inventing and failing")

        val repeated = runWith(RecordedAiReasoningPort(records))
        assertEquals(original.events.all(), repeated.events.all())
        assertEquals(original, repeated)

        val other = runWith(RandomPort(seed = 8))
        assertNotEquals(original.events.all(), other.events.all(), "a different advisor really does change the run")
    }
}
