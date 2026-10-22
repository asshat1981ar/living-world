package org.livingworld.core

import kotlinx.coroutines.test.runTest
import org.livingworld.core.beliefs.Belief
import org.livingworld.core.beliefs.KnowledgeSource
import org.livingworld.core.beliefs.Proposition
import org.livingworld.core.events.GameEvent
import org.livingworld.core.ids.*
import org.livingworld.core.npc.Goal
import org.livingworld.core.npc.Npc
import org.livingworld.core.npc.Trait
import org.livingworld.core.simulation.AdviceOutcome
import org.livingworld.core.simulation.AiAdvice
import org.livingworld.core.simulation.AttackEvents
import org.livingworld.core.simulation.Intent
import org.livingworld.core.simulation.NpcDecisionEngine
import org.livingworld.core.simulation.SimulationEngine
import org.livingworld.core.simulation.ValidatedAction
import org.livingworld.core.time.WorldDuration
import org.livingworld.core.time.WorldTime
import org.livingworld.core.world.DefaultWorldReducer
import org.livingworld.core.world.WorldState
import kotlin.test.*

/**
 * The engine's own job: who acts, in what order, how each intent becomes events, and the quests it dispatches.
 * A scripted decision engine stands in for the NPCs so each intent can be driven directly.
 * Tests named `current_behaviour_*` pin what the engine does today where that looks doubtful; they are not a spec.
 */
class SimulationEngineTest {
    private class Scripted(private val plan: (Npc, WorldState) -> Intent) : NpcDecisionEngine {
        val asked = mutableListOf<NpcId>()
        override suspend fun decide(npc: Npc, world: WorldState): ValidatedAction {
            asked += npc.id
            return ValidatedAction(plan(npc, world))
        }
    }

    private val merchant = NpcId("merchant")
    private val apprentice = NpcId("apprentice")
    private val baker = NpcId("baker_milo")
    private val guard = NpcId("guard_captain")
    private val scholar = NpcId("scholar_erik")
    private val square = LocationId("village_square")

    /** The TestWorld village cut down to [keep], so only those NPCs act and event ids stay predictable. */
    private fun world(keep: List<NpcId> = emptyList()): WorldState {
        val s = TestWorld.create().state
        return if (keep.isEmpty()) s else s.copy(npcs = s.npcs.filterKeys { it in keep })
    }

    private fun WorldState.edit(id: NpcId, change: (Npc) -> Npc): WorldState = copy(npcs = npcs + (id to change(npc(id))))

    /** Runs one hourly tick where [actor] does [intent] and everyone else rests. */
    private suspend fun oneTick(state: WorldState, actor: NpcId, intent: Intent): WorldState {
        val engine = SimulationEngine(DefaultWorldReducer(), Scripted { npc, _ -> if (npc.id == actor) intent else Intent.Rest })
        return engine.tick(state)
    }

    private fun WorldState.eventsBy(npc: NpcId): List<GameEvent> = events.all().filter { npc.toEntityId() in it.actors }

    // --- Scheduling ---

    @Test
    fun a_zero_or_negative_step_returns_the_same_state() = runTest {
        val s = world()
        val engine = SimulationEngine(DefaultWorldReducer(), Scripted { _, _ -> Intent.Rest })
        assertSame(s, engine.tick(s, WorldDuration.ticks(0)))
        assertSame(s, engine.tick(s, WorldDuration.ticks(-5)))
        assertSame(s, engine.advance(s, WorldDuration.ticks(0)))
    }

    @Test
    fun advance_steps_hourly_and_ends_on_the_exact_tick() = runTest {
        val scripted = Scripted { _, _ -> Intent.Rest }
        val engine = SimulationEngine(DefaultWorldReducer(), scripted)
        val after = engine.advance(world(listOf(baker)), WorldDuration.ticks(90))   // one 60-tick step, then a 30-tick step
        assertEquals(90L, after.time.tick)
        assertEquals(listOf(baker, baker), scripted.asked)
    }

    @Test
    fun npcs_act_in_id_order_and_the_dead_are_never_asked() = runTest {
        val s = world().edit(guard) { it.copy(identity = it.identity.copy(isAlive = false)) }
        val scripted = Scripted { _, _ -> Intent.Rest }
        SimulationEngine(DefaultWorldReducer(), scripted).tick(s)
        assertEquals(s.npcs.keys.filter { it != guard }.sortedBy { it.value }, scripted.asked)
    }

    @Test
    fun at_night_only_needy_npcs_act_but_by_day_everyone_does() {
        val calm = world(listOf(baker, merchant)).edit(baker) { it.copy(goals = listOf(Goal("g", "idle", priority = 0.1f, urgency = 0.1f))) }
        val engine = SimulationEngine()
        val night = calm.copy(time = WorldTime(1L * WorldTime.TICKS_PER_HOUR))
        val morning = calm.copy(time = WorldTime(9L * WorldTime.TICKS_PER_HOUR))
        assertEquals(listOf(merchant), engine.selectActiveNpcs(night).map { it.id })   // merchant's goal is urgent (0.5)
        assertEquals(listOf(baker, merchant), engine.selectActiveNpcs(morning).map { it.id })
        val wounded = night.edit(baker) { it.copy(resources = it.resources.copy(health = 50f)) }
        assertEquals(listOf(baker, merchant), engine.selectActiveNpcs(wounded).map { it.id })
    }

    // --- Intents to events ---

    @Test
    fun rest_heals_and_costs_a_little_food() = runTest {
        val s = world(listOf(baker)).edit(baker) { it.copy(resources = it.resources.copy(health = 95f)) }
        val after = oneTick(s, baker, Intent.Rest)
        val e = after.events.all().single() as GameEvent.NpcRoutineCompleted
        assertEquals("REST", e.routineType)
        assertEquals(100f, after.npc(baker).resources.health)
        assertEquals(14.75f, after.npc(baker).resources.food)
    }

    // --- The village economy is a closed loop: coins move between purses and the treasury, never appear or vanish ---

    private val farmer = NpcId("farmer_giles")
    private fun WorldState.treasury() = economy.treasury
    private fun WorldState.totalCoins(): Double =
        npcs.values.sumOf { it.resources.coins.toDouble() } + player.coins + economy.treasury

    @Test
    fun ten_days_of_village_life_neither_mint_nor_destroy_coins() = runTest {
        val start = TestWorld.create().state
        val after = SimulationEngine().advance(start, WorldDuration.days(10))
        assertEquals(start.totalCoins(), after.totalCoins(), 0.01)
        assertTrue(after.events.all().any { it is GameEvent.RationPurchased }, "hungry workers buy food in ten days")
    }

    @Test
    fun work_pays_a_wage_from_the_treasury_and_producers_earn_nothing_for_it() = runTest {
        val worker = oneTick(world(listOf(scholar)), scholar, Intent.Work(square))
        assertEquals(50.25f, worker.npc(scholar).resources.coins)
        assertEquals(14.75f, worker.npc(scholar).resources.food)
        assertEquals(199.75f, worker.treasury())

        val producer = oneTick(world(listOf(baker)), baker, Intent.Work(square))
        assertEquals(50f, producer.npc(baker).resources.coins)
        assertEquals(16.5f, producer.npc(baker).resources.food)
        assertEquals(200f, producer.treasury())
    }

    @Test
    fun an_empty_treasury_pays_no_wage() = runTest {
        val broke = world(listOf(scholar)).let { it.copy(economy = it.economy.copy(treasury = 0f)) }
        val after = oneTick(broke, scholar, Intent.Work(square))
        assertEquals(50f, after.npc(scholar).resources.coins)
        assertEquals(0f, after.treasury())
    }

    @Test
    fun a_hungry_worker_buys_rations_from_the_best_stocked_producer() = runTest {
        val s = world(listOf(scholar, baker, farmer))
            .edit(scholar) { it.copy(resources = it.resources.copy(food = 4f)) }
            .edit(baker) { it.copy(resources = it.resources.copy(food = 20f)) }
            .edit(farmer) { it.copy(resources = it.resources.copy(food = 30f)) }
        val after = oneTick(s, scholar, Intent.Work(square))
        val ration = after.events.all().filterIsInstance<GameEvent.RationPurchased>().single()
        assertEquals(scholar, ration.buyer)
        assertEquals(farmer, ration.seller)
        assertEquals(11.75f, after.npc(scholar).resources.food)     // 4 - 0.25 for the shift + 8 bought
        assertEquals(47.25f, after.npc(scholar).resources.coins)    // 50 + 0.25 wage - 3
        assertEquals(21.75f, after.npc(farmer).resources.food)      // 30 - 0.25 resting - 8 sold
        assertEquals(52f, after.npc(farmer).resources.coins)        // 3 paid, 1 of it to the treasury
        assertEquals(200.75f, after.treasury())
    }

    @Test
    fun no_ration_without_a_producer_to_spare_food_or_coins_to_pay() = runTest {
        val hungry = world(listOf(scholar, baker))
            .edit(scholar) { it.copy(resources = it.resources.copy(food = 4f)) }
            .edit(baker) { it.copy(resources = it.resources.copy(food = 12f)) }  // would fall below the 5 it keeps
        assertTrue(oneTick(hungry, scholar, Intent.Work(square)).events.all().none { it is GameEvent.RationPurchased })

        val poor = hungry.edit(baker) { it.copy(resources = it.resources.copy(food = 40f)) }
            .edit(scholar) { it.copy(resources = it.resources.copy(coins = 2f)) }
        assertTrue(oneTick(poor, scholar, Intent.Work(square)).events.all().none { it is GameEvent.RationPurchased })
    }

    @Test
    fun upkeep_is_paid_to_the_treasury_once_a_day_by_those_with_coins() = runTest {
        val lateEvening = world(listOf(scholar, baker))
            .edit(baker) { it.copy(resources = it.resources.copy(coins = 0f)) }
            .copy(time = WorldTime(23L * WorldTime.TICKS_PER_HOUR))
        val engine = SimulationEngine(DefaultWorldReducer(), Scripted { _, _ -> Intent.Rest })
        val midnight = engine.tick(lateEvening)
        val upkeep = midnight.events.all().filterIsInstance<GameEvent.NpcRoutineCompleted>().filter { it.routineType == "UPKEEP" }
        assertEquals(listOf(scholar), upkeep.map { it.npc })
        assertEquals(47f, midnight.npc(scholar).resources.coins)
        assertEquals(203f, midnight.treasury())
        val nextHour = engine.tick(midnight)
        assertTrue(nextHour.events.all().count { it is GameEvent.NpcRoutineCompleted && it.routineType == "UPKEEP" } == 1)
    }

    @Test
    fun socializing_warms_the_relationship() = runTest {
        val after = oneTick(world(listOf(baker, merchant)), baker, Intent.Socialize(merchant))
        val shift = after.eventsBy(baker).filterIsInstance<GameEvent.RelationshipShifted>().single()
        assertEquals(merchant.toEntityId(), shift.target)
        assertEquals(0.05f, after.relationships.get(baker.toEntityId(), merchant.toEntityId()).trust)
    }

    @Test
    fun a_rumor_is_passed_on_as_hearsay_and_linked_to_its_origin() = runTest {
        val proposition = Proposition.NpcDied(guard)
        val belief = Belief(proposition, 1.0f, KnowledgeSource.DirectObservation, 1.0f, WorldTime.ZERO)
        val after = oneTick(world(listOf(baker, merchant)), baker, Intent.ShareRumor(merchant, belief))
        val rumor = after.events.all().filterIsInstance<GameEvent.RumorTransmitted>().single()
        assertEquals(KnowledgeSource.Hearsay(baker), rumor.transmittedBelief.source)
        assertEquals(rumor.transmittedBelief, after.npc(merchant).beliefs.get(proposition))
    }

    // Current behaviour, may change: an action aimed at someone dead (a rumor or an attack) is dropped without any event,
    // so the ledger has no trace that the NPC chose it (G5/G6).
    @Test
    fun current_behaviour_actions_aimed_at_the_dead_leave_no_trace() = runTest {
        val s = world(listOf(baker, merchant)).edit(merchant) { it.copy(identity = it.identity.copy(isAlive = false)) }
        val belief = Belief(Proposition.NpcDied(guard), 1.0f, KnowledgeSource.DirectObservation, 1.0f, WorldTime.ZERO)
        assertTrue(oneTick(s, baker, Intent.ShareRumor(merchant, belief)).events.all().isEmpty())
        assertTrue(oneTick(s, baker, Intent.Attack(merchant, "grudge")).events.all().isEmpty())
    }

    @Test
    fun the_npc_who_chooses_a_trade_is_the_seller() = runTest {
        val s = world(listOf(merchant, baker)).edit(merchant) { it.copy(resources = it.resources.copy(items = listOf(ItemId("bread")))) }
        val after = oneTick(s, merchant, Intent.Trade(baker.toEntityId(), ItemId("bread"), 6f))
        val trade = after.events.all().filterIsInstance<GameEvent.TradeCompleted>().single()
        assertEquals(merchant.toEntityId(), trade.seller)
        assertEquals(baker.toEntityId(), trade.buyer)
        assertEquals(56f, after.npc(merchant).resources.coins)
        assertEquals(listOf(ItemId("bread")), after.npc(baker).resources.items)
    }

    // Current behaviour, may change: the event's oldPrice is the scarcity-adjusted getPrice (floored at 1, default 10),
    // not the stored price, so oldPrice is not always the previous event's newPrice.
    @Test
    fun current_behaviour_a_price_change_reports_the_scarcity_adjusted_old_price() = runTest {
        val s = world(listOf(merchant)).let { it.copy(economy = it.economy.copy(scarcityFactors = mapOf("bread" to 2.0f))) }
        val after = oneTick(s, merchant, Intent.ChangePrice(ItemId("bread"), 8f, "demand"))
        val e = after.events.all().filterIsInstance<GameEvent.PriceAdjusted>().single()
        assertEquals(10f, e.oldPrice)                          // stored 5.0 x scarcity 2.0
        assertEquals(8f, after.economy.itemPrices["bread"])
        val unknown = oneTick(world(listOf(merchant)), merchant, Intent.ChangePrice(ItemId("rope"), 3f, "new"))
        assertEquals(10f, unknown.events.all().filterIsInstance<GameEvent.PriceAdjusted>().single().oldPrice)
    }

    // Current behaviour, may change: the faction left is just the first in the NPC's set; any other memberships stay.
    @Test
    fun changing_allegiance_leaves_only_the_first_listed_faction() = runTest {
        val both = setOf(FactionId("iron_guild"), FactionId("verdant_order"))
        val s = world(listOf(baker)).edit(baker) { it.copy(factions = both) }
        val after = oneTick(s, baker, Intent.ChangeAllegiance(FactionId("verdant_order"), "coin"))
        val e = after.events.all().filterIsInstance<GameEvent.AllegianceChanged>().single()
        assertEquals(FactionId("iron_guild"), e.oldFaction)
        assertEquals(setOf(FactionId("verdant_order")), after.npc(baker).factions)
    }

    @Test
    fun an_allegiance_to_an_unknown_faction_is_refused() = runTest {
        val s = world(listOf(baker)).edit(baker) { it.copy(factions = setOf(FactionId("iron_guild"))) }
        val after = oneTick(s, baker, Intent.ChangeAllegiance(FactionId("bandits"), "coin"))
        assertTrue(
            after.events.all().none { it is GameEvent.AllegianceChanged },
            "an allegiance to a faction that does not exist was recorded"
        )
        assertEquals(setOf(FactionId("iron_guild")), after.npc(baker).factions)
    }

    @Test
    fun a_lethal_attack_is_followed_by_a_death_caused_by_it_and_seen_by_those_present() = runTest {
        val s = world(listOf(guard, merchant, baker, scholar))
            .edit(merchant) { it.copy(resources = it.resources.copy(health = 5f)) }
            .edit(scholar) { it.copy(identity = it.identity.copy(currentLocation = LocationId("tavern"))) }
        val after = oneTick(s, guard, Intent.Attack(merchant, "duty"))
        val attack = after.events.all().filterIsInstance<GameEvent.NpcAttacked>().single()
        val death = after.events.all().filterIsInstance<GameEvent.NpcKilled>().single()
        assertEquals(attack.id, death.causalEventId)
        assertEquals(guard.toEntityId(), death.killer)
        assertEquals(listOf(baker), attack.witnesses)          // the scholar was elsewhere
        assertEquals(listOf(baker), death.witnesses)
        assertTrue(after.isDead(merchant))
    }

    @Test
    fun a_survivable_attack_records_no_death() = runTest {
        val after = oneTick(world(listOf(guard, merchant)), guard, Intent.Attack(merchant, "spar"))
        assertEquals(1, after.events.all().filterIsInstance<GameEvent.NpcAttacked>().size)
        assertTrue(after.events.deaths().isEmpty())
        assertTrue(after.npc(merchant).isAlive)
    }

    @Test
    fun attack_events_record_a_fatal_blow_as_one_death_and_never_a_second() {
        val reducer = DefaultWorldReducer()
        val s = world(listOf(guard, merchant)).edit(merchant) { it.copy(resources = it.resources.copy(health = 5f)) }
        fun blow(state: WorldState) = GameEvent.NpcAttacked(state.events.nextId(), state.time, EntityId.Player(), merchant, 50f, square)
        val first = blow(s)
        val killed = AttackEvents.apply(reducer, s, first, motive = "duel")
        val death = killed.events.deaths().single()
        assertEquals(first.id, death.causalEventId)
        assertEquals(EntityId.Player(), death.killer)
        assertEquals("duel", death.motive)
        val again = AttackEvents.apply(reducer, killed, blow(killed), motive = "duel")
        assertEquals(1, again.events.deaths().size)
        val scratch = AttackEvents.apply(reducer, world(listOf(guard, merchant)), blow(world(listOf(guard, merchant))).copy(damage = 10f), motive = "spar")
        assertTrue(scratch.events.deaths().isEmpty())
    }

    @Test
    fun inheriting_a_shop_is_linked_to_the_death_that_made_it_possible() = runTest {
        val killed = TestWorld(world(listOf(apprentice, merchant))).apply { kill(merchant, killer = null) }.state
        val deathId = killed.events.deaths().single().id
        val after = oneTick(killed, apprentice, Intent.InheritShop(merchant, "Alden's Sundries"))
        val e = after.events.all().filterIsInstance<GameEvent.ShopInherited>().single()
        assertEquals(deathId, e.causalEventId)
    }

    // Current behaviour, may change: fleeing only lowers fear; the NPC does not move away from the threat.
    @Test
    fun current_behaviour_fleeing_lowers_fear_but_does_not_move_the_npc() = runTest {
        val s = world(listOf(baker)).edit(baker) { it.copy(emotionalState = it.emotionalState.copy(fear = 0.8f)) }
        val after = oneTick(s, baker, Intent.Flee(guard.toEntityId()))
        assertEquals("FLEE", (after.events.all().single() as GameEvent.NpcRoutineCompleted).routineType)
        assertEquals(0.7f, after.npc(baker).emotionalState.fear, 1e-6f)
        assertEquals(square, after.npc(baker).identity.currentLocation)
    }

    // --- Quest dispatch ---

    // A death becomes a public investigation only when someone living knows about it; the investigation is asked for
    // by one of those knowers, and it resolves when the quest is completed.
    @Test
    fun a_death_someone_saw_produces_one_murder_quest_issued_by_a_living_knower() = runTest {
        val killed = TestWorld(world(listOf(guard, baker)))
            .apply { kill(guard, killer = null, witnesses = listOf(baker)) }.state
        val engine = SimulationEngine(DefaultWorldReducer(), Scripted { _, _ -> Intent.Rest })
        val after = engine.advance(killed, WorldDuration.hours(3))
        val quest = after.activeQuests.single()
        assertEquals("quest_death_guard_captain", quest.id)
        assertEquals("Investigate the Murder of guard_captain", quest.title)
        assertEquals(
            baker.toEntityId(), quest.issuer,
            "the investigation must be asked for by someone who lives and knows about it"
        )
        assertEquals(75f, quest.reward)
        val dispatched = after.events.all().filterIsInstance<GameEvent.OpportunityDispatched>().single()
        assertEquals(killed.events.deaths().single().id, dispatched.causalEventId)
    }

    @Test
    fun a_death_nobody_saw_produces_no_public_quest() = runTest {
        val killed = TestWorld(world(listOf(guard, baker))).apply { kill(guard, killer = null) }.state
        val engine = SimulationEngine(DefaultWorldReducer(), Scripted { _, _ -> Intent.Rest })
        val after = engine.advance(killed, WorldDuration.hours(3))
        assertTrue(after.activeQuests.isEmpty(), "a murder nobody witnessed became a public quest")
    }

    // --- Rumor transmission ---

    @Test
    fun hearsay_loses_confidence_and_an_opportunistic_teller_distorts_more() {
        val s = world(listOf(merchant, baker, scholar))
        val belief = Belief(Proposition.NpcDied(guard), 1.0f, KnowledgeSource.DirectObservation, 1.0f, WorldTime.ZERO)
        val engine = SimulationEngine()
        val honest = engine.transmitBelief(s.npc(scholar), s.npc(baker), belief, WorldTime(5), s.relationships)
        val sly = engine.transmitBelief(s.npc(merchant), s.npc(baker), belief, WorldTime(5), s.relationships)
        assertTrue(s.npc(merchant).hasTrait(Trait.OPPORTUNISTIC))
        assertEquals(0.5f, honest.reliability)                 // strangers: reputation 0 maps to 0.5
        assertEquals(0.475f, honest.confidence, 1e-6f)          // 1.0 x 0.5 x 0.95
        assertEquals(0.425f, sly.confidence, 1e-6f)             // 1.0 x 0.5 x 0.85
        assertEquals(WorldTime(5), honest.acquiredAt)
        val faint = engine.transmitBelief(s.npc(scholar), s.npc(baker), belief.copy(confidence = 0.05f), WorldTime(5), s.relationships)
        assertEquals(0.1f, faint.confidence)
        assertEquals(0.1f, faint.reliability)
    }

    @Test
    fun ai_advice_is_recorded_just_before_the_action_and_changes_nothing_else() = runTest {
        val start = world(keep = listOf(merchant))
        val advised = object : NpcDecisionEngine {
            override suspend fun decide(npc: Npc, world: WorldState) = ValidatedAction(Intent.Rest)
            override suspend fun decideWithAdvice(npc: Npc, world: WorldState) =
                ValidatedAction(Intent.Rest) to AiAdvice("test-port", AdviceOutcome.FOLLOWED, candidateIndex = 2, explanation = "tired")
        }
        val withAdvice = SimulationEngine(DefaultWorldReducer(), advised).tick(start)
        val without = SimulationEngine(DefaultWorldReducer(), Scripted { _, _ -> Intent.Rest }).tick(start)

        val events = withAdvice.events.all()
        val record = events.filterIsInstance<GameEvent.AiAdviceRecorded>().single()
        assertEquals(merchant, record.npc)
        assertEquals(AdviceOutcome.FOLLOWED, record.outcome)
        assertEquals(2, record.candidateIndex)
        assertEquals("tired", record.explanation)
        val next = events[events.indexOf(record) + 1]
        assertTrue(next is GameEvent.NpcRoutineCompleted && next.npc == merchant, "the advised action follows its record")
        assertEquals(record.id, (next as GameEvent.NpcRoutineCompleted).causalEventId, "the action names the advice as its cause")

        assertEquals(without.npcs, withAdvice.npcs, "a record changes no NPC")
        assertEquals(without.economy, withAdvice.economy)
        assertEquals(without.events.size + 1, withAdvice.events.size)
    }

    @Test
    fun an_engine_without_advice_records_nothing() = runTest {
        val state = oneTick(world(keep = listOf(merchant)), merchant, Intent.Rest)
        assertTrue(state.events.all().none { it is GameEvent.AiAdviceRecorded })
    }
}
