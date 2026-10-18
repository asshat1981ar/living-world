package org.livingworld.ai.reasoning

import kotlinx.coroutines.CancellationException
import org.livingworld.core.npc.Npc
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
    private val validator: ActionValidator = DefaultActionValidator()
) : NpcDecisionEngine {

    override suspend fun decide(npc: Npc, world: WorldState): ValidatedAction {
        val fallback = base.decide(npc, world)
        if (base.isSurvivalOverride(npc)) return fallback

        val candidates = base.generateCandidates(npc, world)
        if (candidates.size < 2) return fallback

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
            return fallback
        }

        if (candidates.none { it.action == proposal.selectedAction }) return fallback
        return validator.validate(proposal, npc, world).getOrNull() ?: fallback
    }
}
