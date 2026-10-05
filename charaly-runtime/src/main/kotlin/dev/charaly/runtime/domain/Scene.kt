package dev.charaly.runtime.domain

import kotlinx.serialization.Serializable

/**
 * A Scene is runtime context, NOT prose.
 *
 * It is a structured, serialisable answer to "what is happening right now and
 * what is relevant to it". The SceneDirector derives it from world state; the
 * ContextBuilder renders it into a prompt.
 */
@Serializable
data class Scene(
    val id: SceneId,
    val locationId: LocationId,
    val participants: List<CharacterId> = emptyList(),
    val focusCharacterId: CharacterId? = null,
    val activeThreadIds: List<ThreadId> = emptyList(),
    /** Facts that are relevant *and* visible to the participants. */
    val relevantFactIds: List<FactId> = emptyList(),
    val relevantRelationshipIds: List<RelationshipKey> = emptyList(),
    val relevantMemoryIds: List<MemoryId> = emptyList(),
    val objective: String = "",

    /**
     * How this scene feels, as a short instruction rather than a mood word.
     *
     * Derived by the director from the time of day, who is present and what is live -
     * never from prose. Exists because "it is 03:00 and the two of them have been arguing"
     * and "it is lunchtime in a crowded canteen" call for different dialogue, and a model
     * given only the location and the cast cannot work that out.
     */
    val mood: String = "",

    /**
     * Who is plausibly about to walk in.
     *
     * Derived from [dev.charaly.runtime.domain.Routine] - whoever the location's own
     * occupants put here in the near future - and kept as ids rather than names so the
     * director cannot accidentally narrate them. Empty is a legitimate answer and the
     * common one: most places at most times of day have nobody next.
     *
     * This is what makes a world feel populated from the player's side rather than only
     * from the engine's, because it is the difference between "the room is empty" and
     * "the room is empty, and Andre starts at nine".
     */
    val possibleArrivals: List<CharacterId> = emptyList(),

    /**
     * Who is likely to leave, for the same reason and with the same discipline.
     */
    val possibleDepartures: List<CharacterId> = emptyList(),

    /**
     * How much has already happened in this scene, and how fast it should now move.
     *
     * ## Why this exists
     *
     * A small model handed an open-ended scene reliably escalates on every turn: two
     * people talk, an alarm goes off, someone confesses, the roof collapses. Nothing in
     * the prompt says otherwise, so the model invents momentum it was never given - and
     * a scene that cannot be *in* a quiet moment is a scene where every conversation
     * costs the same.
     *
     * Real scenes spend most of their time doing very little. So the engine says
     * explicitly how much has happened, and whether anything is expected to happen at
     * all. [SceneTempo.QUIET] is a legitimate and common answer, and it is the one that
     * makes ordinary conversation playable.
     */
    val tempo: SceneTempo = SceneTempo.QUIET,

    /** How many times something actually changed in this scene. Not the turn count. */
    val incidents: Int = 0,

    val state: SceneState = SceneState.ACTIVE,
    val startedAt: StoryTime = StoryTime.START,
    val endedAt: StoryTime? = null,
    val turnCount: Int = 0,
) {
    init {
        require(turnCount >= 0) { "turnCount must not be negative" }
        require(incidents >= 0) { "incidents must not be negative" }
    }

    /**
     * What to do when something actually happens in this scene.
     *
     * Counts *incidents* - validated world changes - rather than turns, because a turn
     * where nobody moved anything is exactly the case this is here to model.
     */
    fun withIncident(): Scene = copy(incidents = incidents + 1)

    /**
     * The tempo this scene has reached.
     *
     * Not a dial a caller sets: a function of how much has happened and whether anything
     * live is pressing, so that a scene cannot be *told* to be exciting when the world
     * has not given it a reason to be.
     */
    fun currentTempo(hasLivePressure: Boolean): SceneTempo = when {
        // Something is unresolved and the scene has already gone somewhere. Keep going.
        hasLivePressure && incidents >= 2 -> SceneTempo.ESCALATING
        // Something is live but nothing has happened yet. Let it breathe, but be ready.
        hasLivePressure -> SceneTempo.BUILDING
        // Nothing live at all, however many turns have passed. This is the case that
        // stops a model from manufacturing an emergency to fill an empty scene.
        else -> SceneTempo.QUIET
    }

    fun isActive(): Boolean = state == SceneState.ACTIVE

    fun withTurn(): Scene = copy(turnCount = turnCount + 1)

    fun participantSet(): Set<CharacterId> = participants.toSet()

    /** Someone about to arrive is not present yet. Being "possibly here" is not being here. */
    fun isPresent(characterId: CharacterId): Boolean = participants.contains(characterId)

    /** Structured, non-narrative description used by prompts and the inspector. */
    fun describe(): String = buildString {
        appendLine("scene: ${id.value} (${state.name.lowercase()}, turn $turnCount)")
        appendLine("location: ${locationId.value}")
        appendLine("participants: ${participants.joinToString { it.value }}")
        if (activeThreadIds.isNotEmpty()) appendLine("threads: ${activeThreadIds.joinToString { it.value }}")
        if (objective.isNotBlank()) appendLine("objective: $objective")
        if (mood.isNotBlank()) appendLine("mood: $mood")
        if (possibleArrivals.isNotEmpty()) {
            appendLine("may arrive: ${possibleArrivals.joinToString { it.value }}")
        }
        if (possibleDepartures.isNotEmpty()) {
            appendLine("may leave: ${possibleDepartures.joinToString { it.value }}")
        }
    }
}

@Serializable
enum class SceneState {
    ACTIVE,
    SUSPENDED,
    ENDED,
}

/**
 * How fast a scene is allowed to move.
 *
 * An instruction to the model rather than a throttle on the engine. The engine does not
 * stop a character from doing something dramatic; it tells the model that nothing is
 * currently pressing, so a dramatic thing has to come from the characters rather than
 * from the model needing the scene to be interesting.
 */
@Serializable
enum class SceneTempo(val instruction: String) {
    /**
     * Nothing live, nothing expected.
     *
     * The default, and the most common state of a real scene. Talk, be ordinary, let
     * the conversation be the point.
     */
    QUIET(
        "Nothing is happening and nothing needs to. This is an ordinary conversation. " +
            "Do not introduce an event, a crisis, a confession or a sound in the corridor. " +
            "Let the scene be uneventful.",
    ),

    /** Something unresolved is live but has not started moving yet. */
    BUILDING(
        "Something is unresolved, but it has not started moving. Let the characters " +
            "come nearer to it in their own time rather than forcing it.",
    ),

    /** Something has happened, at least twice, and something is still live. */
    ESCALATING(
        "This scene is already under way and something is still unresolved. Let it " +
            "continue at the pace it has reached.",
    ),
}
