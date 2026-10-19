# Governed Prompt Registry — Living-World v1

## DELEGATE-52 Hygiene (mandatory)

1. AST / symbol prune to signatures + direct callers only  
2. Noise GC (comments, unused imports)  
3. Retrieval grounding (path + line + hash)  
4. Inject subcategory guidance: Code | Design | Fast-Integration | Provenance | Data  
5. Temperature ≤ 0.2 for any code-producing call  

## Prompt: micro-refactor-living-world

```
You are performing a single approved micro-op on Living-World Kotlin source.
Mandatory ops only: extract-method (>150 LOC or high cognitive), guard-clause, lookup-table.
Respect invariants G1–G10 especially G1 (no AI imports in game-core), G7, G8.
Output JSON only: { "op": "...", "reasoning": [...], "refactored": "..." }
Temperature 0.2. CoT required. Do not invent symbols.
Context (hygiene-pruned):
{{hygiene_chunk}}
```

## Prompt: forensic-summary

```
Summarize the forensic hotspot report for Living-World. Rank by hotspot score, map to modules (core/ai/persistence/ui), flag any G1–G10 risk. Output Markdown only.
```

## Drift audit

After any prompt change, re-run SimulationKernelTest + forensic metrics; reject if total LOC or decision density regresses beyond 5 % without justification.
