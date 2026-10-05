package dev.charaly.runtime.director

import dev.charaly.runtime.domain.CharacterActivity
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.Scene
import dev.charaly.runtime.domain.SceneId
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryTime
import dev.charaly.runtime.domain.StoryThread
import dev.charaly.runtime.domain.WorldDefinition

/**
 * Decides what scene is active right now.
 *
 * ## What the director is for, and what it is not for
 *
 * It assembles the *context of a moment* out of authoritative world state: who is here,
 * where, which threads are live, how the scene feels, and who is plausibly about to
 * change that. It never writes the scene. A director that produced narration would be a
 * second narrator competing with the model, and the whole point of the architecture is
 * that there is exactly one thing generating prose.
 *
 * ## Why it derives mood
 *
 * "It is three in the morning and the two of them have been arguing" and "it is lunchtime
 * in a crowded canteen" call for completely different dialogue, and a model handed only
 * a location id and a list of names cannot work that out. So the mood is computed here,
 * from the clock, the cast and what is live - deterministically, and as an instruction
 * rather than a mood word, because a model can act on "tense, and getting worse" and
 * cannot act on "melancholy".
 *
 * ## Determinism
 *
 * Pure, and a function of (definition, instance, focus). Same instance and same focus
 * character always produce the same scene, which is what makes the developer's context
 * inspector trustworthy.
 */
class SceneDirector(private val definition: WorldDefinition) {

    /**
     * Returns the current scene, or a freshly derived one when there is none.
     * A brand new scene is returned with [dev.charaly.runtime.domain.SceneState.PENDING]
     * so the caller can decide to commit it as an event.
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

        val activeThreads = relevantThreads(instance, locationId, participants.toSet())
        val arrivals = arrivals(instance, locationId, participants.toSet())
        val departures = departures(instance, locationId, participants.toSet())

        return Scene(
            id = nextSceneId(instance),
            locationId = locationId,
            participants = participants,
            focusCharacterId = focusCharacterId,
            activeThreadIds = activeThreads.map { it.id },
            objective = objective.ifBlank { objectiveOf(activeThreads, locationId, instance) },
            mood = moodOf(instance, locationId, participants.toSet(), activeThreads),
            possibleArrivals = arrivals,
            possibleDepartures = departures,
            startedAt = instance.worldClock.now,
        )
    }

    /**
     * Re-derives the volatile parts of a live scene.
     *
     * Everything volatile is recomputed on every call rather than stored once, because
     * a scene is a *view* of the world: the moment the world's facts change, the scene's
     * description of them must change too, or the prompt describes a moment that has
     * already passed.
     */
    private fun refine(scene: Scene, instance: StoryInstance, focus: CharacterId): Scene {
        val participants = scene.participants.toMutableSet().apply {
            addAll(instance.worldState.charactersAt(scene.locationId).map { it.characterId })
            removeAll { id ->
                instance.characters[id]?.locationId != null && instance.characters[id]?.locationId != scene.locationId
            }
        }.sortedBy { it.value }

        val activeThreads = relevantThreads(instance, scene.locationId, participants.toSet())
        return scene.copy(
            participants = participants,
            activeThreadIds = activeThreads.map { it.id },
            objective = scene.objective.ifBlank { objectiveOf(activeThreads, scene.locationId, instance) },
            mood = moodOf(instance, scene.locationId, participants.toSet(), activeThreads),
            possibleArrivals = arrivals(instance, scene.locationId, participants.toSet()),
            possibleDepartures = departures(instance, scene.locationId, participants.toSet()),
            focusCharacterId = scene.focusCharacterId ?: focus,
        )
    }

    /**
     * Threads live for this moment.
     *
     * Filtered to threads the focus is genuinely party to *or* that touch this place.
     * Without the second clause a scene in a corridor would inherit every arc in the
     * story, and the prompt would spend its budget on subplots the scene cannot touch.
     */
    private fun relevantThreads(
        instance: StoryInstance,
        locationId: LocationId,
        participants: Set<CharacterId>,
    ): List<StoryThread> = instance.worldState
        .activeThreadsFor(locationId, participants)
        .filter { thread -> thread.touches(locationId) || thread.involvedCharacterIds.any { it in participants } }
        .sortedByDescending { it.priority }
        .take(MAX_THREADS_PER_SCENE)

    /**
     * What this scene is about, in one line.
     *
     * Threads first, because an active thread is the strongest available signal of what
     * a scene is for. The fallback deliberately names the place and the hour rather than
     * inventing a situation - a director that made something up here would be a second
     * narrator.
     */
    private fun objectiveOf(
        threads: List<StoryThread>,
        locationId: LocationId,
        instance: StoryInstance,
    ): String {
        if (threads.isNotEmpty()) {
            return threads.joinToString("; ") { "${it.title} (${it.progress}% done)" }
        }
        val place = definition.location(locationId)?.name ?: locationId.value
        return "an ordinary moment at the $place at ${partOfDay(instance.worldClock.now)}"
    }

    /**
     * The scene's mood, as an instruction.
     *
     * A function of the hour, the size and state of the cast, and whether anything live
     * is going wrong. Not a mood word, and never a mood the model has to guess at: "it is
     * late, it is quiet, and something is unfinished" is actionable; "melancholy" is a
     * suggestion the model will ignore.
     */
    private fun moodOf(
        instance: StoryInstance,
        locationId: LocationId,
        participants: Set<CharacterId>,
        threads: List<StoryThread>,
    ): String {
        val now = instance.worldClock.now
        val parts = mutableListOf<String>()

        parts += partOfDay(now)
        parts += crowdMood(instance, locationId, participants)

        val unfinished = threads.firstOrNull { it.isOpen() }
        if (unfinished != null) {
            parts += if (unfinished.priority >= 80) {
                "something important is unresolved (${unfinished.nextBeat.ifBlank { unfinished.title }})"
            } else {
                "something is still going on (${unfinished.title})"
            }
        }

        // Fear is directional - being frightened *of* somebody is not the same as being
        // frightened *by* them - so it has to be read edge by edge rather than as a
        // property of the pair. Any direction counts: an argument is uncomfortable from
        // both sides of it.
        val anyoneAfraid = participants.any { participant ->
            participants.any { other ->
                (instance.worldState.relationship(other, participant)?.fear ?: 0) >= FEAR_THRESHOLD ||
                    (instance.worldState.relationship(participant, other)?.fear ?: 0) >= FEAR_THRESHOLD
            }
        }
        if (anyoneAfraid) parts += "someone here is not comfortable"

        return parts.joinToString("; ")
    }

    /** Crowded and quiet are different scenes, and only the crowd count knows. */
    private fun crowdMood(
        instance: StoryInstance,
        locationId: LocationId,
        participants: Set<CharacterId>,
    ): String = when {
        participants.size >= 6 -> "busy, with people all around and no privacy"
        participants.size >= 3 -> "a few people, ordinary conversation"
        participants.size == 2 -> "one other person, close enough to talk to properly"
        else -> "alone, and it is noticeable"
    }

    /** Coarse: the clock is split into parts of a day, not minutes. */
    private fun partOfDay(now: StoryTime): String = when {
        now.hour < 5 -> "the small hours, when nobody should be out"
        now.hour < 8 -> "early, and the streets are still quiet"
        now.hour < 12 -> "morning"
        now.hour < 14 -> "lunchtime, and everything is busy"
        now.hour < 18 -> "afternoon"
        now.hour < 22 -> "evening"
        now.hour < 24 -> "late"
        else -> "the small hours, when nobody should be out"
    }

    /**
     * Who is scheduled to be here soon but is not here yet.
     *
     * Read from routines, over a two-hour window, and only for characters who are not
     * already present. A short window on purpose: this is a hint for the model, and a
     * longer one turns "someone might come by" into a promise the world then has to keep.
     */
    private fun arrivals(
        instance: StoryInstance,
        locationId: LocationId,
        present: Set<CharacterId>,
    ): List<CharacterId> {
        val now = instance.worldClock.now
        val horizon = now.plusMinutes(ARRIVAL_HORIZON_MINUTES)
        return definition.characters
            .filter { character -> character.routine.isScheduled }
            .mapNotNull { character ->
                if (character.id in present) return@mapNotNull null
                val nowThere = character.routine.resolve(now)?.locationId
                val laterThere = character.routine.resolve(horizon)?.locationId
                when {
                    // Not here now, but here within the window: an arrival.
                    nowThere != locationId && laterThere == locationId -> character.id
                    else -> null
                }
            }
            .sortedBy { it.value }
            .take(MAX_HINT_CHARACTERS)
    }

    /**
     * Who is likely to leave.
     *
     * The mirror of [arrivals] and for the same reason. Both are hints, and both are
     * capped, because a scene listing fifteen names is a scene that has stopped being a
     * scene.
     */
    private fun departures(
        instance: StoryInstance,
        locationId: LocationId,
        present: Set<CharacterId>,
    ): List<CharacterId> {
        val now = instance.worldClock.now
        val horizon = now.plusMinutes(ARRIVAL_HORIZON_MINUTES)
        return present.filter { characterId ->
            val character = definition.character(characterId) ?: return@filter false
            if (!character.routine.isScheduled) return@filter false
            val nowThere = character.routine.resolve(now)?.locationId
            val laterThere = character.routine.resolve(horizon)?.locationId
            nowThere == locationId && laterThere != locationId
        }.sortedBy { it.value }
            .take(MAX_HINT_CHARACTERS)
    }

    private fun nextSceneId(instance: StoryInstance): SceneId =
        SceneId("scene-${instance.worldState.revision + 1}-${instance.worldClock.now.totalMinutes}")

    companion object {
        /** Threads named in one scene. More than this and the scene has no focus. */
        const val MAX_THREADS_PER_SCENE = 3

        /** How far ahead an arrival hint looks. Short on purpose - see [arrivals]. */
        const val ARRIVAL_HORIZON_MINUTES = 120L

        /** Arrival and departure hints are capped for the same reason threads are. */
        const val MAX_HINT_CHARACTERS = 3

        /** Fear at or above this is worth telling the model about. */
        const val FEAR_THRESHOLD = 60
    }
}