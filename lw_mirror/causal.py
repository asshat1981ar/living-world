"""CausalLinks: most recent matching ledger event, or None."""
from . import beliefs as bf
from . import events as ev


def _last(ledger, pred):
    for e in reversed(ledger.all()):
        if pred(e):
            return e.id
    return None


def death_of(ledger, victim):
    return _last(ledger, lambda e: isinstance(e, ev.NpcKilled) and e.victim == victim)


def origin_of(ledger, p):
    if isinstance(p, (bf.EntityKilledNpc, bf.NpcDied)):
        return death_of(ledger, p.victim)
    if isinstance(p, bf.PlayerStole):
        return _last(ledger, lambda e: isinstance(e, ev.ItemStolen) and e.item == p.item)
    if isinstance(p, bf.EntityStoleItem):
        return _last(ledger, lambda e: isinstance(e, ev.ItemStolen) and e.item == p.item and e.thief == p.thief)
    if isinstance(p, bf.PriceChanged):
        return _last(ledger, lambda e: isinstance(e, ev.PriceAdjusted) and e.item == p.item)
    if isinstance(p, bf.ShopInheritedProp):
        return _last(ledger, lambda e: isinstance(e, ev.ShopInherited) and e.heir == p.heir)
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
