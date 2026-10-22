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


def feud_seed(start):
    """TurmoilScenario.seedFeud"""
    return reducer.apply(start, ev.RelationshipShifted(
        id=start.events.next_id(), time=start.time, source=("npc", "miner_derek"), target=("npc", "blacksmith_krag"),
        deltaTrust=f32(-0.5), deltaAffection=f32(-0.4), deltaRespect=f32(0.0), deltaFear=f32(0.0),
        deltaResentment=f32(0.9), reason="Scenario: old feud over a stolen blade"))
