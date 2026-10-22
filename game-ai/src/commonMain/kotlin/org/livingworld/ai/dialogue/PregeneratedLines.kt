package org.livingworld.ai.dialogue

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.livingworld.core.beliefs.Belief
import org.livingworld.core.dialogue.DialogueGrounding
import org.livingworld.core.ids.LocationId
import org.livingworld.core.npc.Npc
import org.livingworld.core.relationships.Relationship

/** One line an NPC may be asked to say: who, in reply to what, where, and with what knowledge. */
data class LineRequest(
    val npc: Npc,
    val topic: String,
    val location: LocationId,
    val beliefs: List<Belief>,
    val relationship: Relationship
)

/**
 * AI lines written ahead of time. A slow on-device model never makes the player wait or see text change mid-read:
 * [prepare] runs in the background, and [lineFor] only returns a line that is already ready, so callers show the
 * built-in line otherwise. A line is keyed by the NPC, the topic and the exact facts the NPC may speak, so new
 * knowledge makes an old line unreachable instead of stale. Each request reaches the model at most once.
 */
class PregeneratedLines(private val voice: GroundedDialogueVoice, private val capacity: Int = 256) {
    private val mutex = Mutex()
    private val ready = LinkedHashMap<String, String>()
    private val attempted = LinkedHashSet<String>()

    val stats: DialogueStats get() = voice.stats

    suspend fun prepare(request: LineRequest) {
        val key = keyOf(request)
        mutex.withLock {
            if (key in attempted) return
            attempted += key
            trim(attempted)
        }
        val line = voice.generateGroundedLine(request.npc, request.topic, request.location, request.beliefs, request.relationship)
            ?: return
        mutex.withLock {
            ready[key] = line
            trim(ready.keys)
        }
    }

    fun lineFor(request: LineRequest): String? = ready[keyOf(request)]

    /**
     * A line belongs to the NPC, the topic, the place and the facts the NPC can speak: the same question asked at
     * another place, or after the NPC learned something, must not be answered with the old line.
     *
     * The relationship is deliberately not part of the key: the lines for the next turn are prepared before the last
     * change of heart lands, so a key that included the feeling would make the freshest line unreachable.
     */
    private fun keyOf(r: LineRequest): String =
        r.npc.id.value + "\u0000" + voice.sanitizePlayerText(r.topic) + "\u0000" +
            r.location.value + "\u0000" +
            DialogueGrounding.speakableFacts(r.beliefs).joinToString("\u0001") { it.line }

    /** Drops the oldest entries; insertion-ordered collections keep this deterministic. */
    private fun trim(keys: MutableSet<String>) {
        while (keys.size > capacity) keys.remove(keys.first())
    }
}
