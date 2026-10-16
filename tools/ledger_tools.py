"""EventLedger inspection over save files (SaveManager envelopes or raw WorldState JSON). Stdlib only.

    python3 tools/ledger_tools.py summary SAVE
    python3 tools/ledger_tools.py diff OLD_SAVE NEW_SAVE     # G5: history must be append-only
    python3 tools/ledger_tools.py trace SAVE EVENT_ID        # G6: same walk as EventLedger.traceCausalChain

Used by the lw-sim MCP server; runs on the phone or on awsbox (python3 -I tools/ledger_tools.py ...).
"""

from __future__ import annotations

import json
import sys
from collections import Counter
from pathlib import Path


def _id(v) -> int | None:
    """EventId is a value class: serialized as a bare number (or {"value": n} if ever boxed)."""
    if isinstance(v, dict):
        v = v.get("value")
    return int(v) if v is not None else None


def load_events(path: str | Path) -> list[dict]:
    doc = json.loads(Path(path).read_text())
    world = doc.get("worldState", doc)
    events = world.get("events", {})
    return list(events.get("eventsList", events) if isinstance(events, dict) else events)


def kind(event: dict) -> str:
    return str(event.get("type", "?")).rsplit(".", 1)[-1]


def summary(events: list[dict]) -> dict:
    return {
        "event_count": len(events),
        "first_id": _id(events[0]["id"]) if events else None,
        "last_id": _id(events[-1]["id"]) if events else None,
        "by_type": dict(sorted(Counter(kind(e) for e in events).items())),
        "duplicate_ids": sorted(i for i, n in Counter(_id(e["id"]) for e in events).items() if n > 1),
    }


def diff(old: list[dict], new: list[dict]) -> dict:
    """Append-only check: every event in `old` must appear unchanged in `new`."""
    new_by_id = {_id(e["id"]): e for e in new}
    old_ids = {_id(e["id"]) for e in old}
    removed = [_id(e["id"]) for e in old if _id(e["id"]) not in new_by_id]
    changed = [_id(e["id"]) for e in old if _id(e["id"]) in new_by_id and new_by_id[_id(e["id"])] != e]
    appended = [e for e in new if _id(e["id"]) not in old_ids]
    return {
        "g5_ok": not removed and not changed,
        "removed_ids": removed,
        "changed_ids": changed,
        "appended_count": len(appended),
        "appended_by_type": dict(sorted(Counter(kind(e) for e in appended).items())),
    }


def trace(events: list[dict], target: int) -> list[dict]:
    """Root-first causal chain ending at `target`, following causalEventId (cycle-safe), like traceCausalChain."""
    by_id = {_id(e["id"]): e for e in events}
    chain, seen, cur = [], set(), by_id.get(target)
    while cur is not None and _id(cur["id"]) not in seen:
        seen.add(_id(cur["id"]))
        chain.append(cur)
        parent = _id(cur.get("causalEventId"))
        cur = by_id.get(parent) if parent is not None else None
    return [{"id": _id(e["id"]), "type": kind(e), "tick": e.get("time"), "causalEventId": _id(e.get("causalEventId"))}
            for e in reversed(chain)]


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__)
        return 2
    cmd = argv[0]
    if cmd == "summary":
        out = summary(load_events(argv[1]))
    elif cmd == "diff" and len(argv) == 3:
        out = diff(load_events(argv[1]), load_events(argv[2]))
    elif cmd == "trace" and len(argv) == 3:
        out = {"chain": trace(load_events(argv[1]), int(argv[2]))}
    else:
        print(__doc__)
        return 2
    print(json.dumps(out, indent=2))
    return 1 if cmd == "diff" and not out["g5_ok"] else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
