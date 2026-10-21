package com.livingworld.game.android.ui.dialogue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.livingworld.ai.dialogue.DialogueStats
import org.livingworld.ai.dialogue.GroundedDialogueVoice
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
    /** The AI that voices NPC lines; null keeps the built-in lines only. */
    private var voice: GroundedDialogueVoice? = null,
    private val scope: CoroutineScope? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(DialogueUiState())
    val uiState: StateFlow<DialogueUiState> = _uiState.asStateFlow()

    val currentWorldState: WorldState get() = world

    private val _voiceStats = MutableStateFlow(voice?.stats)
    /** How the AI voice is doing (null when there is none), for the AI tab's status line. */
    val voiceStats: StateFlow<DialogueStats?> = _voiceStats.asStateFlow()

    /** Turns AI-voiced lines on (for example once the player enters a Hugging Face token) or off with null. */
    fun setVoice(voice: GroundedDialogueVoice?) {
        this.voice = voice
        _voiceStats.value = voice?.stats
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
            dialogueHistory = listOf(
                DialogueLine(
                    speaker = npc.identity.name,
                    text = "Greetings, traveler. What brings you to my presence?",
                    isPlayer = false
                )
            ),
            availableOptions = OPTIONS
        )
        voiceLine(npc, index = 0, topic = "The player approaches and greets you.")
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
            dialogueHistory = newHistory + DialogueLine(updatedNpc.identity.name, npcReply, isPlayer = false),
            availableOptions = OPTIONS
        )
        voiceLine(updatedNpc, index = newHistory.size, topic = chosenText)
    }

    /**
     * The built-in line is shown at once; the AI then gets to say it in its own words. Its reply replaces that line
     * only when it is grounded in what the NPC believes (G3) and the player is still talking to the same NPC.
     */
    private fun voiceLine(npc: Npc, index: Int, topic: String) {
        val voice = voice ?: return
        (scope ?: viewModelScope).launch {
            val rel = world.relationships.get(npc.id.toEntityId(), EntityId.Player("player"))
            val line = voice.generateGroundedLine(npc, topic, npc.identity.currentLocation, npc.beliefs.all().toList(), rel)
            if (this@DialogueViewModel.voice === voice) _voiceStats.value = voice.stats
            if (line == null) return@launch
            _uiState.update { s ->
                val target = s.dialogueHistory.getOrNull(index)
                if (s.currentNpc?.id != npc.id || target == null || target.isPlayer) s
                else s.copy(dialogueHistory = s.dialogueHistory.toMutableList().also { it[index] = target.copy(text = line, byAi = true) })
            }
        }
    }

    private companion object {
        const val ASK_WHAT_THEY_KNOW = 0
        const val OFFER_ASSISTANCE = 1
        val OPTIONS = listOf("Ask what they have heard", "Offer assistance with local trade", "Farewell.")
    }
}
