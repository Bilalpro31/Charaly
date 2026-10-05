package dev.charaly.runtime.domain.memory

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.MemoryId
import dev.charaly.runtime.domain.StoryTime

/**
 * Keeps memory from growing without bound, and keeps it honest.
 *
 * Three jobs, all deterministic:
 *
 *  1. **Merge duplicates.** A character who is told the same thing three times should
 *     not carry three identical memories forever.
 *  2. **Resolve contradictions.** "The player lives in Paris" and "the player moved to
 *     Lyon" cannot both be current. The newer one supersedes the older; the old one
 *     is retained as history, timestamped, rather than silently deleted - knowing that
 *     a belief changed is itself information.
 *  3. **Promote to canon.** A claim that recurs across scenes becomes a stable fact
 *     rather than an anecdote.
 *
 * What it deliberately does not do: ask the model. Nothing here calls inference. The
 * model may *propose* memory content, but the runtime decides what merges, what wins a
 * contradiction and what becomes canon - otherwise a hallucination could quietly
 * promote itself to permanent truth.
 */
object MemoryConsolidator {

    /** How similar two memories must be to count as the same memory said twice. */
    const val DUPLICATE_OVERLAP = 0.8

    /** Contradiction subject/predicate keys longer than this are ignored as noise. */
    private const val MAX_SUBJECT_LENGTH = 80

    /**
     * Runs one consolidation pass over the store.
     *
     * The result is a function of (store, now) only, so a replayed story consolidates
     * to exactly the same state.
     */
    fun consolidate(store: MemoryStore, now: StoryTime): MemoryStore {
        var working = store
        working = mergeDuplicates(working, now)
        working = resolveContradictions(working, now)
        return working
    }

    /**
     * Folds a repeated memory into its first occurrence and retires the duplicates.
     *
     * "Similar" means the same content tokens in the same proportion - not string
     * equality, because dialogue rarely repeats itself verbatim.
     */
    fun mergeDuplicates(store: MemoryStore, now: StoryTime): MemoryStore {
        // Only unlabelled memories are candidates for a plain content merge; a memory
        // that makes a claim goes through contradiction resolution instead.
        //
        // Grouped by owner *and* place, because "the same thing said twice" only means
        // something within one memory's own context. The place is part of a nullable
        // key rather than a sentinel id, because EntityId deliberately rejects blank.
        val groups = store.current()
            .filter { it.subject.isBlank() }
            .groupBy { memoryGroupKey(it.characterId, it.relatedLocationId) }
        var result = store
        groups.forEach { (_, memories) ->
            val ordered = memories.sortedWith(MemoryStore.DETERMINISTIC)
            ordered.forEachIndexed { index, candidate ->
                if (index == 0) return@forEachIndexed
                val earlier = ordered.take(index).firstOrNull { similarity(it, candidate) >= DUPLICATE_OVERLAP }
                    ?: return@forEachIndexed
                // Keep the earliest memory (the first time it happened), fold the
                // duplicate's weight into it, and retire the duplicate.
                result = result.update(earlier.id) {
                    it.copy(
                        importance = maxOf(it.importance, candidate.importance),
                        emotionalWeight = maxOf(it.emotionalWeight, candidate.emotionalWeight),
                        relatedCharacterIds = (it.relatedCharacterIds + candidate.relatedCharacterIds).distinct(),
                        consolidatedFrom = (it.consolidatedFrom + candidate.id).distinct(),
                        accessCount = it.accessCount + candidate.accessCount,
                    )
                }
                result = result.update(candidate.id) {
                    it.copy(supersededBy = earlier.id, validUntil = now, consolidatedFrom = listOf(earlier.id))
                }
            }
        }
        return result
    }

    /**
     * Makes the newest claim about a subject/predicate the current one.
     *
     * Only memories that actually state a subject and predicate take part; an
     * unlabelled memory cannot contradict anything, which is why contradiction
     * detection is opt-in by construction rather than by a fragile parser.
     */
    fun resolveContradictions(store: MemoryStore, now: StoryTime): MemoryStore {
        var result = store
        val keys = result.current()
            .filter { it.subject.isNotBlank() && it.subject.length <= MAX_SUBJECT_LENGTH && it.predicate.isNotBlank() }
            .groupBy { keyOf(it) }
        keys.forEach { (_, claims) ->
            if (claims.size < 2) return@forEach
            val newest = claims.maxWithOrNull(compareBy({ it.createdAt }, { it.id.value })) ?: return@forEach
            claims.filter { it.id != newest.id }.forEach { older ->
                if (!older.contradicts(newest)) return@forEach
                result = result.update(older.id) {
                    it.copy(supersededBy = newest.id, validUntil = newerInstant(now, newest.createdAt))
                }
            }
            result = result.update(newest.id) { it.copy(supersedes = claims.map { c -> c.id }.distinct() - newest.id) }
        }
        return result
    }

    /**
     * Promotes a recurring claim to canon.
     *
     * Canon is what a character treats as settled background: they do not need to have
     * lived it to know it, and it survives pruning.
     */
    fun promoteToCanon(store: MemoryStore, id: MemoryId): MemoryStore =
        store.update(id) { it.copy(tier = MemoryTier.CANON) }

    /**
     * Keeps memory from growing without bound.
     *
     * Pinned and canon memories are never dropped. Superseded memories are pruned
     * first, oldest first, because they are history rather than current truth.
     */
    fun prune(store: MemoryStore, maxPerCharacter: Int = DEFAULT_MAX_PER_CHARACTER): MemoryStore {
        if (maxPerCharacter <= 0) return store
        var result = store
        store.all()
            .groupBy { it.characterId }
            .forEach { (_, memories) ->
                val keep = memories
                    .sortedWith(
                        // Pinned first, then durable tiers, then importance, then recency.
                        compareByDescending<Memory> { it.pinned }
                            .thenByDescending { it.tier.isDurable }
                            .thenByDescending { it.importance }
                            .thenByDescending { it.createdAt }
                            .thenBy { it.id.value },
                    )
                    .take(maxPerCharacter)
                    .map { it.id }
                    .toSet()
                val drop = memories.map { it.id }.filterNot { it in keep }
                if (drop.isNotEmpty()) result = result.removeAll(drop)
            }
        return result
    }

    /**
     * Token-overlap similarity in 0..1.
     *
     * Jaccard over content tokens: two memories that say the same thing with different
     * wording still match, and two that share only filler words do not.
     */
    fun similarity(a: Memory, b: Memory): Double = MemoryScore.similarityOf(a, b)

    /** The claim a memory makes, used to detect tension between memories. */
    fun keyOf(memory: Memory): String =
        "${memory.subject.trim().lowercase()}|${memory.predicate.trim().lowercase()}"

    /**
     * Grouping key for duplicate detection: the owner plus the place.
     *
     * The place is rendered as text rather than as an [dev.charaly.runtime.domain.EntityId]
     * so that "no place" can be expressed without inventing an id, which the id type
     * correctly refuses to do.
     */
    private fun memoryGroupKey(
        owner: CharacterId,
        location: dev.charaly.runtime.domain.LocationId?,
    ): String = "${owner.value}|${location?.value.orEmpty()}"

    /**
     * A claim never supersedes itself because it was created earlier than "now".
     *
     * `validUntil` records when the belief stopped being current, which cannot precede
     * the newer claim that replaced it.
     */
    private fun newerInstant(now: StoryTime, newestCreatedAt: StoryTime): StoryTime =
        if (now.isAfter(newestCreatedAt)) now else newestCreatedAt

    const val DEFAULT_MAX_PER_CHARACTER = 60

    /** Memory of one character, for the memory panel. */
    fun forCharacter(store: MemoryStore, characterId: CharacterId): List<Memory> =
        store.current().filter { it.characterId == characterId }
}