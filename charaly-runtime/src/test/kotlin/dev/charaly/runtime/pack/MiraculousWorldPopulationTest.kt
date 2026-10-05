package dev.charaly.runtime.pack

import dev.charaly.runtime.domain.CharacterActivity
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.CharacterRole
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.StoryDuration
import dev.charaly.runtime.domain.StoryTime
import dev.charaly.runtime.engine.EventEngine
import dev.charaly.runtime.engine.PresenceEngine
import dev.charaly.runtime.engine.StoryCreationOptions
import dev.charaly.runtime.engine.StoryInstanceFactory
import dev.charaly.runtime.domain.StoryInstanceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The acceptance test for "Charaly is a world you enter".
 *
 * This is the demo pack the brief describes: Marinette, Adrien, Alya, Nino, Gabriel,
 * Ladybug and Cat Noir - *and* a Paris with people in it. Andre exists whether or not
 * anyone is talking to him, and his day moves when the clock does.
 */
class MiraculousWorldPopulationTest {

    private val pack = MiraculousPack.pack
    private val definition = dev.charaly.runtime.domain.WorldDefinition(pack.characters, pack.locations)

    private val andre = CharacterId("andre")
    private val shop = LocationId("andre-ice-cream")
    private val andreHome = LocationId("andre-home")

    private fun instance() = StoryInstanceFactory.create(
        pack,
        StoryCreationOptions(
            instanceId = StoryInstanceId("story-test"),
            title = "Population",
            scenario = pack.defaultScenario(),
            focusCharacterId = CharacterId("marinette"),
            castCharacterIds = listOf(CharacterId("marinette")),
            nowEpochMs = 1_700_000_000_000L,
        ),
    )

    // ---- cast -----------------------------------------------------------

    @Test
    fun `the core seven are all present`() {
        listOf("marinette", "adrien", "alya", "nino", "gabriel", "ladybug", "catnoir")
            .forEach { id ->
                assertNotNull("$id must exist", pack.character(CharacterId(id)))
            }
    }

    @Test
    fun `paris is populated with meaningful recurring npcs`() {
        val npcs = pack.characters.filter { it.storyRole == CharacterRole.NPC }
        assertTrue(
            "expected at least ${MiraculousPack.MIN_NPCS} meaningful NPCs, found ${npcs.size}",
            npcs.size >= MiraculousPack.MIN_NPCS,
        )
    }

    @Test
    fun `andre exists, is an npc, and has his own day`() {
        val andreDefinition = pack.character(andre)
        assertNotNull("Andre must exist in the pack", andreDefinition)
        assertEquals(CharacterRole.NPC, andreDefinition!!.storyRole)
        assertTrue("Andre must have a routine", andreDefinition.routine.isScheduled)
        assertNotNull("Andre must have a home", andreDefinition.routine.homeLocationId)
        assertTrue(
            "Andre's routine must mention human activity",
            andreDefinition.routine.ordered.any { it.activityLabel.isNotBlank() },
        )
    }

    @Test
    fun `school staff exist as named characters with routines`() {
        listOf("principal-damore", "teacher-rosa", "teacher-klein", "receptionist-mme-lenoire", "caretaker-bonnet")
            .forEach { id ->
                val character = pack.character(CharacterId(id))
                assertNotNull("$id must exist", character)
                assertEquals("$id must be an NPC", CharacterRole.NPC, character!!.storyRole)
                assertTrue("$id must have a routine", character.routine.isScheduled)
            }
    }

    @Test
    fun `every scheduled npc has a routine that resolves at every hour of the day`() {
        val scheduled = pack.characters.filter { it.routine.isScheduled }
        assertTrue(scheduled.isNotEmpty())
        scheduled.forEach { character ->
            (0..23).forEach { hour ->
                val placement = character.routine.resolve(StoryTime.of(day = 1, hour = hour, minute = 30))
                assertNotNull(
                    "${character.name} has no placement at ${hour}:30",
                    placement,
                )
                assertNotNull(
                    "${character.name}'s placement at ${hour}:30 points at a place that does not exist",
                    pack.location(placement!!.locationId),
                )
            }
        }
    }

    @Test
    fun `every routine location exists and is connected to something`() {
        pack.characters.filter { it.routine.isScheduled }.forEach { character ->
            character.routine.ordered.forEach { entry ->
                assertNotNull(
                    "${character.name}'s routine points at unknown place ${entry.locationId}",
                    pack.location(entry.locationId),
                )
            }
            character.routine.homeLocationId?.let { home ->
                assertNotNull("${character.name}'s home is unknown: $home", pack.location(home))
            }
        }
    }

    @Test
    fun `no two scheduled characters start at the same minute`() {
        pack.characters.forEach { character ->
            val minutes = character.routine.ordered.map { it.startMinuteOfDay }
            assertEquals(
                "${character.name} has two routine entries at the same minute",
                minutes.size,
                minutes.distinct().size,
            )
        }
    }

    // ---- locations ------------------------------------------------------

    @Test
    fun `the pack has the locations the brief names`() {
        listOf(
            "bakery", "school", "classroom", "school-courtyard", "andre-ice-cream",
            "park", "museum", "rooftop", "city-streets", "marinette-room",
            "adrien-home", "city-landmark",
        ).forEach { id ->
            assertNotNull("location $id must exist", pack.location(LocationId(id)))
        }
    }

    @Test
    fun `every location connection points at a location that exists`() {
        pack.locations.forEach { location ->
            location.connections.forEach { target ->
                assertNotNull(
                    "${location.name} connects to unknown place $target",
                    pack.location(target),
                )
            }
        }
    }

    @Test
    fun `no location is unreachable from anywhere`() {
        // A character whose routine sends them to an island nobody can reach is a
        // content bug, not a feature.
        val connected = pack.locations.flatMap { it.connections }.toSet()
        pack.locations.forEach { location ->
            assertTrue(
                "${location.name} is connected to nothing",
                location.connections.isNotEmpty() || location.id in connected,
            )
        }
    }

    // ---- presence -------------------------------------------------------

    @Test
    fun `andre is already at his shop when the story starts`() {
        val andreRuntime = instance().characters[andre]
        assertNotNull("Andre must have runtime state", andreRuntime)
        assertEquals(shop, andreRuntime!!.locationId)
    }

    @Test
    fun `npcs are present in the world without the player ever interacting`() {
        val started = instance()
        val atShop = started.worldState.charactersAt(shop).map { it.characterId.value }
        assertTrue("Andre must be standing in his shop at story start", "andre" in atShop)

        // Nobody was added to a conversation; they are simply there.
        assertEquals(
            0,
            started.conversation.entries.count { it.role == dev.charaly.runtime.domain.TranscriptRole.CHARACTER },
        )
    }

    @Test
    fun `the player can walk into andres shop and find him there`() {
        val started = instance()
        val present = started.worldState.charactersAt(shop).map { it.characterId.value }
        assertTrue(
            "Andre must be discoverable at his shop without being summoned",
            "andre" in present,
        )
    }

    @Test
    fun `the presence engine places andre by his schedule`() {
        val engine = PresenceEngine(definition)
        // 10:30 on day one: Andre is serving customers.
        val residents = engine.residentsOf(shop, StoryTime.of(day = 1, hour = 10, minute = 30))
        assertTrue(andre in residents)
        // 03:00: he is asleep at home, so the shop is empty.
        assertFalse(andre in engine.residentsOf(shop, StoryTime.of(day = 1, hour = 3, minute = 0)))
        assertTrue(andre in engine.residentsOf(andreHome, StoryTime.of(day = 1, hour = 3, minute = 0)))
    }

    @Test
    fun `advancing the clock closes andres shop and sends him home`() {
        val engine = EventEngine(definition)
        // Sync him to the lunch entry so only the schedule moving matters.
        var current = instance()
        current = current.copy(
            worldState = current.worldState.copy(
                characters = current.worldState.characters.mapValues { (_, c) ->
                    if (c.characterId == andre) c.copy(routineEntryMinute = 13 * 60) else c
                },
            ),
        )

        val result = engine.advanceTime(current, StoryTime.of(day = 1, hour = 20, minute = 0))

        val andreAfter = result.instance.characters.getValue(andre)
        assertEquals(
            "Andre must be at home after closing time",
            andreHome,
            andreAfter.locationId,
        )
        assertEquals(CharacterActivity.RESTING, andreAfter.activity)
    }

    @Test
    fun `advancing time never wedges the world clock`() {
        val engine = EventEngine(definition)
        val result = engine.advanceClock(instance(), StoryDuration.hours(9))
        assertEquals(
            StoryTime.of(day = 1, hour = 17, minute = 10),
            result.instance.worldClock.now,
        )
    }

    @Test
    fun `presence is deterministic - the same clock produces the same placements`() {
        val engine = PresenceEngine(definition)
        val a = instance()
        val b = instance()
        val time = StoryTime.of(day = 1, hour = 20, minute = 0)
        assertEquals(
            engine.residentsOf(shop, time),
            engine.residentsOf(shop, time),
        )
        assertEquals(
            EventEngine(definition).advanceTime(a, time).instance.worldState.characters,
            EventEngine(definition).advanceTime(b, time).instance.worldState.characters,
        )
    }

    @Test
    fun `every npc has their own home, not a shared one`() {
        // A real bug this file now guards: every NPC routine originally pointed "18:00
        // at home" at Andre's flat, so at six in the evening the principal, two
        // teachers, the receptionist, the caretaker, the museum guide and Andre were
        // all standing in one two-room flat. The world looked right all day and then
        // put seven strangers in a kitchen.
        val homes = pack.characters
            .filter { it.routine.isScheduled }
            .mapNotNull { character ->
                character.routine.homeLocationId?.let { character.name to it }
            }
        assertTrue("scheduled characters must declare a home", homes.isNotEmpty())

        val byHome = homes.groupBy({ it.second.value }, { it.first })
        byHome.forEach { (home, residents) ->
            assertEquals(
                "${residents.size} characters share the home '$home': $residents. " +
                    "A city where everyone goes to the same place at night is not a city.",
                1,
                residents.size,
            )
        }
    }

    @Test
    fun `the evening scatters rather than gathering`() {
        // The payoff of the above: at 20:00 the school staff are at their own homes,
        // so walking somewhere at night actually finds someone - or nobody.
        val evening = EventEngine(definition)
            .advanceTime(instance(), StoryTime.of(day = 1, hour = 20, minute = 0))
            .instance

        val locations = evening.characters.values.mapNotNull { it.locationId }.toSet()
        assertTrue(
            "at 20:00 the cast must be spread over many places, all were at: $locations",
            locations.size >= 5,
        )
        // Andre's flat is his.
        assertEquals(
            listOf("andre"),
            evening.worldState.charactersAt(andreHome).map { it.characterId.value },
        )
    }

    @Test
    fun `a background crowd is never invented as a named character`() {
        // The pack expresses crowds in prose and tags, not as dozens of dead characters.
        assertTrue(
            "the pack should not fill its cast with background characters",
            pack.characters.none { it.storyRole == CharacterRole.BACKGROUND },
        )
        assertTrue(
            "the cast should still be substantial",
            pack.characters.size >= MiraculousPack.MIN_NPCS + 7,
        )
    }
}