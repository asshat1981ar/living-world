package com.livingworld.game.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import com.livingworld.game.android.ui.dialogue.DialogueScreen
import com.livingworld.game.android.ai.HfTokenStore
import com.livingworld.game.android.ai.HttpUrlHfTransport
import com.livingworld.game.android.ui.dialogue.DialogueViewModel
import com.livingworld.game.android.ui.settings.AiSettingsScreen
import org.livingworld.ai.huggingface.HuggingFaceDialogueAdapter
import org.livingworld.ai.huggingface.HuggingFaceEndpointConfig
import com.livingworld.game.android.ui.inventory.InventoryScreen
import com.livingworld.game.android.ui.inventory.InventoryViewModel
import com.livingworld.game.android.ui.map.MapScreen
import com.livingworld.game.android.ui.map.MapViewModel
import com.livingworld.game.android.ui.theme.ChimeraTheme
import com.livingworld.game.android.ui.theme.toComposeColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.livingworld.core.events.GameEvent
import org.livingworld.core.simulation.AttackEvents
import org.livingworld.core.events.TacticalCombatResolver
import org.livingworld.core.events.TacticalStance
import org.livingworld.core.ids.NpcId
import org.livingworld.core.npc.Identity
import org.livingworld.core.npc.Npc
import org.livingworld.core.npc.ResourceState
import org.livingworld.core.npc.Trait
import org.livingworld.core.world.DefaultVillageFactory
import org.livingworld.core.world.DefaultWorldReducer
import org.livingworld.core.world.WorldReducer
import org.livingworld.core.world.WorldState
import org.livingworld.ui.compose.ChimeraUiLayoutEngine
import org.livingworld.ui.compose.TacticalDuelCanvasState
import org.livingworld.ui.sprites.SpriteManifest
import org.livingworld.ui.theme.ChimeraSpritePalette

/**
 * ViewModel driving the Interactive Tactical Duel Screen (Sprint 6: compose-combat-screen-006).
 * Enforces RT-17 dead-combatant guard so deceased NPCs cannot execute or receive additional duel rounds.
 */
class TacticalDuelViewModel(
    private var world: WorldState,
    private val manifest: SpriteManifest,
    private val opponentId: NpcId = NpcId("miner_derek"),
    private val reducer: WorldReducer = DefaultWorldReducer()
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ChimeraUiLayoutEngine.createTacticalDuelState(
            world = world,
            opponentId = opponentId,
            playerStance = TacticalStance.STRIKE,
            opponentStance = TacticalStance.WARD,
            manifest = manifest
        )
    )
    val uiState: StateFlow<TacticalDuelCanvasState> = _uiState.asStateFlow()

    val currentWorldState: WorldState get() = world

    fun executeDuelRound(playerStance: TacticalStance) {
        val opponent = world.npcs[opponentId] ?: return
        if (!opponent.isAlive || opponent.resources.health <= 0f) {
            return
        }

        val playerProxyNpc = Npc(
            id = NpcId("player"),
            identity = Identity(
                name = "Player",
                occupation = "Wayfarer",
                homeLocation = world.player.currentLocation,
                currentLocation = world.player.currentLocation,
                isAlive = true
            ),
            traits = listOf(Trait.BRAVE),
            resources = ResourceState(
                coins = world.player.coins,
                health = 100f,
                items = world.player.inventory
            )
        )

        val oppStance = TacticalCombatResolver.selectStance(opponent, world.time, isAttacker = false)
        val resolution = TacticalCombatResolver.resolveExchange(
            attacker = playerProxyNpc,
            defender = opponent,
            time = world.time,
            overrideAttackerStance = playerStance,
            overrideDefenderStance = oppStance
        )

        val attackEventId = world.events.nextId()
        val attackEvent = GameEvent.NpcAttacked(
            id = attackEventId,
            time = world.time,
            attacker = world.player.id,
            target = opponentId,
            damage = resolution.finalDamage,
            location = world.player.currentLocation,
            attackerStance = resolution.attackerStance,
            defenderStance = resolution.defenderStance,
            duelOutcome = resolution.outcome
        )
        world = AttackEvents.apply(reducer, world, attackEvent, motive = "Defeated in tactical duel (${resolution.outcome.description})")

        val nextRound = _uiState.value.roundCount + 1
        val summary = "Round $nextRound: Player [${playerStance.name}] vs ${opponent.identity.name} [${oppStance.name}] -> ${resolution.outcome.description} (${resolution.finalDamage} dmg)"
        _uiState.value = ChimeraUiLayoutEngine.createTacticalDuelState(
            world = world,
            opponentId = opponentId,
            playerStance = playerStance,
            opponentStance = oppStance,
            manifest = manifest,
            lastSummary = summary,
            roundCount = nextRound
        )
    }
}

@Composable
fun TacticalDuelScreen(
    viewModel: TacticalDuelViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ChimeraSpritePalette.PARCHMENT_DARK.toComposeColor())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Tactical Stance Duel — vs ${state.opponentName}",
            color = ChimeraSpritePalette.ACCENT_GOLD.toComposeColor(),
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(ChimeraSpritePalette.PARCHMENT_MID.toComposeColor())
                .border(1.dp, ChimeraSpritePalette.INK_GREY.toComposeColor(), RoundedCornerShape(8.dp))
                .padding(12.dp)
        ) {
            Text(
                text = "Opponent Health: ${(state.opponentHealthPercent * 100).toInt()}% ${if (state.opponentIsDeceased) "[DEFEATED]" else ""}",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Player Stance: ${state.playerStance.label} (${state.playerStanceSprite?.id?.value ?: "none"})",
                color = ChimeraSpritePalette.ACCENT_GOLD.toComposeColor(),
                fontSize = 12.sp
            )
            Text(
                text = "Opponent Stance: ${state.opponentStance.label} (${state.opponentStanceSprite?.id?.value ?: "none"})",
                color = ChimeraSpritePalette.ACCENT_CRIMSON.toComposeColor(),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = state.lastOutcomeSummary,
                color = Color.White,
                fontSize = 12.sp
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TacticalStance.entries.forEach { stance ->
                Button(
                    onClick = { viewModel.executeDuelRound(stance) },
                    enabled = !state.opponentIsDeceased,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ChimeraSpritePalette.INK_CHARCOAL.toComposeColor()
                    )
                ) {
                    Text(stance.name, color = ChimeraSpritePalette.ACCENT_GOLD.toComposeColor(), fontSize = 12.sp)
                }
            }
        }
    }
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val world = DefaultVillageFactory.marketVillage()
        val manifest = SpriteManifest.createDefault()

        // The player enters a Hugging Face token in the AI tab; none is ever built into the APK. Without one, NPCs use
        // their built-in lines and the app makes no network calls.
        val tokenStore = HfTokenStore(noBackupFilesDir)
        fun voiceFor(token: String?) = token?.let {
            HuggingFaceDialogueAdapter(HuggingFaceEndpointConfig.defaultFreeTier(it), HttpUrlHfTransport())
        }
        val dialogueViewModel = DialogueViewModel(world, manifest, voice = voiceFor(tokenStore.load())).apply {
            loadNpc(NpcId("merchant"))
        }
        val mapViewModel = MapViewModel(world, manifest)
        val inventoryViewModel = InventoryViewModel(world, manifest)
        val duelViewModel = TacticalDuelViewModel(world, manifest)

        setContent {
            ChimeraTheme {
                var selectedTab by remember { mutableStateOf(0) }
                var hasToken by remember { mutableStateOf(tokenStore.load() != null) }

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                label = { Text("Dialogue") },
                                icon = { Text("💬") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                label = { Text("Village Map") },
                                icon = { Text("🗺️") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2 },
                                label = { Text("Inventory") },
                                icon = { Text("🎒") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 3,
                                onClick = { selectedTab = 3 },
                                label = { Text("Combat Duel") },
                                icon = { Text("⚔️") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 4,
                                onClick = { selectedTab = 4 },
                                label = { Text("AI") },
                                icon = { Text("✨") }
                            )
                        }
                    }
                ) { padding ->
                    Box(modifier = Modifier.padding(padding)) {
                        when (selectedTab) {
                            0 -> DialogueScreen(viewModel = dialogueViewModel)
                            1 -> MapScreen(viewModel = mapViewModel)
                            2 -> InventoryScreen(viewModel = inventoryViewModel)
                            3 -> TacticalDuelScreen(viewModel = duelViewModel)
                            4 -> AiSettingsScreen(
                                hasToken = hasToken,
                                stats = dialogueViewModel.voiceStats.collectAsState().value,
                                onSave = { token ->
                                    tokenStore.save(token)
                                    val voice = voiceFor(tokenStore.load())
                                    dialogueViewModel.setVoice(voice)
                                    hasToken = voice != null
                                    voice?.checkConnection()
                                },
                                onRemove = {
                                    tokenStore.clear()
                                    dialogueViewModel.setVoice(null)
                                    hasToken = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
