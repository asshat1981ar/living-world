package org.livingworld.core

import org.livingworld.core.events.EventLedger
import org.livingworld.core.events.GameEvent
import org.livingworld.core.ids.EventId
import org.livingworld.core.ids.LocationId
import org.livingworld.core.ids.NpcId
import org.livingworld.core.time.WorldTime
import kotlin.test.*

/** G5: the ledger only grows; find/record stay correct while being O(1) for the normal sequential case. */
class EventLedgerTest {
    private fun event(id: Long) = GameEvent.NpcRoutineCompleted(
        id = EventId(id), time = WorldTime(id), npc = NpcId("a"), routineType = "WORK", location = LocationId("l")
    )

    @Test
    fun sequential_events_are_found_by_id_and_unknown_ids_are_absent() {
        var ledger = EventLedger()
        repeat(5) { ledger = ledger.append { event(it.value) }.first }
        assertEquals(5, ledger.size)
        for (i in 1L..5L) assertEquals(EventId(i), ledger.find(EventId(i))?.id)
        assertNull(ledger.find(EventId(6)))
        assertNull(ledger.find(EventId(0)))
        assertNull(ledger.find(EventId(-3)))
        assertNull(ledger.find(EventId(Long.MAX_VALUE)))
    }

    @Test
    fun record_is_idempotent_and_never_loses_or_reorders_events() {
        val first = EventLedger().record(event(1)).record(event(2))
        val again = first.record(event(2))
        assertEquals(first, again)
        assertEquals(listOf(1L, 2L), again.all().map { it.id.value })
        assertEquals(EventId(3), again.nextId())
    }

    @Test
    fun gaps_and_out_of_order_ids_still_resolve_correctly() {
        val ledger = EventLedger().record(event(10)).record(event(3)).record(event(7))
        assertEquals(listOf(10L, 3L, 7L), ledger.all().map { it.id.value })
        for (id in listOf(10L, 3L, 7L)) assertEquals(EventId(id), ledger.find(EventId(id))?.id)
        for (id in listOf(1L, 2L, 4L, 5L, 6L, 8L, 9L, 11L)) assertNull(ledger.find(EventId(id)), "id $id was never recorded")
        assertEquals(EventId(11), ledger.nextId())
    }

    @Test
    fun an_older_ledger_is_unaffected_by_later_appends() {
        val one = EventLedger().record(event(1))
        val two = one.record(event(2))
        assertEquals(1, one.size)
        assertNull(one.find(EventId(2)))
        assertNotNull(two.find(EventId(1)))
    }

    @Test
    fun causal_chain_follows_parents_oldest_first() {
        val a = event(1)
        val b = GameEvent.NpcRoutineCompleted(EventId(2), WorldTime(2), NpcId("a"), "WORK", LocationId("l"), causalEventId = EventId(1))
        val c = GameEvent.NpcRoutineCompleted(EventId(3), WorldTime(3), NpcId("a"), "WORK", LocationId("l"), causalEventId = EventId(2))
        val ledger = EventLedger().record(a).record(b).record(c)
        assertEquals(listOf(1L, 2L, 3L), ledger.traceCausalChain(EventId(3)).map { it.id.value })
    }

    private fun death(id: Long, victim: String) = GameEvent.NpcKilled(
        id = EventId(id), time = WorldTime(id), victim = NpcId(victim), killer = null, witnesses = emptyList()
    )

    @Test
    fun the_death_index_matches_a_full_scan_however_it_was_built() {
        fun expected(l: EventLedger) = l.all().filterIsInstance<GameEvent.NpcKilled>().map { it.id.value }

        var warm = EventLedger().record(event(1))
        assertTrue(warm.deaths().isEmpty())               // index built early, then carried forward
        warm = warm.record(death(2, "x")).record(event(3)).record(death(4, "y"))
        val cold = EventLedger().record(event(1)).record(death(2, "x")).record(event(3)).record(death(4, "y"))
        assertEquals(expected(warm), warm.deaths().map { it.id.value })
        assertEquals(expected(cold), cold.deaths().map { it.id.value })
        assertEquals(warm, cold)                          // the index is not part of equality
        assertTrue(warm.containsDeath(NpcId("y")))
        assertFalse(warm.containsDeath(NpcId("nobody")))
    }

    @Test
    fun an_older_ledger_keeps_its_own_death_index() {
        val before = EventLedger().record(death(1, "x"))
        assertEquals(1, before.deaths().size)
        val after = before.record(death(2, "y"))
        assertEquals(1, before.deaths().size)
        assertEquals(2, after.deaths().size)
        val appended = after.append { death(it.value, "z") }.first
        assertEquals(listOf(1L, 2L, 3L), appended.deaths().map { it.id.value })
    }
}
