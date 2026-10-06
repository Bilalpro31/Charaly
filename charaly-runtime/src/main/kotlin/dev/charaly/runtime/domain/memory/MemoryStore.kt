package dev.charaly.runtime.domain.memory

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.MemoryId
import dev.charaly.runtime.domain.StoryTime
import dev.charaly.runtime.domain.ThreadId

/**
 * Persistent memory store. Owned by the
 * [dev.charaly.runtime.domain.StoryInstance]; nothing else may hold authoritative
 * memory state.
 *
 * The store is a *set*, not a log. Memories are added, superseded, consolidated and
 * forgotten through explicit operations, and retrieval always goes through
 * [retrieve] so that visibility and the deterministic scoring rules cannot be
 * bypassed by a caller that "just wants the last twenty".
 */
@kotlinx.serialization.Serializable
data class MemoryStore(
    val memories: Map<MemoryId, Memory> = emptyMap(),
) {
    val size: Int get() = memories.size

    fun byId(id: MemoryId): Memory? = memories[id]

    /** Every memory owned by [characterId], oldest first. */
    fun of(characterId: CharacterId): List<Memory> =
        memories.values.filter { it.characterId == characterId }.sortedWith(DETERMINISTIC)

    fun all(): List<Memory> = memories.values.sortedWith(DETERMINISTIC)

    /** Current memories only: superseded ones are kept as history, never retrieved. */
    fun current(): List<Memory> = all().filter { it.isCurrent }

    fun add(memory: Memory): MemoryStore = copy(memories = memories + (memory.id to memory))

    fun addAll(items: Collection<Memory>): MemoryStore {
        if (items.isEmpty()) return this
        val merged = memories.toMutableMap()
        items.forEach { merged[it.id] = it }
        return copy(memories = merged)
    }

    fun remove(id: MemoryId): MemoryStore = copy(memories = memories - id)

    fun removeAll(ids: Collection<MemoryId>): MemoryStore =
        copy(memories = memories.filterKeys { it !in ids.toSet() })

    fun idsOf(characterId: CharacterId): List<MemoryId> = of(characterId).map { it.id }

    /** Applies a transformation to one memory, if it exists. */
    fun update(id: MemoryId, transform: (Memory) -> Memory): MemoryStore {
        val existing = memories[id] ?: return this
        return copy(memories = memories + (id to transform(existing)))
    }

    fun pin(id: MemoryId, pinned: Boolean = true): MemoryStore =
        update(id) { it.copy(pinned = pinned) }

    /**
     * "Forget" a memory.
     *
     * This is a domain operation rather than a row deletion so the rest of the world
     * stays consistent: a forgotten memory is marked invalid, dropped from retrieval,
     * and stripped out of the owning character's runtime index.
     */
    fun forget(id: MemoryId, at: StoryTime): MemoryStore =
        update(id) { it.copy(validUntil = at, supersededBy = SUPPRESSED) }

    // ------------------------------------------------------------------
    // Retrieval
    // ------------------------------------------------------------------

    /**
     * Memories [viewer] is allowed to see, ranked by the deterministic score.
     *
     * The contract that matters: **a memory the viewer may not see can never be
     * returned here**, regardless of how relevant it scores. Visibility is applied
     * before scoring, not after.
     */
    fun retrieve(
        viewer: MemorySubject,
        limit: Int = 8,
        atLocation: LocationId? = null,
        involving: Set<CharacterId> = emptySet(),
        activeThreads: Set<ThreadId> = emptySet(),
        tiers: Set<MemoryTier> = setOf(
            MemoryTier.SCENE,
            MemoryTier.EPISODIC,
            MemoryTier.CANON,
            MemoryTier.RELATIONSHIP,
        ),
        query: String = "",
        now: StoryTime = StoryTime.START,
    ): List<Memory> {
        if (limit <= 0) return emptyList()
        return visibleTo(viewer, tiers)
            .asSequence()
            .filter { atLocation == null || it.relatedLocationId == null || it.relatedLocationId == atLocation }
            .filter { involving.isEmpty() || it.relatedCharacterIds.any { id -> id in involving } || it.characterId in involving }
            .filter { activeThreads.isEmpty() || it.relatedThreadIds.any { t -> t in activeThreads } }
            .map { MemoryScore.of(it, now, atLocation, involving, activeThreads, query) }
            .sortedWith(MemoryScore.ORDER)
            .take(limit)
            .map { it.memory }
            .toList()
    }

    /** Everything [viewer] may see in [tiers], before scoring. */
    fun visibleTo(viewer: MemorySubject, tiers: Set<MemoryTier> = MemoryTier.entries.toSet()): List<Memory> =
        memories.values
            .filter { it.isCurrent }
            .filter { it.tier in tiers }
            .filter { it.visibleTo(viewer) }
            .sortedWith(DETERMINISTIC)

    /**
     * Deterministic selection used by the ContextBuilder.
     *
     * Retained as the name the rest of the runtime already calls, and now
     * visibility-aware: it delegates to [retrieve] rather than selecting by
     * importance alone.
     */
    fun select(
        characterId: CharacterId,
        limit: Int = 8,
        minImportance: Int = 1,
        atLocation: LocationId? = null,
        involving: Set<CharacterId> = emptySet(),
    ): List<Memory> = retrieve(
        viewer = MemorySubject.Character(characterId),
        limit = limit,
        atLocation = atLocation,
        involving = involving,
    ).filter { it.importance >= minImportance }

    /**
     * The exact list [select] would return for a character in a scene.
     *
     * ## Why a second entry point rather than making callers pass a Scene
     *
     * [ContextBuilder]'s cache needs to know *which* memories a prompt's memory section
     * is built from, so it can fingerprint them. That is the same selection the builder
     * is about to perform, and duplicating its predicate here would guarantee the two
     * drift apart - after which a cache would happily serve memories that are no longer
     * visible to that character.
     *
     * So this is the single implementation, and [select] is kept as the plain-id form
     * that existing callers use.
     */
    fun selectedFor(
        characterId: CharacterId,
        scene: dev.charaly.runtime.domain.Scene,
        limit: Int = 8,
    ): List<Memory> = select(
        characterId = characterId,
        limit = limit,
        atLocation = scene.locationId,
        involving = scene.participantSet() - characterId,
    )

    // ------------------------------------------------------------------
    // Queries used by the UI and the developer panel
    // ------------------------------------------------------------------

    fun tier(tier: MemoryTier): List<Memory> = current().filter { it.tier == tier }

    fun pinned(): List<Memory> = current().filter { it.pinned }

    /** Count per tier, for the memory panel's tab counts. */
    fun countsByTier(): Map<MemoryTier, Int> =
        MemoryTier.entries.associateWith { tier -> current().count { it.tier == tier } }

    companion object {
        val EMPTY = MemoryStore()

        /** Marker used by [forget] to retire a memory without naming a replacement. */
        val SUPPRESSED: MemoryId = MemoryId("memory-suppressed")

        val DETERMINISTIC: Comparator<Memory> = compareBy({ it.createdAt }, { it.id.value })
    }
}

/**
 * Deterministic relevance scoring.
 *
 * Charaly never lets the model decide what is important. Instead a memory's
 * relevance is a pure function of metadata the runtime already knows: how important
 * it was declared, how long ago it happened, who is in the room, where they are, and
 * which story threads are live.
 *
 * Every term is an integer and the sort is total (ties break on id), so retrieval is
 * reproducible on a replay and assertable in a test.
 */
data class MemoryScore(
    val memory: Memory,
    val importance: Int,
    val recency: Int,
    val participantRelevance: Int,
    val locationRelevance: Int,
    val threadRelevance: Int,
    val relationshipRelevance: Int,
    val frequency: Int,
    val topical: Int,
    val pinned: Int,
) {
    val total: Int
        get() = importance + recency + participantRelevance + locationRelevance +
            threadRelevance + relationshipRelevance + frequency + topical + pinned

    companion object {
        /** Total order: score first, then most recent, then id for stability. */
        val ORDER: Comparator<MemoryScore> = compareByDescending<MemoryScore> { it.total }
            .thenByDescending { it.memory.createdAt }
            .thenBy { it.memory.id.value }

        /**
         * @param atLocation where the scene currently is
         * @param participants who else is in the scene
         * @param activeThreads threads live in this scene
         * @param query optional words from the user's latest line
         */
        fun of(
            memory: Memory,
            now: StoryTime,
            atLocation: LocationId?,
            participants: Set<CharacterId>,
            activeThreads: Set<ThreadId>,
            query: String = "",
        ): MemoryScore = MemoryScore(
            memory = memory,
            importance = memory.importance,
            recency = recency(memory, now),
            participantRelevance = if (memory.characterId in participants || participants.any { it in memory.relatedCharacterIds }) 2 else 0,
            locationRelevance = if (atLocation != null && memory.relatedLocationId == atLocation) 2 else 0,
            threadRelevance = if (memory.relatedThreadIds.any { it in activeThreads }) 3 else 0,
            relationshipRelevance = if (memory.tier == MemoryTier.RELATIONSHIP) 2 else 0,
            frequency = minOf(memory.accessCount, 3),
            topical = topicalOverlap(memory, query),
            pinned = if (memory.pinned) 4 else 0,
        )

        /**
         * Recency as a 0..3 band: this scene, today, this week, older.
         *
         * Banded rather than linear so a very old but very important memory is not
         * permanently outranked by a trivial one from two minutes ago.
         */
        private fun recency(memory: Memory, now: StoryTime): Int {
            val age = memory.createdAt.minutesUntil(now)
            return when {
                age <= 60L -> 3
                age <= 8 * 60L -> 2
                age <= 7 * 24 * 60L -> 1
                else -> 0
            }
        }

        /**
         * Keyword overlap between the user's line and the memory.
         *
         * This is a plain token intersection, not an embedding. It is enough to keep
         * "we should talk about the notebook" pointing at the notebook, it is fully
         * deterministic, and it keeps the app entirely local. If embeddings are ever
         * added they slot in behind this function without changing any ownership rule.
         */
        private fun topicalOverlap(memory: Memory, query: String): Int {
            if (query.isBlank()) return 0
            val haystack = memoryTokens(memory)
            val asked = tokenize(query)
            if (asked.isEmpty() || haystack.isEmpty()) return 0
            val hits = asked.count { it in haystack }
            return when {
                hits >= 3 -> 3
                hits == 2 -> 2
                hits == 1 -> 1
                else -> 0
            }
        }

        fun memoryTokens(memory: Memory): Set<String> =
            tokenize(memory.content) + tokenize(memory.subject) + tokenize(memory.predicate)

        /**
         * Token-overlap similarity of two memories, 0..1.
         *
         * Lives here rather than in [MemoryConsolidator] because retrieval, dedup and
         * the health analyzer all need it, and three private copies of one Jaccard
         * implementation is how they drift apart.
         */
        fun similarityOf(a: Memory, b: Memory): Double {
            val left = memoryTokens(a)
            val right = memoryTokens(b)
            if (left.isEmpty() || right.isEmpty()) return 0.0
            val union = left.union(right).size
            if (union == 0) return 0.0
            return left.intersect(right).size.toDouble() / union.toDouble()
        }

        /** Lowercased word tokens with a small stop-word list. Deterministic. */
        fun tokenize(text: String): Set<String> =
            text.lowercase()
                .split(Regex("[^a-z0-9']+"))
                .asSequence()
                .map { it.trim('\'') }
                .filter { it.length >= 3 && it !in STOP_WORDS }
                .toSet()

        private val STOP_WORDS = setOf(
            "the", "and", "for", "but", "not", "you", "her", "his", "she", "him",
            "they", "them", "was", "were", "are", "with", "that", "this", "from",
            "have", "has", "had", "been", "will", "would", "could", "should", "there",
            "their", "then", "than", "when", "what", "who", "why", "how", "all",
            "into", "out", "about", "after", "before", "over", "some", "just", "very",
        )
    }
}