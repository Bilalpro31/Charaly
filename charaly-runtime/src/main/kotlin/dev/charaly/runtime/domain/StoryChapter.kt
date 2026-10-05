package dev.charaly.runtime.domain

import kotlinx.serialization.Serializable

/**
 * A chapter of a running story.
 *
 * A session is not a transcript: it is a sequence of chapters. Chapters are
 * derived from scenes (see `dev.charaly.runtime.engine.ChapterPlanner`), so they
 * stay consistent with authoritative world state instead of being guessed from
 * prose. A chapter is closed when the scene that created it ends.
 */
@Serializable
data class StoryChapter(
    val id: String,
    val index: Int,
    val title: String,
    val summary: String = "",
    val locationId: LocationId? = null,
    val participantIds: List<CharacterId> = emptyList(),
    val threadIds: List<ThreadId> = emptyList(),
    val startedAt: StoryTime = StoryTime.START,
    val endedAt: StoryTime? = null,
    val firstTurn: Int = 0,
    val lastTurn: Int = 0,
    val isCurrent: Boolean = true,
) {
    fun turnCount(): Int = (lastTurn - firstTurn).coerceAtLeast(0) + if (lastTurn > 0) 1 else 0

    fun label(): String = "Chapter $index"
}

object ChapterTitles {
    /**
     * Deterministic opening titles derived from the place, so a fresh story reads
     * like a story and not like "Scene 1".
     */
    fun first(locationName: String, focusName: String?): String = when {
        focusName.isNullOrBlank() -> "Arrival in $locationName"
        else -> "$focusName in $locationName"
    }

    fun at(locationName: String, focusName: String?): String = when {
        focusName.isNullOrBlank() -> "Later, in $locationName"
        else -> "$focusName, $locationName"
    }

    /** Compact deterministic summary used on chapter rows. */
    fun summary(locationName: String, participants: List<String>, turns: Int): String = buildString {
        append(locationName)
        if (participants.isNotEmpty()) {
            append(" · ")
            append(participants.take(3).joinToString(", "))
            if (participants.size > 3) append(" +${participants.size - 3}")
        }
        if (turns > 0) append(" · $turns turns")
    }
}