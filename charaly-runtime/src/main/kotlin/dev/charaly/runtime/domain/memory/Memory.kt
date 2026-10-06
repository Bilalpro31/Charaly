package dev.charaly.runtime.domain.memory

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.EventId
import dev.charaly.runtime.domain.FactId
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.MemoryId
import dev.charaly.runtime.domain.StoryTime
import dev.charaly.runtime.domain.ThreadId
import kotlinx.serialization.Serializable

/**
 * Who a memory belongs to in the hierarchy of knowledge.
 *
 * Charaly's memory is layered, not a flat list of the last N messages. Each tier
 * answers a different question and has a different lifetime:
 *
 * ```
 *  WORKING      the scene in progress.        minutes.  dies with the scene.
 *  SCENE        what happened in one scene.   hours.    the summary.
 *  EPISODIC     a specific thing that happened. days.   "the player found my notebook".
 *  CANON        a stable fact about the world. permanent. "Andre runs the shop".
 *  RELATIONSHIP what one character knows about another. permanent.
 * ```
 *
 * The tier is assigned deterministically by the runtime. The model may *propose*
 * content, but it never decides what tier something becomes or whether it survives
 * consolidation - otherwise a hallucination could quietly promote itself to canon.
 */
@Serializable
enum class MemoryTier {
    /** Immediate context for the scene being generated. */
    WORKING,

    /** The durable summary of one completed scene. */
    SCENE,

    /** A specific past event, with a time and a place. */
    EPISODIC,

    /**
     * A distilled fact that no longer belongs to one moment.
     *
     * Distinct from [CANON] because a semantic memory is still *this character's*
     * understanding - they may be wrong - whereas canon is what the world actually
     * holds. Collapsing the two is how a character's mistake becomes permanent truth.
     */
    SEMANTIC,

    /** Authoritative world truth. Merged and superseded rather than accumulated. */
    CANON,

    /** Character-specific history: "the player has bought ice cream three times". */
    RELATIONSHIP,

    /**
     * Something this character learned about *someone*.
     *
     * Character-scoped by construction: a [CHARACTER] memory about Ladybug's identity
     * is retrievable by its owner and nobody else, which is the same rule
     * [MemoryVisibility] enforces. The tier records the *kind* of claim; visibility
     * decides who may read it.
     */
    CHARACTER,

    /** Something about the world as such: "the museum wing closed in March". */
    WORLD,

    /** Progress and state of one [dev.charaly.runtime.domain.StoryThread]. */
    THREAD,

    /** A secret. Always paired with a restrictive visibility. */
    SECRET,

    /** A commitment made by someone, still outstanding. */
    PROMISE,

    /** An objective someone is currently pursuing. */
    GOAL,

    /** A decision the player made that will have an effect later. */
    CONSEQUENCE,
    ;

    /** Whether memories in this tier are expected to be kept indefinitely. */
    val isDurable: Boolean
        get() = this == CANON || this == RELATIONSHIP || this == CHARACTER ||
            this == THREAD || this == PROMISE || this == GOAL

    /**
     * Whether this tier describes something the character believes rather than
     * something the world asserts.
     *
     * Only [CANON] and [WORLD] are authoritative; everything else is one character's
     * understanding and may be mistaken. Presentation uses this to phrase a memory
     * as belief rather than fact.
     */
    val isSubjective: Boolean
        get() = this != CANON && this != WORLD

    /** Tiers that are only worth keeping while the scene they belong to is live. */
    val isEphemeral: Boolean get() = this == WORKING || this == SCENE
}

/**
 * Who may retrieve a memory.
 *
 * This is the single most important field in the memory system: it is what stops a
 * secret told to one character from surfacing in another character's prompt.
 *
 * Ownership alone is not enough, because the player is not a character and must be
 * able to see things no character knows. So visibility is stated explicitly and
 * checked explicitly, rather than inferred.
 */
@Serializable
enum class MemoryVisibility {
    /**
     * Anyone at all. Only for things that are simply true and public - the shop
     * exists, the school is closed on Sundays.
     */
    WORLD,

    /** The owner, plus anyone named in `visibleTo`. */
    CHARACTER,

    /** The player, plus anyone named in `visibleTo`. */
    PLAYER,

    /**
     * The owner and explicitly named characters only.
     *
     * Behaves like [CHARACTER] for retrieval, but additionally guarantees the memory
     * is never surfaced as ambient world colour, however relevant it looks.
     */
    SECRET,

    /** The owner alone. Nothing can widen this. */
    PRIVATE,
    ;

    /** Whether this visibility may ever be granted to a third party. */
    val isGrantable: Boolean get() = this != PRIVATE
}

/**
 * A persistent memory owned by one character (or by the player).
 *
 * Retrieval is importance + recency + explicit scene/character filters, all
 * deterministic and testable with no device, no embeddings and no network.
 *
 * ## Why there are no vector embeddings, and what would change if there were
 *
 * Charaly does not embed memories, and the omission is a decision rather than a gap. Three
 * properties depend on it, and only the third is about convenience:
 *
 * 1. **Visibility stays a gate, not a ranking.** `Memory.visibleTo` is a closed, total
 *    function, and a memory a character may not see can never be returned by retrieval
 *    regardless of how relevant it scores. An embedding-based index would have to filter
 *    *before* ranking for that to hold, which means the index can only ever be built over
 *    the memories a given subject may see - so there is no single index, and a shared one
 *    would leak by construction. The guarantee is structural rather than statistical, which
 *    is the whole point of it.
 * 2. **Retrieval stays deterministic.** The same story, replayed on the same build, prompts
 *    with the same memories in the same order. Embeddings introduce a numerical comparison
 *    whose result can change with a library version or a quantisation, which would make a
 *    replayed story diverge from the original for reasons no one could audit.
 * 3. **Prompt caching stays intact.** `ContextCache` keys on a fingerprint of the memories a
 *    prompt's memory section is built from. A retrieval step whose *selection* can change
 *    would invalidate that cache on nearly every turn, and llama.cpp's prefix cache - which
 *    is what makes a long context affordable on a phone - would stop paying for itself.
 *    (SillyTavern's own documentation warns about exactly this interaction: vectorised
 *    retrieval and prompt caching fight each other, and you pick one.)
 *
 * If embeddings are ever added they belong behind [MemoryStore], and they must change only
 * the *ordering* of memories that are already visible - never which ones are visible, never
 * whether a given retrieval is reproducible, and never whether the cache fingerprint still
 * describes the section that was actually sent.
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
    // ---- layered memory --------------------------------------------------
    val tier: MemoryTier = MemoryTier.EPISODIC,
    val visibility: MemoryVisibility = MemoryVisibility.CHARACTER,
    /** Explicit additional grants. The union of these and the owner may retrieve it. */
    val visibleTo: List<CharacterId> = emptyList(),
    /** 0..5. How strongly this memory carries feeling, independent of importance. */
    val emotionalWeight: Int = 0,
    /**
     * 0..100. How sure the owner is that this is true.
     *
     * Separate from [importance] on purpose: "I am certain the akuma was in the
     * museum" and "this barely matters to me" are independent claims, and conflating
     * them makes a vital half-belief indistinguishable from small talk.
     *
     * Nothing about retrieval *trusts* this number. It is the author's stated
     * confidence, used for scoring and for the developer inspector; a low-confidence
     * memory is still a real memory and is never silently dropped.
     */
    val confidence: Int = 100,
    /** The world event that produced this memory, for "inspect source". */
    val sourceEventId: EventId? = null,
    /**
     * The transcript line this memory came from, when it came from dialogue.
     *
     * "Explainable memory" depends on this: without it a memory has a source *kind*
     * but not a source, and the inspector can only say "from a conversation".
     */
    val sourceMessageId: String? = null,
    val relatedThreadIds: List<ThreadId> = emptyList(),
    /**
     * Subject/predicate of the claim this memory makes.
     *
     * Two memories with the same subject and predicate are in tension; the newer one
     * supersedes the older rather than both being kept as equally current.
     */
    val subject: String = "",
    val predicate: String = "",
    // ---- lifecycle -------------------------------------------------------
    /** Memories this one replaced. Empty for ordinary memories. */
    val supersedes: List<MemoryId> = emptyList(),
    /** Set when a newer memory has replaced this one. */
    val supersededBy: MemoryId? = null,
    val validFrom: StoryTime = StoryTime.START,
    /** Null while the memory is still current. */
    val validUntil: StoryTime? = null,
    /** Lower-tier memories rolled up into this one by consolidation. */
    val consolidatedFrom: List<MemoryId> = emptyList(),
    /** How often this memory has been folded into a prompt; drives frequency weight. */
    val accessCount: Int = 0,
    /** User pin: survives consolidation and pruning. */
    val pinned: Boolean = false,
) {
    init {
        require(content.isNotBlank()) { "Memory $id must have content" }
        require(importance in 1..5) { "importance must be in 1..5, was $importance" }
        require(emotionalWeight in 0..5) { "emotionalWeight must be in 0..5, was $emotionalWeight" }
        require(confidence in 0..100) { "confidence must be in 0..100, was $confidence" }
    }

    /** A superseded memory is history, not current truth. */
    val isCurrent: Boolean get() = supersededBy == null

    /**
     * Whether [viewer] is allowed to retrieve this memory.
     *
     * This is the knowledge boundary. It is a closed, total function - there is no
     * "probably fine" branch - and it is enforced here rather than at the call site so
     * no caller can forget it.
     */
    fun visibleTo(viewer: MemorySubject): Boolean = when (visibility) {
        MemoryVisibility.PRIVATE -> viewer.matchesOwner(characterId)
        MemoryVisibility.PLAYER -> viewer.isPlayer || viewer.matchesOwner(characterId) || viewer.id in visibleTo
        MemoryVisibility.CHARACTER -> viewer.matchesOwner(characterId) || viewer.id in visibleTo
        MemoryVisibility.SECRET -> viewer.matchesOwner(characterId) || viewer.id in visibleTo
        MemoryVisibility.WORLD -> true
    }

    /** True when [other] and this memory make incompatible claims about the same thing. */
    fun contradicts(other: Memory): Boolean =
        subject.isNotBlank() && other.subject.isNotBlank() &&
            subject.equals(other.subject, ignoreCase = true) &&
            predicate.equals(other.predicate, ignoreCase = true) &&
            !content.trim().equals(other.content.trim(), ignoreCase = true)

    fun supersedeBy(newer: MemoryId, at: StoryTime): Memory = copy(
        supersededBy = newer,
        validUntil = at,
    )

    fun withAccess(): Memory = copy(accessCount = accessCount + 1)
}

/**
 * Who is asking for a memory.
 *
 * The player is deliberately *not* a [CharacterId]: the player has no
 * [dev.charaly.runtime.domain.CharacterRuntime], nothing to move and nothing for the
 * engine to validate about them. Modelling them as a first-class subject here keeps
 * the visibility rules total.
 */
@Serializable
sealed interface MemorySubject {

    val isPlayer: Boolean get() = false

    /** The owning character id, or null for the player. */
    val id: CharacterId?

    fun matchesOwner(owner: CharacterId): Boolean = id == owner

    data class Character(override val id: CharacterId) : MemorySubject

    data object Player : MemorySubject {
        override val id: CharacterId? get() = null
        override val isPlayer: Boolean get() = true
    }

    companion object {
        fun of(id: CharacterId?): MemorySubject = if (id == null) Player else Character(id)
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
    /** Produced by consolidation rolling lower tiers together. */
    CONSOLIDATED,
    /** Imported from outside Charaly. */
    IMPORTED,
}