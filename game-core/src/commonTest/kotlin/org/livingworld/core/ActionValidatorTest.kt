package org.livingworld.core

import org.livingworld.core.beliefs.Belief
import org.livingworld.core.beliefs.KnowledgeSource
import org.livingworld.core.beliefs.Proposition
import org.livingworld.core.ids.*
import org.livingworld.core.npc.Npc
import org.livingworld.core.simulation.AiProposal
import org.livingworld.core.simulation.DefaultActionValidator
import org.livingworld.core.simulation.Intent
import org.livingworld.core.world.WorldState
import kotlin.test.*

class ActionValidatorTest {
    private val validator = DefaultActionValidator()
    private val world = TestWorld.create()
    private val actor = NpcId("merchant")
    private val other = NpcId("apprentice")

    private fun check(intent: Intent, confidence: Float = 0.9f, state: WorldState = world.state, who: NpcId = actor) =
        validator.validate(AiProposal(intent, confidence), state.npc(who), state)

    private fun assertRejected(result: Result<*>, fragment: String) {
        assertTrue(result.isFailure, "expected rejection containing '$fragment'")
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains(fragment, ignoreCase = true), "'$message' should mention '$fragment'")
    }

    private fun withNpc(id: NpcId, change: (Npc) -> Npc): WorldState =
        world.state.copy(npcs = world.state.npcs + (id to change(world.state.npc(id))))

    private fun killedBelief() = Belief(
        proposition = Proposition.EntityKilledNpc(EntityId.Player("player"), other),
        confidence = 0.9f,
        source = KnowledgeSource.DirectObservation,
        reliability = 1.0f,
        acquiredAt = world.state.time
    )

    @Test
    fun accepts_rest_and_flee_unconditionally() {
        assertTrue(check(Intent.Rest).isSuccess)
        assertTrue(check(Intent.Flee(EntityId.Player("player"))).isSuccess)
    }

    @Test
    fun rejects_dead_actor() {
        val state = withNpc(actor) { it.copy(identity = it.identity.copy(isAlive = false)) }
        assertRejected(check(Intent.Rest, state = state), "dead")
    }

    @Test
    fun rejects_out_of_range_or_nan_confidence() {
        assertRejected(check(Intent.Rest, confidence = -0.1f), "confidence")
        assertRejected(check(Intent.Rest, confidence = 1.01f), "confidence")
        assertRejected(check(Intent.Rest, confidence = Float.NaN), "confidence")
        assertTrue(check(Intent.Rest, confidence = 0f).isSuccess)
        assertTrue(check(Intent.Rest, confidence = 1f).isSuccess)
    }

    @Test
    fun work_only_at_current_location() {
        assertTrue(check(Intent.Work(LocationId("village_square"))).isSuccess)
        assertRejected(check(Intent.Work(LocationId("tavern"))), "cannot work")
    }

    @Test
    fun trade_rejects_self_and_bad_prices() {
        assertRejected(check(Intent.Trade(actor.toEntityId(), ItemId("bread"), 5f)), "self")
        assertRejected(check(Intent.Trade(other.toEntityId(), ItemId("bread"), 0f)), "positive")
        assertRejected(check(Intent.Trade(other.toEntityId(), ItemId("bread"), -3f)), "positive")
        assertRejected(check(Intent.Trade(other.toEntityId(), ItemId("bread"), Float.NaN)), "positive")
    }

    @Test
    fun trade_with_player_needs_presence_and_coins() {
        val player = EntityId.Player("player")
        assertTrue(check(Intent.Trade(player, ItemId("bread"), 100f)).isSuccess)
        assertRejected(check(Intent.Trade(player, ItemId("bread"), 100.5f)), "enough coins")
        val away = world.state.copy(player = world.state.player.copy(currentLocation = LocationId("tavern")))
        assertRejected(check(Intent.Trade(player, ItemId("bread"), 5f), state = away), "not at")
    }

    @Test
    fun trade_with_npc_needs_alive_colocated_solvent_partner() {
        assertTrue(check(Intent.Trade(other.toEntityId(), ItemId("bread"), 50f)).isSuccess)
        assertRejected(check(Intent.Trade(other.toEntityId(), ItemId("bread"), 50.5f)), "lacks")
        assertRejected(check(Intent.Trade(NpcId("nobody").toEntityId(), ItemId("bread"), 5f)), "dead or missing")
        val dead = withNpc(other) { it.copy(identity = it.identity.copy(isAlive = false)) }
        assertRejected(check(Intent.Trade(other.toEntityId(), ItemId("bread"), 5f), state = dead), "dead or missing")
        val away = withNpc(other) { it.copy(identity = it.identity.copy(currentLocation = LocationId("tavern"))) }
        assertRejected(check(Intent.Trade(other.toEntityId(), ItemId("bread"), 5f), state = away), "co-located")
    }

    @Test
    fun attack_and_socialize_need_a_living_colocated_other() {
        assertTrue(check(Intent.Attack(other, "test")).isSuccess)
        assertTrue(check(Intent.Socialize(other)).isSuccess)
        assertRejected(check(Intent.Attack(actor, "test")), "self")
        assertRejected(check(Intent.Socialize(actor)), "self")
        assertRejected(check(Intent.Attack(NpcId("nobody"), "test")), "dead or absent")
        val dead = withNpc(other) { it.copy(identity = it.identity.copy(isAlive = false)) }
        assertRejected(check(Intent.Attack(other, "test"), state = dead), "dead or absent")
        val away = withNpc(other) { it.copy(identity = it.identity.copy(currentLocation = LocationId("tavern"))) }
        assertRejected(check(Intent.Socialize(other), state = away), "co-located")
    }

    @Test
    fun share_rumor_requires_holding_the_belief() {
        val belief = killedBelief()
        assertRejected(check(Intent.ShareRumor(other, belief)), "BeliefStore")
        val informed = withNpc(actor) { it.copy(beliefs = it.beliefs.addOrUpdate(belief)) }
        assertTrue(check(Intent.ShareRumor(other, belief), state = informed).isSuccess)
        assertRejected(check(Intent.ShareRumor(actor, belief), state = informed), "self")
    }

    @Test
    fun change_price_is_bounded() {
        assertTrue(check(Intent.ChangePrice(ItemId("bread"), 10_000f, "ok")).isSuccess)
        assertRejected(check(Intent.ChangePrice(ItemId("bread"), 10_000.5f, "too high")), "bounded")
        assertRejected(check(Intent.ChangePrice(ItemId("bread"), 0f, "free")), "bounded")
        assertRejected(check(Intent.ChangePrice(ItemId("bread"), Float.NaN, "nan")), "bounded")
    }

    @Test
    fun change_allegiance_needs_an_existing_faction() {
        assertTrue(check(Intent.ChangeAllegiance(FactionId("verdant_order"), "ok")).isSuccess)
        assertRejected(check(Intent.ChangeAllegiance(FactionId("ghosts"), "no such faction")), "does not exist")
    }

    @Test
    fun inherit_shop_requires_a_dead_predecessor() {
        assertRejected(check(Intent.InheritShop(NpcId("merchant"), "Shop"), who = other), "not dead")
        assertRejected(check(Intent.InheritShop(NpcId("nobody"), "Shop"), who = other), "not dead")
        val dead = withNpc(NpcId("merchant")) { it.copy(identity = it.identity.copy(isAlive = false)) }
        assertTrue(check(Intent.InheritShop(NpcId("merchant"), "Shop"), state = dead, who = other).isSuccess)
    }
}
