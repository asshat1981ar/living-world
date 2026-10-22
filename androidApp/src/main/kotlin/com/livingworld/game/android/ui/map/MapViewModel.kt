package com.livingworld.game.android.ui.map

import androidx.lifecycle.ViewModel
import com.livingworld.game.android.world.WorldStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.livingworld.core.events.GameEvent
import org.livingworld.core.ids.LocationId
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
    private val store: WorldStore,
    private val manifest: SpriteManifest,
    private val scope: CoroutineScope? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(uiStateFor(store.state.value))
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    val currentWorldState: WorldState get() = store.state.value

    init {
        // The map follows the world: when the village moves, the map moves with it.
        scope?.launch { store.state.collect { _uiState.value = uiStateFor(it) } }
    }

    private fun uiStateFor(world: WorldState): MapUiState = MapUiState(
        canvasState = ChimeraUiLayoutEngine.createMapCanvasState(world, manifest),
        playerCurrentLocation = world.player.currentLocation
    )

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

        val world = store.state.value
        store.apply(
            GameEvent.PlayerMoved(
                id = world.events.nextId(),
                time = world.time,
                from = world.player.currentLocation,
                to = target.locationId
            )
        )

        // Record EntitiesMet for co-located living NPCs at the new destination
        val coLocatedNpcs = store.state.value.npcs.values
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
            store.apply(meetEvent)
        }

        val newCanvas = ChimeraUiLayoutEngine.createMapCanvasState(store.state.value, manifest)
        _uiState.value = current.copy(
            canvasState = newCanvas,
            playerCurrentLocation = target.locationId,
            selectedNode = newCanvas.nodes.firstOrNull { it.locationId == target.locationId }
        )
    }
}
