package dev.charaly.runtime.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StoryThreads exist to give the SceneDirector a structured handle on what is
 * narratively in play. They are not a quest engine.
 */
class StoryThreadTest {

    private val alice = CharacterId("alice")
    private val library = LocationId("library")

    private val thread = StoryThread(
        id = ThreadId("t1"),
        title = "The hidden ledger",
        status = StoryThreadStatus.ACTIVE,
        stage = 1,
        involvedCharacterIds = listOf(alice),
        relevantLocationIds = listOf(library),
    )

    @Test
    fun `a thread needs a title`() {
        assertTrue(runCatching { thread.copy(title = " ") }.isFailure)
    }

    @Test
    fun `stage must not be negative`() {
        assertTrue(runCatching { thread.copy(stage = -1) }.isFailure)
    }

    @Test
    fun `involvement checks are explicit`() {
        assertTrue(thread.involves(alice))
        assertFalse(thread.involves(CharacterId("bob")))
        assertTrue(thread.touches(library))
        assertFalse(thread.touches(LocationId("square")))
    }

    @Test
    fun `open means active or dormant`() {
        assertTrue(thread.isOpen())
        assertTrue(thread.copy(status = StoryThreadStatus.DORMANT).isOpen())
        assertFalse(thread.copy(status = StoryThreadStatus.COMPLETED).isOpen())
        assertFalse(thread.copy(status = StoryThreadStatus.FAILED).isOpen())
    }

    @Test
    fun `statuses are a closed set`() {
        assertEquals(
            setOf(StoryThreadStatus.DORMANT, StoryThreadStatus.ACTIVE, StoryThreadStatus.COMPLETED, StoryThreadStatus.FAILED),
            StoryThreadStatus.entries.toSet(),
        )
    }

    @Test
    fun `structured state is preserved`() {
        val withState = thread.copy(state = mapOf("clue" to "cabinet key"))
        assertEquals("cabinet key", withState.state["clue"])
    }

    @Test
    fun `updating a thread keeps its identity`() {
        val updated = thread.copy(stage = 2, updatedAt = StoryTime(2, 0, 0))
        assertEquals(thread.id, updated.id)
        assertEquals(thread.title, updated.title)
        assertEquals(2, updated.stage)
    }

    @Test
    fun `default thread is dormant with no stage`() {
        val fresh = StoryThread(ThreadId("t2"), "Fresh")
        assertEquals(StoryThreadStatus.DORMANT, fresh.status)
        assertEquals(0, fresh.stage)
        assertTrue(fresh.involvedCharacterIds.isEmpty())
    }
}
