"""EntityId is a (kind, identifier) tuple: ('npc'|'player'|'faction'|'location', id). Other ids are plain str/int."""


def npc_entity(npc_id):
    return ("npc", npc_id)


def faction_entity(fid):
    return ("faction", fid)


def location_entity(lid):
    return ("location", lid)


PLAYER = ("player", "player")


def identifier(entity):
    return entity[1]
