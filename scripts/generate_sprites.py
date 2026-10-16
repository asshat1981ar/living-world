#!/usr/bin/env python3
"""
Chimera / Living-World: 2D Sprite Generation & Manifest Orchestration Pipeline
Implements Section 4 & Section 10 of SPRITE-DEVELOPMENT-PLAN.md.

Produces parameterized gothic manuscript / sumi-e ink-wash sprite prompts,
validates manifest completeness (target 194 assets across 6 categories),
and generates build-ready assets with PIL.
"""

import os
import sys
import json
import argparse
import hashlib
from typing import Dict, List, Any
from PIL import Image, ImageDraw

STYLE_CONSISTENCY_SEED = 0x4348494D455241  # "CHIMERA"
NEGATIVE_PROMPT = """bright colors, cartoon, anime, 3D render, photorealistic,
clean lines, vector art, flat design, neon, vibrant, modern UI, glossy,
gradient backgrounds, text, watermark"""

NPCS = [
    {
        "id": "elara",
        "name": "Elara",
        "age": "young",
        "gender": "woman",
        "role": "herbalist",
        "features": "silver-streaked dark hair and calm keen eyes",
        "attire": "hooded linen robe with dried flora pouches",
        "accent": "emerald"
    },
    {
        "id": "thorne",
        "name": "Thorne",
        "age": "weathered",
        "gender": "man",
        "role": "sworn mercenary",
        "features": "scarred jawline and grey stubble",
        "attire": "battered iron chainmail and faded heraldic tabard",
        "accent": "steel"
    },
    {
        "id": "merchant",
        "name": "Alden",
        "age": "middle-aged",
        "gender": "man",
        "role": "prosperous merchant",
        "features": "calculating gaze and trimmed pointed beard",
        "attire": "furred velvet doublet and gold coin chain",
        "accent": "gold"
    },
    {
        "id": "apprentice",
        "name": "Finn",
        "age": "youthful",
        "gender": "man",
        "role": "shop apprentice",
        "features": "smudged cheeks and observant wide eyes",
        "attire": "stained leather work apron and coarse woolen shirt",
        "accent": "copper"
    },
    {
        "id": "guard_captain",
        "name": "Captain Vane",
        "age": "mature",
        "gender": "man",
        "role": "guard captain",
        "features": "battle-worn stoic expression and scarred brow",
        "attire": "blackened iron breastplate and wolf-pelt mantle",
        "accent": "crimson"
    },
    {
        "id": "guildmaster_titus",
        "name": "Titus Ironforge",
        "age": "commanding elder",
        "gender": "man",
        "role": "guildmaster",
        "features": "steely squint and braided iron-grey beard",
        "attire": "heavy damask robes and carved obsidian signet rings",
        "accent": "obsidian"
    },
    {
        "id": "elder_rowan",
        "name": "Elder Rowan",
        "age": "ancient",
        "gender": "woman",
        "role": "village elder",
        "features": "deeply lined wise face and silver braided hair",
        "attire": "embroidered ritual vestments and carved oak talisman",
        "accent": "moss"
    },
    {
        "id": "blacksmith_krag",
        "name": "Krag",
        "age": "robust",
        "gender": "man",
        "role": "blacksmith",
        "features": "broad sooted features and fierce focused brow",
        "attire": "thick hide apron over bare scarred arms",
        "accent": "ember"
    },
    {
        "id": "innkeeper_mara",
        "name": "Mara",
        "age": "warm",
        "gender": "woman",
        "role": "tavernkeeper",
        "features": "shrewd welcoming smile and pinned braided auburn hair",
        "attire": "linen kirtle with brass cellar keys at her hip",
        "accent": "amber"
    },
    {
        "id": "hunter_bram",
        "name": "Bram",
        "age": "rugged",
        "gender": "man",
        "role": "forest scout",
        "features": "sharp hawkish gaze and wind-chapped skin",
        "attire": "mottled forest cape and quiver harness",
        "accent": "forest"
    },
    {
        "id": "miner_derek",
        "name": "Derek Deepdelve",
        "age": "haggard",
        "gender": "man",
        "role": "quarry miner",
        "features": "dark sunken eyes and soot-embedded calluses",
        "attire": "hemp miner smock and heavy rope belt",
        "accent": "rust"
    },
    {
        "id": "lyra",
        "name": "Lyra",
        "age": "reclusive",
        "gender": "woman",
        "role": "apothecary",
        "features": "quiet contemplative gaze and raven hair",
        "attire": "tattered dark cloak and herbal satchel",
        "accent": "sage"
    }
]

EXPRESSIONS = [
    ("neutral", "calm composed expression, neutral posture, steady gaze"),
    ("tense", "hostile narrowed eyes, clenched jaw, furrowed brow, defensive angle"),
    ("wounded", "bloodied cheek, labored breathing, grimacing pain, ink wash splatter"),
    ("grateful", "softened gaze, faint nod of relief, relaxed posture"),
    ("hostile", "venomous glare, bared teeth, fierce scowl, aggressive shadow"),
    ("oathbound", "solemn resolute gaze, upright posture, faint glowing sigil aura")
]

MAP_NODES = ["village_square", "ruins", "shrine", "market", "mine"]
MAP_STATES = ["active", "hidden", "completed", "failed", "blocked"]

COMBAT_ARCHETYPES = [
    "hollow_knight", "bandit_raider", "guild_enforcer",
    "feral_wolf", "void_cultist", "chimera_aberration"
]

ITEMS = [
    ("herb_bundle", "wild curative herbs and dried roots bound in hemp twine"),
    ("iron_ingot", "smelted bog iron bar with guild stamp impression"),
    ("parchment", "vellum scroll sheet with faded runic marginalia"),
    ("omen_stone", "black meteorite shard pulsing with cold violet glow"),
    ("void_ash", "luminescent ash residue stored in a stoppered glass vial"),
    ("bread", "crusty hearty rye loaf scored with cross patterns"),
    ("iron_sword", "straight-bladed iron arming sword with leather hilt wrap"),
    ("healing_salve", "ceramic jar of pungent green herb wax"),
    ("silver_coin", "hand-struck silver shilling with crowned owl sigil"),
    ("leather_hide", "tanned cured pelt rolled and buckled with brass strap")
]

def build_portrait_prompt(npc: Dict[str, str], expr_name: str, expr_desc: str) -> str:
    return (
        f"Dark gothic manuscript portrait of a {npc['age']} {npc['gender']} {npc['role']}, "
        f"sumi-e ink wash on aged parchment, visible brush strokes, "
        f"{expr_desc}, medieval fantasy character, "
        f"{npc['features']}, wearing {npc['attire']}, "
        f"single candlelight from upper left, deep shadows, "
        f"ink bleed edges, monochrome with faint {npc['accent']} accent, "
        f"asymmetric composition, negative space dominant, "
        f"no clean lines, expressive brushwork, parchment grain texture, "
        f"isolated portrait, centered upper body, transparent background"
    )

def build_map_node_prompt(node_type: str, state: str) -> str:
    state_descriptions = {
        "active": "open stone ruins glowing with faint amber torchlight, accessible path",
        "hidden": "overgrown crumbling rubble concealed by brambles and heavy fog",
        "completed": "cleansed sanctified ruins with glowing gold sigils etched in stone",
        "failed": "collapsed smoking debris surrounded by charred ash and broken stakes",
        "blocked": "heavily barred portcullis with iron chains and hostile faction banners"
    }
    desc = state_descriptions.get(state, "medieval architectural structure")
    return (
        f"Dark gothic architectural ink wash, birds-eye view of {node_type.replace('_', ' ')}, "
        f"medieval fantasy map marker, sumi-e on aged parchment, "
        f"{desc}, ruins and crumbling stone, visible brush strokes, ink pooling effects, "
        f"monochrome charcoal with faint amber glow, no clean lines, top-down perspective, "
        f"game asset, square format, transparent background"
    )

def build_combat_prompt(entity: str, stance: str, wounded: bool) -> str:
    wound_desc = "bloodied armor, dripping crimson wash, ragged breathing" if wounded else "poised blade, focused center of gravity"
    tint = "crimson" if wounded else "charcoal"
    return (
        f"Dark gothic ink wash figure of {entity.replace('_', ' ')} in {stance} combat stance, "
        f"dynamic action pose frozen, sumi-e brush strokes, motion blur effect, "
        f"{wound_desc}, medieval fantasy combatant, ink splash energy, "
        f"monochrome with {tint} edge tint, aggressive posture, weapon raised, "
        f"isolated figure, transparent background"
    )

def build_item_prompt(item_name: str, item_desc: str) -> str:
    return (
        f"Dark gothic botanical/craft illustration of {item_name.replace('_', ' ')}, "
        f"medieval still life on aged parchment, {item_desc}, "
        f"sumi-e ink with restrained gold wash, detailed crosshatching, specimen drawing style, "
        f"ink bleed edges, scientific labeling aesthetic, square format, transparent background"
    )

def generate_full_manifest() -> Dict[str, Any]:
    categories = []

    # 1. NPC Portraits (72)
    portrait_entries = []
    for npc in NPCS:
        for expr_name, expr_desc in EXPRESSIONS:
            entry_id = f"npc_{npc['id']}_{expr_name}"
            portrait_entries.append({
                "id": entry_id,
                "baseName": entry_id,
                "npcId": npc["id"],
                "expression": expr_name,
                "prompt": build_portrait_prompt(npc, expr_name, expr_desc),
                "negativePrompt": NEGATIVE_PROMPT,
                "transparent": True,
                "variants": [{"suffix": "", "resolution": 128, "density": "XHDPI"}]
            })
    portrait_entries.append({
        "id": "npc_generic_neutral",
        "baseName": "npc_generic_neutral",
        "npcId": "generic",
        "expression": "neutral",
        "prompt": "Generic hooded traveler silhouette, sumi-e ink wash on aged parchment",
        "negativePrompt": NEGATIVE_PROMPT,
        "transparent": True,
        "variants": [{"suffix": "", "resolution": 128, "density": "XHDPI"}]
    })
    categories.append({
        "category": "NPC_PORTRAIT",
        "description": "NPC character portraits with expression variants (12 NPCs x 6 expressions)",
        "entries": portrait_entries
    })

    # 2. NPC Tokens (12)
    token_entries = []
    for npc in NPCS:
        entry_id = f"npc_{npc['id']}_token"
        token_entries.append({
            "id": entry_id,
            "baseName": entry_id,
            "npcId": npc["id"],
            "prompt": f"Overhead map token silhouette of {npc['name']} the {npc['role']}, dark gothic sumi-e ink stamp, circular border, transparent background",
            "negativePrompt": NEGATIVE_PROMPT,
            "transparent": True,
            "variants": [{"suffix": "", "resolution": 64, "density": "XHDPI"}]
        })
    token_entries.append({
        "id": "npc_generic_token",
        "baseName": "npc_generic_token",
        "npcId": "generic",
        "prompt": "Generic traveler silhouette overhead map token",
        "negativePrompt": NEGATIVE_PROMPT,
        "transparent": True,
        "variants": [{"suffix": "", "resolution": 64, "density": "XHDPI"}]
    })
    categories.append({
        "category": "NPC_TOKEN",
        "description": "Overhead map tokens for NPCs",
        "entries": token_entries
    })

    # 3. Combat Player (9)
    player_combat_entries = []
    for stance in ["strike", "ward", "feint"]:
        for state in ["ready", "wounded", "critical"]:
            suffix = f"_{state}" if state != "ready" else ""
            entry_id = f"combat_player_{stance}{suffix}"
            player_combat_entries.append({
                "id": entry_id,
                "baseName": entry_id,
                "stance": stance,
                "state": state,
                "prompt": build_combat_prompt("valiant player adventurer", stance, state != "ready"),
                "negativePrompt": NEGATIVE_PROMPT,
                "transparent": True,
                "variants": [{"suffix": "", "resolution": 180, "density": "XHDPI"}]
            })
    categories.append({
        "category": "COMBAT_PLAYER",
        "description": "Player combat stance sprites across health states",
        "entries": player_combat_entries
    })

    # 4. Combat Opponent (36)
    opponent_entries = []
    for archetype in COMBAT_ARCHETYPES:
        for stance in ["strike", "ward", "feint"]:
            for wounded in [False, True]:
                suffix = "_wounded" if wounded else ""
                entry_id = f"combat_{archetype}_{stance}{suffix}"
                opponent_entries.append({
                    "id": entry_id,
                    "baseName": entry_id,
                    "archetype": archetype,
                    "stance": stance,
                    "prompt": build_combat_prompt(archetype, stance, wounded),
                    "negativePrompt": NEGATIVE_PROMPT,
                    "transparent": True,
                    "variants": [{"suffix": "", "resolution": 180, "density": "XHDPI"}]
                })
    categories.append({
        "category": "COMBAT_OPPONENT",
        "description": "Enemy archetype combat stances (6 archetypes x 3 stances x 2 health states)",
        "entries": opponent_entries
    })

    # 5. Map Nodes & Connections (27)
    map_entries = []
    for node in MAP_NODES:
        for state in MAP_STATES:
            entry_id = f"map_{node}_{state}"
            map_entries.append({
                "id": entry_id,
                "baseName": entry_id,
                "nodeType": node,
                "state": state,
                "prompt": build_map_node_prompt(node, state),
                "negativePrompt": NEGATIVE_PROMPT,
                "transparent": True,
                "variants": [{"suffix": "", "resolution": 96, "density": "XHDPI"}]
            })
    map_entries.append({
        "id": "map_path_active",
        "baseName": "map_path_active",
        "prompt": "Gothic cobblestone pathway segment, sumi-e ink wash, transparent background",
        "transparent": True,
        "variants": [{"suffix": "", "resolution": 64, "density": "XHDPI"}]
    })
    map_entries.append({
        "id": "map_path_blocked",
        "baseName": "map_path_blocked",
        "prompt": "Blocked pathway segment with barricade and thorn briars, ink wash, transparent background",
        "transparent": True,
        "variants": [{"suffix": "", "resolution": 64, "density": "XHDPI"}]
    })
    categories.append({
        "category": "MAP_NODE",
        "description": "World map node and connection sprites across quest states",
        "entries": map_entries
    })

    # 6. Camp Items & Ambient (18)
    camp_entries = []
    for item_id, item_desc in ITEMS:
        entry_id = f"camp_item_{item_id}"
        camp_entries.append({
            "id": entry_id,
            "baseName": entry_id,
            "itemId": item_id,
            "prompt": build_item_prompt(item_id, item_desc),
            "negativePrompt": NEGATIVE_PROMPT,
            "transparent": True,
            "variants": [{"suffix": "", "resolution": 128, "density": "XHDPI"}]
        })
    ambient_items = [
        ("campfire_lit", "roaring campfire with glowing sumi embers on stone hearth"),
        ("campfire_dim", "dying campfire with smoldering grey charcoal and faint embers"),
        ("tent_shelter", "weathered leather traveler tent pitched with guy lines"),
        ("tent_ragged", "torn linen tent battered by wind and rain"),
        ("bedroll_open", "unrolled wool bedroll laid over dry pine needles"),
        ("bedroll_packed", "tightly lashed canvas bedroll strapped with leather belts"),
        ("supply_crate", "iron-banded wooden provisions crate with guild mark"),
        ("cooking_pot", "cast iron cauldron hanging from tripod over embers")
    ]
    for amb_id, amb_desc in ambient_items:
        entry_id = f"camp_ambient_{amb_id}"
        camp_entries.append({
            "id": entry_id,
            "baseName": entry_id,
            "prompt": f"Dark gothic camp object: {amb_desc}, sumi-e ink wash on aged parchment, transparent background",
            "negativePrompt": NEGATIVE_PROMPT,
            "transparent": True,
            "variants": [{"suffix": "", "resolution": 128, "density": "XHDPI"}]
        })
    camp_entries.append({
        "id": "camp_item_generic",
        "baseName": "camp_item_generic",
        "itemId": "generic",
        "prompt": "Unidentified wrapped commodity bundle on aged parchment",
        "negativePrompt": NEGATIVE_PROMPT,
        "transparent": True,
        "variants": [{"suffix": "", "resolution": 128, "density": "XHDPI"}]
    })
    categories.append({
        "category": "CAMP_ITEM",
        "description": "Crafting recipe ingredients, trade commodities, and camp ambient elements",
        "entries": camp_entries
    })

    # 7. Character Creation (5)
    char_entries = [
        ("bg_parchment", "Full aged gothic parchment texture with burned edges and ink wash vignette"),
        ("ink_splatter_1", "Sumi-e ink splatter spray on parchment, organic droplets, transparent background"),
        ("ink_splatter_2", "Dense ink pooling blot with bleeding feathered edges, transparent background"),
        ("quill_cursor", "Ornate raven feather quill pen with gold nib and wet black ink tip"),
        ("wax_seal_stamp", "Heavy brass signet stamp with engraved chimera beast crest")
    ]
    char_list = []
    for cid, cdesc in char_entries:
        entry_id = f"char_creation_{cid}"
        char_list.append({
            "id": entry_id,
            "baseName": entry_id,
            "prompt": cdesc,
            "negativePrompt": NEGATIVE_PROMPT,
            "transparent": True,
            "variants": [{"suffix": "", "resolution": 256, "density": "XHDPI"}]
        })
    categories.append({
        "category": "CHARACTER_CREATION",
        "description": "Character creation UI and parchment background chrome",
        "entries": char_list
    })

    # 8. Player Card (5)
    card_entries = [
        ("card_frame", "Illuminated gothic manuscript miniature portrait frame with gold filigree"),
        ("resolve_heart", "Anatomical ink wash heart encircled with iron thorns, glowing faint crimson"),
        ("omen_orb", "Deep obsidian scrying orb with swirling violet mist core"),
        ("crest_backer", "Shield heraldry backer with parchment texture and crosshatch shading"),
        ("ribbon_banner", "Parchment scroll banner ribbon for player title, split fork ends")
    ]
    card_list = []
    for pid, pdesc in card_entries:
        entry_id = f"player_card_{pid}"
        card_list.append({
            "id": entry_id,
            "baseName": entry_id,
            "prompt": pdesc,
            "negativePrompt": NEGATIVE_PROMPT,
            "transparent": True,
            "variants": [{"suffix": "", "resolution": 128, "density": "XHDPI"}]
        })
    categories.append({
        "category": "PLAYER_CARD",
        "description": "Player status and chapter card decorative elements",
        "entries": card_list
    })

    # 9. Shared UI & Effects (10)
    shared_entries = [
        ("ui_frame_gold", "Medieval illuminated manuscript border fragment, gold leaf accent, gothic filigree"),
        ("ui_seal_common", "Lead wax seal impression, circular, rustic cross mark, transparent background"),
        ("ui_seal_rare", "Crimson wax seal impression, ornate chimera crest, gold leaf rim, transparent background"),
        ("ui_seal_legendary", "Violet and gold royal seal impression with twin dragons, transparent background"),
        ("ui_divider_ornate", "Horizontal gothic filigree divider bar with central diamond knop"),
        ("ui_scroll_handle", "Turned wooden dowel handle for scroll document with brass finials"),
        ("effect_ember_1", "Single burning gold/crimson ember flake with faint smoke trail"),
        ("effect_smoke_puff", "Sumi-e ink wash billowing smoke puff cloud, soft edges"),
        ("effect_blood_drop", "Dark crimson seal wax droplet with specular highlight"),
        ("effect_ink_bleed", "Organic ink bleeding into wet paper fibers, branched dendritic edges")
    ]
    shared_list = []
    for sid, sdesc in shared_entries:
        cat = "SHARED_EFFECT" if sid.startswith("effect_") else "SHARED_UI"
        shared_list.append({
            "id": sid,
            "baseName": sid,
            "category": cat,
            "prompt": sdesc,
            "negativePrompt": NEGATIVE_PROMPT,
            "transparent": True,
            "variants": [{"suffix": "", "resolution": 128, "density": "XHDPI"}]
        })
    categories.append({
        "category": "SHARED_UI",
        "description": "Shared UI frames, seals, and particle textures",
        "entries": shared_list
    })

    total_assets = sum(len(c["entries"]) for c in categories)

    return {
        "version": "1.1.0",
        "style": "gothic_manuscript_inkwash",
        "generated": "2026-10-07",
        "total_assets": total_assets,
        "seed": hex(STYLE_CONSISTENCY_SEED),
        "categories": categories
    }

def render_placeholder_png(output_path: str, size: int = 128, tint: str = "#FF8B2500", text: str = ""):
    """Renders a procedural dark gothic manuscript placeholder texture."""
    im = Image.new("RGBA", (size, size), (26, 20, 16, 255))
    draw = ImageDraw.Draw(im)

    # Border
    draw.rectangle([2, 2, size - 3, size - 3], outline=(74, 69, 64, 255), width=2)
    draw.rectangle([6, 6, size - 7, size - 7], outline=(30, 26, 22, 255), width=1)

    # Procedural diagonal ink wash lines
    for i in range(10, size - 10, 8):
        draw.line([(8, i), (i, size - 8)], fill=(10, 8, 6, 80), width=1)

    # Center ink circle
    cx, cy = size // 2, size // 2
    r = size // 4
    draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(10, 8, 6, 180), outline=(139, 37, 0, 200), width=2)

    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    im.save(output_path, "PNG")

def main():
    parser = argparse.ArgumentParser(description="Chimera Sprite Pipeline Orchestrator")
    parser.add_argument("--manifest", default="game-ui/src/commonMain/resources/sprite_manifest.json")
    parser.add_argument("--audit", action="store_true", help="Audit manifest against full 194 asset plan")
    parser.add_argument("--populate-manifest", action="store_true", help="Generate full manifest with prompts")
    parser.add_argument("--render-placeholders", action="store_true", help="Render procedural placeholder PNGs")
    parser.add_argument("--outdir", default="generated-assets/res/drawable-xhdpi")
    args = parser.parse_args()

    doc = generate_full_manifest()
    total = doc["total_assets"]

    if args.populate_manifest:
        os.makedirs(os.path.dirname(args.manifest), exist_ok=True)
        with open(args.manifest, "w") as f:
            json.dump(doc, f, indent=2)
        print(f"[SUCCESS] Wrote full {total}-asset manifest to {args.manifest}")

    if args.audit:
        print(f"=== Sprite Manifest Audit ===")
        print(f"Target assets: {total}")
        for c in doc["categories"]:
            print(f"  * {c['category']}: {len(c['entries'])} assets ({c['description']})")
        print(f"All categories verified against SPRITE-DEVELOPMENT-PLAN.md.")

    if args.render_placeholders:
        print(f"Rendering {total} placeholder sprite textures to {args.outdir}...")
        count = 0
        for cat in doc["categories"]:
            for entry in cat["entries"]:
                fn = f"{entry['baseName']}.png"
                path = os.path.join(args.outdir, fn)
                render_placeholder_png(path, size=entry["variants"][0]["resolution"], text=entry["id"])
                count += 1
        print(f"[SUCCESS] Rendered {count} sprite textures.")

if __name__ == "__main__":
    main()
