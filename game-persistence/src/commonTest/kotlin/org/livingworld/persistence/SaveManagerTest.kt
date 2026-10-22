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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
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
    fun test_state_hash_is_derived_from_digest_not_platform_hashcode() {
        val world = DefaultVillageFactory.marketVillage()
        val envelope = SaveManager.deserializeEnvelope(SaveManager.serializeEnvelope(world))
        assertEquals(SaveManager.stateHashOf(envelope.deterministicDigestHex), envelope.stateHash)
        assertEquals(-0x1, SaveManager.stateHashOf("0000000000ffffffff".takeLast(16)))
        assertEquals(0x1234abcd, SaveManager.stateHashOf("00000000" + "1234abcd"))
    }

    @Test
    fun test_legacy_save_with_per_npc_relationship_graph_still_loads() {
        val world = DefaultVillageFactory.marketVillage()
        val tree = SaveManager.jsonConfig.parseToJsonElement(SaveManager.serializeEnvelope(world))

        // Older saves stored a private `relationships` graph on every NPC; it must be ignored, not rejected.
        fun addLegacyField(e: JsonElement): JsonElement = when (e) {
            is JsonObject -> {
                val mapped = e.mapValues { addLegacyField(it.value) }
                if ("identity" in e) JsonObject(mapped + ("relationships" to JsonObject(emptyMap()))) else JsonObject(mapped)
            }
            is JsonArray -> JsonArray(e.map { addLegacyField(it) })
            else -> e
        }
        val legacyJson = addLegacyField(tree).toString()
        assertTrue(legacyJson.contains("\"relationships\":{}"))

        val loaded = SaveManager.deserializeEnvelope(legacyJson)
        assertEquals(SaveManager.computeDeterministicFingerprint(world), loaded.deterministicDigestHex)
        assertEquals(world.npcs.size, loaded.worldState.npcs.size)
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

    @Test
    fun an_envelope_without_a_digest_is_refused() {
        val world = DefaultVillageFactory.marketVillage()
        val forged = SaveEnvelope(
            savedAtTick = 0, eventCount = 0, stateHash = 0,
            deterministicDigestHex = "", worldState = world
        )
        val raw = SaveManager.jsonConfig.encodeToString(SaveEnvelope.serializer(), forged)
        assertFailsWith<IllegalArgumentException> { SaveManager.deserializeEnvelope(raw) }
    }

    @Test
    fun an_envelope_whose_metadata_does_not_match_its_world_is_refused() {
        val world = DefaultVillageFactory.marketVillage()
        val digest = SaveManager.computeDeterministicFingerprint(world)
        val wrongCount = SaveEnvelope(
            savedAtTick = 0, eventCount = 99, stateHash = SaveManager.stateHashOf(digest),
            deterministicDigestHex = digest, worldState = world
        )
        val raw = SaveManager.jsonConfig.encodeToString(SaveEnvelope.serializer(), wrongCount)
        assertFailsWith<IllegalArgumentException> { SaveManager.deserializeEnvelope(raw) }
    }

    @Test
    fun replaying_a_ledger_with_a_repeated_event_id_is_refused() {
        val world = DefaultVillageFactory.marketVillage()
        val event = GameEvent.PriceAdjusted(
            id = EventId(1L), time = WorldTime(1L), merchant = NpcId("merchant"), item = ItemId("bread"),
            oldPrice = 5f, newPrice = 6f, reason = "t"
        )
        assertFailsWith<IllegalArgumentException> {
            SaveManager.replayEvents(world, listOf(event, event))
        }
    }

    @Test
    fun the_save_gate_refuses_a_world_that_breaks_its_own_rules() {
        val world = DefaultVillageFactory.marketVillage()
        val merchant = world.npc(NpcId("merchant"))
        val broken = world.copy(
            npcs = world.npcs + (merchant.id to merchant.copy(resources = merchant.resources.copy(health = 0f)))
        )
        assertTrue(SaveManager.validateWorldIntegrity(broken).isFailure)
        assertTrue(SaveManager.validateWorldIntegrity(world).isSuccess)
    }
}
