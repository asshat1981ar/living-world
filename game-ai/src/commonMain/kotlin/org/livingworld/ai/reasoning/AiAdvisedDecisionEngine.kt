package org.livingworld.ai.reasoning

import kotlinx.coroutines.CancellationException
import org.livingworld.core.events.GameEvent
import org.livingworld.core.ids.NpcId
import org.livingworld.core.npc.Npc
import org.livingworld.core.simulation.AdviceOutcome
import org.livingworld.core.simulation.AiAdvice
import org.livingworld.core.simulation.AiProposal
import org.livingworld.core.simulation.Intent
import org.livingworld.core.simulation.ActionValidator
import org.livingworld.core.simulation.DefaultActionValidator
import org.livingworld.core.simulation.DefaultNpcDecisionEngine
import org.livingworld.core.simulation.NpcDecisionEngine
import org.livingworld.core.simulation.ValidatedAction
import org.livingworld.core.world.WorldState

/**
 * The AI port advises; the kernel decides (G1, G7). The port only chooses among candidates the
 * rule/utility engine generated, its pick still goes through [ActionValidator], and any failure
 * (exception, invented action, rejected action) falls back to the deterministic [base] decision.
 * Survival rules are never handed to the AI. Replays stay deterministic because the world is rebuilt
 * from the events the chosen action produced, not from the AI's output.
 */
class AiAdvisedDecisionEngine(
    private val port: AiReasoningPort,
    private val base: DefaultNpcDecisionEngine = DefaultNpcDecisionEngine(),
    private val validator: ActionValidator = DefaultActionValidator(),
    /** Names the advisor in the recorded advice (P9). */
    private val source: String = "ai"
) : NpcDecisionEngine {
    override suspend fun decide(npc: Npc, world: WorldState): ValidatedAction = decideWithAdvice(npc, world).first

    /**
     * P9: the decision plus what the AI did with it, which the engine records so the run can be repeated with
     * [RecordedAiReasoningPort]. No advice when the AI was not consulted (survival rule, a single candidate).
     */
    override suspend fun decideWithAdvice(npc: Npc, world: WorldState): Pair<ValidatedAction, AiAdvice?> {
        val fallback = base.decide(npc, world)
        if (base.isSurvivalOverride(npc)) return fallback to null
        val candidates = base.generateCandidates(npc, world)
        if (candidates.size < 2) return fallback to null
        val here = npc.identity.currentLocation
        val snapshot = world.snapshotFor(here)
        val context = ReasoningContext(
            npc = npc,
            relevantWorldState = snapshot,
            memories = npc.memories.all(),
            beliefs = npc.beliefs.all().toList(),
            relationships = snapshot.presentNpcs.filter { it != npc.id }
                .map { world.relationships.get(npc.id.toEntityId(), it.toEntityId()) },
            goals = npc.goals,
            candidates = candidates
        )
        val proposal = try {
            port.reason(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fallback to AiAdvice(source, AdviceOutcome.PORT_FAILED)
        }
        val index = candidates.indexOfFirst { it.action == proposal.selectedAction }
        if (index < 0) return fallback to AiAdvice(source, AdviceOutcome.NOT_A_CANDIDATE, explanation = proposal.explanation)
        val validated = validator.validate(proposal, npc, world).getOrNull()
            ?: return fallback to AiAdvice(source, AdviceOutcome.REJECTED, index, proposal.explanation)
        return validated to AiAdvice(source, AdviceOutcome.FOLLOWED, index, proposal.explanation)
    }
}

/**
 * Plays back a run's recorded advice (P9), one record per consultation per NPC in order, so a run that used a
 * nondeterministic AI repeats exactly. It reproduces each outcome: the same candidate (followed, or rejected again by
 * the same rules), an action that is not a candidate, or a failure.
 */
class RecordedAiReasoningPort(records: List<GameEvent.AiAdviceRecorded>) : AiReasoningPort {
    private val queues: Map<NpcId, ArrayDeque<GameEvent.AiAdviceRecorded>> =
        records.groupBy { it.npc }.mapValues { (_, list) -> ArrayDeque(list) }

    override suspend fun reason(context: ReasoningContext): AiProposal {
        val record = queues[context.npc.id]?.removeFirstOrNull()
            ?: throw IllegalStateException("No recorded advice left for ${context.npc.id.value}")
        return when (record.outcome) {
            AdviceOutcome.FOLLOWED, AdviceOutcome.REJECTED ->
                AiProposal(context.candidates[requireNotNull(record.candidateIndex)].action, 1f, record.explanation)
            AdviceOutcome.NOT_A_CANDIDATE -> AiProposal(NOT_A_CANDIDATE, 1f, record.explanation)
            AdviceOutcome.PORT_FAILED -> throw IllegalStateException("Recorded advisor failure")
        }
    }

    private companion object {
        /** Never generated as a candidate, so the engine records NOT_A_CANDIDATE again. */
        val NOT_A_CANDIDATE: Intent = Intent.InheritShop(NpcId("recorded-not-a-candidate"), "")
    }
}
