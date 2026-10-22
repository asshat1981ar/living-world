"""TurmoilScenario.seed"""
from dataclasses import replace

from . import events as ev
from .f32 import f32
from . import reducer


def turmoil_seed(start):
    world = start
    world = reducer.apply(world, ev.NpcKilled(
        id=world.events.next_id(), time=world.time, victim="merchant", killer=("npc", "miner_derek"),
        witnesses=("guard_captain",), location=world.npc("merchant").identity.currentLocation,
        motive="Scenario: dispute over ore"))
    world = reducer.apply(world, ev.NpcKilled(
        id=world.events.next_id(), time=world.time, victim="farmer_elena", killer=None, witnesses=(),
        location=world.npc("farmer_elena").identity.currentLocation, motive="Scenario: found dead"))
    # The blacksmith owns the blade, so the theft actually moves something.
    smith = world.npc("blacksmith_krag")
    world = replace(world, npcs=dict(world.npcs, **{
        "blacksmith_krag": replace(
            smith, resources=replace(smith.resources, items=smith.resources.items + ("iron_sword",)))
    }))
    return reducer.apply(world, ev.ItemStolen(
        id=world.events.next_id(), time=world.time, thief=("npc", "miner_derek"), item="iron_sword",
        owner=("npc", "blacksmith_krag"), witnesses=("herbalist_lyra",),
        location=world.npc("blacksmith_krag").identity.currentLocation))


def famine_seed(start):
    """TurmoilScenario.seedFamine"""
    world = start
    moved = world.economy.treasury
    for npc_id in ("blacksmith_krag", "tanner_garth"):
        npc = world.npc(npc_id)
        moved = f32(moved + npc.resources.coins)
        world = replace(world, npcs=dict(world.npcs, **{npc_id: replace(
            npc, resources=replace(npc.resources, food=f32(0.0), coins=f32(0.0), health=f32(20.0)))}))
    merchant = world.npc("merchant")
    return replace(
        world,
        npcs=dict(world.npcs, merchant=replace(
            merchant, resources=replace(merchant.resources, coins=f32(merchant.resources.coins + moved)))),
        economy=replace(world.economy, treasury=f32(0.0)))


def schism_seed(start):
    """TurmoilScenario.seedSchism"""
    world = start
    world = reducer.apply(world, ev.FactionHostilityDeclared(
        id=world.events.next_id(), time=world.time, declaringFaction="iron_guild", targetFaction="verdant_order",
        reason="Scenario: a dispute over the mine", witnesses=("elder_rowan",)))
    for defector in ("herbalist_lyra", "elder_rowan"):
        world = reducer.apply(world, ev.AllegianceChanged(
            id=world.events.next_id(), time=world.time, npc=defector, oldFaction="verdant_order",
            newFaction="iron_guild", reason="Scenario: sided with the guild"))
    world = reducer.apply(world, ev.EntitiesMet(
        id=world.events.next_id(), time=world.time, entityA=("npc", "baker_milo"), entityB=("npc", "farmer_giles"),
        location="market"))
    world = reducer.apply(world, ev.EntitiesMet(
        id=world.events.next_id(), time=world.time, entityA=("npc", "apprentice"), entityB=("npc", "merchant"),
        location="market"))
    smith = world.npc("blacksmith_krag")
    world = replace(world, npcs=dict(world.npcs, **{
        "blacksmith_krag": replace(
            smith, resources=replace(smith.resources, items=smith.resources.items + ("iron_sword",)))
    }))
    world = reducer.apply(world, ev.TradeCompleted(
        id=world.events.next_id(), time=world.time, buyer=("npc", "baker_milo"), seller=("npc", "blacksmith_krag"),
        item="iron_sword", price=f32(12.0), witnesses=("merchant",)))
    tanner = world.npc("tanner_garth")
    return replace(world, npcs=dict(world.npcs, tanner_garth=replace(
        tanner, resources=replace(tanner.resources, health=f32(20.0)),
        emotionalState=replace(tanner.emotionalState, fear=f32(0.8)))))


def feud_seed(start):
    """TurmoilScenario.seedFeud"""
    return reducer.apply(start, ev.RelationshipShifted(
        id=start.events.next_id(), time=start.time, source=("npc", "miner_derek"), target=("npc", "blacksmith_krag"),
        deltaTrust=f32(-0.5), deltaAffection=f32(-0.4), deltaRespect=f32(0.0), deltaFear=f32(0.0),
        deltaResentment=f32(0.9), reason="Scenario: old feud over a stolen blade"))
