package dev.charaly.runtime.domain

import kotlinx.serialization.Serializable

/**
 * AUTHORITATIVE dynamic world state.
 *
 * Everything in here is owned by the deterministic runtime and can only be
 * changed by applying a validated [dev.charaly.runtime.domain.events.WorldEvent]
 * through the [dev.charaly.runtime.engine.EventEngine]. Nothing in this class
 * knows about the LLM.
 *
 * Note what is *not* here: conversation transcripts, knowledge and memories.
 * Those live next to it in the StoryInstance because they are not authoritative
 * world truth (see section 22 of the Charaly architecture).
 */
@Serializable
data class WorldState(
    val worldClock: WorldClock = WorldClock(),
    val locations: Map<LocationId, Location> = emptyMap(),
    val variables: Map<String, WorldVariable> = emptyMap(),
    val characters: Map<CharacterId, CharacterRuntime> = emptyMap(),
    val relationships: Map<RelationshipKey, Relationship> = emptyMap(),
    val storyThreads: Map<ThreadId, StoryThread> = emptyMap(),
    val activeScenes: Map<SceneId, Scene> = emptyMap(),
    /** Events that are still in effect (a started scene, a presence, ...). */
    val activeEvents: List<EventId> = emptyList(),
    /** Audit trail of applied events, oldest first. */
    val eventLog: List<EventId> = emptyList(),
    val revision: Long = 0L,
) {
    fun location(id: LocationId?): Location? = id?.let { locations[it] }

    fun character(id: CharacterId?): CharacterRuntime? = id?.let { characters[it] }

    fun relationship(a: CharacterId, b: CharacterId): Relationship? =
        relationships[RelationshipKey(a, b)] ?: relationships[RelationshipKey(b, a)]

    fun relationshipOf(rel: Relationship): Relationship = relationships.getValue(rel.key())

    fun charactersAt(locationId: LocationId): List<CharacterRuntime> =
        characters.values.filter { it.locationId == locationId }.sortedBy { it.characterId.value }

    fun scenesAt(locationId: LocationId): List<Scene> =
        activeScenes.values.filter { it.locationId == locationId }.sortedBy { it.id.value }

    fun thread(id: ThreadId): StoryThread? = storyThreads[id]

    fun openThreads(): List<StoryThread> =
        storyThreads.values
            .filter { it.isOpen() }
            .sortedWith(compareByDescending<StoryThread> { it.status == StoryThreadStatus.ACTIVE }.thenBy { it.id.value })

    fun activeThreadsFor(locationId: LocationId, participants: Set<CharacterId>): List<StoryThread> =
        storyThreads.values
            .filter { it.status == StoryThreadStatus.ACTIVE }
            .filter { thread -> thread.touches(locationId) || thread.involvedCharacterIds.any { it in participants } }
            .sortedBy { it.id.value }

    fun withCharacter(runtime: CharacterRuntime): WorldState =
        copy(characters = characters + (runtime.characterId to runtime), revision = revision + 1)

    fun withRelationship(relationship: Relationship): WorldState =
        copy(relationships = relationships + (relationship.key() to relationship), revision = revision + 1)

    fun withThread(thread: StoryThread): WorldState =
        copy(storyThreads = storyThreads + (thread.id to thread), revision = revision + 1)

    fun withScene(scene: Scene): WorldState =
        copy(activeScenes = activeScenes + (scene.id to scene), revision = revision + 1)

    fun withVariable(variable: WorldVariable): WorldState =
        copy(variables = variables + (variable.key to variable), revision = revision + 1)

    /**
     * Records an applied event. `activeEvents` is a set-like list of events that
     * are still in force, so a repeated id must not accumulate.
     */
    fun recordEvent(id: EventId, stillActive: Boolean): WorldState = copy(
        activeEvents = if (stillActive && id !in activeEvents) activeEvents + id else activeEvents,
        eventLog = eventLog + id,
        revision = revision + 1,
    )

    /** Short human readable dump for the debug inspector. */
    fun describe(): String = buildString {
        appendLine("World @ ${worldClock.now.format()} (rev $revision)")
        appendLine("Locations: ${locations.keys.joinToString { it.value }}")
        appendLine("Characters:")
        characters.values.sortedBy { it.characterId.value }.forEach { c ->
            appendLine(
                "  - ${c.name} @ ${c.locationId?.value ?: "?"} " +
                    "[${c.activity.name}] goals=${c.activeGoals.size} facts=${c.knownFactIds.size} scenes=${c.sceneIds.size}",
            )
        }
        appendLine("Relationships: ${relationships.size}")
        appendLine("Threads: ${storyThreads.values.count { it.status == StoryThreadStatus.ACTIVE }} active / ${storyThreads.size} total")
        appendLine("Active scenes: ${activeScenes.keys.joinToString { it.value }}")
        appendLine("Pending events: (see EventQueue)")
    }

    companion object {
        val EMPTY = WorldState()
    }
}
