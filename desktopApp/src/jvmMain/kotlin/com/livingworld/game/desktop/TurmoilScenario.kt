package com.livingworld.game.desktop

import org.livingworld.core.events.GameEvent
import org.livingworld.core.ids.ItemId
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

        val theft = GameEvent.ItemStolen(
            id = world.events.nextId(), time = world.time,
            thief = NpcId("miner_derek").toEntityId(), item = ItemId("iron_sword"),
            owner = NpcId("blacksmith_krag").toEntityId(), witnesses = listOf(NpcId("herbalist_lyra")),
            location = world.npc(NpcId("blacksmith_krag")).identity.currentLocation
        )
        return reducer.apply(world, theft)
    }
}
