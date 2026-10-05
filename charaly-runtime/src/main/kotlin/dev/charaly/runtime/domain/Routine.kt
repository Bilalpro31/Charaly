package dev.charaly.runtime.domain

import kotlinx.serialization.Serializable

/**
 * A character is a persistent world entity, not a chat bot.
 *
 * This is the distinction that makes Charaly a world simulator rather than a
 * multi-character chat: a [dev.charaly.runtime.domain.CharacterRole.NPC] exists in the
 * world because the Story Pack says he does. He has a location, a routine,
 * relationships, knowledge and memories whether or not the player has ever
 * addressed him, and the player meets him by *walking into his shop*, not by adding
 * him to a conversation.
 */
@Serializable
enum class CharacterRole {
    /** Core cast: the story is about them. Always has full presence and state. */
    PROTAGONIST,

    /**
     * A meaningful recurring NPC.
     *
     * Named, scheduled, has relationships, knowledge and memory. Andre, the school
     * principal, a museum guide. The user never has to "add" one of these to a chat;
     * they are simply in the world.
     */
    NPC,

    /**
     * Background population - a crowd.
     *
     * Has no individual identity, schedule, knowledge or memory. The world may refer
     * to them collectively ("several students"), and that is all. Keeping this a
     * distinct role is what stops the world from accumulating dozens of meaningless
     * characters with dead state.
     */
    BACKGROUND,
    ;

    /** Whether this character owns individual runtime state that the engine tracks. */
    val hasIndividualState: Boolean
        get() = this != BACKGROUND

    /** Whether this character has a daily routine the engine advances. */
    val isScheduled: Boolean
        get() = this == PROTAGONIST || this == NPC
}

/**
 * One entry of a daily routine: from [startMinuteOfDay] until the next entry, this
 * character belongs at [locationId], doing [activity].
 *
 * Minutes-of-day (0..1439) rather than a [StoryTime] on purpose: a routine recurs
 * every day of the story, so it must not carry a day number.
 */
@Serializable
data class RoutineEntry(
    val startMinuteOfDay: Int,
    val locationId: LocationId,
    val activity: CharacterActivity = CharacterActivity.IDLE,
    /** Human wording for the UI, e.g. "serving customers". Never parsed by the engine. */
    val activityLabel: String = "",
) {
    init {
        require(startMinuteOfDay in 0..MINUTES_IN_DAY) {
            "startMinuteOfDay must be 0..1439, was $startMinuteOfDay"
        }
        require(activityLabel.length <= 120) { "activityLabel is display text, not a paragraph" }
    }

    fun covers(time: StoryTime): Boolean = time.minuteOfDay >= startMinuteOfDay

    companion object {
        const val MINUTES_IN_DAY = 24 * 60

        /** `08:00 -> bakery`. */
        fun at(hour: Int, minute: Int = 0, locationId: LocationId, activity: CharacterActivity = CharacterActivity.IDLE, label: String = "") =
            RoutineEntry(
                startMinuteOfDay = hour * 60 + minute,
                locationId = locationId,
                activity = activity,
                activityLabel = label,
            )
    }
}

/** Where a character belongs at one moment of the day. */
@Serializable
data class RoutinePlacement(
    val locationId: LocationId,
    val activity: CharacterActivity,
    val activityLabel: String,
    /** Which entry produced this, for the developer inspector. */
    val fromEntryMinute: Int,
)

/**
 * A character's daily routine.
 *
 * Deterministic by construction: an entry applies from its start minute until the
 * next entry begins, and the last entry of the day wraps past midnight. There is no
 * probability, no randomness and no "maybe they stay late" - given the same story
 * time, the same character is in the same place.
 *
 * A routine with no entries means "this character is not scheduled", which is a
 * legitimate and common choice for a character who is only where the story puts them.
 */
@Serializable
data class Routine(
    /** Where this character sleeps. Used as the fallback before the first entry. */
    val homeLocationId: LocationId? = null,
    val entries: List<RoutineEntry> = emptyList(),
    /**
     * One line describing the shape of the day, for character profiles.
     * Presentation text only: the engine never reads it.
     */
    val summary: String = "",
) {
    init {
        val duplicates = entries.groupBy { it.startMinuteOfDay }.filterValues { it.size > 1 }
        require(duplicates.isEmpty()) {
            "Two routine entries start at the same minute: ${duplicates.keys.sorted()}"
        }
    }

    val isScheduled: Boolean get() = entries.isNotEmpty()

    /** Entries in chronological order; resolved once, never re-sorted per query. */
    val ordered: List<RoutineEntry> by lazy { entries.sortedBy { it.startMinuteOfDay } }

    /**
     * Where this character belongs at [time].
     *
     * Returns null when the character has no routine, which the caller must treat as
     * "leave them alone" - never as "send them somewhere".
     */
    fun resolve(time: StoryTime): RoutinePlacement? {
        if (ordered.isEmpty()) return null
        val minute = time.minuteOfDay
        // The last entry that has already started today wins.
        val active = ordered.lastOrNull { it.covers(time) }
        // Before the first entry of the day the character is still at their last
        // placement from yesterday, wrapping around to the final entry.
        val entry = active ?: ordered.last()
        return RoutinePlacement(
            locationId = entry.locationId,
            activity = entry.activity,
            activityLabel = entry.activityLabel,
            fromEntryMinute = entry.startMinuteOfDay,
        )
    }

    /**
     * Every distinct (location, activity) pair this routine can produce, in the order
     * the character visits them. Used by the pack UI to show "where Andre can be".
     */
    fun itinerary(): List<Pair<LocationId, String>> =
        ordered.map { it.locationId to it.activityLabel }
}

/** Minutes elapsed since midnight. The only time-of-day notion routines need. */
val StoryTime.minuteOfDay: Int
    get() = hour * 60 + minute