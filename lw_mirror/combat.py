"""TacticalCombatResolver."""
from collections import namedtuple

from .events import DUEL
from .f32 import f32, coerce_at_least

F = f32
WOUNDED = 35.0
DuelResolution = namedtuple("DuelResolution", "attackerStance defenderStance outcome baseDamage finalDamage attackerWounded defenderWounded")


def stable_string_hash(value):
    h = 0
    for ch in value:
        h = (31 * h + ord(ch)) & 0xFFFFFFFF
    if h >= 0x80000000:
        h -= 0x100000000
    return h  # Int overflow semantics; toLong() keeps the sign


def select_stance(npc, tick, is_attacker):
    if npc.resources.health < WOUNDED and not npc.has_trait("VENGEFUL"):
        return "WARD"
    if npc.has_trait("OPPORTUNISTIC") or npc.has_trait("AMBITIOUS"):
        return "STRIKE" if tick % 3 == 0 else "FEINT"
    if npc.has_trait("BRAVE") or npc.has_trait("VENGEFUL"):
        return "WARD" if (not is_attacker and tick % 4 == 0) else "STRIKE"
    if npc.has_trait("COWARDLY") or npc.has_trait("LOYAL"):
        return "WARD"
    h = ((stable_string_hash(npc.id) & 0x7FFFFFFF) + tick) % 3
    return "STRIKE" if h == 0 else ("WARD" if h == 1 else "FEINT")


def evaluate_matrix(attacker, defender):
    if attacker == defender:
        return "STANDARD_EXCHANGE"
    if attacker == "STRIKE":
        return "COUNTER_STRIKE" if defender == "FEINT" else "PARRIED"
    if attacker == "FEINT":
        return "GUARD_BREAK" if defender == "WARD" else "PARRIED"
    return "PARRIED" if defender == "STRIKE" else "GUARD_BREAK"


def resolve_exchange(attacker, defender, tick, override_attacker=None, override_defender=None):
    atk = override_attacker or select_stance(attacker, tick, True)
    dfn = override_defender or select_stance(defender, tick, False)
    outcome = evaluate_matrix(atk, dfn)
    if attacker.has_trait("BRAVE"):
        trait_base = 35.0
    elif attacker.has_trait("VENGEFUL"):
        trait_base = 30.0
    elif attacker.has_trait("COWARDLY"):
        trait_base = 18.0
    else:
        trait_base = 25.0
    weapon = 10.0 if any("sword" in i for i in attacker.resources.items) else 0.0
    base = F(trait_base + weapon)
    final = coerce_at_least(F(base * DUEL[outcome][0]), 1.0)
    post = coerce_at_least(F(defender.resources.health - final), 0.0)
    return DuelResolution(atk, dfn, outcome, base, final, attacker.resources.health < WOUNDED, post < WOUNDED)
