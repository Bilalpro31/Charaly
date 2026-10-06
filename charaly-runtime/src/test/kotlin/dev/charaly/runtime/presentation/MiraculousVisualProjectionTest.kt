package dev.charaly.runtime.presentation

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.StoryTime
import dev.charaly.runtime.domain.WorldVariable
import dev.charaly.runtime.domain.WorldVariableType
import dev.charaly.runtime.domain.WorldDefinition
import dev.charaly.runtime.engine.StoryInstanceFactory
import dev.charaly.runtime.pack.DemoStoryPacks
import dev.charaly.runtime.pack.MiraculousPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE VISUAL SCENE PROJECTION.
 *
 * ## What is being protected
 *
 * Two failure modes this file exists to prevent, both of which look like art direction and
 * are actually correctness bugs:
 *
 *  1. **The UI inventing the world.** A background that changes because the model said
 *     something, or because a screen guessed, is a lie about world state. Every variation
 *     here must trace to something the engine holds.
 *  2. **A projection that mutates the world.** It is a read of state, not a write. If a
 *     projection could change `WorldState`, it would have become part of the simulation
 *     without anybody deciding that.
 *
 * ## Why Miraculous specifically
 *
 * It is the pack with the most places and the most cast, so it is where "the same gradient
 * in every scene" is most visible and where a generic avatar would be most obviously wrong.
 */
class MiraculousVisualProjectionTest {

    private val pack = DemoStoryPacks.all.first { it.id.value == MiraculousPack.ID }
    private val definition = WorldDefinition(pack.characters, pack.locations)

    /**
     * The pack keeps its NPC and masked-hero ids private on purpose, so a test that needs
     * one names it here rather than widening the pack's public surface.
     */
    private val CATNOIR = "catnoir"

    // ------------------------------------------------------------------
    // identity
    // ------------------------------------------------------------------

    @Test
    fun `portraits are keyed by runtime id, so two characters never share a face`() {
        val marinette = pack.characters.first { it.id.value == MiraculousPack.MARINETTE }
        val alya = pack.characters.first { it.id.value == MiraculousPack.ALYA }

        val a = VisualResolver.character(marinette).seed
        val b = VisualResolver.character(alya).seed
        assertNotEquals(
            "Adrien and Marinette must not resolve to the same drawing",
            a,
            b,
        )

        // Resolved from the id through the projection too, not only through the resolver.
        val projection = VisualSceneProjection.project(StoryInstanceFactory.create(pack, StoryInstanceId("s")), definition, pack)
        val portraitA = projection.portraitOf(CharacterId(MiraculousPack.MARINETTE), definition)
        val portraitB = projection.portraitOf(CharacterId(MiraculousPack.ALYA), definition)
        assertEquals("portrait:${MiraculousPack.MARINETTE}", portraitA.key)
        assertEquals("portrait:${MiraculousPack.ALYA}", portraitB.key)
    }

    @Test
    fun `a character the pack does not describe still gets a drawable portrait`() {
        val projection = VisualSceneProjection.project(StoryInstanceFactory.create(pack, StoryInstanceId("s")), definition, pack)
        val stranger = projection.portraitOf(CharacterId("someone-nobody-declared"), definition)
        assertNotNull(stranger)
        assertTrue("the fallback must still carry a stable seed", stranger.seed.isNotBlank())
    }

    @Test
    fun `every named character in the pack has its own portrait seed`() {
        val seeds = pack.characters.associate { it.id.value to VisualResolver.character(it).seed }
        val duplicates = seeds.values.groupingBy { it }.eachCount().filterValues { it > 1 }
        assertTrue(
            "these characters would draw identically: ${duplicates.keys}",
            duplicates.isEmpty(),
        )
        assertEquals(pack.characters.size, seeds.size)
    }

    // ------------------------------------------------------------------
    // location
    // ------------------------------------------------------------------

    @Test
    fun `the backdrop follows the player's location, not the scene that opened first`() {
        val instance = storyAt("bakery")
        val bakery = VisualSceneProjection.project(instance, definition, pack)
        assertEquals("bakery", bakery.locationId)
        assertEquals(
            pack.location(dev.charaly.runtime.domain.LocationId("bakery"))!!.name,
            bakery.locationName,
        )
    }

    @Test
    fun `every location in the pack declares the tags the visual layer needs`() {
        pack.locations.forEach { location ->
            assertTrue(
                "location ${location.id.value} has no tags, so it would draw as a generic " +
                    "skyline rather than as the place it is",
                location.tags.isNotEmpty(),
            )
        }
    }

    @Test
    fun `places that should look different are declared differently`() {
        fun tagsOf(id: String) = pack.location(dev.charaly.runtime.domain.LocationId(id))?.tags.orEmpty()

        val rooftop = tagsOf("rooftop")
        val classroom = tagsOf("classroom")
        val home = tagsOf("andre-home")
        assertTrue(rooftop.contains("rooftop"))
        assertTrue(classroom.contains("classroom"))
        assertTrue(home.contains("interior"))
        assertNotEquals(rooftop, classroom)
        assertNotEquals(classroom, home)
    }

    // ------------------------------------------------------------------
    // time and weather
    // ------------------------------------------------------------------

    @Test
    fun `time of day comes from the world clock and nothing else`() {
        fun at(hour: Int) = storyAt("rooftop", hour = hour).let {
            VisualSceneProjection.project(it, definition, pack).timeOfDay
        }
        assertEquals(TimeOfDay.DAWN, at(6))
        assertEquals(TimeOfDay.DAY, at(12))
        assertEquals(TimeOfDay.SUNSET, at(18))
        assertEquals(TimeOfDay.NIGHT, at(23))
        assertEquals(TimeOfDay.NIGHT, at(3))
    }

    @Test
    fun `the same place at night is a different scene from the same place at noon`() {
        val noon = VisualSceneProjection.project(storyAt("rooftop", hour = 12), definition, pack)
        val night = VisualSceneProjection.project(storyAt("rooftop", hour = 23), definition, pack)
        assertNotEquals(noon.cacheKey, night.cacheKey)
        assertNotEquals(noon.background.seed, night.background.seed)
        assertEquals(noon.locationId, night.locationId)
    }

    @Test
    fun `the projection is deterministic - the same state always draws the same picture`() {
        val a = VisualSceneProjection.project(storyAt("rooftop", hour = 21), definition, pack)
        val b = VisualSceneProjection.project(storyAt("rooftop", hour = 21), definition, pack)
        assertEquals(a.background.seed, b.background.seed)
        assertEquals(a.cacheKey, b.cacheKey)
    }

    @Test
    fun `a declared weather variant is chosen, and only when the world declares weather`() {
        val dry = VisualSceneProjection.project(storyAt("rooftop", hour = 21), definition, pack)
        assertEquals("", dry.weather)
        assertTrue("nothing may claim rain when the world never said so", dry.appliedVariants.none { it == "rain" })

        val wet = VisualSceneProjection.project(
            storyAt("rooftop", hour = 21).withWeather("rain"),
            definition,
            pack,
        )
        assertEquals("rain", wet.weather)
        assertTrue(wet.appliedVariants.contains("rain"))
        assertNotEquals(dry.background.seed, wet.background.seed)
    }

    @Test
    fun `prose never changes the backdrop`() {
        // A character saying it is raining is not world state. This is the assertion that
        // keeps the visual layer honest: nothing in the transcript can reach it.
        val instance = storyAt("rooftop", hour = 12).withWeather("")
        val before = VisualSceneProjection.project(instance, definition, pack)
        val after = VisualSceneProjection.project(
            instance.copy(
                conversation = instance.conversation.append(
                    dev.charaly.runtime.domain.TranscriptEntry(
                        id = "t1",
                        turn = 1,
                        role = dev.charaly.runtime.domain.TranscriptRole.CHARACTER,
                        text = "It is pouring with rain out here.",
                        at = instance.worldClock.now,
                    ),
                ),
            ),
            definition,
            pack,
        )
        assertEquals(before.background.seed, after.background.seed)
        assertEquals(before.appliedVariants, after.appliedVariants)
    }

    // ------------------------------------------------------------------
    // it is a read
    // ------------------------------------------------------------------

    @Test
    fun `projecting a scene changes nothing about the world`() {
        val instance = storyAt("rooftop", hour = 20)
        val before = instance.describeForComparison()
        repeat(5) {
            VisualSceneProjection.project(instance, definition, pack)
            VisualSceneProjection.project(instance.withWeather("rain"), definition, pack)
        }
        assertEquals(before, instance.describeForComparison())
    }

    @Test
    fun `a story with no resolvable location still produces a drawable scene`() {
        // A world definition with no places at all: the honest "nowhere" case, which must
        // still render a picture rather than leaving the scene empty.
        val emptyWorld = WorldDefinition(pack.characters, emptyList())
        // Nobody has a location, which is what "nowhere" actually means. An empty world
        // definition alone is not enough: the projection reads the location from the
        // instance, because the instance is the authority on where anybody is.
        val created = StoryInstanceFactory.create(pack, StoryInstanceId("nowhere"))
        val instance = created.copy(
            worldState = created.worldState.copy(
                characters = created.worldState.characters.mapValues { (_, runtime) ->
                    runtime.copy(locationId = null)
                },
            ),
        )
        val projection = VisualSceneProjection.project(instance, emptyWorld, pack)
        assertFalse(projection.hasLocation)
        assertEquals("", projection.locationId)
        assertTrue("the fallback must still have a stable seed", projection.background.seed.isNotBlank())
        assertTrue(projection.background.tier.toString().isNotBlank())
        assertFalse(projection.background.needsNetwork)
    }

    @Test
    fun `the chat stage carries the projection rather than re-deriving it`() {
        val instance = storyAt("rooftop", hour = 21)
        val stage = ChatStagePresenter.build(
            instance = instance,
            pack = pack,
            definition = definition,
        )
        assertEquals("rooftop", stage.visuals.locationId)
        assertEquals(TimeOfDay.NIGHT, stage.visuals.timeOfDay)
        assertEquals(
            VisualSceneProjection.project(instance, definition, pack).background.seed,
            stage.visuals.background.seed,
        )
    }

    @Test
    fun `a character's beat carries their runtime id rather than their name`() {
        val instance = storyAt("rooftop", hour = 12)
        val stage = ChatStagePresenter.build(
            instance = instance,
            pack = pack,
            definition = definition,
            phase = GenerationPhase.IDLE,
            streamingText = "We should go. The akuma will be there by now.",
        )
        val beat = stage.beats.firstOrNull { it.isStreaming }
        assertNotNull("the streaming beat should exist", beat)
        assertEquals(CATNOIR, beat!!.speakerPortraitSeed)
        assertTrue(beat.speakerPortraitSeed.isNotBlank())
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private fun storyAt(locationId: String, hour: Int = 9): StoryInstance {
        val created = StoryInstanceFactory.create(pack, StoryInstanceId("visual-$locationId-$hour"))
        val moved = created.evolved(
            worldState = created.worldState.withVariable(
                WorldVariable(
                    key = VisualSceneProjection.WEATHER_VARIABLE,
                    type = WorldVariableType.BOOLEAN,
                    value = "",
                ),
            ),
        )
        // Place the player where the test asked. Done through a state value the test owns,
        // because the point of the assertion is the projection and not the event engine.
        // The clock lives on WorldState, so moving it is a state edit - which is the point:
        // the projection reads time from the world, not from anywhere else.
        val withPlayer = moved.copy(
            worldState = moved.worldState.copy(
                worldClock = moved.worldState.worldClock.copy(
                    now = StoryTime.of(day = 2, hour = hour, minute = 30),
                ),
            ),
            focusCharacterId = CharacterId(CATNOIR),
        )
        return withPlayer.copy(
            worldState = withPlayer.worldState.copy(
                characters = withPlayer.worldState.characters.mapValues { (_, runtime) ->
                    if (runtime.characterId.value == CATNOIR) {
                        runtime.copy(locationId = dev.charaly.runtime.domain.LocationId(locationId))
                    } else {
                        runtime
                    }
                },
            ),
        )
    }

    private fun StoryInstance.withWeather(value: String): StoryInstance = copy(
        worldState = worldState.withVariable(
            WorldVariable(
                key = VisualSceneProjection.WEATHER_VARIABLE,
                type = WorldVariableType.TEXT,
                value = value,
            ),
        ),
    )

    /** A stable snapshot of everything a projection must not be able to change. */
    private fun StoryInstance.describeForComparison(): String = buildString {
        appendLine("revision=${worldState.revision}")
        appendLine("clock=${worldClock.now.format()}")
        appendLine("locations=${worldState.locations.keys.joinToString { it.value }}")
        appendLine(
            "characters=" + worldState.characters.entries.sortedBy { it.key.value }
                .joinToString { "${it.key.value}@${it.value.locationId?.value}" },
        )
        appendLine("scenes=${worldState.activeScenes.keys.joinToString { it.value }}")
        appendLine("log=${worldState.eventLog.joinToString { it.value }}")
        appendLine("variables=${worldState.variables.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value.value}" }}")
        appendLine("threads=${worldState.storyThreads.keys.joinToString { it.value }}")
    }
}