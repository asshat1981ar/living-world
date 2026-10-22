"""CanonicalDump (desktopApp) in Python."""
import re

from . import events as ev
from .f32 import f4


def _ids(lst):
    return "-" if not lst else ",".join(lst)


def _opt(v):
    return "-" if v is None else v


def line(e):
    t = type(e)
    if t is ev.NpcKilled:
        body = [e.victim, e.killer[1] if e.killer else "-", _opt(e.location), _opt(e.motive)]
    elif t is ev.ItemStolen:
        body = [e.thief[1], e.item, e.owner[1] if e.owner else "-", _opt(e.location)]
    elif t is ev.EntitiesMet:
        body = [e.entityA[1], e.entityB[1], e.location]
    elif t is ev.RelationshipShifted:
        body = [e.source[1], e.target[1], f4(e.deltaTrust), f4(e.deltaAffection), f4(e.deltaRespect),
                f4(e.deltaFear), f4(e.deltaResentment), e.reason]
    elif t is ev.RumorTransmitted:
        b = e.transmittedBelief
        body = [e.source, e.target, b.proposition.describe(), f4(b.confidence), f4(e.distortion)]
    elif t is ev.TradeCompleted:
        body = [e.buyer[1], e.seller[1], e.item, f4(e.price)]
    elif t is ev.RationPurchased:
        body = [e.buyer, e.seller, f4(e.food), f4(e.price), f4(e.tax)]
    elif t is ev.AllegianceChanged:
        body = [e.npc, _opt(e.oldFaction), e.newFaction, e.reason]
    elif t is ev.NpcAttacked:
        body = [e.attacker[1], e.target, f4(e.damage), e.location, e.attackerStance, e.defenderStance, e.duelOutcome]
    elif t is ev.PriceAdjusted:
        body = [e.merchant, e.item, f4(e.oldPrice), f4(e.newPrice), e.reason]
    elif t is ev.ShopInherited:
        body = [e.heir, e.predecessor, e.shopName]
    elif t is ev.FactionHostilityDeclared:
        body = [e.declaringFaction, e.targetFaction, e.reason]
    elif t is ev.PlayerMoved:
        body = [e.from_, e.to]
    elif t is ev.NpcRoutineCompleted:
        body = [e.npc, e.routineType, e.location, f4(e.deltaHealth), f4(e.deltaCoins), f4(e.deltaFood), f4(e.deltaFear)]
    elif t is ev.OpportunityDispatched:
        body = [e.opportunityId, e.opportunityType, e.title, e.location, f4(e.urgency), ",".join(a[1] for a in e.actors)]
    else:
        raise TypeError(t)
    head = ["E", str(e.id), str(e.time), t.__name__, "w=" + _ids(e.witnesses),
            "c=" + ("-" if e.causalEventId is None else str(e.causalEventId))]
    return "|".join(head + body)


def _edge_key(key: str) -> str:
    """The dump keeps the historical `a->b` edge form; the typed key lives in the world."""
    return re.sub(r"(?<=->)[a-z]+:", "", re.sub(r"^[a-z]+:", "", key))


def dump(world):
    out = [line(e) for e in world.events.all()]
    out.append(f"T|{world.time}")
    for n in sorted(world.npcs.values(), key=lambda n: n.id):
        es = n.emotionalState
        out.append("|".join([
            "N", n.id, "true" if n.is_alive else "false", n.identity.currentLocation,
            f4(n.resources.health), f4(n.resources.coins), f4(n.resources.food),
            f4(es.fear), f4(es.anger), f4(es.grief),
            "b=" + str(n.beliefs.size()), "m=" + str(n.memories.size),
            "f=" + ("-" if not n.factions else ",".join(sorted(n.factions)))]))
    for k, v in sorted(world.economy.itemPrices.items()):
        out.append(f"P|{k}|{f4(v)}")
    for k, v in sorted(world.economy.scarcityFactors.items()):
        out.append(f"S|{k}|{f4(v)}")
    out.append(f"V|{f4(world.economy.totalTradeVolume)}")
    out.append(f"TR|{f4(world.economy.treasury)}")
    for q in sorted(world.activeQuests, key=lambda q: q.id):
        out.append(f"Q|{q.id}|{q.status}|{f4(q.reward)}")
    out.append(f"PL|{f4(world.player.coins)}")
    for k, r in sorted(world.relationships.all_edges().items(), key=lambda kv: _edge_key(kv[0])):
        out.append(f"R|{_edge_key(k)}|{f4(r.trust)}|{f4(r.affection)}|{f4(r.respect)}|{f4(r.fear)}|{f4(r.resentment)}")
    return out
