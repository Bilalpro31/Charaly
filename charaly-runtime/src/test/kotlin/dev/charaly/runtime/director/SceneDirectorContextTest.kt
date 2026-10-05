package dev.charaly.runtime.director

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.StoryTime
import dev.charaly.runtime.domain.WorldDefinition
import dev.charaly.runtime.engine.EventEngine
import dev.charaly.runtime.engine.StoryCreationOptions
import dev.charaly.runtime.engine.StoryInstanceFactory
import dev.charaly.runtime.pack.MiraculousPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SceneDirector 2.0.
 *
 * The director assembles the *context of a moment* and never the moment's prose. The
 * interesting new parts are mood, objective and the arrival/departure hints, and all
 * three have to be derived from world state rather than invented - a director that
 * made something up would be a second narrator competing with the model.
 */
class SceneDirectorContextTest {

    private val pack = MiraculousPack.pack
    private val definition = WorldDefinition(pack.characters, pack.locations)
    private val director = SceneDirector(definition)
    private val engine = EventEngine(definition)

    private val andre = CharacterId("andre")
    private val marinette = CharacterId("marinette")
    private val shop = LocationId("andre-ice-cream")
    private val school = LocationId("school")

    private fun instance() = StoryInstanceFactory.create(
        pack,
        StoryCreationOptions(
            instanceId = StoryInstanceId("story-scene"),
            title = "Scenes",
            scenario = pack.defaultScenario(),
            focusCharacterId = marinette,
            castCharacterIds = listOf(marinette, andre),
            nowEpochMs = 1_700_000_000_000L,
        ),
    )

    private fun at(time: StoryTime, current: dev.charaly.runtime.domain.StoryInstance = instance()) =
        engine.advanceTime(current, time).instance

    // ------------------------------------------------------------------
    // It decides the scene, and never the prose
    // ------------------------------------------------------------------

    @Test
    fun `the director produces a scene, not a sentence`() {
        val scene = director.directScene(instance(), andre)!!
        assertNotNull(scene.id)
        assertNotNull(scene.locationId)
        assertTrue(scene.objective.isNotBlank())
        // Nothing here is prose: there is no field on a scene a narrator could fill.
        assertTrue(scene.mood.isNotBlank())
    }

    @Test
    fun `the same story and focus always produce the same scene`() {
        val a = director.directScene(instance(), andre)!!
        val b = director.directScene(instance(), andre)!!
        assertEquals(a.copy(id = dev.charaly.runtime.domain.SceneId("x")), b.copy(id = dev.charaly.runtime.domain.SceneId("x")))
    }

    // ------------------------------------------------------------------
    // Participants: the world, not the model
    // ------------------------------------------------------------------

    @Test
    fun `participants are whoever is actually co-located`() {
        val scene = director.directScene(instance(), andre)!!
        val actual = instance().worldState.charactersAt(scene.locationId).map { it.characterId }
        assertTrue("Andre must be in his own scene", actual.contains(andre))
        actual.forEach { assertTrue("a co-located character is not a participant", scene.participants.contains(it)) }
    }

    @Test
    fun `somebody about to arrive is not a participant`() {
        // The whole point of separating "may arrive" from "is here": a hint must not
        // become presence, or the world invents a character and then has to explain them.
        val morning = at(StoryTime.of(day = 1, hour = 8, minute = 30))
        val scene = director.directScene(morning, andre)!!
        scene.possibleArrivals.forEach { arriving ->
            assertFalse(
                "${arriving.value} is listed as arriving and as present",
                scene.participants.contains(arriving),
            )
        }
    }

    // ------------------------------------------------------------------
    // Mood: derived, and actionable
    // ------------------------------------------------------------------

    @Test
    fun `mood names the part of the day`() {
        // Day two, not day one: the story starts at 08:10 and time does not run
        // backwards, so 03:00 on day one is in the past and the clock must stay put.
        val smallHours = director.directScene(at(StoryTime.of(day = 2, hour = 3, minute = 0)), andre)!!
        assertTrue(smallHours.mood, smallHours.mood.contains("small hours"))

        val lunchtime = director.directScene(at(StoryTime.of(day = 2, hour = 12, minute = 0)), andre)!!
        assertTrue(lunchtime.mood, lunchtime.mood.contains("lunchtime"))
    }

    @Test
    fun `time does not run backwards`() {
        val before = at(StoryTime.of(day = 1, hour = 20, minute = 0))
        val after = at(StoryTime.of(day = 1, hour = 3, minute = 0), before)
        assertEquals(
            "asking for a time already gone must not rewind the world",
            StoryTime.of(day = 1, hour = 20, minute = 0),
            after.worldClock.now,
        )
    }

    @Test
    fun `mood distinguishes being alone from being crowded`() {
        val alone = director.directScene(at(StoryTime.of(day = 1, hour = 20, minute = 0)), andre)!!
        assertTrue("late at night alone", alone.mood.contains("alone"))

        val busy = director.directScene(at(StoryTime.of(day = 1, hour = 10, minute = 30)), marinette)!!
        val crowd = instance().worldState.charactersAt(school).size
        if (crowd >= 3) {
            assertTrue("a full schoolyard is not a private conversation", busy.mood.contains("people"))
        }
    }

    @Test
    fun `mood mentions something unresolved when a thread is live`() {
        // Marinette at school at the start of the day is the one moment the pack
        // actually has a live arc in front of the player. Asserting on Andre in his
        // shop would be asserting on a scene with no thread in it.
        val scene = director.directScene(at(StoryTime.of(day = 1, hour = 8, minute = 45)), marinette)!!
        if (scene.activeThreadIds.isNotEmpty()) {
            assertTrue(
                "a scene with an open arc should say so: ${scene.mood}",
                scene.mood.contains("going on") || scene.mood.contains("unresolved"),
            )
        }
    }

    @Test
    fun `mood is an instruction rather than an adjective`() {
        // A model can act on "alone, and it is noticeable" and cannot act on "melancholy".
        val scene = director.directScene(instance(), andre)!!
        assertFalse("mood must not be a bare mood word", scene.mood.isBlank())
        assertTrue(scene.mood.contains(" "))
    }

    // ------------------------------------------------------------------
    // Objective
    // ------------------------------------------------------------------

    @Test
    fun `the objective names a live thread when there is one`() {
        val scene = director.directScene(at(StoryTime.of(day = 1, hour = 8, minute = 45)), andre)!!
        if (scene.activeThreadIds.isNotEmpty()) {
            val thread = instance().storyThreads.getValue(scene.activeThreadIds.first())
            assertTrue(
                "the objective should name the thread: ${scene.objective}",
                scene.objective.contains(thread.title),
            )
        }
    }

    @Test
    fun `with no live thread the objective describes the place and the hour`() {
        val scene = director.directScene(at(StoryTime.of(day = 1, hour = 20, minute = 0)), andre)!!
        if (scene.activeThreadIds.isEmpty()) {
            assertTrue(scene.objective, scene.objective.contains("ordinary moment"))
        }
    }

    @Test
    fun `the objective never invents a situation`() {
        // A director that made something up here would be a second narrator. Every
        // objective has to be traceable to a thread, a place or the clock.
        val scene = director.directScene(at(StoryTime.of(day = 1, hour = 20, minute = 0)), andre)!!
        val traceable = scene.activeThreadIds.isNotEmpty() ||
            scene.objective.contains("ordinary moment") ||
            scene.objective.contains("(")
        assertTrue("the objective must be traceable: ${scene.objective}", traceable)
    }

    // ------------------------------------------------------------------
    // Arrival and departure hints
    // ------------------------------------------------------------------

    @Test
    fun `arrival hints come from routines, not from invention`() {
        val evening = at(StoryTime.of(day = 1, hour = 20, minute = 0))
        val scene = director.directScene(evening, andre)!!
        scene.possibleArrivals.forEach { arriving ->
            val character = definition.character(arriving)
            assertNotNull("an arrival hint must name a real character", character)
            assertTrue(
                "${arriving.value} has no schedule, so nothing can be predicted about it",
                character!!.routine.isScheduled,
            )
        }
    }

    @Test
    fun `departure hints only name people who are here and are scheduled to leave`() {
        val morning = at(StoryTime.of(day = 1, hour = 8, minute = 30))
        val scene = director.directScene(morning, andre)!!
        scene.possibleDepartures.forEach { leaving ->
            assertTrue(
                "${leaving.value} is listed as leaving but is not in the scene",
                scene.participants.contains(leaving),
            )
        }
    }

    @Test
    fun `hints are capped, because a scene listing fifteen names is not a scene`() {
        listOf(StoryTime.of(day = 1, hour = 8, minute = 30), StoryTime.of(day = 1, hour = 17, minute = 0))
            .forEach { time ->
                val scene = director.directScene(at(time), marinette)!!
                assertTrue(
                    "too many arrival hints at $time: ${scene.possibleArrivals.size}",
                    scene.possibleArrivals.size <= SceneDirector.MAX_HINT_CHARACTERS,
                )
                assertTrue(
                    "too many departure hints at $time: ${scene.possibleDepartures.size}",
                    scene.possibleDepartures.size <= SceneDirector.MAX_HINT_CHARACTERS,
                )
            }
    }

    @Test
    fun `a character already present is never listed as arriving`() {
        listOf(7, 8, 12, 17, 20).forEach { hour ->
            val story = at(StoryTime.of(day = 1, hour = hour, minute = 0))
            val scene = director.directScene(story, andre)!!
            scene.possibleArrivals.forEach {
                assertFalse("hour $hour: ${it.value} is both here and arriving", scene.participants.contains(it))
            }
        }
    }

    // ------------------------------------------------------------------
    // Threads: relevance, not enumeration
    // ------------------------------------------------------------------

    @Test
    fun `a scene names only a few threads`() {
        val scene = director.directScene(at(StoryTime.of(day = 1, hour = 8, minute = 45)), andre)!!
        assertTrue(
            "a scene with no focus is not a scene: ${scene.activeThreadIds.size}",
            scene.activeThreadIds.size <= SceneDirector.MAX_THREADS_PER_SCENE,
        )
    }

    @Test
    fun `threads are ordered by the priority the author set`() {
        val scene = director.directScene(at(StoryTime.of(day = 1, hour = 8, minute = 45)), andre)!!
        if (scene.activeThreadIds.size > 1) {
            val priorities = scene.activeThreadIds.map { instance().storyThreads.getValue(it).priority }
            assertEquals("threads must be ordered by priority", priorities.sortedDescending(), priorities)
        }
    }

    // ------------------------------------------------------------------
    // Refinement
    // ------------------------------------------------------------------

    @Test
    fun `a live scene is re-derived, not frozen`() {
        // A scene is a view of the world. If it were stored once and never recomputed,
        // the prompt would keep describing a moment that has already passed.
        val morning = at(StoryTime.of(day = 1, hour = 8, minute = 45))
        val scene = director.directScene(morning, andre)!!
        val evening = at(StoryTime.of(day = 1, hour = 20, minute = 0), morning)
        val refined = director.directScene(evening, andre)!!

        assertTrue(
            "an evening scene should not read as morning",
            refined.mood != scene.mood,
        )
    }

    @Test
    fun `the scene describes its own hints`() {
        val scene = director.directScene(at(StoryTime.of(day = 1, hour = 17, minute = 0)), marinette)!!
        val described = scene.describe()
        if (scene.possibleArrivals.isNotEmpty()) assertTrue(described.contains("may arrive"))
        if (scene.possibleDepartures.isNotEmpty()) assertTrue(described.contains("may leave"))
        assertTrue(described.contains("mood:"))
    }

    @Test
    fun `being possibly here is not being here`() {
        val scene = director.directScene(instance(), andre)!!
        scene.possibleArrivals.forEach {
            assertFalse("${it.value} is 'possibly here' and so must not read as here", scene.isPresent(it))
        }
        assertTrue(scene.isPresent(andre))
    }
}