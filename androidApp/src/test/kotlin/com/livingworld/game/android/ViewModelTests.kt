package com.livingworld.game.android

import com.livingworld.game.android.ui.dialogue.DialogueViewModel
import com.livingworld.game.android.ui.dialogue.LineUsage
import com.livingworld.game.android.ui.inventory.InventoryViewModel
import com.livingworld.game.android.ui.map.MapViewModel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.livingworld.ai.dialogue.ChatModel
import org.livingworld.ai.dialogue.GroundedDialogueVoice
import org.livingworld.ai.dialogue.PregeneratedLines
import org.livingworld.core.events.GameEvent
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
        // G4: a duel death is recorded as a death (cascade, quests), caused by the final blow, exactly once.
        val deaths = duelVm.currentWorldState.events.all().filterIsInstance<GameEvent.NpcKilled>()
        assertEquals(listOf(NpcId("miner_derek")), deaths.map { it.victim })
        val finalBlow = duelVm.currentWorldState.events.all().filterIsInstance<GameEvent.NpcAttacked>().last()
        assertEquals(finalBlow.id, deaths.single().causalEventId)
        val roundsAtDeath = duelVm.uiState.value.roundCount
        val eventsAtDeath = duelVm.currentWorldState.events.size

        duelVm.executeDuelRound(TacticalStance.FEINT)
        assertEquals(roundsAtDeath, duelVm.uiState.value.roundCount)
        assertEquals(eventsAtDeath, duelVm.currentWorldState.events.size)
    }

    /** A voice whose model always answers [reply]. */
    private fun voiceSaying(reply: String) = GroundedDialogueVoice(object : ChatModel {
        override suspend fun complete(system: String?, user: String, temperature: Float, maxTokens: Int) = Result.success(reply)
        override fun describeFailure(error: Throwable) = error.message ?: "failed"
    })

    private fun linesSaying(reply: String) = PregeneratedLines(voiceSaying(reply))
    private val greeting = "Greetings, traveler. What brings you to my presence?"

    @Test
    fun aPreparedAiLineIsShownAtOnceAndAShownLineNeverChanges() = runTest {
        val vm = DialogueViewModel(world, manifest, lines = linesSaying("Aye, friend. Well met, traveler."), scope = this)
        vm.loadNpc(NpcId("merchant"))
        assertEquals("nothing is prepared on a first meeting", greeting, vm.uiState.value.dialogueHistory.last().text)
        advanceUntilIdle()
        assertEquals("the shown greeting is never swapped mid-read", greeting, vm.uiState.value.dialogueHistory.last().text)

        vm.chooseOption(2)
        val history = vm.uiState.value.dialogueHistory
        assertEquals(listOf(false, true, false), history.map { it.isPlayer })
        assertEquals("prepared while the player read the greeting", "Aye, friend. Well met, traveler.", history.last().text)
        assertTrue(history.last().byAi)

        vm.loadNpc(NpcId("merchant"))
        assertEquals("the greeting is prepared for the next visit", "Aye, friend. Well met, traveler.", vm.uiState.value.dialogueHistory.last().text)
        assertEquals(LineUsage(npcLines = 3, aiLines = 2), vm.lineUsage.value)
    }

    @Test
    fun anInventedAiLineIsNeverShown() = runTest {
        val lines = linesSaying("Mara poisoned the well.")
        val vm = DialogueViewModel(world, manifest, lines = lines, scope = this)
        vm.loadNpc(NpcId("merchant"))
        advanceUntilIdle()
        vm.chooseOption(2)
        assertEquals("Farewell, traveler.", vm.uiState.value.dialogueHistory.last().text)
        assertFalse(vm.uiState.value.dialogueHistory.last().byAi)
        assertTrue(lines.stats.rejected > 0)
    }

    @Test
    fun turningTheAiOnMidConversationPreparesTheNextLinesAndTurningItOffStops() = runTest {
        val vm = DialogueViewModel(world, manifest, scope = this)
        vm.loadNpc(NpcId("merchant"))
        vm.setLines(linesSaying("Well met, stranger."))
        advanceUntilIdle()
        vm.chooseOption(1)
        assertEquals("Well met, stranger.", vm.uiState.value.dialogueHistory.last().text)

        vm.setLines(null)
        vm.chooseOption(2)
        assertEquals("Farewell, traveler.", vm.uiState.value.dialogueHistory.last().text)
    }

    @Test
    fun theStatsAreSharedAndThePlayersOwnLineIsNeverMarked() = runTest {
        val vm = DialogueViewModel(world, manifest, lines = linesSaying("Well met, stranger."), scope = this)
        assertNull(DialogueViewModel(world, manifest).voiceStats.value)
        vm.loadNpc(NpcId("merchant"))
        advanceUntilIdle()
        assertTrue((vm.voiceStats.value?.accepted ?: 0) > 0)

        vm.chooseOption(0)
        assertFalse(vm.uiState.value.dialogueHistory[1].byAi)
    }

    @Test
    fun askingAgainGetsTheNextFactInTheAisWordsNotTheSameLine() = runTest {
        fun seen(item: String) = org.livingworld.core.beliefs.Belief(
            org.livingworld.core.beliefs.Proposition.PlayerStole(ItemId(item)), 0.9f,
            org.livingworld.core.beliefs.KnowledgeSource.DirectObservation, 1f, world.time
        )
        val merchant = world.npcs.getValue(NpcId("merchant"))
        val knowing = world.copy(npcs = world.npcs + (merchant.id to merchant.copy(
            beliefs = merchant.beliefs.addOrUpdate(seen("iron_sword")).addOrUpdate(seen("gold_ring"))
        )))
        // A model that rewords exactly the fact it is asked to tell, so each fact gets its own AI line.
        val echo = GroundedDialogueVoice(object : ChatModel {
            override suspend fun complete(system: String?, user: String, temperature: Float, maxTokens: Int): Result<String> {
                val fact = user.substringAfter("in your own words: ", "").substringBefore("\"")
                return Result.success(if (fact.isEmpty()) "Aye." else "Aye, $fact")
            }
            override fun describeFailure(error: Throwable) = "failed"
        })
        val vm = DialogueViewModel(knowing, manifest, lines = PregeneratedLines(echo), scope = this)
        vm.loadNpc(NpcId("merchant"))
        advanceUntilIdle()
        vm.chooseOption(0)
        val first = vm.uiState.value.dialogueHistory.last()
        advanceUntilIdle()
        vm.chooseOption(0)
        val second = vm.uiState.value.dialogueHistory.last()
        assertTrue(first.byAi && second.byAi)
        assertNotEquals("the second ask tells the next fact", first.text, second.text)
        assertTrue(first.text.contains("gold_ring") || first.text.contains("iron_sword"))
    }
}
