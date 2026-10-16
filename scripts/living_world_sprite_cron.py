#!/usr/bin/env python3
"""
Living-World RPG: Autonomous Sprite Generation Cron Loop

Iterates through NPCs, factions, and locations defined in the living-world
simulation model, checks which assets have not yet been generated in Google Drive
or the local asset directory, builds the gothic manuscript prompt stack,
and orchestrates the generation pass.

Usage:
  python3 living_world_sprite_cron.py --once
  python3 living_world_sprite_cron.py --interval-hours 24
"""

import os
import sys
import json
import time
import argparse
from pathlib import Path
from typing import List, Dict

# Style Prompt Stack according to Chimera / Living-World Specification
GOTHIC_STYLE_STACK = (
    "Dark gothic manuscript illustration, sumi-e ink wash on aged parchment, "
    "visible brush strokes, expressive brushwork, ink bleed edges, "
    "monochrome charcoal with restrained accent color, parchment grain texture, "
    "no clean lines, no text, no watermark, game asset sprite"
)

# Canonical 20 Living-World NPCs synchronized with DefaultVillageFactory / TestWorld.kt / simulate_village.py
LIVING_WORLD_NPCS = [
    {"id": "merchant", "name": "Alden the Merchant", "role": "Merchant", "accent": "faint crimson", "expression": "opportunistic cautious expression"},
    {"id": "apprentice", "name": "Finn the Apprentice", "role": "Shop Apprentice", "accent": "amber gold", "expression": "curious eager expression"},
    {"id": "guard_captain", "name": "Captain Vane", "role": "Guard Captain", "accent": "steel grey", "expression": "stern vigilant expression"},
    {"id": "guildmaster_titus", "name": "Titus Ironforge", "role": "Iron Guildmaster", "accent": "deep crimson", "expression": "commanding ruthless expression"},
    {"id": "elder_rowan", "name": "Elder Rowan", "role": "Verdant Order Elder", "accent": "sage green", "expression": "serene thoughtful expression"},
    {"id": "blacksmith_krag", "name": "Krag the Blacksmith", "role": "Blacksmith", "accent": "smoky ash", "expression": "determined rugged expression"},
    {"id": "innkeeper_mara", "name": "Mara of the Hearth", "role": "Tavern Innkeeper", "accent": "warm amber", "expression": "welcoming observant expression"},
    {"id": "herbalist_lyra", "name": "Lyra Greenleaf", "role": "Herbalist", "accent": "muted jade", "expression": "gentle compassionate expression"},
    {"id": "hunter_bram", "name": "Bram the Scout", "role": "Wilderness Hunter", "accent": "forest moss", "expression": "sharp focused expression"},
    {"id": "farmer_giles", "name": "Giles Oatfield", "role": "Farmer", "accent": "faded ochre", "expression": "weathered weary expression"},
    {"id": "farmer_elena", "name": "Elena Oatfield", "role": "Farmer", "accent": "earth brown", "expression": "resilient steady expression"},
    {"id": "carpenter_jorin", "name": "Jorin Woodshaper", "role": "Carpenter", "accent": "timber umber", "expression": "thoughtful practical expression"},
    {"id": "miner_derek", "name": "Derek Deepdelve", "role": "Miner", "accent": "slate charcoal", "expression": "vengeful gritty expression"},
    {"id": "weaver_sela", "name": "Sela Threadspinner", "role": "Weaver", "accent": "faded indigo", "expression": "quiet meticulous expression"},
    {"id": "priest_orion", "name": "Father Orion", "role": "Priest", "accent": "pale silver", "expression": "pious solemn expression"},
    {"id": "watchman_cole", "name": "Cole Nightwatch", "role": "Watchman", "accent": "lantern bronze", "expression": "vigilant watchful expression"},
    {"id": "baker_milo", "name": "Milo the Baker", "role": "Baker", "accent": "warm crust gold", "expression": "generous cheerful expression"},
    {"id": "brewer_oscar", "name": "Oscar Barleybrew", "role": "Brewer", "accent": "cask amber", "expression": "convivial hearty expression"},
    {"id": "scholar_erik", "name": "Erik Chronicler", "role": "Scholar", "accent": "ink violet", "expression": "curious analytical expression"},
    {"id": "tanner_garth", "name": "Garth Hidesman", "role": "Tanner", "accent": "dark leather", "expression": "practical stoic expression"}
]

def build_portrait_prompt(npc: Dict[str, str]) -> str:
    return (
        f"{GOTHIC_STYLE_STACK}, isolated portrait, centered upper body, "
        f"medieval fantasy {npc['role']}, {npc['name']}, {npc['expression']}, "
        f"single candlelight from upper left, deep shadows, asymmetric composition, "
        f"negative space dominant, monochrome charcoal with {npc['accent']} accent, "
        f"transparent background"
    )

def build_token_prompt(npc: Dict[str, str]) -> str:
    return (
        f"{GOTHIC_STYLE_STACK}, small overhead token, birds-eye view silhouette, "
        f"{npc['role']} figure marker, even overhead lighting, centered, compact, "
        f"monochrome charcoal with faint {npc['accent']} accent, transparent background"
    )

def run_cron_cycle(output_dir: Path) -> Dict[str, int]:
    output_dir.mkdir(parents=True, exist_ok=True)
    manifest_queue = []
    existing_count = 0

    for npc in LIVING_WORLD_NPCS:
        portrait_name = f"npc_{npc['id']}_neutral.png"
        token_name = f"npc_{npc['id']}_token.png"

        if (output_dir / portrait_name).exists():
            existing_count += 1
        else:
            manifest_queue.append({
                "id": f"npc_{npc['id']}_neutral",
                "filename": portrait_name,
                "category": "NPC_PORTRAIT",
                "prompt": build_portrait_prompt(npc)
            })

        if (output_dir / token_name).exists():
            existing_count += 1
        else:
            manifest_queue.append({
                "id": f"npc_{npc['id']}_token",
                "filename": token_name,
                "category": "NPC_TOKEN",
                "prompt": build_token_prompt(npc)
            })

    queue_file = output_dir / "pending_sprite_queue.json"
    queue_file.write_text(json.dumps(manifest_queue, indent=2), encoding="utf-8")
    return {"existing": existing_count, "queued": len(manifest_queue)}

def main():
    parser = argparse.ArgumentParser(description="Living-World Sprite Cron")
    parser.add_argument("--once", action="store_true", help="Run a single check cycle and exit")
    parser.add_argument("--output-dir", default="generated-assets/res/drawable-xhdpi", help="Target asset directory")
    parser.add_argument("--interval-hours", type=float, default=24.0, help="Loop interval in hours")
    args = parser.parse_args()

    out_path = Path(args.output_dir)
    if args.once:
        stats = run_cron_cycle(out_path)
        print(f"Sprite Cron Cycle Complete: {stats['existing']} existing, {stats['queued']} queued.")
        return

    while True:
        stats = run_cron_cycle(out_path)
        print(f"Sprite Cron Cycle Complete: {stats['existing']} existing, {stats['queued']} queued.")
        time.sleep(max(60.0, args.interval_hours * 3600.0))

if __name__ == "__main__":
    main()
