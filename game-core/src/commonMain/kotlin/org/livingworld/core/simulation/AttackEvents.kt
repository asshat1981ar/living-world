package org.livingworld.core.simulation

import org.livingworld.core.events.GameEvent
import org.livingworld.core.world.WorldReducer
import org.livingworld.core.world.WorldState

/**
 * The one place an attack becomes events. A lethal NpcAttacked only lowers health (the reducer applies events, it does
 * not emit them), and `Npc.isAlive` already reads false at 0 health, so a caller that checked it would never record the
 * death. Every caller goes through here instead, so a fatal blow always gets its NpcKilled (G4), caused by the blow.
 */
object AttackEvents {
    fun apply(reducer: WorldReducer, state: WorldState, attack: GameEvent.NpcAttacked, motive: String): WorldState {
        val afterAttack = reducer.apply(state, attack)
        val target = afterAttack.npcs[attack.target] ?: return afterAttack
        // identity.isAlive is false only once an NpcKilled has been applied, so a death is never recorded twice.
        if (target.resources.health > 0f || !target.identity.isAlive) return afterAttack
        val kill = GameEvent.NpcKilled(
            id = afterAttack.events.nextId(),
            time = attack.time,
            victim = attack.target,
            killer = attack.attacker,
            witnesses = attack.witnesses,
            causalEventId = attack.id,
            location = attack.location,
            motive = motive
        )
        return reducer.apply(afterAttack, kill)
    }
}
