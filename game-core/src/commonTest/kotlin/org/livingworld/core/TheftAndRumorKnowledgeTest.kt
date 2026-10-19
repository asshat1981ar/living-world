package org.livingworld.core

import org.livingworld.core.beliefs.Belief
import org.livingworld.core.beliefs.BeliefStore
import org.livingworld.core.beliefs.KnowledgeSource
import org.livingworld.core.beliefs.Proposition
import org.livingworld.core.ids.*
import org.livingworld.core.simulation.DefaultNpcDecisionEngine
import org.livingworld.core.simulation.Intent
import org.livingworld.core.time.WorldTime
import kotlin.test.*

/** G2: what an NPC knows comes from what it saw or was told; rumors lose certainty as they travel. */
class TheftAndRumorKnowledgeTest {
    @Test
    fun npc_cannot_know_unwitnessed_theft() {
        val world = TestWorld.create()
        val player = EntityId.Player("player")
        val sword = ItemId("iron_sword")

        world.steal(
            thief = player,
            item = sword,
            owner = NpcId("merchant").toEntityId(),
            witnesses = emptyList()
        )

        val npc = world.npc(NpcId("merchant"))

        assertFalse(
            npc.beliefs.knows(Proposition.PlayerStole(sword)),
            "Unwitnessed theft must not produce omniscience in victim (Invariant G2)"
        )
        assertEquals(
            0f,
            world.relationships.get(NpcId("merchant").toEntityId(), player).resentment,
            "Unwitnessed theft must not shift relationship resentment toward unseen thief (Invariant G2)"
        )
    }

    @Test
    fun npc_witnessed_theft_creates_belief_and_syncs_relationship() {
        val world = TestWorld.create()
        val player = EntityId.Player("player")
        val sword = ItemId("iron_sword")
        val witness = NpcId("guard_captain")

        world.steal(
            thief = player,
            item = sword,
            owner = NpcId("merchant").toEntityId(),
            witnesses = listOf(witness)
        )

        val witnessNpc = world.npc(witness)

        assertTrue(
            witnessNpc.beliefs.knows(
                Proposition.PlayerStole(sword),
                minConfidence = 0.8f
            ),
            "Witnessed theft must form high-confidence belief in witness"
        )
        assertTrue(
            witnessNpc.reputationToward(player, world.relationships) < 0f,
            "RT-3: Witness reputation must reflect the world relationship graph"
        )
    }

    @Test
    fun rumor_propagation_distorts_and_avoids_echo_back_to_source() {
        val world = TestWorld.create()
        val source = NpcId("merchant")
        val target = NpcId("apprentice")

        val originalBelief = Belief(
            proposition = Proposition.EntityIsHostile(EntityId.Player("bandit_scout")),
            confidence = 0.9f,
            source = KnowledgeSource.DirectObservation,
            reliability = 0.95f,
            acquiredAt = WorldTime.ZERO
        )

        world.spreadRumor(source, target, originalBelief)

        val targetNpc = world.npc(target)
        val acquired = targetNpc.beliefs.get(originalBelief.proposition)

        assertNotNull(acquired, "Target must acquire rumor belief")
        assertTrue(
            acquired.source is KnowledgeSource.Hearsay,
            "Rumor source must be recorded as Hearsay"
        )
        assertEquals(source, (acquired.source as KnowledgeSource.Hearsay).sourceNpc)

        val candidates = DefaultNpcDecisionEngine().generateCandidates(targetNpc, world.state)
        assertFalse(
            candidates.any {
                it.action is Intent.ShareRumor &&
                it.action.target == source &&
                it.action.belief.proposition == originalBelief.proposition
            },
            "RT-4: NPC must not echo a hearsay rumor back to the original sourceNpc"
        )
    }

    @Test
    fun belief_keys_use_platform_independent_float_text() {
        fun key(price: Float) = Proposition.ItemPrice(ItemId("bread"), price).describe()
        assertEquals("Price of bread is 14.0", key(14f))
        assertEquals("Price of bread is 7.5", key(7.5f))
        assertEquals("Price of bread is 0.1", key(0.1f))
        assertEquals("Price of bread is 12.35", key(12.35f))
        assertEquals("Price of bread is -3.0", key(-3f))
        assertEquals(
            "Price of bread changed from 5.0 to 14.0",
            Proposition.PriceChanged(ItemId("bread"), 5f, 14f).describe()
        )

        val belief = Belief(Proposition.ItemPrice(ItemId("bread"), 14f), 0.8f, KnowledgeSource.DirectObservation, 0.9f, WorldTime(1L))
        val store = BeliefStore().add(belief).add(belief)
        assertEquals(1, store.all().size, "Same price proposition must map to one belief key")
    }
}
