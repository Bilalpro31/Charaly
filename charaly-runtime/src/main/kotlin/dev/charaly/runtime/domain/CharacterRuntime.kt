package dev.charaly.runtime.domain

import kotlinx.serialization.Serializable

/**
 * DYNAMIC state of a character inside one running story.
 *
 * Hard rules:
 *  * no inference/LLM calls live here (see CharalyRuntime for generation),
 *  * knowledge/memory/relationship *ownership* stays in their stores; this class
 *    only keeps ids of what is currently relevant, so context selection can be
 *    done without loading everything.
 */
@Serializable
data class CharacterRuntime(
    val characterId: CharacterId,
    val name: String,
    val locationId: LocationId? = null,
    val activity: CharacterActivity = CharacterActivity.IDLE,
    val mood: String = "",
    val activeGoals: List<String> = emptyList(),
    val sceneIds: List<SceneId> = emptyList(),
    /** Ids of world facts this character currently knows about. */
    val knownFactIds: List<FactId> = emptyList(),
    /** Ids of persistent memories owned by this character. */
    val memoryIds: List<MemoryId> = emptyList(),
    val lastUpdatedAt: StoryTime = StoryTime.START,
) {
    init {
        require(name.isNotBlank()) { "CharacterRuntime name must not be blank ($characterId)" }
    }

    fun isPresentIn(sceneId: SceneId): Boolean = sceneIds.contains(sceneId)

    fun knows(factId: FactId): Boolean = knownFactIds.contains(factId)

    /** Idempotent: entering a scene twice must not duplicate the reference. */
    fun enterScene(sceneId: SceneId, at: StoryTime): CharacterRuntime =
        copy(
            sceneIds = if (sceneId in sceneIds) sceneIds else sceneIds + sceneId,
            lastUpdatedAt = at,
        )

    fun leaveScene(sceneId: SceneId, at: StoryTime): CharacterRuntime =
        copy(sceneIds = sceneIds - sceneId, lastUpdatedAt = at)
}

/**
 * What a character is doing right now. Deliberately a closed set: the LLM is
 * never allowed to invent an activity that the runtime cannot represent.
 */
@Serializable
enum class CharacterActivity {
    IDLE,
    WORKING,
    RESTING,
    TRAVELLING,
    TALKING,
    INVESTIGATING,
    FLEEING,
    UNKNOWN,
}
