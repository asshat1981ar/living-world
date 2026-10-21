package org.livingworld.ai.huggingface

import kotlinx.serialization.json.Json
import org.livingworld.ai.dialogue.ChatModel
import org.livingworld.ai.dialogue.GroundedDialogueVoice

/** An NPC dialogue voice backed by a Hugging Face hosted model (see [GroundedDialogueVoice] for the G3 rules). */
fun HuggingFaceDialogueAdapter(
    config: HuggingFaceEndpointConfig = HuggingFaceEndpointConfig.defaultFreeTier(),
    transport: HfTransport = OfflineHfTransport()
): GroundedDialogueVoice = GroundedDialogueVoice(HuggingFaceChatModel(config, transport))

/**
 * Chat completions through the Hugging Face router. The token goes only in the Authorization header, and only to an
 * allow-listed host (G9: the transport itself is supplied by the platform).
 */
class HuggingFaceChatModel(
    private val config: HuggingFaceEndpointConfig,
    private val transport: HfTransport
) : ChatModel {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The reply text, or a failure: the transport's own error, or [UnreadableReply] for a body that is not a chat reply. */
    override suspend fun complete(system: String?, user: String, temperature: Float, maxTokens: Int): Result<String> {
        val messages = listOfNotNull(
            system?.let { HfChatMessage(role = "system", content = it) },
            HfChatMessage(role = "user", content = user)
        )
        val requestJson = json.encodeToString(
            HfChatRequest.serializer(),
            HfChatRequest(model = config.modelId, messages = messages, temperature = temperature, max_tokens = maxTokens)
        )
        val headers = mutableMapOf("Content-Type" to "application/json")
        if (!config.apiToken.isNullOrBlank()) {
            headers["Authorization"] = "Bearer ${config.apiToken}"
        }
        val body = transport.postBounded(config, requestJson, headers).getOrElse { return Result.failure(it) }
        val content = try {
            json.decodeFromString<HfChatResponse>(body).choices.firstOrNull()?.message?.content?.trim()
        } catch (_: Exception) {
            null
        }
        return if (content.isNullOrBlank()) Result.failure(UnreadableReply()) else Result.success(content)
    }

    private class UnreadableReply : Exception("Hugging Face reply was not a chat completion")

    /** What went wrong, worded for the player: a bad token reads differently from a network or service problem. */
    override fun describeFailure(error: Throwable): String = when (error) {
        is HfHttpError -> when (error.status) {
            401, 403 -> "Hugging Face rejected the token (HTTP ${error.status})."
            402 -> "This Hugging Face account is out of credits (HTTP 402)."
            429 -> "Hugging Face is limiting requests; try again later (HTTP 429)."
            else -> "Hugging Face could not answer (HTTP ${error.status})."
        }
        is HfTimeout -> "Hugging Face did not answer in time."
        is UnreadableReply -> "Hugging Face sent a reply the game could not read."
        else -> "Could not reach Hugging Face."
    }
}
