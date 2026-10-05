package dev.charaly.runtime.domain

import kotlinx.serialization.Serializable

/**
 * A narrative thread with enough structure to be worth keeping.
 *
 * ## Why a thread is more than a stage number
 *
 * The original thread held a `stage` and little else, which is enough to say "something
 * is going on" and not enough to answer the questions a story actually asks:
 *
 *  * what is this thread *for*?
 *  * how far along is it, as opposed to how many beats have it had?
 *  * what would finish it?
 *  * what happens if I walk away from it?
 *
 * Without [progress] and [resolutionCondition] a thread can only ever be "at stage
 * two", which is a counter rather than a piece of story. And without [consequenceIfAbandoned]
 * there is no answer to "can I stop caring about this", which is a question players ask
 * constantly.
 *
 * ## Progress is not stage
 *
 * [stage] counts beats and is what an event advances. [progress] is 0..100 and means
 * "how close is this to being finished". They move together in a well-run thread and
 * diverge in a sprawling one, which is exactly the difference between a mystery with a
 * solution and a series of things that happen.
 */
@Serializable
data class StoryThread(
    val id: ThreadId,
    val title: String,

    /** What kind of thread this is. Drives which checks apply to it. */
    val kind: ThreadKind = ThreadKind.MYSTERY,

    val status: StoryThreadStatus = StoryThreadStatus.DORMANT,
    val stage: Int = 0,

    /** 0..100. How close to finished. Distinct from [stage], which counts beats. */
    val progress: Int = 0,

    /**
     * How much this thread wants the player's attention, 0..100.
     *
     * Used for context budgeting: when two threads are live and only one fits in the
     * prompt, the higher priority is the one that gets to be there. Without it,
     * relevance is decided by iteration order, which is author order.
     */
    val priority: Int = 50,

    val description: String = "",

    /**
     * The next thing that has to happen for this to move.
     *
     * Authored as prose because it is read by the model, and a model reads
     * "someone has to have seen the wing that night" better than a boolean.
     */
    val nextBeat: String = "",

    /**
     * What has to be true for this thread to be finished.
     *
     * Also prose, for the same reason. This is what lets the engine distinguish a thread
     * that is winding down from one that has simply gone quiet.
     */
    val resolutionCondition: String = "",

    /**
     * What happens to the world if this is dropped.
     *
     * Empty means "nothing happens", which is a legitimate and common answer - but it
     * has to be *stated*, because the alternative is that nobody decided and the
     * default quietly becomes "silence".
     */
    val consequenceIfAbandoned: String = "",

    val involvedCharacterIds: List<CharacterId> = emptyList(),
    val relevantLocationIds: List<LocationId> = emptyList(),

    /** Events that moved this thread, so "how did we get here" is answerable. */
    val relatedEventIds: List<EventId> = emptyList(),

    /** Memories that came out of this thread, so retrieval can favour them. */
    val relatedMemoryIds: List<MemoryId> = emptyList(),

    val state: Map<String, String> = emptyMap(),
    val updatedAt: StoryTime = StoryTime.START,
) {
    init {
        require(title.isNotBlank()) { "StoryThread $id needs a title" }
        require(stage >= 0) { "stage must not be negative" }
        require(progress in 0..100) { "progress must be 0..100, was $progress" }
        require(priority in 0..100) { "priority must be 0..100, was $priority" }
    }

    fun involves(characterId: CharacterId): Boolean = involvedCharacterIds.contains(characterId)

    fun touches(locationId: LocationId): Boolean = relevantLocationIds.contains(locationId)

    fun isOpen(): Boolean = status == StoryThreadStatus.ACTIVE || status == StoryThreadStatus.DORMANT

    fun isFinished(): Boolean = status == StoryThreadStatus.COMPLETED || status == StoryThreadStatus.FAILED

    /**
     * Whether this thread may be dropped without leaving a hole.
     *
     * Only ever true when the author said dropping it costs nothing. A thread that
     * simply never mentioned a consequence is *not* droppable, because silence is not
     * a decision and treating it as one is how a world quietly stops keeping score.
     */
    fun isDroppable(): Boolean = isOpen() && consequenceIfAbandoned.isBlank()

    /** A compact prompt line: where it is and what it needs. */
    fun promptLine(): String = buildString {
        append("$title (${kind.label}, $progress%")
        if (priority >= HIGH_PRIORITY) append(", important")
        append(")")
        if (nextBeat.isNotBlank()) append(" - next: $nextBeat")
        if (resolutionCondition.isNotBlank()) append(" - ends when: $resolutionCondition")
    }

    companion object {
        /** At or above this, a thread wins a contested context slot. */
        const val HIGH_PRIORITY = 70
    }
}

/**
 * What kind of thread this is.
 *
 * Not decoration: the kind decides which health checks apply. A mystery with no
 * progress for a week is suspicious; a romance arc with no progress for a week is a
 * romance arc. Judging both by the same rule produces a monitor people learn to ignore.
 */
@Serializable
enum class ThreadKind(val label: String) {
    /** A question with an answer. Must eventually resolve or be closed. */
    MYSTERY("a mystery"),

    /** Something a character wants. Progress is theirs, not the world's. */
    PERSONAL("something personal"),

    /** A relationship between two people. Moves when they interact. */
    RELATIONSHIP("a relationship"),

    /** Something the player must deal with. Consequences are the point. */
    QUEST("something to do"),

    /** Colour. No obligation, and saying so is the point. */
    AMBIENT("background"),

    /** A standing rule of the world rather than a plot. */
    LORE("a rule of the world"),
    ;

    /**
     * Whether going quiet in this thread is a problem.
     *
     * The single thing that makes the kind worth declaring.
     */
    val requiresProgress: Boolean
        get() = this == MYSTERY || this == QUEST

    /** Whether it is fair to warn about this thread going stale. */
    val stalenessMatters: Boolean
        get() = this != AMBIENT && this != LORE
}
/**
 * Where a thread is.
 *
 * [FAILED] is separate from [COMPLETED] deliberately: "this went wrong" is a different
 * kind of story from "this was finished", and a thread that quietly reported success
 * after its resolution condition was met by accident is how a mystery becomes a
 * non-mystery.
 */
@Serializable
enum class StoryThreadStatus {
    /** Declared but not yet in play. */
    DORMANT,

    /** In play right now. */
    ACTIVE,

    /** Finished, as intended. */
    COMPLETED,

    /** Ended without being finished. */
    FAILED,
    ;

    val isOpen: Boolean get() = this == ACTIVE || this == DORMANT
}
