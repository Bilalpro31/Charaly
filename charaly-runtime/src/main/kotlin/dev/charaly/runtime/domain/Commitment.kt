package dev.charaly.runtime.domain

import kotlinx.serialization.Serializable

/**
 * Commitments, goals and consequences.
 *
 * ## Why these are separate state
 *
 * A promise is not a memory and a goal is not a plan. Both need to survive the
 * character forgetting that they made the promise, both need to be answerable months
 * later in story time, and both need to be *checkable*: "you said you would help" is
 * only an interesting line if the engine knows whether they did.
 *
 * So each of these is authoritative world state with its own lifecycle, changed only by
 * validated events. None of them is prose, and none of them is inferred from what the
 * model said happened.
 *
 * ## The three kinds
 *
 *  * [Promise] - a commitment with a named other party. Has a keeper, a beneficiary, and
 *    an outcome: kept, broken, or still open.
 *  * [Goal] - something a character is trying to achieve. Has progress, because a goal
 *    at 0% forever is a decoration.
 *  * [Consequence] - a decision that will have an effect later. Has a trigger, so the
 *    engine can fire the consequence at the moment the story earns it rather than
 *    whenever it happens to remember.
 */

@Serializable
enum class CommitmentStatus {
    /** Made, not yet due or not yet resolved. */
    OPEN,

    /** Kept. The right outcome. */
    KEPT,

    /**
     * Broken.
     *
     * Distinct from FAILED for a reason that matters socially: a promise can be broken
     * *and* be forgiven, and "broken" is a fact about the world while "forgiven" is a
     * fact about a relationship.
     */
    BROKEN,

    /** Dropped because the person it depended on is gone or the circumstances ended. */
    ABANDONED,
    ;

    val isOpen: Boolean get() = this == OPEN

    /** Whether the story can still do something with it. */
    val isResolved: Boolean get() = !isOpen
}

@Serializable
data class Promise(
    val id: PromiseId,
    val text: String,
    /** Who made it. They are accountable for it. */
    val keeperId: CharacterId,
    /** Who it was made to. A promise to nobody is a plan. */
    val beneficiaryId: CharacterId,
    val madeAt: StoryTime = StoryTime.START,
    /** When it stops mattering: "by Friday", or -1 when no deadline was set. */
    val dueAtMinuteOfDay: Int = -1,
    val dueOnDay: Int = -1,
    val status: CommitmentStatus = CommitmentStatus.OPEN,
    /** When it was resolved, and what resolved it. */
    val resolvedAt: StoryTime? = null,
    val resolutionNote: String = "",
    val locationId: LocationId? = null,
    /** Whether the keeper still believes they are bound by it. */
    val rememberedByKeeper: Boolean = true,
) {
    init {
        require(text.isNotBlank()) { "a promise needs words ($id)" }
        require(keeperId != beneficiaryId) { "a promise to yourself is a plan, not a promise ($id)" }
        require(dueAtMinuteOfDay in -1..1439) { "dueAtMinuteOfDay out of range: $dueAtMinuteOfDay" }
    }

    /**
     * Whether this promise has an explicit deadline.
     *
     * A deadline is *not* the same as due: most promises never had one, and treating
     * "no deadline" as "due immediately" would break every one of them on the first
     * clock tick.
     */
    val hasDeadline: Boolean
        get() = dueOnDay >= 0 && dueAtMinuteOfDay >= 0

    fun resolve(status: CommitmentStatus, at: StoryTime, note: String = ""): Promise {
        require(status.isResolved) { "resolving a promise to $status is not resolving it" }
        return copy(status = status, resolvedAt = at, resolutionNote = note)
    }

    /**
     * Whether this promise is overdue at [now].
     *
     * Only ever true for a promise that *had* a deadline, and only while it is still
     * open. A broken promise with a deadline stays broken rather than becoming more
     * overdue forever, which is what a naive "overdue" check does.
     */
    fun isOverdueAt(now: StoryTime): Boolean {
        if (!hasDeadline || !status.isOpen) return false
        val due = StoryTime.of(day = dueOnDay, hour = dueAtMinuteOfDay / 60, minute = dueAtMinuteOfDay % 60)
        return now.isAfter(due)
    }

    /** One line, for a prompt or a sheet. */
    fun describe(fromPerspectiveOf: CharacterId): String = when {
        fromPerspectiveOf != keeperId -> "promised ${beneficiaryId.value}: \"$text\" (${status.name.lowercase()})"
        status == CommitmentStatus.OPEN -> "you promised ${beneficiaryId.value} that $text"
        else -> "you promised ${beneficiaryId.value} that $text - ${status.name.lowercase()}"
    }
}

/**
 * Something a character is trying to achieve.
 *
 * Progress is 0..100 and *moved by events*, never by the passage of time alone. That is
 * the whole difference between a goal and a mood: a goal at 0% after fifty in-story
 * days is a story problem, and the engine can say so.
 */
@Serializable
data class Goal(
    val id: GoalId,
    val text: String,
    val ownerId: CharacterId,
    /** 0..100. */
    val progress: Int = 0,
    val status: CommitmentStatus = CommitmentStatus.OPEN,
    val createdAt: StoryTime = StoryTime.START,
    val updatedAt: StoryTime = StoryTime.START,
    /** Why it stopped, if it did. */
    val resolutionNote: String = "",
    val relatedThreadIds: List<ThreadId> = emptyList(),
    val locationId: LocationId? = null,
    /**
     * Whether anyone would notice this character doing nothing about it.
     *
     * A shopkeeper's routine goal is not stalled, it is just what they do. Without this
     * the health monitor would report every settled character as stale.
     */
    val isAmbient: Boolean = false,
) {
    init {
        require(text.isNotBlank()) { "a goal needs words ($id)" }
        require(progress in 0..100) { "progress out of range: $progress" }
    }

    fun advance(by: Int, at: StoryTime): Goal = copy(
        progress = (progress + by).coerceIn(0, 100),
        updatedAt = at,
    )

    /** A goal with no movement for this many story hours is stalled. */
    fun isStalledAt(now: StoryTime, afterMinutes: Long): Boolean =
        status.isOpen && !isAmbient && updatedAt.minutesUntil(now) >= afterMinutes

    fun describe(): String = when {
        !status.isOpen -> "$text (${status.name.lowercase()})"
        progress > 0 -> "$text - $progress% there"
        else -> text
    }
}

/**
 * A decision whose effect arrives later.
 *
 * This is the engine's answer to "the player chose something and nothing happened".
 * A consequence is armed by an event and fires when its condition is met, which is what
 * makes a choice feel like it had weight rather than being narrated and forgotten.
 */
@Serializable
data class Consequence(
    val id: ConsequenceId,
    /** What the player or a character did. */
    val decision: String,
    val decidedBy: CharacterId,
    val decidedAt: StoryTime = StoryTime.START,
    /**
     * What happens because of it.
     *
     * An authored description, plus structured effects applied by the event engine.
     * Deliberately *not* a stored payload: holding a mutable event inside world state
     * would create a second path to change the world that bypasses the queue, and the
     * whole point is that consequences arrive as ordinary replayable events.
     */
    val outcome: String,
    /** How many story minutes must pass before it lands. */
    val delayMinutes: Long = 0L,
    /** Whether it needs a character to be somewhere before it can fire. */
    val requiresPresenceOf: CharacterId? = null,
    val requiresLocationId: LocationId? = null,
    val firedAt: StoryTime? = null,
    val resolved: Boolean = false,
    val relatedThreadIds: List<ThreadId> = emptyList(),
) {
    init {
        require(decision.isNotBlank()) { "a consequence needs a decision ($id)" }
        require(outcome.isNotBlank()) { "a consequence needs an outcome ($id)" }
        require(delayMinutes >= 0L) { "a consequence cannot land in the past ($id)" }
    }

    val isPending: Boolean get() = !resolved

    /** Whether this consequence is armed and its time has come. */
    fun isDueAt(now: StoryTime): Boolean {
        if (resolved) return false
        return decidedAt.minutesUntil(now) >= delayMinutes
    }

    /**
     * Whether this consequence is *waiting on something* rather than merely pending.
     *
     * Reported separately from "not yet due" because they are different problems: one is
     * the clock, the other is a character who is not standing where they need to be.
     */
    fun isBlockedBy(instance: StoryInstance): Boolean {
        if (resolved) return false
        requiresPresenceOf?.let { required ->
            val location = instance.characters[required]?.locationId ?: return true
            if (requiresLocationId != null && location != requiresLocationId) return true
        }
        return false
    }

    fun describe(): String = when {
        resolved -> "$decision -> $outcome (already happened)"
        else -> "$decision -> $outcome (pending)"
    }
}

/** The complete commitment state for one story. */
@Serializable
data class CommitmentLedger(
    val promises: List<Promise> = emptyList(),
    val goals: List<Goal> = emptyList(),
    val consequences: List<Consequence> = emptyList(),
) {
    fun promise(id: PromiseId): Promise? = promises.firstOrNull { it.id == id }

    fun goal(id: GoalId): Goal? = goals.firstOrNull { it.id == id }

    fun consequence(id: ConsequenceId): Consequence? = consequences.firstOrNull { it.id == id }

    /** Open promises a character is still accountable for. */
    fun openPromisesOf(characterId: CharacterId): List<Promise> =
        promises.filter { it.keeperId == characterId && it.status.isOpen }
            .sortedBy { it.madeAt }

    /**
     * Open promises a character is party to, in either role.
     *
     * Separate from [openPromisesOf] because being the person a promise was *made to*
     * is a completely different situation from being the one who made it, and a
     * character needs to be able to ask "what is owed to me" without that question
     * being answered in terms of their own obligations.
     */
    fun openPromisesInvolving(characterId: CharacterId): List<Promise> =
        promises.filter {
            it.status.isOpen && (it.keeperId == characterId || it.beneficiaryId == characterId)
        }.sortedBy { it.madeAt }

    /** Open promises somebody else made to this character. */
    fun promisesOwedTo(characterId: CharacterId): List<Promise> =
        openPromisesInvolving(characterId).filter { it.beneficiaryId == characterId }

    /** Everything a character owes and everything they are chasing. */
    fun commitmentsOf(characterId: CharacterId): List<String> = buildList {
        openPromisesOf(characterId).forEach { add(it.describe(characterId)) }
        goals.filter { it.ownerId == characterId && it.status.isOpen }
            .sortedBy { it.createdAt }
            .forEach { add(it.describe()) }
    }

    fun pendingConsequences(): List<Consequence> = consequences.filter { it.isPending }

    fun withPromise(promise: Promise): CommitmentLedger = copy(
        promises = promises.filterNot { it.id == promise.id } + promise,
    )

    fun withGoal(goal: Goal): CommitmentLedger = copy(
        goals = goals.filterNot { it.id == goal.id } + goal,
    )

    fun withConsequence(consequence: Consequence): CommitmentLedger = copy(
        consequences = consequences.filterNot { it.id == consequence.id } + consequence,
    )

    companion object {
        val EMPTY = CommitmentLedger()
    }
}