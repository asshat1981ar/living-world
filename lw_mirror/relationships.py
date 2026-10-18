from dataclasses import dataclass, replace

from .f32 import f32, coerce_in

F = f32


@dataclass(frozen=True)
class Relationship:
    trust: float = 0.0
    affection: float = 0.0
    respect: float = 0.0
    fear: float = 0.0
    resentment: float = 0.0
    sharedEvents: tuple = ()

    @property
    def overall_sentiment(self):
        v = F(F(F(F(F(self.trust * F(0.35)) + F(self.affection * F(0.25))) + F(self.respect * F(0.2)))
                - F(self.resentment * F(0.3))) - F(self.fear * F(0.1)))
        return coerce_in(v, -1.0, 1.0)

    def shifted(self, dt=0.0, da=0.0, dr=0.0, dfear=0.0, dres=0.0, event=None):
        dt = 0.0 if dt != dt else dt
        da = 0.0 if da != da else da
        dr = 0.0 if dr != dr else dr
        dfear = 0.0 if dfear != dfear else dfear
        dres = 0.0 if dres != dres else dres
        shared = self.sharedEvents
        if event is not None and event not in shared:
            shared = shared + (event,)
        return Relationship(
            trust=coerce_in(F(self.trust + dt), -1.0, 1.0),
            affection=coerce_in(F(self.affection + da), -1.0, 1.0),
            respect=coerce_in(F(self.respect + dr), -1.0, 1.0),
            fear=coerce_in(F(self.fear + dfear), 0.0, 1.0),
            resentment=coerce_in(F(self.resentment + dres), 0.0, 1.0),
            sharedEvents=shared,
        )


NEUTRAL = Relationship()


class RelationshipGraph:
    __slots__ = ("_edges", "_changes")

    def __init__(self, edges=None, changes=0):
        self._edges = edges if edges is not None else {}
        self._changes = changes

    @staticmethod
    def key(frm, to):
        return f"{frm[1]}->{to[1]}"

    def get(self, frm, to):
        return self._edges.get(self.key(frm, to), NEUTRAL)

    def set(self, frm, to, rel):
        ne = dict(self._edges)
        ne[self.key(frm, to)] = rel
        return RelationshipGraph(ne, self._changes + 1)

    def modify(self, frm, to, dt=0.0, da=0.0, dr=0.0, dfear=0.0, dres=0.0, event=None):
        cur = self.get(frm, to)
        return self.set(frm, to, cur.shifted(dt, da, dr, dfear, dres, event))

    def all_edges(self):
        return self._edges
