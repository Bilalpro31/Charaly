package dev.charaly.runtime.director

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.Scene
import dev.charaly.runtime.domain.SceneId
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.StoryThreadStatus
import dev.charaly.runtime.domain.ThreadId
import dev.charaly.runtime.domain.WorldDefinition
import dev.charaly.runtime.domain.WorldVariable
import dev.charaly.runtime.engine.EventApplication
import dev.charaly.runtime.engine.EventEngine
import dev.charaly.runtime.engine.SampleWorlds
import dev.charaly.runtime.engine.StoryInstanceFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The SceneDirector answers one question: what scene is active right now?
 * It is deterministic, pure, and never calls the model.
 */
class SceneDirectorTest {

    private lateinit var definition: WorldDefinition
    private lateinit var director: SceneDirector
    private lateinit var engine: EventEngine
    private lateinit var instance: StoryInstance
    private lateinit var pack: StoryPack

    private val alice = SampleWorlds.alice.id
    private val bob = SampleWorlds.bob.id
    private val library = SampleWorlds.library.id
    private val square = SampleWorlds.square.id

    @Before
    fun setUp() {
        pack = SampleWorlds.libraryPack()
        definition = WorldDefinition(pack.characters, pack.locations)
        director = SceneDirector(definition)
        engine = EventEngine(definition)
        instance = StoryInstanceFactory.create(pack, StoryInstanceId("s1"))
    }

    @Test
    fun `a scene is derived where the focus character actually is`() {
        val scene = director.directScene(instance, alice)!!
        assertEquals("Alice is in the library", library, scene.locationId)
        assertEquals(alice, scene.focusCharacterId)
    }

    @Test
    fun `participants are whoever is physically present`() {
        val scene = director.directScene(instance, alice)!!
        assertEquals("Bob is in the square", setOf(alice), scene.participantSet())
    }

    @Test
    fun `a co-located character joins the scene`() {
        val moved = apply(engine.applyImmediately(instance, dev.charaly.runtime.domain.events.CharacterMoved(bob, from = square, to = library)))
        val scene = director.directScene(moved, alice)!!
        assertEquals(setOf(alice, bob), scene.participantSet())
    }

    @Test
    fun `directing twice gives the same answer`() {
        val first = director.directScene(instance, alice)
        val second = director.directScene(instance, alice)
        assertEquals(first?.locationId, second?.locationId)
        assertEquals(first?.participants, second?.participants)
    }

    @Test
    fun `the scene follows the character when they move`() {
        val nowhere = apply(
            engine.applyImmediately(instance, dev.charaly.runtime.domain.events.CharacterMoved(alice, from = library, to = square)),
        )
        val scene = director.directScene(nowhere, alice)
        assertNotNull(scene)
        assertEquals(square, scene!!.locationId)
    }

    @Test
    fun `an unknown focus character yields nothing`() {
        assertNull(director.directScene(instance, CharacterId("nobody")))
    }

    @Test
    fun `active threads at the location are attached`() {
        val scene = director.directScene(instance, alice)!!
        assertTrue(scene.activeThreadIds.contains(ThreadId("thread-ledger")))
    }

    @Test
    fun `a dormant thread elsewhere is not attached`() {
        val scene = director.directScene(instance, bob)!!
        assertFalse("the dormant lamp thread is not in play", scene.activeThreadIds.contains(ThreadId("thread-lamps")))
    }

    @Test
    fun `an existing committed scene is reused rather than recreated`() {
        val committed = apply(
            engine.applyImmediately(instance, dev.charaly.runtime.domain.events.SceneStarted(SceneId("scene-1"), library, listOf(alice))),
        )
        val scene = director.directScene(committed, alice)!!
        assertEquals(SceneId("scene-1"), scene.id)
    }

    @Test
    fun `refinement picks up a character who arrived`() {
        val committed = apply(
            engine.applyImmediately(instance, dev.charaly.runtime.domain.events.SceneStarted(SceneId("scene-1"), library, listOf(alice))),
        )
        val bobArrived = apply(
            engine.applyImmediately(committed, dev.charaly.runtime.domain.events.CharacterMoved(bob, from = square, to = library)),
        )
        val scene = director.directScene(bobArrived, alice)!!
        assertTrue(scene.participants.contains(bob))
    }

    @Test
    fun `a derived scene is not world state until an event commits it`() {
        val derived = director.directScene(instance, alice)!!
        assertFalse(instance.worldState.activeScenes.containsKey(derived.id))
        assertNull(instance.currentSceneId)

        val committed = apply(
            engine.applyImmediately(instance, dev.charaly.runtime.domain.events.SceneStarted(derived.id, library, derived.participants)),
        )
        assertTrue(committed.worldState.activeScenes.containsKey(derived.id))
        assertEquals(derived.id, committed.currentSceneId)
    }

    @Test
    fun `an explicit objective can be supplied`() {
        val scene = director.deriveNewScene(instance, alice, library, "Alice is closing up")
        assertEquals("Alice is closing up", scene.objective)
    }

    @Test
    fun `the scene starts at the current world time`() {
        val scene = director.directScene(instance, alice)!!
        assertEquals(instance.worldClock.now, scene.startedAt)
    }

    @Test
    fun `turn counting is monotonic`() {
        val scene = director.directScene(instance, alice)!!
        assertEquals(1, scene.withTurn().turnCount)
        assertEquals(2, scene.withTurn().withTurn().turnCount)
    }

    @Test
    fun `a thread marked completed drops out of the scene`() {
        val finished = apply(
            engine.applyImmediately(instance, dev.charaly.runtime.domain.events.StoryThreadAdvanced(ThreadId("thread-ledger"), stage = 1, status = StoryThreadStatus.COMPLETED)),
        )
        val scene = director.directScene(finished, alice)!!
        assertFalse(scene.activeThreadIds.contains(ThreadId("thread-ledger")))
    }

    @Test
    fun `participants always include the focus character`() {
        val scene = director.deriveNewScene(instance, alice, LocationId("unpopulated"))
        assertTrue(scene.participants.contains(alice))
    }

    @Test
    fun `scene describe is structured not prose`() {
        val scene = director.directScene(instance, alice)!!
        val text = scene.describe()
        assertTrue(text.contains("scene:"))
        assertTrue(text.contains("location: library"))
        assertTrue(text.contains("participants: alice"))
    }

    @Test
    fun `scene state is a closed set`() {
        val scene = director.directScene(instance, alice)!!
        assertTrue(scene.isActive())
        assertFalse(scene.copy(state = dev.charaly.runtime.domain.SceneState.ENDED).isActive())
    }

    @Test
    fun `world variables do not leak into the scene`() {
        instance = instance.evolved(
            worldState = instance.worldState.withVariable(
                WorldVariable(key = "secret_flag", value = "hunter2", description = "internal"),
            ),
        )
        val scene = director.directScene(instance, alice)!!
        assertFalse("the scene is context, not a variable dump", scene.describe().contains("hunter2"))
    }

    private fun apply(application: EventApplication): StoryInstance {
        assertTrue(application is EventApplication.Applied)
        return (application as EventApplication.Applied).instance
    }
}
