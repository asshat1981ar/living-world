package org.livingworld.core.factions

import kotlinx.serialization.Serializable
import org.livingworld.core.ids.FactionId
import org.livingworld.core.ids.NpcId
import org.livingworld.core.npc.Goal
import org.livingworld.core.npc.ResourceState

@Serializable
data class Ideology(
    val name: String,
    val coreValues: List<String> = emptyList(),
    val hostilityThreshold: Float = 0.5f,
    val description: String = ""
)

@Serializable
data class Conflict(
    val id: String,
    val description: String,
    val severity: Float = 0.5f,
    val partiesInvolved: List<NpcId> = emptyList()
)

@Serializable
data class LeadershipState(
    val leader: NpcId?,
    val successionLine: List<NpcId> = emptyList(),
    val stability: Float = 1.0f // 0.0 to 1.0
)

@Serializable
data class Faction(
    val id: FactionId,
    val name: String,
    val ideology: Ideology,
    val goals: List<Goal> = emptyList(),
    val members: Set<NpcId> = emptySet(),
    val resources: ResourceState = ResourceState(),
    val allies: Set<FactionId> = emptySet(),
    val enemies: Set<FactionId> = emptySet(),
    val internalConflicts: List<Conflict> = emptyList(),
    val leadership: LeadershipState = LeadershipState(null)
) {
    fun isEnemy(factionId: FactionId): Boolean = enemies.contains(factionId)
    fun isAlly(factionId: FactionId): Boolean = allies.contains(factionId)
    fun hasMember(npcId: NpcId): Boolean = members.contains(npcId)

    fun addMember(npcId: NpcId): Faction =
        copy(members = members + npcId)

    fun removeMember(npcId: NpcId): Faction =
        copy(
            members = members - npcId,
            leadership = if (leadership.leader == npcId) {
                val nextLeader = leadership.successionLine.firstOrNull { it != npcId }
                leadership.copy(
                    leader = nextLeader,
                    successionLine = leadership.successionLine.filter { it != npcId }
                )
            } else {
                leadership.copy(successionLine = leadership.successionLine.filter { it != npcId })
            }
        )

    fun declareHostility(target: FactionId): Faction =
        copy(
            enemies = enemies + target,
            allies = allies - target
        )
}
