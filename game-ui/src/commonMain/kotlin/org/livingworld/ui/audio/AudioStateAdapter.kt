package org.livingworld.ui.audio

import org.livingworld.core.events.GameEvent
import org.livingworld.core.ids.LocationId
import org.livingworld.core.inspector.formatFloat1
import org.livingworld.core.npc.Npc
import org.livingworld.core.world.WorldState

object AudioStateAdapter {

    fun determineSoundscapeForLocation(locationId: LocationId, isNight: Boolean): SoundscapeId {
        return when (locationId.value) {
            "market" -> SoundscapeId("soundscape_market_bustle")
            "mine" -> SoundscapeId("soundscape_mine_cavern")
            "tavern" -> SoundscapeId("soundscape_tavern_hearth")
            "shrine" -> SoundscapeId("soundscape_shrine_whispers")
            "ruins" -> SoundscapeId("soundscape_ruins_dissonance")
            else -> if (isNight) SoundscapeId("soundscape_village_night") else SoundscapeId("soundscape_village_day")
        }
    }

    fun deriveAudioCuesFromEvents(events: List<GameEvent>): List<AudioCue> {
        return events.mapNotNull { event ->
            when (event) {
                is GameEvent.NpcKilled -> AudioCue(
                    id = "sfx_death_bell",
                    name = "Somber Iron Bell Toll & Fatal Strike",
                    channel = AudioChannel.SFX,
                    volume = 1.0f,
                    priority = 10
                )
                is GameEvent.NpcAttacked -> AudioCue(
                    id = "sfx_blade_clash",
                    name = "Drawn Steel Blade Clash",
                    channel = AudioChannel.SFX,
                    volume = 0.9f,
                    priority = 8
                )
                is GameEvent.FactionHostilityDeclared -> AudioCue(
                    id = "sfx_war_horn",
                    name = "Distant Bronze War Horn Echo",
                    channel = AudioChannel.SFX,
                    volume = 1.0f,
                    priority = 9
                )
                is GameEvent.ItemStolen -> AudioCue(
                    id = "sfx_stealth_rustle",
                    name = "Stealth Cloak Rustle & Stolen Goods",
                    channel = AudioChannel.SFX,
                    volume = 0.6f,
                    priority = 4
                )
                is GameEvent.TradeCompleted -> AudioCue(
                    id = "sfx_coins_clink",
                    name = "Silver Shilling Purse Clink",
                    channel = AudioChannel.SFX,
                    volume = 0.5f,
                    priority = 3
                )
                is GameEvent.PriceAdjusted -> AudioCue(
                    id = "sfx_ledger_stamp",
                    name = "Heavy Brass Merchant Ledger Stamp",
                    channel = AudioChannel.SFX,
                    volume = 0.6f,
                    priority = 5
                )
                else -> null
            }
        }
    }

    fun synthesizeAmbience(
        world: WorldState,
        locationId: LocationId = world.player.currentLocation,
        recentEventsWindow: List<GameEvent> = world.events.all().takeLast(5),
        focusNpc: Npc? = null
    ): AmbienceState {
        val hour = world.time.hour
        val isNight = hour < 6 || hour >= 21

        val soundscapeId = determineSoundscapeForLocation(locationId, isNight)
        val layers = mutableListOf<AudioLayer>()

        // 1. Base spatial layer
        layers.add(
            AudioLayer(
                id = "layer_base_${soundscapeId.value}",
                soundscapeId = soundscapeId,
                volume = 0.8f,
                description = "Primary ambient bed for ${locationId.value}"
            )
        )

        // 2. Weather & environmental undertone
        if (isNight) {
            layers.add(
                AudioLayer(
                    id = "layer_wind_night",
                    soundscapeId = SoundscapeId("soundscape_night_crickets"),
                    volume = 0.4f,
                    description = "Distant night wind and cold draft"
                )
            )
        }

        // 3. Tension derivation
        val recentDeaths = recentEventsWindow.filterIsInstance<GameEvent.NpcKilled>()
        val recentHostilities = recentEventsWindow.filterIsInstance<GameEvent.FactionHostilityDeclared>()
        val activeEnemies = world.factions.values.any { it.enemies.isNotEmpty() }

        val (tension, tensionIntensity, tensionLayer) = when {
            recentDeaths.isNotEmpty() -> {
                Triple(
                    DynamicTension.LAMENT,
                    1.0f,
                    AudioLayer(
                        id = "layer_lament_drone",
                        soundscapeId = SoundscapeId("soundscape_mourning_flute"),
                        volume = 0.85f,
                        pitch = 0.9f,
                        description = "Gothic minor-scale mourning flute and bowed cello"
                    )
                )
            }
            recentHostilities.isNotEmpty() || activeEnemies -> {
                Triple(
                    DynamicTension.HOSTILE,
                    0.9f,
                    AudioLayer(
                        id = "layer_hostility_drums",
                        soundscapeId = SoundscapeId("soundscape_war_percussion"),
                        volume = 0.75f,
                        pitch = 1.0f,
                        description = "Rhythmic war drum pulse and metallic dissonance"
                    )
                )
            }
            focusNpc != null && (focusNpc.emotionalState.fear > 0.5f || focusNpc.emotionalState.suspicion > 0.5f) -> {
                Triple(
                    DynamicTension.SUSPICIOUS,
                    0.6f,
                    AudioLayer(
                        id = "layer_suspicion_drone",
                        soundscapeId = SoundscapeId("soundscape_strained_strings"),
                        volume = 0.5f,
                        pitch = 1.1f,
                        description = "Nervous pizzicato and muffled whispers"
                    )
                )
            }
            locationId.value == "ruins" || locationId.value == "shrine" -> {
                Triple(
                    DynamicTension.OMINOUS,
                    0.5f,
                    AudioLayer(
                        id = "layer_hollow_resonance",
                        soundscapeId = SoundscapeId("soundscape_void_reverb"),
                        volume = 0.45f,
                        pitch = 0.85f,
                        description = "Deep cavernous resonance and eerie draft"
                    )
                )
            }
            else -> {
                Triple(
                    DynamicTension.PEACEFUL,
                    0.1f,
                    AudioLayer(
                        id = "layer_pastoral_strings",
                        soundscapeId = SoundscapeId("soundscape_pastoral_lute"),
                        volume = 0.3f,
                        pitch = 1.0f,
                        description = "Subtle acoustic lute and birdsong"
                    )
                )
            }
        }

        layers.add(tensionLayer)

        val triggeredCues = deriveAudioCuesFromEvents(recentEventsWindow)

        val desc = "${soundscapeId.value} | Tension: ${tension.name} (${formatFloat1(tensionIntensity)}) | Active Layers: ${layers.size}"

        return AmbienceState(
            primarySoundscape = soundscapeId,
            layers = layers,
            tension = tension,
            tensionIntensity = tensionIntensity,
            activeCues = triggeredCues,
            description = desc
        )
    }
}
