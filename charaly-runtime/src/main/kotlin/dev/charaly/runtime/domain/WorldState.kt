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
/**
 * One retained line about a world change.
 *
 * Deliberately the event's *own* `summary` rather than a natural-language rewrite: the
 * point is that the next prompt is told what the engine did, verbatim, so there is no layer
 * between the mutation and the model's understanding of it.
 */
@Serializable
data class WorldChange(
    val at: StoryTime,
    val eventId: EventId,
    val summary: String,
    val origin: dev.charaly.runtime.domain.events.EventOrigin = dev.charaly.runtime.domain.events.EventOrigin.ENGINE,
    /**
     * True for a change about somebody's inner state: what they noticed, believe, suspect,
     * remember, owe or are owed.
     *
     * ## Why the prompt never sees these
     *
     * A belief is one character's, and putting it in a shared "what just happened" feed is
     * how Andre's theory about "some girl from another school" turns up in Marinette's
     * prompt. Personal state already has its own homes in the prompt - the `mind` block is
     * read for its owner only, and `commitments` likewise - so the world-change feed is
     * restricted to what a bystander could have witnessed.
     */
    val personal: Boolean = false,
) {
    fun render(): String = "${at.formatClock()} - $summary"
}

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
    /**
     * Rolling account of what changed, newest last, capped at [MAX_CHANGE_LOG].
     *
     * Capped because a thirty-turn session must not grow a prompt along with it: this is
     * the "recent events" slice of the context, and recent is the operative word.
     */
    val changeLog: List<WorldChange> = emptyList(),
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
     *
     * [change] is the event's own one-line summary, retained so the next prompt can say
     * WHAT JUST HAPPENED. Without it the only thing left after `applyEvent` returns is the
     * event id, and an id is not something a character can be told.
     */
    fun recordEvent(id: EventId, stillActive: Boolean, change: WorldChange? = null): WorldState = copy(
        activeEvents = if (stillActive && id !in activeEvents) activeEvents + id else activeEvents,
        eventLog = eventLog + id,
        changeLog = if (change == null) changeLog else (changeLog + change).takeLast(MAX_CHANGE_LOG),
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
    /**
     * The last [limit] things that actually changed, oldest first, in the world's own words.
     *
     * Sourced from [changeLog] - the summaries `EventEngine` retained as it applied each
     * event - and nothing else. No prose is read, no transcript is mined, nothing is
     * inferred: what a character is told about the recent past is exactly what the world
     * says happened. That is what makes "what just happened" a fact a later turn can rely on
     * rather than a recollection that might not match the state.
     */
    fun recentChanges(limit: Int = 4): List<String> {
        if (limit <= 0) return emptyList()
        // Only what a bystander could have seen. See [WorldChange.personal].
        return changeLog.filterNot { it.personal }.takeLast(limit).map { it.render() }
    }

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
        /**
         * How many world changes are remembered for the prompt.
         *
         * Bounded on purpose - see [changeLog].
         */
        const val MAX_CHANGE_LOG = 24

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
