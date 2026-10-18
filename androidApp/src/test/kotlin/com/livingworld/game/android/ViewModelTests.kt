package com.livingworld.game.android

import com.livingworld.game.android.ai.AndroidLlamaEngineBridge
import com.livingworld.game.android.ai.AndroidModelAssetManager
import com.livingworld.game.android.ui.dialogue.DialogueViewModel
import com.livingworld.game.android.ui.inventory.InventoryViewModel
import com.livingworld.game.android.ui.map.MapViewModel
import kotlinx.coroutines.test.runTest
import org.livingworld.ai.ondevice.ModelAssetCatalog
import org.livingworld.ai.ondevice.ModelAssetStatus
import org.livingworld.core.events.TacticalStance
import org.livingworld.core.ids.ItemId
import org.livingworld.core.ids.LocationId
import org.livingworld.core.ids.NpcId
import org.livingworld.core.world.DefaultVillageFactory
import org.livingworld.ui.sprites.SpriteManifest
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class ViewModelTests {

    private val world = DefaultVillageFactory.marketVillage()
    private val manifest = SpriteManifest.createDefault()

    @Test
    fun testDialogueViewModelTurnProgressionAndLetterAvatarFallback() {
        val vm = DialogueViewModel(world, manifest)
        vm.loadNpc(NpcId("merchant"))

        val state = vm.uiState.value
        assertNotNull(state.currentNpc)
        assertEquals("Alden the Merchant", state.currentNpc?.identity?.name)
        assertNotNull(state.portraitCard)
        assertEquals("Alden the Merchant", state.portraitCard?.name)
        assertEquals(1, state.dialogueHistory.size)
        assertEquals(3, state.availableOptions.size)

        vm.chooseOption(1)
        val updated = vm.uiState.value
        assertEquals(3, updated.dialogueHistory.size)
        assertTrue(updated.relationship.trust > 0f)
        assertEquals(1, vm.currentWorldState.events.size)
        assertFalse(updated.dialogueHistory.last().text.contains("Iron Guild"))

        // G3: with no beliefs the NPC claims nothing; once it believes something it can say exactly that.
        val vmAsk = DialogueViewModel(world, manifest)
        vmAsk.loadNpc(NpcId("merchant"))
        vmAsk.chooseOption(0)
        assertEquals(org.livingworld.core.dialogue.DialogueGrounding.NOTHING_TO_TELL, vmAsk.uiState.value.dialogueHistory.last().text)

        val emptyManifest = SpriteManifest()
        val vmFallback = DialogueViewModel(world, emptyManifest)
        vmFallback.loadNpc(NpcId("merchant"))
        assertNull(
            "PRO-58: Sprite should be null in empty manifest to trigger LetterAvatarFallback",
            vmFallback.uiState.value.portraitCard?.portraitSprite
        )
    }

    @Test
    fun testMapViewModelSelectionAndTravel() {
        val vm = MapViewModel(world, manifest)
        val initial = vm.uiState.value
        assertEquals(LocationId("village_square"), initial.playerCurrentLocation)
        assertTrue(initial.canvasState.nodes.isNotEmpty())

        vm.selectNode(LocationId("market"))
        assertEquals(LocationId("market"), vm.uiState.value.selectedNode?.locationId)

        vm.travelToSelected()
        assertEquals(LocationId("market"), vm.uiState.value.playerCurrentLocation)
    }

    @Test
    fun testInventoryViewModelBuySellEconomy() {
        val vm = InventoryViewModel(world, manifest)
        val initial = vm.uiState.value
        val initialCoins = initial.playerCoins
        val breadCount = initial.inventoryCounts["bread"] ?: 0

        vm.buyItem(ItemId("bread"))
        val afterBuy = vm.uiState.value
        assertTrue(afterBuy.playerCoins < initialCoins)
        assertEquals(breadCount + 1, afterBuy.inventoryCounts["bread"] ?: 0)
        assertEquals(1, vm.currentWorldState.events.size)

        val coinsBeforeSell = afterBuy.playerCoins
        vm.sellItem(ItemId("bread"))
        val afterSell = vm.uiState.value
        assertTrue(afterSell.playerCoins > coinsBeforeSell)
        assertEquals(breadCount, afterSell.inventoryCounts["bread"] ?: 0)
        assertEquals(2, vm.currentWorldState.events.size)
    }

    @Test
    fun testTacticalDuelViewModelAndRt17DeadCombatantGuard() {
        val duelVm = TacticalDuelViewModel(world, manifest, opponentId = NpcId("miner_derek"))
        assertEquals(1.0f, duelVm.uiState.value.opponentHealthPercent)
        assertFalse(duelVm.uiState.value.opponentIsDeceased)

        repeat(6) {
            duelVm.executeDuelRound(TacticalStance.STRIKE)
        }
        assertTrue(duelVm.uiState.value.opponentIsDeceased)
        val roundsAtDeath = duelVm.uiState.value.roundCount
        val eventsAtDeath = duelVm.currentWorldState.events.size

        duelVm.executeDuelRound(TacticalStance.FEINT)
        assertEquals(roundsAtDeath, duelVm.uiState.value.roundCount)
        assertEquals(eventsAtDeath, duelVm.currentWorldState.events.size)
    }

    @Test
    fun testAndroidLlamaEngineBridgeLoadsBundledGgufAsset() = runTest {
        val modelsDir = File("../assets/models")
        val assetManager = AndroidModelAssetManager(modelsDir)
        val status = assetManager.getStatus(ModelAssetCatalog.SMOLLM2_135M_GGUF)
        if (status is ModelAssetStatus.Ready) {
            val bridge = AndroidLlamaEngineBridge()
            val loaded = bridge.loadModel(status.localPath, threads = 2, contextSize = 2048)
            assertTrue(loaded)
            assertTrue(bridge.isModelLoaded)
            val output = bridge.generate("Test prompt", maxTokens = 32, temperature = 0.2f)
            assertTrue(output.contains("action_index"))
            bridge.unload()
            assertFalse(bridge.isModelLoaded)
        }
    }
}
