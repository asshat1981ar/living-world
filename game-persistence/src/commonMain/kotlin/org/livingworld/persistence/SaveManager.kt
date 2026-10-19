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
        const val CURRENT_SCHEMA_VERSION = 3
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
                SaveEnvelope(
                    schemaVersion = SaveEnvelope.CURRENT_SCHEMA_VERSION,
                    savedAtTick = legacyState.time.tick,
                    eventCount = legacyState.events.size,
                    stateHash = SaveManager.stateHashOf(digest),
                    deterministicDigestHex = digest,
                    worldState = legacyState,
                    migrationHistory = listOf("v1->v2: wrapped raw WorldState", "v2->v3: added deterministicDigestHex")
                )
            }
            2 -> {
                val v2Envelope = json.decodeFromString<SaveEnvelope>(rawJson)
                SaveManager.validateWorldIntegrity(v2Envelope.worldState).getOrThrow()
                val digest = SaveManager.computeDeterministicFingerprint(v2Envelope.worldState)
                v2Envelope.copy(
                    schemaVersion = SaveEnvelope.CURRENT_SCHEMA_VERSION,
                    eventCount = v2Envelope.worldState.events.size,
                    savedAtTick = v2Envelope.worldState.time.tick,
                    deterministicDigestHex = digest,
                    migrationHistory = v2Envelope.migrationHistory + "v2->v3: computed deterministicDigestHex"
                )
            }
            else -> {
                val v3Envelope = json.decodeFromString<SaveEnvelope>(rawJson)
                SaveManager.validateWorldIntegrity(v3Envelope.worldState).getOrThrow()
                if (v3Envelope.deterministicDigestHex.isNotBlank()) {
                    val actualDigest = SaveManager.computeDeterministicFingerprint(v3Envelope.worldState)
                    require(actualDigest == v3Envelope.deterministicDigestHex) {
                        "SaveEnvelope digest mismatch: expected ${v3Envelope.deterministicDigestHex}, got $actualDigest"
                    }
                }
                v3Envelope
            }
        }
    }
}

object SaveManager {

    val jsonConfig = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Computes a platform-independent, deterministic FNV-1a 64-bit hex fingerprint of the complete
     * authoritative WorldState covering NPCs, beliefs, factions, relationships, and economy (RT-12 hardened).
     */
    /** Platform-independent 32-bit state hash: the low 32 bits of the FNV-1a digest (data-class hashCode() is not stable). */
    fun stateHashOf(digestHex: String): Int = digestHex.takeLast(8).toLong(16).toInt()

    fun computeDeterministicFingerprint(state: WorldState): String {
        val canonicalBuilder = StringBuilder()
        canonicalBuilder.append("seed=").append(state.seed)
        canonicalBuilder.append("|tick=").append(state.time.tick)
        canonicalBuilder.append("|events=").append(state.events.size)
        for ((npcId, npc) in state.npcs.entries.sortedBy { it.key.value }) {
            canonicalBuilder.append("|npc=").append(npcId.value)
                .append(":").append(npc.isAlive)
                .append(":").append((npc.resources.health * 100).toInt())
                .append(":").append((npc.resources.coins * 100).toInt())
                .append(":").append((npc.resources.food * 100).toInt())
                .append(":").append(npc.identity.occupation)
                .append(":b=").append(npc.beliefs.all().size)
                .append(":m=").append(npc.memories.size)
        }
        for ((factionId, faction) in state.factions.entries.sortedBy { it.key.value }) {
            val enemiesSorted = faction.enemies.map { it.value }.sorted().joinToString(",")
            canonicalBuilder.append("|fac=").append(factionId.value).append(":e=").append(enemiesSorted)
        }
        for ((edgeKey, rel) in state.relationships.allEdges().entries.sortedBy { it.key }) {
            canonicalBuilder.append("|rel=").append(edgeKey)
                .append(":").append((rel.overallSentiment * 100).toInt())
        }
        for ((item, price) in state.economy.itemPrices.entries.sortedBy { it.key }) {
            canonicalBuilder.append("|item=").append(item).append(":").append((price * 100).toInt())
        }

        val payload = canonicalBuilder.toString()
        var hash = -0x340d631b7bdddcdbL
        val prime = 0x100000001b3L
        for (ch in payload) {
            hash = hash xor ch.code.toLong()
            hash *= prime
        }
        return hash.toULong().toString(16).padStart(16, '0')
    }

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
        }
        return Result.success(Unit)
    }

    fun replayEvents(
        initialState: WorldState,
        events: List<GameEvent>,
        reducer: WorldReducer = DefaultWorldReducer()
    ): WorldState {
        var state = initialState
        for (event in events) {
            state = reducer.apply(state, event)
        }
        return state
    }
}
