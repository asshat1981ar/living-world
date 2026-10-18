from dataclasses import dataclass, replace


@dataclass(frozen=True)
class Leadership:
    leader: object = None
    successionLine: tuple = ()
    stability: float = 1.0


@dataclass(frozen=True)
class Faction:
    id: str
    name: str
    members: tuple = ()  # ordered set
    allies: tuple = ()
    enemies: tuple = ()
    leadership: Leadership = Leadership()

    def has_member(self, npc_id):
        return npc_id in self.members

    def add_member(self, npc_id):
        return self if npc_id in self.members else replace(self, members=self.members + (npc_id,))

    def remove_member(self, npc_id):
        members = tuple(m for m in self.members if m != npc_id)
        lead = self.leadership
        if lead.leader == npc_id:
            nxt = next((x for x in lead.successionLine if x != npc_id), None)
            lead = replace(lead, leader=nxt, successionLine=tuple(x for x in lead.successionLine if x != npc_id))
        else:
            lead = replace(lead, successionLine=tuple(x for x in lead.successionLine if x != npc_id))
        return replace(self, members=members, leadership=lead)

    def declare_hostility(self, target):
        enemies = self.enemies if target in self.enemies else self.enemies + (target,)
        return replace(self, enemies=enemies, allies=tuple(a for a in self.allies if a != target))
