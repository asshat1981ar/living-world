---
name: c4-snapshot
description: Regenerate the living-world C4 architecture snapshot (context, containers, key components, invariants) as c4/c4-snapshot-<version>.md in Mermaid. Use when modules or dependencies change, or when asked for an architecture diagram.
argument-hint: "[version-label]"
---

Build the snapshot from the source of truth, not from memory:

1. **Containers**: `settings.gradle.kts` `include(...)` lines. **Relations**: each module's `build.gradle.kts` `project(":…")` dependencies. Draw every edge and its direction; if `game-core` ever depends on `game-ai`, mark it as a **G1 violation** in red text.
2. **External systems**: the optional LLM via `AiReasoningPort` (HuggingFace router, on-device GGUF via `AndroidLlamaEngineBridge`). The deterministic fallback is required (G7).
3. **Key components** of `game-core`: list the packages under `game-core/src/commonMain/kotlin/org/livingworld/core/` with a one-line role each, read from the code.
4. Keep the structure of `c4/c4-snapshot-v6.md`: Context (`C4Context`), Containers (`C4Container`), Key Components, Invariants G1–G10, and Hotspot priorities (top 5 from the newest forensic report).
5. Write `c4/c4-snapshot-$ARGUMENTS.md`. Never overwrite an older snapshot.
