"""DefaultNpcDecisionEngine."""
from functools import cmp_to_key

from . import actions as ac
from . import beliefs as bf
from . import memory as mem
from .f32 import f32, coerce_in
from .worldtime import TICKS_PER_DAY

F = f32
RUMOR_BASE_UTILITY = F(0.3)
RUMOR_NEWS_WEIGHT = F(0.4)
RUMOR_FADE_PER_DAY = F(0.25)
MEMORY_RECALL_LIMIT = 8
_DAY_F = F(TICKS_PER_DAY)

_WARMTH = {
    "KINDNESS": F(0.15), "COOPERATION": F(0.15), "SHARED_DANGER": F(0.1), "TRADE": F(0.05),
    "BETRAYAL": F(-0.3), "ASSAULT": F(-0.3), "THEFT": F(-0.25),
}


def memory_bias(npc, action, world, base=None):
    if isinstance(action, (ac.Socialize, ac.ShareRumor, ac.Attack)):
        target = ("npc", action.target)
    elif isinstance(action, ac.Trade):
        target = action.partner
    else:
        return 0.0
    if base is None:
        base = mem.base_scores(npc.memories, world.time)
    recalled = [m for m in mem.retrieve_from(base, {target}, MEMORY_RECALL_LIMIT)
                if isinstance(m, mem.RelationshipMemory) and m.entity == target]
    bias = 0.0
    for m in recalled:
        warmth = _WARMTH[m.experience]
        eff = F(F(-warmth) * F(0.7)) if isinstance(action, ac.Attack) else warmth
        bias = F(bias + F(eff * m.importance))
    return coerce_in(bias, F(-0.6), F(0.6))


def newsworthiness(belief, world):
    age = max(world.time - belief.acquiredAt, 0)
    days_old = F(F(age) / _DAY_F)
    return F(F(belief.confidence * belief.reliability) / F(1.0 + F(RUMOR_FADE_PER_DAY * days_old)))


class Candidate:
    __slots__ = ("action", "risks", "effects")

    def __init__(self, action, effects, risks=()):
        self.action = action
        self.effects = effects  # list of utilityDelta floats
        self.risks = risks      # list of (probability, severity)

    def estimated_utility(self):
        benefit = 0.0
        for d in self.effects:
            benefit += d
        penalty = 0.0
        for p, s in self.risks:
            penalty += F(p * s)
        return F(F(benefit) - F(penalty))


def generate_candidates(npc, world):
    cands = [Candidate(ac.Rest(), [F(0.3)]),
             Candidate(ac.Work(npc.identity.currentLocation), [F(0.5)])]
    co = sorted((n for n in world.npcs.values()
                 if n.id != npc.id and n.is_alive and n.identity.currentLocation == npc.identity.currentLocation),
                key=lambda n: n.id)
    for other in co:
        cands.append(Candidate(ac.Socialize(other.id), [F(0.4)]))
        shareable = None
        best_key = None
        for b in npc.beliefs.all():
            src = b.source
            if isinstance(src, bf.Hearsay) and src.sourceNpc == other.id:
                continue
            if npc.memories.has_told(other.id, b.proposition):
                continue
            if shareable is None:
                shareable = b
                best_key = (newsworthiness(b, world), b.proposition.describe())
            else:
                nw = newsworthiness(b, world)
                desc = b.proposition.describe()
                # maxWithOrNull(compareBy newsworthiness, thenByDescending describe): replace only if strictly greater
                if nw > best_key[0] or (nw == best_key[0] and desc < best_key[1]):
                    shareable = b
                    best_key = (nw, desc)
        if shareable is not None:
            cands.append(Candidate(ac.ShareRumor(other.id, shareable),
                                   [F(RUMOR_BASE_UTILITY + F(RUMOR_NEWS_WEIGHT * newsworthiness(shareable, world)))]))

    if "apprentice" in npc.identity.occupation.lower():
        victims = []
        for b in npc.beliefs.all():
            p = b.proposition
            if isinstance(p, (bf.EntityKilledNpc, bf.NpcDied)):
                victims.append(p.victim)
        victims.sort()
        deceased = None
        for v in victims:
            n = world.npcs.get(v)
            if n is not None and not n.is_alive and "merchant" in n.identity.occupation.lower():
                deceased = n
                break
        already = any(isinstance(b.proposition, bf.ShopInheritedProp) and b.proposition.heir == npc.id
                      for b in npc.beliefs.all())
        if deceased is not None and not already:
            cands.append(Candidate(ac.InheritShop(deceased.id, "Alden's Sundries"), [F(0.95)]))

    occ = npc.identity.occupation.lower()
    if "merchant" in occ or "shopkeeper" in occ:
        cands.append(Candidate(ac.ChangePrice("bread", F(18.0), "Apprentice new management markup"), [F(0.6)]))
        if world.player.currentLocation == npc.identity.currentLocation:
            cands.append(Candidate(ac.Trade(world.player.id, "goods", F(15.0)), [F(0.7)]))

    for other in co:
        rel = world.relationships.get(("npc", npc.id), ("npc", other.id))
        if rel.resentment > F(0.7) or rel.trust < F(-0.6):
            cands.append(Candidate(ac.Attack(other.id, "Simmering resentment erupted into violence"),
                                   [F(0.6)], risks=[(F(0.5), F(0.9))]))
    return cands


def is_survival_override(npc):
    return npc.resources.health < 25.0 and npc.emotionalState.fear > F(0.6)


def decide(npc, world):
    """Returns the validated Intent."""
    if is_survival_override(npc):
        flee = ac.Flee(("player", "threat"))
        if ac.validate(flee, 1.0, npc, world) is None:
            return flee
    scored = []
    base = mem.base_scores(npc.memories, world.time)
    for c in generate_candidates(npc, world):
        score = c.estimated_utility()
        a = c.action
        if npc.has_trait("GREEDY") and isinstance(a, (ac.Trade, ac.ChangePrice)):
            score = F(score + F(0.4))
        if npc.has_trait("AMBITIOUS") and isinstance(a, ac.InheritShop):
            score = F(score + F(0.5))
        if npc.has_trait("VENGEFUL") and isinstance(a, ac.Attack):
            score = F(score + F(0.5))
        if npc.has_trait("COWARDLY") and isinstance(a, ac.Attack):
            score = F(score - F(0.6))
        if npc.has_trait("COMPASSIONATE") and isinstance(a, ac.Socialize):
            score = F(score + F(0.3))
        score = F(score + memory_bias(npc, a, world, base))
        scored.append((c, score))
    scored.sort(key=lambda p: p[1], reverse=True)  # stable descending
    for c, _ in scored:
        if ac.validate(c.action, F(0.85), npc, world) is None:
            return c.action
    return ac.Rest()
