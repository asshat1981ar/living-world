"""SimulationEngine."""
from dataclasses import replace

from . import actions as ac
from . import causal
from . import combat
from . import decision
from . import events as ev
from . import reducer
from . import worldtime
from .f32 import f32, coerce_in
from .world import generate_opportunities

F = f32
_ACTIVE_PHASES = ("MORNING", "MIDDAY", "AFTERNOON")

# SimulationEngine companion constants
WORK_WAGE = 0.25
DAILY_UPKEEP = 3.0
RATION_FOOD = 8.0
RATION_PRICE = 3.0
RATION_TAX = 1.0
RATION_HUNGER_THRESHOLD = 5.0
RATION_SELLER_RESERVE = 5.0


def advance(state, duration_ticks):
    """advance(): one tick() call per hour (60 ticks)."""
    if duration_ticks <= 0:
        return state
    remaining = duration_ticks
    while remaining > 0:
        step = min(remaining, worldtime.TICKS_PER_HOUR)
        state = tick(state, step)
        remaining -= step
    return state


def tick(state, step):
    if step <= 0:
        return state
    current = replace(state, time=state.time + step)
    for npc in select_active_npcs(current):
        fresh = current.npcs.get(npc.id)
        if fresh is None or not fresh.is_alive:
            continue
        action = decision.decide(fresh, current)
        current = execute(current, fresh, action)
    if current.time // worldtime.TICKS_PER_DAY > state.time // worldtime.TICKS_PER_DAY:
        current = collect_upkeep(current)
    return dispatch_opportunities(current)


def collect_upkeep(state):
    current = state
    for npc in sorted((n for n in current.npcs.values() if n.is_alive and n.resources.coins > 0.0), key=lambda n: n.id):
        current = reducer.apply(current, ev.NpcRoutineCompleted(
            id=current.events.next_id(), time=current.time, npc=npc.id, routineType="UPKEEP",
            location=npc.identity.currentLocation, deltaCoins=F(-DAILY_UPKEEP)))
    return current


def buy_rations_if_hungry(state, buyer_id):
    buyer = state.npcs.get(buyer_id)
    if buyer is None or buyer.resources.food >= RATION_HUNGER_THRESHOLD or buyer.resources.coins < RATION_PRICE:
        return state
    sellers = sorted((n for n in state.npcs.values()
                      if n.is_alive and n.id != buyer_id and _is_food_producer(n.identity.occupation)
                      and n.resources.food >= RATION_FOOD + RATION_SELLER_RESERVE),
                     key=lambda n: (-n.resources.food, n.id))
    if not sellers:
        return state
    return reducer.apply(state, ev.RationPurchased(
        id=state.events.next_id(), time=state.time, buyer=buyer_id, seller=sellers[0].id,
        food=F(RATION_FOOD), price=F(RATION_PRICE), tax=F(RATION_TAX)))


def dispatch_opportunities(state):
    current = state
    for opp in generate_opportunities(current):
        if any(q.id == opp.id for q in current.activeQuests):
            continue
        target_loc = opp.target[1] if (opp.target is not None and opp.target[0] == "location") else "village_square"
        event = ev.OpportunityDispatched(
            id=current.events.next_id(), time=current.time, opportunityId=opp.id, opportunityType="CRISIS_QUEST",
            title=opp.title, location=target_loc,
            urgency=coerce_in(F(opp.reward / 100.0), F(0.25), 1.0),
            narrativeHook=opp.description, actors=(opp.issuer,),
            causalEventId=causal.origin_of_quest(current.events, opp.id))
        current = reducer.apply(current, event)
    return current


def select_active_npcs(state):
    phase = worldtime.phase(state.time)
    out = []
    for npc in state.npcs.values():
        if npc.is_alive and (
                npc.resources.health < 80.0
                or any(g.urgency > F(0.4) for g in npc.goals)
                or npc.emotionalState.fear > F(0.3)
                or npc.emotionalState.anger > F(0.3)
                or phase in _ACTIVE_PHASES):
            out.append(npc)
    out.sort(key=lambda n: n.id)
    return out


def _is_food_producer(occupation):
    o = occupation.lower()
    return any(k in o for k in ("farmer", "baker", "hunter", "herbalist", "brewer"))


def transmit_belief(source, target, belief, current_time, relationships):
    from . import beliefs as bf
    rel_score = F(F(target.reputation_toward(("npc", source.id), relationships) + 1.0) / 2.0)
    reliability = coerce_in(F(F(rel_score * belief.confidence) * belief.reliability), F(0.1), F(0.95))
    distortion = F(0.85) if source.has_trait("OPPORTUNISTIC") else F(0.95)
    new_conf = coerce_in(F(F(belief.confidence * reliability) * distortion), F(0.1), 1.0)
    return bf.Belief(belief.proposition, new_conf, bf.Hearsay(source.id), reliability, current_time)


def execute(state, npc, intent):
    event_id = state.events.next_id()
    time = state.time
    if isinstance(intent, ac.Rest):
        e = ev.NpcRoutineCompleted(id=event_id, time=time, npc=npc.id, routineType="REST",
                                   location=npc.identity.currentLocation, deltaHealth=F(10.0), deltaFood=F(-0.25))
        return reducer.apply(state, e)
    if isinstance(intent, ac.Work):
        producer = _is_food_producer(npc.identity.occupation)
        wage = 0.0 if producer else max(min(F(WORK_WAGE), state.economy.treasury), 0.0)
        e = ev.NpcRoutineCompleted(id=event_id, time=time, npc=npc.id, routineType="WORK",
                                   location=intent.location, deltaCoins=F(wage),
                                   deltaFood=F(1.5) if producer else F(-0.25))
        worked = reducer.apply(state, e)
        return worked if producer else buy_rations_if_hungry(worked, npc.id)
    if isinstance(intent, ac.Socialize):
        e = ev.RelationshipShifted(id=event_id, time=time, source=("npc", npc.id), target=("npc", intent.target),
                                   deltaTrust=F(0.05), deltaAffection=F(0.05), deltaRespect=F(0.02), deltaFear=0.0,
                                   deltaResentment=F(-0.02), reason="Friendly conversation in village")
        return reducer.apply(state, e)
    if isinstance(intent, ac.ShareRumor):
        target = state.npcs.get(intent.target)
        if target is None or not target.is_alive:
            return state
        transmitted = transmit_belief(npc, target, intent.belief, time, state.relationships)
        e = ev.RumorTransmitted(id=event_id, time=time, source=npc.id, target=intent.target,
                                transmittedBelief=transmitted, distortion=F(0.1),
                                causalEventId=causal.origin_of(state.events, intent.belief.proposition))
        return reducer.apply(state, e)
    if isinstance(intent, ac.Trade):
        e = ev.TradeCompleted(id=event_id, time=time, buyer=intent.partner, seller=("npc", npc.id),
                              item=intent.item, price=intent.price)
        return reducer.apply(state, e)
    if isinstance(intent, ac.ChangePrice):
        old = state.economy.get_price(intent.item)
        e = ev.PriceAdjusted(id=event_id, time=time, merchant=npc.id, item=intent.item, oldPrice=old,
                             newPrice=intent.newPrice, reason=intent.reason)
        return reducer.apply(state, e)
    if isinstance(intent, ac.ChangeAllegiance):
        old = npc.factions[0] if npc.factions else None
        e = ev.AllegianceChanged(id=event_id, time=time, npc=npc.id, oldFaction=old, newFaction=intent.newFaction,
                                 reason=intent.reason)
        return reducer.apply(state, e)
    if isinstance(intent, ac.Attack):
        target = state.npcs.get(intent.target)
        if target is None or not target.is_alive:
            return state
        duel = combat.resolve_exchange(npc, target, time)
        witnesses = tuple(n.id for n in sorted(
            (n for n in state.npcs.values()
             if n.is_alive and n.id != npc.id and n.id != intent.target
             and n.identity.currentLocation == npc.identity.currentLocation),
            key=lambda n: n.id))
        attack = ev.NpcAttacked(id=event_id, time=time, attacker=("npc", npc.id), target=intent.target,
                                damage=duel.finalDamage, location=npc.identity.currentLocation, witnesses=witnesses,
                                attackerStance=duel.attackerStance, defenderStance=duel.defenderStance,
                                duelOutcome=duel.outcome)
        inter = reducer.apply(state, attack)
        tgt = inter.npcs.get(intent.target)
        after_health = tgt.resources.health if tgt is not None else 0.0
        if after_health <= 0.0:
            kill_id = inter.events.next_id()
            kill = ev.NpcKilled(id=kill_id, time=time, victim=intent.target, killer=("npc", npc.id),
                                witnesses=witnesses, causalEventId=event_id,
                                location=npc.identity.currentLocation,
                                motive=f"{intent.reason} ({ev.DUEL[duel.outcome][1]})")
            inter = reducer.apply(inter, kill)
        return inter
    if isinstance(intent, ac.InheritShop):
        e = ev.ShopInherited(id=event_id, time=time, heir=npc.id, predecessor=intent.deceased,
                             shopName=intent.shopName, causalEventId=causal.death_of(state.events, intent.deceased))
        return reducer.apply(state, e)
    if isinstance(intent, ac.Flee):
        e = ev.NpcRoutineCompleted(id=event_id, time=time, npc=npc.id, routineType="FLEE",
                                   location=npc.identity.currentLocation, deltaFear=F(-0.1))
        return reducer.apply(state, e)
    raise TypeError(intent)
