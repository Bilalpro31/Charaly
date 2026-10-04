package dev.charaly.runtime.domain

import dev.charaly.runtime.engine.SampleWorlds
import dev.charaly.runtime.engine.StoryInstanceFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WorldState is the authoritative dynamic state. These tests pin the shape rules
 * that stop it degrading into an untyped bag.
 */
class WorldStateTest {

    private val pack = SampleWorlds.libraryPack()
    private val instance = StoryInstanceFactory.create(pack, StoryInstanceId("s1"))

    @Test
    fun `world state exposes clock, places, characters and relationships`() {
        val world = instance.worldState
        assertNotNull(world.worldClock)
        assertEquals(2, world.locations.size)
        assertEquals(2, world.characters.size)
        assertEquals(1, world.relationships.size)
        assertEquals(2, world.storyThreads.size)
    }

    @Test
    fun `characters at a location are resolved deterministically`() {
        val atLibrary = instance.worldState.charactersAt(SampleWorlds.library.id)
        assertEquals(listOf(SampleWorlds.alice.id), atLibrary.map { it.characterId })
    }

    @Test
    fun `relationship lookup is symmetric`() {
        val forward = instance.worldState.relationship(SampleWorlds.alice.id, SampleWorlds.bob.id)
        val backward = instance.worldState.relationship(SampleWorlds.bob.id, SampleWorlds.alice.id)
        assertNotNull(forward)
        assertNotNull(backward)
        assertEquals("lookup must be direction-agnostic", forward!!.key(), backward!!.key())
    }

    @Test
    fun `relationshipOf round-trips through the map`() {
        val rel = instance.worldState.relationship(SampleWorlds.alice.id, SampleWorlds.bob.id)!!
        assertEquals(rel, instance.worldState.relationshipOf(rel))
    }

    @Test
    fun `world variables are typed, not any`() {
        val variable = instance.worldState.variables.getValue("library_lamp")
        assertEquals(WorldVariableType.BOOLEAN, variable.type)
        assertEquals(true, variable.asBoolean())
        assertNull("a boolean has no long value", variable.asLong())
    }

    @Test
    fun `withCharacter bumps the revision`() {
        val before = instance.worldState.revision
        val runtime = instance.characters.getValue(SampleWorlds.alice.id)
        val after = instance.worldState.withCharacter(runtime.copy(mood = "wary")).revision
        assertTrue(after > before)
    }

    @Test
    fun `recordEvent appends to the log and does not duplicate active entries`() {
        val base = instance.worldState
        val world = base
            .recordEvent(EntityId("e1"), stillActive = true)
            .recordEvent(EntityId("e1"), stillActive = true)
            .recordEvent(EntityId("e2"), stillActive = true)
        assertEquals("the audit trail keeps every application", base.eventLog.size + 3, world.eventLog.size)
        assertEquals("an event can only be in force once", 2, world.activeEvents.size)
        assertEquals(1, world.activeEvents.count { it.value == "e1" })
        assertEquals(1, world.activeEvents.count { it.value == "e2" })
    }

    @Test
    fun `a finished event is not listed as active`() {
        val world = instance.worldState
            .recordEvent(EntityId("done"), stillActive = false)
            .recordEvent(EntityId("live"), stillActive = true)
        assertEquals(listOf("live"), world.activeEvents.map { it.value })
        assertTrue(world.eventLog.any { it.value == "done" })
    }

    @Test
    fun `active thread filtering respects location and participants`() {
        val world = instance.worldState
        val threads = world.activeThreadsFor(SampleWorlds.library.id, setOf(SampleWorlds.alice.id))
        assertEquals(listOf("thread-ledger"), threads.map { it.id.value })
    }

    @Test
    fun `open threads exclude finished ones`() {
        val world = instance.worldState.copy(
            storyThreads = instance.worldState.storyThreads.mapValues { (id, thread) ->
                if (id.value == "thread-ledger") thread.copy(status = StoryThreadStatus.COMPLETED) else thread
            },
        )
        assertEquals(listOf("thread-lamps"), world.openThreads().map { it.id.value })
    }

    @Test
    fun `describe includes every inspector field`() {
        val text = instance.worldState.describe()
        listOf("World @", "Locations:", "Characters:", "Relationships:", "Threads:", "Active scenes:").forEach {
            assertTrue("describe() must mention '$it'", text.contains(it))
        }
    }

    @Test
    fun `empty world state is usable`() {
        val empty = WorldState.EMPTY
        assertEquals(0, empty.characters.size)
        assertEquals(StoryTime.START, empty.worldClock.now)
        assertNull(empty.character(SampleWorlds.alice.id))
    }
}
