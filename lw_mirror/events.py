"""GameEvent variants. Class names double as the `simpleName` printed by the canonical dump."""
from dataclasses import dataclass, field
from typing import Any, Optional

from .f32 import f32

# DuelOutcome: name -> (damageMultiplier, description)
DUEL = {
    "COUNTER_STRIKE": (f32(1.35), "Strike intercepted Feint for critical counter damage"),
    "GUARD_BREAK": (f32(1.35), "Feint bypassed Ward defense for unmitigated strike"),
    "PARRIED": (f32(0.50), "Ward deflected Strike and halved incoming damage"),
    "STANDARD_EXCHANGE": (f32(1.00), "Matched stances resulted in standard exchange"),
}

_kw = dict(frozen=True, kw_only=True)


@dataclass(**_kw)
class NpcKilled:
    id: int
    time: int
    victim: str
    killer: Optional[tuple]
    witnesses: tuple
    causalEventId: Optional[int] = None
    location: Optional[str] = None
    motive: Optional[str] = None

    @property
    def actors(self):
        return ((self.killer,) if self.killer is not None else ()) + (("npc", self.victim),)


@dataclass(**_kw)
class ItemStolen:
    id: int
    time: int
    thief: tuple
    item: str
    owner: Optional[tuple]
    witnesses: tuple
    causalEventId: Optional[int] = None
    location: Optional[str] = None

    @property
    def actors(self):
        return (self.thief,) + ((self.owner,) if self.owner is not None else ())


@dataclass(**_kw)
class EntitiesMet:
    id: int
    time: int
    entityA: tuple
    entityB: tuple
    location: str
    witnesses: tuple = ()
    causalEventId: Optional[int] = None

    @property
    def actors(self):
        return (self.entityA, self.entityB)


@dataclass(**_kw)
class RelationshipShifted:
    id: int
    time: int
    source: tuple
    target: tuple
    deltaTrust: float
    deltaAffection: float
    deltaRespect: float
    deltaFear: float
    deltaResentment: float
    reason: str
    witnesses: tuple = ()
    causalEventId: Optional[int] = None

    @property
    def actors(self):
        return (self.source, self.target)


@dataclass(**_kw)
class RumorTransmitted:
    id: int
    time: int
    source: str
    target: str
    transmittedBelief: Any
    distortion: float
    witnesses: tuple = ()
    causalEventId: Optional[int] = None

    @property
    def actors(self):
        return (("npc", self.source), ("npc", self.target))


@dataclass(**_kw)
class RationPurchased:
    id: int
    time: int
    buyer: str
    seller: str
    food: float
    price: float
    tax: float
    witnesses: tuple = ()
    causalEventId: Optional[int] = None

    @property
    def actors(self):
        return (("npc", self.buyer), ("npc", self.seller))


@dataclass(**_kw)
class TradeCompleted:
    id: int
    time: int
    buyer: tuple
    seller: tuple
    item: str
    price: float
    witnesses: tuple = ()
    causalEventId: Optional[int] = None

    @property
    def actors(self):
        return (self.buyer, self.seller)


@dataclass(**_kw)
class AllegianceChanged:
    id: int
    time: int
    npc: str
    oldFaction: Optional[str]
    newFaction: str
    reason: str
    witnesses: tuple = ()
    causalEventId: Optional[int] = None

    @property
    def actors(self):
        return (("npc", self.npc), ("faction", self.newFaction))


@dataclass(**_kw)
class NpcAttacked:
    id: int
    time: int
    attacker: tuple
    target: str
    damage: float
    location: str
    witnesses: tuple = ()
    causalEventId: Optional[int] = None
    attackerStance: str = "STRIKE"
    defenderStance: str = "WARD"
    duelOutcome: str = "STANDARD_EXCHANGE"

    @property
    def actors(self):
        return (self.attacker, ("npc", self.target))


@dataclass(**_kw)
class PriceAdjusted:
    id: int
    time: int
    merchant: str
    item: str
    oldPrice: float
    newPrice: float
    reason: str
    witnesses: tuple = ()
    causalEventId: Optional[int] = None

    @property
    def actors(self):
        return (("npc", self.merchant),)


@dataclass(**_kw)
class ShopInherited:
    id: int
    time: int
    heir: str
    predecessor: str
    shopName: str
    witnesses: tuple = ()
    causalEventId: Optional[int] = None

    @property
    def actors(self):
        return (("npc", self.heir), ("npc", self.predecessor))


@dataclass(**_kw)
class FactionHostilityDeclared:
    id: int
    time: int
    declaringFaction: str
    targetFaction: str
    reason: str
    witnesses: tuple = ()
    causalEventId: Optional[int] = None

    @property
    def actors(self):
        return (("faction", self.declaringFaction), ("faction", self.targetFaction))


@dataclass(**_kw)
class NpcRoutineCompleted:
    id: int
    time: int
    npc: str
    routineType: str
    location: str
    deltaHealth: float = 0.0
    deltaCoins: float = 0.0
    deltaFood: float = 0.0
    deltaFear: float = 0.0
    witnesses: tuple = ()
    causalEventId: Optional[int] = None

    @property
    def actors(self):
        return (("npc", self.npc),)


@dataclass(**_kw)
class OpportunityDispatched:
    id: int
    time: int
    opportunityId: str
    opportunityType: str
    title: str
    location: str
    urgency: float
    narrativeHook: str
    actors: tuple = ()
    witnesses: tuple = ()
    causalEventId: Optional[int] = None
