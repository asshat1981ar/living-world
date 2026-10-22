package org.livingworld.core

import org.livingworld.core.events.GameEvent
import org.livingworld.core.ids.*
import org.livingworld.core.time.WorldTime
import org.livingworld.core.world.DefaultWorldReducer
import org.livingworld.core.world.StateDigest
import org.livingworld.core.world.WorldInvariants
import kotlin.test.*

/** The rules a world must satisfy at rest (the contract the save gate checks). */
class WorldInvariantsTest {

    private fun world() = TestWorld.create().state

    @Test
    fun a_healthy_world_breaks_no_rule() {
        assertEquals(emptyList(), WorldInvariants.violations(world()))
    }

    @Test
    fun a_dead_npc_with_health_is_a_violation() {
        val state = world()
        val merchant = state.npc(NpcId("merchant"))
        val broken = state.copy(
            npcs = state.npcs + (merchant.id to merchant.copy(
                identity = merchant.identity.copy(isAlive = false),
                resources = merchant.resources.copy(health = 50f)
            ))
        )
        assertTrue(WorldInvariants.violations(broken).any { it.contains("deceased NPC merchant") })
    }

    @Test
    fun an_alive_npc_without_health_is_a_violation() {
        val state = world()
        val merchant = state.npc(NpcId("merchant"))
        val broken = state.copy(
            npcs = state.npcs + (merchant.id to merchant.copy(resources = merchant.resources.copy(health = 0f)))
        )
        assertTrue(WorldInvariants.violations(broken).any { it.contains("alive with no health") })
    }

    @Test
    fun an_event_that_names_an_unknown_npc_is_never_recorded() {
        val reducer = DefaultWorldReducer()
        val state = reducer.apply(world(), GameEvent.NpcKilled(
            id = EventId(1), time = WorldTime(1), victim = NpcId("nobody"), killer = null, witnesses = emptyList()
        ))
        assertEquals(0, state.events.size, "a death of someone who never existed was recorded")
        assertEquals(emptyList(), WorldInvariants.violations(state), "the world should still be sound")
    }

    @Test
    fun the_digest_sees_a_goal_that_decides_whether_an_npc_acts() {
        val world = world()
        val baker = world.npc(NpcId("baker_milo"))
        val eager = world.copy(
            npcs = world.npcs + (baker.id to baker.copy(
                goals = listOf(
                    org.livingworld.core.npc.Goal(
                        id = "survive_baker_milo", description = "Provide for self and family",
                        priority = 0.8f, urgency = 0.5f
                    )
                )
            ))
        )
        val idle = world.copy(
            npcs = world.npcs + (baker.id to baker.copy(
                goals = listOf(
                    org.livingworld.core.npc.Goal(
                        id = "survive_baker_milo", description = "Provide for self and family",
                        priority = 0.8f, urgency = 0.1f
                    )
                )
            ))
        )
        assertNotEquals(
            "a goal's urgency decides whether the NPC acts, so the digest must see it",
            StateDigest.of(eager), StateDigest.of(idle)
        )
    }

    @Test
    fun a_quest_issued_by_a_dead_npc_is_a_violation() {
        val reducer = DefaultWorldReducer()
        var state = world()
        val guard = state.npc(NpcId("guard_captain"))
        state = reducer.apply(state, GameEvent.NpcKilled(
            id = state.events.nextId(), time = WorldTime(1), victim = NpcId("guard_captain"),
            killer = null, witnesses = emptyList()
        ))
        state = state.copy(
            activeQuests = listOf(
                org.livingworld.core.world.QuestOpportunity(
                    id = "quest_death_someone", title = "t", description = "d",
                    issuer = NpcId("guard_captain").toEntityId(),
                    status = org.livingworld.core.world.QuestStatus.AVAILABLE
                )
            )
        )
        assertTrue(
            WorldInvariants.violations(state).any { it.contains("dead NPC guard_captain") },
            "a quest issued by a dead NPC must be a violation"
        )
        assertFalse(guard.isAlive.not(), "precondition: the guard captain is dead")
    }

    @Test
    fun an_attack_by_a_dead_npc_is_never_recorded() {
        val reducer = DefaultWorldReducer()
        var state = world()
        state = reducer.apply(state, GameEvent.NpcKilled(
            id = state.events.nextId(), time = WorldTime(1), victim = NpcId("miner_derek"),
            killer = null, witnesses = emptyList()
        ))
        val before = state.events.size
        state = reducer.apply(state, GameEvent.NpcAttacked(
            id = state.events.nextId(), time = WorldTime(2), attacker = NpcId("miner_derek").toEntityId(),
            target = NpcId("merchant"), damage = 30f, location = LocationId("village_square")
        ))
        assertEquals(before, state.events.size, "a dead NPC struck")
        assertEquals(100f, state.npc(NpcId("merchant")).resources.health, "the target was hurt by a corpse")
    }

    @Test
    fun a_ledger_that_does_not_increase_is_a_violation() {
        val reducer = DefaultWorldReducer()
        var state = world()
        state = reducer.apply(state, GameEvent.PriceAdjusted(
            id = EventId(5), time = WorldTime(1), merchant = NpcId("merchant"), item = ItemId("bread"),
            oldPrice = 5f, newPrice = 6f, reason = "t"
        ))
        state = reducer.apply(state, GameEvent.PriceAdjusted(
            id = EventId(4), time = WorldTime(2), merchant = NpcId("merchant"), item = ItemId("bread"),
            oldPrice = 6f, newPrice = 7f, reason = "t"
        ))
        assertTrue(WorldInvariants.violations(state).any { it.contains("not after the previous one") })
    }

    @Test
    fun a_negative_treasury_is_a_violation() {
        val state = world().copy(economy = world().economy.copy(treasury = -1f))
        assertTrue(WorldInvariants.violations(state).any { it.contains("treasury") })
    }
}
