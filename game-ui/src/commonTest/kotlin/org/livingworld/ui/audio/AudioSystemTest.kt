package org.livingworld.ui.audio

import org.livingworld.core.events.GameEvent
import org.livingworld.core.ids.*
import org.livingworld.core.npc.EmotionalState
import org.livingworld.core.npc.Identity
import org.livingworld.core.npc.Npc
import org.livingworld.core.time.WorldTime
import org.livingworld.core.world.Location
import org.livingworld.core.world.PlayerState
import org.livingworld.core.world.WorldState
import kotlin.test.*

class AudioSystemTest {

    @Test
    fun test_location_soundscape_mapping() {
        val dayMarket = AudioStateAdapter.determineSoundscapeForLocation(LocationId("market"), isNight = false)
        assertEquals("soundscape_market_bustle", dayMarket.value)

        val nightMine = AudioStateAdapter.determineSoundscapeForLocation(LocationId("mine"), isNight = true)
        assertEquals("soundscape_mine_cavern", nightMine.value)

        val dayVillage = AudioStateAdapter.determineSoundscapeForLocation(LocationId("village_square"), isNight = false)
        assertEquals("soundscape_village_day", dayVillage.value)

        val nightVillage = AudioStateAdapter.determineSoundscapeForLocation(LocationId("village_square"), isNight = true)
        assertEquals("soundscape_village_night", nightVillage.value)
    }

    @Test
    fun test_event_audio_cue_derivation() {
        val deathEv = GameEvent.NpcKilled(
            id = EventId(1L),
            time = WorldTime(1440L),
            victim = NpcId("merchant"),
            killer = EntityId.Npc(NpcId("miner_derek")),
            witnesses = emptyList(),
            motive = "Death"
        )
        val tradeEv = GameEvent.TradeCompleted(
            id = EventId(2L),
            time = WorldTime(1440L),
            buyer = EntityId.Player("player"),
            seller = EntityId.Npc(NpcId("merchant")),
            item = ItemId("bread"),
            price = 5.0f
        )

        val cues = AudioStateAdapter.deriveAudioCuesFromEvents(listOf(deathEv, tradeEv))
        assertEquals(2, cues.size)
        assertTrue(cues.any { it.id == "sfx_death_bell" && it.priority == 10 })
        assertTrue(cues.any { it.id == "sfx_coins_clink" })
    }

    @Test
    fun test_dynamic_tension_modulation_peaceful_to_lament() {
        val loc = Location(LocationId("village_square"), "Village Square")
        val world = WorldState(
            locations = mapOf(loc.id to loc),
            player = PlayerState(currentLocation = loc.id)
        )

        val peacefulAmbience = AudioStateAdapter.synthesizeAmbience(world, recentEventsWindow = emptyList())
        assertEquals(DynamicTension.PEACEFUL, peacefulAmbience.tension)
        assertTrue(peacefulAmbience.layers.any { it.id.contains("pastoral") })

        val deathEv = GameEvent.NpcKilled(
            id = EventId(10L),
            time = WorldTime(1440L * 10),
            victim = NpcId("merchant"),
            killer = EntityId.Npc(NpcId("miner_derek")),
            witnesses = emptyList(),
            motive = "Merchant died"
        )
        val lamentAmbience = AudioStateAdapter.synthesizeAmbience(world, recentEventsWindow = listOf(deathEv))
        assertEquals(DynamicTension.LAMENT, lamentAmbience.tension)
        assertEquals(1.0f, lamentAmbience.tensionIntensity)
        assertTrue(lamentAmbience.layers.any { it.id.contains("lament") })
        assertTrue(lamentAmbience.activeCues.any { it.id == "sfx_death_bell" })
    }

    @Test
    fun test_dynamic_tension_suspicion_from_npc_emotion() {
        val loc = Location(LocationId("village_square"), "Village Square")
        val fearfulNpc = Npc(
            id = NpcId("apprentice"),
            identity = Identity("Finn", "Apprentice", loc.id, loc.id),
            emotionalState = EmotionalState(fear = 0.8f, suspicion = 0.7f)
        )
        val world = WorldState(
            locations = mapOf(loc.id to loc),
            npcs = mapOf(fearfulNpc.id to fearfulNpc),
            player = PlayerState(currentLocation = loc.id)
        )

        val tenseAmbience = AudioStateAdapter.synthesizeAmbience(world, focusNpc = fearfulNpc, recentEventsWindow = emptyList())
        assertEquals(DynamicTension.SUSPICIOUS, tenseAmbience.tension)
        assertTrue(tenseAmbience.layers.any { it.id.contains("suspicion") })
    }
}
