#!/usr/bin/env python3
"""
Living-World RPG: Visual UI Inspector & Map Canvas Simulator (Milestone ui-compose-001)
Simulates and renders the complete 2D overhead map canvas, NPC portrait cards,
market trade board, and causal event ledger timeline.
"""

import sys
import os
from simulate_village import (
    init_village_world,
    SpriteManifest,
    ChimeraSpritePalette,
    NpcVisualAdapter,
    MapVisualAdapter,
    EventVisualAdapter,
    ItemVisualAdapter,
    VillageDashboardAdapter
)

def render_ascii_map_canvas(world, manifest):
    print("================================================================================")
    print(f"      CHIMERA / LIVING-WORLD: 2D OVERHEAD VILLAGE CANVAS — {world.time.formatted()}")
    print("================================================================================")
    print("                       [ Shrine of Rowan ]")
    print("                              |")
    print("                              | (Pilgrim Trail)")
    print("                              |")
    print("    [ Market / Smithy ] --- [ Village Square ] --- [ Hearthside Tavern ]")
    print("            |                        |                       |")
    print("            | (Iron Road)            | (South Gate)          | (Forest Track)")
    print("            |                        |                       |")
    print("    [ Deepdelve Mine ]       [ Palissade Gate ]       [ Hunter's Lodge ]")
    print("--------------------------------------------------------------------------------")
    print("Location Census & Occupant Sprite Tokens:")

    for loc_id, loc_info in world.locations.items():
        occupants = [npc for npc in world.npcs.values() if npc.is_alive and npc.location == loc_id]
        token_ids = [manifest.resolve_npc_token(n.id)["id"] for n in occupants if manifest.resolve_npc_token(n.id)]
        faction_badge = f" [Controlled by {loc_info['controlling_faction']}]" if loc_info['controlling_faction'] else ""
        print(f"  * {loc_info['name']}{faction_badge}:")
        print(f"    - Occupants ({len(occupants)}): {', '.join(n.name for n in occupants[:4])}{'...' if len(occupants) > 4 else ''}")
        print(f"    - Sprite Tokens: {', '.join(token_ids[:4])}{'...' if len(token_ids) > 4 else ''}")

def render_portrait_gallery(world, manifest):
    print("\n================================================================================")
    print("              GOTHIC MANUSCRIPT NPC PORTRAIT GALLERY & EMOTIONAL TELEMETRY")
    print("================================================================================")
    for npc_id in ["merchant", "apprentice", "miner_derek", "guard_captain", "elder_rowan"]:
        if npc_id in world.npcs:
            npc = world.npcs[npc_id]
            visual = NpcVisualAdapter.create_visual_model(npc, manifest)
            health_bar = "[" + "=" * int(visual["health_pct"] * 10) + " " * (10 - int(visual["health_pct"] * 10)) + "]"
            ink_alert = " [INK WASH BLEED]" if visual["needs_ink_wash"] else ""
            print(f"  +--------------------------------------------------------------+")
            print(f"  | {visual['display_name']:<28} | Role: {visual['occupation']:<21} |")
            print(f"  | Portrait Sprite : {visual['portrait_sprite'] or 'None':<20} | Expression: {visual['expression']:<15} |")
            print(f"  | Health {health_bar} {int(visual['health_pct']*100)}%   | Tint : {visual['tint_color']:<15}{ink_alert} |")
            print(f"  | Badges: {', '.join(visual['badges']):<52} |")
            print(f"  +--------------------------------------------------------------+")

def render_market_catalog(world, manifest):
    print("\n================================================================================")
    print("                   MARKETPLACE ECONOMIC BOARD & COMMODITY SEALS")
    print("================================================================================")
    commodities = ["bread", "iron_sword", "herb_bundle", "iron_ingot", "omen_stone"]
    for cid in commodities:
        model = ItemVisualAdapter.create_item_model(cid, world, manifest, rarity="RARE" if "sword" in cid else ("LEGENDARY" if "omen" in cid else "COMMON"))
        print(f"  [{model['rarity']:<9}] {model['display_name']:<18} | Price: {model['price']:>5.1f} coins | Sprite: {model['item_sprite']:<22} | Seal: {model['rarity_seal']}")


def render_audio_ambience(world):
    from simulate_village import AudioStateAdapter
    amb = AudioStateAdapter.synthesize_ambience(world, location_id="village_square")
    print("\n================================================================================")
    print("           ATMOSPHERIC AUDIO SOUNDSCAPE & ACTIVE SFX CUES")
    print("================================================================================")
    print(f"  Primary Soundscape : {amb['soundscape']}")
    print(f"  Dynamic Tension    : {amb['tension']} (Intensity: {amb['intensity']:.2f})")
    print("  Active Audio Layers:")
    for l in amb["layers"]:
        print(f"    - [{l['id']}] vol={l['volume']:.2f}")
    print("  Recent Event Audio Cues:")
    for cue in amb["cues"]:
        print(f"    * SFX [{cue['id']}] (priority={cue['priority']}): {cue['name']}")

def render_causal_timeline(world, manifest, limit=6):
    print("\n================================================================================")
    print("            CAUSAL EVENT LEDGER TIMELINE (Narrative Backtracking)")
    print("================================================================================")
    recent = list(reversed(world.events.events[-limit:]))
    for ev in recent:
        chain = world.events.trace_causal_chain(ev.id)
        depth = len(chain) - 1
        indent = "  " * depth
        parent_str = f" <- [Predecessor #{ev.causal_event_id}]" if ev.causal_event_id else " [Root Action]"
        actor = ev.actors[0] if ev.actors else "System"
        portrait = manifest.resolve_npc_portrait(actor, "neutral")
        day = ev.time_tick // 1440
        hour = (ev.time_tick % 1440) // 60
        crit_str = " [CRITICAL]" if ev.event_type in ("NpcKilled", "NpcAttacked") else ""
        print(f"{indent}[Day {day:02d}, {hour:02d}:00] Event #{ev.id} ({ev.event_type}){crit_str}{parent_str}")
        print(f"{indent}  Summary : {ev.summary}")
        print(f"{indent}  Portrait: {portrait['id'] if portrait else 'None'}")
        print()

def main():
    manifest_path = "game-ui/src/commonMain/resources/sprite_manifest.json"
    manifest = SpriteManifest(manifest_path)
    world = init_village_world()

    # Step through historical scenario:
    # 1. Day 14: Escort caravan
    world.time.tick = 14 * 1440
    ev_caravan = world.events.append("RelationshipShifted", world.time.tick, ["merchant", "player"], [],
                                     "Merchant trusts player after successful caravan escort")
    world.modify_rel("merchant", "player", d_trust=0.6, d_aff=0.5, ev_id=ev_caravan.id)

    # 2. Day 24: Defection
    world.time.tick = 24 * 1440
    ev_def = world.events.append("AllegianceChanged", world.time.tick, ["merchant", "verdant_order"], ["miner_derek"],
                                 "Merchant Alden defects to Verdant Order", causal_id=ev_caravan.id)

    # 3. Day 29: Ambush & Wounding
    world.time.tick = 29 * 1440
    world.npcs["merchant"].health = 25.0
    ev_amb = world.events.append("NpcAttacked", world.time.tick, ["miner_derek", "merchant"], ["apprentice"],
                                "Miner Derek ambushed Merchant Alden for guild betrayal", causal_id=ev_def.id)

    # 4. Day 30: Death
    world.time.tick = 30 * 1440
    world.npcs["merchant"].is_alive = False
    world.npcs["merchant"].health = 0.0
    ev_kill = world.events.append("NpcKilled", world.time.tick, ["miner_derek", "merchant"], ["apprentice"],
                                 "Merchant Alden died of fatal wounds", causal_id=ev_amb.id)
    world.modify_rel("apprentice", "miner_derek", d_trust=-0.9, d_resent=1.0, ev_id=ev_kill.id)

    # 5. Day 36: Price increase
    world.time.tick = 36 * 1440
    world.item_prices["bread"] = 14.0
    ev_price = world.events.append("PriceAdjusted", world.time.tick, ["apprentice"], [],
                                   "Finn increased bread price due to supplier blockade", causal_id=ev_kill.id)

    render_ascii_map_canvas(world, manifest)
    render_portrait_gallery(world, manifest)
    render_market_catalog(world, manifest)
    render_causal_timeline(world, manifest)
    render_audio_ambience(world)

if __name__ == "__main__":
    main()
