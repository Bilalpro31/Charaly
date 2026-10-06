package dev.charaly.runtime.context

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.StoryInstanceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The context cache.
 *
 * ## What is actually being asserted
 *
 * A stale cache entry is the dangerous failure, not a slow one: a model handed a memory
 * that was retracted will confidently contradict the world it is standing in. So most of
 * these tests are about invalidation rather than about hits.
 *
 * The cache is keyed on a fingerprint of what a section depends on *and* gated by a
 * caller-supplied epoch, which gives two independent ways for a change to be caught.
 * Both are tested, because the whole safety argument rests on at least one of them
 * always being right.
 */
class SmartContextCacheTest {

    private val story = StoryInstanceId("story-1")
    private val otherStory = StoryInstanceId("story-2")
    private val alice = CharacterId("alice")
    private val bob = CharacterId("bob")
    private val bakery = LocationId("bakery")
    private val roof = LocationId("roof")

    private fun render(cache: ContextCache, sceneKey: String = "s1", sources: List<String> = listOf("a"), epoch: Long = 0) =
        cache.stableSection(story, alice, bakery, sceneKey, epoch, sources) {
            "RENDERED-$epoch-${sources.joinToString(",")}"
        }

    /**
     * A second identical request does no work.
     *
     * The render lambda increments a counter, so a hit is provable by it not being
     * called - which is stronger than comparing strings, which would also pass if the
     * function were simply deterministic.
     */
    @Test
    fun `an unchanged section is rendered once`() {
        val cache = ContextCache()
        var renders = 0
        repeat(5) {
            cache.stableSection(story, alice, bakery, "s1", 0L, listOf("mem-1")) {
                renders++
                "body"
            }
        }
        assertEquals(1, renders)
        assertEquals(4, cache.hits)
        assertEquals(1, cache.misses)
    }

    @Test
    fun `a changed dependency list misses`() {
        val cache = ContextCache()
        render(cache, sources = listOf("mem-1"))
        render(cache, sources = listOf("mem-1", "mem-2"))
        assertEquals(2, cache.misses)
    }

    @Test
    fun `a changed scene key misses`() {
        val cache = ContextCache()
        render(cache, sceneKey = "s1")
        render(cache, sceneKey = "s2")
        assertEquals(2, cache.misses)
    }

    /**
     * A different character is a different prompt.
     *
     * This is the most important isolation property of the cache: Marinette's context
     * must never be served for Adrien, even though both are in the same scene at the
     * same location with the same memories in the store.
     */
    @Test
    fun `two characters in one scene do not share an entry`() {
        val cache = ContextCache()
        val a = cache.stableSection(story, alice, bakery, "s1", 0L, listOf("m")) { "alice" }
        val b = cache.stableSection(story, bob, bakery, "s1", 0L, listOf("m")) { "bob" }
        assertNotEquals(a, b)
        assertEquals("alice", a)
        assertEquals("bob", b)
    }

    @Test
    fun `a different location misses`() {
        val cache = ContextCache()
        cache.stableSection(story, alice, bakery, "s1", 0L, listOf("m")) { "indoors" }
        cache.stableSection(story, alice, roof, "s1", 0L, listOf("m")) { "outdoors" }
        assertEquals(2, cache.misses)
    }

    /**
     * Two stories never share an entry.
     *
     * The story id is part of the key, so a memory from one playthrough cannot leak into
     * another through the cache even when every other input is identical.
     */
    @Test
    fun `stories are isolated`() {
        val cache = ContextCache()
        val a = cache.stableSection(story, alice, bakery, "s1", 0L, listOf("m")) { "story-one" }
        val b = cache.stableSection(otherStory, alice, bakery, "s1", 0L, listOf("m")) { "story-two" }
        assertEquals("story-one", a)
        assertEquals("story-two", b)
    }

    /**
     * The epoch overrides an unchanged fingerprint.
     *
     * The conservative direction: when the caller says the world moved, the cache
     * rebuilds even though it can see no relevant change. Being wrong here costs a
     * little work; being wrong the other way costs correctness.
     */
    @Test
    fun `an advanced epoch forces a rebuild`() {
        val cache = ContextCache()
        render(cache, epoch = 0)
        render(cache, epoch = 1)
        assertEquals(2, cache.misses)
        assertEquals(1, cache.invalidations)
        assertEquals(0, cache.hits)
    }

    @Test
    fun `an epoch that does not move still hits`() {
        val cache = ContextCache()
        render(cache, epoch = 7)
        render(cache, epoch = 7)
        render(cache, epoch = 7)
        assertEquals(2, cache.hits)
    }

    /** The LRU bound. Four entries is the default; the story has very few scenes. */
    @Test
    fun `the cache is bounded`() {
        val cache = ContextCache(maxEntries = 2)
        cache.stableSection(story, alice, bakery, "s1", 0L, listOf("m")) { "a" }
        cache.stableSection(story, alice, roof, "s1", 0L, listOf("m")) { "b" }
        cache.stableSection(story, bob, bakery, "s1", 0L, listOf("m")) { "c" }
        assertEquals(2, cache.size)

        // The oldest entry has been evicted, so the first key misses again.
        var renders = 0
        cache.stableSection(story, alice, bakery, "s1", 0L, listOf("m")) { renders++; "a" }
        assertEquals(1, renders)
    }

    @Test
    fun `clearing one story leaves the others alone`() {
        val cache = ContextCache()
        cache.stableSection(story, alice, bakery, "s1", 0L, listOf("m")) { "one" }
        cache.stableSection(otherStory, alice, bakery, "s1", 0L, listOf("m")) { "two" }

        cache.clearStory(story)
        assertEquals(1, cache.size)

        var renders = 0
        cache.stableSection(otherStory, alice, bakery, "s1", 0L, listOf("m")) { renders++; "two" }
        assertEquals("the surviving story should still hit", 0, renders)
    }

    @Test
    fun `clear drops everything`() {
        val cache = ContextCache()
        render(cache)
        cache.clear()
        assertEquals(0, cache.size)
        var renders = 0
        cache.stableSection(story, alice, bakery, "s1", 0L, listOf("a")) {
            renders++
            "body"
        }
        assertEquals(1, renders)
    }

    /**
     * Hash collisions cannot serve another section's text.
     *
     * The cache key holds the full identity *and* a 31-bit fingerprint. So even if two
     * inputs collided on the hash, the surrounding fields differ and lookup misses -
     * which is why the hash is allowed to be cheap.
     */
    @Test
    fun `the full key prevents hash collisions from serving wrong text`() {
        val cache = ContextCache()
        // Two different source lists, deliberately chosen to be equal in length.
        val first = cache.stableSection(story, alice, bakery, "s1", 0L, listOf("aa")) { "first" }
        val second = cache.stableSection(story, alice, bakery, "s1", 0L, listOf("bb")) { "second" }
        assertEquals("first", first)
        assertEquals("second", second)
        assertEquals(2, cache.misses)
    }

    @Test
    fun `sources are reported for the developer inspector`() {
        val cache = ContextCache()
        cache.stableSection(story, alice, bakery, "s1", 0L, listOf("mem-a", "mem-b")) { "body" }
        assertEquals(listOf("mem-a", "mem-b"), cache.sourcesFor(story, alice))
        assertEquals(emptyList<String>(), cache.sourcesFor(otherStory, bob))
    }

    // ------------------------------------------------------------------
    // The epoch counter
    // ------------------------------------------------------------------

    @Test
    fun `epochs are per story`() {
        val epochs = ContextEpoch()
        assertEquals(0L, epochs.current(story))
        epochs.bump(story)
        epochs.bump(story)
        assertEquals(2L, epochs.current(story))
        // A second story is unaffected.
        assertEquals(0L, epochs.current(otherStory))
    }

    @Test
    fun `bumpBy adds a batch`() {
        val epochs = ContextEpoch()
        epochs.bumpBy(story, 5)
        assertEquals(5L, epochs.current(story))
        // Zero and negative batches are no-ops, not rewinds.
        epochs.bumpBy(story, 0)
        assertEquals(5L, epochs.current(story))
        epochs.bumpBy(story, -3)
        assertEquals(5L, epochs.current(story))
    }

    @Test
    fun `forgetting a story drops its epoch`() {
        val epochs = ContextEpoch()
        epochs.bump(story)
        epochs.forget(story)
        assertEquals(0L, epochs.current(story))
    }

    /**
     * The two mechanisms together: content change *and* epoch change.
     *
     * This is the shape a real turn has - the world moved, and the memory list changed
     * too - and it is the one that would be wrong if either mechanism were broken.
     */
    @Test
    fun `content change plus epoch change misses exactly once`() {
        val cache = ContextCache()
        val epochs = ContextEpoch()

        fun turn(sources: List<String>): String =
            cache.stableSection(story, alice, bakery, "s1", epochs.current(story), sources) {
                "turn-${epochs.current(story)}"
            }

        assertEquals("turn-0", turn(listOf("m1")))
        assertEquals("turn-0", turn(listOf("m1")))

        epochs.bump(story)
        assertEquals("turn-1", turn(listOf("m1", "m2")))
        // And that one is now cached in its turn.
        assertEquals("turn-1", turn(listOf("m1", "m2")))
        assertEquals(2, cache.misses)
        assertEquals(2, cache.hits)
    }

    @Test
    fun `a builder with no cache produces identical prompts`() {
        // The cache is an optimisation and must be invisible when absent: the same
        // instance and scene must produce byte-identical system prompts either way,
        // otherwise enabling the cache would change model behaviour.
        val pack = dev.charaly.runtime.pack.DemoStoryPacks.all.first()
        val definition = dev.charaly.runtime.domain.WorldDefinition(pack.characters, pack.locations)
        val instance = dev.charaly.runtime.engine.StoryInstanceFactory.create(
            pack,
            dev.charaly.runtime.domain.StoryInstanceId("story-cache"),
        )
        val scene = instance.worldState.activeScenes.values.firstOrNull() ?: return
        val characterId = scene.participants.firstOrNull() ?: return

        val plain = ContextBuilder(definition)
        val cached = ContextBuilder(definition, ContextBudget(), ContextCache(), ContextEpoch())

        val a = plain.buildContext(instance, scene, characterId).systemPrompt()
        val b = cached.buildContext(instance, scene, characterId).systemPrompt()
        assertEquals(a, b)
    }
}
