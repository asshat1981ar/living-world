package org.livingworld.core

import kotlinx.coroutines.test.runTest
import org.livingworld.core.ids.*
import org.livingworld.core.memory.Memory
import org.livingworld.core.memory.MemoryStore
import org.livingworld.core.memory.RelationshipExperience
import org.livingworld.core.npc.Identity
import org.livingworld.core.npc.Npc
import org.livingworld.core.npc.Trait
import org.livingworld.core.simulation.DefaultNpcDecisionEngine
import org.livingworld.core.simulation.Intent
import org.livingworld.core.time.WorldTime
import org.livingworld.core.world.Location
import org.livingworld.core.world.PlayerState
import org.livingworld.core.world.WorldState
import kotlin.test.*

/** NPCs treat people differently according to what they remember of them (MemoryRetriever in decisions). */
class MemoryDrivenDecisionTest {
    private val square = LocationId("village_square")
    private val engine = DefaultNpcDecisionEngine()
    private val bran = NpcId("bran")
    private val cora = NpcId("cora")
    private val dane = NpcId("dane")

    private fun memory(who: NpcId, what: RelationshipExperience, importance: Float = 0.9f) =
        Memory.Relationship(who.toEntityId(), what, importance, WorldTime(0))

    private fun world(vararg memories: Memory, traits: List<Trait> = emptyList()): Pair<Npc, WorldState> {
        fun make(id: NpcId, t: List<Trait> = emptyList(), m: List<Memory> = emptyList()) =
            Npc(id = id, identity = Identity(id.value, "Villager", square, square), traits = t,
                memories = m.fold(MemoryStore()) { s, x -> s.add(x) })
        val me = make(bran, traits, memories.toList())
        val state = WorldState(
            locations = mapOf(square to Location(square, "Square")),
            npcs = listOf(me, make(cora), make(dane)).associateBy { it.id },
            player = PlayerState(currentLocation = square)
        )
        return me to state
    }

    @Test
    fun kindness_raises_and_assault_lowers_the_appeal_of_contact() {
        val (me, w) = world(memory(cora, RelationshipExperience.KINDNESS), memory(dane, RelationshipExperience.ASSAULT))
        assertTrue(engine.memoryBias(me, Intent.Socialize(cora), w) > 0f)
        assertTrue(engine.memoryBias(me, Intent.Socialize(dane), w) < 0f)
        assertEquals(0f, engine.memoryBias(me, Intent.Rest, w))
    }

    @Test
    fun memories_of_someone_else_do_not_leak_onto_a_stranger() {
        val (me, w) = world(memory(dane, RelationshipExperience.ASSAULT))
        assertEquals(0f, engine.memoryBias(me, Intent.Socialize(cora), w))
    }

    @Test
    fun a_grudge_makes_violence_more_attractive_and_kindness_less() {
        val (me, w) = world(memory(dane, RelationshipExperience.ASSAULT), memory(cora, RelationshipExperience.KINDNESS))
        assertTrue(engine.memoryBias(me, Intent.Attack(dane, "x"), w) > 0f)
        assertTrue(engine.memoryBias(me, Intent.Attack(cora, "x"), w) < 0f)
    }

    @Test
    fun the_retriever_keeps_the_important_memory_among_many_trivial_ones() {
        val trivial = (1..20).map { memory(dane, RelationshipExperience.KINDNESS, importance = 0.1f) }
        val (me, w) = world(*trivial.toTypedArray(), memory(dane, RelationshipExperience.ASSAULT, importance = 0.95f))
        assertTrue(engine.memoryBias(me, Intent.Socialize(dane), w) < 0f)
    }

    @Test
    fun a_remembered_assault_changes_who_a_friendly_npc_chooses_to_talk_to() = runTest {
        val (plain, plainWorld) = world(traits = listOf(Trait.COMPASSIONATE))
        val (wary, waryWorld) = world(
            memory(cora, RelationshipExperience.ASSAULT), memory(dane, RelationshipExperience.ASSAULT),
            traits = listOf(Trait.COMPASSIONATE)
        )
        assertTrue(engine.decide(plain, plainWorld).intent is Intent.Socialize)
        assertFalse(engine.decide(wary, waryWorld).intent is Intent.Socialize)
    }
}
