package org.livingworld.core

import kotlinx.coroutines.test.runTest
import org.livingworld.core.beliefs.Proposition
import org.livingworld.core.ids.*
import org.livingworld.core.simulation.DefaultNpcDecisionEngine
import org.livingworld.core.simulation.Intent
import org.livingworld.core.world.QuestOpportunity
import org.livingworld.core.world.QuestStatus
import kotlin.test.*

/** G4: a death has consequences beyond the people who saw it. */
class DeathCascadeTest {
    private val merchant = NpcId("merchant")
    private val apprentice = NpcId("apprentice")
    private val player = EntityId.Player("player")

    private fun TestWorld.beliefs(id: NpcId) = npc(id).beliefs.all().map { it.proposition }

    @Test
    fun faction_mates_who_were_absent_hear_of_the_death() {
        val world = TestWorld.create()
        val mate = NpcId("guildmaster_titus")
        assertTrue(world.npc(mate).factions.any { it in world.npc(merchant).factions })
        world.kill(merchant, killer = player, witnesses = emptyList())
        assertTrue(Proposition.EntityKilledNpc(player, merchant) in world.beliefs(mate))
        val belief = world.npc(mate).beliefs.get(Proposition.EntityKilledNpc(player, merchant))!!
        assertTrue(belief.confidence < 1f, "second-hand news is less certain than seeing it")
    }

    @Test
    fun strangers_do_not_hear() {
        val world = TestWorld.create()
        val stranger = NpcId("tanner_garth")
        assertTrue(world.npc(stranger).factions.isEmpty())
        world.kill(merchant, killer = player)
        assertTrue(world.beliefs(stranger).none { it is Proposition.EntityKilledNpc })
    }

    @Test
    fun killerless_death_is_learned_and_unlocks_inheritance() {
        val world = TestWorld.create()
        world.kill(merchant, killer = null, witnesses = listOf(apprentice))
        assertTrue(Proposition.NpcDied(merchant) in world.beliefs(apprentice))
        val intents = DefaultNpcDecisionEngine().generateCandidates(world.npc(apprentice), world.state).map { it.action }
        assertTrue(intents.any { it is Intent.InheritShop })
    }

    @Test
    fun quests_issued_by_the_dead_fail() {
        val world = TestWorld.create()
        val quest = QuestOpportunity(
            id = "q", title = "t", description = "d", issuer = apprentice.toEntityId(),
            target = LocationId("market").toEntityId(), reward = 45f, status = QuestStatus.AVAILABLE
        )
        world.state = world.state.copy(activeQuests = world.state.activeQuests + quest)
        world.kill(apprentice, killer = player)
        assertEquals(QuestStatus.FAILED, world.state.activeQuests.first { it.id == "q" }.status)
    }

    private suspend fun scenario(): TestWorld {
        val world = TestWorld.create()
        world.kill(merchant, killer = player, witnesses = listOf(NpcId("guard_captain")))
        world.kill(NpcId("tanner_garth"), killer = null)
        world.kill(apprentice, killer = NpcId("miner_derek").toEntityId(), witnesses = listOf(NpcId("elder_rowan")))
        world.advance(10)
        return world
    }

    /** The 30-day fingerprint run has no deaths, so this is the determinism evidence for the cascade. */
    @Test
    fun cascade_is_deterministic_over_ten_days() = runTest {
        val a = scenario()
        val b = scenario()
        assertEquals(a.state, b.state)
        assertEquals(a.state.events.size, b.state.events.size)
    }
}
