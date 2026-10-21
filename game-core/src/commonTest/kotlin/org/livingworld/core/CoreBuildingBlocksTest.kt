package org.livingworld.core

import org.livingworld.core.beliefs.Proposition
import org.livingworld.core.factions.Faction
import org.livingworld.core.factions.Ideology
import org.livingworld.core.factions.LeadershipState
import org.livingworld.core.ids.*
import org.livingworld.core.inspector.SimulationInspector
import org.livingworld.core.inspector.formatFloat1
import org.livingworld.core.inspector.formatFloat2
import org.livingworld.core.memory.DefaultMemoryRetriever
import org.livingworld.core.memory.Memory
import org.livingworld.core.memory.MemoryContext
import org.livingworld.core.memory.MemoryStore
import org.livingworld.core.memory.RelationshipExperience
import org.livingworld.core.time.DayPhase
import org.livingworld.core.time.WorldDuration
import org.livingworld.core.time.WorldTime
import kotlin.test.*

/** P6: the small building blocks every other test leans on (time, memory, factions, the inspector's text). */
class CoreBuildingBlocksTest {

    // --- WorldTime / WorldDuration ---

    @Test
    fun time_splits_into_day_hour_minute_and_formats_with_padding() {
        val t = WorldTime.ZERO + WorldDuration.days(3) + WorldDuration.hours(7) + WorldDuration.minutes(5)
        assertEquals(3L, t.day)
        assertEquals(7, t.hour)
        assertEquals(5, t.minute)
        assertEquals("Day 3, 07:05 (DAWN)", t.formatted())
        assertEquals(WorldDuration.weeks(1), WorldDuration.days(7))
        assertEquals(WorldDuration.hours(2), WorldDuration.hours(1) * 2)
        assertEquals(WorldDuration.hours(5), (t + WorldDuration.hours(5)) - t)
        assertTrue(WorldTime(10) < WorldTime(11) && WorldDuration.ticks(1) < WorldDuration.ticks(2))
    }

    @Test
    fun day_phases_change_at_their_boundary_hours() {
        fun phaseAt(hour: Long) = WorldTime(hour * WorldTime.TICKS_PER_HOUR).phase
        assertEquals(
            listOf(DayPhase.NIGHT, DayPhase.DAWN, DayPhase.DAWN, DayPhase.MORNING, DayPhase.MORNING, DayPhase.MIDDAY,
                DayPhase.MIDDAY, DayPhase.AFTERNOON, DayPhase.AFTERNOON, DayPhase.DUSK, DayPhase.DUSK, DayPhase.NIGHT),
            listOf(4L, 5L, 7L, 8L, 11L, 12L, 14L, 15L, 18L, 19L, 21L, 22L).map(::phaseAt)
        )
        assertEquals(DayPhase.NIGHT, phaseAt(0))
    }

    @Test
    fun current_behaviour_time_can_go_negative_and_then_reads_as_day_zero() {
        // Current behaviour, may change: nothing stops time going below zero; integer division truncates toward zero,
        // so 30 minutes before the start reads as day 0 with a negative minute.
        val before = WorldTime.ZERO - WorldDuration.minutes(30)
        assertEquals(-30L, before.tick)
        assertEquals(0L, before.day)
        assertEquals(-30, before.minute)
    }

    // --- Memory ---

    private val player = EntityId.Player("player")
    private val guard = NpcId("guard_captain")
    private fun rel(entity: EntityId, importance: Float, day: Long) =
        Memory.Relationship(entity, RelationshipExperience.THEFT, importance, WorldTime(day * WorldTime.TICKS_PER_DAY))

    @Test
    fun memory_store_answers_what_was_seen_told_and_felt() {
        val told = Proposition.PlayerStole(ItemId("bread"))
        val store = MemoryStore()
            .add(Memory.Episodic(EventId(7), 0.5f, WorldTime.ZERO))
            .add(Memory.Told(guard, told, 0.3f, WorldTime.ZERO))
            .add(rel(player, 0.8f, 0))
        assertEquals(3, store.size)
        assertTrue(store.hasEpisodicMemory(EventId(7)))
        assertFalse(store.hasEpisodicMemory(EventId(8)))
        assertTrue(store.hasTold(guard, told))
        assertFalse(store.hasTold(NpcId("merchant"), told))
        assertEquals(1, store.memoriesOf(player).size)
        assertTrue(store.memoriesOf(guard.toEntityId()).isEmpty(), "a Told memory is not a relationship memory")
    }

    @Test
    fun retrieval_favours_important_recent_and_focal_memories() {
        val now = WorldTime(10 * WorldTime.TICKS_PER_DAY)
        val old = rel(player, 0.9f, 0)
        val fresh = rel(player, 0.9f, 10)
        val other = rel(guard.toEntityId(), 0.9f, 10)
        val store = MemoryStore().add(old).add(fresh).add(other)

        val focusedOnPlayer = DefaultMemoryRetriever().retrieve(store, MemoryContext(now, focalEntities = setOf(player)), limit = 3)
        assertEquals(listOf(fresh, old, other), focusedOnPlayer, "focal entity beats recency; recency breaks the tie")
        assertEquals(1, DefaultMemoryRetriever().retrieve(store, MemoryContext(now), limit = 1).size)
    }

    @Test
    fun current_behaviour_recency_halves_after_20_days_not_the_14_the_comment_claims() {
        // Current behaviour, may change: recency = 1 / (1 + 0.05 * days), which is 0.5 at 20 days.
        val now = WorldTime(20 * WorldTime.TICKS_PER_DAY)
        val twentyDaysOld = rel(player, 1.0f, 0)
        val freshHalf = rel(player, 0.5f, 20)
        val ranked = DefaultMemoryRetriever().retrieve(MemoryStore().add(twentyDaysOld).add(freshHalf), MemoryContext(now), limit = 2)
        assertEquals(listOf(twentyDaysOld, freshHalf), ranked, "equal scores keep insertion order")
    }

    @Test
    fun current_behaviour_memory_store_only_grows() {
        // Current behaviour, may change: there is no forgetting or cap; every add is kept forever.
        var store = MemoryStore()
        repeat(500) { store = store.add(Memory.Episodic(EventId(it.toLong()), 0.1f, WorldTime.ZERO)) }
        assertEquals(500, store.size)
    }

    // --- Faction ---

    private val titus = NpcId("guildmaster_titus")
    private val heir = NpcId("heir")
    private val guild = Faction(
        id = FactionId("guild"),
        name = "Guild",
        ideology = Ideology("trade"),
        members = setOf(titus, heir),
        allies = setOf(FactionId("watch")),
        leadership = LeadershipState(leader = titus, successionLine = listOf(titus, heir))
    )

    @Test
    fun a_dead_leader_is_succeeded_by_the_next_in_line_and_others_just_leave() {
        val afterTitus = guild.removeMember(titus)
        assertFalse(afterTitus.hasMember(titus))
        assertEquals(heir, afterTitus.leadership.leader)
        assertEquals(listOf(heir), afterTitus.leadership.successionLine)

        val afterHeir = guild.removeMember(heir)
        assertEquals(titus, afterHeir.leadership.leader)
        assertEquals(listOf(titus), afterHeir.leadership.successionLine)
        assertNull(guild.removeMember(titus).removeMember(heir).leadership.leader, "nobody left to lead")
    }

    @Test
    fun declaring_hostility_turns_an_ally_into_an_enemy() {
        val hostile = guild.declareHostility(FactionId("watch"))
        assertTrue(hostile.isEnemy(FactionId("watch")))
        assertFalse(hostile.isAlly(FactionId("watch")))
        assertTrue(guild.addMember(NpcId("new")).hasMember(NpcId("new")))
    }

    @Test
    fun current_behaviour_a_successor_need_not_be_a_member() {
        // Current behaviour, may change: the succession line is not checked against membership.
        val outsider = NpcId("outsider")
        val faction = guild.copy(leadership = LeadershipState(leader = titus, successionLine = listOf(outsider)))
        assertEquals(outsider, faction.removeMember(titus).leadership.leader)
        assertFalse(faction.removeMember(titus).hasMember(outsider))
    }

    // --- Inspector text ---

    @Test
    fun floats_format_to_fixed_decimals_with_sign_and_nan_as_zero() {
        assertEquals("0.50", formatFloat2(0.5f))
        assertEquals("-1.25", formatFloat2(-1.25f))
        assertEquals("12.00", formatFloat2(11.999f))
        assertEquals("0.00", formatFloat2(Float.NaN))
        assertEquals("-0.3", formatFloat1(-0.26f))
        assertEquals("0.0", formatFloat1(Float.NaN))
    }

    @Test
    fun current_behaviour_huge_and_infinite_floats_clamp_instead_of_failing() {
        // Current behaviour, may change: roundToInt saturates, so anything above ~21.47 million prints the same wrong value.
        assertEquals("21474836.47", formatFloat2(1e9f))
        assertEquals("21474836.47", formatFloat2(Float.POSITIVE_INFINITY))
    }

    @Test
    fun the_inspector_describes_an_npc_an_event_and_an_entity_arc() {
        val world = TestWorld.create()
        val killing = world.kill(NpcId("merchant"), killer = player, witnesses = listOf(NpcId("apprentice")))
        val inspector = SimulationInspector(world.state)

        val merchant = inspector.inspectNpc(NpcId("merchant"))
        assertTrue(merchant.contains("NPC INSPECTOR:"))
        assertTrue(merchant.contains("DEAD (Permanent Death)"))
        assertTrue(inspector.inspectNpc(NpcId("apprentice")).contains("player killed merchant"), "the witness's belief is listed")
        assertTrue(inspector.inspectNpc(NpcId("nobody")).contains("NOT FOUND"))

        val event = inspector.inspectEvent(killing.id)
        assertTrue(event.contains("Type: NpcKilled"))
        assertTrue(event.contains("Witnesses: apprentice"))
        assertTrue(event.contains("Causal Parent Event: Root initiative"))
        assertTrue(inspector.inspectEvent(EventId(999_999)).contains("NOT FOUND"))

        assertTrue(inspector.explainNarrativeArc(player).contains("Event #${killing.id.value}"))
    }
}
