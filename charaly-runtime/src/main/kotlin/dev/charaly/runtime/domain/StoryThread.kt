package dev.charaly.runtime.domain

import kotlinx.serialization.Serializable

/**
 * A minimal narrative thread.
 *
 * This is NOT a quest engine. Its only job is to give the SceneDirector a
 * structured, persistent handle on "what is currently going on" so that context
 * selection is deterministic instead of "whatever the LLM seems to care about".
 */
@Serializable
data class StoryThread(
    val id: ThreadId,
    val title: String,
    val status: StoryThreadStatus = StoryThreadStatus.DORMANT,
    val stage: Int = 0,
    val description: String = "",
    val involvedCharacterIds: List<CharacterId> = emptyList(),
    val relevantLocationIds: List<LocationId> = emptyList(),
    val state: Map<String, String> = emptyMap(),
    val updatedAt: StoryTime = StoryTime.START,
) {
    init {
        require(title.isNotBlank()) { "StoryThread $id needs a title" }
        require(stage >= 0) { "stage must not be negative" }
    }

    fun involves(characterId: CharacterId): Boolean = involvedCharacterIds.contains(characterId)

    fun touches(locationId: LocationId): Boolean = relevantLocationIds.contains(locationId)

    fun isOpen(): Boolean = status == StoryThreadStatus.ACTIVE || status == StoryThreadStatus.DORMANT
}

@Serializable
enum class StoryThreadStatus {
    DORMANT,
    ACTIVE,
    COMPLETED,
    FAILED,
}
