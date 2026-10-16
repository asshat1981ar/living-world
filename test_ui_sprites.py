import unittest
import sys
import os
import json
import struct
from pathlib import Path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from simulate_village import (
    SpriteManifest,
    ChimeraSpritePalette,
    NpcVisualAdapter,
    MapVisualAdapter,
    EventVisualAdapter,
    ItemVisualAdapter,
    VillageDashboardAdapter,
    TacticalStance,
    TacticalCombatResolver,
    SaveSchemaMigrator,
    GgufBinaryHeaderParser,
    ProceduralQuestEngine,
    TacticalDuelSimulator,
    run_90_day_soak_simulation,
    init_village_world,
    Npc,
    Relationship,
    GameEvent
)

class TestSpriteSystem(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest = SpriteManifest("game-ui/src/commonMain/resources/sprite_manifest.json")

    def test_manifest_resolution_and_fallbacks(self):
        hostile_elara = self.manifest.resolve_npc_portrait("elara", "hostile")
        self.assertIsNotNone(hostile_elara)
        self.assertEqual(hostile_elara["id"], "npc_elara_hostile")

        wounded_thorne = self.manifest.resolve_npc_portrait("thorne", "wounded")
        self.assertIsNotNone(wounded_thorne)
        self.assertEqual(wounded_thorne["id"], "npc_thorne_wounded")

        custom_thorne = self.manifest.resolve_npc_portrait("thorne", "unknown_expression")
        self.assertIsNotNone(custom_thorne)
        self.assertEqual(custom_thorne["id"], "npc_thorne_neutral")

        unknown = self.manifest.resolve_npc_portrait("unknown_hermit", "neutral")
        self.assertIsNotNone(unknown)
        self.assertEqual(unknown["id"], "npc_generic_neutral")

        bread = self.manifest.resolve_camp_item("bread")
        self.assertIsNotNone(bread)
        self.assertEqual(bread["id"], "camp_item_bread")

        seal = self.manifest.resolve_rarity_seal("RARE")
        self.assertIsNotNone(seal)
        self.assertEqual(seal["id"], "ui_seal_rare")

    def test_npc_visual_adapter_states(self):
        world = init_village_world()
        merchant = world.npcs["merchant"]

        visual_neutral = NpcVisualAdapter.create_visual_model(merchant, self.manifest)
        self.assertEqual(visual_neutral["expression"], "NEUTRAL")
        self.assertEqual(visual_neutral["tint_color"], ChimeraSpritePalette.TINT_NEUTRAL)
        self.assertFalse(visual_neutral["needs_ink_wash"])

        merchant.health = 20.0
        visual_wounded = NpcVisualAdapter.create_visual_model(merchant, self.manifest)
        self.assertEqual(visual_wounded["expression"], "WOUNDED")
        self.assertEqual(visual_wounded["tint_color"], ChimeraSpritePalette.TINT_WOUNDED)
        self.assertTrue(visual_wounded["needs_ink_wash"])
        self.assertIn("Wounded", visual_wounded["badges"])

        merchant.is_alive = False
        visual_dead = NpcVisualAdapter.create_visual_model(merchant, self.manifest)
        self.assertEqual(visual_dead["expression"], "DECEASED")
        self.assertEqual(visual_dead["tint_color"], ChimeraSpritePalette.TINT_DECEASED)
        self.assertIn("Deceased", visual_dead["badges"])

    def test_npc_relationship_expressions(self):
        npc = Npc(
            id="apprentice",
            name="Finn",
            occupation="Apprentice",
            location="village_square",
            factions=set(),
            traits=["AMBITIOUS"]
        )

        rel_oath = Relationship(trust=0.9, affection=0.9, respect=0.9)
        visual_oath = NpcVisualAdapter.create_visual_model(npc, self.manifest, observer_rel=rel_oath)
        self.assertEqual(visual_oath["expression"], "OATHBOUND")

        rel_mod = Relationship(trust=0.4, affection=0.3, respect=0.2)
        visual_mod = NpcVisualAdapter.create_visual_model(npc, self.manifest, observer_rel=rel_mod)
        self.assertEqual(visual_mod["expression"], "GRATEFUL")

        rel_hostile = Relationship(trust=-0.9, resentment=0.9)
        visual_hostile = NpcVisualAdapter.create_visual_model(npc, self.manifest, observer_rel=rel_hostile)
        self.assertEqual(visual_hostile["expression"], "HOSTILE")
        self.assertEqual(visual_hostile["tint_color"], ChimeraSpritePalette.TINT_HOSTILE)
        self.assertTrue(visual_hostile["needs_ink_wash"])

    def test_map_and_economic_adapters(self):
        world = init_village_world()
        square_model = MapVisualAdapter.create_node_model("village_square", "Village Square", world, self.manifest)
        self.assertEqual(square_model["state"], "ACTIVE")
        self.assertEqual(square_model["occupant_count"], 20)
        self.assertIn("npc_merchant_token", square_model["occupant_tokens"])

        bread_model = ItemVisualAdapter.create_item_model("bread", world, self.manifest, rarity="COMMON")
        self.assertEqual(bread_model["display_name"], "Bread")
        self.assertEqual(bread_model["price"], 5.0)
        self.assertEqual(bread_model["item_sprite"], "camp_item_bread")
        self.assertEqual(bread_model["rarity_seal"], "ui_seal_common")

    def test_village_dashboard_adapter(self):
        world = init_village_world()
        world.npcs["merchant"].is_alive = False
        world.events.append("NpcKilled", 1440, ["miner_derek", "merchant"], ["apprentice"], "Merchant died")

        dash = VillageDashboardAdapter.create_dashboard(world, self.manifest)
        self.assertEqual(dash["total_npcs"], 20)
        self.assertEqual(dash["alive"], 19)
        self.assertEqual(dash["dead"], 1)
        self.assertEqual(len(dash["recent_events"]), 1)
        self.assertTrue(dash["recent_events"][0]["is_violent"])

    def test_sprints_1_to_6_tactical_combat_save_migration_gguf_quests_and_90d_soak(self):
        w = init_village_world()
        derek = w.npcs["miner_derek"]
        alden = w.npcs["merchant"]

        # Sprint 1: Tactical combat stance matrix (STRIKE beats FEINT, WARD vs FEINT = GUARD_BREAK)
        ex1 = TacticalCombatResolver.resolve_exchange(
            derek, alden, tick=60,
            override_atk=TacticalStance.STRIKE, override_def=TacticalStance.FEINT
        )
        self.assertEqual(ex1["outcome"], "COUNTER_STRIKE")
        self.assertGreater(ex1["final_damage"], ex1["base_damage"])

        ex2 = TacticalCombatResolver.resolve_exchange(
            derek, alden, tick=60,
            override_atk=TacticalStance.WARD, override_def=TacticalStance.FEINT
        )
        self.assertEqual(ex2["outcome"], "GUARD_BREAK")

        # Sprint 2: Save schema migration v1 -> v2 -> v3 + RT-12 relationship/faction tamper detection
        raw_v1 = SaveSchemaMigrator.serialize_v1_legacy(w)
        migrated_v3 = SaveSchemaMigrator.migrate_to_v3(raw_v1)
        self.assertEqual(migrated_v3["schemaVersion"], 3)
        self.assertEqual(len(migrated_v3["migrationHistory"]), 2)

        tampered = json.loads(json.dumps(migrated_v3))
        tampered["worldState"]["npcs"]["merchant"]["coins"] = 99999.0
        with self.assertRaises(ValueError):
            SaveSchemaMigrator.migrate_to_v3(json.dumps(tampered))

        # Sprint 3: Bundled GGUF binary header validation + RT-13 tensor count upper-bound check
        model_path = Path("assets/models/smollm2-135m-q4_k_m.gguf")
        self.assertTrue(model_path.exists())
        header_bytes = model_path.read_bytes()[:24]
        parsed = GgufBinaryHeaderParser.parse_header_bytes(header_bytes, model_path.stem)
        self.assertEqual(parsed["magic"], "GGUF")

        overflow_header = b"GGUF" + struct.pack("<IQQ", 3, 999_999_999, 10)
        with self.assertRaises(ValueError):
            GgufBinaryHeaderParser.parse_header_bytes(overflow_header)

        # Sprint 5 & RT-15/RT-16: Procedural Quest Engine deduplication & trade completion reward
        w_quest = init_village_world()
        w_quest.scarcity_factors["bread"] = 1.85
        dispatched1 = ProceduralQuestEngine.dispatch_emergent_opportunities(w_quest)
        dispatched2 = ProceduralQuestEngine.dispatch_emergent_opportunities(w_quest)
        self.assertEqual(len(dispatched1), 1)
        self.assertEqual(len(dispatched2), 0, "RT-15: Duplicate quest opportunities must not be dispatched")
        bonus = ProceduralQuestEngine.complete_scarcity_trade(w_quest, "bread", 9.0)
        self.assertEqual(bonus, 45.0, "RT-16: Completing scarcity trade awards quest bounty")

        # Sprint 6 & RT-17: Interactive Tactical Duel Simulator & dead-combatant guard
        w_duel = init_village_world()
        duel_sim = TacticalDuelSimulator(w_duel, "miner_derek")
        for _ in range(6):
            duel_sim.execute_round(TacticalStance.STRIKE)
        self.assertFalse(w_duel.npcs["miner_derek"].is_alive)
        rounds_at_death = duel_sim.round_count
        self.assertIsNone(duel_sim.execute_round(TacticalStance.FEINT), "RT-17: Deceased opponent cannot fight")
        self.assertEqual(duel_sim.round_count, rounds_at_death)

        # Sprint 4: 90-day (2,160-tick) autonomous soak & RT-14 closed-loop food ration equilibrium
        soak1 = run_90_day_soak_simulation(days=90)
        soak2 = run_90_day_soak_simulation(days=90)
        self.assertEqual(soak1["ticks_simulated"], 129600)
        self.assertEqual(soak1["alive_count"], 19)
        self.assertGreaterEqual(soak1["min_food"], 3.0)
        self.assertEqual(soak1["fingerprint"], soak2["fingerprint"])

if __name__ == "__main__":
    unittest.main()
