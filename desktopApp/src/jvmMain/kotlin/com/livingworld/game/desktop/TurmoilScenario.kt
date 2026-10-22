package com.livingworld.game.desktop

import org.livingworld.core.events.GameEvent
import org.livingworld.core.ids.FactionId
import org.livingworld.core.ids.ItemId
import org.livingworld.core.ids.LocationId
import org.livingworld.core.ids.NpcId
import org.livingworld.core.world.DefaultWorldReducer
import org.livingworld.core.world.WorldState

/**
 * A village that starts with trouble: a witnessed killing, a killerless death and a theft. It exists so the
 * determinism run exercises the death cascade, belief and rumor paths that the quiet default run never reaches.
 */
object TurmoilScenario {
    fun seed(start: WorldState): WorldState {
        val reducer = DefaultWorldReducer()
        var world = start

        val killing = GameEvent.NpcKilled(
            id = world.events.nextId(), time = world.time,
            victim = NpcId("merchant"), killer = NpcId("miner_derek").toEntityId(),
            witnesses = listOf(NpcId("guard_captain")),
            location = world.npc(NpcId("merchant")).identity.currentLocation, motive = "Scenario: dispute over ore"
        )
        world = reducer.apply(world, killing)

        val found = GameEvent.NpcKilled(
            id = world.events.nextId(), time = world.time,
            victim = NpcId("farmer_elena"), killer = null, witnesses = emptyList(),
            location = world.npc(NpcId("farmer_elena")).identity.currentLocation, motive = "Scenario: found dead"
        )
        world = reducer.apply(world, found)

        // The blacksmith owns the blade, so the theft actually moves something.
        val smith = world.npc(NpcId("blacksmith_krag"))
        world = world.copy(npcs = world.npcs + (smith.id to smith.copy(
            resources = smith.resources.copy(items = smith.resources.items + ItemId("iron_sword"))
        )))

        val theft = GameEvent.ItemStolen(
            id = world.events.nextId(), time = world.time,
            thief = NpcId("miner_derek").toEntityId(), item = ItemId("iron_sword"),
            owner = NpcId("blacksmith_krag").toEntityId(), witnesses = listOf(NpcId("herbalist_lyra")),
            location = world.npc(NpcId("blacksmith_krag")).identity.currentLocation
        )
        return reducer.apply(world, theft)
    }

    /**
     * A famine: the treasury is empty (so no wages are paid on day 0) and two non-producers start with no food, no coins
     * and poor health, so they cannot buy rations before the first day boundary. The money (the treasury and their
     * purses) goes to the merchant, so the economy stays closed. It exists so the golden covers the STARVE routine and
     * the death it causes, which the fed default village never reaches.
     */
    fun seedFamine(start: WorldState): WorldState {
        var world = start
        var moved = world.economy.treasury
        for (id in listOf("blacksmith_krag", "tanner_garth")) {
            val npc = world.npc(NpcId(id))
            moved += npc.resources.coins
            world = world.copy(npcs = world.npcs + (npc.id to npc.copy(
                resources = npc.resources.copy(food = 0f, coins = 0f, health = 20f)
            )))
        }
        val merchant = world.npc(NpcId("merchant"))
        return world.copy(
            npcs = world.npcs + (merchant.id to merchant.copy(resources = merchant.resources.copy(coins = merchant.resources.coins + moved))),
            economy = world.economy.copy(treasury = 0f)
        )
    }

    /**
     * A schism: the guild declares hostility toward the order, the order's successor and then its leader switch sides
     * (the faction ends up leaderless), two NPCs meet (one pair is strangers, one already has a history), the blacksmith
     * sells a blade to the baker, and the tanner starts wounded and terrified. The engine never produces an allegiance
     * change, a faction war, a meeting or an NPC-to-NPC trade on its own, so these events are seeded; the tanner takes
     * the engine's survival rule (Flee). It exists so a golden holds those reducer branches and the mirror's ports.
     */
    fun seedSchism(start: WorldState): WorldState {
        val reducer = DefaultWorldReducer()
        var world = start
        fun next() = world.events.nextId()

        world = reducer.apply(world, GameEvent.FactionHostilityDeclared(
            id = next(), time = world.time, declaringFaction = FactionId("iron_guild"),
            targetFaction = FactionId("verdant_order"), reason = "Scenario: a dispute over the mine",
            witnesses = listOf(NpcId("elder_rowan"))
        ))
        for (defector in listOf("herbalist_lyra", "elder_rowan")) {
            world = reducer.apply(world, GameEvent.AllegianceChanged(
                id = next(), time = world.time, npc = NpcId(defector), oldFaction = FactionId("verdant_order"),
                newFaction = FactionId("iron_guild"), reason = "Scenario: sided with the guild"
            ))
        }
        // Strangers (a fresh edge) and the apprentice/merchant pair (an edge with a history, which must be kept).
        world = reducer.apply(world, GameEvent.EntitiesMet(
            id = next(), time = world.time, entityA = NpcId("baker_milo").toEntityId(),
            entityB = NpcId("farmer_giles").toEntityId(), location = LocationId("market")
        ))
        world = reducer.apply(world, GameEvent.EntitiesMet(
            id = next(), time = world.time, entityA = NpcId("apprentice").toEntityId(),
            entityB = NpcId("merchant").toEntityId(), location = LocationId("market")
        ))

        val smith = world.npc(NpcId("blacksmith_krag"))
        world = world.copy(npcs = world.npcs + (smith.id to smith.copy(
            resources = smith.resources.copy(items = smith.resources.items + ItemId("iron_sword"))
        )))
        world = reducer.apply(world, GameEvent.TradeCompleted(
            id = next(), time = world.time, buyer = NpcId("baker_milo").toEntityId(),
            seller = NpcId("blacksmith_krag").toEntityId(), item = ItemId("iron_sword"), price = 12f,
            witnesses = listOf(NpcId("merchant"))
        ))

        val tanner = world.npc(NpcId("tanner_garth"))
        return world.copy(npcs = world.npcs + (tanner.id to tanner.copy(
            resources = tanner.resources.copy(health = 20f),
            emotionalState = tanner.emotionalState.copy(fear = 0.8f)
        )))
    }

    /** A grudge between a vengeful miner and the blacksmith: drives the attack, duel, flee, kill and witness paths. */
    fun seedFeud(start: WorldState): WorldState {
        val grudge = GameEvent.RelationshipShifted(
            id = start.events.nextId(), time = start.time,
            source = NpcId("miner_derek").toEntityId(), target = NpcId("blacksmith_krag").toEntityId(),
            deltaTrust = -0.5f, deltaAffection = -0.4f, deltaRespect = 0f, deltaFear = 0f, deltaResentment = 0.9f,
            reason = "Scenario: old feud over a stolen blade"
        )
        return DefaultWorldReducer().apply(start, grudge)
    }
}
