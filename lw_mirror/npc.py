from dataclasses import dataclass, field, replace
from typing import Any

from .beliefs import BeliefStore
from .f32 import f32, coerce_in
from .memory import MemoryStore

F = f32


@dataclass(frozen=True)
class Goal:
    id: str
    description: str
    priority: float
    urgency: float

    @property
    def effective_score(self):
        return F(self.priority * F(F(0.6) + F(F(0.4) * self.urgency)))


@dataclass(frozen=True)
class Identity:
    name: str
    occupation: str
    homeLocation: str
    currentLocation: str
    isAlive: bool = True


@dataclass(frozen=True)
class EmotionalState:
    fear: float = 0.0
    anger: float = 0.0
    trust: float = 0.0
    grief: float = 0.0
    hope: float = 0.5
    guilt: float = 0.0
    suspicion: float = 0.0
    pride: float = 0.0

    def shifted(self, deltaFear=0.0, deltaAnger=0.0, deltaTrust=0.0, deltaGrief=0.0, deltaHope=0.0,
                deltaGuilt=0.0, deltaSuspicion=0.0, deltaPride=0.0):
        return EmotionalState(
            fear=coerce_in(F(self.fear + deltaFear), 0.0, 1.0),
            anger=coerce_in(F(self.anger + deltaAnger), 0.0, 1.0),
            trust=coerce_in(F(self.trust + deltaTrust), 0.0, 1.0),
            grief=coerce_in(F(self.grief + deltaGrief), 0.0, 1.0),
            hope=coerce_in(F(self.hope + deltaHope), 0.0, 1.0),
            guilt=coerce_in(F(self.guilt + deltaGuilt), 0.0, 1.0),
            suspicion=coerce_in(F(self.suspicion + deltaSuspicion), 0.0, 1.0),
            pride=coerce_in(F(self.pride + deltaPride), 0.0, 1.0),
        )


@dataclass(frozen=True)
class ResourceState:
    coins: float = 50.0
    food: float = 10.0
    health: float = 100.0
    items: tuple = ()

    @property
    def is_dead(self):
        return self.health <= 0.0


@dataclass(frozen=True)
class Npc:
    id: str
    identity: Identity
    traits: tuple = ()
    goals: tuple = ()
    memories: MemoryStore = field(default_factory=MemoryStore)
    beliefs: BeliefStore = field(default_factory=BeliefStore)
    factions: tuple = ()  # ordered set
    emotionalState: EmotionalState = EmotionalState()
    resources: ResourceState = ResourceState()

    @property
    def is_alive(self):
        return self.identity.isAlive and not self.resources.is_dead

    def has_trait(self, trait):
        return trait in self.traits

    def reputation_toward(self, target, relationships):
        rel = relationships.get(("npc", self.id), target)
        rep = F(F(F(F(rel.trust * F(0.4)) + F(rel.respect * F(0.3))) + F(rel.affection * F(0.3))) - F(rel.resentment * F(0.4)))
        if "SKEPTICAL" in self.traits:
            rep = F(rep - F(0.15))
        return coerce_in(rep, -1.0, 1.0)
