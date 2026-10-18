from dataclasses import dataclass
from typing import Any

from .f32 import f32
from .worldtime import TICKS_PER_DAY


@dataclass(frozen=True)
class Episodic:
    eventId: int
    importance: float
    createdAt: int


@dataclass(frozen=True)
class RelationshipMemory:
    entity: tuple
    experience: str
    importance: float
    createdAt: int


@dataclass(frozen=True)
class Told:
    target: str
    proposition: Any
    importance: float
    createdAt: int


@dataclass(frozen=True)
class Semantic:
    proposition: Any
    importance: float
    createdAt: int


class MemoryStore:
    __slots__ = ("_e",)

    def __init__(self, entries=()):
        self._e = entries

    @property
    def size(self):
        return len(self._e)

    def all(self):
        return self._e

    def add(self, memory):
        return MemoryStore(self._e + (memory,))

    def has_told(self, target, proposition):
        for m in self._e:
            if isinstance(m, Told) and m.target == target and m.proposition == proposition:
                return True
        return False


_R005 = f32(0.05)
_ONE = 1.0
_TWO = 2.0
_DAY_F = f32(TICKS_PER_DAY)


def base_scores(memories, current_time):
    """Per-memory importance * recency (independent of the focal entities); shared across targets within a decision."""
    out = []
    for m in memories.all():
        age = max(current_time - m.createdAt, 0)
        days_old = f32(f32(age) / _DAY_F)
        recency = f32(_ONE / f32(_ONE + f32(_R005 * days_old)))
        out.append((m, f32(m.importance * recency)))
    return out


def retrieve_from(base, focal_entities, limit, focal_propositions=()):
    """DefaultMemoryRetriever.retrieve: score = importance * recency * relevance, stable descending sort, take limit."""
    scored = []
    for m, ir in base:
        relevance = _ONE
        if isinstance(m, RelationshipMemory):
            if m.entity in focal_entities:
                relevance = f32(relevance + _TWO)
        elif isinstance(m, Semantic):
            if m.proposition in focal_propositions:
                relevance = f32(relevance + _TWO)
        scored.append((m, f32(ir * relevance)))
    scored.sort(key=lambda p: p[1], reverse=True)  # stable, like sortedByDescending
    return [p[0] for p in scored[:limit]]


def retrieve(memories, current_time, focal_entities, limit, focal_propositions=()):
    return retrieve_from(base_scores(memories, current_time), focal_entities, limit, focal_propositions)
