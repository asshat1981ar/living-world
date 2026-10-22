package org.livingworld.core

import org.livingworld.core.beliefs.Belief
import org.livingworld.core.beliefs.KnowledgeSource
import org.livingworld.core.beliefs.Proposition
import org.livingworld.core.events.GameEvent
import org.livingworld.core.ids.*
import org.livingworld.core.time.WorldTime
import org.livingworld.core.world.EventValidator
import org.livingworld.core.world.WorldState
import kotlin.test.*

/** The pre-conditions of an event, asked of the validator itself rather than through the reducer. */
class EventValidatorTest {
    private val ghost = NpcId("nobody")
    private val merchant = NpcId("merchant")
    private val baker = NpcId("baker_milo")
    private val place = LocationId("village_square")
    private var next = 1L

    private fun id() = EventId(next++)
    private fun now() = WorldTime(1)
    private fun state() = TestWorld.create().state

    private fun assertRefused(event: GameEvent, fragment: String, state: WorldState = state()) {
        val reasons = EventValidator.reasons(state, event)
        assertTrue(reasons.any { fragment in it }, "expected a reason containing \"$fragment\", got $reasons")
        assertFalse(EventValidator.canApply(state, event))
    }

    @Test
    fun an_event_between_living_npcs_that_exist_is_allowed() {
        val s = state()
        val rumor = Belief(Proposition.GeneralFact("k", "v"), 0.9f, KnowledgeSource.DirectObservation, 1f, now())
        val events = listOf(
            GameEvent.RumorTransmitted(id(), now(), merchant, baker, rumor, 0f),
            GameEvent.EntitiesMet(id(), now(), merchant.toEntityId(), baker.toEntityId(), place),
            GameEvent.PlayerMoved(id(), now(), place, place)
        )
        events.forEach { assertEquals(emptyList(), EventValidator.reasons(s, it), it.summary) }
    }

    @Test
    fun a_killing_names_a_victim_and_a_killer_that_exist() {
        assertRefused(GameEvent.NpcKilled(id(), now(), ghost, null, emptyList()), "victim nobody")
        assertRefused(GameEvent.NpcKilled(id(), now(), merchant, ghost.toEntityId(), emptyList()), "killer nobody")
    }

    @Test
    fun a_theft_needs_a_thief_that_exists_and_an_owner_that_holds_the_item() {
        val item = ItemId("iron_sword")
        assertRefused(GameEvent.ItemStolen(id(), now(), ghost.toEntityId(), item, merchant.toEntityId(), emptyList()), "thief nobody")
        assertRefused(GameEvent.ItemStolen(id(), now(), baker.toEntityId(), item, ghost.toEntityId(), emptyList()), "owner nobody")
        assertRefused(GameEvent.ItemStolen(id(), now(), baker.toEntityId(), item, null, emptyList()), "no owner")
        assertRefused(
            GameEvent.ItemStolen(id(), now(), baker.toEntityId(), ItemId("no_such_item"), merchant.toEntityId(), emptyList()),
            "does not hold no_such_item"
        )
    }

    @Test
    fun a_trade_needs_two_parties_that_exist_a_stocked_seller_and_a_price() {
        val item = ItemId("bread")
        assertRefused(GameEvent.TradeCompleted(id(), now(), ghost.toEntityId(), merchant.toEntityId(), item, 1f), "buyer nobody is not in the world")
        assertRefused(GameEvent.TradeCompleted(id(), now(), baker.toEntityId(), ghost.toEntityId(), item, 1f), "seller nobody is not in the world")
        assertRefused(GameEvent.TradeCompleted(id(), now(), baker.toEntityId(), merchant.toEntityId(), item, 0f), "not a positive amount")
        assertRefused(GameEvent.TradeCompleted(id(), now(), baker.toEntityId(), merchant.toEntityId(), item, Float.NaN), "not a positive amount")
        assertRefused(GameEvent.TradeCompleted(id(), now(), baker.toEntityId(), merchant.toEntityId(), item, 1_000_000f), "cannot pay")
        assertRefused(
            GameEvent.TradeCompleted(id(), now(), baker.toEntityId(), merchant.toEntityId(), ItemId("no_such_item"), 1f),
            "does not hold no_such_item"
        )
    }

    @Test
    fun the_dead_do_not_act_and_are_not_acted_on() {
        val world = TestWorld.create()
        world.kill(merchant, killer = null)
        val s = world.state
        assertRefused(GameEvent.RationPurchased(id(), now(), baker, merchant, 8f, 3f, 1f), "seller", s)
        assertRefused(GameEvent.NpcAttacked(id(), now(), baker.toEntityId(), merchant, 5f, place), "target", s)
        assertRefused(GameEvent.NpcAttacked(id(), now(), merchant.toEntityId(), baker, 5f, place), "attacker", s)
        assertRefused(
            GameEvent.AllegianceChanged(id(), now(), merchant, null, s.factions.keys.first(), "test"),
            "is dead", s
        )
    }

    @Test
    fun a_relation_or_a_meeting_with_an_unknown_npc_is_refused() {
        assertRefused(GameEvent.EntitiesMet(id(), now(), ghost.toEntityId(), merchant.toEntityId(), place), "first party nobody")
        assertRefused(GameEvent.EntitiesMet(id(), now(), merchant.toEntityId(), ghost.toEntityId(), place), "second party nobody")
        assertRefused(
            GameEvent.RelationshipShifted(id(), now(), ghost.toEntityId(), merchant.toEntityId(), 0.1f, 0f, 0f, 0f, 0f, "t"),
            "source nobody"
        )
        assertRefused(
            GameEvent.RelationshipShifted(id(), now(), merchant.toEntityId(), ghost.toEntityId(), 0.1f, 0f, 0f, 0f, 0f, "t"),
            "target nobody"
        )
    }

    @Test
    fun places_factions_and_merchants_must_exist() {
        assertRefused(GameEvent.PlayerMoved(id(), now(), place, LocationId("atlantis")), "atlantis does not exist")
        assertRefused(
            GameEvent.AllegianceChanged(id(), now(), merchant, null, FactionId("no_such_faction"), "t"),
            "no_such_faction does not exist"
        )
        assertRefused(GameEvent.PriceAdjusted(id(), now(), ghost, ItemId("bread"), 1f, 2f, "t"), "merchant nobody")
    }
}
