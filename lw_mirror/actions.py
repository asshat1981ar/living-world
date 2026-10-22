"""Intent variants and the ActionValidator."""
from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class Rest:
    pass


@dataclass(frozen=True)
class Work:
    location: str


@dataclass(frozen=True)
class Flee:
    threat: tuple


@dataclass(frozen=True)
class Socialize:
    target: str


@dataclass(frozen=True)
class ShareRumor:
    target: str
    belief: Any


@dataclass(frozen=True)
class Trade:
    partner: tuple
    item: str
    price: float


@dataclass(frozen=True)
class ChangePrice:
    item: str
    newPrice: float
    reason: str


@dataclass(frozen=True)
class ChangeAllegiance:
    newFaction: str
    reason: str


@dataclass(frozen=True)
class DeclareHostility:
    declaring: str
    target: str
    victim: str
    reason: str


@dataclass(frozen=True)
class Attack:
    target: str
    reason: str


@dataclass(frozen=True)
class InheritShop:
    deceased: str
    shopName: str


def validate(intent, confidence, npc, world):
    """Returns None when valid, else an error string (Result.failure)."""
    if not npc.is_alive or world.is_dead(npc.id):
        return "Dead or unknown NPC cannot perform actions"
    if confidence != confidence or confidence < 0.0 or confidence > 1.0:
        return "Proposal confidence out of valid [0.0, 1.0] range"
    if isinstance(intent, Work):
        if intent.location != npc.identity.currentLocation:
            return "Cannot work away from current location"
    elif isinstance(intent, Trade):
        return _trade(intent, npc, world)
    elif isinstance(intent, Attack):
        return _co_located(intent.target, npc, world)
    elif isinstance(intent, Socialize):
        return _co_located(intent.target, npc, world)
    elif isinstance(intent, ShareRumor):
        err = _co_located(intent.target, npc, world)
        if err:
            return err
        if not npc.beliefs.knows(intent.belief.proposition, min_confidence=_F01):
            return "NPC cannot share a rumor they do not hold in their BeliefStore (Invariant G2/G3)"
    elif isinstance(intent, ChangePrice):
        if intent.newPrice != intent.newPrice or intent.newPrice <= 0.0 or intent.newPrice > 10000.0:
            return "Price must be positive and within bounded economy limits"
    elif isinstance(intent, ChangeAllegiance):
        if intent.newFaction not in world.factions:
            return "Target faction does not exist"
    elif isinstance(intent, DeclareHostility):
        declaring = world.factions.get(intent.declaring)
        if declaring is None:
            return "Declaring faction does not exist"
        if declaring.leadership.leader != npc.id:
            return "Only the leader can declare hostility for the faction"
        if intent.target == intent.declaring:
            return "A faction cannot declare hostility on itself"
        if intent.target not in world.factions:
            return "Target faction does not exist"
    elif isinstance(intent, InheritShop):
        deceased = world.npcs.get(intent.deceased)
        if deceased is None or deceased.is_alive:
            return "Shop predecessor is not dead"
    return None


from .f32 import f32 as _f32
_F01 = _f32(0.1)


def _trade(action, npc, world):
    if action.partner == ("npc", npc.id):
        return "Cannot trade with self"
    if action.price != action.price or action.price <= 0.0:
        return "Trade price must be positive"
    kind = action.partner[0]
    if kind == "player":
        if world.player.currentLocation != npc.identity.currentLocation:
            return "Player is not co-located"
        if world.player.coins < action.price:
            return "Buyer does not have enough coins"
        return None
    if kind == "npc":
        buyer = world.npcs.get(action.partner[1])
        if buyer is None or not buyer.is_alive:
            return "Trade partner NPC is dead or missing"
        if buyer.identity.currentLocation != npc.identity.currentLocation:
            return "Trade partner NPC is not co-located"
        if buyer.resources.coins < action.price:
            return "Trade partner NPC lacks sufficient coins"
        return None
    return "Invalid trade partner entity type"


def _co_located(target_id, npc, world):
    if target_id == npc.id:
        return "Cannot act on self"
    target = world.npcs.get(target_id)
    if target is None or not target.is_alive:
        return "Target is dead or absent"
    if target.identity.currentLocation != npc.identity.currentLocation:
        return "Target is not co-located"
    return None
