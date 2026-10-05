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
    // ---- first-class content (see StoryPackContent.kt) ---------------------
    val identity: PackIdentity = PackIdentity(),
    val factions: List<Faction> = emptyList(),
    val lore: List<WorldLoreEntry> = emptyList(),
    /** Authored events with triggers, conditions and structured effects. */
    val events: List<PackEventDefinition> = emptyList(),
    /** Ways of opening this world. */
    val scenarios: List<StartingScenario> = emptyList(),
    /** Roles the player may take. */
    val personas: List<PersonaTemplate> = emptyList(),
    /**
     * Default model profile for stories from this pack. A StoryInstance copies the
     * *resolved* configuration, so changing a global default later cannot silently
     * change an existing story's behaviour.
     */
    val defaultModelProfileId: String = "",
    val defaultNarrativeStyle: String = "",
    /**
     * Pack-level visual assets: cover, banner, event art, hook art.
     *
     * Presentation metadata only. This is deliberately *not* world state - nothing here
     * can affect what a character knows, where anyone is, or what has happened. A pack
     * with no assets is entirely valid and renders as generated artwork.
     */
    val visualAssets: List<VisualAsset> = emptyList(),
) {
    init {
        require(title.isNotBlank()) { "StoryPack $id needs a title" }
        val dupes = characters.groupBy { it.id }.filterValues { it.size > 1 }
        require(dupes.isEmpty()) { "Duplicate character ids in pack $id: ${dupes.keys.map { it.value }}" }
        val eventDupes = events.groupBy { it.id }.filterValues { it.size > 1 }
        require(eventDupes.isEmpty()) { "Duplicate event ids in pack $id: ${eventDupes.keys}" }
        val scenarioDupes = scenarios.groupBy { it.id }.filterValues { it.size > 1 }
        require(scenarioDupes.isEmpty()) { "Duplicate scenario ids in pack $id: ${scenarioDupes.keys}" }
        val assetDupes = visualAssets.groupBy { it.assetId }.filterValues { it.size > 1 }
        require(assetDupes.isEmpty()) { "Duplicate visual asset ids in pack $id: ${assetDupes.keys}" }
    }

    fun character(id: CharacterId): CharacterDefinition? = characters.firstOrNull { it.id == id }

    fun characterByName(name: String): CharacterDefinition? =
        characters.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    fun location(id: LocationId): Location? = locations.firstOrNull { it.id == id }

    val characterIds: List<CharacterId> get() = characters.map { it.id }
    val locationIds: List<LocationId> get() = locations.map { it.id }

    /** Where a character starts, as declared by the pack. */
    fun startLocationOf(characterId: CharacterId): LocationId? = initialWorldState.startLocations[characterId]

    // ---- content lookups ------------------------------------------------

    fun event(id: String): PackEventDefinition? = events.firstOrNull { it.id == id }

    fun scenario(id: String): StartingScenario? = scenarios.firstOrNull { it.id == id }

    fun persona(id: String): PersonaTemplate? = personas.firstOrNull { it.id == id }

    fun faction(id: String): Faction? = factions.firstOrNull { it.id == id }

    fun loreEntry(id: String): WorldLoreEntry? = lore.firstOrNull { it.id == id }

    /** The opening to use when the user does not pick one. */
    fun defaultScenario(): StartingScenario? = scenarios.firstOrNull()

    fun defaultPersona(): PersonaTemplate? = personas.firstOrNull()

    /** Genres and tags, deduplicated, for chips. */
    fun genreChips(): List<String> =
        (identity.genres + tags).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** Characters that share a starting location, ordered for a stable UI. */
    fun charactersAt(locationId: LocationId): List<CharacterDefinition> =
        characters.filter { startLocationOf(it.id) == locationId }
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
    /**
     * What each character has observed, believes, suspects and is wrong about.
     *
     * Seeded alongside the facts rather than derived from them, because a character's
     * starting position is not a set of true statements: it is what they have seen, the
     * conclusions they have drawn, the questions they have not resolved, and - for most
     * interesting characters - at least one thing they have simply got wrong.
     */
    val characterMinds: Map<CharacterId, dev.charaly.runtime.domain.knowledge.CharacterMind> = emptyMap(),
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
