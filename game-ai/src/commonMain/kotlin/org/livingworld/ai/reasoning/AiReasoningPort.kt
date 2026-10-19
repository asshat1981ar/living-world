package org.livingworld.ai.reasoning

import org.livingworld.core.beliefs.Belief
import org.livingworld.core.memory.Memory
import org.livingworld.core.npc.Goal
import org.livingworld.core.npc.Npc
import org.livingworld.core.relationships.Relationship
import org.livingworld.core.simulation.ActionCandidate
import org.livingworld.core.simulation.AiProposal
import org.livingworld.core.simulation.Intent
import org.livingworld.core.world.WorldSnapshot

data class ReasoningContext(
    val npc: Npc,
    val relevantWorldState: WorldSnapshot,
    val memories: List<Memory>,
    val beliefs: List<Belief>,
    val relationships: List<Relationship>,
    val goals: List<Goal>,
    val candidates: List<ActionCandidate>
)

interface AiReasoningPort {
    suspend fun reason(
        context: ReasoningContext
    ): AiProposal
}

/**
 * Deterministic fallback that fulfills the advisory AI role when no LLM is configured or available.
 * Fulfills Invariant 7: "The game remains functional without AI."
 */
class DeterministicAiFallback : AiReasoningPort {

    override suspend fun reason(context: ReasoningContext): AiProposal {
        if (context.candidates.isEmpty()) {
            return AiProposal(
                selectedAction = Intent.Rest,
                confidence = 1.0f,
                explanation = "$FALLBACK_MARKER: no candidate actions available"
            )
        }

        val bestCandidate = context.candidates.maxByOrNull { candidate ->
            var score = candidate.estimatedUtility()
            for (goal in context.goals) {
                if (goal.urgency > 0.5f) {
                    score += 0.2f
                }
            }
            score
        } ?: context.candidates.first()

        return AiProposal(
            selectedAction = bestCandidate.action,
            confidence = 0.8f,
            explanation = "$FALLBACK_MARKER: highest expected utility and goal alignment"
        )
    }

    companion object {
        const val FALLBACK_MARKER = "Advisory deterministic fallback"

        fun isFallbackProposal(proposal: AiProposal): Boolean {
            val exp = proposal.explanation ?: return false
            return exp.contains(FALLBACK_MARKER, ignoreCase = true) || exp.contains("fallback", ignoreCase = true)
        }

        /**
         * Balanced JSON object extractor (RT-7): extracts the last top-level {...} JSON block
         * from potentially chatty LLM output so earlier prose braces do not corrupt parsing.
         */
        fun extractLastJsonObject(raw: String): String? {
            val cleaned = raw.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
            var depth = 0
            var lastStart = -1
            var bestJson: String? = null
            var inString = false
            var escaped = false

            for (i in cleaned.indices) {
                val c = cleaned[i]
                if (escaped) {
                    escaped = false
                    continue
                }
                if (c == '\\' && inString) {
                    escaped = true
                    continue
                }
                if (c == '"') {
                    inString = !inString
                    continue
                }
                if (!inString) {
                    if (c == '{') {
                        if (depth == 0) lastStart = i
                        depth++
                    } else if (c == '}') {
                        depth--
                        if (depth == 0 && lastStart != -1) {
                            bestJson = cleaned.substring(lastStart, i + 1)
                            lastStart = -1
                        }
                    }
                }
            }
            return bestJson
        }
    }
}
