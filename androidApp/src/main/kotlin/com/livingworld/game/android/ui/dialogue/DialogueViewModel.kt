package com.livingworld.game.android.ui.dialogue

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val isPlayer: Boolean
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
    private val reducer: WorldReducer = DefaultWorldReducer()
) : ViewModel() {

    private val _uiState = MutableStateFlow(DialogueUiState())
    val uiState: StateFlow<DialogueUiState> = _uiState.asStateFlow()

    val currentWorldState: WorldState get() = world

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
    }

    private companion object {
        const val ASK_WHAT_THEY_KNOW = 0
        const val OFFER_ASSISTANCE = 1
        val OPTIONS = listOf("Ask what they have heard", "Offer assistance with local trade", "Farewell.")
    }
}
