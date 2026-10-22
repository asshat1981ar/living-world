package com.livingworld.game.android.ui.dialogue

import androidx.lifecycle.ViewModel
import com.livingworld.game.android.world.WorldStore
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.livingworld.ai.dialogue.DialogueStats
import org.livingworld.ai.dialogue.LineRequest
import org.livingworld.ai.dialogue.PregeneratedLines
import org.livingworld.core.dialogue.DialogueGrounding
import org.livingworld.core.events.GameEvent
import org.livingworld.core.ids.EntityId
import org.livingworld.core.ids.NpcId
import org.livingworld.core.npc.Npc
import org.livingworld.core.relationships.Relationship
import org.livingworld.core.world.WorldState
import org.livingworld.ui.compose.ChimeraUiLayoutEngine
import org.livingworld.ui.compose.NpcPortraitCardState
import org.livingworld.ui.sprites.SpriteManifest

data class DialogueLine(
    val speaker: String,
    val text: String,
    val isPlayer: Boolean,
    /** Written by the AI voice rather than the built-in line; the screen marks these. */
    val byAi: Boolean = false
)

/** How many NPC lines the player has been shown, and how many of those the AI wrote (the share that reached them). */
data class LineUsage(val npcLines: Int = 0, val aiLines: Int = 0)

data class DialogueUiState(
    val currentNpc: Npc? = null,
    val portraitCard: NpcPortraitCardState? = null,
    val relationship: Relationship = Relationship.NEUTRAL,
    val dialogueHistory: List<DialogueLine> = emptyList(),
    val availableOptions: List<String> = emptyList()
)

class DialogueViewModel(
    private val store: WorldStore,
    private val manifest: SpriteManifest,
    /** AI lines prepared ahead of time; null keeps the built-in lines only. */
    private var lines: PregeneratedLines? = null,
    private val scope: CoroutineScope? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(DialogueUiState())
    val uiState: StateFlow<DialogueUiState> = _uiState.asStateFlow()

    val currentWorldState: WorldState get() = store.state.value

    private val _voiceStats = MutableStateFlow(lines?.stats)
    /** How the AI voice is doing (null when there is none), for the AI tab's status line. */
    val voiceStats: StateFlow<DialogueStats?> = _voiceStats.asStateFlow()

    private val _lineUsage = MutableStateFlow(LineUsage())
    val lineUsage: StateFlow<LineUsage> = _lineUsage.asStateFlow()

    /** Turns AI lines on (once the on-device model is ready) or off with null; on, it starts preparing at once. */
    fun setLines(lines: PregeneratedLines?) {
        this.lines = lines
        _voiceStats.value = lines?.stats
        _uiState.value.currentNpc?.let(::prepareNextLines)
    }

    /** How many things this NPC has already told the player this conversation. */
    private var told = 0

    fun loadNpc(npcId: NpcId) {
        val world = store.state.value
        val npc = world.npcs[npcId] ?: return
        told = 0
        val rel = world.relationships.get(npcId.toEntityId(), world.player.id)
        val card = ChimeraUiLayoutEngine.createPortraitCardState(npc, manifest, rel)

        _uiState.value = DialogueUiState(
            currentNpc = npc,
            portraitCard = card,
            relationship = rel,
            dialogueHistory = listOf(npcLine(npc, GREETING_TOPIC, builtIn = "Greetings, traveler. What brings you to my presence?")),
            availableOptions = OPTIONS
        )
        prepareNextLines(npc)
    }

    fun chooseOption(optionIndex: Int) {
        val current = _uiState.value
        val npc = current.currentNpc ?: return
        val chosenText = current.availableOptions.getOrNull(optionIndex) ?: return

        val newHistory = current.dialogueHistory + DialogueLine("Player", chosenText, isPlayer = true)

        // Social simulation feedback: record canonical RelationshipShifted event via WorldReducer (Invariant G5)
        val deltaTrust = if (optionIndex == OFFER_ASSISTANCE) 0.15f else 0.05f
        val world = store.state.value
        val shiftEvent = GameEvent.RelationshipShifted(
            id = world.events.nextId(),
            time = world.time,
            source = npc.id.toEntityId(),
            target = world.player.id,
            deltaTrust = deltaTrust,
            deltaAffection = 0.1f,
            deltaRespect = 0.05f,
            deltaFear = 0f,
            deltaResentment = 0f,
            reason = "Dialogue choice: $chosenText"
        )
        store.apply(shiftEvent)

        // Read the world again: the shift is in it now.
        val after = store.state.value
        val updatedNpc = after.npcs[npc.id] ?: npc
        val updatedRel = after.relationships.get(npc.id.toEntityId(), after.player.id)
        val updatedCard = ChimeraUiLayoutEngine.createPortraitCardState(updatedNpc, manifest, updatedRel)

        // Worked out before `told` moves on, so the AI rewords exactly the fact the built-in line tells.
        val topic = topicFor(updatedNpc, optionIndex)
        // G3: an NPC only asserts what it believes; everything else is a non-claim.
        val npcReply = when (optionIndex) {
            ASK_WHAT_THEY_KNOW -> {
                val facts = DialogueGrounding.speakableFacts(updatedNpc)
                facts.getOrNull(told++)?.line
                    ?: if (facts.isEmpty()) DialogueGrounding.NOTHING_TO_TELL else "That is all I know."
            }
            OFFER_ASSISTANCE -> "Your aid is welcome."
            else -> "Farewell, traveler."
        }

        _uiState.value = current.copy(
            currentNpc = updatedNpc,
            portraitCard = updatedCard,
            relationship = updatedRel,
            dialogueHistory = newHistory + npcLine(updatedNpc, topic, builtIn = npcReply),
            availableOptions = OPTIONS
        )
        prepareNextLines(updatedNpc)
    }

    /**
     * The NPC's line: the AI's version if one is already prepared for exactly this NPC, topic and knowledge (G3-checked),
     * else the built-in line. Nothing is ever swapped in later, so the player never sees text change mid-read.
     */
    private fun npcLine(npc: Npc, topic: String, builtIn: String): DialogueLine {
        val prepared = lines?.lineFor(requestFor(npc, topic))
        _lineUsage.value = _lineUsage.value.let { it.copy(npcLines = it.npcLines + 1, aiLines = it.aiLines + if (prepared != null) 1 else 0) }
        return DialogueLine(npc.identity.name, prepared ?: builtIn, isPlayer = false, byAi = prepared != null)
    }

    /** While the player reads, prepare what this NPC may say next (the greeting is for the next visit). */
    private fun prepareNextLines(npc: Npc) {
        val lines = lines ?: return
        (scope ?: viewModelScope).launch {
            for (topic in OPTIONS.indices.map { topicFor(npc, it) } + GREETING_TOPIC) {
                if (this@DialogueViewModel.lines !== lines) return@launch
                lines.prepare(requestFor(npc, topic))
                _voiceStats.value = lines.stats
            }
        }
    }

    /**
     * What the AI is asked to say for an option. Asking what they have heard names the next fact the built-in line would
     * tell, so each ask gets its own prepared line instead of repeating the first one.
     */
    private fun topicFor(npc: Npc, optionIndex: Int): String =
        if (optionIndex != ASK_WHAT_THEY_KNOW) OPTIONS[optionIndex]
        else DialogueGrounding.speakableFacts(npc).getOrNull(told)
            ?.let { "The player asks what you have heard. Tell them this, in your own words: ${it.line}" }
            ?: "The player asks what you have heard, but you have nothing more to tell."

    private fun requestFor(npc: Npc, topic: String) = LineRequest(
        npc = npc,
        topic = topic,
        location = npc.identity.currentLocation,
        beliefs = npc.beliefs.all().toList(),
        relationship = store.state.value.relationships.get(npc.id.toEntityId(), store.state.value.player.id)
    )

    private companion object {
        const val ASK_WHAT_THEY_KNOW = 0
        const val OFFER_ASSISTANCE = 1
        val OPTIONS = listOf("Ask what they have heard", "Offer assistance with local trade", "Farewell.")
        const val GREETING_TOPIC = "The player approaches and greets you."
    }
}
