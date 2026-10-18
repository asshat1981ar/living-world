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

    suspend fun generateDialogueLine(
        npc: Npc,
        playerTopic: String,
        locationId: LocationId,
        beliefs: List<Belief>,
        relationship: Relationship
    ): String {
        val prompt = buildDialoguePrompt(npc, playerTopic, locationId, beliefs, relationship)

        val requestJson = json.encodeToString(
            HfChatRequest.serializer(),
            HfChatRequest(
                model = config.modelId,
                messages = listOf(
                    HfChatMessage(
                        role = "system",
                        content = "You are roleplaying as ${npc.identity.name}, a ${npc.identity.occupation} in a dark gothic fantasy RPG. " +
                                "Speak in character with concise medieval phrasing. " +
                                "CRITICAL CONSTRAINT: You can only state or reference facts present in your KNOWN BELIEFS. Never fabricate facts."
                    ),
                    HfChatMessage(role = "user", content = prompt)
                ),
                temperature = 0.7f,
                max_tokens = 120
            )
        )

        val headers = mutableMapOf("Content-Type" to "application/json")
        if (!config.apiToken.isNullOrBlank()) {
            headers["Authorization"] = "Bearer ${config.apiToken}"
        }

        val result = transport.postBounded(config, requestJson, headers)
        if (result.isFailure) {
            return generateDeterministicFallback(npc, playerTopic, beliefs)
        }

        return try {
            val chatResp = json.decodeFromString<HfChatResponse>(result.getOrNull() ?: "")
            val content = chatResp.choices.firstOrNull()?.message?.content?.trim()
            if (!content.isNullOrBlank() && isGrounded(content, npc, playerTopic, locationId, beliefs)) content
            else generateDeterministicFallback(npc, playerTopic, beliefs)
        } catch (_: Exception) {
            generateDeterministicFallback(npc, playerTopic, beliefs)
        }
    }

    fun buildDialoguePrompt(
        npc: Npc,
        playerTopic: String,
        locationId: LocationId,
        beliefs: List<Belief>,
        relationship: Relationship
    ): String {
        val sanitizedTopic = playerTopic.replace("\n", " ").take(200)
        val sb = StringBuilder()
        sb.appendLine("Speaker: ${npc.identity.name} (${npc.identity.occupation})")
        sb.appendLine("Location: ${locationId.value}")
        sb.appendLine("Relationship toward player: Sentiment=${formatFloat2(relationship.overallSentiment)}, Trust=${formatFloat2(relationship.trust)}")
        sb.appendLine("Player said / inquired: \"$sanitizedTopic\"")
        sb.appendLine("KNOWN BELIEFS:")
        if (beliefs.isEmpty()) {
            sb.appendLine("(No specific rumors or secrets known)")
        } else {
            beliefs.forEach { b -> sb.appendLine("- ${b.proposition.describe()}") }
        }
        sb.appendLine("Respond to the player in 1-2 sentences.")
        return sb.toString()
    }

    /** G3: a model reply may only name what the NPC believes, plus the speaker, the place and the player's own words. */
    private fun isGrounded(reply: String, npc: Npc, playerTopic: String, locationId: LocationId, beliefs: List<Belief>): Boolean =
        GroundingCheck.isGrounded(
            line = reply,
            facts = DialogueGrounding.speakableFacts(beliefs),
            extraAllowed = listOf(npc.identity.name, npc.id.value, locationId.value, playerTopic)
        )

    private fun generateDeterministicFallback(npc: Npc, playerTopic: String, beliefs: List<Belief>): String =
        DialogueGrounding.speakableFacts(beliefs).firstOrNull()?.line ?: DialogueGrounding.NOTHING_TO_TELL
}
