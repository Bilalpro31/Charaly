package dev.charaly.runtime.domain

import kotlinx.serialization.Serializable

/**
 * One edge in the causal graph: this event happened because of that one.
 *
 * ## Why this is a graph and not a log
 *
 * An event log answers "in what order did things happen". It cannot answer "why did
 * this happen", and "why" is the only question a story is actually made of.
 *
 * A player who was offered a deal three scenes ago and then meets an akuma cannot
 * connect the two, because nothing in the world says they are connected. Everything
 * the model is told about cause is invented at generation time, which is exactly the
 * class of hallucination this engine exists to prevent.
 *
 * So the engine records the connection itself, at the moment it applies the event.
 *
 * ## Typed reasons
 *
 * [reason] is a closed enum rather than free text. That costs some expressiveness and
 * buys the ability to answer real questions: "what set this off?" ("a deal that was
 * accepted"), "what did this player choice cause?" ("every player choice"), "what
 * happened because Gabriel was in the museum?" (a filter over reasons). On a graph of
 * free text none of those are answerable, and a graph nobody can query is a log with
 * extra steps.
 */
@Serializable
data class CausalLink(
    val causeId: EventId,
    val effectId: EventId,
    val reason: CausalReason,
    /** Story time the link was recorded. */
    val at: StoryTime = StoryTime.START,
    /** A short authored detail, e.g. "the west wing, again". Never required. */
    val detail: String = "",
) {
    init {
        require(causeId != effectId) { "an event cannot cause itself ($causeId)" }
    }

    fun describe(): String {
        val suffix = if (detail.isBlank()) "" else " ($detail)"
        return "$causeId -> $effectId: ${reason.label}$suffix"
    }
}

/**
 * Why one thing happened.
 *
 * Grouped so that a query can select a whole family without listing members: "every
 * consequence of a player decision" is [PLAYER_CHOICE], [CONSEQUENCE] and
 * [PROMISE_BROKEN], not twenty names.
 */
@Serializable
enum class CausalReason(val label: String) {
    /** The pack author said so: a scripted sequence. */
    SCRIPTED("because the story says so"),

    /** Story time reached a scheduled point. */
    CLOCK("because time passed"),

    /** A character's routine moved them. */
    ROUTINE("because it is their day"),

    /** A character walked somewhere. */
    MOVEMENT("because somebody moved"),

    /** Two characters interacted. */
    ENCOUNTER("because they met"),

    /** Something was said. */
    DIALOGUE("because of something that was said"),

    /** The model proposed it and the engine accepted it. */
    MODEL_PROPOSAL("because the story proposed it"),

    /** An earlier event set this one off. */
    TRIGGERED("because of an earlier event"),

    /** Someone said yes to something. */
    DEAL_ACCEPTED("because a deal was accepted"),

    /** Someone said no. */
    DEAL_REFUSED("because a deal was refused"),

    /** A promise was kept. */
    PROMISE_KEPT("because a promise was kept"),

    /** A promise was broken. */
    PROMISE_BROKEN("because a promise was broken"),

    /** The player chose something, and this is what that caused. */
    PLAYER_CHOICE("because of your choice"),

    /** A goal moved. */
    GOAL_PROGRESS("because someone got closer to what they wanted"),

    /** A character learned something, which changed what they did. */
    KNOWLEDGE_GAINED("because someone found out"),

    /** Something was revealed that had been hidden. */
    SECRET_REVEALED("because a secret came out"),

    /** Consequence one caused another. */
    CONSEQUENCE("because of something that came before"),
    ;

    /**
     * Whether this reason means "the player is responsible".
     *
     * The one query that matters most, because it is the answer to "what did my
     * decisions actually do" - and the only honest way to answer it is from the graph
     * rather than from the model's narration.
     */
    val isPlayerResponsible: Boolean
        get() = this == PLAYER_CHOICE || this == DEAL_ACCEPTED || this == DEAL_REFUSED
}