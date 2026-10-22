package org.livingworld.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.livingworld.core.events.GameEvent
import org.livingworld.core.world.DefaultWorldReducer
import org.livingworld.core.world.StateDigest
import org.livingworld.core.world.WorldInvariants
import org.livingworld.core.world.WorldReducer
import org.livingworld.core.world.WorldState

@Serializable
data class SaveEnvelope(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val savedAtTick: Long,
    val eventCount: Int,
    val stateHash: Int,
    val deterministicDigestHex: String = "",
    val worldState: WorldState,
    val migrationHistory: List<String> = emptyList()
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 4
    }
}

/**
 * Cross-version schema migrator (Milestone save-migration-002):
 * Upgrades legacy v1 (raw WorldState JSON without SaveEnvelope) and v2 (SaveEnvelope without
 * deterministicDigestHex / migrationHistory) payloads into canonical v3 SaveEnvelopes.
 */
object SaveSchemaMigrator {

    fun detectSchemaVersion(rawJson: String, json: Json = SaveManager.jsonConfig): Int {
        val obj: JsonObject = json.parseToJsonElement(rawJson).jsonObject
        val explicitVersion = obj["schemaVersion"]?.jsonPrimitive?.intOrNull
        if (explicitVersion != null) return explicitVersion
        if (obj.containsKey("seed") && obj.containsKey("npcs") && obj.containsKey("player")) {
            return 1
        }
        throw IllegalArgumentException("Unrecognized save payload format: missing schemaVersion and WorldState keys")
    }

    fun migrateToLatest(rawJson: String, json: Json = SaveManager.jsonConfig): SaveEnvelope {
        val detectedVersion = detectSchemaVersion(rawJson, json)
        require(detectedVersion in 1..SaveEnvelope.CURRENT_SCHEMA_VERSION) {
            "Unsupported save schema version: $detectedVersion (supported: 1..${SaveEnvelope.CURRENT_SCHEMA_VERSION})"
        }

        return when (detectedVersion) {
            1 -> {
                val legacyState = json.decodeFromString<WorldState>(rawJson)
                SaveManager.validateWorldIntegrity(legacyState).getOrThrow()
                val digest = SaveManager.computeDeterministicFingerprint(legacyState)
                migrateV3ToV4(
                    SaveEnvelope(
                        schemaVersion = 3,
                        savedAtTick = legacyState.time.tick,
                        eventCount = legacyState.events.size,
                        stateHash = SaveManager.stateHashOf(digest),
                        deterministicDigestHex = digest,
                        worldState = legacyState,
                        migrationHistory = listOf("v1->v2: wrapped raw WorldState", "v2->v3: added deterministicDigestHex")
                    )
                )
            }
            2 -> {
                val v2Envelope = json.decodeFromString<SaveEnvelope>(rawJson)
                SaveManager.validateWorldIntegrity(v2Envelope.worldState).getOrThrow()
                val digest = SaveManager.computeDeterministicFingerprint(v2Envelope.worldState)
                migrateV3ToV4(
                    v2Envelope.copy(
                        schemaVersion = 3,
                        eventCount = v2Envelope.worldState.events.size,
                        savedAtTick = v2Envelope.worldState.time.tick,
                        deterministicDigestHex = digest,
                        migrationHistory = v2Envelope.migrationHistory + "v2->v3: computed deterministicDigestHex"
                    )
                )
            }
            3 -> {
                val v3Envelope = json.decodeFromString<SaveEnvelope>(rawJson)
                migrateV3ToV4(v3Envelope)
            }
            else -> {
                val envelope = json.decodeFromString<SaveEnvelope>(rawJson)
                SaveManager.validateWorldIntegrity(envelope.worldState).getOrThrow()
                // A current envelope must carry the digest of the world it holds; without it nothing is verified.
                require(envelope.deterministicDigestHex.isNotBlank()) {
                    "SaveEnvelope has no deterministic digest, so its world cannot be verified"
                }
                val actualDigest = SaveManager.computeDeterministicFingerprint(envelope.worldState)
                require(actualDigest == envelope.deterministicDigestHex) {
                    "SaveEnvelope digest mismatch: expected ${envelope.deterministicDigestHex}, got $actualDigest"
                }
                require(envelope.eventCount == envelope.worldState.events.size) {
                    "SaveEnvelope says ${envelope.eventCount} events but the world holds " +
                        "${envelope.worldState.events.size}"
                }
                require(envelope.savedAtTick == envelope.worldState.time.tick) {
                    "SaveEnvelope says tick ${envelope.savedAtTick} but the world is at " +
                        "${envelope.worldState.time.tick}"
                }
                envelope
            }
        }
    }

    /**
     * v3 -> v4: relationship edges gain the kind of both parties (an NPC and a player may share a name), and the
     * digest is recomputed with the full-state digest, which sees beliefs, items, the player and the quests.
     */
    private fun migrateV3ToV4(envelope: SaveEnvelope): SaveEnvelope {
        val playerId = envelope.worldState.player.id.identifier
        fun typed(part: String?): String {
            if (part == null) return "-"
            if (part.contains(':')) return part
            return if (part == playerId) "player:$part" else "npc:$part"
        }
        val rewritten = envelope.worldState.relationships.allEdges().entries.associate { (key, rel) ->
            val parts = key.split("->")
            "${typed(parts.getOrNull(0))}->${typed(parts.getOrNull(1))}" to rel
        }
        val world = envelope.worldState.copy(
            relationships = envelope.worldState.relationships.withEdges(rewritten)
        )
        val digest = SaveManager.computeDeterministicFingerprint(world)
        return envelope.copy(
            schemaVersion = SaveEnvelope.CURRENT_SCHEMA_VERSION,
            savedAtTick = world.time.tick,
            eventCount = world.events.size,
            stateHash = SaveManager.stateHashOf(digest),
            deterministicDigestHex = digest,
            worldState = world,
            migrationHistory = envelope.migrationHistory + "v3->v4: typed relationship keys, full-state digest"
        )
    }
}

object SaveManager {

    val jsonConfig = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * A platform-independent, deterministic digest of the complete authoritative world: the NPCs with their beliefs,
     * memories, items and feelings, the factions, the relationships, the economy, the places, the quests, the player
     * and the shape of the ledger. The full-state digest (v2) replaced the old projection, which ignored exactly the
     * parts that a behaviour change usually touches.
     */
    fun computeDeterministicFingerprint(state: WorldState): String = StateDigest.of(state)

    /** Platform-independent 32-bit state hash: the low 32 bits of the FNV-1a digest (data-class hashCode() is not stable). */
    fun stateHashOf(digestHex: String): Int = digestHex.takeLast(8).toLong(16).toInt()

    fun serializeWorld(state: WorldState): String {
        validateWorldIntegrity(state).getOrThrow()
        return jsonConfig.encodeToString(state)
    }

    fun deserializeWorld(jsonString: String): WorldState {
        return SaveSchemaMigrator.migrateToLatest(jsonString, jsonConfig).worldState
    }

    fun serializeEnvelope(state: WorldState): String {
        validateWorldIntegrity(state).getOrThrow()
        val digest = computeDeterministicFingerprint(state)
        val envelope = SaveEnvelope(
            schemaVersion = SaveEnvelope.CURRENT_SCHEMA_VERSION,
            savedAtTick = state.time.tick,
            eventCount = state.events.size,
            stateHash = stateHashOf(digest),
            deterministicDigestHex = digest,
            worldState = state
        )
        return jsonConfig.encodeToString(envelope)
    }

    fun deserializeEnvelope(jsonString: String): SaveEnvelope {
        return SaveSchemaMigrator.migrateToLatest(jsonString, jsonConfig)
    }

    fun validateWorldIntegrity(state: WorldState): Result<Unit> {
        if (state.time.tick < 0L) {
            return Result.failure(IllegalStateException("WorldTime tick cannot be negative: ${state.time.tick}"))
        }
        for ((npcId, npc) in state.npcs) {
            if (npcId != npc.id) {
                return Result.failure(IllegalStateException("NPC map key ${npcId.value} does not match Npc.id ${npc.id.value}"))
            }
            if (npc.resources.coins.isNaN() || npc.resources.health.isNaN() || npc.resources.food.isNaN()) {
                return Result.failure(IllegalStateException("NPC ${npcId.value} has NaN resource values"))
            }
            if (npc.resources.coins < 0f || npc.resources.health < 0f || npc.resources.food < 0f) {
                return Result.failure(IllegalStateException("NPC ${npcId.value} has negative resource values"))
            }
            if (!npc.identity.isAlive && npc.resources.health > 0f) {
                return Result.failure(IllegalStateException("Deceased NPC ${npcId.value} has positive health (${npc.resources.health})"))
            }
            if (npc.identity.isAlive && npc.resources.health <= 0f) {
                return Result.failure(IllegalStateException("NPC ${npcId.value} is alive with no health (${npc.resources.health})"))
            }
        }
        val violations = WorldInvariants.violations(state)
        if (violations.isNotEmpty()) {
            return Result.failure(IllegalStateException("The world breaks its own rules: " + violations.joinToString("; ")))
        }
        return Result.success(Unit)
    }

    fun replayEvents(
        initialState: WorldState,
        events: List<GameEvent>,
        reducer: WorldReducer = DefaultWorldReducer()
    ): WorldState {
        // The ledger is strictly ordered and unique; a replay that does not satisfy that is not a replay.
        var last = Long.MIN_VALUE
        for (event in events) {
            require(event.id.value > last) {
                "Event ${event.id.value} does not follow the previous event ($last): the ledger must increase"
            }
            last = event.id.value
        }
        var state = initialState
        for (event in events) {
            state = reducer.apply(state, event)
        }
        return state
    }
}
