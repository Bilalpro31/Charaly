package dev.charaly.runtime.domain

import dev.charaly.runtime.engine.SampleWorlds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CharacterDefinition (static identity) and CharacterRuntime (dynamic state)
 * must stay separate. These tests guard the separation.
 */
class CharacterRuntimeTest {

    private val definition = SampleWorlds.alice
    private val runtime = CharacterRuntime(
        characterId = SampleWorlds.alice.id,
        name = SampleWorlds.alice.name,
        locationId = SampleWorlds.library.id,
    )

    @Test
    fun `definition holds identity, not state`() {
        assertEquals("Alice", definition.name)
        assertTrue("Alice has goals", definition.goals.isNotEmpty())
        // A definition has no location field at all: state lives elsewhere.
        assertTrue(definition.personaBlock().contains("Alice"))
        assertTrue(definition.personaBlock().contains("dry, observant"))
        assertTrue(definition.personaBlock().contains("bookseller"))
    }

    @Test
    fun `runtime holds dynamic state`() {
        assertEquals(SampleWorlds.library.id, runtime.locationId)
        assertEquals(CharacterActivity.IDLE, runtime.activity)
    }

    @Test
    fun `scenes are tracked on the runtime`() {
        val entered = runtime.enterScene(SceneId("s1"), StoryTime(1, 10, 0))
        assertTrue(entered.isPresentIn(SceneId("s1")))
        assertTrue(entered.lastUpdatedAt == StoryTime(1, 10, 0))
        val left = entered.leaveScene(SceneId("s1"), StoryTime(1, 11, 0))
        assertFalse(left.isPresentIn(SceneId("s1")))
    }

    @Test
    fun `entering the same scene twice is idempotent`() {
        val twice = runtime.enterScene(SceneId("s1"), StoryTime.START).enterScene(SceneId("s1"), StoryTime.START)
        assertEquals(listOf(SceneId("s1")), twice.sceneIds)
    }

    @Test
    fun `knowledge references are tracked separately from the store`() {
        val knows = runtime.copy(knownFactIds = listOf(FactId("f1")))
        assertTrue(knows.knows(FactId("f1")))
        assertFalse(knows.knows(FactId("f2")))
    }

    @Test
    fun `runtime and definition stay consistent by id`() {
        assertEquals(definition.id, runtime.characterId)
        assertEquals(definition.name, runtime.name)
    }

    @Test
    fun `a blank name is rejected in both halves`() {
        assertTrue(runCatching { CharacterDefinition(id = CharacterId("x"), name = " ") }.isFailure)
        assertTrue(
            "a runtime record must also refuse a blank name",
            runCatching { CharacterRuntime(CharacterId("x"), name = " ") }.isFailure,
        )
    }

    @Test
    fun `activity is a closed set`() {
        assertEquals(
            "activity must stay a closed enum, not free text",
            CharacterActivity.entries.size,
            CharacterActivity.entries.toSet().size,
        )
    }
}
