package dev.charaly.runtime.domain

import kotlinx.serialization.Serializable

/**
 * STATIC story definition.
 *
 * A StoryPack is immutable authoring data: characters, places, the initial
 * world, the initial relationships/knowledge/threads, and the events that fire
 * when a story starts. It is NOT a running world - creating a
 * [StoryInstance] from a pack is what starts the story.
 */
@Serializable
data class StoryPack(
    val id: StoryPackId,
    val title: String,
    val description: String = "",
    val author: String = "",
    val version: Int = 1,
    val characters: List<CharacterDefinition> = emptyList(),
    val locations: List<Location> = emptyList(),
    val initialWorldState: InitialWorldState = InitialWorldState(),
    val initialRelationships: List<Relationship> = emptyList(),
    val initialKnowledge: InitialKnowledge = InitialKnowledge(),
    val initialStoryThreads: List<StoryThread> = emptyList(),
    /** Events applied (and/or scheduled) when a StoryInstance is created. */
    val initialEvents: List<SeedEvent> = emptyList(),
    val tags: List<String> = emptyList(),
) {
    init {
        require(title.isNotBlank()) { "StoryPack $id needs a title" }
        val dupes = characters.groupBy { it.id }.filterValues { it.size > 1 }
        require(dupes.isEmpty()) { "Duplicate character ids in pack $id: ${dupes.keys.map { it.value }}" }
    }

    fun character(id: CharacterId): CharacterDefinition? = characters.firstOrNull { it.id == id }

    fun characterByName(name: String): CharacterDefinition? =
        characters.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    fun location(id: LocationId): Location? = locations.firstOrNull { it.id == id }

    val characterIds: List<CharacterId> get() = characters.map { it.id }
    val locationIds: List<LocationId> get() = locations.map { it.id }

    /** Where a character starts, as declared by the pack. */
    fun startLocationOf(characterId: CharacterId): LocationId? = initialWorldState.startLocations[characterId]
}

/** World facts authored by the pack, plus who starts out knowing them. */
@Serializable
data class InitialKnowledge(
    /** Objective truth for this story. */
    val facts: List<dev.charaly.runtime.domain.knowledge.Fact> = emptyList(),
    /** Explicit per-character knowledge grants. Anything absent stays unknown. */
    val characterKnowledge: Map<CharacterId, List<String>> = emptyMap(),
    /** Ids of memories seeded into the instance (authored). */
    val authoredMemories: List<dev.charaly.runtime.domain.memory.Memory> = emptyList(),
)

/**
 * The declarative starting world of a pack: clock, variables, placements and
 * activities. Activities are applied through the event engine at instance
 * creation so that every mutation still flows through one code path.
 */
@Serializable
data class InitialWorldState(
    val startTime: StoryTime = StoryTime.START,
    val variables: List<WorldVariable> = emptyList(),
    val startLocations: Map<CharacterId, LocationId> = emptyMap(),
    val startActivities: Map<CharacterId, CharacterActivity> = emptyMap(),
    val startGoals: Map<CharacterId, List<String>> = emptyMap(),
)

/**
 * An event as authored in a pack: a payload plus optional delay.
 * The concrete [dev.charaly.runtime.domain.events.EventId]/sequence are assigned
 * by the runtime when the instance is created, which keeps packs portable.
 */
@Serializable
data class SeedEvent(
    val payload: dev.charaly.runtime.domain.events.EventPayload,
    val delayMinutes: Long = 0L,
    val note: String = "",
) {
    init {
        require(delayMinutes >= 0) { "delayMinutes must not be >= 0" }
    }
}
