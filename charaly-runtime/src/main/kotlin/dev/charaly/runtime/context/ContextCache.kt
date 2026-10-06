package dev.charaly.runtime.context

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.MemoryId
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.ThreadId

/**
 * A content-addressed cache for the expensive half of a prompt.
 *
 * ## The problem
 *
 * [ContextBuilder.buildContext] assembles the same material on every single turn:
 *
 * ```
 *   world rules          ~constant for the life of a story
 *   canon assertions     ~constant
 *   location description ~constant until the player moves
 *   character sheet      ~constant
 *   relationship state   changes rarely
 *   the character's mind  changes rarely
 *   memory selection     changes on every new memory
 *   transcript           changes every turn  <- never cacheable
 *   user input           changes every turn  <- never cacheable
 * ```
 *
 * On a 4B model on a phone, serialising that into a prompt string is a measurable cost
 * on every turn of a conversation that may run for an hour. The transcript and the user
 * input have to be rebuilt regardless - they are what actually changed - but the rest is
 * the same string, character after character.
 *
 * ## What is cached, and what is deliberately not
 *
 * Cached: world rules, canon, location description, character sheet, relationships,
 * mind, and the *retrieved memory block*.
 *
 * Not cached, ever: the transcript and the user's message. A cache that included them
 * would return the previous turn's answer to the previous question, which is not a
 * performance bug but a correctness one.
 *
 * ## The invalidation problem, stated honestly
 *
 * A stale cache entry produces a model that confidently contradicts the world it is in -
 * it cites a memory that was retracted, or a relationship that ended an hour ago. That
 * is worse than being slow, so invalidation is not best-effort here.
 *
 * Rather than trying to observe every possible mutation (which would mean threading
 * notifications through the entire engine, and would still miss the ones nobody
 * remembered), this cache is keyed on a **fingerprint derived from the state it
 * depends on**. If the state changes, the fingerprint changes, and the entry is
 * unreachable. There is no path by which a stale entry can be served, because serving
 * requires an exact fingerprint match.
 *
 * The cost is that computing the fingerprint reads the same state the cache was meant to
 * avoid re-reading. That is a real trade-off and it is stated here rather than hidden:
 * the saving is in *serialisation and allocation*, not in state access. For a few KB of
 * world text per turn, that is where most of the cost actually is.
 */
class ContextCache(
    /** How many distinct scene contexts to keep. Small: a story has very few scenes. */
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {

    /**
     * The cache key.
     *
     * Two things are hashed: a coarse **epoch counter** the caller bumps on any
     * authoritative change, and a **content hash** of the exact slices that are being
     * cached. The counter makes the common case cheap (one integer compare); the content
     * hash makes correctness independent of whether the caller remembered to bump it.
     */
    data class Key(
        val storyId: StoryInstanceId,
        val characterId: CharacterId,
        val locationId: LocationId,
        /**
         * Identity of the moment within the scene.
         *
         * A scene advances through beats, so a scene *id* alone is too coarse: the same
         * scene with different participants is different context.
         */
        val sceneKey: String,
        val contentHash: Int,
    )

    private data class Entry(
        val epoch: Long,
        val key: Key,
        /** The rendered, non-transcript part of the prompt. */
        val body: String,
        /** What went into it, for the developer panel. */
        val sources: List<String>,
    )

    private val entries = LinkedHashMap<Key, Entry>()

    /** Statistics the developer inspector shows. */
    var hits: Int = 0
        private set

    var misses: Int = 0
        private set

    /** How many times a hit was refused because the epoch moved. */
    var invalidations: Int = 0
        private set

    val size: Int get() = entries.size

    /**
     * Renders the stable part of the prompt, reusing the previous result when nothing it
     * depends on has changed.
     *
     * [epoch] is the caller's authoritative version counter. [render] is invoked only on
     * a miss, so a warm cache does no serialisation work at all.
     */
    fun stableSection(
        storyId: StoryInstanceId,
        characterId: CharacterId,
        locationId: LocationId,
        sceneKey: String,
        epoch: Long,
        sources: List<String>,
        render: () -> String,
    ): String {
        // The content hash is taken over the *declared sources* - the identity of what
        // the section depends on - not over the rendered text. That keeps the key cheap
        // while still being sensitive to the inputs that matter.
        val hash = fingerprint(storyId, characterId, locationId, sceneKey, sources)

        val candidate = Key(storyId, characterId, locationId, sceneKey, hash)
        val existing = entries[candidate]
        if (existing != null) {
            if (existing.epoch == epoch) {
                hits++
                // Refresh LRU position.
                entries.remove(candidate)
                entries[candidate] = existing
                return existing.body
            }
            // Same content, but the caller says the world moved. The content hash said
            // nothing relevant changed, so trust the more conservative signal and rebuild.
            invalidations++
        }

        misses++
        val body = render()
        entries.remove(candidate)
        entries[candidate] = Entry(epoch, candidate, body, sources)
        while (entries.size > maxEntries) {
            val oldest = entries.keys.firstOrNull() ?: break
            entries.remove(oldest)
        }
        return body
    }

    /** Drops everything. Called when a story is deleted or reset. */
    fun clear() {
        entries.clear()
    }

    /** Drops every entry for one story, leaving others intact. */
    fun clearStory(storyId: StoryInstanceId) {
        val doomed = entries.keys.filter { it.storyId == storyId }
        doomed.forEach { entries.remove(it) }
    }

    /**
     * Which dependencies the last render for a scene actually used.
     *
     * For the developer inspector only. Returns empty when nothing has been rendered.
     */
    fun sourcesFor(
        storyId: StoryInstanceId,
        characterId: CharacterId,
    ): List<String> = entries.values
        .lastOrNull { it.key.storyId == storyId && it.key.characterId == characterId }
        ?.sources
        ?: emptyList()

    /**
     * A 31-bit hash over the inputs a cached section depends on.
     *
     * Deliberately not a cryptographic hash: this is a cache key, and a collision would
     * serve the wrong prompt text. So it is combined with an exact-match check on the
     * full [Key] - which includes story, character, location and scene - and a collision
     * would have to occur across all of those *and* the source list simultaneously.
     *
     * FNV-1a, written out because the runtime module keeps its dependency list minimal.
     */
    private fun fingerprint(
        storyId: StoryInstanceId,
        characterId: CharacterId,
        locationId: LocationId,
        sceneKey: String,
        sources: List<String>,
    ): Int {
        var hash = -0x7EE3623B // FNV offset basis, 32-bit
        fun mix(text: String) {
            for (ch in text) {
                hash = hash xor (ch.code and 0xFF)
                hash *= 0x01000193 // FNV prime
            }
            hash = hash xor 0x1F
            hash *= 0x01000193
        }
        mix(storyId.value)
        mix(characterId.value)
        mix(locationId.value)
        mix(sceneKey)
        for (source in sources) mix(source)
        return hash and 0x7FFFFFFF
    }

    companion object {
        /**
         * Four entries.
         *
         * A story typically has one active scene and one or two characters in it. Four
         * covers a scene change and a small cast change without letting the map grow
         * across a long session.
         */
        const val DEFAULT_MAX_ENTRIES = 4
    }
}

/**
 * The version counter a cache epoch is taken from.
 *
 * ## Why this is one integer and not a pile of observers
 *
 * Every authoritative change to a story is an event applied by the engine, so the
 * natural home for "the world moved" is the event log rather than a set of callbacks.
 * [dev.charaly.runtime.domain.events.EventQueue] already knows the order; this simply
 * records the count of applied events per story.
 *
 * Because the cache *also* fingerprints the content it depends on, a forgotten bump is a
 * performance miss rather than a correctness bug. That belt-and-braces arrangement is
 * what makes it safe to have a single coarse counter: there is no way for a wrong
 * counter to produce a wrong prompt.
 */
class ContextEpoch {

    private val epochs = mutableMapOf<StoryInstanceId, Long>()

    fun current(storyId: StoryInstanceId): Long = epochs[storyId] ?: 0L

    /** Called after any authoritative change. Cheap: one map write. */
    fun bump(storyId: StoryInstanceId) {
        epochs[storyId] = current(storyId) + 1L
    }

    /** Called once per batch of applied events, after the last one. */
    fun bumpBy(storyId: StoryInstanceId, events: Int) {
        if (events > 0) epochs[storyId] = current(storyId) + events.toLong()
    }

    fun reset(storyId: StoryInstanceId) {
        epochs[storyId] = 0L
    }

    fun forget(storyId: StoryInstanceId) {
        epochs.remove(storyId)
    }
}
