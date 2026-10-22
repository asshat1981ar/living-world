package com.livingworld.game.desktop

import org.livingworld.core.beliefs.KnowledgeSource
import org.livingworld.core.memory.Memory
import org.livingworld.core.world.WorldState

/**
 * The end state of a run as text, for the content [CanonicalDump] leaves out: belief content, memories, items, traits,
 * goals, emotions, factions, places, quests and the player. It is a separate dump with its own goldens
 * (`tests/golden/<scenario>-<days>d.state.txt.gz`), so the event goldens stay untouched; lw_mirror reproduces it byte
 * for byte, so a drift in any of these shows up as a golden mismatch instead of only in the opaque digest.
 *
 * Not dumped because the mirror does not model it: fears, values, skills, reputation, place names and descriptions.
 */
object CanonicalStateDump {
    private fun f4(v: Float) = CanonicalDump.f4(v)
    private fun list(items: Collection<String>) = if (items.isEmpty()) "-" else items.joinToString(",")

    private fun source(s: KnowledgeSource): String = when (s) {
        is KnowledgeSource.DirectObservation -> "seen"
        is KnowledgeSource.Observation -> "observed:${s.eventId?.value ?: 0L}"
        is KnowledgeSource.Hearsay -> "hearsay:${s.sourceNpc.value}"
        is KnowledgeSource.Deduction -> "deduction"
        is KnowledgeSource.FactionReport -> "faction:${s.faction.value}"
    }

    private fun memory(m: Memory): String = when (m) {
        is Memory.Episodic -> "episodic:${m.eventId.value}:${f4(m.importance)}:${m.createdAt.tick}"
        is Memory.Relationship -> "relationship:${m.entity.typedId()}:${m.experience.name}:${f4(m.importance)}:${m.createdAt.tick}"
        is Memory.Told -> "told:${m.target.value}:${m.proposition.describe()}:${f4(m.importance)}:${m.createdAt.tick}"
        is Memory.Semantic -> "semantic:${m.proposition.describe()}:${f4(m.importance)}:${m.createdAt.tick}"
    }

    fun dump(world: WorldState): List<String> {
        val out = mutableListOf<String>()
        out += "T|${world.time.tick}"
        for (n in world.npcs.values.sortedBy { it.id.value }) {
            val e = n.emotionalState
            out += listOf(
                "NPC", n.id.value, n.isAlive.toString(), n.identity.name, n.identity.occupation,
                n.identity.homeLocation.value, n.identity.currentLocation.value,
                "traits=" + list(n.traits.map { it.name }),
                "items=" + list(n.resources.items.map { it.value }.sorted()),
                "emo=" + listOf(e.fear, e.anger, e.trust, e.grief, e.hope, e.guilt, e.suspicion, e.pride).joinToString(",") { f4(it) }
            ).joinToString("|")
            for (g in n.goals) out += "GOAL|${n.id.value}|${g.id}|${g.description}|${f4(g.priority)}|${f4(g.urgency)}"
            for (b in n.beliefs.all().sortedBy { it.summary }) {
                out += "BEL|${n.id.value}|${b.summary}|${f4(b.confidence)}|${f4(b.reliability)}|${source(b.source)}|${b.acquiredAt.tick}"
            }
            n.memories.all().forEachIndexed { i, m -> out += "MEM|${n.id.value}|$i|${memory(m)}" }
        }
        for ((id, f) in world.factions.entries.sortedBy { it.key.value }) {
            out += listOf(
                "FAC", id.value, f.name,
                "members=" + list(f.members.map { it.value }.sorted()),
                "leader=" + (f.leadership.leader?.value ?: "-"),
                "succession=" + list(f.leadership.successionLine.map { it.value }),
                "stability=" + f4(f.leadership.stability),
                "allies=" + list(f.allies.map { it.value }.sorted()),
                "enemies=" + list(f.enemies.map { it.value }.sorted())
            ).joinToString("|")
        }
        out += "LOC|" + list(world.locations.keys.map { it.value }.sorted())
        for (q in world.activeQuests.sortedBy { it.id }) {
            out += "QUEST|${q.id}|${q.title}|${q.description}|${q.issuer.typedId()}|${q.target?.typedId() ?: "-"}|${f4(q.reward)}|${q.status.name}"
        }
        val p = world.player
        out += "PLAYER|${p.id.typedId()}|${p.currentLocation.value}|${f4(p.coins)}|${list(p.inventory.map { it.value }.sorted())}|actions=${p.historicalActions.size}"
        return out
    }
}
