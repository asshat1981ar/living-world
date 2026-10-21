package org.livingworld.ai.dialogue

import org.livingworld.core.beliefs.Belief
import org.livingworld.core.dialogue.DialogueGrounding
import org.livingworld.core.dialogue.GroundingCheck
import org.livingworld.core.ids.LocationId
import org.livingworld.core.inspector.formatFloat2
import org.livingworld.core.npc.Npc
import org.livingworld.core.relationships.Relationship

/**
 * Something that turns a system instruction and a user message into reply text: a hosted model (Hugging Face) or one
 * running on the phone. It knows nothing about NPCs or grounding; [GroundedDialogueVoice] does that for every model.
 */
interface ChatModel {
    suspend fun complete(system: String?, user: String, temperature: Float, maxTokens: Int): Result<String>

    /** A failure from [complete], worded for the player (a bad token reads differently from a missing model file). */
    fun describeFailure(error: Throwable): String
}

/**
 * Lets a [ChatModel] voice NPC lines under G3: the model only sees the NPC's speakable beliefs, the player's text is
 * sanitised, and only the reply's grounded sentences are ever returned. G9: no platform code here.
 */
class GroundedDialogueVoice(private val model: ChatModel) {

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
        ?: generateDeterministicFallback(beliefs)

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
        val reply = model.complete(
            system = "You are roleplaying as ${npc.identity.name}, a ${npc.identity.occupation} in a dark gothic fantasy RPG. " +
                    "Speak in character with concise medieval phrasing. " +
                    "CRITICAL CONSTRAINT: You can only state or reference facts present in your KNOWN BELIEFS. Never fabricate facts.",
            user = buildDialoguePrompt(npc, playerTopic, locationId, beliefs, relationship),
            temperature = 0.7f,
            maxTokens = 120
        )
        // A reasoning model's private thinking is never dialogue.
        val content = reply.map { it.replace(thinkBlock, "").trim() }.getOrElse { e ->
            stats = stats.copy(failed = stats.failed + 1, lastFailure = model.describeFailure(e))
            return null
        }
        if (content.isEmpty()) {
            stats = stats.copy(failed = stats.failed + 1, lastFailure = EMPTY_REPLY)
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
     * One tiny request to see whether the model works: null when it does, otherwise the reason in plain words. It does
     * not count towards [stats].
     */
    suspend fun checkConnection(): String? =
        model.complete(system = null, user = "Reply with the word OK.", temperature = 0.1f, maxTokens = 4)
            .exceptionOrNull()?.let(model::describeFailure)

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

    private fun generateDeterministicFallback(beliefs: List<Belief>): String =
        DialogueGrounding.speakableFacts(beliefs).firstOrNull()?.line ?: DialogueGrounding.NOTHING_TO_TELL

    private companion object {
        val thinkBlock = Regex("(?s)<think>.*?</think>")
        const val EMPTY_REPLY = "The model gave an empty reply."
    }
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
