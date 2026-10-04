package dev.charaly.runtime.domain.memory

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.EntityId
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.MemoryId
import dev.charaly.runtime.domain.StoryTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Memory at MVP level: structured, persistent, deterministic retrieval. No
 * embeddings, no vector store.
 */
class MemoryTest {

    private val alice = CharacterId("alice")
    private val bob = CharacterId("bob")
    private val library = LocationId("library")

    @Test
    fun `memories require content and valid importance`() {
        assertTrue(runCatching { Memory(EntityId("m"), alice, "  ") }.isFailure)
        assertTrue(runCatching { memory("m", importance = 0) }.isFailure)
        assertTrue(runCatching { memory("m", importance = 6) }.isFailure)
    }

    @Test
    fun `storing and reading by character`() {
        val store = MemoryStore.EMPTY.addAll(listOf(memory("m1"), memory("m2", characterOverride = bob)))
        assertEquals(2, store.size)
        assertEquals(listOf("m1"), store.of(alice).map { it.id.value })
        assertEquals(listOf("m2"), store.of(bob).map { it.id.value })
    }

    private fun memory(
        id: String,
        content: String = "something happened",
        importance: Int = 3,
        at: StoryTime = StoryTime(1, 9, 0),
        related: List<CharacterId> = emptyList(),
        location: LocationId? = null,
        characterOverride: CharacterId = alice,
    ) = Memory(
        id = MemoryId(id),
        characterId = characterOverride,
        content = content,
        importance = importance,
        createdAt = at,
        relatedCharacterIds = related,
        relatedLocationId = location,
    )

    @Test
    fun `selection prefers importance then recency`() {
        val store = MemoryStore.EMPTY.addAll(
            listOf(
                memory("low", importance = 1, at = StoryTime(1, 12, 0)),
                memory("high-old", importance = 5, at = StoryTime(1, 1, 0)),
                memory("mid", importance = 3, at = StoryTime(1, 10, 0)),
                memory("high-new", importance = 5, at = StoryTime(1, 11, 0)),
            ),
        )
        assertEquals(listOf("high-new", "high-old", "mid"), store.select(alice, limit = 3).map { it.id.value })
    }

    @Test
    fun `selection respects the limit`() {
        val store = MemoryStore.EMPTY.addAll((1..10).map { memory("m$it") })
        assertEquals(3, store.select(alice, limit = 3).size)
        assertTrue(store.select(alice, limit = 0).isEmpty())
    }

    @Test
    fun `selection filters by minimum importance`() {
        val store = MemoryStore.EMPTY.addAll(
            listOf(memory("trivial", importance = 1), memory("important", importance = 5)),
        )
        assertEquals(listOf("important"), store.select(alice, minImportance = 3).map { it.id.value })
    }

    @Test
    fun `selection filters by location but keeps universal memories`() {
        val store = MemoryStore.EMPTY.addAll(
            listOf(
                memory("here", location = library),
                memory("there", location = LocationId("square")),
                memory("anywhere"),
            ),
        )
        assertEquals(setOf("here", "anywhere"), store.select(alice, atLocation = library).map { it.id.value }.toSet())
    }

    @Test
    fun `selection filters by involved characters`() {
        val store = MemoryStore.EMPTY.addAll(
            listOf(memory("about-bob", related = listOf(bob)), memory("unrelated")),
        )
        assertEquals(listOf("about-bob"), store.select(alice, involving = setOf(bob)).map { it.id.value })
    }

    @Test
    fun `adding the same id overwrites`() {
        val store = MemoryStore.EMPTY
            .add(memory("m1", content = "first"))
            .add(memory("m1", content = "second"))
        assertEquals(1, store.size)
        assertEquals("second", store.byId(MemoryId("m1"))!!.content)
    }

    @Test
    fun `removal works`() {
        val store = MemoryStore.EMPTY.add(memory("m1")).remove(MemoryId("m1"))
        assertEquals(0, store.size)
        assertNull(store.byId(MemoryId("m1")))
    }

    @Test
    fun `ordering is stable across calls`() {
        val store = MemoryStore.EMPTY.addAll(listOf(memory("b"), memory("a"), memory("c", at = StoryTime(1, 10, 0))))
        val first = store.of(alice).map { it.id.value }
        val second = store.of(alice).map { it.id.value }
        assertEquals(first, second)
    }

    @Test
    fun `ids of a character are indexable`() {
        val store = MemoryStore.EMPTY.addAll(listOf(memory("m1"), memory("m2")))
        assertEquals(setOf("m1", "m2"), store.idsOf(alice).map { it.value }.toSet())
    }

    @Test
    fun `source is a closed provenance set`() {
        assertTrue(MemorySource.entries.isNotEmpty())
        assertTrue(MemorySource.EVENT in MemorySource.entries)
        assertFalse(MemorySource.entries.any { it.name.isBlank() })
    }

    @Test
    fun `empty store is safe to query`() {
        assertTrue(MemoryStore.EMPTY.of(alice).isEmpty())
        assertTrue(MemoryStore.EMPTY.select(alice).isEmpty())
        assertEquals(0, MemoryStore.EMPTY.size)
    }
}
