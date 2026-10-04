package dev.charaly.runtime.domain.memory

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.FactId
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.MemoryId
import dev.charaly.runtime.domain.StoryTime
import kotlinx.serialization.Serializable

/**
 * A persistent memory owned by one character.
 *
 * Structured and explicit at MVP level. No embeddings: retrieval is importance +
 * recency + explicit scene/character filters, which is deterministic and
 * testable. If embeddings are ever added they belong *here* (behind
 * [MemoryStore]) and must not change the ownership rules.
 */
@Serializable
data class Memory(
    val id: MemoryId,
    val characterId: CharacterId,
    val content: String,
    /** 1 (trivial) .. 5 (defining) */
    val importance: Int = 3,
    val createdAt: StoryTime = StoryTime.START,
    val source: MemorySource = MemorySource.EVENT,
    val relatedCharacterIds: List<CharacterId> = emptyList(),
    val relatedLocationId: LocationId? = null,
    val relatedFactIds: List<FactId> = emptyList(),
    val sceneId: String? = null,
) {
    init {
        require(content.isNotBlank()) { "Memory $id must have content" }
        require(importance in 1..5) { "importance must be in 1..5, was $importance" }
    }
}

@Serializable
enum class MemorySource {
    /** Derived from an authoritative world event (highest trust). */
    EVENT,
    /** Derived from something a character said in conversation. */
    DIALOGUE,
    /** Derived from the user's narration. */
    USER_INPUT,
    /** Authored by hand in the story pack. */
    AUTHORED,
    IMPORTED,
}

/**
 * Persistent memory store. Owned by the [dev.charaly.runtime.domain.StoryInstance];
 * nothing else may hold authoritative memory state.
 */
@Serializable
data class MemoryStore(
    val memories: Map<MemoryId, Memory> = emptyMap(),
) {
    val size: Int get() = memories.size

    fun byId(id: MemoryId): Memory? = memories[id]

    fun of(characterId: CharacterId): List<Memory> =
        memories.values.filter { it.characterId == characterId }.sortedWith(DETERMINISTIC)

    fun all(): List<Memory> = memories.values.sortedWith(DETERMINISTIC)

    fun add(memory: Memory): MemoryStore = copy(memories = memories + (memory.id to memory))

    fun addAll(items: Collection<Memory>): MemoryStore {
        if (items.isEmpty()) return this
        val merged = memories.toMutableMap()
        items.forEach { merged[it.id] = it }
        return copy(memories = merged)
    }

    fun remove(id: MemoryId): MemoryStore = copy(memories = memories - id)

    fun idsOf(characterId: CharacterId): List<MemoryId> = of(characterId).map { it.id }

    /**
     * Deterministic selection used by the ContextBuilder: highest importance
     * first, then most recent, then id for stability.
     */
    fun select(
        characterId: CharacterId,
        limit: Int = 8,
        minImportance: Int = 1,
        atLocation: LocationId? = null,
        involving: Set<CharacterId> = emptySet(),
    ): List<Memory> {
        if (limit <= 0) return emptyList()
        return of(characterId)
            .asSequence()
            .filter { it.importance >= minImportance }
            .filter { atLocation == null || it.relatedLocationId == null || it.relatedLocationId == atLocation }
            .filter { involving.isEmpty() || it.relatedCharacterIds.any { id -> id in involving } || it.characterId in involving }
            .sortedWith(
                compareByDescending<Memory> { it.importance }
                    .thenByDescending { it.createdAt }
                    .thenBy { it.id.value },
            )
            .take(limit)
            .toList()
    }

    companion object {
        val EMPTY = MemoryStore()

        val DETERMINISTIC: Comparator<Memory> = compareBy({ it.createdAt }, { it.id.value })
    }
}
