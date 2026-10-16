import unittest
import json

ON_DEVICE_CATALOG = [
    {"id": "HuggingFaceTB/SmolLM2-135M-Instruct", "family": "SmolLM2", "ram_mb": 85, "tokens": 2048},
    {"id": "HuggingFaceTB/SmolLM2-360M-Instruct", "family": "SmolLM2", "ram_mb": 220, "tokens": 4096},
    {"id": "Qwen/Qwen2.5-0.5B-Instruct", "family": "Qwen2.5", "ram_mb": 340, "tokens": 32768},
    {"id": "meta-llama/Llama-3.2-1B-Instruct", "family": "Llama 3.2", "ram_mb": 720, "tokens": 131072},
    {"id": "HuggingFaceTB/SmolLM2-1.7B-Instruct", "family": "SmolLM2", "ram_mb": 980, "tokens": 8192}
]

def find_models_for_ram_budget(max_mb):
    return [m for m in ON_DEVICE_CATALOG if m["ram_mb"] <= max_mb]

def extract_last_json_object(raw: str):
    """Balanced JSON object extractor matching DeterministicAiFallback.extractLastJsonObject (RT-7)."""
    import re
    cleaned = re.sub(r"<think>[\s\S]*?</think>", "", raw).strip()
    depth = 0
    last_start = -1
    best_json = None
    in_string = False
    escaped = False
    for i, c in enumerate(cleaned):
        if escaped:
            escaped = False
            continue
        if c == "\\" and in_string:
            escaped = True
            continue
        if c == '"':
            in_string = not in_string
            continue
        if not in_string:
            if c == "{":
                if depth == 0:
                    last_start = i
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0 and last_start != -1:
                    best_json = cleaned[last_start:i + 1]
                    last_start = -1
    return best_json

class HuggingFaceEndpointConfig:
    def __init__(self, model_id="Qwen/Qwen2.5-7B-Instruct", api_token=None,
                 endpoint_url="https://router.huggingface.co/hf-inference/v1/chat/completions",
                 is_free_space=False, space_url=None):
        self.model_id = model_id
        self.api_token = api_token
        self.endpoint_url = endpoint_url
        self.is_free_space = is_free_space
        self.space_url = space_url

    @property
    def effective_url(self):
        if self.is_free_space and self.space_url:
            return self.space_url
        return self.endpoint_url

class HuggingFaceReasoningSimulator:
    def __init__(self, config, transport_fn=None):
        self.config = config
        self.transport_fn = transport_fn

    def build_prompt(self, npc_name, occupation, candidates, beliefs):
        prompt = f"NPC: {npc_name} ({occupation})\nKnown Beliefs: {', '.join(beliefs)}\nCandidates:\n"
        for i, c in enumerate(candidates):
            prompt += f"[{i}] {c}\n"
        return prompt

    def reason(self, npc_name, occupation, candidates, beliefs):
        if not candidates:
            return {"action": "Rest", "confidence": 1.0, "source": "fallback"}

        prompt = self.build_prompt(npc_name, occupation, candidates, beliefs)
        headers = {"Content-Type": "application/json"}
        if self.config.api_token:
            headers["Authorization"] = f"Bearer {self.config.api_token}"

        if not self.transport_fn:
            return {"action": candidates[0], "confidence": 0.8, "source": "deterministic_fallback"}

        try:
            resp_str = self.transport_fn(self.config.effective_url, prompt, headers)
            json_sub = extract_last_json_object(resp_str) or resp_str
            data = json.loads(json_sub)
            idx = data.get("selected_index", data.get("action_index", 0))
            if 0 <= idx < len(candidates):
                return {
                    "action": candidates[idx],
                    "confidence": data.get("confidence", 0.85),
                    "explanation": data.get("explanation"),
                    "source": f"huggingface:{self.config.model_id}"
                }
            return {"action": candidates[0], "confidence": 0.8, "source": "deterministic_fallback"}
        except Exception:
            return {"action": candidates[0], "confidence": 0.8, "source": "deterministic_fallback"}

class LocalOnDeviceSimulator:
    def __init__(self, model_spec, is_loaded=True, completion_fn=None):
        self.model_spec = model_spec
        self.is_loaded = is_loaded
        self.completion_fn = completion_fn

    def reason(self, npc_name, occupation, candidates, beliefs):
        if not self.is_loaded or not candidates:
            return {"action": candidates[0] if candidates else "Rest", "confidence": 0.8, "source": "deterministic_fallback"}

        if not self.completion_fn:
            return {"action": candidates[0], "confidence": 0.8, "source": "deterministic_fallback"}

        try:
            raw_out = self.completion_fn(npc_name, occupation, candidates, beliefs)
            json_sub = extract_last_json_object(raw_out) or raw_out
            data = json.loads(json_sub)
            idx = data.get("selected_index", data.get("action_index", 0))
            if 0 <= idx < len(candidates):
                return {
                    "action": candidates[idx],
                    "confidence": data.get("confidence", 0.85),
                    "explanation": data.get("explanation"),
                    "source": f"local:{self.model_spec['id']}"
                }
            return {"action": candidates[0], "confidence": 0.8, "source": "deterministic_fallback"}
        except Exception:
            return {"action": candidates[0], "confidence": 0.8, "source": "deterministic_fallback"}

class TieredReasoningSimulator:
    def __init__(self, local_sim=None, remote_sim=None):
        self.local_sim = local_sim
        self.remote_sim = remote_sim

    def reason(self, npc_name, occupation, candidates, beliefs):
        if self.local_sim and self.local_sim.is_loaded:
            prop = self.local_sim.reason(npc_name, occupation, candidates, beliefs)
            if "fallback" not in prop.get("source", "").lower():
                return prop

        if self.remote_sim:
            prop = self.remote_sim.reason(npc_name, occupation, candidates, beliefs)
            if "fallback" not in prop.get("source", "").lower():
                return prop

        return {"action": candidates[0] if candidates else "Rest", "confidence": 0.8, "source": "deterministic_fallback"}

class TestHuggingFaceIntegration(unittest.TestCase):
    def test_endpoint_config_and_spaces(self):
        cfg = HuggingFaceEndpointConfig()
        self.assertEqual(cfg.model_id, "Qwen/Qwen2.5-7B-Instruct")
        self.assertIn("router.huggingface.co", cfg.effective_url)

        space_cfg = HuggingFaceEndpointConfig(is_free_space=True, space_url="https://livingworld-rpg.hf.space/api/predict")
        self.assertEqual(space_cfg.effective_url, "https://livingworld-rpg.hf.space/api/predict")

    def test_remote_reasoning_with_mock_transport(self):
        cfg = HuggingFaceEndpointConfig(api_token="hf_mock_123")
        def mock_transport(url, prompt, headers):
            self.assertEqual(headers.get("Authorization"), "Bearer hf_mock_123")
            return json.dumps({
                "selected_index": 1,
                "confidence": 0.93,
                "explanation": "Trading bread aligns with merchant greed."
            })

        sim = HuggingFaceReasoningSimulator(cfg, transport_fn=mock_transport)
        res = sim.reason("Alden", "Merchant", ["Rest", "Trade(bread)", "Work"], ["Bread is scarce"])
        self.assertEqual(res["action"], "Trade(bread)")
        self.assertAlmostEqual(res["confidence"], 0.93)
        self.assertIn("huggingface:", res["source"])

    def test_offline_fallback_invariant_g7(self):
        cfg = HuggingFaceEndpointConfig()
        sim = HuggingFaceReasoningSimulator(cfg, transport_fn=None)
        res = sim.reason("Alden", "Merchant", ["Rest", "Trade(bread)"], [])
        self.assertEqual(res["action"], "Rest")
        self.assertEqual(res["source"], "deterministic_fallback")

    def test_on_device_catalog_memory_filtering(self):
        ultra_light = find_models_for_ram_budget(250)
        self.assertEqual(len(ultra_light), 2)
        mid_tier = find_models_for_ram_budget(750)
        self.assertEqual(len(mid_tier), 4)

    def test_tiered_resilient_pipeline(self):
        smol_spec = ON_DEVICE_CATALOG[0]
        local_sim = LocalOnDeviceSimulator(
            smol_spec,
            is_loaded=True,
            completion_fn=lambda n, o, c, b: json.dumps({"selected_index": 2, "confidence": 0.89})
        )
        remote_sim = HuggingFaceReasoningSimulator(HuggingFaceEndpointConfig())
        tiered = TieredReasoningSimulator(local_sim=local_sim, remote_sim=remote_sim)

        res = tiered.reason("Vane", "Guard Captain", ["Rest", "Patrol", "Investigate Theft"], ["Sword stolen"])
        self.assertEqual(res["action"], "Investigate Theft")
        self.assertEqual(res["source"], "local:HuggingFaceTB/SmolLM2-135M-Instruct")

        local_unloaded = LocalOnDeviceSimulator(smol_spec, is_loaded=False)
        tiered_fallback = TieredReasoningSimulator(local_sim=local_unloaded, remote_sim=remote_sim)
        res_fb = tiered_fallback.reason("Vane", "Guard Captain", ["Rest", "Patrol"], [])
        self.assertEqual(res_fb["source"], "deterministic_fallback")

    def test_red_team_rt7_think_tags_and_prose_braces_json_extraction(self):
        smol_spec = ON_DEVICE_CATALOG[2]
        chatty_output = (
            "<think>Evaluating {candidate 0} vs {candidate 1}...</think>\n"
            "Note {internal}: choosing index 1.\n"
            "{\"action_index\": 1, \"confidence\": 0.91, \"explanation\": \"Optimal choice\"}"
        )
        local_sim = LocalOnDeviceSimulator(
            smol_spec,
            is_loaded=True,
            completion_fn=lambda n, o, c, b: chatty_output
        )
        res = local_sim.reason("Alden", "Merchant", ["Rest", "Trade(bread)"], ["High demand"])
        self.assertEqual(res["action"], "Trade(bread)")
        self.assertAlmostEqual(res["confidence"], 0.91)

if __name__ == "__main__":
    unittest.main()
