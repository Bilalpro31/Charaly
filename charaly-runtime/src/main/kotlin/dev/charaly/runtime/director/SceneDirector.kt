package dev.charaly.runtime.director

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.Scene
import dev.charaly.runtime.domain.SceneId
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryTime
import dev.charaly.runtime.domain.WorldDefinition

/**
 * Decides what scene is active right now.
 *
 * It is pure and deterministic: same instance + same focus character => same
 * scene. It never writes to the instance and never calls the LLM; the caller
 * persists the derived scene by scheduling a `SceneStarted` event.
 */
class SceneDirector(private val definition: WorldDefinition) {

    /**
     * Returns the current scene, or a freshly derived one when there is none.
     * A brand new scene is returned with [SceneState.PENDING] so the caller can
     * decide to commit it as an event.
     */
    fun directScene(instance: StoryInstance, focusCharacterId: CharacterId?): Scene? {
        val focus = focusCharacterId ?: instance.focusCharacterId ?: return null
        val runtime = instance.characters[focus] ?: return null

        // Reuse the active scene the focus character already participates in.
        runtime.sceneIds.asSequence()
            .mapNotNull { instance.worldState.activeScenes[it] }
            .firstOrNull { it.isActive() && it.focusCharacterId == focus }
            ?.let { return refine(it, instance, focus) }

        runtime.sceneIds.asSequence()
            .mapNotNull { instance.worldState.activeScenes[it] }
            .firstOrNull { it.isActive() }
            ?.let { return refine(it, instance, focus) }

        val location = runtime.locationId ?: return null
        return deriveNewScene(instance, focus, location)
    }

    /**
     * A scene for a character who has no scene yet. Participants are whoever is
     * physically co-located: presence is authoritative world state, not a guess.
     */
    fun deriveNewScene(
        instance: StoryInstance,
        focusCharacterId: CharacterId,
        locationId: LocationId,
        objective: String = "",
    ): Scene {
        val participants = instance.worldState
            .charactersAt(locationId)
            .map { it.characterId }
            .ifEmpty { listOf(focusCharacterId) }

        val activeThreads = instance.worldState
            .activeThreadsFor(locationId, participants.toSet())
            .filter { it.involves(focusCharacterId) || it.touches(locationId) }

        return Scene(
            id = nextSceneId(instance),
            locationId = locationId,
            participants = participants,
            focusCharacterId = focusCharacterId,
            activeThreadIds = activeThreads.map { it.id },
            objective = objective,
            startedAt = instance.worldClock.now,
        )
    }

    /** Re-derives the volatile parts of a live scene (participants, threads). */
    private fun refine(scene: Scene, instance: StoryInstance, focus: CharacterId): Scene {
        val participants = scene.participants.toMutableSet().apply {
            addAll(instance.worldState.charactersAt(scene.locationId).map { it.characterId })
            removeAll { id ->
                instance.characters[id]?.locationId != null && instance.characters[id]?.locationId != scene.locationId
            }
        }.sortedBy { it.value }

        val activeThreads = instance.worldState
            .activeThreadsFor(scene.locationId, participants.toSet())
            .map { it.id }

        return scene.copy(
            participants = participants,
            activeThreadIds = activeThreads,
            focusCharacterId = scene.focusCharacterId ?: focus,
        )
    }

    private fun nextSceneId(instance: StoryInstance): SceneId =
        SceneId("scene-${instance.worldState.revision + 1}-${instance.worldClock.now.totalMinutes}")
}
