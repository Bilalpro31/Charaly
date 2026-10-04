package dev.charaly.runtime.domain

import dev.charaly.runtime.engine.SampleWorlds
import dev.charaly.runtime.engine.StoryInstanceFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A StoryInstance is a RUNNING copy of a StoryPack. Two instances of the same
 * pack must be completely independent: that is what makes "start a new story"
 * meaningful.
 */
class StoryInstanceTest {

    private val pack = SampleWorlds.libraryPack()

    @Test
    fun `factory builds an instance from a pack`() {
        val instance = StoryInstanceFactory.create(pack, StoryInstanceId("s1"))
        assertEquals("s1", instance.id.value)
        assertEquals(pack.id, instance.storyPackId)
        assertEquals(2, instance.characters.size)
        assertEquals(2, instance.locations.size)
    }

    @Test
    fun `instance starts with the pack clock`() {
        val instance = StoryInstanceFactory.create(pack, StoryInstanceId("s1"))
        assertEquals(StoryTime(day = 1, hour = 21, minute = 0), instance.worldClock.now)
    }

    @Test
    fun `characters start at their declared locations`() {
        val instance = StoryInstanceFactory.create(pack, StoryInstanceId("s1"))
        assertEquals(
            SampleWorlds.library.id,
            instance.characters.getValue(SampleWorlds.alice.id).locationId,
        )
        assertEquals(
            SampleWorlds.square.id,
            instance.characters.getValue(SampleWorlds.bob.id).locationId,
        )
    }

    @Test
    fun `two instances of the same pack do not share state`() {
        val a = StoryInstanceFactory.create(pack, StoryInstanceId("a"))
        val b = StoryInstanceFactory.create(pack, StoryInstanceId("b"))
        assertEquals(a.worldClock.now, b.worldClock.now)
        assertTrue("instances must not be the same object", a !== b)
        assertTrue("and their world state must not be shared either", a.worldState !== b.worldState)

        // Moving Alice in `a` must be invisible to `b`.
        val moved = a.evolved(
            worldState = a.worldState.withCharacter(
                a.characters.getValue(SampleWorlds.alice.id).copy(locationId = SampleWorlds.square.id),
            ),
        )
        assertEquals(SampleWorlds.square.id, moved.characters.getValue(SampleWorlds.alice.id).locationId)
        assertEquals(
            "second instance must be untouched",
            SampleWorlds.library.id,
            b.characters.getValue(SampleWorlds.alice.id).locationId,
        )
    }

    @Test
    fun `instance exposes owned sub-stores`() {
        val instance = StoryInstanceFactory.create(pack, StoryInstanceId("s1"))
        assertNotNull(instance.knowledge)
        assertNotNull(instance.memories)
        assertNotNull(instance.eventQueue)
        assertNotNull(instance.conversation)
        assertEquals(instance.worldState.storyThreads, instance.storyThreads)
        assertEquals(instance.worldState.relationships, instance.relationships)
    }

    @Test
    fun `evolved does not mutate the original instance`() {
        val instance = StoryInstanceFactory.create(pack, StoryInstanceId("s1"))
        val before = instance.worldClock.now
        instance.evolved(
            worldState = instance.worldState.copy(
                worldClock = instance.worldState.worldClock.advance(dev.charaly.runtime.domain.StoryDuration(120)),
            ),
        )
        assertEquals("immutability violated", before, instance.worldClock.now)
    }

    @Test
    fun `instance focus character defaults to the first character`() {
        val instance = StoryInstanceFactory.create(pack, StoryInstanceId("s1"))
        assertEquals(SampleWorlds.alice.id, instance.focusCharacterId)
    }

    @Test
    fun `initial knowledge is applied through events, not by fiat`() {
        val instance = StoryInstanceFactory.create(pack, StoryInstanceId("s1"))
        assertTrue(instance.knowledge.knows(SampleWorlds.bob.id, FactId("fact-ledger")))
        assertFalse(instance.knowledge.knows(SampleWorlds.alice.id, FactId("fact-ledger")))
        // The runtime record must agree with the knowledge store.
        assertTrue(instance.characters.getValue(SampleWorlds.bob.id).knows(FactId("fact-ledger")))
        assertFalse(instance.characters.getValue(SampleWorlds.alice.id).knows(FactId("fact-ledger")))
        // And it must be visible in the event log.
        assertTrue(instance.worldState.eventLog.isNotEmpty())
    }

    @Test
    fun `pack start activity is applied`() {
        val instance = StoryInstanceFactory.create(pack, StoryInstanceId("s1"))
        assertEquals(
            CharacterActivity.WORKING,
            instance.characters.getValue(SampleWorlds.bob.id).activity,
        )
    }

    @Test
    fun `summarize is readable`() {
        val summary = StoryInstanceFactory.create(pack, StoryInstanceId("s1")).summarize()
        assertTrue(summary.contains("StoryInstance s1"))
        assertTrue(summary.contains("Alice"))
    }
}
