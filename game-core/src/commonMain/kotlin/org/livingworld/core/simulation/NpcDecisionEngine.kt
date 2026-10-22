package org.livingworld.core.simulation

import org.livingworld.core.beliefs.KnowledgeSource
import org.livingworld.core.beliefs.Proposition
import org.livingworld.core.ids.EntityId
import org.livingworld.core.ids.ItemId
import org.livingworld.core.memory.DefaultMemoryRetriever
import org.livingworld.core.memory.Memory
import org.livingworld.core.memory.MemoryContext
import org.livingworld.core.memory.MemoryRetriever
import org.livingworld.core.memory.RelationshipExperience
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

    /** The decision plus, for an AI-advised engine, what the AI did; the engine records non-null advice (P9). */
    suspend fun decideWithAdvice(npc: Npc, world: WorldState): Pair<ValidatedAction, AiAdvice?> = decide(npc, world) to null
}

class DefaultNpcDecisionEngine(
    private val validator: ActionValidator = DefaultActionValidator(),
    private val retriever: MemoryRetriever = DefaultMemoryRetriever()
) : NpcDecisionEngine {

    /**
     * What the NPC remembers about the other party nudges how it treats them: kindness and cooperation make
     * contact more attractive, assault, theft and betrayal make it less so and a violent act more so (a grudge).
     * Only the memories the retriever surfaces for this entity (most important, most recent) count, capped at +-0.6.
     */
    fun memoryBias(npc: Npc, action: Intent, world: WorldState): Float =
        memoryBias(npc, action, world, entitiesWithRelationshipMemory(npc))

    /** One pass over the NPC's memories, so a decision with many candidates does not rescan them for each one. */
    private fun entitiesWithRelationshipMemory(npc: Npc): Set<EntityId> =
        npc.memories.all().filterIsInstance<Memory.Relationship>().map { it.entity }.toSet()

    private fun memoryBias(npc: Npc, action: Intent, world: WorldState, remembered: Set<EntityId>): Float {
        val target: EntityId = when (action) {
            is Intent.Socialize -> action.target.toEntityId()
            is Intent.ShareRumor -> action.target.toEntityId()
            is Intent.Attack -> action.target.toEntityId()
            is Intent.Trade -> action.partner
            else -> return 0f
        }
        // No relationship memory about this party means nothing can survive the filter below, so skip ranking every memory.
        // Otherwise rank the whole store as before: unrelated memories can still crowd a weak one about the target out of the top N.
        if (target !in remembered) return 0f
        val recalled = retriever.retrieve(npc.memories, MemoryContext(world.time, focalEntities = setOf(target)), MEMORY_RECALL_LIMIT)
            .filterIsInstance<Memory.Relationship>()
            .filter { it.entity == target }
        var bias = 0f
        for (m in recalled) {
            val warmth = when (m.experience) {
                RelationshipExperience.KINDNESS, RelationshipExperience.COOPERATION -> 0.15f
                RelationshipExperience.SHARED_DANGER -> 0.1f
                RelationshipExperience.TRADE -> 0.05f
                RelationshipExperience.BETRAYAL, RelationshipExperience.ASSAULT -> -0.3f
                RelationshipExperience.THEFT -> -0.25f
            }
            bias += (if (action is Intent.Attack) -warmth * 0.7f else warmth) * m.importance
        }
        return bias.coerceIn(-0.6f, 0.6f)
    }

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
            val shareable = npc.beliefs.all()
                .filter { b -> (b.source as? KnowledgeSource.Hearsay)?.sourceNpc != other.id }
                .filter { b -> !npc.memories.hasTold(other.id, b.proposition) }
                .maxWithOrNull(compareBy<org.livingworld.core.beliefs.Belief> { newsworthiness(it, world.time) }
                    .thenByDescending { it.proposition.describe() })

            if (shareable != null) {
                candidates.add(
                    ActionCandidate(
                        action = Intent.ShareRumor(other.id, shareable),
                        expectedEffects = listOf(ExpectedEffect("Spread information", RUMOR_BASE_UTILITY + RUMOR_NEWS_WEIGHT * newsworthiness(shareable, world.time)))
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
                // Whether the death really happened is the validator's call (a false rumor is refused there); an
                // occupation is public knowledge in the village.
                .firstOrNull { it.identity.occupation.contains("Merchant", ignoreCase = true) }
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

        // 7. Politics. Both read only what the NPC itself feels (its own edges) or believes (G2).
        // Defection: a member who has lost faith in its faction's leader looks for a faction whose leader it does not
        // resent; needing that is what stops it from switching back and forth every hour.
        val home = npc.factions.firstOrNull()?.let { world.factions[it] }
        val homeLeader = home?.leadership?.leader
        if (home != null && homeLeader != null && homeLeader != npc.id) {
            val faith = world.relationships.get(npc.id.toEntityId(), homeLeader.toEntityId())
            if (faith.resentment > 0.7f || faith.trust < -0.6f) {
                val refuge = world.factions.values
                    .sortedBy { it.id.value }
                    .firstOrNull { f -> f.id != home.id && f.id !in npc.factions && leaderIsBearable(npc, f, world) }
                if (refuge != null) {
                    candidates.add(
                        ActionCandidate(
                            action = Intent.ChangeAllegiance(refuge.id, "Lost faith in ${homeLeader.value}"),
                            expectedEffects = listOf(ExpectedEffect("Leave a leader it cannot follow", DEFECTION_UTILITY))
                        )
                    )
                }
            }
        }
        // War: a leader who believes a member of another faction killed one of its own members takes the faction to war.
        for (faction in world.factions.values.sortedBy { it.id.value }) {
            if (faction.leadership.leader != npc.id) continue
            for (rival in world.factions.values.sortedBy { it.id.value }) {
                if (rival.id == faction.id || rival.id in faction.enemies) continue
                val grievance = npc.beliefs.all()
                    .filter { it.confidence >= 0.5f }
                    .mapNotNull { b ->
                        val p = b.proposition as? Proposition.EntityKilledNpc ?: return@mapNotNull null
                        val killerId = (p.killer as? EntityId.Npc)?.id
                        // A death removes the victim from Faction.members, so a member who died is still one of ours
                        // by the faction ids the dead NPC keeps (the same ones the death cascade reads).
                        val ours = p.victim in faction.members || faction.id in (world.npcs[p.victim]?.factions ?: emptySet())
                        if (ours && killerId != null && killerId in rival.members) p else null
                    }
                    .minByOrNull { it.describe() }
                if (grievance != null) {
                    candidates.add(
                        ActionCandidate(
                            action = Intent.DeclareHostility(faction.id, rival.id, grievance.victim, "Retaliation: ${grievance.describe()}"),
                            expectedEffects = listOf(ExpectedEffect("Avenge a killing", WAR_UTILITY))
                        )
                    )
                }
            }
        }

        return candidates
    }

    /** A faction's leader the NPC could live under: no leader at all, or one it neither resents nor distrusts. */
    private fun leaderIsBearable(npc: Npc, faction: org.livingworld.core.factions.Faction, world: WorldState): Boolean {
        val leader = faction.leadership.leader ?: return true
        val rel = world.relationships.get(npc.id.toEntityId(), leader.toEntityId())
        return rel.resentment <= 0.3f && rel.trust >= 0f
    }

    /** Rule-tier survival response that no advisor may override. */
    fun isSurvivalOverride(npc: Npc): Boolean = npc.resources.health < 25f && npc.emotionalState.fear > 0.6f

    override suspend fun decide(
        npc: Npc,
        world: WorldState
    ): ValidatedAction {
        if (isSurvivalOverride(npc)) {
            val fleeProposal = AiProposal(Intent.Flee(EntityId.Player("threat")), 1.0f, "Survival rule: health critical")
            val validated = validator.validate(fleeProposal, npc, world)
            if (validated.isSuccess) return validated.getOrThrow()
        }

        val candidates = generateCandidates(npc, world)
        val remembered = entitiesWithRelationshipMemory(npc)
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

            score += memoryBias(npc, candidate.action, world, remembered)

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

    /**
     * How worth telling a belief is right now: how sure and reliable it is, fading as it ages. Fresh, certain news
     * outranks routine work for about two days; stale or doubtful beliefs do not (otherwise the village would
     * gossip forever instead of working).
     */
    private fun newsworthiness(belief: org.livingworld.core.beliefs.Belief, now: org.livingworld.core.time.WorldTime): Float {
        val ageTicks = (now.tick - belief.acquiredAt.tick).coerceAtLeast(0L)
        val daysOld = ageTicks / org.livingworld.core.time.WorldTime.TICKS_PER_DAY.toFloat()
        return belief.confidence * belief.reliability / (1f + RUMOR_FADE_PER_DAY * daysOld)
    }

    private companion object {
        const val DEFECTION_UTILITY = 0.65f
        const val WAR_UTILITY = 0.8f
        const val RUMOR_BASE_UTILITY = 0.3f
        const val RUMOR_NEWS_WEIGHT = 0.4f
        const val RUMOR_FADE_PER_DAY = 0.25f
        const val MEMORY_RECALL_LIMIT = 8
    }
}
