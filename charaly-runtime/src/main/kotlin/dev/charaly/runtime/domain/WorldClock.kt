package dev.charaly.runtime.domain

import kotlinx.serialization.Serializable

/**
 * Logical story time.
 *
 * Charaly uses a *story clock*, not wall clock time. The world only moves when
 * the runtime decides it moves (an event scheduled for later, a user driven
 * "advance 30 minutes" action, a scene ending). This keeps the whole simulation
 * deterministic and replayable, and keeps tests free of `System.currentTimeMillis`.
 */
@Serializable
data class StoryTime(
    val day: Int = 1,
    val hour: Int = 0,
    val minute: Int = 0,
) : Comparable<StoryTime> {
    init {
        require(day >= 1) { "day must be >= 1, was $day" }
        require(hour in 0..23) { "hour must be in 0..23, was $hour" }
        require(minute in 0..59) { "minute must be in 0..59, was $minute" }
    }

    val totalMinutes: Long get() = (day - 1L) * MINUTES_PER_DAY + hour * MINUTES_PER_HOUR + minute

    fun plusMinutes(minutes: Long): StoryTime {
        require(minutes >= 0) { "StoryTime does not move backwards (got -${-minutes})" }
        val total = totalMinutes + minutes
        return fromTotalMinutes(total)
    }

    fun minusMinutes(minutes: Long): StoryTime {
        require(minutes >= 0) { "minutes must not be negative" }
        return fromTotalMinutes((totalMinutes - minutes).coerceAtLeast(0L))
    }

    fun isBefore(other: StoryTime): Boolean = totalMinutes < other.totalMinutes

    fun isAfter(other: StoryTime): Boolean = totalMinutes > other.totalMinutes

    fun isAtOrBefore(other: StoryTime): Boolean = totalMinutes <= other.totalMinutes

    override fun compareTo(other: StoryTime): Int = totalMinutes.compareTo(other.totalMinutes)

    /** Stable duration between two story times, never negative. */
    fun minutesUntil(other: StoryTime): Long = (other.totalMinutes - totalMinutes).coerceAtLeast(0L)

    fun format(): String = "Day $day, ${"%02d".format(hour)}:${"%02d".format(minute)}"

    fun formatClock(): String = "%02d:%02d".format(hour, minute)

    companion object {
        const val MINUTES_PER_HOUR = 60L
        const val MINUTES_PER_DAY = 24L * MINUTES_PER_HOUR

        val START: StoryTime = StoryTime(day = 1, hour = 9, minute = 0)

        fun fromTotalMinutes(total: Long): StoryTime {
            require(total >= 0) { "total minutes must not be negative" }
            val day = (total / MINUTES_PER_DAY).toInt() + 1
            val rest = total % MINUTES_PER_DAY
            return StoryTime(day = day, hour = (rest / 60L).toInt(), minute = (rest % 60L).toInt())
        }

        fun of(day: Int, hour: Int, minute: Int): StoryTime = StoryTime(day, hour, minute)
    }
}

/** A small, explicit duration type. Charaly never uses wall-clock durations for world time. */
@Serializable
data class StoryDuration(val minutes: Long) : Comparable<StoryDuration> {
    init {
        require(minutes >= 0) { "StoryDuration must not be negative" }
    }

    override fun compareTo(other: StoryDuration): Int = minutes.compareTo(other.minutes)

    fun toStoryTime(from: StoryTime): StoryTime = from.plusMinutes(minutes)

    companion object {
        val ZERO = StoryDuration(0)
        fun minutes(value: Long) = StoryDuration(value)
        fun hours(value: Long) = StoryDuration(value * 60L)
    }
}

/**
 * Deterministic world clock.
 *
 * The clock owns *time arithmetic only*. Scheduled work lives in the
 * [dev.charaly.runtime.engine.EventEngine] event queue, which asks the clock what
 * time it is; the clock never mutates the world by itself.
 */
@Serializable
data class WorldClock(val now: StoryTime = StoryTime.START) {

    fun advance(by: StoryDuration): WorldClock =
        copy(now = now.plusMinutes(by.minutes))

    /** Absolute scheduling helper: at which story time does a delay elapse? */
    fun scheduleAfter(by: StoryDuration): StoryTime = now.plusMinutes(by.minutes)

    /** Absolute scheduling helper: normalises any story time to the future. */
    fun scheduleAt(time: StoryTime): StoryTime =
        if (time.isBefore(now)) now.plusMinutes(1) else time

    fun advanceTo(time: StoryTime): WorldClock =
        if (time.isAfter(now)) copy(now = time) else this

    /** How many story minutes elapse between now and [time]. */
    fun minutesUntil(time: StoryTime): Long = now.minutesUntil(time)

    /** How many story minutes have elapsed since [time]. */
    fun minutesSince(time: StoryTime): Long = time.minutesUntil(now)
}
