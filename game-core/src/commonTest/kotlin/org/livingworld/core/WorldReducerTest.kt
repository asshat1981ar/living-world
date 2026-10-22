package org.livingworld.core

import org.livingworld.core.beliefs.Belief
import org.livingworld.core.beliefs.KnowledgeSource
import org.livingworld.core.beliefs.Proposition
import org.livingworld.core.events.GameEvent
import org.livingworld.core.factions.Faction
import org.livingworld.core.factions.Ideology
import org.livingworld.core.factions.LeadershipState
import org.livingworld.core.ids.*
import org.livingworld.core.memory.Memory
import org.livingworld.core.memory.RelationshipExperience
import org.livingworld.core.npc.Trait
import org.livingworld.core.time.WorldTime
import org.livingworld.core.world.DefaultWorldReducer
import org.livingworld.core.world.QuestStatus
import org.livingworld.core.world.WorldState
import kotlin.test.*

/**
 * One section per reducer branch: what each event does to the world, including its clamps and no-op cases.
 * Tests named `current_behaviour_*` pin what the reducer does today where that looks doubtful; they are not a spec.
 * Change them deliberately (with the goldens and lw_mirror) when the behaviour is fixed.
 */
class WorldReducerTest {
    private val reducer = DefaultWorldReducer()
    private val merchant = NpcId("merchant")
    private val apprentice = NpcId("apprentice")
    private val baker = NpcId("baker_milo")
    private val elder = NpcId("elder_rowan")
    private val ironGuild = FactionId("iron_guild")
    private val verdantOrder = FactionId("verdant_order")
    private val square = LocationId("village_square")

    private fun world(): WorldState = TestWorld.create().state
    private fun WorldState.apply(build: (EventId, WorldTime) -> GameEvent): WorldState =
        reducer.apply(this, build(events.nextId(), WorldTime(events.size + 1L)))

    // --- Ledger and time (every branch) ---

    @Test
    fun applying_the_same_event_twice_changes_nothing() {
        val event = GameEvent.PriceAdjusted(EventId(1), WorldTime(1), merchant, ItemId("bread"), 5f, 6f, "test")
        val once = reducer.apply(world(), event)
        val twice = reducer.apply(once, event)
        assertEquals(1, twice.events.size)
        assertEquals(once.events, twice.events)
        assertEquals(once.economy, twice.economy, "a recorded event was applied a second time")
        assertEquals(once.npcs, twice.npcs, "a recorded event was applied a second time")
    }

    @Test
    fun the_dead_do_not_work_trade_or_heal() {
        val dead = world().apply { id, t -> GameEvent.NpcKilled(id, t, merchant, killer = null, witnesses = emptyList()) }

        val afterRoutine = dead.apply { id, t ->
            GameEvent.NpcRoutineCompleted(id, t, merchant, "WORK", square, deltaHealth = 10f)
        }
        assertEquals(dead.events.size, afterRoutine.events.size, "a dead NPC worked")
        assertEquals(0f, afterRoutine.npc(merchant).resources.health, "a dead NPC was healed")

        val afterTrade = dead.apply { id, t ->
            GameEvent.TradeCompleted(id, t, buyer = NpcId("apprentice").toEntityId(), seller = merchant.toEntityId(),
                item = ItemId("bread"), price = 5f)
        }
        assertEquals(dead.events.size, afterTrade.events.size, "a dead merchant sold something")
        assertEquals(dead.npc(apprentice).resources.coins, afterTrade.npc(apprentice).resources.coins)
        assertEquals(dead.npc(merchant).resources.coins, afterTrade.npc(merchant).resources.coins)
    }

    @Test
    fun an_event_from_the_past_never_moves_the_clock_back() {
        val later = reducer.apply(world(), GameEvent.PriceAdjusted(EventId(1), WorldTime(50), merchant, ItemId("bread"), 5f, 6f, "t"))
        val earlier = reducer.apply(later, GameEvent.PriceAdjusted(EventId(2), WorldTime(10), merchant, ItemId("bread"), 6f, 7f, "t"))
        assertEquals(WorldTime(50), earlier.time)
        assertEquals(7f, earlier.economy.itemPrices["bread"])
    }

    // --- PriceAdjusted ---

    @Test
    fun price_adjustment_sets_the_price_with_a_floor_of_half_a_coin() {
        val raised = world().apply { id, t -> GameEvent.PriceAdjusted(id, t, merchant, ItemId("bread"), 5f, 7.25f, "demand") }
        assertEquals(7.25f, raised.economy.itemPrices["bread"])
        val floored = raised.apply { id, t -> GameEvent.PriceAdjusted(id, t, merchant, ItemId("bread"), 7.25f, 0.1f, "glut") }
        assertEquals(0.5f, floored.economy.itemPrices["bread"])
        val newItem = floored.apply { id, t -> GameEvent.PriceAdjusted(id, t, merchant, ItemId("rope"), 0f, -3f, "odd") }
        assertEquals(0.5f, newItem.economy.itemPrices["rope"])
    }

    // --- ShopInherited ---

    @Test
    fun shop_inheritance_makes_the_heir_a_shopkeeper_with_a_treasury_grant_and_a_belief() {
        val after = world().apply { id, t -> GameEvent.ShopInherited(id, t, apprentice, merchant, "Alden's Sundries") }
        val heir = after.npc(apprentice)
        assertEquals("Shopkeeper (Alden's Sundries)", heir.identity.occupation)
        assertEquals(75f, heir.resources.coins)
        assertEquals(175f, after.economy.treasury)
        val belief = heir.beliefs.get(Proposition.ShopInherited(apprentice, "Alden's Sundries"))
        assertNotNull(belief)
        assertEquals(1.0f, belief.confidence)
        assertEquals(KnowledgeSource.DirectObservation, belief.source)
    }

    @Test
    fun an_inheritance_grant_and_a_quest_reward_are_limited_to_what_the_treasury_holds() {
        val poor = world().let { it.copy(economy = it.economy.copy(treasury = 10f)) }
        val heir = poor.apply { id, t -> GameEvent.ShopInherited(id, t, apprentice, merchant, "Shop") }
        assertEquals(60f, heir.npc(apprentice).resources.coins)
        assertEquals(0f, heir.economy.treasury)

        val quest = org.livingworld.core.world.QuestOpportunity("quest_scarcity_bread", "t", "d", merchant.toEntityId(), reward = 45f)
        val withQuest = poor.copy(activeQuests = listOf(quest))
            .let { it.copy(npcs = it.npcs + (merchant to it.npc(merchant).copy(resources = it.npc(merchant).resources.copy(items = listOf(ItemId("bread")))))) }
        val traded = withQuest.apply { id, t -> GameEvent.TradeCompleted(id, t, EntityId.Player(), merchant.toEntityId(), ItemId("bread"), 5f) }
        assertEquals(100f - 5f + 10f, traded.player.coins)
        assertEquals(0f, traded.economy.treasury)
        assertEquals(QuestStatus.COMPLETED, traded.activeQuests.single().status)
    }

    @Test
    fun shop_inheritance_by_an_unknown_heir_is_not_recorded() {
        val before = world()
        val after = before.apply { id, t -> GameEvent.ShopInherited(id, t, NpcId("nobody"), merchant, "Shop") }
        assertEquals(before.npcs, after.npcs)
        assertEquals(before.events.size, after.events.size, "an impossible inheritance was recorded")
    }

    // --- FactionHostilityDeclared ---

    @Test
    fun hostility_is_mutual_and_breaks_an_alliance() {
        val allied = world().let { s ->
            val iron = s.factions.getValue(ironGuild).copy(allies = setOf(verdantOrder))
            s.copy(factions = s.factions + (ironGuild to iron))
        }
        val after = allied.apply { id, t -> GameEvent.FactionHostilityDeclared(id, t, ironGuild, verdantOrder, "land") }
        assertTrue(after.factions.getValue(ironGuild).isEnemy(verdantOrder))
        assertTrue(after.factions.getValue(verdantOrder).isEnemy(ironGuild))
        assertFalse(after.factions.getValue(ironGuild).isAlly(verdantOrder))
    }

    @Test
    fun hostility_toward_an_unknown_faction_changes_no_faction() {
        val before = world()
        val after = before.apply { id, t -> GameEvent.FactionHostilityDeclared(id, t, ironGuild, FactionId("ghosts"), "?") }
        assertEquals(before.factions, after.factions)
    }

    // --- AllegianceChanged ---

    @Test
    fun allegiance_change_moves_the_npc_between_faction_rosters() {
        val joined = world().apply { id, t -> GameEvent.AllegianceChanged(id, t, baker, null, ironGuild, "pay") }
        assertEquals(setOf(ironGuild), joined.npc(baker).factions)
        assertTrue(joined.factions.getValue(ironGuild).hasMember(baker))

        val switched = joined.apply { id, t -> GameEvent.AllegianceChanged(id, t, baker, ironGuild, verdantOrder, "faith") }
        assertEquals(setOf(verdantOrder), switched.npc(baker).factions)
        assertFalse(switched.factions.getValue(ironGuild).hasMember(baker))
        assertTrue(switched.factions.getValue(verdantOrder).hasMember(baker))
    }

    // Current behaviour, may change: the NPC joins a faction id that does not exist, leaving a dangling reference.
    @Test
    fun an_allegiance_to_an_unknown_faction_is_refused() {
        val before = world()
        val after = before.apply { id, t -> GameEvent.AllegianceChanged(id, t, baker, null, FactionId("bandits"), "coin") }
        assertEquals(before.npc(baker).factions, after.npc(baker).factions)
        assertEquals(before.events.size, after.events.size, "an impossible allegiance was recorded")
        assertNull(after.factions[FactionId("bandits")])
    }

    // --- RelationshipShifted ---

    @Test
    fun a_positive_shift_is_remembered_as_cooperation_and_a_negative_one_as_betrayal() {
        val from = baker.toEntityId()
        val to = merchant.toEntityId()
        val kind = world().apply { id, t -> GameEvent.RelationshipShifted(id, t, from, to, 0.3f, 0.2f, 0.1f, 0f, 0f, "help") }
        assertEquals(0.3f, kind.relationships.get(from, to).trust)
        assertEquals(RelationshipExperience.COOPERATION, (kind.npc(baker).memories.all().last() as Memory.Relationship).experience)

        val spiteful = kind.apply { id, t -> GameEvent.RelationshipShifted(id, t, from, to, 0f, 0f, 0f, 0f, 0.4f, "slight") }
        assertEquals(0.4f, spiteful.relationships.get(from, to).resentment)
        assertEquals(RelationshipExperience.BETRAYAL, (spiteful.npc(baker).memories.all().last() as Memory.Relationship).experience)
    }

    // Current behaviour, may change: only trust and resentment decide the memory, so a pure loss of affection
    // (or respect, or a jump in fear) is remembered as cooperation.
    @Test
    fun current_behaviour_losing_affection_alone_is_remembered_as_cooperation() {
        val after = world().apply { id, t ->
            GameEvent.RelationshipShifted(id, t, baker.toEntityId(), merchant.toEntityId(), 0f, -0.5f, -0.3f, 0.4f, 0f, "insult")
        }
        assertEquals(RelationshipExperience.COOPERATION, (after.npc(baker).memories.all().last() as Memory.Relationship).experience)
    }

    @Test
    fun a_shift_from_the_player_changes_the_graph_but_no_npc_memory() {
        val before = world()
        val after = before.apply { id, t ->
            GameEvent.RelationshipShifted(id, t, EntityId.Player(), merchant.toEntityId(), 0.5f, 0f, 0f, 0f, 0f, "gift")
        }
        assertEquals(0.5f, after.relationships.get(EntityId.Player(), merchant.toEntityId()).trust)
        assertEquals(before.npcs, after.npcs)
    }

    // --- RumorTransmitted ---

    @Test
    fun a_rumor_gives_the_listener_the_belief_and_the_teller_a_told_memory() {
        val proposition = Proposition.NpcDied(NpcId("hunter_bram"))
        val belief = Belief(proposition, 0.8f, KnowledgeSource.Hearsay(elder), 0.7f, WorldTime.ZERO)
        val after = world().apply { id, t -> GameEvent.RumorTransmitted(id, t, elder, baker, belief, 0.05f) }

        val listener = after.npc(baker)
        assertEquals(belief, listener.beliefs.get(proposition))
        val kinds = listener.memories.all().map { it::class }
        assertEquals(listOf(Memory.Episodic::class, Memory.Semantic::class), kinds)
        assertEquals(0.8f, (listener.memories.all()[1] as Memory.Semantic).importance)
        assertTrue(after.npc(elder).memories.hasTold(baker, proposition))
    }

    @Test
    fun a_rumor_to_an_unknown_listener_changes_no_npc() {
        val before = world()
        val belief = Belief(Proposition.NpcDied(merchant), 0.8f, KnowledgeSource.Hearsay(elder), 0.7f, WorldTime.ZERO)
        val after = before.apply { id, t -> GameEvent.RumorTransmitted(id, t, elder, NpcId("nobody"), belief, 0f) }
        assertEquals(before.npcs, after.npcs)
    }

    // --- EntitiesMet ---

    @Test
    fun meeting_leaves_an_existing_relationship_intact() {
        val before = world()
        val a = apprentice.toEntityId()
        val m = merchant.toEntityId()
        val after = before.apply { id, t -> GameEvent.EntitiesMet(id, t, a, m, square) }
        assertEquals(before.relationships.get(a, m), after.relationships.get(a, m))
        assertEquals(0.7f, after.relationships.get(a, m).trust)
    }

    @Test
    fun meeting_never_erases_a_grudge_that_has_no_trust_or_affection_component() {
        val b = baker.toEntityId()
        val m = merchant.toEntityId()
        val wary = world().apply { id, t -> GameEvent.RelationshipShifted(id, t, b, m, 0f, 0f, 0.2f, 0.5f, 0.6f, "rumour") }
        val met = wary.apply { id, t -> GameEvent.EntitiesMet(id, t, b, m, square) }
        assertEquals(wary.relationships.get(b, m), met.relationships.get(b, m))
        assertEquals(0.6f, met.relationships.get(b, m).resentment)
    }

    @Test
    fun strangers_who_meet_start_neutral() {
        val b = baker.toEntityId()
        val s = NpcId("scholar_erik").toEntityId()
        val met = world().apply { id, t -> GameEvent.EntitiesMet(id, t, b, s, square) }
        assertEquals(org.livingworld.core.relationships.Relationship.NEUTRAL, met.relationships.get(b, s))
    }

    // --- NpcAttacked ---

    @Test
    fun an_attack_wounds_the_target_and_leaves_a_grudge() {
        val attacker = NpcId("guard_captain").toEntityId()
        val after = world().apply { id, t -> GameEvent.NpcAttacked(id, t, attacker, baker, 30f, square) }
        val target = after.npc(baker)
        assertEquals(70f, target.resources.health)
        assertEquals(0.2f, target.emotionalState.fear)
        assertEquals(0.3f, target.emotionalState.anger)
        val memory = target.memories.all().single() as Memory.Relationship
        assertEquals(attacker, memory.entity)
        assertEquals(RelationshipExperience.ASSAULT, memory.experience)
        val grudge = after.relationships.get(baker.toEntityId(), attacker)
        assertEquals(-0.4f, grudge.trust)
        assertEquals(0.5f, grudge.resentment)
        assertEquals(0.25f, grudge.fear)
    }

    // The reducer only applies events: a lethal NpcAttacked on its own lowers health to 0 and records no death.
    // Callers apply attacks through AttackEvents, which adds the NpcKilled (see SimulationEngineTest).
    @Test
    fun attack_damage_never_heals_and_a_lethal_blow_alone_records_no_death() {
        val attacker = EntityId.Player()
        val healed = world().apply { id, t -> GameEvent.NpcAttacked(id, t, attacker, baker, -10f, square) }
        assertEquals(100f, healed.npc(baker).resources.health)
        val overkill = healed.apply { id, t -> GameEvent.NpcAttacked(id, t, attacker, baker, 500f, square) }
        assertEquals(0f, overkill.npc(baker).resources.health)
        assertFalse(overkill.npc(baker).isAlive)
        assertTrue(overkill.events.deaths().isEmpty())
    }

    // --- NpcRoutineCompleted ---

    @Test
    fun routine_deltas_are_clamped_to_their_ranges() {
        val after = world().apply { id, t ->
            GameEvent.NpcRoutineCompleted(id, t, baker, "WORK", square, deltaHealth = 50f, deltaCoins = -999f, deltaFood = -999f)
        }
        val r = after.npc(baker).resources
        assertEquals(100f, r.health)
        assertEquals(0f, r.coins)
        assertEquals(0.5f, r.food)
    }

    @Test
    fun bread_scarcity_tracks_average_food_within_its_bounds() {
        val fed = world().apply { id, t -> GameEvent.NpcRoutineCompleted(id, t, baker, "REST", square) }
        assertEquals(1.0f, fed.economy.scarcityFactors["bread"])  // everyone at the starting 15 food

        var starving = fed
        for (npc in fed.npcs.keys.sortedBy { it.value }) {
            starving = starving.apply { id, t -> GameEvent.NpcRoutineCompleted(id, t, npc, "WORK", square, deltaFood = -999f) }
        }
        assertEquals(2.5f, starving.economy.scarcityFactors["bread"])

        var glut = fed
        for (npc in fed.npcs.keys.sortedBy { it.value }) {
            glut = glut.apply { id, t -> GameEvent.NpcRoutineCompleted(id, t, npc, "WORK", square, deltaFood = 999f) }
        }
        assertEquals(0.75f, glut.economy.scarcityFactors["bread"])
    }

    @Test
    fun routine_coins_are_a_transfer_with_the_treasury_limited_by_what_either_side_has() {
        val paid = world().let { it.copy(economy = it.economy.copy(treasury = 1f)) }
            .apply { id, t -> GameEvent.NpcRoutineCompleted(id, t, baker, "WORK", square, deltaCoins = 5f) }
        assertEquals(51f, paid.npc(baker).resources.coins)        // only what the treasury had
        assertEquals(0f, paid.economy.treasury)

        val taxed = world().apply { id, t -> GameEvent.NpcRoutineCompleted(id, t, baker, "UPKEEP", square, deltaCoins = -80f) }
        assertEquals(0f, taxed.npc(baker).resources.coins)         // only what the NPC had
        assertEquals(250f, taxed.economy.treasury)
    }

    @Test
    fun a_ration_purchase_moves_food_and_coins_and_taxes_the_sale() {
        val after = world().apply { id, t -> GameEvent.RationPurchased(id, t, buyer = baker, seller = merchant, food = 8f, price = 3f, tax = 1f) }
        assertEquals(23f, after.npc(baker).resources.food)
        assertEquals(47f, after.npc(baker).resources.coins)
        assertEquals(7f, after.npc(merchant).resources.food)
        assertEquals(52f, after.npc(merchant).resources.coins)
        assertEquals(201f, after.economy.treasury)
    }

    @Test
    fun a_ration_purchase_never_takes_more_than_the_buyer_has_or_the_seller_can_spare() {
        val s = world()
            .let { it.copy(npcs = it.npcs + (baker to it.npc(baker).copy(resources = it.npc(baker).resources.copy(coins = 2f)))) }
            .let { it.copy(npcs = it.npcs + (merchant to it.npc(merchant).copy(resources = it.npc(merchant).resources.copy(food = 3f)))) }
        val after = s.apply { id, t -> GameEvent.RationPurchased(id, t, buyer = baker, seller = merchant, food = 8f, price = 3f, tax = 1f) }
        assertEquals(0f, after.npc(baker).resources.coins)
        assertEquals(17.5f, after.npc(baker).resources.food)       // the seller keeps the 0.5 floor
        assertEquals(0.5f, after.npc(merchant).resources.food)
        assertEquals(51f, after.npc(merchant).resources.coins)      // 2 paid, 1 of it tax
        assertEquals(201f, after.economy.treasury)
        val unknown = world().apply { id, t -> GameEvent.RationPurchased(id, t, buyer = NpcId("nobody"), seller = merchant, food = 8f, price = 3f, tax = 1f) }
        assertEquals(world().npcs, unknown.npcs)
        assertEquals(200f, unknown.economy.treasury)
    }

    @Test
    fun a_routine_for_an_unknown_npc_changes_nothing_but_the_ledger() {
        val before = world()
        val after = before.apply { id, t -> GameEvent.NpcRoutineCompleted(id, t, NpcId("nobody"), "WORK", square, deltaCoins = 5f) }
        assertEquals(before.npcs, after.npcs)
        assertEquals(before.economy, after.economy)
    }

    // --- OpportunityDispatched ---

    private fun opportunity(id: EventId, t: WorldTime, oid: String, urgency: Float, actors: List<EntityId> = emptyList()) =
        GameEvent.OpportunityDispatched(id, t, oid, "TEST", "Title $oid", square, urgency, "hook", actors = actors)

    // Current behaviour, may change: an opportunity with no actors is issued by a hard-coded "elder_rowan", whether or
    // not that NPC exists or is alive.
    @Test
    fun current_behaviour_an_opportunity_becomes_one_available_quest_with_a_clamped_reward_and_a_default_issuer() {
        val after = world()
            .apply { id, t -> opportunity(id, t, "low", 0.1f) }
            .apply { id, t -> opportunity(id, t, "mid", 0.9f, actors = listOf(merchant.toEntityId())) }
            .apply { id, t -> opportunity(id, t, "high", 5f) }
            .apply { id, t -> opportunity(id, t, "mid", 0.2f) }
        assertEquals(listOf("low", "mid", "high"), after.activeQuests.map { it.id })
        assertEquals(listOf(25f, 90f, 150f), after.activeQuests.map { it.reward })
        assertTrue(after.activeQuests.all { it.status == QuestStatus.AVAILABLE })
        assertEquals(elder.toEntityId(), after.activeQuests[0].issuer)
        assertEquals(merchant.toEntityId(), after.activeQuests[1].issuer)
        assertEquals(square.toEntityId(), after.activeQuests[0].target)
    }

    // --- ItemStolen (owner side; witness beliefs are covered in TheftAndRumorKnowledgeTest) ---

    private fun withBread(s: WorldState): WorldState {
        val m = s.npc(merchant)
        return s.copy(npcs = s.npcs + (merchant to m.copy(resources = m.resources.copy(items = listOf(ItemId("bread"))))))
    }

    @Test
    fun theft_takes_the_item_and_an_absent_owner_grows_suspicious() {
        val after = withBread(world()).apply { id, t ->
            GameEvent.ItemStolen(id, t, EntityId.Player(), ItemId("bread"), merchant.toEntityId(), witnesses = listOf(baker))
        }
        assertTrue(after.npc(merchant).resources.items.isEmpty())
        assertEquals(0.2f, after.npc(merchant).emotionalState.suspicion)
        val witness = after.npc(baker)
        assertNotNull(witness.beliefs.get(Proposition.PlayerStole(ItemId("bread"))))
        assertNotNull(witness.beliefs.get(Proposition.HasTrait(EntityId.Player(), Trait.OPPORTUNISTIC)))
        assertEquals(0.3f, witness.emotionalState.suspicion)
    }

    @Test
    fun an_owner_who_saw_the_theft_reacts_as_a_witness_only() {
        val after = withBread(world()).apply { id, t ->
            GameEvent.ItemStolen(id, t, EntityId.Player(), ItemId("bread"), merchant.toEntityId(), witnesses = listOf(merchant))
        }
        assertEquals(0.3f, after.npc(merchant).emotionalState.suspicion)
        assertTrue(after.npc(merchant).resources.items.isEmpty())
    }

    // A theft moves the item: from the owner's hands to the thief's.
    @Test
    fun a_theft_moves_the_item_from_the_owner_to_the_thief() {
        val before = world()
        val withSword = before.copy(player = before.player.copy(inventory = listOf(ItemId("iron_sword"))))
        val after = withSword.apply { id, t ->
            GameEvent.ItemStolen(id, t, merchant.toEntityId(), ItemId("iron_sword"), EntityId.Player(), witnesses = emptyList())
        }
        assertEquals(emptyList(), after.player.inventory, "the stolen item stayed with the victim")
        assertTrue(
            after.npc(merchant).resources.items.contains(ItemId("iron_sword")),
            "the thief did not get the stolen item"
        )
    }

    // --- NpcKilled (faction side; beliefs and grief are covered in DeathCascadeTest) ---

    @Test
    fun a_dead_leader_leaves_the_roster_and_the_next_in_line_takes_over() {
        val titus = NpcId("guildmaster_titus")
        val krag = NpcId("blacksmith_krag")
        val derek = NpcId("miner_derek")
        val guild = Faction(
            id = ironGuild,
            name = "The Iron Guild",
            ideology = Ideology("Industry & Order", emptyList()),
            members = setOf(titus, krag, derek),
            leadership = LeadershipState(leader = titus, successionLine = listOf(titus, krag, derek))
        )
        val s = world().let { it.copy(factions = it.factions + (ironGuild to guild)) }
        val after = s.apply { id, t -> GameEvent.NpcKilled(id, t, titus, killer = null, witnesses = emptyList()) }
        val next = after.factions.getValue(ironGuild)
        assertFalse(next.hasMember(titus))
        assertEquals(krag, next.leadership.leader)
        assertEquals(listOf(krag, derek), next.leadership.successionLine)
        assertFalse(after.npc(titus).isAlive)
    }

    // Current behaviour, may change: a second NpcKilled for an NPC who is already dead runs the cascade again
    // (witnesses grieve twice, a second death in the ledger).
    @Test
    fun current_behaviour_killing_the_dead_again_repeats_the_cascade() {
        val once = world().apply { id, t -> GameEvent.NpcKilled(id, t, merchant, killer = null, witnesses = listOf(baker)) }
        val twice = once.apply { id, t -> GameEvent.NpcKilled(id, t, merchant, killer = null, witnesses = listOf(baker)) }
        assertEquals(2, twice.events.deaths().size)
        assertTrue(twice.npc(baker).emotionalState.grief > once.npc(baker).emotionalState.grief)
    }

    @Test
    fun killing_an_unknown_npc_is_not_recorded() {
        val before = world()
        val after = before.apply { id, t -> GameEvent.NpcKilled(id, t, NpcId("nobody"), killer = null, witnesses = emptyList()) }
        assertEquals(before.npcs, after.npcs)
        assertEquals(before.events.size, after.events.size, "a death of someone who never existed was recorded")
    }
}
