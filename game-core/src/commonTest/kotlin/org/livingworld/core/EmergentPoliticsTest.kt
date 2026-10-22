package org.livingworld.core

import kotlinx.coroutines.test.runTest
import org.livingworld.core.beliefs.Belief
import org.livingworld.core.beliefs.KnowledgeSource
import org.livingworld.core.beliefs.Proposition
import org.livingworld.core.events.GameEvent
import org.livingworld.core.ids.*
import org.livingworld.core.simulation.AiProposal
import org.livingworld.core.simulation.DefaultActionValidator
import org.livingworld.core.simulation.DefaultNpcDecisionEngine
import org.livingworld.core.simulation.Intent
import org.livingworld.core.simulation.SimulationEngine
import org.livingworld.core.time.WorldDuration
import org.livingworld.core.world.DefaultVillageFactory
import org.livingworld.core.world.DefaultWorldReducer
import org.livingworld.core.world.WorldState
import kotlin.test.*

/** Allegiance and war come from what an NPC feels and believes, not from a script. */
class EmergentPoliticsTest {
    private val engine = DefaultNpcDecisionEngine()
    private val guild = FactionId("iron_guild")
    private val order = FactionId("verdant_order")
    private val titus = NpcId("guildmaster_titus")
    private val derek = NpcId("miner_derek")
    private val rowan = NpcId("elder_rowan")
    private val giles = NpcId("farmer_giles")

    private fun village(): WorldState = DefaultVillageFactory.marketVillage()

    private fun resent(world: WorldState, from: NpcId, to: NpcId, resentment: Float = 0.9f): WorldState = world.copy(
        relationships = world.relationships.modify(from.toEntityId(), to.toEntityId(), deltaResentment = resentment)
    )

    private fun believing(world: WorldState, who: NpcId, p: Proposition, confidence: Float = 0.9f): WorldState {
        val npc = world.npc(who)
        val belief = Belief(p, confidence, KnowledgeSource.DirectObservation, 1f, world.time)
        return world.copy(npcs = world.npcs + (who to npc.copy(beliefs = npc.beliefs.add(belief))))
    }

    private fun candidates(world: WorldState, who: NpcId) = engine.generateCandidates(world.npc(who), world).map { it.action }

    private val killing = Proposition.EntityKilledNpc(giles.toEntityId(), derek)

    @Test
    fun a_quiet_village_has_no_politics() {
        val world = village()
        world.npcs.keys.forEach { id ->
            assertTrue(
                candidates(world, id).none { it is Intent.ChangeAllegiance || it is Intent.DeclareHostility },
                "$id considered politics with no grievance"
            )
        }
    }

    @Test
    fun a_member_who_resents_its_leader_considers_defecting() {
        val world = resent(village(), derek, titus)
        val move = candidates(world, derek).filterIsInstance<Intent.ChangeAllegiance>().single()
        assertEquals(order, move.newFaction)
        assertTrue(titus.value in move.reason)
    }

    @Test
    fun distrust_alone_is_enough() {
        val world = village().let {
            it.copy(relationships = it.relationships.modify(derek.toEntityId(), titus.toEntityId(), deltaTrust = -0.9f))
        }
        assertTrue(candidates(world, derek).any { it is Intent.ChangeAllegiance })
    }

    @Test
    fun a_leader_never_defects_from_its_own_faction() {
        val world = resent(village(), titus, titus)
        assertTrue(candidates(world, titus).none { it is Intent.ChangeAllegiance })
    }

    @Test
    fun there_is_no_refuge_under_a_leader_it_also_resents() {
        val world = resent(resent(village(), derek, titus), derek, rowan)
        assertTrue(candidates(world, derek).none { it is Intent.ChangeAllegiance })
    }

    @Test
    fun a_defector_does_not_switch_back_and_forth() {
        // Derek hates both leaders: after moving to the order he would be unhappy again, but the guild's leader is
        // exactly who he fled, so there is nowhere bearable to go.
        var world = resent(resent(village(), derek, titus), derek, rowan)
        world = DefaultWorldReducer().apply(world, GameEvent.AllegianceChanged(
            id = world.events.nextId(), time = world.time, npc = derek, oldFaction = guild, newFaction = order, reason = "test"
        ))
        assertTrue(candidates(world, derek).none { it is Intent.ChangeAllegiance || it is Intent.DeclareHostility })
    }

    @Test
    fun a_leader_who_believes_a_rival_killed_a_member_considers_war() {
        val world = believing(village(), titus, killing)
        val war = candidates(world, titus).filterIsInstance<Intent.DeclareHostility>().single()
        assertEquals(guild, war.declaring)
        assertEquals(order, war.target)
    }

    @Test
    fun war_needs_a_belief_a_leader_and_a_new_enemy() {
        assertTrue(candidates(village(), titus).none { it is Intent.DeclareHostility }, "no belief, no war")
        assertTrue(
            candidates(believing(village(), titus, killing, confidence = 0.4f), titus).none { it is Intent.DeclareHostility },
            "a doubtful rumor is not a cause for war"
        )
        assertTrue(
            candidates(believing(village(), derek, killing), derek).none { it is Intent.DeclareHostility },
            "a member who is not the leader cannot declare war"
        )
        val atWar = DefaultWorldReducer().apply(believing(village(), titus, killing), GameEvent.FactionHostilityDeclared(
            id = EventId(1), time = village().time, declaringFaction = guild, targetFaction = order, reason = "test"
        ))
        assertTrue(candidates(atWar, titus).none { it is Intent.DeclareHostility }, "already at war")
    }

    @Test
    fun a_killing_inside_the_faction_is_not_a_cause_for_war() {
        val inHouse = Proposition.EntityKilledNpc(NpcId("blacksmith_krag").toEntityId(), derek)
        assertTrue(candidates(believing(village(), titus, inHouse), titus).none { it is Intent.DeclareHostility })
    }

    @Test
    fun only_the_leader_may_declare_war_for_a_faction() {
        val world = village()
        val forged = AiProposal(Intent.DeclareHostility(guild, order, derek, "test"), 0.85f, "test")
        assertTrue(DefaultActionValidator().validate(forged, world.npc(titus), world).isSuccess)
        assertTrue(DefaultActionValidator().validate(forged, world.npc(derek), world).isFailure)
        val self = AiProposal(Intent.DeclareHostility(guild, guild, derek, "test"), 0.85f, "test")
        assertTrue(DefaultActionValidator().validate(self, world.npc(titus), world).isFailure)
        val unknown = AiProposal(Intent.DeclareHostility(guild, FactionId("nowhere"), derek, "test"), 0.85f, "test")
        assertTrue(DefaultActionValidator().validate(unknown, world.npc(titus), world).isFailure)
    }

    @Test
    fun the_war_is_declared_in_the_ledger_and_the_factions_turn_hostile() = runTest {
        val start = village()
        val killingEvent = GameEvent.NpcKilled(
            id = start.events.nextId(), time = start.time, victim = derek, killer = giles.toEntityId(),
            witnesses = emptyList(), location = start.npc(derek).identity.currentLocation, motive = "test"
        )
        val killed = DefaultWorldReducer().apply(start, killingEvent)
        val world = SimulationEngine().advance(believing(killed, titus, killing), WorldDuration.days(1))
        val declared = world.events.ofType<GameEvent.FactionHostilityDeclared>().single()
        assertEquals(guild, declared.declaringFaction)
        assertEquals(order, declared.targetFaction)
        assertEquals(killingEvent.id, declared.causalEventId, "the war should name the killing it avenges")
        assertTrue(order in world.factions.getValue(guild).enemies)
        assertTrue(guild in world.factions.getValue(order).enemies)
    }

    @Test
    fun a_resentful_member_defects_in_the_ledger() = runTest {
        val krag = NpcId("blacksmith_krag")
        val world = SimulationEngine().advance(resent(village(), krag, titus), WorldDuration.days(1))
        val change = world.events.ofType<GameEvent.AllegianceChanged>().single()
        assertEquals(krag, change.npc)
        assertEquals(order, change.newFaction)
        assertTrue(order in world.npc(krag).factions && guild !in world.npc(krag).factions)
    }

    // Current behaviour, may change: Attack (0.6 - 0.5 x 0.9 + 0.5 for a vengeful NPC) ties defection (0.65) and wins
    // only because it is listed first; a vengeful member fights its leader before it leaves him.
    @Test
    fun current_behaviour_a_vengeful_member_attacks_its_leader_before_it_defects() = runTest {
        val world = resent(village(), derek, titus)
        val decided = engine.decide(world.npc(derek), world).intent
        assertTrue(decided is Intent.Attack, "decided $decided")
    }

    @Test
    fun an_honest_member_who_resents_its_leader_chooses_to_defect() = runTest {
        val krag = NpcId("blacksmith_krag")
        val world = resent(village(), krag, titus)
        val decided = engine.decide(world.npc(krag), world).intent
        assertTrue(decided is Intent.ChangeAllegiance, "decided $decided")
    }
}
