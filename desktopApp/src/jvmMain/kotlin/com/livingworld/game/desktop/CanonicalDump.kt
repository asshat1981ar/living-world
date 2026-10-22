package com.livingworld.game.desktop

import org.livingworld.core.events.GameEvent
import org.livingworld.core.world.WorldState

/**
 * Language-neutral text form of a run: one line per ledger event, then the end state. The Python kernel mirror
 * (lw_mirror) must reproduce it byte for byte; tests/golden holds the reference dumps. Floats print with at most
 * four decimals (same rule as stableFloatText) so both languages agree on text.
 */
object CanonicalDump {
    fun f4(value: Float): String {
        if (value.isNaN()) return "NaN"
        val scaled = Math.round(value.toDouble() * 10_000.0)
        val magnitude = if (scaled < 0) -scaled else scaled
        val fraction = (magnitude % 10_000).toString().padStart(4, '0').trimEnd('0').ifEmpty { "0" }
        return (if (scaled < 0) "-" else "") + (magnitude / 10_000) + "." + fraction
    }

    private fun ids(list: List<org.livingworld.core.ids.NpcId>) = if (list.isEmpty()) "-" else list.joinToString(",") { it.value }

    /**
     * The dump keeps the historical `a->b` edge form, so the committed goldens stay comparable; the typed key
     * (`npc:a->player:b`) lives in the world and in the save format.
     */
    private fun edgeKey(key: String): String =
        key.replace(Regex("^[a-z]+:"), "").replace(Regex("(?<=->)[a-z]+:"), "")

    fun line(e: GameEvent): String {
        val body = when (e) {
            is GameEvent.NpcKilled -> listOf(e.victim.value, e.killer?.identifier ?: "-", e.location?.value ?: "-", e.motive ?: "-")
            is GameEvent.ItemStolen -> listOf(e.thief.identifier, e.item.value, e.owner?.identifier ?: "-", e.location?.value ?: "-")
            is GameEvent.EntitiesMet -> listOf(e.entityA.identifier, e.entityB.identifier, e.location.value)
            is GameEvent.RelationshipShifted -> listOf(e.source.identifier, e.target.identifier, f4(e.deltaTrust), f4(e.deltaAffection), f4(e.deltaRespect), f4(e.deltaFear), f4(e.deltaResentment), e.reason)
            is GameEvent.RumorTransmitted -> listOf(e.source.value, e.target.value, e.transmittedBelief.proposition.describe(), f4(e.transmittedBelief.confidence), f4(e.distortion))
            is GameEvent.TradeCompleted -> listOf(e.buyer.identifier, e.seller.identifier, e.item.value, f4(e.price))
            is GameEvent.RationPurchased -> listOf(e.buyer.value, e.seller.value, f4(e.food), f4(e.price), f4(e.tax))
            is GameEvent.AiAdviceRecorded -> listOf(e.npc.value, e.source, e.outcome.name, e.candidateIndex?.toString() ?: "-", e.explanation ?: "-")
            is GameEvent.AllegianceChanged -> listOf(e.npc.value, e.oldFaction?.value ?: "-", e.newFaction.value, e.reason)
            is GameEvent.NpcAttacked -> listOf(e.attacker.identifier, e.target.value, f4(e.damage), e.location.value, e.attackerStance.name, e.defenderStance.name, e.duelOutcome.name)
            is GameEvent.PriceAdjusted -> listOf(e.merchant.value, e.item.value, f4(e.oldPrice), f4(e.newPrice), e.reason)
            is GameEvent.ShopInherited -> listOf(e.heir.value, e.predecessor.value, e.shopName)
            is GameEvent.FactionHostilityDeclared -> listOf(e.declaringFaction.value, e.targetFaction.value, e.reason)
            is GameEvent.NpcRoutineCompleted -> listOf(e.npc.value, e.routineType, e.location.value, f4(e.deltaHealth), f4(e.deltaCoins), f4(e.deltaFood), f4(e.deltaFear))
            is GameEvent.PlayerMoved -> listOf(e.from.value, e.to.value)
            is GameEvent.PlayerAttacked -> listOf(e.attacker.value, e.location.value, f4(e.damage))
            is GameEvent.OpportunityDispatched -> listOf(e.opportunityId, e.opportunityType, e.title, e.location.value, f4(e.urgency), e.actors.joinToString(",") { it.identifier })
        }
        val type = e::class.simpleName
        return (listOf("E", e.id.value.toString(), e.time.tick.toString(), type, "w=" + ids(e.witnesses), "c=" + (e.causalEventId?.value?.toString() ?: "-")) + body).joinToString("|")
    }

    fun dump(world: WorldState): List<String> {
        val out = mutableListOf<String>()
        world.events.all().forEach { out += line(it) }
        out += "T|${world.time.tick}"
        for (n in world.npcs.values.sortedBy { it.id.value }) {
            val es = n.emotionalState
            out += listOf(
                "N", n.id.value, n.isAlive.toString(), n.identity.currentLocation.value,
                f4(n.resources.health), f4(n.resources.coins), f4(n.resources.food),
                f4(es.fear), f4(es.anger), f4(es.grief),
                "b=" + n.beliefs.all().size, "m=" + n.memories.size,
                "f=" + (if (n.factions.isEmpty()) "-" else n.factions.map { it.value }.sorted().joinToString(","))
            ).joinToString("|")
        }
        for ((k, v) in world.economy.itemPrices.entries.sortedBy { it.key }) out += "P|$k|${f4(v)}"
        for ((k, v) in world.economy.scarcityFactors.entries.sortedBy { it.key }) out += "S|$k|${f4(v)}"
        out += "V|${f4(world.economy.totalTradeVolume)}"
        out += "TR|${f4(world.economy.treasury)}"
        for (q in world.activeQuests.sortedBy { it.id }) out += "Q|${q.id}|${q.status.name}|${f4(q.reward)}"
        out += "PL|${f4(world.player.coins)}"
        for ((k, r) in world.relationships.allEdges().entries.sortedBy { edgeKey(it.key) })
            out += "R|${edgeKey(k)}|${f4(r.trust)}|${f4(r.affection)}|${f4(r.respect)}|${f4(r.fear)}|${f4(r.resentment)}"
        return out
    }
}
