package com.livingworld.game.android.ui.inventory

import androidx.lifecycle.ViewModel
import com.livingworld.game.android.world.WorldStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.livingworld.core.events.GameEvent
import org.livingworld.core.ids.ItemId
import org.livingworld.core.ids.NpcId
import org.livingworld.core.world.WorldState
import org.livingworld.ui.compose.ChimeraUiLayoutEngine
import org.livingworld.ui.compose.MarketItemCardState
import org.livingworld.ui.sprites.SpriteManifest

data class InventoryUiState(
    val playerCoins: Float = 0f,
    val catalog: List<MarketItemCardState> = emptyList(),
    val inventoryCounts: Map<String, Int> = emptyMap()
)

/**
 * The market screen reads the one world, so what it shows is what the player carries: the counts come from
 * `world.player.inventory`, not from a number this screen made up.
 */
class InventoryViewModel(
    private val store: WorldStore,
    private val manifest: SpriteManifest
) : ViewModel() {

    private val commodities = listOf(
        ItemId("bread"), ItemId("iron_sword"), ItemId("herb_bundle"),
        ItemId("iron_ingot"), ItemId("omen_stone"), ItemId("healing_salve")
    )

    val uiState: Flow<InventoryUiState> = store.state.map { world ->
        InventoryUiState(
            playerCoins = world.player.coins,
            catalog = catalogFor(world),
            inventoryCounts = world.player.inventory.groupingBy { it.value }.eachCount()
        )
    }

    val currentWorldState: WorldState get() = store.state.value

    fun buyItem(itemId: ItemId) {
        val world = store.state.value
        val item = catalogFor(world).firstOrNull { it.itemId == itemId } ?: return
        if (world.player.coins < item.currentPrice) return
        store.apply(
            GameEvent.TradeCompleted(
                id = world.events.nextId(),
                time = world.time,
                buyer = world.player.id,
                seller = NpcId("merchant").toEntityId(),
                item = itemId,
                price = item.currentPrice
            )
        )
    }

    fun sellItem(itemId: ItemId) {
        val world = store.state.value
        val item = catalogFor(world).firstOrNull { it.itemId == itemId } ?: return
        if (!world.player.inventory.contains(itemId)) return      // you cannot sell what you do not have
        store.apply(
            GameEvent.TradeCompleted(
                id = world.events.nextId(),
                time = world.time,
                buyer = NpcId("merchant").toEntityId(),
                seller = world.player.id,
                item = itemId,
                price = item.currentPrice * 0.85f
            )
        )
    }

    private fun catalogFor(world: WorldState): List<MarketItemCardState> =
        ChimeraUiLayoutEngine.createMarketCatalog(commodities, world, manifest)
}
