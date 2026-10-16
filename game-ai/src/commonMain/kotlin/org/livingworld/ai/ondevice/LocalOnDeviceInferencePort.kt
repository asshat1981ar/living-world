package org.livingworld.ai.ondevice

import kotlinx.serialization.json.Json
import org.livingworld.ai.huggingface.OnDeviceModelSpec
import org.livingworld.ai.huggingface.ReasoningJsonResponse
import org.livingworld.ai.reasoning.AiReasoningPort
import org.livingworld.ai.reasoning.DeterministicAiFallback
import org.livingworld.ai.reasoning.ReasoningContext
import org.livingworld.core.simulation.AiProposal

/**
 * Multiplatform contract for running local, on-device small language models.
 * Implementations bind to LiteRT (MediaPipe), llama.cpp via JNI/C-interop, ExecuTorch, or ONNX Runtime.
 */
interface LocalOnDeviceInferencePort {
    val modelSpec: OnDeviceModelSpec

    fun isModelLoaded(): Boolean

    suspend fun generateCompletion(
        prompt: String,
        maxTokens: Int = 128,
        temperature: Float = 0.2f
    ): Result<String>
}

/**
 * Advisory AI reasoning adapter powered by an on-device local model.
 * Invariant G1: Output is strictly advisory.
 * Invariant G7: Deterministic fallback if model not loaded or inference fails.
 */
class LocalOnDeviceReasoningAdapter(
    val inferencePort: LocalOnDeviceInferencePort,
    private val fallbackPort: AiReasoningPort = DeterministicAiFallback()
) : AiReasoningPort {

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override suspend fun reason(context: ReasoningContext): AiProposal {
        if (!inferencePort.isModelLoaded() || context.candidates.isEmpty()) {
            return fallbackPort.reason(context)
        }

        val prompt = buildOnDevicePrompt(context)
        val result = inferencePort.generateCompletion(
            prompt = prompt,
            maxTokens = 64,
            temperature = 0.2f
        )

        return result.fold(
            onSuccess = { rawOutput ->
                parseOutputToProposal(rawOutput, context) ?: fallbackPort.reason(context)
            },
            onFailure = {
                fallbackPort.reason(context)
            }
        )
    }

    private fun buildOnDevicePrompt(context: ReasoningContext): String {
        val npc = context.npc
        val candidateLines = context.candidates.mapIndexed { idx, candidate ->
            "[$idx] ${candidate.action.description}"
        }.joinToString("\n")

        val knownBeliefs = context.beliefs.take(3).joinToString("; ") { it.summary }

        return """
            |You are an NPC decision assistant.
            |NPC: ${npc.identity.name} (${npc.identity.occupation})
            |Current Health: ${npc.resources.health}
            |Beliefs: $knownBeliefs
            |Candidate Actions:
            |$candidateLines
            |
            |Select the single best candidate index for this NPC.
            |Respond in JSON: {"selected_index": <int>, "confidence": <float>, "explanation": "<short>"}
        """.trimMargin()
    }

    private fun parseOutputToProposal(output: String, context: ReasoningContext): AiProposal? {
        try {
            val jsonSnippet = DeterministicAiFallback.extractLastJsonObject(output)
            if (jsonSnippet != null) {
                val parsed = jsonParser.decodeFromString<ReasoningJsonResponse>(jsonSnippet)
                if (parsed.selected_index in context.candidates.indices) {
                    val chosen = context.candidates[parsed.selected_index]
                    val safeConf = if (parsed.confidence.isNaN()) 0.8f else parsed.confidence.coerceIn(0.1f, 1.0f)
                    return AiProposal(
                        selectedAction = chosen.action,
                        confidence = safeConf,
                        explanation = parsed.explanation ?: "Selected by local on-device model (${inferencePort.modelSpec.modelId})"
                    )
                }
            }
        } catch (_: Exception) {
            // Fall through to regex heuristic
        }

        val regexMatch = Regex("""(?:selected_index["\s:]+|\[)(\d+)""").find(output)
        if (regexMatch != null) {
            val idx = regexMatch.groupValues[1].toIntOrNull()
            if (idx != null && idx in context.candidates.indices) {
                return AiProposal(
                    selectedAction = context.candidates[idx].action,
                    confidence = 0.75f,
                    explanation = "Extracted candidate [$idx] from local model output"
                )
            }
        }

        return null
    }
}

/**
 * Three-tier resilient AI Reasoning pipeline:
 * Tier 1: Local On-Device Model (zero latency, zero API cost, full privacy, offline)
 * Tier 2: Remote Hugging Face Inference / Free Space (richer context if network available)
 * Tier 3: Deterministic Rule & Utility Fallback (guaranteed execution, Invariant G7)
 */
class TieredResilientReasoningEngine(
    val localAdapter: LocalOnDeviceReasoningAdapter?,
    val remoteAdapter: AiReasoningPort?,
    val deterministicFallback: DeterministicAiFallback = DeterministicAiFallback()
) : AiReasoningPort {

    override suspend fun reason(context: ReasoningContext): AiProposal {
        // 1. Try Local On-Device (RT-6: verify proposal is not a fallback before short-circuiting)
        if (localAdapter != null && localAdapter.inferencePort.isModelLoaded()) {
            val proposal = localAdapter.reason(context)
            if (!DeterministicAiFallback.isFallbackProposal(proposal)) {
                return proposal
            }
        }

        // 2. Try Remote Hugging Face
        if (remoteAdapter != null) {
            try {
                val proposal = remoteAdapter.reason(context)
                if (!DeterministicAiFallback.isFallbackProposal(proposal)) {
                    return proposal
                }
            } catch (_: Exception) {
                // Network unavailable or rate limited, move to deterministic
            }
        }

        // 3. Deterministic Fallback (Invariant G7)
        return deterministicFallback.reason(context)
    }
}
