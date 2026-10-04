package dev.charaly.runtime.domain.knowledge

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.FactId
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.StoryTime
import kotlinx.serialization.Serializable

/**
 * WORLD TRUTH: a fact that is objectively true inside the story instance.
 *
 * Truth lives in the [KnowledgeStore], not in any character's head. A character
 * only knows a [Fact] when the store explicitly records that character's
 * knowledge of it (see [KnowledgeEntry]). This is the mechanism that stops the
 * ContextBuilder from leaking omniscient world state into a character's prompt.
 */
@Serializable
data class Fact(
    val id: FactId,
    val subject: String = "",
    val predicate: String = "",
    val description: String = "",
    val locationId: LocationId? = null,
    val involvedCharacters: List<CharacterId> = emptyList(),
    /** Secrets require an explicit discovery event before anyone can know them. */
    val secret: Boolean = false,
    val tags: List<String> = emptyList(),
    val recordedAt: StoryTime = StoryTime.START,
) {
    init {
        require(
            description.isNotBlank() || (subject.isNotBlank() && predicate.isNotBlank()),
        ) { "Fact $id needs a description or a subject/predicate pair" }
    }

    fun render(): String = if (description.isNotBlank()) {
        description
    } else {
        "$subject $predicate".trim()
    }
}

/**
 * One character's belief about one world fact.
 *
 * `confidence` and `via` exist so the runtime can model partial knowledge later
 * (e.g. "Bob believes the key is in the library, he heard it") without
 * changing the storage shape.
 */
@Serializable
data class KnowledgeEntry(
    val factId: FactId,
    val learnedAt: StoryTime,
    val confidence: Int = 100,
    val via: String = "",
) {
    init {
        require(confidence in 0..100) { "confidence out of range: $confidence" }
    }
}

/**
 * Persistent, structured knowledge store.
 *
 * MVP scope: an explicit fact table plus explicit per-character knowledge sets.
 * No vector database, no embeddings - visibility must be *provable*, not
 * statistically guessed.
 */
@Serializable
data class KnowledgeStore(
    /** Objective world truth. */
    val truth: Map<FactId, Fact> = emptyMap(),
    /** What each character knows. */
    val knowledge: Map<CharacterId, List<KnowledgeEntry>> = emptyMap(),
) {
    val factCount: Int get() = truth.size

    fun fact(id: FactId): Fact? = truth[id]

    fun knows(characterId: CharacterId, factId: FactId): Boolean =
        knowledge[characterId].orEmpty().any { it.factId == factId }

    fun entry(characterId: CharacterId, factId: FactId): KnowledgeEntry? =
        knowledge[characterId].orEmpty().firstOrNull { it.factId == factId }

    /** Facts a character is allowed to be told about, in deterministic order. */
    fun visibleTo(characterId: CharacterId): List<Fact> =
        knowledge[characterId].orEmpty()
            .sortedWith(compareBy({ it.learnedAt }, { it.factId.value }))
            .mapNotNull { truth[it.factId] }

    fun withFact(fact: Fact): KnowledgeStore = copy(truth = truth + (fact.id to fact))

    fun withFacts(facts: Collection<Fact>): KnowledgeStore =
        copy(truth = truth + facts.associateBy { it.id })

    fun withKnowledge(characterId: CharacterId, entries: Collection<KnowledgeEntry>): KnowledgeStore =
        copy(knowledge = knowledge + (characterId to mergeEntries(knowledge[characterId], entries)))

    fun forget(characterId: CharacterId, factId: FactId): KnowledgeStore {
        val current = knowledge[characterId] ?: return this
        return copy(knowledge = knowledge + (characterId to current.filterNot { it.factId == factId }))
    }

    private fun mergeEntries(
        existing: List<KnowledgeEntry>?,
        incoming: Collection<KnowledgeEntry>,
    ): List<KnowledgeEntry> {
        val merged = LinkedHashMap<FactId, KnowledgeEntry>()
        existing.orEmpty().forEach { merged[it.factId] = it }
        incoming.forEach { merged[it.factId] = it }
        return merged.values.toList()
    }

    /** Characters who know a given fact - used by the debug inspector. */
    fun knownBy(factId: FactId): List<CharacterId> =
        knowledge.entries.filter { (_, entries) -> entries.any { it.factId == factId } }.map { it.key }

    companion object {
        val EMPTY = KnowledgeStore()
    }
}
