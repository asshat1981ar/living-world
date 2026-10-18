package org.livingworld.core.simulation

import org.livingworld.core.beliefs.KnowledgeSource
import org.livingworld.core.beliefs.Proposition
import org.livingworld.core.ids.EntityId
import org.livingworld.core.ids.ItemId
import org.livingworld.core.npc.ExpectedEffect
import org.livingworld.core.npc.Npc
import org.livingworld.core.npc.Risk
import org.livingworld.core.npc.Trait
import org.livingworld.core.world.WorldState

enum class DecisionTier {
    RULE,
    UTILITY,
    PLANNER,
    AI_REASONING
}

interface NpcDecisionEngine {
    suspend fun decide(
        npc: Npc,
        world: WorldState
    ): ValidatedAction
}

class DefaultNpcDecisionEngine(
    private val validator: ActionValidator = DefaultActionValidator()
) : NpcDecisionEngine {

    fun generateCandidates(
        npc: Npc,
        world: WorldState
    ): List<ActionCandidate> {
        val candidates = mutableListOf<ActionCandidate>()

        // 1. Survival rules: Rest
        candidates.add(
            ActionCandidate(
                action = Intent.Rest,
                expectedEffects = listOf(ExpectedEffect("Recover energy", 0.3f))
            )
        )

        // 2. Work / Routine
        candidates.add(
            ActionCandidate(
                action = Intent.Work(npc.identity.currentLocation),
                expectedEffects = listOf(ExpectedEffect("Earn livelihood", 0.5f))
            )
        )

        // 3. Social & Rumor sharing with co-located NPCs (sorted deterministically)
        val coLocatedNpcs = world.npcs.values
            .filter { it.id != npc.id && it.isAlive && it.identity.currentLocation == npc.identity.currentLocation }
            .sortedBy { it.id.value }

        for (other in coLocatedNpcs) {
            candidates.add(
                ActionCandidate(
                    action = Intent.Socialize(other.id),
                    expectedEffects = listOf(ExpectedEffect("Build relationship", 0.4f))
                )
            )

            // RT-4 Fix: Do not echo a hearsay rumor back to the original sourceNpc who told us.
            // G2: an NPC cannot read the listener's beliefs; it only remembers what it already told them.
            val shareableBelief = npc.beliefs.all()
                .filter { b -> (b.source as? KnowledgeSource.Hearsay)?.sourceNpc != other.id }
                .filter { b -> !npc.memories.hasTold(other.id, b.proposition) }
                .maxByOrNull { it.confidence * it.reliability }

            if (shareableBelief != null) {
                candidates.add(
                    ActionCandidate(
                        action = Intent.ShareRumor(other.id, shareableBelief),
                        expectedEffects = listOf(ExpectedEffect("Spread information", 0.45f))
                    )
                )
            }
        }

        // 4. RT-5 Fix: Apprentice shop succession if merchant predecessor died and shop not yet inherited
        if (npc.identity.occupation.contains("Apprentice", ignoreCase = true)) {
            // G2: the apprentice must know of the death (a belief), not read it off the world.
            val deceasedMerchant = npc.beliefs.all()
                .mapNotNull {
                    when (val p = it.proposition) {
                        is Proposition.EntityKilledNpc -> p.victim
                        is Proposition.NpcDied -> p.victim
                        else -> null
                    }
                }
                .sortedBy { it.value }
                .mapNotNull { world.npcs[it] }
                .firstOrNull { !it.isAlive && it.identity.occupation.contains("Merchant", ignoreCase = true) }
            val alreadyInherited = npc.beliefs.all().any { (it.proposition as? Proposition.ShopInherited)?.heir == npc.id }
            if (deceasedMerchant != null && !alreadyInherited) {
                candidates.add(
                    ActionCandidate(
                        action = Intent.InheritShop(deceasedMerchant.id, "Alden's Sundries"),
                        expectedEffects = listOf(ExpectedEffect("Assume stewardship of master's shop", 0.95f))
                    )
                )
            }
        }

        // 5. Trade if merchant or shopkeeper
        if (npc.identity.occupation.contains("Merchant", ignoreCase = true) ||
            npc.identity.occupation.contains("Shopkeeper", ignoreCase = true)) {
            candidates.add(
                ActionCandidate(
                    action = Intent.ChangePrice(ItemId("bread"), 18f, "Apprentice new management markup"),
                    expectedEffects = listOf(ExpectedEffect("Higher margins", 0.6f))
                )
            )
            // The player's purse is hidden; the validator rejects an unaffordable offer and the next candidate is used.
            if (world.player.currentLocation == npc.identity.currentLocation) {
                candidates.add(
                    ActionCandidate(
                        action = Intent.Trade(world.player.id, ItemId("goods"), 15f),
                        expectedEffects = listOf(ExpectedEffect("Profit from traveler", 0.7f))
                    )
                )
            }
        }

        // 6. Hostility / Faction Defense (reads the world relationship graph)
        for (other in coLocatedNpcs) {
            val rel = world.relationships.get(npc.id.toEntityId(), other.id.toEntityId())

            if (rel.resentment > 0.7f || rel.trust < -0.6f) {
                candidates.add(
                    ActionCandidate(
                        action = Intent.Attack(other.id, "Simmering resentment erupted into violence"),
                        risks = listOf(Risk("Injury or death", 0.5f, 0.9f)),
                        expectedEffects = listOf(ExpectedEffect("Retribution", 0.6f))
                    )
                )
            }
        }

        return candidates
    }

    override suspend fun decide(
        npc: Npc,
        world: WorldState
    ): ValidatedAction {
        if (npc.resources.health < 25f && npc.emotionalState.fear > 0.6f) {
            val fleeProposal = AiProposal(Intent.Flee(EntityId.Player("threat")), 1.0f, "Survival rule: health critical")
            val validated = validator.validate(fleeProposal, npc, world)
            if (validated.isSuccess) return validated.getOrThrow()
        }

        val candidates = generateCandidates(npc, world)
        val scored = candidates.map { candidate ->
            var score = candidate.estimatedUtility()

            if (npc.hasTrait(Trait.GREEDY) && (candidate.action is Intent.Trade || candidate.action is Intent.ChangePrice)) {
                score += 0.4f
            }
            if (npc.hasTrait(Trait.AMBITIOUS) && candidate.action is Intent.InheritShop) {
                score += 0.5f
            }
            if (npc.hasTrait(Trait.VENGEFUL) && candidate.action is Intent.Attack) {
                score += 0.5f
            }
            if (npc.hasTrait(Trait.COWARDLY) && candidate.action is Intent.Attack) {
                score -= 0.6f
            }
            if (npc.hasTrait(Trait.COMPASSIONATE) && candidate.action is Intent.Socialize) {
                score += 0.3f
            }

            Pair(candidate, score)
        }.sortedByDescending { it.second }

        for ((candidate, _) in scored) {
            val proposal = AiProposal(candidate.action, confidence = 0.85f, explanation = "Selected by utility engine")
            val validationResult = validator.validate(proposal, npc, world)
            if (validationResult.isSuccess) {
                return validationResult.getOrThrow()
            }
        }

        return ValidatedAction(Intent.Rest)
    }
}
