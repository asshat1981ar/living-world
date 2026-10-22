from dataclasses import dataclass, field, replace
from typing import Any, Optional

from . import events as ev
from . import beliefs as bf
from .f32 import f32, coerce_in, coerce_at_least
from .factions import Faction, Leadership
from .ids import PLAYER
from .ledger import EventLedger
from .npc import Npc, Identity, Goal, ResourceState
from .relationships import RelationshipGraph
from . import worldtime

F = f32


@dataclass(frozen=True)
class EconomyState:
    itemPrices: dict = field(default_factory=dict)
    scarcityFactors: dict = field(default_factory=dict)
    totalTradeVolume: float = 0.0
    treasury: float = 200.0  # EconomyState.STARTING_TREASURY

    def get_price(self, item, base_price=10.0):
        cur = self.itemPrices.get(item, base_price)
        scarcity = self.scarcityFactors.get(item, 1.0)
        return coerce_at_least(F(cur * scarcity), 1.0)

    def with_price(self, item, price):
        p = dict(self.itemPrices)
        p[item] = price
        return replace(self, itemPrices=p)


@dataclass(frozen=True)
class QuestOpportunity:
    id: str
    title: str
    description: str
    issuer: tuple
    target: Optional[tuple] = None
    reward: float = 50.0
    status: str = "AVAILABLE"


@dataclass(frozen=True)
class WorldProblem:
    id: str
    category: str
    severity: float
    location: str
    summary: str


@dataclass(frozen=True)
class PlayerState:
    id: tuple
    currentLocation: str
    coins: float = 100.0
    inventory: tuple = ()
    historicalActions: tuple = ()


@dataclass(frozen=True)
class WorldState:
    seed: int
    time: int
    npcs: dict
    factions: dict
    locations: dict
    relationships: RelationshipGraph
    economy: EconomyState
    events: EventLedger
    activeQuests: tuple
    player: PlayerState

    def npc(self, npc_id):
        n = self.npcs.get(npc_id)
        if n is None:
            raise ValueError(f"Unknown NPC: {npc_id}")
        return n

    def is_dead(self, npc_id):
        c = self.npcs.get(npc_id)
        if c is None:
            return True
        return not c.is_alive


def float_to_string(v):
    """Used only in free-text descriptions that never reach the canonical dump."""
    return repr(v)


def derive_active_problems(state):
    problems = []
    # An investigation that is done (or failed) is no longer an open problem.
    settled = {q.id[len("quest_death_"):] for q in state.activeQuests
               if q.id.startswith("quest_death_") and q.status in ("COMPLETED", "FAILED")}
    counted = set()
    for death in state.events.of_type(ev.NpcKilled):
        if death.victim in counted:
            continue  # one problem per victim
        counted.add(death.victim)
        if death.victim in settled:
            continue
        loc = death.location if death.location is not None else "village_square"
        problems.append(WorldProblem(f"problem_death_{death.victim}", "HOMICIDE", F(0.90), loc,
                                     f"Homicide of {death.victim} at {loc}"))
    for fid, faction in sorted(state.factions.items(), key=lambda kv: kv[0]):
        for enemy in sorted(faction.enemies):
            if fid < enemy:
                problems.append(WorldProblem(f"problem_war_{fid}_{enemy}", "FACTION_WAR", F(0.85), "village_square",
                                             f"Open hostility between {faction.name} and {enemy}"))
    for item_key, scarcity in sorted(state.economy.scarcityFactors.items(), key=lambda kv: kv[0]):
        if scarcity > F(1.25):
            sev = coerce_in(F(F(scarcity - 1.0) / F(1.5)), F(0.3), F(0.95))
            problems.append(WorldProblem(f"problem_scarcity_{item_key}", "SCARCITY", sev, "market",
                                         f"Severe scarcity of {item_key} (multiplier: {float_to_string(scarcity)})"))
    return sorted(problems, key=lambda p: p.severity, reverse=True)  # stable descending


def _investigator_for(state, victim):
    """A living NPC that knows about the death: the guard captain if he saw it, else the first who heard."""
    knowers = []
    for npc in sorted(state.npcs.values(), key=lambda n: n.id):
        if not npc.is_alive:
            continue
        for belief in npc.beliefs.all():
            p = belief.proposition
            if (isinstance(p, bf.EntityKilledNpc) and p.victim == victim) or \
               (isinstance(p, bf.NpcDied) and p.victim == victim):
                knowers.append(npc.id)
                break
    if "guard_captain" in knowers:
        return "guard_captain"
    return knowers[0] if knowers else None


def generate_opportunities(state):
    existing = {q.id for q in state.activeQuests}
    generated = []
    for problem in derive_active_problems(state):
        quest_id = "quest_" + problem.id[len("problem_"):] if problem.id.startswith("problem_") else "quest_" + problem.id
        if quest_id in existing:
            continue
        if problem.category == "HOMICIDE":
            victim = problem.id[len("problem_death_"):]
            # Only someone who knows about the death can ask for an investigation (G2).
            issuer = _investigator_for(state, victim)
            if issuer is None:
                continue
            generated.append(QuestOpportunity(
                quest_id, f"Investigate the Murder of {victim}",
                f"{problem.summary}. Bring justice or uncover the killer's motive.",
                ("npc", issuer), ("location", problem.location), 75.0, "AVAILABLE"))
        elif problem.category == "FACTION_WAR":
            generated.append(QuestOpportunity(
                quest_id, "Broker Ceasefire in Oakhaven",
                f"{problem.summary}. Restore order at the village square.",
                ("npc", "elder_rowan"), ("location", problem.location), 100.0, "AVAILABLE"))
        elif problem.category == "SCARCITY":
            item_key = problem.id[len("problem_scarcity_"):]
            generated.append(QuestOpportunity(
                quest_id, f"Relieve {item_key} Shortage at Market",
                f"{problem.summary}. Deliver trade supplies to stabilize prices.",
                ("npc", "apprentice"), ("location", "market"), 45.0, "AVAILABLE"))
    return generated


def market_village():
    square, market, tavern, mine, shrine = "village_square", "market", "tavern", "mine", "shrine"
    iron, verdant = "iron_guild", "verdant_order"
    defs = [
        ("merchant", "Alden the Merchant", "Merchant"),
        ("apprentice", "Finn the Apprentice", "Shop Apprentice"),
        ("guard_captain", "Captain Vane", "Guard Captain"),
        ("guildmaster_titus", "Titus Ironforge", "Guildmaster"),
        ("elder_rowan", "Elder Rowan", "Village Elder"),
        ("blacksmith_krag", "Krag the Blacksmith", "Blacksmith"),
        ("innkeeper_mara", "Mara of the Hearth", "Innkeeper"),
        ("herbalist_lyra", "Lyra Greenleaf", "Herbalist"),
        ("hunter_bram", "Bram the Scout", "Hunter"),
        ("farmer_giles", "Giles Oatfield", "Farmer"),
        ("farmer_elena", "Elena Oatfield", "Farmer"),
        ("carpenter_jorin", "Jorin Woodshaper", "Carpenter"),
        ("miner_derek", "Derek Deepdelve", "Miner"),
        ("weaver_sela", "Sela Threadspinner", "Weaver"),
        ("priest_orion", "Father Orion", "Priest"),
        ("watchman_cole", "Cole Nightwatch", "Watchman"),
        ("baker_milo", "Milo the Baker", "Baker"),
        ("brewer_oscar", "Oscar Barleybrew", "Brewer"),
        ("scholar_erik", "Erik Chronicler", "Scholar"),
        ("tanner_garth", "Garth Hidesman", "Tanner"),
    ]
    npcs = {}
    for id_, name, occ in defs:
        if id_ in ("guildmaster_titus", "blacksmith_krag", "miner_derek", "merchant"):
            fs = (iron,)
        elif id_ in ("elder_rowan", "herbalist_lyra", "farmer_giles", "farmer_elena"):
            fs = (verdant,)
        else:
            fs = ()
        traits = {
            "merchant": ("GREEDY", "OPPORTUNISTIC"),
            "apprentice": ("AMBITIOUS",),
            "guard_captain": ("BRAVE", "LOYAL"),
            "miner_derek": ("VENGEFUL",),
            "elder_rowan": ("COMPASSIONATE",),
            "herbalist_lyra": ("COMPASSIONATE",),
        }.get(id_, ("HONEST",))
        npcs[id_] = Npc(
            id=id_,
            identity=Identity(name, occ, square, square, True),
            traits=traits,
            goals=(Goal(f"survive_{id_}", "Provide for self and family", F(0.8), F(0.5)),),
            factions=fs,
            resources=ResourceState(coins=50.0, food=15.0, health=100.0,
                                    items=(("bread",) * 3 + ("goods",) * 6) if id_ == "merchant" else ()),
        )
    iron_g = Faction(iron, "The Iron Guild",
                     members=("guildmaster_titus", "blacksmith_krag", "miner_derek", "merchant"),
                     leadership=Leadership("guildmaster_titus", ("blacksmith_krag",)))
    verdant_o = Faction(verdant, "The Verdant Order",
                        members=("elder_rowan", "herbalist_lyra", "farmer_giles", "farmer_elena"),
                        leadership=Leadership("elder_rowan", ("herbalist_lyra",)))
    rel = RelationshipGraph().modify(("npc", "apprentice"), ("npc", "merchant"), F(0.7), F(0.6), F(0.8))
    return WorldState(
        seed=10001, time=0, npcs=npcs,
        factions={iron: iron_g, verdant: verdant_o},
        locations={square: None, market: None, tavern: None, mine: None, shrine: None},
        relationships=rel,
        economy=EconomyState(itemPrices={"bread": 5.0, "iron_sword": 50.0, "herb_bundle": 8.0, "iron_ingot": 20.0, "goods": 15.0}),
        events=EventLedger(),
        activeQuests=(),
        player=PlayerState(PLAYER, square, 100.0, ("iron_sword",)),
    )
