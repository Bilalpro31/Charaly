package dev.charaly.runtime.engine

import dev.charaly.runtime.domain.ChapterTitles
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.Scene
import dev.charaly.runtime.domain.StoryChapter
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryTime
import dev.charaly.runtime.domain.TranscriptEntry
import dev.charaly.runtime.domain.WorldDefinition

/**
 * Derives story chapters from authoritative state.
 *
 * A session should feel like a book, not a log file. Chapters come from scenes
 * (which the EventEngine validates) plus the transcript turns that happened in
 * them, so the chapter list can never disagree with the world: it is a projection,
 * not a parallel narrative invented next to the truth.
 */
object ChapterPlanner {

    /**
     * Recomputes the chapter list from the current instance.
     *
     * Deterministic: the same instance always yields the same chapters.
     */
    fun derive(
        instance: StoryInstance,
        definition: WorldDefinition,
    ): StoryInstance {
        val sceneOrder = sceneOrder(instance)
        if (sceneOrder.isEmpty()) return instance

        val chapters = sceneOrder.mapIndexed { index, sceneId ->
            val scene = instance.worldState.activeScenes[sceneId]
            val locationName = definition.location(scene?.locationId)?.name
                ?: scene?.locationId?.value
                ?: "an unnamed place"
            val entries = instance.conversation.forScene(sceneId)
            val participantNames = (scene?.participants.orEmpty())
                .mapNotNull { definition.nameOf(it) }
            val turnNumbers = entries.map { it.turn }
            val focusName = scene?.focusCharacterId?.let { definition.nameOf(it) }

            StoryChapter(
                id = "chapter-${index + 1}-${sceneId.value}",
                index = index + 1,
                title = if (index == 0) {
                    ChapterTitles.first(locationName, focusName)
                } else {
                    ChapterTitles.at(locationName, focusName)
                },
                summary = ChapterTitles.summary(
                    locationName = locationName,
                    participants = participantNames,
                    turns = turnNumbers.size,
                ),
                locationId = scene?.locationId,
                participantIds = scene?.participants.orEmpty(),
                threadIds = scene?.activeThreadIds.orEmpty(),
                startedAt = scene?.startedAt ?: firstTurnTime(entries, instance),
                endedAt = scene?.endedAt,
                firstTurn = turnNumbers.minOrNull() ?: 0,
                lastTurn = turnNumbers.maxOrNull() ?: 0,
                isCurrent = index == sceneOrder.lastIndex,
            )
        }

        return instance.copy(
            chapters = chapters,
            chapterCounter = chapters.size,
            updatedAt = instance.worldState.worldClock.now,
        )
    }

    /** A single "opening" chapter, used the moment a story starts. */
    fun openingChapter(
        instance: StoryInstance,
        definition: WorldDefinition,
        locationId: LocationId,
        focusCharacterId: CharacterId?,
        at: StoryTime,
    ): StoryChapter = StoryChapter(
        id = "chapter-1-opening",
        index = 1,
        title = ChapterTitles.first(
            locationName = definition.location(locationId)?.name ?: locationId.value,
            focusName = focusCharacterId?.let { definition.nameOf(it) },
        ),
        summary = ChapterTitles.summary(
            locationName = definition.location(locationId)?.name ?: locationId.value,
            participants = focusCharacterId?.let { listOf(definition.nameOf(it)) }.orEmpty(),
            turns = 0,
        ),
        locationId = locationId,
        participantIds = focusCharacterId?.let(::listOf).orEmpty(),
        startedAt = at,
        isCurrent = true,
    )

    /**
     * Scene order: chronological by start time, then by id, so it is stable even if
     * two scenes were opened at the same story minute.
     */
    private fun sceneOrder(instance: StoryInstance): List<dev.charaly.runtime.domain.SceneId> =
        instance.worldState.activeScenes.values
            .sortedWith(compareBy<Scene> { it.startedAt }.thenBy { it.id.value })
            .map { it.id }

    private fun firstTurnTime(entries: List<TranscriptEntry>, instance: StoryInstance): StoryTime =
        entries.minByOrNull { it.turn }?.at ?: instance.worldClock.now
}