package dev.charaly.runtime.domain

import dev.charaly.runtime.engine.SampleWorlds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A StoryPack is STATIC story definition. It must not behave like a running
 * world: no clock ticking, no character location changing, no accumulating
 * memories.
 */
class StoryPackTest {

    private val pack = SampleWorlds.libraryPack()

    @Test
    fun `pack declares characters and places`() {
        assertEquals(2, pack.characters.size)
        assertEquals(2, pack.locations.size)
        assertEquals("Alice", pack.character(pack.characters[0].id)?.name)
    }

    @Test
    fun `pack exposes character start locations`() {
        val alice = pack.characterByName("Alice")!!
        val bob = pack.characterByName("Bob")!!
        assertEquals(SampleWorlds.library.id, pack.startLocationOf(alice.id))
        assertEquals(SampleWorlds.square.id, pack.startLocationOf(bob.id))
    }

    @Test
    fun `pack is immutable across repeated reads`() {
        val first = pack.startLocationOf(pack.characterByName("Alice")!!.id)
        repeat(5) { pack.startLocationOf(pack.characterByName("Alice")!!.id) }
        assertEquals(first, pack.startLocationOf(pack.characterByName("Alice")!!.id))
    }

    @Test
    fun `pack lookup helpers return null for unknown ids`() {
        assertNull(pack.character(CharacterId("nobody")))
        assertNull(pack.location(LocationId("nowhere")))
        assertNotNull("name lookup must be case-insensitive", pack.characterByName("alice"))
    }

    @Test
    fun `pack requires a title`() {
        val failure = runCatching {
            StoryPack(id = StoryPackId("x"), title = "  ")
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `pack rejects duplicate character ids`() {
        val failure = runCatching {
            StoryPack(
                id = StoryPackId("dup"),
                title = "Dupes",
                characters = listOf(SampleWorlds.alice, SampleWorlds.alice),
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `pack knowledge declares truth separately from who knows it`() {
        val fact = pack.initialKnowledge.facts.first { it.id.value == "fact-ledger" }
        assertTrue("ledger fact should be a secret", fact.secret)
        // Truth exists for the world...
        assertEquals(2, pack.initialKnowledge.facts.size)
        // ...but only Bob starts out knowing where it is.
        assertEquals(listOf("fact-ledger"), pack.initialKnowledge.characterKnowledge[SampleWorlds.bob.id])
        assertEquals(listOf("fact-lamps"), pack.initialKnowledge.characterKnowledge[SampleWorlds.alice.id])
        assertTrue(
            "Bob was not granted the lamp fact",
            !pack.initialKnowledge.characterKnowledge.getValue(SampleWorlds.bob.id).contains("fact-lamps"),
        )
    }

    @Test
    fun `pack threads start with declared statuses`() {
        val active = pack.initialStoryThreads.filter { it.status == StoryThreadStatus.ACTIVE }
        val dormant = pack.initialStoryThreads.filter { it.status == StoryThreadStatus.DORMANT }
        assertEquals(1, active.size)
        assertEquals(1, dormant.size)
    }
}
