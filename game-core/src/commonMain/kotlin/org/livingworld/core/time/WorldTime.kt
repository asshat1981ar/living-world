package org.livingworld.core.time

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@JvmInline
@Serializable
value class WorldTime(
    val tick: Long
) : Comparable<WorldTime> {

    operator fun plus(duration: WorldDuration): WorldTime =
        WorldTime(tick + duration.ticks)

    operator fun minus(duration: WorldDuration): WorldTime =
        WorldTime(tick - duration.ticks)

    operator fun minus(other: WorldTime): WorldDuration =
        WorldDuration(tick - other.tick)

    override fun compareTo(other: WorldTime): Int =
        tick.compareTo(other.tick)

    val day: Long get() = tick / TICKS_PER_DAY
    val hour: Int get() = ((tick % TICKS_PER_DAY) / TICKS_PER_HOUR).toInt()
    val minute: Int get() = (tick % TICKS_PER_HOUR).toInt()

    val phase: DayPhase
        get() = when (hour) {
            in 5..7 -> DayPhase.DAWN
            in 8..11 -> DayPhase.MORNING
            in 12..14 -> DayPhase.MIDDAY
            in 15..18 -> DayPhase.AFTERNOON
            in 19..21 -> DayPhase.DUSK
            else -> DayPhase.NIGHT
        }

    fun formatted(): String = "Day $day, ${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')} ($phase)"

    companion object {
        const val TICKS_PER_HOUR: Long = 60L
        const val TICKS_PER_DAY: Long = 1440L // 24 * 60

        val ZERO = WorldTime(0L)
    }
}

@JvmInline
@Serializable
value class WorldDuration(
    val ticks: Long
) : Comparable<WorldDuration> {

    operator fun plus(other: WorldDuration): WorldDuration =
        WorldDuration(ticks + other.ticks)

    operator fun minus(other: WorldDuration): WorldDuration =
        WorldDuration(ticks - other.ticks)

    operator fun times(scalar: Int): WorldDuration =
        WorldDuration(ticks * scalar)

    override fun compareTo(other: WorldDuration): Int =
        ticks.compareTo(other.ticks)

    companion object {
        fun ticks(count: Long): WorldDuration = WorldDuration(count)
        fun minutes(count: Long): WorldDuration = WorldDuration(count)
        fun hours(count: Long): WorldDuration = WorldDuration(count * WorldTime.TICKS_PER_HOUR)
        fun days(count: Long): WorldDuration = WorldDuration(count * WorldTime.TICKS_PER_DAY)
        fun weeks(count: Long): WorldDuration = WorldDuration(count * WorldTime.TICKS_PER_DAY * 7L)

        val ZERO = WorldDuration(0L)
    }
}

enum class DayPhase {
    DAWN,
    MORNING,
    MIDDAY,
    AFTERNOON,
    DUSK,
    NIGHT
}
