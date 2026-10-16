package org.livingworld.persistence

import kotlinx.coroutines.test.runTest
import org.livingworld.core.events.GameEvent
import org.livingworld.core.ids.EntityId
import org.livingworld.core.ids.EventId
import org.livingworld.core.ids.ItemId
import org.livingworld.core.ids.NpcId
import org.livingworld.core.simulation.SimulationEngine
import org.livingworld.core.time.WorldDuration
import org.livingworld.core.time.WorldTime
import org.livingworld.core.world.DefaultVillageFactory
import org.livingworld.core.world.DefaultWorldReducer
import kotlin.test.*

class SaveManagerTest {

    @Test
    fun test_v3_envelope_roundtrip_and_deterministic_fingerprint() {
        val world = DefaultVillageFactory.marketVillage()
        val encoded = SaveManager.serializeEnvelope(world)
        val decoded = SaveManager.deserializeEnvelope(encoded)

        assertEquals(SaveEnvelope.CURRENT_SCHEMA_VERSION, decoded.schemaVersion)
        assertTrue(decoded.deterministicDigestHex.isNotBlank())
        assertEquals(
            SaveManager.computeDeterministicFingerprint(world),
            decoded.deterministicDigestHex
        )
        assertEquals(world.npcs.size, decoded.worldState.npcs.size)
    }

    @Test
    fun test_v1_legacy_raw_worldstate_migration_to_v3() {
        val world = DefaultVillageFactory.marketVillage()
        val rawV1Json = SaveManager.serializeWorld(world)

        assertEquals(1, SaveSchemaMigrator.detectSchemaVersion(rawV1Json))

        val migrated = SaveManager.deserializeEnvelope(rawV1Json)
        assertEquals(3, migrated.schemaVersion)
        assertEquals(2, migrated.migrationHistory.size)
        assertEquals(
            SaveManager.computeDeterministicFingerprint(world),
            migrated.deterministicDigestHex
        )
    }

    @Test
    fun test_tampered_digest_rejected_on_v3_load() {
        val world = DefaultVillageFactory.marketVillage()
        val encoded = SaveManager.serializeEnvelope(world)
        val tampered = encoded.replace(
            "\"coins\": 50.0",
            "\"coins\": 9999.0"
        )

        assertFailsWith<IllegalArgumentException> {
            SaveManager.deserializeEnvelope(tampered)
        }
    }

    @Test
    fun test_deterministic_replay_matches_fingerprint() {
        val initial = DefaultVillageFactory.marketVillage()
        val reducer = DefaultWorldReducer()

        val priceEvent = GameEvent.PriceAdjusted(
            id = EventId(1L),
            time = WorldTime(1440L),
            merchant = NpcId("merchant"),
            item = ItemId("bread"),
            oldPrice = 5f,
            newPrice = 9f,
            reason = "Grain tax"
        )
        val liveAfter = reducer.apply(initial, priceEvent)
        val replayed = SaveManager.replayEvents(initial, liveAfter.events.all(), reducer)

        assertEquals(
            SaveManager.computeDeterministicFingerprint(liveAfter),
            SaveManager.computeDeterministicFingerprint(replayed)
        )
    }

    @Test
    fun test_simulated_world_roundtrips_exactly_with_polymorphic_entity_ids() = runTest {
        // Regression: EntityId subclasses were value classes; their polymorphic "type" discriminator leaked into the
        // next JSON structure, so any save holding a RelationshipShifted/TradeCompleted event could not be loaded.
        val world = SimulationEngine().advance(DefaultVillageFactory.marketVillage(), WorldDuration.days(1))
        val shifts = world.events.ofType<GameEvent.RelationshipShifted>()
        assertTrue(shifts.isNotEmpty(), "fixture must produce events with EntityId fields")

        val decoded = SaveManager.deserializeEnvelope(SaveManager.serializeEnvelope(world))

        assertEquals(world, decoded.worldState)
        val first = decoded.worldState.events.ofType<GameEvent.RelationshipShifted>().first()
        assertTrue(first.source is EntityId.Npc, "entity kind must survive the round trip")
        assertEquals(SaveManager.computeDeterministicFingerprint(world), decoded.deterministicDigestHex)
    }
}
