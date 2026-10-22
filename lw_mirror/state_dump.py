"""CanonicalStateDump (desktopApp) in Python: belief content, memories, items, traits, goals, emotions, factions, places,
quests and the player. Not dumped because the mirror does not model it: fears, values, skills, reputation, place names."""
from . import beliefs as bf
from . import memory as mem
from .f32 import f4


def _list(items):
    items = list(items)
    return "-" if not items else ",".join(items)


def _typed(entity):
    return f"{entity[0]}:{entity[1]}"


def _source(s):
    t = type(s)
    if t is bf.DirectObservation:
        return "seen"
    if t is bf.Observation:
        return f"observed:{s.eventId if s.eventId is not None else 0}"
    if t is bf.Hearsay:
        return f"hearsay:{s.sourceNpc}"
    if t is bf.Deduction:
        return "deduction"
    if t is bf.FactionReport:
        return f"faction:{s.faction}"
    raise TypeError(t)


def _memory(m):
    t = type(m)
    if t is mem.Episodic:
        return f"episodic:{m.eventId}:{f4(m.importance)}:{m.createdAt}"
    if t is mem.RelationshipMemory:
        return f"relationship:{_typed(m.entity)}:{m.experience}:{f4(m.importance)}:{m.createdAt}"
    if t is mem.Told:
        return f"told:{m.target}:{m.proposition.describe()}:{f4(m.importance)}:{m.createdAt}"
    if t is mem.Semantic:
        return f"semantic:{m.proposition.describe()}:{f4(m.importance)}:{m.createdAt}"
    raise TypeError(t)


def dump(world):
    out = [f"T|{world.time}"]
    for n in sorted(world.npcs.values(), key=lambda n: n.id):
        e = n.emotionalState
        emo = ",".join(f4(v) for v in (e.fear, e.anger, e.trust, e.grief, e.hope, e.guilt, e.suspicion, e.pride))
        out.append("|".join([
            "NPC", n.id, "true" if n.is_alive else "false", n.identity.name, n.identity.occupation,
            n.identity.homeLocation, n.identity.currentLocation,
            "traits=" + _list(n.traits), "items=" + _list(sorted(n.resources.items)), "emo=" + emo]))
        for g in n.goals:
            out.append(f"GOAL|{n.id}|{g.id}|{g.description}|{f4(g.priority)}|{f4(g.urgency)}")
        for b in sorted(n.beliefs.all(), key=lambda b: b.summary):
            out.append(f"BEL|{n.id}|{b.summary}|{f4(b.confidence)}|{f4(b.reliability)}|{_source(b.source)}|{b.acquiredAt}")
        for i, m in enumerate(n.memories.all()):
            out.append(f"MEM|{n.id}|{i}|{_memory(m)}")
    for fid, f in sorted(world.factions.items()):
        out.append("|".join([
            "FAC", fid, f.name, "members=" + _list(sorted(f.members)),
            "leader=" + (f.leadership.leader if f.leadership.leader is not None else "-"),
            "succession=" + _list(f.leadership.successionLine), "stability=" + f4(f.leadership.stability),
            "allies=" + _list(sorted(f.allies)), "enemies=" + _list(sorted(f.enemies))]))
    out.append("LOC|" + _list(sorted(world.locations)))
    for q in sorted(world.activeQuests, key=lambda q: q.id):
        target = _typed(q.target) if q.target is not None else "-"
        out.append(f"QUEST|{q.id}|{q.title}|{q.description}|{_typed(q.issuer)}|{target}|{f4(q.reward)}|{q.status}")
    p = world.player
    out.append(f"PLAYER|{_typed(p.id)}|{p.currentLocation}|{f4(p.coins)}|{_list(sorted(p.inventory))}|actions={len(p.historicalActions)}")
    return out
