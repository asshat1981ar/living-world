from dataclasses import dataclass, replace
from typing import Any

from .f32 import f32, coerce_in, coerce_at_least, stable_float_text

# ---- Propositions (frozen dataclasses: structural equality like Kotlin data classes) ----


@dataclass(frozen=True)
class PlayerStole:
    item: str

    def describe(self):
        return f"PlayerStole({self.item})"


@dataclass(frozen=True)
class EntityStoleItem:
    thief: tuple
    item: str

    def describe(self):
        return f"{self.thief[1]} stole item {self.item}"


@dataclass(frozen=True)
class EntityKilledNpc:
    killer: tuple
    victim: str

    def describe(self):
        return f"{self.killer[1]} killed {self.victim}"


@dataclass(frozen=True)
class NpcDied:
    victim: str

    def describe(self):
        return f"{self.victim} died"


@dataclass(frozen=True)
class EntityIsHostile:
    entity: tuple

    def describe(self):
        return f"{self.entity[1]} is hostile"


@dataclass(frozen=True)
class HasTrait:
    entity: tuple
    trait: str

    def describe(self):
        return f"{self.entity[1]} has trait {self.trait}"


@dataclass(frozen=True)
class NpcLoyalToFaction:
    npc: str
    faction: str

    def describe(self):
        return f"{self.npc} is loyal to faction {self.faction}"


@dataclass(frozen=True)
class ItemPrice:
    item: str
    price: float

    def describe(self):
        return f"Price of {self.item} is {stable_float_text(self.price)}"


@dataclass(frozen=True)
class PriceChanged:
    item: str
    oldPrice: float
    newPrice: float

    def describe(self):
        return f"Price of {self.item} changed from {stable_float_text(self.oldPrice)} to {stable_float_text(self.newPrice)}"


@dataclass(frozen=True)
class ShopInheritedProp:
    heir: str
    shopName: str

    def describe(self):
        return f"{self.heir} inherited {self.shopName}"


@dataclass(frozen=True)
class GeneralFact:
    key: str
    detail: str

    def describe(self):
        return f"[{self.key}] {self.detail}"


# ---- Knowledge sources ----


@dataclass(frozen=True)
class DirectObservation:
    pass


@dataclass(frozen=True)
class Observation:
    eventId: Any = None


@dataclass(frozen=True)
class Hearsay:
    sourceNpc: str


@dataclass(frozen=True)
class Deduction:
    pass


@dataclass(frozen=True)
class FactionReport:
    faction: str


@dataclass(frozen=True)
class Belief:
    proposition: Any
    confidence: float
    source: Any
    reliability: float
    acquiredAt: int

    @property
    def summary(self):
        return self.proposition.describe()


_F001 = f32(0.01)


class BeliefStore:
    """Insertion-ordered key -> Belief; replacing keeps position (Kotlin LinkedHashMap `+`)."""

    __slots__ = ("_b",)

    def __init__(self, beliefs=None):
        self._b = beliefs if beliefs is not None else {}

    def all(self):
        return list(self._b.values())

    def size(self):
        return len(self._b)

    def get(self, proposition):
        return self._b.get(proposition.describe())

    def knows(self, proposition, min_confidence=f32(0.5)):
        b = self.get(proposition)
        if b is None:
            return False
        return b.confidence >= min_confidence

    def add_or_update(self, belief):
        key = belief.proposition.describe()
        existing = self._b.get(key)
        conf = 0.0 if belief.confidence != belief.confidence else coerce_in(belief.confidence, 0.0, 1.0)
        rel = 0.0 if belief.reliability != belief.reliability else coerce_in(belief.reliability, 0.0, 1.0)
        sanitized = replace(belief, confidence=conf, reliability=rel)
        if existing is None:
            updated = sanitized
        else:
            num = f32(f32(existing.confidence * existing.reliability) + f32(sanitized.confidence * sanitized.reliability))
            den = coerce_at_least(f32(existing.reliability + sanitized.reliability), _F001)
            combined = f32(num / den)
            updated = replace(
                existing,
                confidence=coerce_in(combined, 0.0, 1.0),
                reliability=max(existing.reliability, sanitized.reliability),
                source=sanitized.source,
                acquiredAt=max(existing.acquiredAt, sanitized.acquiredAt),
            )
        nb = dict(self._b)
        nb[key] = updated
        return BeliefStore(nb)
