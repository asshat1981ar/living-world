package org.livingworld.core

import org.livingworld.core.events.DuelOutcome
import org.livingworld.core.events.TacticalCombatResolver
import org.livingworld.core.events.TacticalStance
import org.livingworld.core.ids.*
import org.livingworld.core.time.WorldTime
import kotlin.test.*

/** The stance matrix and damage resolution are pure and deterministic (G8). */
class TacticalCombatTest {
    @Test
    fun tactical_combat_stance_matrix_and_weapon_bonus_resolve_deterministically() {
        val world = TestWorld.create()
        val captain = world.npc(NpcId("guard_captain"))
        val merchant = world.npc(NpcId("merchant"))

        val counterExchange = TacticalCombatResolver.resolveExchange(
            attacker = captain,
            defender = merchant,
            time = WorldTime(60L),
            overrideAttackerStance = TacticalStance.STRIKE,
            overrideDefenderStance = TacticalStance.FEINT
        )
        assertEquals(DuelOutcome.COUNTER_STRIKE, counterExchange.outcome)
        assertTrue(counterExchange.finalDamage > counterExchange.baseDamage)

        val parriedExchange = TacticalCombatResolver.resolveExchange(
            attacker = captain,
            defender = merchant,
            time = WorldTime(60L),
            overrideAttackerStance = TacticalStance.STRIKE,
            overrideDefenderStance = TacticalStance.WARD
        )
        assertEquals(DuelOutcome.PARRIED, parriedExchange.outcome)
        assertEquals(counterExchange.baseDamage * 0.5f, parriedExchange.finalDamage)

        val armedCaptain = captain.copy(resources = captain.resources.copy(items = listOf(ItemId("iron_sword"))))
        val armedExchange = TacticalCombatResolver.resolveExchange(
            attacker = armedCaptain,
            defender = merchant,
            time = WorldTime(60L),
            overrideAttackerStance = TacticalStance.STRIKE,
            overrideDefenderStance = TacticalStance.STRIKE
        )
        assertEquals(45f, armedExchange.baseDamage)
    }
}
