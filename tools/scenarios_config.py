"""The one strict reader of tests/golden/scenarios.json, shared by verify, the stop gate, regen and mirror_parity.

Everything that names a golden scenario reads it through `load`, so a value one tool would accept and another would
format differently (`"days": 10.0` -> `default-10.0d.txt.gz`) cannot exist: anything but a plain positive integer, a
non-empty list of distinct plain-word names, raises ValueError. tools/list_scenarios.sh applies the same rules in jq for CI.
"""
import json
import re
from pathlib import Path

NAME = re.compile(r"[a-z][a-z0-9_]*")  # fullmatch: a trailing newline does not match


def load(path):
    """(days, names) from the scenario file, or ValueError saying what is wrong with it."""
    try:
        data = json.loads(Path(path).read_text())
        days, names = data["days"], data["scenarios"]
    except (OSError, ValueError, KeyError, TypeError) as e:
        raise ValueError(f"{path} is unreadable or incomplete: {e}") from e
    if type(days) is not int or days <= 0:  # not a bool, not a float such as 10.0
        raise ValueError(f"days must be a positive whole number, got {days!r}")
    if not isinstance(names, list) or not names:
        raise ValueError("scenarios must be a non-empty list")
    for name in names:
        if not isinstance(name, str) or not NAME.fullmatch(name):
            raise ValueError(f"scenario names must be plain words (a-z, 0-9, _), got {name!r}")
    if len(set(names)) != len(names):
        raise ValueError("a scenario is listed twice")
    return days, list(names)
