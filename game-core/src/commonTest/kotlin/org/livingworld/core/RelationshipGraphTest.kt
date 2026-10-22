package org.livingworld.core

import org.livingworld.core.events.GameEvent
import org.livingworld.core.ids.*
import org.livingworld.core.relationships.RelationshipGraph
import org.livingworld.core.time.WorldTime
import org.livingworld.core.world.DefaultWorldReducer
import kotlin.test.*

/** Relationships are keyed by the kind *and* the name of both parties. */
class RelationshipGraphTest {

    @Test
    fun an_npc_and_a_player_with_the_same_name_are_different_relationships() {
        val graph = RelationshipGraph().modify(
            from = NpcId("x").toEntityId(), to = NpcId("y").toEntityId(), deltaTrust = 0.5f
        )
        assertEquals(0.5f, graph.get(NpcId("x").toEntityId(), NpcId("y").toEntityId()).trust)
        assertEquals(
            0f, graph.get(EntityId.Player("x"), EntityId.Player("y")).trust,
            "a player shared the relationship of an NPC with the same name"
        )
    }

    @Test
    fun a_player_that_shares_its_name_with_an_npc_is_still_the_player() {
        val base = TestWorld.create().state
        // A player called "merchant": the name collides with the NPC merchant, the kind does not.
        val world = base.copy(player = base.player.copy(id = EntityId.Player("merchant")))
        val merchant = world.npc(NpcId("merchant"))
        val after = DefaultWorldReducer().apply(
            world,
            GameEvent.TradeCompleted(
                id = world.events.nextId(), time = WorldTime(1),
                buyer = EntityId.Player("merchant"), seller = NpcId("merchant").toEntityId(),
                item = ItemId("bread"), price = 5f
            )
        )
        assertEquals(
            merchant.resources.coins + 5f, after.npc(NpcId("merchant")).resources.coins,
            "the merchant paid itself instead of being paid by the player"
        )
        assertEquals(world.player.coins - 5f, after.player.coins)
    }
}
