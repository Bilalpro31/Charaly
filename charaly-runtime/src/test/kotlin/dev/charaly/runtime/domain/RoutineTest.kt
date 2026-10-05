package dev.charaly.runtime.domain

import dev.charaly.runtime.engine.EventEngine
import dev.charaly.runtime.engine.EventApplication
import dev.charaly.runtime.engine.EventRejection
import dev.charaly.runtime.engine.PresenceEngine
import dev.charaly.runtime.engine.ScheduleResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An NPC is a resident of the world, not a line in a cast list.
 *
 * These tests encode the promise the whole "world, not chat" product rests on: a
 * character exists, has a place to be and a day to live, whether or not the player
 * has ever spoken to him.
 */
class RoutineTest {

    private val shop = LocationId("andre-shop")
    private val home = LocationId("andre-home")
    private val street = LocationId("street")

    private val andre = CharacterDefinition(
        id = CharacterId("andre"),
        name = "André",
        storyRole = CharacterRole.NPC,
        routine = Routine(
            homeLocationId = home,
            summary = "Runs the ice cream shop by day, goes home at night.",
            entries = listOf(
                RoutineEntry.at(8, locationId = shop, activity = CharacterActivity.WORKING, label = "opening the shop"),
                RoutineEntry.at(9, locationId = shop, activity = CharacterActivity.WORKING, label = "serving customers"),
                RoutineEntry.at(13, locationId = shop, activity = CharacterActivity.WORKING, label = "serving customers"),
                RoutineEntry.at(18, locationId = shop, activity = CharacterActivity.RESTING, label = "closing up"),
                RoutineEntry.at(19, locationId = home, activity = CharacterActivity.RESTING, label = "at home"),
            ),
        ),
    )

    private val world = WorldDefinition(
        characters = listOf(andre),
        locations = listOf(
            Location(id = shop, name = "André's Ice Cream Shop"),
            Location(id = home, name = "André's Flat"),
            Location(id = street, name = "Paris Street"),
        ),
    )

    private fun at(hour: Int, minute: Int = 0) = StoryTime.of(day = 1, hour = hour, minute = minute)

    private fun instanceAt(time: StoryTime, andreLocation: LocationId? = shop) = StoryInstance(
        id = StoryInstanceId("story-1"),
        storyPackId = StoryPackId("pack-x"),
        packTitle = "Test",
        worldState = WorldState(
            worldClock = WorldClock(time),
            locations = world.locations.associateBy { it.id },
            characters = mapOf(
                CharacterId("andre") to CharacterRuntime(
                    characterId = CharacterId("andre"),
                    name = "André",
                    locationId = andreLocation,
                    activity = CharacterActivity.WORKING,
                ),
            ),
        ),
    )

    // ---- resolution ----------------------------------------------------

    @Test
    fun `an entry runs until the next entry begins`() {
        assertEquals(shop, andre.routine.resolve(at(8, 30))?.locationId)
        assertEquals(shop, andre.routine.resolve(at(12, 59))?.locationId)
        assertEquals(shop, andre.routine.resolve(at(18, 30))?.locationId)
        assertEquals(home, andre.routine.resolve(at(19, 0))?.locationId)
        assertEquals(home, andre.routine.resolve(at(23, 59))?.locationId)
    }

    @Test
    fun `before the first entry the character wraps to yesterday's last placement`() {
        // 02:00 is before "08:00 opens the shop", so he is still at home.
        assertEquals(home, andre.routine.resolve(at(2, 0))?.locationId)
    }

    @Test
    fun `resolution carries human activity wording for the UI`() {
        assertEquals("serving customers", andre.routine.resolve(at(10, 0))?.activityLabel)
        assertEquals("closing up", andre.routine.resolve(at(18, 30))?.activityLabel)
    }

    @Test
    fun `a character with no routine has no placement`() {
        val unscheduled = CharacterDefinition(id = CharacterId("x"), name = "X")
        assertNull(unscheduled.routine.resolve(at(10, 0)))
        assertTrue(!unscheduled.routine.isScheduled)
    }

    @Test
    fun `two entries may not start at the same minute`() {
        val clash = runCatching {
            Routine(
                entries = listOf(
                    RoutineEntry.at(9, locationId = shop),
                    RoutineEntry.at(9, locationId = home),
                ),
            )
        }
        assertTrue("a duplicated start minute must be rejected", clash.isFailure)
    }

    // ---- presence engine ------------------------------------------------

    @Test
    fun `an npc is placed by his schedule without the player ever talking to him`() {
        // André has never been placed: routineEntryMinute is still -1.
        val instance = instanceAt(at(10, 0), andreLocation = home)
        val relocations = PresenceEngine(world).relocationsFor(instance)

        assertEquals(1, relocations.size)
        val relocation = relocations.single()
        assertEquals(CharacterId("andre"), relocation.characterId)
        assertEquals(shop, relocation.to)
        assertEquals("serving customers", relocation.activityLabel)
    }

    @Test
    fun `an npc who is already in the right place is not relocated`() {
        // Synced to the 09:00 entry ("serving customers") at 10:00: already correct, so
        // nothing to do.
        val instance = syncRoutine(
            instanceAt(at(10, 0), andreLocation = shop).let { synced ->
                synced.copy(
                    worldState = synced.worldState.copy(
                        characters = synced.worldState.characters.mapValues { (_, c) ->
                            c.copy(activityLabel = "serving customers")
                        },
                    ),
                )
            },
            9 * 60,
        )
        assertTrue(PresenceEngine(world).relocationsFor(instance).isEmpty())
    }

    @Test
    fun `advancing the clock moves the npc to where his day takes him`() {
        val engine = EventEngine(world)
        var instance = instanceAt(at(17, 0), andreLocation = shop)
        // Sync him to the afternoon entry so only the schedule moving matters.
        instance = syncRoutine(instance, 13 * 60)

        val result = engine.advanceClock(instance, StoryDuration.hours(2))

        val andre = result.instance.characters.getValue(CharacterId("andre"))
        assertEquals("Day 1, 19:00", result.instance.worldClock.now.format())
        assertEquals(home, andre.locationId)
        assertEquals("at home", andre.activityLabel)
    }

    @Test
    fun `an npc who follows a routine remains a world entity when nothing happens`() {
        // No event, no interaction: the character simply still exists, with a place.
        val instance = instanceAt(at(12, 0))
        val runtime = instance.characters.getValue(CharacterId("andre"))
        assertEquals("André", runtime.name)
        assertNotNull(runtime.locationId)
        assertTrue(PresenceEngine(world).residentsOf(shop, at(12, 0)).contains(CharacterId("andre")))
    }

    @Test
    fun `relocations are ordered deterministically by character id`() {
        val a = andre.copy(id = CharacterId("aaa"), routine = andre.routine.copy(entries = andre.routine.entries.map { it.copy(locationId = shop) }))
        val b = andre.copy(id = CharacterId("bbb"), name = "Bee", routine = a.routine)
        val manyWorld = WorldDefinition(listOf(b, a), world.locations)
        val instance = StoryInstance(
            id = StoryInstanceId("s"),
            storyPackId = StoryPackId("p"),
            packTitle = "P",
            worldState = WorldState(
                worldClock = WorldClock(at(10, 0)),
                locations = manyWorld.locations.associateBy { it.id },
                characters = listOf(a, b).associate {
                    it.id to CharacterRuntime(it.id, it.name, locationId = home)
                },
            ),
        )
        val ids = PresenceEngine(manyWorld).relocationsFor(instance).map { it.characterId.value }
        assertEquals(listOf("aaa", "bbb"), ids)
    }

    @Test
    fun `a background character is collective and is never given a schedule`() {
        val crowd = andre.copy(id = CharacterId("crowd"), name = "Students", storyRole = CharacterRole.BACKGROUND)
        val crowdWorld = WorldDefinition(listOf(crowd), world.locations)
        val instance = StoryInstance(
            id = StoryInstanceId("s"),
            storyPackId = StoryPackId("p"),
            packTitle = "P",
            worldState = WorldState(
                worldClock = WorldClock(at(10, 0)),
                locations = crowdWorld.locations.associateBy { it.id },
                characters = mapOf(crowd.id to CharacterRuntime(crowd.id, crowd.name, locationId = shop)),
            ),
        )
        assertTrue(
            "background crowds must not generate per-character relocations",
            PresenceEngine(crowdWorld).relocationsFor(instance).isEmpty(),
        )
        assertTrue(crowdWorld.character(crowd.id)?.storyRole?.hasIndividualState == false)
    }

    @Test
    fun `residents of a place are derived from the schedule, not from conversation`() {
        // At 03:00 nobody is at the shop, even though the world's state says so,
        // because the routine has not put him there yet.
        assertTrue(PresenceEngine(world).residentsOf(shop, at(3, 0)).isEmpty())
        assertEquals(listOf(CharacterId("andre")), PresenceEngine(world).residentsOf(shop, at(12, 0)))
    }

    // ---- engine application --------------------------------------------

    @Test
    fun `the event engine applies a routine relocation and records it`() {
        val engine = EventEngine(world)
        val instance = syncRoutine(instanceAt(at(10, 0), andreLocation = home), 9 * 60)
        val payload = dev.charaly.runtime.domain.events.CharacterRoutineApplied(
            characterId = CharacterId("andre"),
            from = home,
            to = shop,
            activity = CharacterActivity.WORKING,
            activityLabel = "serving customers",
            routineEntryMinute = 9 * 60,
        )
        val scheduled = engine.scheduleEvent(instance, payload, instance.worldClock.now) as ScheduleResult.Scheduled
        val applied = engine.applyEvent(scheduled.instance, scheduled.events.first())

        assertTrue(applied is EventApplication.Applied)
        val moved = (applied as EventApplication.Applied).instance
        assertEquals(shop, moved.characters.getValue(CharacterId("andre")).locationId)
        assertEquals(9 * 60, moved.characters.getValue(CharacterId("andre")).routineEntryMinute)
        // The relocation is part of the replayable event log, like every world change.
        assertTrue(
            "the applied relocation must be recorded in the event log",
            moved.worldState.eventLog.map { it.value }.contains(scheduled.events.first().id.value),
        )
    }

    @Test
    fun `a relocation to a place the pack does not define is rejected`() {
        val engine = EventEngine(world)
        val instance = instanceAt(at(10, 0), andreLocation = home)
        val payload = dev.charaly.runtime.domain.events.CharacterRoutineApplied(
            characterId = CharacterId("andre"),
            from = home,
            to = LocationId("atlantis"),
            activity = CharacterActivity.IDLE,
        )
        val scheduled = engine.scheduleEvent(instance, payload, instance.worldClock.now) as ScheduleResult.Scheduled
        val applied = engine.applyEvent(scheduled.instance, scheduled.events.first())
        assertTrue(applied is EventApplication.Rejected)
        assertEquals(
            EventRejection.UNKNOWN_LOCATION,
            (applied as EventApplication.Rejected).validation.errors.single(),
        )
    }

    @Test
    fun `a stale relocation is rejected rather than silently overwriting a newer world`() {
        val engine = EventEngine(world)
        val instance = instanceAt(at(10, 0), andreLocation = shop)
        val payload = dev.charaly.runtime.domain.events.CharacterRoutineApplied(
            characterId = CharacterId("andre"),
            from = home, // stale: he is actually at the shop
            to = home,
            activity = CharacterActivity.IDLE,
        )
        val scheduled = engine.scheduleEvent(instance, payload, instance.worldClock.now) as ScheduleResult.Scheduled
        val applied = engine.applyEvent(scheduled.instance, scheduled.events.first())
        assertTrue(applied is EventApplication.Rejected)
        assertEquals(
            EventRejection.STALE_FROM_LOCATION,
            (applied as EventApplication.Rejected).validation.errors.single(),
        )
    }

    @Test
    fun `an unknown character cannot be given a routine`() {
        val engine = EventEngine(world)
        val instance = instanceAt(at(10, 0))
        val payload = dev.charaly.runtime.domain.events.CharacterRoutineApplied(
            characterId = CharacterId("nobody"),
            from = null,
            to = shop,
            activity = CharacterActivity.IDLE,
        )
        val scheduled = engine.scheduleEvent(instance, payload, instance.worldClock.now) as ScheduleResult.Scheduled
        val applied = engine.applyEvent(scheduled.instance, scheduled.events.first())
        assertTrue(
            (applied as EventApplication.Rejected).validation.errors.contains(EventRejection.UNKNOWN_CHARACTER),
        )
    }

    private fun syncRoutine(instance: StoryInstance, minute: Int): StoryInstance = instance.copy(
        worldState = instance.worldState.copy(
            characters = instance.worldState.characters.mapValues { (_, c) -> c.copy(routineEntryMinute = minute) },
        ),
    )
}