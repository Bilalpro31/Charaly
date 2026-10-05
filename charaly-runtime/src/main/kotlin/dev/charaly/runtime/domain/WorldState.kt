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
    /**
     * Promises, goals and armed consequences.
     *
     * Authoritative world state rather than something derived from memory, because all
     * three need to be *checkable*: a promise has to survive the keeper forgetting it,
     * and a goal has to be able to report that it has not moved in fifty days. Both are
     * impossible to answer from memories.
     */
    val commitments: dev.charaly.runtime.domain.CommitmentLedger =
        dev.charaly.runtime.domain.CommitmentLedger.EMPTY,
    val activeScenes: Map<SceneId, Scene> = emptyMap(),
    /** Events that are still in effect (a started scene, a presence, ...). */
    val activeEvents: List<EventId> = emptyList(),
    /** Audit trail of applied events, oldest first. */
    val eventLog: List<EventId> = emptyList(),
    /**
     * What caused what.
     *
     * An event log alone answers "in what order". It cannot answer "why did this
     * happen", which is the question a story is actually made of: a player who
     * remembers being offered a deal three scenes ago cannot connect that to the akuma
     * appearing now, because nothing in the world says those are related.
     *
     * So this is a real graph rather than a list, and every edge names a typed reason
     * rather than "because of that". Typed reasons are what make it queryable - "what
     * set off this?" has no answer on a graph whose edges are free text.
     */
    val causality: Map<EventId, CausalLink> = emptyMap(),
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

    /** Replaces the whole ledger. The only write path, so no half-updated state. */
    fun withCommitments(ledger: dev.charaly.runtime.domain.CommitmentLedger): WorldState =
        copy(commitments = ledger, revision = revision + 1)

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

    /** Records that [id] happened *because of* something, with a typed reason. */
    fun recordCausality(link: CausalLink): WorldState =
        copy(causality = causality + (link.effectId to link), revision = revision + 1)

    /**
     * Every event in this causal chain, nearest cause first.
     *
     * Bounded by [MAX_CAUSE_DEPTH] and deduplicated, because a cycle in the graph -
     * entirely possible once consequences start referencing each other - would
     * otherwise hang a diagnostic. A truncated chain is still useful; a hang is not.
     */
    fun causesOf(eventId: EventId, limit: Int = MAX_CAUSE_DEPTH): List<CausalLink> {
        val chain = mutableListOf<CausalLink>()
        val seen = mutableSetOf(eventId)
        var cursor = eventId
        while (chain.size < limit) {
            val link = causality[cursor] ?: break
            if (!seen.add(link.causeId)) break
            chain += link
            cursor = link.causeId
        }
        return chain
    }

    /** Everything this event set off, directly. */
    fun effectsOf(eventId: EventId): List<EventId> =
        causality.values.filter { it.causeId == eventId }.map { it.effectId }

    /**
     * A one-line "because of" chain, for the inspector and for prompt building.
     *
     * [nameOf] resolves an id to something readable. Passed in rather than held here
     * because WorldState is a pure value: giving it a reference to the pack so it can
     * print names would make equality depend on that reference, which is precisely the
     * kind of thing that quietly breaks a replay.
     */
    fun explainWhy(eventId: EventId, nameOf: (EventId) -> String = { it.value }): String {
        val chain = causesOf(eventId)
        if (chain.isEmpty()) return "nothing in particular"
        return chain.joinToString(" because ") { nameOf(it.causeId) }
    }


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
        appendLine("Causal links: ${causality.size}")
    }

    companion object {
        val EMPTY = WorldState()

        /**
         * How far back a cause chain is followed.
         *
         * Long enough for a player choice to reach a consequence three moves later,
         * which is the distance that actually matters, and short enough that a
         * pathological graph cannot make the inspector expensive.
         */
        const val MAX_CAUSE_DEPTH = 12
    }
}
