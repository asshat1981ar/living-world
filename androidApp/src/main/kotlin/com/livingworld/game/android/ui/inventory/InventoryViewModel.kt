package com.livingworld.game.android.ui.inventory

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.livingworld.core.events.GameEvent
import org.livingworld.core.ids.EntityId
import org.livingworld.core.ids.ItemId
import org.livingworld.core.ids.NpcId
import org.livingworld.core.world.DefaultWorldReducer
import org.livingworld.core.world.WorldReducer
import org.livingworld.core.world.WorldState
import org.livingworld.ui.compose.ChimeraUiLayoutEngine
import org.livingworld.ui.compose.MarketItemCardState
import org.livingworld.ui.sprites.SpriteManifest

data class InventoryUiState(
    val playerCoins: Float = 0f,
    val catalog: List<MarketItemCardState> = emptyList(),
    val inventoryCounts: Map<String, Int> = emptyMap()
)

class InventoryViewModel(
    private var world: WorldState,
    private val manifest: SpriteManifest,
    private val reducer: WorldReducer = DefaultWorldReducer()
) : ViewModel() {

    private val commodities = listOf(
        ItemId("bread"), ItemId("iron_sword"), ItemId("herb_bundle"),
        ItemId("iron_ingot"), ItemId("omen_stone"), ItemId("healing_salve")
    )

    private val _uiState = MutableStateFlow(
        InventoryUiState(
            playerCoins = world.player.coins,
            catalog = ChimeraUiLayoutEngine.createMarketCatalog(commodities, world, manifest),
            inventoryCounts = mapOf("bread" to 3, "herb_bundle" to 1)
        )
    )
    val uiState: StateFlow<InventoryUiState> = _uiState.asStateFlow()

    val currentWorldState: WorldState get() = world

    fun buyItem(itemId: ItemId) {
        val current = _uiState.value
        val item = current.catalog.firstOrNull { it.itemId == itemId } ?: return
        if (current.playerCoins >= item.currentPrice) {
            val tradeEvent = GameEvent.TradeCompleted(
                id = world.events.nextId(),
                time = world.time,
                buyer = world.player.id,
                seller = NpcId("merchant").toEntityId(),
                item = itemId,
                price = item.currentPrice
            )
            world = reducer.apply(world, tradeEvent)
            val counts = current.inventoryCounts.toMutableMap()
            counts[itemId.value] = (counts[itemId.value] ?: 0) + 1
            _uiState.value = current.copy(playerCoins = world.player.coins, inventoryCounts = counts)
        }
    }

    fun sellItem(itemId: ItemId) {
        val current = _uiState.value
        val item = current.catalog.firstOrNull { it.itemId == itemId } ?: return
        val count = current.inventoryCounts[itemId.value] ?: 0
        if (count > 0) {
            val sellPrice = item.currentPrice * 0.85f
            val tradeEvent = GameEvent.TradeCompleted(
                id = world.events.nextId(),
                time = world.time,
                buyer = NpcId("merchant").toEntityId(),
                seller = world.player.id,
                item = itemId,
                price = sellPrice
            )
            world = reducer.apply(world, tradeEvent)
            val counts = current.inventoryCounts.toMutableMap()
            counts[itemId.value] = count - 1
            _uiState.value = current.copy(playerCoins = world.player.coins, inventoryCounts = counts)
        }
    }
}
