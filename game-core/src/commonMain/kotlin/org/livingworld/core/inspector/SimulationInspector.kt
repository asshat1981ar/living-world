package org.livingworld.core.inspector

import org.livingworld.core.events.GameEvent
import org.livingworld.core.ids.EntityId
import org.livingworld.core.ids.EventId
import org.livingworld.core.ids.NpcId
import org.livingworld.core.world.WorldState
import kotlin.math.roundToInt

/**
 * Pure Kotlin Multiplatform float formatter (2 decimal places) to preserve Invariant G9
 * (Zero JVM/Android platform leaks in commonMain).
 */
fun formatFloat2(value: Float): String {
    if (value.isNaN()) return "0.00"
    val scaled = (value * 100.0f).roundToInt()
    val sign = if (scaled < 0) "-" else ""
    val absScaled = kotlin.math.abs(scaled)
    val whole = absScaled / 100
    val frac = (absScaled % 100).toString().padStart(2, '0')
    return "$sign$whole.$frac"
}

fun formatFloat1(value: Float): String {
    if (value.isNaN()) return "0.0"
    val scaled = (value * 10.0f).roundToInt()
    val sign = if (scaled < 0) "-" else ""
    val absScaled = kotlin.math.abs(scaled)
    val whole = absScaled / 10
    val frac = absScaled % 10
    return "$sign$whole.$frac"
}

class SimulationInspector(
    private val world: WorldState
) {

    fun inspectNpc(npcId: NpcId): String = buildString {
        val npc = world.npcs[npcId]
        if (npc == null) {
            appendLine("NPC ${npcId.value} NOT FOUND in world state.")
            return@buildString
        }

        appendLine("==================================================")
        appendLine("NPC INSPECTOR: ${npc.identity.name} [${npc.id.value}]")
        appendLine("Status: ${if (npc.isAlive) "ALIVE (Health: ${npc.resources.health}%)" else "DEAD (Permanent Death)"}")
        appendLine("Occupation: ${npc.identity.occupation} | Location: ${npc.identity.currentLocation.value}")
        appendLine("Factions: ${if (npc.factions.isEmpty()) "Unaligned" else npc.factions.joinToString { it.value }}")
        appendLine("Traits: ${npc.traits.joinToString()}")
        appendLine("Coins: ${npc.resources.coins} | Food: ${npc.resources.food}")

        appendLine("\n--- Emotional State ---")
        appendLine("Fear: ${formatFloat2(npc.emotionalState.fear)} | Anger: ${formatFloat2(npc.emotionalState.anger)} | Trust: ${formatFloat2(npc.emotionalState.trust)}")
        appendLine("Grief: ${formatFloat2(npc.emotionalState.grief)} | Hope: ${formatFloat2(npc.emotionalState.hope)} | Suspicion: ${formatFloat2(npc.emotionalState.suspicion)}")

        appendLine("\n--- Active Goals (${npc.goals.size}) ---")
        npc.goals.forEach { goal ->
            appendLine(" • [${goal.id}] \"${goal.description}\" (Priority: ${goal.priority}, Urgency: ${goal.urgency})")
        }

        appendLine("\n--- Beliefs (${npc.beliefs.all().size}) ---")
        npc.beliefs.all().forEach { b ->
            appendLine(" • \"${b.proposition.describe()}\" (Confidence: ${formatFloat2(b.confidence)}, Source: ${b.source}, Acquired: Day ${b.acquiredAt.day})")
        }

        appendLine("\n--- Recent Memories (${npc.memories.all().takeLast(5).size}) ---")
        npc.memories.all().takeLast(5).forEach { m ->
            when (m) {
                is org.livingworld.core.memory.Memory.Episodic -> appendLine(" • Episodic: Event #${m.eventId.value} (Imp: ${m.importance}, Day ${m.createdAt.day})")
                is org.livingworld.core.memory.Memory.Relationship -> appendLine(" • Relationship: ${m.entity.identifier} [${m.experience}] (Imp: ${m.importance})")
                is org.livingworld.core.memory.Memory.Told -> appendLine(" • Told ${m.target.value}: \"${m.proposition.describe()}\" (Imp: ${m.importance})")
                is org.livingworld.core.memory.Memory.Semantic -> appendLine(" • Semantic: \"${m.proposition.describe()}\" (Imp: ${m.importance})")
            }
        }

        appendLine("\n--- Relationships ---")
        val relevantEdges = world.relationships.allEdges().filter { it.key.startsWith("${npc.id.value}->") }
        if (relevantEdges.isEmpty()) {
            appendLine(" (No prominent relationships established)")
        } else {
            relevantEdges.forEach { (key, rel) ->
                val targetId = key.substringAfter("->")
                appendLine(" • Toward $targetId: Trust=${formatFloat2(rel.trust)}, Affection=${formatFloat2(rel.affection)}, Fear=${formatFloat2(rel.fear)}, Resentment=${formatFloat2(rel.resentment)}")
            }
        }
        appendLine("==================================================")
    }

    fun inspectEvent(eventId: EventId): String = buildString {
        val event = world.events.find(eventId)
        if (event == null) {
            appendLine("Event #${eventId.value} NOT FOUND in EventLedger.")
            return@buildString
        }

        appendLine("==================================================")
        appendLine("EVENT INSPECTOR: Event #${event.id.value} at ${event.time.formatted()}")
        appendLine("Type: ${event::class.simpleName}")
        appendLine("Summary: ${event.summary}")
        appendLine("Actors: ${event.actors.joinToString { it.identifier }}")
        appendLine("Witnesses: ${if (event.witnesses.isEmpty()) "None (Unwitnessed)" else event.witnesses.joinToString { it.value }}")

        if (event.causalEventId != null) {
            appendLine("Causal Parent Event: Event #${event.causalEventId!!.value}")
            val parent = world.events.find(event.causalEventId!!)
            if (parent != null) {
                appendLine("  Parent Summary: ${parent.summary}")
            }
        } else {
            appendLine("Causal Parent Event: Root initiative")
        }

        // Trace downstream consequences
        val consequences = world.events.all().filter { it.causalEventId == event.id }
        appendLine("Direct Downstream Consequences (${consequences.size}):")
        consequences.forEach { c ->
            appendLine(" • Event #${c.id.value} (${c.time.formatted()}): ${c.summary}")
        }
        appendLine("==================================================")
    }

    fun explainNarrativeArc(focalEntity: EntityId): String = buildString {
        val entityEvents = world.events.eventsForEntity(focalEntity)
        appendLine("Narrative Arc for ${focalEntity.identifier} (${entityEvents.size} events recorded):")
        entityEvents.forEach { ev ->
            appendLine(" [${ev.time.formatted()}] Event #${ev.id.value}: ${ev.summary}")
        }
    }
}
