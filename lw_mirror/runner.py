from . import dump as _dump
from . import engine
from .scenarios import famine_seed, feud_seed, turmoil_seed
from .world import market_village
from .worldtime import TICKS_PER_DAY


def run(scenario, days):
    world = market_village()
    if scenario == "turmoil":
        world = turmoil_seed(world)
    elif scenario == "feud":
        world = feud_seed(world)
    elif scenario == "famine":
        world = famine_seed(world)
    elif scenario != "default":
        raise ValueError(f"unknown scenario '{scenario}' (default, turmoil, feud, famine)")
    world = engine.advance(world, days * TICKS_PER_DAY)
    return _dump.dump(world)
