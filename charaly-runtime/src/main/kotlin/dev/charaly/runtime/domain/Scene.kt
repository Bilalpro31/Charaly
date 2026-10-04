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

    /** Structured, non-narrative description used by prompts and the inspector. */
    fun describe(): String = buildString {
        appendLine("scene: ${id.value} (${state.name.lowercase()}, turn $turnCount)")
        appendLine("location: ${locationId.value}")
        appendLine("participants: ${participants.joinToString { it.value }}")
        if (activeThreadIds.isNotEmpty()) appendLine("threads: ${activeThreadIds.joinToString { it.value }}")
        if (objective.isNotBlank()) appendLine("objective: $objective")
    }
}

@Serializable
enum class SceneState {
    ACTIVE,
    SUSPENDED,
    ENDED,
}
