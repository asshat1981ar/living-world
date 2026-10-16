package org.livingworld.ai.reasoning

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.livingworld.core.simulation.AiProposal

/**
 * Native interface bridging to the on-device llama.cpp engine.
 * Supports loading quantized GGUF models (e.g. qwen2.5-0.5b-instruct-q4_k_m.gguf)
 * with hardware acceleration (ARM NEON / OpenCL / Vulkan / QNN).
 */
interface LlamaEngineBridge {
    val isModelLoaded: Boolean
    suspend fun loadModel(modelPath: String, threads: Int = 4, contextSize: Int = 2048): Boolean
    suspend fun generate(prompt: String, maxTokens: Int = 256, temperature: Float = 0.3f): String
    fun unload()
}

/**
 * Structured LLM decision output schema for on-device JSON decoding.
 */
@Serializable
data class LlmDecisionPayload(
    val action_index: Int,
    val confidence: Float,
    val explanation: String
)

/**
 * On-Device Qwen 2.5 Reasoner implementing [AiReasoningPort].
 *
 * Invariants Enforced:
 * - G1: Zero AI imports in game-core (all AI logic isolated in game-ai).
 * - G2: Knowledge != Truth (reasoning context only receives subjective NPC beliefs).
 * - G3: Dialogue & decisions grounded strictly in NPC beliefs and memories.
 * - G7: Graceful degradation to [DeterministicAiFallback] if model is uninitialized,
 *       native inference fails, or schema validation rejects the output.
 */
class OnDeviceQwenReasoningEngine(
    private val llamaBridge: LlamaEngineBridge,
    private val fallback: DeterministicAiFallback = DeterministicAiFallback(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }
) : AiReasoningPort {

    override suspend fun reason(context: ReasoningContext): AiProposal {
        if (!llamaBridge.isModelLoaded || context.candidates.isEmpty()) {
            return fallback.reason(context)
        }

        return withContext(Dispatchers.Default) {
            try {
                val prompt = buildQwenPrompt(context)
                val rawResponse = llamaBridge.generate(prompt, maxTokens = 128, temperature = 0.2f)
                parseAndValidateResponse(rawResponse, context) ?: fallback.reason(context)
            } catch (_: Exception) {
                fallback.reason(context)
            }
        }
    }

    private fun sanitizeChatMlToken(input: String): String {
        return input
            .replace("<|im_start|>", "")
            .replace("<|im_end|>", "")
            .replace("<|endoftext|>", "")
    }

    fun buildQwenPrompt(context: ReasoningContext): String {
        val npc = context.npc
        val traitsStr = if (npc.traits.isEmpty()) "None" else npc.traits.joinToString(", ") { it.name }
        val fearsStr = if (npc.fears.isEmpty()) "None" else npc.fears.joinToString(", ") { sanitizeChatMlToken(it.description) }

        val beliefsSummary = if (context.beliefs.isEmpty()) "No notable rumors or beliefs."
        else context.beliefs.take(5).joinToString("; ") { b ->
            "\"${sanitizeChatMlToken(b.proposition.describe())}\" (confidence: ${(b.confidence * 100).toInt()}%)"
        }

        val goalsSummary = if (context.goals.isEmpty()) "Survive and maintain routine."
        else context.goals.sortedByDescending { it.effectiveScore }.take(3).joinToString("; ") {
            "${sanitizeChatMlToken(it.description)} (urgency: ${(it.urgency * 100).toInt()}%)"
        }

        val candidatesList = context.candidates.mapIndexed { index, candidate ->
            "[$index] ${sanitizeChatMlToken(candidate.action.description)} (utility: ${candidate.estimatedUtility()})"
        }.joinToString("\n")

        return buildString {
            append("<|im_start|>system\n")
            append("You are an on-device NPC behavioral simulation kernel for a living-world RPG.\n")
            append("Analyze the NPC's persona, subjective beliefs, goals, and candidate actions.\n")
            append("You MUST select the single best action from the candidates list by index.\n")
            append("Respond ONLY with valid JSON in this exact format:\n")
            append("{\"action_index\": <int>, \"confidence\": <float 0.0-1.0>, \"explanation\": \"<short justification>\"}\n")
            append("<|im_end|>\n")
            append("<|im_start|>user\n")
            append("NPC Identity: ${sanitizeChatMlToken(npc.identity.name)} (${sanitizeChatMlToken(npc.identity.occupation)})\n")
            append("Current Location: ${sanitizeChatMlToken(npc.identity.currentLocation.value)}\n")
            append("Personality Traits: $traitsStr\n")
            append("Fears: $fearsStr\n")
            append("Current Emotional State: Fear=${npc.emotionalState.fear}, Anger=${npc.emotionalState.anger}, Trust=${npc.emotionalState.trust}\n")
            append("Active Goals: $goalsSummary\n")
            append("Known Subjective Beliefs: $beliefsSummary\n\n")
            append("Candidate Actions:\n")
            append(candidatesList)
            append("\n\nSelect best action index and reply with JSON.\n")
            append("<|im_end|>\n")
            append("<|im_start|>assistant\n")
        }
    }

    private fun parseAndValidateResponse(raw: String, context: ReasoningContext): AiProposal? {
        val jsonStr = DeterministicAiFallback.extractLastJsonObject(raw) ?: return null
        val payload = json.decodeFromString<LlmDecisionPayload>(jsonStr)
        if (payload.action_index in context.candidates.indices) {
            val selected = context.candidates[payload.action_index]
            val safeConfidence = if (payload.confidence.isNaN()) 0.75f else payload.confidence.coerceIn(0.1f, 1.0f)
            return AiProposal(
                selectedAction = selected.action,
                confidence = safeConfidence,
                explanation = "On-Device Qwen 2.5: ${payload.explanation}"
            )
        }
        return null
    }
}
