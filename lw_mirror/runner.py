from . import dump as _dump
from . import engine
from . import state_dump as _state_dump
from .scenarios import famine_seed, feud_seed, turmoil_seed
from .world import market_village
from .worldtime import TICKS_PER_DAY


def _final_world(scenario, days):
    world = market_village()
    if scenario == "turmoil":
        world = turmoil_seed(world)
    elif scenario == "feud":
        world = feud_seed(world)
    elif scenario == "famine":
        world = famine_seed(world)
    elif scenario != "default":
        raise ValueError(f"unknown scenario '{scenario}' (default, turmoil, feud, famine)")
    return engine.advance(world, days * TICKS_PER_DAY)


def run(scenario, days):
    """The canonical event dump of a run (tests/golden/<scenario>-<days>d.txt.gz)."""
    return _dump.dump(_final_world(scenario, days))


def run_state(scenario, days):
    """The canonical state dump of a run (tests/golden/<scenario>-<days>d.state.txt.gz)."""
    return _state_dump.dump(_final_world(scenario, days))
