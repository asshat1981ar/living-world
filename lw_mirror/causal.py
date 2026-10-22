"""CausalLinks: most recent matching ledger event, or None."""
from . import beliefs as bf
from . import events as ev


def _last(ledger, pred, at=None):
    for e in reversed(ledger.all()):
        # A cause can only be in the past (G6).
        if at is not None and e.time > at:
            continue
        if pred(e):
            return e.id
    return None


def death_of(ledger, victim, at=None):
    return _last(ledger, lambda e: isinstance(e, ev.NpcKilled) and e.victim == victim, at)


def origin_of(ledger, p, at=None):
    if isinstance(p, (bf.EntityKilledNpc, bf.NpcDied)):
        return death_of(ledger, p.victim, at)
    if isinstance(p, bf.PlayerStole):
        return _last(ledger, lambda e: isinstance(e, ev.ItemStolen) and e.item == p.item, at)
    if isinstance(p, bf.EntityStoleItem):
        return _last(ledger, lambda e: isinstance(e, ev.ItemStolen) and e.item == p.item and e.thief == p.thief, at)
    if isinstance(p, bf.PriceChanged):
        return _last(ledger, lambda e: isinstance(e, ev.PriceAdjusted) and e.item == p.item, at)
    if isinstance(p, bf.ShopInheritedProp):
        return _last(ledger, lambda e: isinstance(e, ev.ShopInherited) and e.heir == p.heir, at)
    return None


def war_declaration_for(ledger, quest_id):
    def pred(e):
        return isinstance(e, ev.FactionHostilityDeclared) and (
            f"quest_war_{min(e.declaringFaction, e.targetFaction)}_{max(e.declaringFaction, e.targetFaction)}" == quest_id
        )
    return _last(ledger, pred)


def origin_of_quest(ledger, quest_id):
    if quest_id.startswith("quest_death_"):
        return death_of(ledger, quest_id[len("quest_death_"):])
    if quest_id.startswith("quest_war_"):
        return war_declaration_for(ledger, quest_id)
    return None
