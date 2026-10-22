package org.livingworld.core.world

import org.livingworld.core.beliefs.Belief
import org.livingworld.core.beliefs.KnowledgeSource
import org.livingworld.core.beliefs.stableFloatText
import org.livingworld.core.events.GameEvent
import org.livingworld.core.memory.Memory
import org.livingworld.core.npc.EmotionalState

/**
 * A canonical, platform-independent description of everything that defines a world: the NPCs with their beliefs,
 * memories, items and feelings, the factions, the relationships, the economy, the places, the quests and the
 * player - plus the shape of the ledger.
 *
 * The old fingerprint only counted beliefs and memories and ignored items, traits, the player, the treasury and the
 * quests, so changes there were invisible (see `docs/reports/adversarial-2026-10-22.md` §2.4.1). This digest sees
 * them, and it is the same algorithm on every target: floats go through `stableFloatText`, collections are sorted
 * by a stable key, and nothing depends on a hash table's iteration order.
 */
object StateDigest {

    /** The canonical text of the world. Stable across runs and across Kotlin targets. */
    fun canonicalText(state: WorldState): String {
        val b = StringBuilder()
        b.append("seed=").append(state.seed).append("|tick=").append(state.time.tick)
        b.append("|events=").append(state.events.size)

        for (event in state.events.all()) {
            b.append("|e=").append(event.id.value).append(':').append(eventType(event))
            b.append('@').append(event.time.tick)
            b.append(":cause=").append(event.causalEventId?.value ?: 0L)
            b.append(":actors=").append(event.actors.joinToString(",") { it.typedId() })
            b.append(":witnesses=").append(event.witnesses.joinToString(",") { it.value })
        }

        for ((id, npc) in state.npcs.entries.sortedBy { it.key.value }) {
            b.append("|npc=").append(id.value)
            b.append(":alive=").append(npc.isAlive)
            b.append(":name=").append(npc.identity.name)
            b.append(":job=").append(npc.identity.occupation)
            b.append(":home=").append(npc.identity.homeLocation.value)
            b.append(":at=").append(npc.identity.currentLocation.value)
            b.append(":traits=").append(npc.traits.joinToString(",") { it.name })
            b.append(":goals=").append(npc.goals.joinToString(",") { it.id })
            b.append(":fears=").append(npc.fears.joinToString(",") { it.description })
            b.append(":values=").append(npc.values.joinToString(",") { it.name })
            b.append(":factions=").append(npc.factions.map { it.value }.sorted().joinToString(","))
            b.append(":hp=").append(stableFloatText(npc.resources.health))
            b.append(":coins=").append(stableFloatText(npc.resources.coins))
            b.append(":food=").append(stableFloatText(npc.resources.food))
            b.append(":items=").append(npc.resources.items.map { it.value }.sorted().joinToString(","))
            b.append(":emo=").append(emotions(npc.emotionalState))
            for (belief in npc.beliefs.all().sortedBy { it.summary }) {
                b.append(":b|").append(belief.summary).append('|')
                    .append(stableFloatText(belief.confidence)).append('|')
                    .append(stableFloatText(belief.reliability)).append('|')
                    .append(source(belief.source)).append('|').append(belief.acquiredAt.tick)
            }
            for (memory in npc.memories.all()) {
                b.append(":m|").append(memoryText(memory))
            }
        }

        for ((id, faction) in state.factions.entries.sortedBy { it.key.value }) {
            b.append("|fac=").append(id.value)
            b.append(":members=").append(faction.members.map { it.value }.sorted().joinToString(","))
            b.append(":leader=").append(faction.leadership.leader?.value ?: "-")
            b.append(":succession=").append(faction.leadership.successionLine.joinToString(",") { it.value })
            b.append(":stability=").append(stableFloatText(faction.leadership.stability))
            b.append(":allies=").append(faction.allies.map { it.value }.sorted().joinToString(","))
            b.append(":enemies=").append(faction.enemies.map { it.value }.sorted().joinToString(","))
        }

        for ((key, rel) in state.relationships.allEdges().entries.sortedBy { it.key }) {
            b.append("|rel=").append(key)
            b.append(":").append(stableFloatText(rel.trust)).append(':').append(stableFloatText(rel.affection))
            b.append(':').append(stableFloatText(rel.respect)).append(':').append(stableFloatText(rel.fear))
            b.append(':').append(stableFloatText(rel.resentment)).append(":shared=").append(rel.sharedEvents.size)
        }

        for ((item, price) in state.economy.itemPrices.entries.sortedBy { it.key }) {
            b.append("|price=").append(item).append(':').append(stableFloatText(price))
        }
        for ((item, scarcity) in state.economy.scarcityFactors.entries.sortedBy { it.key }) {
            b.append("|scarcity=").append(item).append(':').append(stableFloatText(scarcity))
        }
        b.append("|volume=").append(stableFloatText(state.economy.totalTradeVolume))
        b.append("|treasury=").append(stableFloatText(state.economy.treasury))

        for ((id, location) in state.locations.entries.sortedBy { it.key.value }) {
            b.append("|loc=").append(id.value).append(':').append(location.name)
            b.append(':').append(location.description)
            b.append(':').append(location.connectedLocations.map { it.value }.sorted().joinToString(","))
            b.append(':').append(location.controllingFaction?.value ?: "-")
        }

        for (quest in state.activeQuests.sortedBy { it.id }) {
            b.append("|quest=").append(quest.id).append(':').append(quest.title).append(':').append(quest.description)
            b.append(':').append(quest.issuer.typedId()).append(':').append(quest.target?.typedId() ?: "-")
            b.append(':').append(stableFloatText(quest.reward)).append(':').append(quest.status.name)
        }

        val player = state.player
        b.append("|player=").append(player.id.typedId()).append(':').append(player.currentLocation.value)
        b.append(':').append(stableFloatText(player.coins))
        b.append(':').append(player.inventory.map { it.value }.sorted().joinToString(","))
        b.append(':').append(player.skills.joinToString(",") { "${it.name}:${it.level}" })
        b.append(':').append(player.values.joinToString(",") { "${it.name}:${stableFloatText(it.score)}" })
        b.append(':').append(player.reputation.villageReputation).append(',').append(player.reputation.merchantReputation)
        b.append(',').append(player.reputation.banditReputation)
        b.append(':').append(player.factionMemberships.map { it.value }.sorted().joinToString(","))
        b.append(':').append(player.historicalActions.size)
        return b.toString()
    }

    /** The FNV-1a 64 digest of the canonical text, as 16 hex characters. */
    fun of(state: WorldState): String {
        val payload = canonicalText(state)
        var hash = -0x340d631b7bdddcdbL
        val prime = 0x100000001b3L
        for (ch in payload) {
            hash = hash xor ch.code.toLong()
            hash *= prime
        }
        return hash.toULong().toString(16).padStart(16, '0')
    }

    private fun eventType(event: GameEvent): String = when (event) {
        is GameEvent.NpcKilled -> "NpcKilled"
        is GameEvent.ItemStolen -> "ItemStolen"
        is GameEvent.EntitiesMet -> "EntitiesMet"
        is GameEvent.RelationshipShifted -> "RelationshipShifted"
        is GameEvent.RumorTransmitted -> "RumorTransmitted"
        is GameEvent.TradeCompleted -> "TradeCompleted"
        is GameEvent.RationPurchased -> "RationPurchased"
        is GameEvent.AiAdviceRecorded -> "AiAdviceRecorded"
        is GameEvent.AllegianceChanged -> "AllegianceChanged"
        is GameEvent.NpcAttacked -> "NpcAttacked"
        is GameEvent.PriceAdjusted -> "PriceAdjusted"
        is GameEvent.ShopInherited -> "ShopInherited"
        is GameEvent.FactionHostilityDeclared -> "FactionHostilityDeclared"
        is GameEvent.NpcRoutineCompleted -> "NpcRoutineCompleted"
        is GameEvent.OpportunityDispatched -> "OpportunityDispatched"
        is GameEvent.PlayerMoved -> "PlayerMoved"
        is GameEvent.PlayerAttacked -> "PlayerAttacked"
    }

    private fun emotions(state: EmotionalState): String = listOf(
        "fear" to state.fear, "anger" to state.anger, "trust" to state.trust, "grief" to state.grief,
        "hope" to state.hope, "guilt" to state.guilt, "suspicion" to state.suspicion, "pride" to state.pride
    ).joinToString(",") { "${it.first}=${stableFloatText(it.second)}" }

    private fun source(source: KnowledgeSource): String = when (source) {
        is KnowledgeSource.DirectObservation -> "seen"
        is KnowledgeSource.Observation -> "observed:${source.eventId?.value ?: 0L}"
        is KnowledgeSource.Hearsay -> "hearsay:${source.sourceNpc.value}"
        is KnowledgeSource.Deduction -> "deduction"
        is KnowledgeSource.FactionReport -> "faction:${source.faction.value}"
    }

    private fun memoryText(memory: Memory): String = when (memory) {
        is Memory.Episodic ->
            "episodic:${memory.eventId.value}:${stableFloatText(memory.importance)}:${memory.createdAt.tick}"
        is Memory.Relationship ->
            "relationship:${memory.entity.typedId()}:${memory.experience.name}:" +
                "${stableFloatText(memory.importance)}:${memory.createdAt.tick}"
        is Memory.Told ->
            "told:${memory.target.value}:${memory.proposition.describe()}:" +
                "${stableFloatText(memory.importance)}:${memory.createdAt.tick}"
        is Memory.Semantic ->
            "semantic:${memory.proposition.describe()}:${stableFloatText(memory.importance)}:${memory.createdAt.tick}"
    }
}

