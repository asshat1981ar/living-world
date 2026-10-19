package com.livingworld.game.android.ui.map

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.livingworld.core.events.GameEvent
import org.livingworld.core.ids.LocationId
import org.livingworld.core.world.DefaultWorldReducer
import org.livingworld.core.world.WorldReducer
import org.livingworld.core.world.WorldState
import org.livingworld.ui.compose.CanvasMapNode
import org.livingworld.ui.compose.ChimeraUiLayoutEngine
import org.livingworld.ui.compose.VillageMapCanvasState
import org.livingworld.ui.sprites.MapNodeState
import org.livingworld.ui.sprites.SpriteManifest

data class MapUiState(
    val canvasState: VillageMapCanvasState,
    val selectedNode: CanvasMapNode? = null,
    val playerCurrentLocation: LocationId
)

class MapViewModel(
    private var world: WorldState,
    private val manifest: SpriteManifest,
    private val reducer: WorldReducer = DefaultWorldReducer()
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        MapUiState(
            canvasState = ChimeraUiLayoutEngine.createMapCanvasState(world, manifest),
            playerCurrentLocation = world.player.currentLocation
        )
    )
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    val currentWorldState: WorldState get() = world

    fun selectNode(locationId: LocationId) {
        val current = _uiState.value
        val node = current.canvasState.nodes.firstOrNull { it.locationId == locationId }
        _uiState.value = current.copy(selectedNode = node)
    }

    fun travelToSelected() {
        val current = _uiState.value
        val target = current.selectedNode ?: return
        // RT-8 Fix: Prevent traveling to BLOCKED nodes or the current location
        if (target.state == MapNodeState.BLOCKED || target.locationId == current.playerCurrentLocation) {
            return
        }

        val updatedPlayer = world.player.copy(currentLocation = target.locationId)
        world = world.copy(player = updatedPlayer)

        // Record EntitiesMet for co-located living NPCs at the new destination
        val coLocatedNpcs = world.npcs.values
            .filter { it.isAlive && it.identity.currentLocation == target.locationId }
            .sortedBy { it.id.value }

        for (npc in coLocatedNpcs.take(1)) {
            val meetEvent = GameEvent.EntitiesMet(
                id = world.events.nextId(),
                time = world.time,
                entityA = world.player.id,
                entityB = npc.id.toEntityId(),
                location = target.locationId
            )
            world = reducer.apply(world, meetEvent)
        }

        val newCanvas = ChimeraUiLayoutEngine.createMapCanvasState(world, manifest)
        _uiState.value = current.copy(
            canvasState = newCanvas,
            playerCurrentLocation = target.locationId,
            selectedNode = newCanvas.nodes.firstOrNull { it.locationId == target.locationId }
        )
    }
}
