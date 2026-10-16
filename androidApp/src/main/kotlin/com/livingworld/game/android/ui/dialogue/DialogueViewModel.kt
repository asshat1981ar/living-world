package com.livingworld.game.android.ui.dialogue

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    fun loadNpc(npcId: NpcId) {
        val npc = world.npcs[npcId] ?: return
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
            availableOptions = listOf(
                "Ask about rumors regarding the Iron Guild",
                "Offer assistance with local trade shipments",
                "Inquire about recent strange events"
            )
        )
    }

    fun chooseOption(optionIndex: Int) {
        val current = _uiState.value
        val npc = current.currentNpc ?: return
        val chosenText = current.availableOptions.getOrNull(optionIndex) ?: return

        val newHistory = current.dialogueHistory + DialogueLine("Player", chosenText, isPlayer = true)

        // Social simulation feedback: record canonical RelationshipShifted event via WorldReducer (Invariant G5)
        val deltaTrust = if (optionIndex == 1) 0.15f else 0.05f
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

        val npcReply = when (optionIndex) {
            0 -> "The Iron Guild grows bolder each passing night. Be cautious in the mines."
            1 -> "Your aid is welcome. Our supply caravans have been harassed of late."
            else -> "The ancient ruins to the east have begun to glow with an eerie light."
        }

        _uiState.value = current.copy(
            currentNpc = updatedNpc,
            portraitCard = updatedCard,
            relationship = updatedRel,
            dialogueHistory = newHistory + DialogueLine(updatedNpc.identity.name, npcReply, isPlayer = false),
            availableOptions = listOf("I understand.", "Tell me more.", "Farewell.")
        )
    }
}
