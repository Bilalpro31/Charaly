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

    val state: SceneState = SceneState.ACTIVE,
    val startedAt: StoryTime = StoryTime.START,
    val endedAt: StoryTime? = null,
    val turnCount: Int = 0,
) {
    init {
        require(turnCount >= 0) { "turnCount must not be negative" }
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
