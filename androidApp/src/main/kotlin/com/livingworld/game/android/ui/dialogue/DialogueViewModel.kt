package com.livingworld.game.android.ui.dialogue

import androidx.lifecycle.ViewModel
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
import org.livingworld.core.world.DefaultWorldReducer
import org.livingworld.core.world.WorldReducer
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

data class DialogueUiState(
    val currentNpc: Npc? = null,
    val portraitCard: NpcPortraitCardState? = null,
    val relationship: Relationship = Relationship.NEUTRAL,
    val dialogueHistory: List<DialogueLine> = emptyList(),
    val availableOptions: List<String> = emptyList()
)

class DialogueViewModel(
    private var world: WorldState,
    private val manifest: SpriteManifest,
    private val reducer: WorldReducer = DefaultWorldReducer(),
    /** AI lines prepared ahead of time; null keeps the built-in lines only. */
    private var lines: PregeneratedLines? = null,
    private val scope: CoroutineScope? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(DialogueUiState())
    val uiState: StateFlow<DialogueUiState> = _uiState.asStateFlow()

    val currentWorldState: WorldState get() = world

    private val _voiceStats = MutableStateFlow(lines?.stats)
    /** How the AI voice is doing (null when there is none), for the AI tab's status line. */
    val voiceStats: StateFlow<DialogueStats?> = _voiceStats.asStateFlow()

    /** Turns AI lines on (once the on-device model is ready) or off with null; on, it starts preparing at once. */
    fun setLines(lines: PregeneratedLines?) {
        this.lines = lines
        _voiceStats.value = lines?.stats
        _uiState.value.currentNpc?.let(::prepareNextLines)
    }

    /** How many things this NPC has already told the player this conversation. */
    private var told = 0

    fun loadNpc(npcId: NpcId) {
        val npc = world.npcs[npcId] ?: return
        told = 0
        val rel = world.relationships.get(npcId.toEntityId(), EntityId.Player("player"))
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
        val shiftEvent = GameEvent.RelationshipShifted(
            id = world.events.nextId(),
            time = world.time,
            source = npc.id.toEntityId(),
            target = EntityId.Player("player"),
            deltaTrust = deltaTrust,
            deltaAffection = 0.1f,
            deltaRespect = 0.05f,
            deltaFear = 0f,
            deltaResentment = 0f,
            reason = "Dialogue choice: $chosenText"
        )
        world = reducer.apply(world, shiftEvent)

        val updatedNpc = world.npcs[npc.id] ?: npc
        val updatedRel = world.relationships.get(npc.id.toEntityId(), EntityId.Player("player"))
        val updatedCard = ChimeraUiLayoutEngine.createPortraitCardState(updatedNpc, manifest, updatedRel)

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
            dialogueHistory = newHistory + npcLine(updatedNpc, chosenText, builtIn = npcReply),
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
        return DialogueLine(npc.identity.name, prepared ?: builtIn, isPlayer = false, byAi = prepared != null)
    }

    /** While the player reads, prepare what this NPC may say next (the greeting is for the next visit). */
    private fun prepareNextLines(npc: Npc) {
        val lines = lines ?: return
        (scope ?: viewModelScope).launch {
            for (topic in OPTIONS + GREETING_TOPIC) {
                if (this@DialogueViewModel.lines !== lines) return@launch
                lines.prepare(requestFor(npc, topic))
                _voiceStats.value = lines.stats
            }
        }
    }

    private fun requestFor(npc: Npc, topic: String) = LineRequest(
        npc = npc,
        topic = topic,
        location = npc.identity.currentLocation,
        beliefs = npc.beliefs.all().toList(),
        relationship = world.relationships.get(npc.id.toEntityId(), EntityId.Player("player"))
    )

    private companion object {
        const val ASK_WHAT_THEY_KNOW = 0
        const val OFFER_ASSISTANCE = 1
        val OPTIONS = listOf("Ask what they have heard", "Offer assistance with local trade", "Farewell.")
        const val GREETING_TOPIC = "The player approaches and greets you."
    }
}
