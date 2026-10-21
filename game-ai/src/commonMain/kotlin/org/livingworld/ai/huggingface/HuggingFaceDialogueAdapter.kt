package org.livingworld.ai.huggingface

import kotlinx.serialization.json.Json
import org.livingworld.core.beliefs.Belief
import org.livingworld.core.dialogue.DialogueGrounding
import org.livingworld.core.dialogue.GroundingCheck
import org.livingworld.core.ids.LocationId
import org.livingworld.core.inspector.formatFloat2
import org.livingworld.core.npc.Npc
import org.livingworld.core.relationships.Relationship

/**
 * Dialogue generation adapter using Hugging Face models.
 * Strictly adheres to Invariant G3: "Dialogue facts must be grounded in NPC beliefs."
 * Strictly adheres to Invariant G9: "Zero platform leaks in commonMain."
 */
class HuggingFaceDialogueAdapter(
    private val config: HuggingFaceEndpointConfig = HuggingFaceEndpointConfig.defaultFreeTier(),
    private val transport: HfTransport = OfflineHfTransport()
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** How model replies have fared so far; watch [DialogueStats.rejected] before loosening the grounding check. */
    var stats = DialogueStats()
        private set

    suspend fun generateDialogueLine(
        npc: Npc,
        playerTopic: String,
        locationId: LocationId,
        beliefs: List<Belief>,
        relationship: Relationship
    ): String = generateGroundedLine(npc, playerTopic, locationId, beliefs, relationship)
        ?: generateDeterministicFallback(npc, playerTopic, beliefs)

    /**
     * The model's reply cut down to its grounded sentences, or null when the model failed or every sentence was
     * ungrounded. Callers that already show a kernel line use this to replace it only with a real model line.
     */
    suspend fun generateGroundedLine(
        npc: Npc,
        playerTopic: String,
        locationId: LocationId,
        beliefs: List<Belief>,
        relationship: Relationship
    ): String? {
        val prompt = buildDialoguePrompt(npc, playerTopic, locationId, beliefs, relationship)

        val reply = ask(
            listOf(
                HfChatMessage(
                    role = "system",
                    content = "You are roleplaying as ${npc.identity.name}, a ${npc.identity.occupation} in a dark gothic fantasy RPG. " +
                            "Speak in character with concise medieval phrasing. " +
                            "CRITICAL CONSTRAINT: You can only state or reference facts present in your KNOWN BELIEFS. Never fabricate facts."
                ),
                HfChatMessage(role = "user", content = prompt)
            ),
            temperature = 0.7f,
            maxTokens = 120
        )
        val content = reply.getOrElse { e ->
            stats = stats.copy(failed = stats.failed + 1, lastFailure = describeFailure(e))
            return null
        }

        // Keep every grounded sentence of the reply; only a reply with none falls back to the kernel line.
        val grounded = groundedPart(content, npc, locationId, beliefs)
        stats = when (grounded) {
            null -> stats.copy(rejected = stats.rejected + 1)
            content -> stats.copy(accepted = stats.accepted + 1)
            else -> stats.copy(salvaged = stats.salvaged + 1)
        }
        return grounded
    }

    /**
     * One tiny request to see whether the token and endpoint work: null when they do, otherwise the reason in plain
     * words. It does not count towards [stats].
     */
    suspend fun checkConnection(): String? =
        ask(listOf(HfChatMessage(role = "user", content = "Reply with the word OK.")), temperature = 0.1f, maxTokens = 4)
            .exceptionOrNull()?.let(::describeFailure)

    /** The reply text, or a failure: the transport's own error, or [UnreadableReply] for a body that is not a chat reply. */
    private suspend fun ask(messages: List<HfChatMessage>, temperature: Float, maxTokens: Int): Result<String> {
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
    private fun describeFailure(error: Throwable): String = when (error) {
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

    fun buildDialoguePrompt(
        npc: Npc,
        playerTopic: String,
        locationId: LocationId,
        beliefs: List<Belief>,
        relationship: Relationship
    ): String {
        val sanitizedTopic = sanitizePlayerText(playerTopic)
        val sb = StringBuilder()
        sb.appendLine("Speaker: ${npc.identity.name} (${npc.identity.occupation})")
        sb.appendLine("Location: ${locationId.value}")
        sb.appendLine("Relationship toward player: Sentiment=${formatFloat2(relationship.overallSentiment)}, Trust=${formatFloat2(relationship.trust)}")
        sb.appendLine("Player said / inquired: \"$sanitizedTopic\"")
        sb.appendLine("KNOWN BELIEFS:")
        // The same floored facts the reply is checked against: the model is never shown a belief it may not voice.
        val facts = DialogueGrounding.speakableFacts(beliefs)
        if (facts.isEmpty()) {
            sb.appendLine("(No specific rumors or secrets known)")
        } else {
            facts.forEach { f -> sb.appendLine("- ${f.line}") }
        }
        sb.appendLine("Respond to the player in 1-2 sentences.")
        return sb.toString()
    }

    /**
     * G3: a model reply may only name what the NPC believes, plus the speaker and the place. The player's own words
     * are not allowed: otherwise asking "Did Mara do it?" would let the model confirm a name the NPC never heard of.
     */
    private fun groundedPart(reply: String, npc: Npc, locationId: LocationId, beliefs: List<Belief>): String? =
        GroundingCheck.groundedPart(
            line = reply,
            facts = DialogueGrounding.speakableFacts(beliefs),
            extraAllowed = listOf(npc.identity.name, npc.id.value, locationId.value)
        )

    /**
     * Player text is untrusted: keep it on one line inside its quotes, with no chat-template tokens, quotes, backticks
     * or control characters that could close the field or pose as a prompt section.
     */
    internal fun sanitizePlayerText(text: String): String =
        text.replace(Regex("<\\|[^|]*\\|>"), " ")
            .map { c -> if (c.isISOControl() || c in "\"`<>|\\{}[]") ' ' else c }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(200)

    private fun generateDeterministicFallback(npc: Npc, playerTopic: String, beliefs: List<Belief>): String =
        DialogueGrounding.speakableFacts(beliefs).firstOrNull()?.line ?: DialogueGrounding.NOTHING_TO_TELL
}

/** Outcomes of model dialogue replies: used whole, trimmed to their grounded sentences, discarded, or unavailable. */
data class DialogueStats(
    val accepted: Int = 0,
    val salvaged: Int = 0,
    val rejected: Int = 0,
    val failed: Int = 0,
    /** Why the most recent failed reply failed, in plain words; kept after later successes. */
    val lastFailure: String? = null
)
