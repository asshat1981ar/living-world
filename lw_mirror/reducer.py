from dataclasses import replace

from . import beliefs as bf
from . import events as ev
from . import memory as mem
from .f32 import f32, coerce_in, coerce_at_least
from .relationships import NEUTRAL
from .world import QuestOpportunity

F = f32


def _belief(prop, conf, source, rel, at):
    return bf.Belief(prop, conf, source, rel, at)


def apply(state, event):
    """DefaultWorldReducer.apply"""
    # Applying an event that is already in the ledger changes nothing, and an event that cannot happen (the dead do
    # not trade, a seller without stock cannot sell) is not recorded at all.
    if state.events.find(event.id) is not None:
        return state
    if not _is_applicable(state, event):
        return state
    ledger = state.events.record(event)
    next_time = event.time if event.time > state.time else state.time
    base = replace(state, time=next_time, events=ledger)
    handler = _HANDLERS[type(event)]
    return handler(base, event)


def _npc_exists(state, npc_id):
    npc = state.npcs.get(npc_id)
    return npc is not None


def _alive(state, npc_id):
    npc = state.npcs.get(npc_id)
    return npc is not None and npc.is_alive


def _entity_exists(state, entity):
    if entity is None:
        return False
    if entity[0] == "npc":
        return _npc_exists(state, entity[1])
    if entity[0] == "player":
        return entity[1] == state.player.id[1]
    return True


def _entity_alive(state, entity):
    if entity is None:
        return False
    if entity[0] == "npc":
        return _alive(state, entity[1])
    return True


def _holds(state, entity, item):
    if entity is None:
        return False
    if entity[0] == "npc":
        npc = state.npcs.get(entity[1])
        return npc is not None and item in npc.resources.items
    if entity[0] == "player":
        return item in state.player.inventory
    return False


def _is_applicable(state, event):
    """The mirror of EventValidator: an event that cannot happen is not recorded at all."""
    if isinstance(event, ev.NpcKilled):
        if not _npc_exists(state, event.victim):
            return False
        if event.killer is not None and event.killer[0] == "npc" and not _npc_exists(state, event.killer[1]):
            return False
        return True
    if isinstance(event, ev.ItemStolen):
        if not _entity_exists(state, event.thief):
            return False
        if event.owner is None or not _entity_exists(state, event.owner):
            return False
        return _holds(state, event.owner, event.item)
    if isinstance(event, ev.TradeCompleted):
        return _trade_possible(state, event)
    if isinstance(event, ev.RationPurchased):
        return (_npc_exists(state, event.buyer) and _npc_exists(state, event.seller)
                and _alive(state, event.buyer) and _alive(state, event.seller))
    if isinstance(event, ev.NpcRoutineCompleted):
        return _npc_exists(state, event.npc) and _alive(state, event.npc)
    if isinstance(event, ev.AllegianceChanged):
        if not (_npc_exists(state, event.npc) and _alive(state, event.npc)):
            return False
        return event.newFaction in state.factions
    if isinstance(event, ev.ShopInherited):
        if not (_npc_exists(state, event.heir) and _alive(state, event.heir)):
            return False
        return _npc_exists(state, event.predecessor)
    if isinstance(event, ev.RumorTransmitted):
        if not (_npc_exists(state, event.source) and _npc_exists(state, event.target)):
            return False
        return _alive(state, event.target)
    if isinstance(event, ev.NpcAttacked):
        if not (_npc_exists(state, event.target) and _alive(state, event.target)):
            return False
        if event.attacker[0] == "npc":
            return _npc_exists(state, event.attacker[1]) and _alive(state, event.attacker[1])
        return True
    if isinstance(event, ev.RelationshipShifted):
        for entity in (event.source, event.target):
            if entity[0] == "npc" and not _npc_exists(state, entity[1]):
                return False
        return True
    if isinstance(event, ev.EntitiesMet):
        for entity in (event.entityA, event.entityB):
            if entity[0] == "npc" and not _npc_exists(state, entity[1]):
                return False
        return True
    if isinstance(event, ev.PlayerAttacked):
        return _npc_exists(state, event.attacker)
    if isinstance(event, ev.PlayerMoved):
        return event.to in state.locations
    if isinstance(event, ev.PriceAdjusted):
        return _npc_exists(state, event.merchant)
    if isinstance(event, ev.OpportunityDispatched):
        return all(_npc_exists(state, a[1]) for a in event.actors if a[0] == "npc")
    if isinstance(event, ev.FactionHostilityDeclared):
        return event.declaringFaction in state.factions and event.targetFaction in state.factions
    return True


def _is_player(state, entity):
    """The player is the player: an NPC that happens to share its name is not."""
    return entity is not None and entity[0] == "player" and entity[1] == state.player.id[1]


def _trade_possible(state, event):
    """Two parties that exist, a living seller that holds the item and a buyer that can pay."""
    if event.price != event.price or event.price <= 0.0:
        return False
    buyer = state.npcs.get(event.buyer[1]) if event.buyer[0] == "npc" else None
    seller = state.npcs.get(event.seller[1]) if event.seller[0] == "npc" else None
    buyer_is_player = _is_player(state, event.buyer)
    seller_is_player = _is_player(state, event.seller)
    if buyer is None and not buyer_is_player:
        return False
    if seller is None and not seller_is_player:
        return False
    if buyer is not None and not buyer.is_alive:
        return False
    if seller is not None and not seller.is_alive:
        return False
    seller_holds = (event.item in seller.resources.items) if seller is not None else (
        seller_is_player and event.item in state.player.inventory)
    if not seller_holds:
        return False
    buyer_can_pay = (buyer.resources.coins >= event.price) if buyer is not None else (
        buyer_is_player and state.player.coins >= event.price)
    return buyer_can_pay


def _modify(rels, frm, to, dt=0.0, da=0.0, dr=0.0, dfear=0.0, dres=0.0, event_id=None):
    return rels.modify(frm, to, dt, da, dr, dfear, dres, event_id)


def _with(d, key, value):
    nd = dict(d)
    nd[key] = value
    return nd


def _witness_of_death(witness, victim_id, event, cares):
    memories = witness.memories.add(mem.Episodic(event.id, F(1.0), event.time))
    beliefs = witness.beliefs
    if event.killer is None:
        beliefs = beliefs.add_or_update(_belief(bf.NpcDied(victim_id), 1.0, bf.DirectObservation(), 1.0, event.time))
    else:
        beliefs = beliefs.add_or_update(_belief(bf.EntityKilledNpc(event.killer, victim_id), 1.0, bf.DirectObservation(), 1.0, event.time))
        memories = memories.add(mem.RelationshipMemory(event.killer, "ASSAULT", F(0.95), event.time))
    return replace(
        witness, memories=memories, beliefs=beliefs,
        emotionalState=witness.emotionalState.shifted(
            deltaFear=F(0.35),
            deltaGrief=F(0.6) if cares else F(0.15),
            deltaAnger=F(0.45) if cares else F(0.1)))


def _hear_of_death(listener, victim_id, event, source, cares):
    prop = bf.EntityKilledNpc(event.killer, victim_id) if event.killer is not None else bf.NpcDied(victim_id)
    return replace(
        listener,
        beliefs=listener.beliefs.add_or_update(_belief(prop, F(0.85), source, F(0.8), event.time)),
        memories=listener.memories.add(mem.Episodic(event.id, F(0.7), event.time)),
        emotionalState=listener.emotionalState.shifted(deltaGrief=F(0.4) if cares else F(0.1)))


def _npc_death(state, event):
    victim = state.npcs.get(event.victim)
    if victim is None:
        return state
    updated_victim = replace(victim, identity=replace(victim.identity, isAlive=False),
                             resources=replace(victim.resources, health=0.0))
    next_npcs = _with(state.npcs, victim.id, updated_victim)
    rels = state.relationships
    next_factions = {k: (f.remove_member(victim.id) if f.has_member(victim.id) else f) for k, f in state.factions.items()}

    for wid in event.witnesses:
        witness = next_npcs.get(wid)
        if witness is None:
            continue
        affection = rels.get(("npc", wid), ("npc", victim.id)).overall_sentiment
        cares = affection > F(0.2) or witness.reputation_toward(("npc", victim.id), rels) > F(0.2)
        next_npcs = _with(next_npcs, wid, _witness_of_death(witness, victim.id, event, cares))
        if event.killer is not None:
            rels = _modify(rels, ("npc", wid), event.killer,
                           dt=F(-0.8) if cares else F(-0.4),
                           da=F(-0.8) if cares else F(-0.3),
                           dr=F(-0.3), dfear=F(0.45),
                           dres=F(0.9) if cares else F(0.4),
                           event_id=event.id)

    first_witness = event.witnesses[0] if event.witnesses else None
    for listener in sorted(next_npcs.values(), key=lambda n: n.id):
        if (not listener.is_alive) or listener.id == victim.id or listener.id in event.witnesses:
            continue
        sentiment = rels.get(("npc", listener.id), ("npc", victim.id)).overall_sentiment
        shared = [f for f in victim.factions if f in listener.factions]
        shared_faction = min(shared) if shared else None
        if sentiment <= F(0.2) and shared_faction is None:
            continue
        if first_witness is not None:
            source = bf.Hearsay(first_witness)
        elif shared_faction is not None:
            source = bf.FactionReport(shared_faction)
        else:
            source = bf.Observation(event.id)
        next_npcs = _with(next_npcs, listener.id, _hear_of_death(listener, victim.id, event, source, sentiment > F(0.2)))

    victim_entity = ("npc", victim.id)
    quests = tuple(
        replace(q, status="FAILED") if (q.issuer == victim_entity and q.status in ("AVAILABLE", "ACTIVE")) else q
        for q in state.activeQuests)
    return replace(state, npcs=next_npcs, factions=next_factions, relationships=rels, activeQuests=quests)


def _theft(state, event):
    next_npcs = state.npcs
    rels = state.relationships
    if event.thief[0] == "player":
        prop = bf.PlayerStole(event.item)
    else:
        prop = bf.EntityStoleItem(event.thief, event.item)
    for wid in event.witnesses:
        witness = next_npcs.get(wid)
        if witness is None:
            continue
        belief = _belief(prop, F(0.95), bf.DirectObservation(), F(0.95), event.time)
        opp = _belief(bf.HasTrait(event.thief, "OPPORTUNISTIC"), F(0.9), bf.Observation(event.id), F(0.95), event.time)
        memories = (witness.memories
                    .add(mem.Episodic(event.id, F(0.8), event.time))
                    .add(mem.RelationshipMemory(event.thief, "THEFT", F(0.85), event.time)))
        updated = replace(
            witness,
            beliefs=witness.beliefs.add_or_update(belief).add_or_update(opp),
            memories=memories,
            emotionalState=witness.emotionalState.shifted(deltaAnger=F(0.25), deltaSuspicion=F(0.3)))
        next_npcs = _with(next_npcs, wid, updated)
        rels = _modify(rels, ("npc", wid), event.thief, dt=F(-0.5), da=F(-0.3), dres=F(0.6), event_id=event.id)

    owner_id = None
    if event.owner is not None:
        owner_id = event.owner[1] if event.owner[0] == "npc" else event.owner[1]
    owner = next_npcs.get(owner_id) if owner_id is not None else None
    if owner is not None:
        items = list(owner.resources.items)
        if event.item in items:
            items.remove(event.item)
        was_witness = owner.id in event.witnesses
        updated_owner = replace(
            owner, resources=replace(owner.resources, items=tuple(items)),
            emotionalState=owner.emotionalState if was_witness else owner.emotionalState.shifted(deltaSuspicion=F(0.2)))
        next_npcs = _with(next_npcs, owner.id, updated_owner)
    player = state.player
    if event.owner is not None and _is_player(state, event.owner):
        player = replace(player, inventory=_remove_first(player.inventory, event.item))
    # The stolen item moves to the thief: a theft that moves nothing is not a theft.
    if event.thief[0] == "npc":
        thief = next_npcs.get(event.thief[1])
        if thief is not None:
            next_npcs = _with(next_npcs, thief.id, replace(thief, resources=replace(
                thief.resources, items=thief.resources.items + (event.item,))))
    else:
        player = replace(player, inventory=player.inventory + (event.item,))
    return replace(state, npcs=next_npcs, relationships=rels, player=player)


def _rumor(state, event):
    target = state.npcs.get(event.target)
    if target is None:
        return state
    tb = event.transmittedBelief
    updated_target = replace(
        target,
        beliefs=target.beliefs.add_or_update(tb),
        memories=(target.memories.add(mem.Episodic(event.id, F(0.5), event.time))
                  .add(mem.Semantic(tb.proposition, tb.confidence, event.time))))
    src = state.npcs.get(event.source)
    source = None
    if src is not None:
        source = replace(src, memories=src.memories.add(mem.Told(event.target, tb.proposition, F(0.3), event.time)))
    with_target = _with(state.npcs, target.id, updated_target)
    return replace(state, npcs=_with(with_target, source.id, source) if source is not None else with_target)


def _rel_shift(state, event):
    rels = _modify(state.relationships, event.source, event.target, event.deltaTrust, event.deltaAffection,
                   event.deltaRespect, event.deltaFear, event.deltaResentment, event.id)
    src_id = event.source[1]
    src = state.npcs.get(src_id)
    if src is not None:
        exp = "COOPERATION" if (event.deltaTrust >= 0.0 and event.deltaResentment <= 0.0) else "BETRAYAL"
        m = mem.RelationshipMemory(event.target, exp, F(0.6), event.time)
        final = _with(state.npcs, src_id, replace(src, memories=src.memories.add(m)))
    else:
        final = state.npcs
    return replace(state, relationships=rels, npcs=final)


def _allegiance(state, event):
    npc = state.npcs.get(event.npc)
    if npc is None:
        return state
    fs = tuple(f for f in npc.factions if f != event.oldFaction if event.oldFaction is not None) \
        if event.oldFaction is not None else npc.factions
    if event.newFaction not in fs:
        fs = fs + (event.newFaction,)
    updated = replace(npc, factions=fs)
    nf = state.factions
    if event.oldFaction is not None:
        old = nf.get(event.oldFaction)
        if old is not None:
            nf = _with(nf, old.id, old.remove_member(npc.id))
    new = nf.get(event.newFaction)
    if new is not None:
        nf = _with(nf, new.id, new.add_member(npc.id))
    return replace(state, npcs=_with(state.npcs, npc.id, updated), factions=nf)


def _remove_first(items, x):
    items = list(items)
    if x in items:
        items.remove(x)
    return tuple(items)


def _remove_all(items, gone):
    """Remove every occurrence of the items in `gone` (the shop's stock)."""
    out = list(items)
    for g in gone:
        while g in out:
            out.remove(g)
    return tuple(out)


def _trade(state, event):
    next_npcs = state.npcs
    player = state.player
    buyer = next_npcs.get(event.buyer[1]) if event.buyer[0] == "npc" else None
    seller = next_npcs.get(event.seller[1]) if event.seller[0] == "npc" else None
    # The pre-check already proved that both parties exist, that the seller holds the item and that the buyer can
    # pay, so the price moves in full.
    paid = event.price
    if buyer is not None:
        next_npcs = _with(next_npcs, buyer.id, replace(buyer, resources=replace(
            buyer.resources, coins=F(buyer.resources.coins - paid), items=buyer.resources.items + (event.item,))))
    elif _is_player(state, event.buyer):
        player = replace(player, coins=F(player.coins - paid), inventory=player.inventory + (event.item,),
                         historicalActions=player.historicalActions + (event.id,))
    if seller is not None:
        next_npcs = _with(next_npcs, seller.id, replace(seller, resources=replace(
            seller.resources, coins=F(seller.resources.coins + paid), items=_remove_first(seller.resources.items, event.item))))
    elif _is_player(state, event.seller):
        player = replace(player, coins=F(player.coins + paid), inventory=_remove_first(player.inventory, event.item),
                         historicalActions=player.historicalActions + (event.id,))

    player_involved = _is_player(state, event.buyer) or _is_player(state, event.seller)
    bonus = 0.0
    quests = []
    for q in state.activeQuests:
        if player_involved and q.status != "COMPLETED" and q.status != "FAILED" and q.id == f"quest_scarcity_{event.item}":
            bonus = F(bonus + q.reward)
            quests.append(replace(q, status="COMPLETED"))
        else:
            quests.append(q)
    reward = max(min(bonus, state.economy.treasury), 0.0)  # paid from the treasury
    if reward > 0.0:
        player = replace(player, coins=F(player.coins + reward))
    economy = replace(state.economy, totalTradeVolume=F(state.economy.totalTradeVolume + paid),
                      treasury=F(state.economy.treasury - reward))
    return replace(state, npcs=next_npcs, player=player, economy=economy, activeQuests=tuple(quests))


def _price(state, event):
    price = coerce_at_least(event.newPrice, F(0.5))
    return replace(state, economy=state.economy.with_price(event.item, price))


def _shop_inherited(state, event):
    heir = state.npcs.get(event.heir)
    if heir is None:
        return state
    belief = _belief(bf.ShopInheritedProp(event.heir, event.shopName), 1.0, bf.DirectObservation(), 1.0, event.time)
    grant = max(min(25.0, state.economy.treasury), 0.0)  # start-up purse from the treasury
    # The shop's stock goes with the shop: the new shopkeeper sells what the old one left behind.
    predecessor = state.npcs.get(event.predecessor)
    inherited = predecessor.resources.items if predecessor is not None else ()
    npcs = state.npcs
    if predecessor is not None and inherited:
        npcs = _with(npcs, predecessor.id, replace(
            predecessor, resources=replace(predecessor.resources, items=_remove_all(predecessor.resources.items, inherited))))
    updated = replace(
        heir, identity=replace(heir.identity, occupation=f"Shopkeeper ({event.shopName})"),
        resources=replace(heir.resources, coins=F(heir.resources.coins + grant), items=heir.resources.items + inherited),
        beliefs=heir.beliefs.add_or_update(belief))
    return replace(state, npcs=_with(npcs, heir.id, updated),
                   economy=replace(state.economy, treasury=F(state.economy.treasury - grant)))


def _hostility(state, event):
    decl = state.factions.get(event.declaringFaction)
    if decl is None:
        return state
    target = state.factions.get(event.targetFaction)
    if target is None:
        return state
    nf = _with(state.factions, decl.id, decl.declare_hostility(event.targetFaction))
    nf = _with(nf, target.id, target.declare_hostility(event.declaringFaction))
    return replace(state, factions=nf)


def _met(state, event):
    cur = state.relationships.get(event.entityA, event.entityB)
    rels = state.relationships
    if cur == NEUTRAL:
        rels = rels.set(event.entityA, event.entityB, NEUTRAL)
    return replace(state, relationships=rels)


def _attacked(state, event):
    target = state.npcs.get(event.target)
    if target is None:
        return state
    dmg = coerce_at_least(event.damage, 0.0)
    remaining = coerce_at_least(F(target.resources.health - dmg), 0.0)
    updated = replace(
        target, resources=replace(target.resources, health=remaining),
        emotionalState=target.emotionalState.shifted(deltaFear=F(0.2), deltaAnger=F(0.3)),
        memories=target.memories.add(mem.RelationshipMemory(event.attacker, "ASSAULT", F(0.9), event.time)))
    rels = _modify(state.relationships, ("npc", target.id), event.attacker,
                   dt=F(-0.4), da=F(-0.4), dfear=F(0.25), dres=F(0.5), event_id=event.id)
    return replace(state, npcs=_with(state.npcs, target.id, updated), relationships=rels)


def _routine(state, event):
    npc = state.npcs.get(event.npc)
    if npc is None:
        return state
    health = coerce_in(F(npc.resources.health + event.deltaHealth), 0.0, 100.0)
    # coins are a transfer with the treasury, limited by what the paying side has
    if event.deltaCoins >= 0.0:
        shift = min(event.deltaCoins, coerce_at_least(state.economy.treasury, 0.0))
    else:
        shift = -min(-event.deltaCoins, npc.resources.coins)
    food = coerce_in(F(npc.resources.food + event.deltaFood), F(0.5), 100.0)
    updated = replace(npc, resources=replace(npc.resources, health=health, coins=F(npc.resources.coins + shift), food=food),
                      emotionalState=npc.emotionalState.shifted(deltaFear=event.deltaFear))
    next_npcs = _with(state.npcs, npc.id, updated)
    economy = replace(state.economy, treasury=F(state.economy.treasury - shift))
    return replace(state, npcs=next_npcs, economy=_with_bread_scarcity(economy, next_npcs))


def _ration(state, event):
    buyer = state.npcs.get(event.buyer)
    seller = state.npcs.get(event.seller)
    if buyer is None or seller is None:
        return state
    paid = coerce_at_least(min(event.price, buyer.resources.coins), 0.0)
    tax = coerce_at_least(min(event.tax, paid), 0.0)
    food = coerce_at_least(min(event.food, F(seller.resources.food - 0.5)), 0.0)
    nxt = _with(state.npcs, buyer.id, replace(buyer, resources=replace(
        buyer.resources, coins=F(buyer.resources.coins - paid), food=min(F(buyer.resources.food + food), 100.0))))
    nxt = _with(nxt, seller.id, replace(seller, resources=replace(
        seller.resources, coins=F(seller.resources.coins + F(paid - tax)), food=F(seller.resources.food - food))))
    economy = replace(state.economy, treasury=F(state.economy.treasury + tax))
    return replace(state, npcs=nxt, economy=_with_bread_scarcity(economy, nxt))


def _with_bread_scarcity(economy, npcs):
    living = [n for n in npcs.values() if n.is_alive]
    if living:
        total = 0.0
        for n in living:  # sumOf { it.toDouble() }: sequential double sum (not math.fsum / sum())
            total += n.resources.food
        avg = F(F(total) / F(len(living)))
    else:
        avg = 15.0
    bread = coerce_in(F(15.0 / coerce_at_least(avg, 3.0)), F(0.75), F(2.5))
    return replace(economy, scarcityFactors=_with(economy.scarcityFactors, "bread", bread))


def _opportunity(state, event):
    if any(q.id == event.opportunityId for q in state.activeQuests):
        return state
    issuer = event.actors[0] if event.actors else ("npc", "elder_rowan")
    quest = QuestOpportunity(
        id=event.opportunityId, title=event.title, description=event.narrativeHook, issuer=issuer,
        target=("location", event.location),
        reward=coerce_in(F(event.urgency * 100.0), 25.0, 150.0), status="AVAILABLE")
    return replace(state, activeQuests=state.activeQuests + (quest,))


def _player_attacked(state, event):
    return replace(state, player=replace(state.player, health=max(0.0, min(100.0, state.player.health - event.damage))))


def _player_moved(state, event):
    return replace(state, player=replace(state.player, currentLocation=event.to))


_HANDLERS = {
    ev.NpcKilled: _npc_death,
    ev.ItemStolen: _theft,
    ev.RumorTransmitted: _rumor,
    ev.RelationshipShifted: _rel_shift,
    ev.AllegianceChanged: _allegiance,
    ev.TradeCompleted: _trade,
    ev.RationPurchased: _ration,
    ev.PriceAdjusted: _price,
    ev.ShopInherited: _shop_inherited,
    ev.FactionHostilityDeclared: _hostility,
    ev.EntitiesMet: _met,
    ev.NpcAttacked: _attacked,
    ev.NpcRoutineCompleted: _routine,
    ev.OpportunityDispatched: _opportunity,
    ev.PlayerMoved: _player_moved,
    ev.PlayerAttacked: _player_attacked,
}
