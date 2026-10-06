package dev.charaly.runtime.presentation

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryTime
import dev.charaly.runtime.domain.StoryThread
import dev.charaly.runtime.domain.memory.Memory
import dev.charaly.runtime.domain.memory.MemoryTier

/**
 * A short, plain-language line about something the world did.
 *
 * ## Why these exist, and why there are so few
 *
 * The engine does a great deal of invisible work: a character learns something, a
 * relationship shifts, a memory is written, a thread advances. That work is what makes a
 * world feel alive, and it is entirely invisible - which is a problem, because a world
 * that changes without ever acknowledging it reads as broken.
 *
 * The fix is not a notification system. It is a small number of *earned* lines: "Marinette
 * remembered your promise", shown briefly, at the moment it happened.
 *
 * ## The spam budget is the design
 *
 * A naive implementation emits a line per change and produces a screen with a ticker
 * running down it, which the user learns to ignore - at which point the mechanism costs
 * attention and buys nothing.
 *
 * So every line has to earn its place:
 *
 *  * only *notable* categories produce one;
 *  * at most [StoryFeed.MAX_VISIBLE] are ever shown;
 *  * two lines of the same kind about the same subject collapse into the most recent;
 *  * and the transient path rate-limits, so a burst of ten events produces one line.
 *
 * ## What a line may and may not contain
 *
 * No ids, no scores, no event names, no "trust increased by 4". A line says what
 * happened in the language of the story. Every field a developer would want is in
 * [StoryFeedLine.debug], which only the developer inspector renders.
 */
data class StoryFeedLine(
    val id: String,
    /** What sort of change this was. Drives the icon and the accent. */
    val kind: FeedKind,
    /** The user-facing sentence. Story language, never system language. */
    val text: String,
    /** The subject, in the story's own terms. */
    val subject: String = "",
    /** When it happened, in story time: "Earlier today". */
    val relativeLabel: String = "",
    /** Story time of the underlying change, in minutes since the world began. */
    val eventMinutes: Long = 0L,
    /**
     * The engine facts behind the line.
     *
     * Populated unconditionally rather than on demand, because a debug view that shows
     * nothing once enabled is worse than one that was never offered.
     */
    val debug: DebugTrace = DebugTrace(),
) {
    data class DebugTrace(
        val source: String = "",
        val memoryId: String = "",
        val threadId: String = "",
        val characterId: String = "",
        val importance: Int = 0,
        val causalCause: String = "",
    )

    /** Whether this line carries engine detail worth showing in developer mode. */
    val isDebugVisible: Boolean get() = debug.source.isNotBlank()
}

/** What sort of change a line reports. Presentation only; never behaviour. */
enum class FeedKind {
    /** A character remembered something. */
    REMEMBERED,

    /** A relationship between two people shifted. */
    RELATIONSHIP,

    /** A promise, a goal, or a debt came due. */
    COMMITMENT,

    /** A story thread opened, moved or closed. */
    THREAD,

    /** Something in the world changed that the player will notice. */
    WORLD,

    /** The scene changed. */
    SCENE,

    /** A pack-authored event fired. */
    EVENT,
}

/**
 * Builds story-facing feed lines from authoritative world state.
 *
 * ## Why it reads state rather than subscribing to events
 *
 * Two reasons, and the second is the important one.
 *
 * The first is purity: this is a projection, so it can be rebuilt after a restart, it
 * cannot drift out of sync with the world, and it is testable with no UI.
 *
 * The second is that **world state does not retain the events that produced it** - it
 * keeps applied event *ids* for causality and replay, not the payloads. A push-based
 * feed would therefore be the only way to see a relationship change, and it would show
 * nothing at all after a restart. Deriving from state means the feed is correct for a
 * story that was closed and reopened, which is the normal case.
 *
 * The cost is honest and worth stating: a change that has since been *undone* produces
 * no line, and two changes within the same window are indistinguishable. Both are
 * acceptable, because the feed is ambient information rather than an audit trail - the
 * audit trail is the developer inspector's job.
 */
object StoryFeed {

    /** Never more than this many. A ticker is not a feed. */
    const val MAX_VISIBLE = 5

    /** Minimum gap in story minutes between two accepted transient lines. */
    const val RATE_LIMIT_MINUTES = 20

    /**
     * How far back a change still counts as "recent", in story hours.
     *
     * Two days of *story* time, which on a pack that advances a clock per scene is
     * roughly the current session and on a pack that advances it per conversation is
     * most of a day. Anything older has stopped being news.
     */
    const val RECENT_WINDOW_MINUTES = 48L * 60L

    /**
     * The world's recent changes, as user-facing lines.
     *
     * @param world the authoritative state. Read-only.
     * @param now the current story time. Defaults to the world's own clock, and is
     *   injectable so the recency window is testable without advancing a clock.
     */
    fun build(world: StoryInstance, now: StoryTime = world.worldClock.now): List<StoryFeedLine> {
        val lines = buildList {
            // --- memories: the most player-legible change there is ---------------
            world.memories.current()
                // `now - createdAt` rather than an `isAtOrBefore` guard, because a
                // memory stamped a little ahead of the clock is still news and dropping
                // it would lose the line exactly when the clock has just been advanced.
                // [relativeLabel] clamps a negative delta to "Just now" rather than
                // rendering a time in the future.
                .filter { (now.totalMinutes - it.createdAt.totalMinutes) <= RECENT_WINDOW_MINUTES }
                // A greeting is a memory with importance 1; it is not news.
                .filter { it.importance >= NOTABLE_IMPORTANCE }
                .filter { it.tier !in QUIET_TIERS }
                .sortedByDescending { it.createdAt.totalMinutes }
                .take(MAX_VISIBLE)
                .forEach { memory -> add(memoryLine(world, memory)) }

            // --- threads ---------------------------------------------------------
            world.storyThreads.values
                .filter { it.updatedAt.isAtOrBefore(now) }
                .filter { (now.totalMinutes - it.updatedAt.totalMinutes) <= RECENT_WINDOW_MINUTES }
                .filter { it.status != dev.charaly.runtime.domain.StoryThreadStatus.DORMANT }
                .sortedByDescending { it.updatedAt.totalMinutes }
                .take(MAX_VISIBLE)
                .forEach { thread -> add(threadLine(world, thread)) }

            // --- commitments -----------------------------------------------------
            //
            // Only *overdue* promises, and only ones the player is a party to. A promise
            // between two NPCs is world simulation, not something to interrupt the
            // player about: they cannot act on it and were never told about it.
            world.worldState.commitments.openPromisesInvolving(playerId)
                .filter { it.isOverdueAt(now) }
                .sortedBy { it.dueOnDay }
                .take(MAX_VISIBLE)
                .forEach { add(promiseLine(world, it)) }

            // --- relationships ---------------------------------------------------
            world.relationships.values
                .filter { it.updatedAt.isAtOrBefore(now) }
                .filter { (now.totalMinutes - it.updatedAt.totalMinutes) <= RECENT_WINDOW_MINUTES }
                .sortedByDescending { it.updatedAt.totalMinutes }
                .take(MAX_VISIBLE)
                .forEach { add(relationshipLine(world, it)) }
        }

        return deduplicate(lines)
            .sortedByDescending { it.eventMinutes }
            .take(MAX_VISIBLE)
    }

    /**
     * A line for one specific memory, for the transient toast after a turn.
     *
     * Returns null for anything not worth interrupting for. Null is the common case and
     * is not an error: most things a story records are not worth a toast.
     */
    fun lineForMemory(world: StoryInstance, memory: Memory): StoryFeedLine? {
        if (memory.importance < NOTABLE_IMPORTANCE) return null
        if (memory.tier in QUIET_TIERS) return null
        return memoryLine(world, memory)
    }

    /** A line for a thread that moved. Null when the thread has no title to show. */
    fun lineForThread(world: StoryInstance, thread: StoryThread): StoryFeedLine? {
        if (thread.title.isBlank()) return null
        return threadLine(world, thread)
    }

    /**
     * Whether a line may be shown right now.
     *
     * Split from building so the transient path can rate-limit without rebuilding the
     * whole feed.
     *
     * @param lastAccepted the previously shown line, if any.
     */
    fun rateLimit(
        lastAccepted: StoryFeedLine?,
        candidate: StoryFeedLine,
    ): Boolean {
        if (lastAccepted == null) return true
        val gap = candidate.eventMinutes - lastAccepted.eventMinutes
        // A line older than the last one does not "consume" the budget either way, so
        // only a forward step is rate-limited.
        return gap >= RATE_LIMIT_MINUTES || gap < 0
    }

    // ------------------------------------------------------------------
    // Translation
    // ------------------------------------------------------------------

    private fun memoryLine(world: StoryInstance, memory: Memory): StoryFeedLine {
        val owner = world.displayNameOf(memory.characterId)
        val subject = memory.subject.ifBlank {
            memory.relatedCharacterIds.firstOrNull()?.let { world.displayNameOf(it) }.orEmpty()
        }
        return StoryFeedLine(
            id = "feed-memory-${memory.id.value}",
            kind = FeedKind.REMEMBERED,
            // The memory's own text is authored by the engine and is already in story
            // language, so it is used directly. Wrapping it in a template would produce
            // "Marinette remembered that Marinette ..." for every self-referential one.
            text = if (owner.isBlank()) memory.content else "$owner remembered: ${memory.content}",
            subject = subject,
            relativeLabel = relativeLabel(memory.createdAt, world.worldClock.now),
            eventMinutes = memory.createdAt.totalMinutes,
            debug = StoryFeedLine.DebugTrace(
                source = "memory/${memory.tier.name}",
                memoryId = memory.id.value,
                characterId = memory.characterId.value,
                importance = memory.importance,
                causalCause = memory.sourceEventId?.value.orEmpty(),
            ),
        )
    }

    private fun threadLine(world: StoryInstance, thread: StoryThread): StoryFeedLine {
        return StoryFeedLine(
            id = "feed-thread-${thread.id.value}",
            kind = FeedKind.THREAD,
            text = when (thread.status) {
                dev.charaly.runtime.domain.StoryThreadStatus.COMPLETED ->
                    "A thread closed: ${thread.title}."
                dev.charaly.runtime.domain.StoryThreadStatus.FAILED ->
                    "A thread was abandoned: ${thread.title}."
                dev.charaly.runtime.domain.StoryThreadStatus.ACTIVE ->
                    "A thread moved: ${thread.title}."
                dev.charaly.runtime.domain.StoryThreadStatus.DORMANT ->
                    "A thread opened: ${thread.title}."
            },
            subject = thread.title,
            relativeLabel = relativeLabel(thread.updatedAt, world.worldClock.now),
            eventMinutes = thread.updatedAt.totalMinutes,
            debug = StoryFeedLine.DebugTrace(
                source = "thread/${thread.status.name}",
                threadId = thread.id.value,
                characterId = thread.involvedCharacterIds.firstOrNull()?.value.orEmpty(),
            ),
        )
    }

    private fun promiseLine(
        world: StoryInstance,
        promise: dev.charaly.runtime.domain.Promise,
    ): StoryFeedLine {
        val keeper = world.displayNameOf(promise.keeperId)
        // The promise's own text is authored, and naming it is the point: "Marinette is
        // still waiting" is vague, "Marinette is still waiting on your promise about the
        // rooftop" is a hook.
        val text = when {
            keeper.isBlank() -> "A promise came due."
            promise.beneficiaryId == playerId -> "$keeper is still waiting on you."
            else -> "$keeper has not forgotten a promise."
        }
        return StoryFeedLine(
            id = "feed-promise-${promise.id.value}",
            kind = FeedKind.COMMITMENT,
            text = text,
            subject = promise.text,
            relativeLabel = relativeLabel(world.worldClock.now, world.worldClock.now),
            eventMinutes = if (promise.hasDeadline) {
                dev.charaly.runtime.domain.StoryTime.of(
                    day = promise.dueOnDay,
                    hour = promise.dueAtMinuteOfDay / 60,
                    minute = promise.dueAtMinuteOfDay % 60,
                ).totalMinutes
            } else {
                promise.madeAt.totalMinutes
            },
            debug = StoryFeedLine.DebugTrace(
                source = "promise/${promise.status.name.lowercase()}",
                characterId = promise.keeperId.value,
            ),
        )
    }

    private fun relationshipLine(
        world: StoryInstance,
        relationship: dev.charaly.runtime.domain.Relationship,
    ): StoryFeedLine {
        val a = world.displayNameOf(relationship.sourceId)
        val b = world.displayNameOf(relationship.targetId)
        return StoryFeedLine(
            id = "feed-rel-${relationship.sourceId.value}-${relationship.targetId.value}",
            kind = FeedKind.RELATIONSHIP,
            text = if (a.isBlank() || b.isBlank()) {
                "Something changed between two people."
            } else {
                "Something between $a and $b changed."
            },
            subject = "$a & $b",
            relativeLabel = relativeLabel(relationship.updatedAt, world.worldClock.now),
            eventMinutes = relationship.updatedAt.totalMinutes,
            debug = StoryFeedLine.DebugTrace(
                source = "relationship/${relationship.relationshipType.name.lowercase()}",
                characterId = relationship.sourceId.value,
            ),
        )
    }

    /**
     * "Just now" / "3 hours ago" / "Earlier today" / "2 days ago".
     *
     * Story time, not wall time. A user who has played for two hours across three
     * in-world days wants to be told about the *world*.
     */
    fun relativeLabel(eventAt: StoryTime, now: StoryTime): String {
        val delta = now.totalMinutes - eventAt.totalMinutes
        return when {
            // A memory stamped slightly ahead of the clock - which happens when the clock
            // is advanced after the fact - is "now", not "in the future". Rendering
            // "-1 hours ago" would be worse than clamping.
            delta < 0 -> "Just now"
            delta < 60 -> "Just now"
            delta < 60 * 4 -> "${delta / 60} hours ago"
            delta < 60 * 12 -> "Earlier today"
            // Under a day: name the part of the day it fell in, which is what "Last
            // night" means in the world's own terms.
            delta < 60 * 24 -> "Last night"
            delta < 60 * 36 -> "Yesterday"
            else -> "${delta / (60 * 24)} days ago"
        }
    }

    /**
     * Collapses repeats.
     *
     * Two relationship changes involving the same pair inside one scene produce two
     * near-identical lines, and reading the second tells the user nothing the first did
     * not. Keyed on kind *and* subject so genuinely different news survives.
     */
    private fun deduplicate(lines: List<StoryFeedLine>): List<StoryFeedLine> {
        val seen = mutableSetOf<String>()
        return lines.filter { line ->
            val key = "${line.kind.name}:${line.subject}"
            if (key in seen) false else { seen += key; true }
        }
    }

    /**
     * Below this importance, a memory is not news.
     *
     * Mirrors the gate in `MemoryWriter`, which already refuses to store small talk.
     * This is the second half of the same rule: even a stored low-importance memory
     * (an authored one, or one written before the gate existed) stays out of the feed.
     */
    const val NOTABLE_IMPORTANCE = 3

    /**
     * Tiers that never produce a feed line.
     *
     * [MemoryTier.WORKING] and [MemoryTier.SCENE] are scratch space by definition - the
     * current moment and its summary - and surfacing them would put "you are in the
     * bakery" in a feed, which is not news.
     */
    val QUIET_TIERS: Set<MemoryTier> = setOf(MemoryTier.WORKING, MemoryTier.SCENE)

    /**
     * A display name for a character, or empty when unknown.
     *
     * Empty rather than a placeholder id: a line saying "character-7 remembered" is
     * worse than one the caller can decide to suppress.
     */
    private fun StoryInstance.displayNameOf(characterId: CharacterId): String =
        character(characterId)?.name.orEmpty()

    /**
     * Who "the player" is.
     *
     * A story has exactly one player persona, so this is a constant rather than a
     * parameter. It matters because a promise between two NPCs is world simulation and
     * a promise the player is party to is something they can be reminded about; the
     * distinction is the whole reason [build] can stay quiet most of the time.
     */
    val playerId: CharacterId = CharacterId("player")
}
