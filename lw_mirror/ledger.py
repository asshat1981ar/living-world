from . import events as ev


class EventLedger:
    """Append-only ledger. The event list is shared between successive ledgers (a ledger sees only its first `_n`)."""

    __slots__ = ("_ev", "_idx", "_n", "_next")

    def __init__(self, events=None, idx=None, n=0, next_number=1):
        self._ev = events if events is not None else []
        self._idx = idx if idx is not None else {}
        self._n = n
        self._next = next_number

    @property
    def size(self):
        return self._n

    def next_id(self):
        return self._next

    def all(self):
        return self._ev if len(self._ev) == self._n else self._ev[: self._n]

    def find(self, event_id):
        pos = self._idx.get(event_id)
        if pos is None or pos >= self._n:
            return None
        return self._ev[pos]

    def record(self, event):
        if self.find(event.id) is not None:
            return self
        next_num = max(self._next, self._n + 1, event.id + 1)
        if len(self._ev) == self._n:
            events, idx = self._ev, self._idx
        else:
            events, idx = self._ev[: self._n], {k: v for k, v in self._idx.items() if v < self._n}
        idx[event.id] = len(events)
        events.append(event)
        return EventLedger(events, idx, self._n + 1, next_num)

    def of_type(self, cls):
        return [e for e in self.all() if isinstance(e, cls)]
