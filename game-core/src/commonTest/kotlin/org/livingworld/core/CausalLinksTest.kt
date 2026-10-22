package org.livingworld.core

import kotlinx.coroutines.test.runTest
import org.livingworld.core.beliefs.Belief
import org.livingworld.core.beliefs.KnowledgeSource
import org.livingworld.core.beliefs.Proposition
import org.livingworld.core.events.CausalLinks
import org.livingworld.core.events.GameEvent
import org.livingworld.core.ids.*
import kotlin.test.*

class CausalLinksTest {
    private val player = EntityId.Player("player")

    private fun belief(p: Proposition, time: org.livingworld.core.time.WorldTime) = Belief(
        proposition = p, confidence = 0.9f, source = KnowledgeSource.DirectObservation, reliability = 1f, acquiredAt = time
    )

    @Test
    fun murder_quest_points_at_the_killing() = runTest {
        val world = TestWorld.create()
        // A witness knows about it, so the village can ask for an investigation.
        val kill = world.kill(NpcId("apprentice"), killer = player, witnesses = listOf(NpcId("guard_captain")))

        world.state = world.simulationEngine.tick(world.state)

        val dispatched = world.state.events.ofType<GameEvent.OpportunityDispatched>()
            .single { it.opportunityId == "quest_death_apprentice" }
        assertEquals(kill.id, dispatched.causalEventId)
        val chain = world.state.events.traceCausalChain(dispatched.id).map { it.id }
        assertEquals(listOf(kill.id, dispatched.id), chain)
    }

    @Test
    fun rumor_about_a_killing_links_to_the_killing_but_unknown_facts_do_not() {
        val world = TestWorld.create()
        val kill = world.kill(NpcId("apprentice"), killer = player)

        val killed = Proposition.EntityKilledNpc(player, NpcId("apprentice"))
        assertEquals(kill.id, CausalLinks.originOf(world.state.events, killed))
        assertNull(CausalLinks.originOf(world.state.events, Proposition.EntityKilledNpc(player, NpcId("merchant"))))
        assertNull(CausalLinks.originOf(world.state.events, Proposition.GeneralFact("weather", "rain")))
    }

    @Test
    fun theft_belief_links_to_the_matching_theft() {
        val world = TestWorld.create()
        val merchant = world.npc(NpcId("merchant"))
        // A theft needs an owner that holds the item.
        world.state = world.state.copy(npcs = world.state.npcs + (merchant.id to merchant.copy(
            resources = merchant.resources.copy(
                items = listOf(ItemId("iron_sword"), ItemId("iron_sword"), ItemId("silver_ring"))
            )
        )))
        val owner = NpcId("merchant").toEntityId()
        val first = world.steal(player, ItemId("iron_sword"), owner = owner)
        val second = world.steal(player, ItemId("iron_sword"), owner = owner)
        world.steal(player, ItemId("silver_ring"), owner = owner)

        val origin = CausalLinks.originOf(world.state.events, Proposition.PlayerStole(ItemId("iron_sword")))
        assertEquals(second.id, origin, "most recent matching theft wins")
        assertNotEquals(first.id, origin)
    }

    @Test
    fun war_quest_links_to_its_declaration_regardless_of_faction_order() {
        val world = TestWorld.create()
        val declaration = GameEvent.FactionHostilityDeclared(
            id = world.state.events.nextId(),
            time = world.state.time,
            declaringFaction = FactionId("verdant_order"),
            targetFaction = FactionId("iron_guild"),
            reason = "land dispute"
        )
        world.state = world.state.copy(events = world.state.events.record(declaration))

        assertEquals(declaration.id, CausalLinks.originOfQuest(world.state.events, "quest_war_iron_guild_verdant_order"))
        assertNull(CausalLinks.originOfQuest(world.state.events, "quest_scarcity_bread"))
        assertNull(CausalLinks.originOfQuest(world.state.events, "quest_death_nobody"))
    }

    @Test
    fun shop_inheritance_links_to_the_predecessors_death() {
        val world = TestWorld.create()
        val kill = world.kill(NpcId("merchant"), killer = player)
        assertEquals(kill.id, CausalLinks.deathOf(world.state.events, NpcId("merchant")))
        assertNull(CausalLinks.deathOf(world.state.events, NpcId("apprentice")))
    }
}
