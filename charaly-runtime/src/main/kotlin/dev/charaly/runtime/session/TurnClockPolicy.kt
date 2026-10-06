package dev.charaly.runtime.session

import dev.charaly.runtime.domain.StoryDuration

/**
 * How much story time one completed turn costs.
 *
 * ## The rule this exists to enforce
 *
 * Story time moves because the *runtime* decided it moved. It never moves because the
 * model said so.
 *
 * That distinction is the whole difference between Charaly's clock and a chat app
 * showing a timestamp. If a model can write "ten minutes later" and the clock obeys,
 * then the model owns world time, and every guarantee this codebase makes about world
 * state is one hallucinated sentence away from being false. A turn whose prose claims
 * three hours must still cost whatever [minutesFor] says, which is computed from
 * facts the *engine* already has: how much the player actually wrote, and how much
 * the world actually changed.
 *
 * ## Where the inputs come from
 *
 * Both inputs are engine-side facts:
 *
 *  * [userInputChars] - the player's own line. Not the model's.
 *  * [appliedActions] - how many validated events this turn actually committed. A turn
 *    where the player said something that moved the world costs more time than a turn
 *    that was pure talk, and that is a fact about the world, not an opinion.
 *
 * The reply is deliberately not an input. Its length, its content and its claims are
 * all model output.
 *
 * ## Why the numbers are what they are
 *
 * A conversational exchange in a story is not instantaneous, and it is not an hour.
 * Two to four minutes is roughly what "a few lines of dialogue" means when read as
 * fiction, and it is small enough that a reader who spends twenty turns in a scene
 * sees the light change. The caps matter as much as the base: an unbounded clock would
 * let one verbose turn skip past every routine boundary in the pack at once, which is
 * exactly the "the world jumped" feeling this is meant to avoid.
 */
object TurnClockPolicy {

    /** A short exchange. */
    const val EXCHANGE_MINUTES = 2L

    /** The most a single ordinary turn can cost before actions are counted. */
    const val MAX_PLAIN_TURN_MINUTES = 5L

    /** Player input above this length reads as *doing something*, not just talking. */
    const val LONG_INPUT_CHARS = 120

    /** A long player line is worth a little more time on its own. */
    const val LONG_INPUT_BONUS_MINUTES = 1L

    /** Each validated action is worth this much time. */
    const val MINUTES_PER_ACTION = 2L

    /** Actions counted this way stop mattering past this many. */
    const val MAX_COUNTED_ACTIONS = 4

    /**
     * The absolute ceiling for one turn.
     *
     * A hard bound rather than a soft one: a turn that proposes twenty actions must not
     * be able to move the clock by an hour and skip a dozen routines.
     */
    const val MAX_TURN_MINUTES = 15L

    /**
     * The story time a turn costs, from engine-side facts only.
     *
     * Pure and total. Given the same inputs it returns the same duration on any device,
     * in any test, in any order - which is what makes a replayed story reproduce.
     */
    fun minutesFor(userInputChars: Int, appliedActions: Int): StoryDuration {
        val base = when {
            userInputChars <= 0 -> EXCHANGE_MINUTES
            userInputChars <= LONG_INPUT_CHARS -> EXCHANGE_MINUTES
            else -> EXCHANGE_MINUTES + LONG_INPUT_BONUS_MINUTES
        }.coerceAtMost(MAX_PLAIN_TURN_MINUTES)

        val actionMinutes = appliedActions
            .coerceIn(0, MAX_COUNTED_ACTIONS)
            .toLong() * MINUTES_PER_ACTION

        return StoryDuration(minutes = (base + actionMinutes).coerceIn(EXCHANGE_MINUTES, MAX_TURN_MINUTES))
    }
}