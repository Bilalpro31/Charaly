package dev.charaly.runtime.domain.memory

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.MemoryId
import dev.charaly.runtime.domain.StoryTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Memory is the product, not a cache of the last twenty messages.
 *
 * These tests cover the three properties that make layered memory worth having:
 * boundaries that hold, consolidation that keeps the store bounded, and contradictions
 * that resolve instead of accumulating. All deterministic, all on the JVM.
 */
class LayeredMemoryTest {

    private val marinette = CharacterId("marinette")
    private val adrien = CharacterId("adrien")
    private val player = MemorySubject.Player
    private val shop = LocationId("andre-shop")

    private fun memory(
        id: String,
        owner: CharacterId = marinette,
        content: String,
        importance: Int = 3,
        at: Int = 60,
        tier: MemoryTier = MemoryTier.EPISODIC,
        visibility: MemoryVisibility = MemoryVisibility.CHARACTER,
        visibleTo: List<CharacterId> = emptyList(),
        location: LocationId? = null,
        related: List<CharacterId> = emptyList(),
        subject: String = "",
        predicate: String = "",
        pinned: Boolean = false,
    ) = Memory(
        id = MemoryId(id),
        characterId = owner,
        content = content,
        importance = importance,
        createdAt = StoryTime.of(day = 1, hour = at / 60, minute = at % 60),
        tier = tier,
        visibility = visibility,
        visibleTo = visibleTo,
        relatedLocationId = location,
        relatedCharacterIds = related,
        subject = subject,
        predicate = predicate,
        pinned = pinned,
    )

    // ------------------------------------------------------------------
    // Visibility / knowledge boundaries
    // ------------------------------------------------------------------

    @Test
    fun `a secret told to one character is invisible to another`() {
        val secret = memory(
            id = "m-secret",
            owner = marinette,
            content = "The player told Marinette a secret about their family.",
            visibility = MemoryVisibility.SECRET,
        )
        val store = MemoryStore().add(secret)

        val forMarinette = store.retrieve(MemorySubject.Character(marinette), now = StoryTime.of(1, 2, 0))
        val forAdrien = store.retrieve(MemorySubject.Character(adrien), now = StoryTime.of(1, 2, 0))

        assertTrue("the owner must be able to recall it", forMarinette.any { it.id == secret.id })
        assertTrue(
            "Adrien must not be able to recall what Marinette was told in private",
            forAdrien.none { it.id == secret.id },
        )
    }

    @Test
    fun `a private memory is never granted even when it appears in the grant list`() {
        val private = memory(
            id = "m-private",
            owner = marinette,
            content = "Marinette's private thoughts about her father.",
            visibility = MemoryVisibility.PRIVATE,
            visibleTo = listOf(adrien),
        )
        val store = MemoryStore().add(private)

        assertFalse(private.visibleTo(MemorySubject.Character(marinette)) == false)
        assertFalse("PRIVATE is never grantable", private.visibleTo(MemorySubject.Character(adrien)))
        assertTrue(store.retrieve(MemorySubject.Character(adrien)).none { it.id == private.id })
    }

    @Test
    fun `an explicitly granted character can recall a secret`() {
        val secret = memory(
            id = "m-granted",
            owner = marinette,
            content = "The player asked Marinette to keep something between them.",
            visibility = MemoryVisibility.SECRET,
            visibleTo = listOf(adrien, marinette),
        )
        val store = MemoryStore().add(secret)
        assertTrue(store.retrieve(MemorySubject.Character(adrien)).any { it.id == secret.id })
    }

    @Test
    fun `the player can see what was told to them`() {
        val told = memory(
            id = "m-told",
            owner = marinette,
            content = "The player confessed something to Marinette.",
            visibility = MemoryVisibility.PLAYER,
        )
        val store = MemoryStore().add(told)
        assertTrue(store.retrieve(player).any { it.id == told.id })
        assertTrue(
            "a bystander still must not see it",
            store.retrieve(MemorySubject.Character(adrien)).none { it.id == told.id },
        )
    }

    @Test
    fun `a world-visible memory is available to everyone`() {
        val public = memory(
            id = "m-public",
            owner = marinette,
            content = "The bakery opens early on market day.",
            visibility = MemoryVisibility.WORLD,
        )
        val store = MemoryStore().add(public)
        assertNotNull(store.retrieve(MemorySubject.Character(adrien)).firstOrNull { it.id == public.id })
        assertNotNull(store.retrieve(player).firstOrNull { it.id == public.id })
    }

    @Test
    fun `a superseded memory is history and is never retrieved`() {
        val old = memory("m-old", content = "The player lives in Paris.", subject = "player home", predicate = "lives in")
        val new = old.copy(
            id = MemoryId("m-new"),
            content = "The player moved to Lyon.",
            createdAt = StoryTime.of(day = 2, hour = 9, minute = 0),
        )
        val store = MemoryStore().add(old).add(new).let { s ->
            MemoryConsolidator.resolveContradictions(s, StoryTime.of(day = 2, hour = 10, minute = 0))
        }
        assertTrue(store.byId(MemoryId("m-old"))!!.supersededBy == MemoryId("m-new"))
        assertNull(
            "a superseded claim must not be offered to the model as current",
            store.retrieve(MemorySubject.Character(marinette), now = StoryTime.of(day = 2, hour = 11, minute = 0))
                .firstOrNull { it.id == MemoryId("m-old") },
        )
    }

    // ------------------------------------------------------------------
    // Retrieval and scoring
    // ------------------------------------------------------------------

    @Test
    fun `retrieval is relevance ranked, not insertion ranked`() {
        val store = MemoryStore()
            .add(memory("m-old-trivia", content = "The weather is mild.", importance = 1, at = 30))
            .add(
                memory(
                    "m-relevant",
                    content = "Marinette lost her notebook at the school.",
                    importance = 5,
                    at = 600,
                    location = LocationId("school"),
                    related = listOf(adrien),
                ),
            )
        val results = store.retrieve(
            viewer = MemorySubject.Character(marinette),
            atLocation = LocationId("school"),
            involving = setOf(adrien),
            now = StoryTime.of(day = 2, hour = 10, minute = 30),
        )
        assertEquals(MemoryId("m-relevant"), results.first().id)
    }

    @Test
    fun `a memory matching what the player just said is boosted`() {
        val store = MemoryStore()
            .add(memory("m-unrelated", content = "Marinette likes sweet pastries.", importance = 4))
            .add(memory("m-notebook", content = "Marinette lost a notebook near the school gate.", importance = 3))
        val results = store.retrieve(
            viewer = MemorySubject.Character(marinette),
            query = "have you seen that notebook anywhere near the school",
            now = StoryTime.of(day = 1, hour = 10, minute = 0),
        )
        assertEquals(MemoryId("m-notebook"), results.first().id)
    }

    @Test
    fun `retrieval is deterministic for identical input`() {
        val store = MemoryStore().apply {
            listOf(1, 2, 3, 4, 5).forEach { i ->
                add(memory("m$i", content = "A memory about the school day number $i.", importance = 3, at = i * 10))
            }
        }
        val a = store.retrieve(MemorySubject.Character(marinette), now = StoryTime.of(1, 12, 0)).map { it.id }
        val b = store.retrieve(MemorySubject.Character(marinette), now = StoryTime.of(1, 12, 0)).map { it.id }
        assertEquals(a, b)
    }

    @Test
    fun `a limit of zero returns nothing`() {
        val store = MemoryStore().add(memory("m1", content = "Anything at all."))
        assertTrue(store.retrieve(MemorySubject.Character(marinette), limit = 0).isEmpty())
    }

    @Test
    fun `tokenisation drops stop words and short tokens deterministically`() {
        val tokens = MemoryScore.tokenize("She is at the school with Alya, not Marinette!")
        assertTrue("school" in tokens)
        assertTrue("alya" in tokens)
        assertFalse("the" in tokens)
        assertFalse("is" in tokens)
        assertFalse("at" in tokens)
    }

    // ------------------------------------------------------------------
    // Consolidation
    // ------------------------------------------------------------------

    @Test
    fun `consolidation merges a memory that was said the same way twice`() {
        val store = MemoryStore()
            .add(memory("m1", content = "The player helped Marinette find her lost notebook.", at = 100))
            .add(memory("m2", content = "The player helped Marinette find her lost notebook.", at = 200))

        val consolidated = MemoryConsolidator.mergeDuplicates(store, StoryTime.of(1, 5, 0))

        assertNotNull(consolidated.byId(MemoryId("m1")))
        assertEquals(MemoryId("m1"), consolidated.byId(MemoryId("m2"))!!.supersededBy)
        assertEquals(1, consolidated.current().size)
    }

    @Test
    fun `consolidation keeps genuinely different memories apart`() {
        val store = MemoryStore()
            .add(memory("m1", content = "The player helped Marinette find her lost notebook.", at = 100))
            .add(memory("m2", content = "Adrien asked Marinette to study together after class.", at = 200))
        val consolidated = MemoryConsolidator.mergeDuplicates(store, StoryTime.of(1, 5, 0))
        assertEquals(2, consolidated.current().size)
    }

    @Test
    fun `a contradiction is resolved in favour of the newer claim`() {
        val paris = memory(
            "m-paris",
            content = "The player lives in Paris.",
            at = 100,
            subject = "player",
            predicate = "lives in",
        )
        val lyon = memory(
            "m-lyon",
            content = "The player moved to Lyon.",
            at = 60 * 20,
            subject = "player",
            predicate = "lives in",
        )
        val now = StoryTime.of(day = 3, hour = 9, minute = 0)
        val consolidated = MemoryConsolidator.resolveContradictions(
            MemoryStore().add(paris).add(lyon),
            now,
        )

        val old = consolidated.byId(paris.id)!!
        assertEquals(lyon.id, old.supersededBy)
        assertNotNull("a superseded claim keeps an end date", old.validUntil)
        assertTrue("the old claim is retained as history", consolidated.byId(paris.id) != null)
        // Only the newer claim is current.
        assertEquals(
            listOf(lyon.id),
            consolidated.current().map { it.id },
        )
    }

    @Test
    fun `claims about different subjects never contradict each other`() {
        val store = MemoryStore()
            .add(memory("m-a", content = "Marinette lives in Paris.", subject = "marinette", predicate = "lives in", at = 10))
            .add(memory("m-b", content = "Adrien lives in Paris.", subject = "adrien", predicate = "lives in", at = 20))
        val consolidated = MemoryConsolidator.resolveContradictions(store, StoryTime.of(1, 12, 0))
        assertEquals(2, consolidated.current().size)
    }

    @Test
    fun `an unlabelled memory cannot contradict anything`() {
        val store = MemoryStore()
            .add(memory("m-a", content = "Something vague happened.", at = 10))
            .add(memory("m-b", content = "Something else vague happened.", at = 20))
        val consolidated = MemoryConsolidator.resolveContradictions(store, StoryTime.of(1, 12, 0))
        assertEquals(2, consolidated.current().size)
    }

    @Test
    fun `pruning keeps pinned and canon memories and drops the least important`() {
        val store = MemoryStore()
            .add(memory("m-pinned", content = "Pinned memory about the rooftop.", importance = 1, pinned = true))
            .add(memory("m-canon", content = "Andre runs the shop.", importance = 1, tier = MemoryTier.CANON))
            .add(memory("m-trivia", content = "Trivial one.", importance = 1))
            .add(memory("m-trivia2", content = "Another trivial one.", importance = 1))

        val pruned = MemoryConsolidator.prune(store, maxPerCharacter = 2)

        assertNotNull("a pinned memory is never pruned", pruned.byId(MemoryId("m-pinned")))
        assertNotNull("canon is never pruned", pruned.byId(MemoryId("m-canon")))
        assertEquals(2, pruned.size)
    }

    @Test
    fun `forgetting a memory takes it out of retrieval`() {
        val store = MemoryStore()
            .add(memory("m-secret", content = "A secret worth forgetting.", importance = 5))
        assertEquals(1, store.retrieve(MemorySubject.Character(marinette)).size)

        val forgotten = store.forget(MemoryId("m-secret"), StoryTime.of(day = 1, hour = 20, minute = 0))

        assertTrue("a forgotten memory must not be offered to the model", forgotten.retrieve(MemorySubject.Character(marinette)).isEmpty())
        assertNotNull("the record is retained so the forget is auditable", forgotten.byId(MemoryId("m-secret")))
        assertNotNull("and it carries an end date", forgotten.byId(MemoryId("m-secret"))!!.validUntil)
    }

    @Test
    fun `counts by tier drive the memory panel without scanning the UI`() {
        val store = MemoryStore()
            .add(memory("m1", content = "A scene summary.", tier = MemoryTier.SCENE))
            .add(memory("m2", content = "Something that happened.", tier = MemoryTier.EPISODIC))
            .add(memory("m3", content = "A stable fact.", tier = MemoryTier.CANON))
        val counts = store.countsByTier()
        assertEquals(1, counts[MemoryTier.SCENE])
        assertEquals(1, counts[MemoryTier.EPISODIC])
        assertEquals(1, counts[MemoryTier.CANON])
        assertEquals(0, counts[MemoryTier.WORKING])
    }

    @Test
    fun `an empty store answers every query safely`() {
        val empty = MemoryStore.EMPTY
        assertTrue(empty.retrieve(MemorySubject.Character(marinette)).isEmpty())
        assertTrue(empty.current().isEmpty())
        assertEquals(0, empty.size)
        assertTrue(MemoryConsolidator.consolidate(empty, StoryTime.START).current().isEmpty())
    }

    @Test
    fun `an unrelated owner is not returned to a different character`() {
        val store = MemoryStore().add(
            memory("m-andre", owner = CharacterId("andre"), content = "The player bought ice cream three times.", location = shop),
        )
        assertTrue(
            store.retrieve(MemorySubject.Character(marinette), atLocation = shop).none { it.id == MemoryId("m-andre") },
        )
        assertEquals(1, store.retrieve(MemorySubject.Character(CharacterId("andre"))).size)
    }
}