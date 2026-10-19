package org.livingworld.ai.huggingface

import kotlinx.serialization.json.Json
import org.livingworld.ai.reasoning.AiReasoningPort
import org.livingworld.ai.reasoning.DeterministicAiFallback
import org.livingworld.ai.reasoning.ReasoningContext
import org.livingworld.core.simulation.AiProposal

/**
 * Hugging Face remote reasoning adapter.
 * Connects to Hugging Face Serverless Inference API, Inference Endpoints, or Spaces.
 * Strictly adheres to Invariant G1 (Zero AI imports in game-core) and Invariant G7 (Deterministic fallback on failure).
 */
class HuggingFaceReasoningAdapter(
    private val config: HuggingFaceEndpointConfig = HuggingFaceEndpointConfig.defaultFreeTier(),
    private val transport: HfTransport = OfflineHfTransport(),
    private val fallbackPort: AiReasoningPort = DeterministicAiFallback()
) : AiReasoningPort {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override suspend fun reason(context: ReasoningContext): AiProposal {
        if (context.candidates.isEmpty()) {
            return fallbackPort.reason(context)
        }

        val prompt = buildReasoningPrompt(context)

        val requestJson = if (config.useOpenAiChatFormat) {
            val req = HfChatRequest(
                model = config.modelId,
                messages = listOf(
                    HfChatMessage(
                        role = "system",
                        content = "You are an advisory reasoning engine for an NPC in a Living-World RPG. " +
                                "Select the optimal action candidate index. " +
                                "Respond strictly with a JSON object: {\"selected_index\": <int>, \"confidence\": <float 0..1>, \"explanation\": \"<brief text>\"}"
                    ),
                    HfChatMessage(role = "user", content = prompt)
                ),
                temperature = config.temperature,
                max_tokens = config.maxTokens
            )
            json.encodeToString(HfChatRequest.serializer(), req)
        } else {
            val req = HfTextGenerationRequest(
                inputs = prompt,
                parameters = HfGenerationParams(
                    max_new_tokens = config.maxTokens,
                    temperature = config.temperature,
                    return_full_text = false
                )
            )
            json.encodeToString(HfTextGenerationRequest.serializer(), req)
        }

        val headers = mutableMapOf("Content-Type" to "application/json")
        if (!config.apiToken.isNullOrBlank()) {
            headers["Authorization"] = "Bearer ${config.apiToken}"
        }

        val responseResult = transport.postBounded(config, requestJson, headers)
        if (responseResult.isFailure) {
            return fallbackPort.reason(context)
        }

        val responseBody = responseResult.getOrNull() ?: return fallbackPort.reason(context)

        return try {
            val contentText = if (config.useOpenAiChatFormat) {
                val chatResp = json.decodeFromString<HfChatResponse>(responseBody)
                chatResp.choices.firstOrNull()?.message?.content ?: ""
            } else {
                val genResults = json.decodeFromString<List<HfTextGenerationResult>>(responseBody)
                genResults.firstOrNull()?.generated_text ?: ""
            }

            parseProposalFromResponse(contentText, context)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            fallbackPort.reason(context)
        }
    }

    private suspend fun parseProposalFromResponse(rawContent: String, context: ReasoningContext): AiProposal {
        val jsonSubstring = DeterministicAiFallback.extractLastJsonObject(rawContent)
            ?: return fallbackPort.reason(context)

        val parsed = json.decodeFromString<ReasoningJsonResponse>(jsonSubstring)
        if (parsed.selected_index !in context.candidates.indices) {
            return fallbackPort.reason(context)
        }

        val chosenCandidate = context.candidates[parsed.selected_index]
        val safeConfidence = if (parsed.confidence.isNaN()) 0.8f else parsed.confidence.coerceIn(0.1f, 1.0f)
        return AiProposal(
            selectedAction = chosenCandidate.action,
            confidence = safeConfidence,
            explanation = parsed.explanation ?: "Selected by Hugging Face model (${config.modelId})"
        )
    }

    fun buildReasoningPrompt(context: ReasoningContext): String {
        val npc = context.npc
        val sb = StringBuilder()
        sb.appendLine("=== NPC IDENTITY ===")
        sb.appendLine("Name: ${npc.identity.name}")
        sb.appendLine("Occupation: ${npc.identity.occupation}")
        sb.appendLine("Current Location: ${npc.identity.currentLocation.value}")
        sb.appendLine("Traits: ${npc.traits.joinToString()}")
        sb.appendLine("Health: ${npc.resources.health}%, Coins: ${npc.resources.coins}")
        sb.appendLine("Emotions: fear=${npc.emotionalState.fear}, anger=${npc.emotionalState.anger}, trust=${npc.emotionalState.trust}, suspicion=${npc.emotionalState.suspicion}")

        sb.appendLine("\n=== ACTIVE GOALS ===")
        context.goals.forEachIndexed { i, g ->
            sb.appendLine("[$i] ${g.description} (Priority: ${g.priority}, Urgency: ${g.urgency})")
        }

        sb.appendLine("\n=== KNOWN BELIEFS ===")
        context.beliefs.take(3).forEach { b ->
            sb.appendLine("- ${b.proposition.describe()} (Confidence: ${b.confidence})")
        }

        sb.appendLine("\n=== CANDIDATE ACTIONS ===")
        context.candidates.forEachIndexed { i, c ->
            sb.appendLine("Candidate [$i]: ${c.action.description} (Estimated Utility: ${c.estimatedUtility()})")
        }

        sb.appendLine("\nSelect the candidate index best aligned with this NPC's state.")
        return sb.toString()
    }
}
