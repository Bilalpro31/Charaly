package dev.charaly.runtime.engine

import dev.charaly.runtime.domain.CharacterActivity
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.EntityId
import dev.charaly.runtime.domain.FactId
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.RelationshipDelta
import dev.charaly.runtime.domain.RelationshipType
import dev.charaly.runtime.domain.SceneId
import dev.charaly.runtime.domain.StoryDuration
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.StoryThreadStatus
import dev.charaly.runtime.domain.StoryTime
import dev.charaly.runtime.domain.ThreadId
import dev.charaly.runtime.domain.WorldDefinition
import dev.charaly.runtime.domain.events.CharacterMoved
import dev.charaly.runtime.domain.events.EventOrigin
import dev.charaly.runtime.domain.events.KnowledgeDiscovered
import dev.charaly.runtime.domain.events.RelationshipChanged
import dev.charaly.runtime.domain.events.SceneEnded
import dev.charaly.runtime.domain.events.SceneStarted
import dev.charaly.runtime.domain.events.StoryThreadAdvanced
import dev.charaly.runtime.domain.events.WorldVariableSet
import dev.charaly.runtime.domain.memory.Memory
import dev.charaly.runtime.domain.memory.MemorySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The event engine is the heart of Charaly's determinism guarantee.
 *
 * The central rule under test: `CharacterMoved(Alice, Library)` results in
 * `alice.location == Library`, deterministically, with no model involved.
 */
class EventEngineTest {

    private lateinit var definition: WorldDefinition
    private lateinit var engine: EventEngine
    private lateinit var instance: dev.charaly.runtime.domain.StoryInstance

    private val alice = CharacterId("alice")
    private val bob = CharacterId("bob")
    private val library = LocationId("library")
    private val square = LocationId("square")

    @Before
    fun setUp() {
        definition = WorldDefinition(
            characters = SampleWorlds.libraryPack().characters,
            locations = SampleWorlds.libraryPack().locations,
        )
        engine = EventEngine(definition)
        instance = StoryInstanceFactory.create(SampleWorlds.libraryPack(), StoryInstanceId("s1"))
    }

    // ------------------------------------------------------------------
    // The headline guarantee
    // ------------------------------------------------------------------

    @Test
    fun `moving a character deterministically relocates them`() {
        val applied = engine.applyImmediately(instance, CharacterMoved(bob, from = square, to = library))
        val result = applied.asInstance()
        assertEquals(library, result.characters.getValue(bob).locationId)
        assertEquals("identity survives the move", "Bob", result.characters.getValue(bob).name)
    }

    @Test
    fun `the same event twice produces the same world`() {
        val first = engine.applyImmediately(instance, CharacterMoved(bob, from = square, to = library)).asInstance()
        val second = engine.applyImmediately(instance, CharacterMoved(bob, from = square, to = library)).asInstance()
        assertEquals(first.worldState.characters, second.worldState.characters)
    }

    @Test
    fun `a move updates the recorded from-location`() {
        assertEquals("sanity: bob starts at the square", square, instance.characters.getValue(bob).locationId)
        val moved = engine.applyImmediately(instance, CharacterMoved(bob, from = square, to = library)).asInstance()
        assertEquals(library, moved.characters.getValue(bob).locationId)
    }

    @Test
    fun `illegal movement between unconnected places is rejected`() {
        // Pin the topology: the library's only exit is its own door, so the
        // market square is unreachable from inside it.
        val packed = SampleWorlds.libraryPack().let { pack ->
            pack.copy(
                locations = listOf(
                    SampleWorlds.library.copy(connections = listOf(library)),
                    SampleWorlds.square.copy(connections = emptyList()),
                ),
            )
        }
        val strictDefinition = WorldDefinition(packed.characters, packed.locations)
        val strictEngine = EventEngine(strictDefinition)
        val strictInstance = StoryInstanceFactory.create(packed, StoryInstanceId("s-strict"))

        val result = strictEngine.applyImmediately(
            strictInstance,
            CharacterMoved(alice, from = library, to = square),
        )
        assertTrue("expected a rejection, got $result", result is EventApplication.Rejected)
        assertTrue(
            (result as EventApplication.Rejected).validation.errors.contains(EventRejection.ILLEGAL_MOVEMENT),
        )
        // A rejected event must not touch the world.
        assertEquals(library, strictInstance.characters.getValue(alice).locationId)
    }

    @Test
    fun `adjacent movement is allowed`() {
        // Library <-> square are connected in the sample pack.
        val moved = engine.applyImmediately(
            instance,
            CharacterMoved(alice, from = library, to = square),
        ).asInstance()
        assertEquals(square, moved.characters.getValue(alice).locationId)
    }

    @Test
    fun `moving to the current location is a validated no-op`() {
        val result = engine.applyImmediately(instance, CharacterMoved(alice, from = library, to = library))
        val applied = result as EventApplication.Applied
        assertTrue(applied.noOp)
        assertEquals(library, applied.instance.characters.getValue(alice).locationId)
    }

    @Test
    fun `a stale from-location is rejected`() {
        // Bob is at the square, not the library.
        val result = engine.applyImmediately(instance, CharacterMoved(bob, from = library, to = library))
        assertTrue(result is EventApplication.Rejected)
    }

    @Test
    fun `unknown characters and locations are rejected`() {
        assertTrue(engine.applyImmediately(instance, CharacterMoved(CharacterId("ghost"), from = null, to = library)) is EventApplication.Rejected)
        assertTrue(engine.applyImmediately(instance, CharacterMoved(bob, from = square, to = LocationId("atlantis"))) is EventApplication.Rejected)
    }

    // ------------------------------------------------------------------
    // Scheduling
    // ------------------------------------------------------------------

    @Test
    fun `scheduled events stay queued until their time`() {
        val scheduled = engine.scheduleAfter(instance, CharacterMoved(bob, from = square, to = library), StoryDuration(30))
        val queued = scheduled.asScheduled().instance
        assertEquals(1, queued.eventQueue.size)
        assertEquals("not applied yet", square, queued.characters.getValue(bob).locationId)

        val drained = engine.advanceClock(queued, StoryDuration(30))
        assertEquals(library, drained.instance.characters.getValue(bob).locationId)
        assertTrue(drained.instance.eventQueue.isEmpty())
    }

    @Test
    fun `events due earlier than the target all fire`() {
        val queued = engine.scheduleAfter(instance, CharacterMoved(bob, from = square, to = library), StoryDuration(10))
            .asScheduled()
            .let { engine.scheduleAfter(it.instance, CharacterMoved(alice, from = library, to = square), StoryDuration(20)).asScheduled().instance }

        val drained = engine.advanceClock(queued, StoryDuration(120))
        assertEquals(library, drained.instance.characters.getValue(bob).locationId)
        assertEquals(square, drained.instance.characters.getValue(alice).locationId)
        assertTrue(drained.isClean)
    }

    @Test
    fun `the queue is ordered by time then sequence`() {
        var state = engine.scheduleAfter(instance, CharacterMoved(bob, from = square, to = library), StoryDuration(10)).asScheduled().instance
        state = engine.scheduleAfter(state, CharacterMoved(alice, from = library, to = square), StoryDuration(5)).asScheduled().instance
        val start = state.worldClock.now.totalMinutes
        val delays = state.eventQueue.snapshot().map { it.scheduledAt.totalMinutes - start }
        assertEquals(listOf(5L, 10L), delays)
    }

    @Test
    fun `events at the same time keep insertion order`() {
        var state = engine.scheduleAfter(instance, CharacterMoved(bob, from = square, to = library), StoryDuration(5))
            .asScheduled().instance
        state = engine.scheduleAfter(state, CharacterMoved(alice, from = library, to = square), StoryDuration(5))
            .asScheduled().instance

        val order = state.eventQueue.snapshot()
        assertEquals("bob was scheduled first", CharacterId("bob"), (order.first().payload as CharacterMoved).characterId)
        assertEquals("alice second", CharacterId("alice"), (order.last().payload as CharacterMoved).characterId)
        assertTrue(order.first().sequence < order.last().sequence)
    }

    @Test
    fun `an invalid queued event does not wedge the world`() {
        val queued = engine.scheduleAfter(
            instance,
            CharacterMoved(bob, from = square, to = LocationId("atlantis")),
            StoryDuration(10),
        ).asScheduled().instance
        val valid = engine.scheduleAfter(queued, CharacterMoved(bob, from = square, to = library), StoryDuration(20)).asScheduled().instance

        val drained = engine.advanceClock(valid, StoryDuration(60))
        assertFalse(drained.isClean)
        assertEquals(1, drained.rejected.size)
        assertEquals("the good event still applied", library, drained.instance.characters.getValue(bob).locationId)
    }

    @Test
    fun `processNextEvent stops cleanly on an empty queue`() {
        val result = engine.processNextEvent(instance)
        assertTrue(result is ProcessResult.Stopped)
        assertEquals(instance, (result as ProcessResult.Stopped).instance)
    }

    @Test
    fun `processNextEvent applies exactly one event`() {
        var state = engine.scheduleAfter(instance, CharacterMoved(bob, from = square, to = library), StoryDuration(0)).asScheduled().instance
        state = engine.scheduleAfter(state, CharacterMoved(alice, from = library, to = square), StoryDuration(0)).asScheduled().instance

        val first = engine.processNextEvent(state) as ProcessResult.Processed
        assertEquals(1, first.applications.size)
        assertEquals("one event must remain queued", 1, first.instance.eventQueue.size)
    }

    @Test
    fun `processEventsUntil to the current time is a no-op`() {
        val result = engine.processEventsUntil(instance, instance.worldClock.now)
        assertEquals(instance.worldClock.now, result.instance.worldClock.now)
        assertTrue(result.applied.isEmpty())
    }

    // ------------------------------------------------------------------
    // Relationship / knowledge / memory / thread events
    // ------------------------------------------------------------------

    @Test
    fun `relationship changes are applied and bounded`() {
        val changed = engine.applyImmediately(
            instance,
            RelationshipChanged(alice, bob, RelationshipDelta(trust = 20, familiarity = 5), RelationshipType.FRIEND, "confided"),
        ).asInstance()
        val rel = changed.relationships.getValue(dev.charaly.runtime.domain.RelationshipKey(alice, bob))
        assertEquals(65, rel.trust)
        assertEquals(45, rel.familiarity)
        assertEquals(RelationshipType.FRIEND, rel.relationshipType)
        assertEquals(1, rel.history.size)
        assertTrue(rel.history.first().reason.contains("confided"))
    }

    @Test
    fun `relationship values are clamped to zero one hundred`() {
        val changed = engine.applyImmediately(
            instance,
            RelationshipChanged(alice, bob, RelationshipDelta(affinity = 500)),
        ).asInstance()
        assertEquals(100, changed.relationships.getValue(dev.charaly.runtime.domain.RelationshipKey(alice, bob)).affinity)
    }

    @Test
    fun `self relationships and empty deltas are rejected`() {
        assertTrue(engine.applyImmediately(instance, RelationshipChanged(alice, alice, RelationshipDelta(1))) is EventApplication.Rejected)
        assertTrue(engine.applyImmediately(instance, RelationshipChanged(alice, bob, RelationshipDelta())) is EventApplication.Rejected)
    }

    @Test
    fun `knowledge discovery requires the fact to exist`() {
        val result = engine.applyImmediately(instance, KnowledgeDiscovered(alice, FactId("fact-nope")))
        assertTrue(result is EventApplication.Rejected)
        assertTrue((result as EventApplication.Rejected).validation.errors.contains(EventRejection.UNKNOWN_FACT))
    }

    @Test
    fun `discovering a known fact is a no-op`() {
        val result = engine.applyImmediately(instance, KnowledgeDiscovered(bob, FactId("fact-ledger")))
        assertTrue((result as EventApplication.Applied).noOp)
    }

    @Test
    fun `learning a fact updates both the store and the runtime record`() {
        val learned = engine.applyImmediately(instance, KnowledgeDiscovered(alice, FactId("fact-ledger"), via = "Bob told her")).asInstance()
        assertTrue(learned.knowledge.knows(alice, FactId("fact-ledger")))
        assertTrue(learned.characters.getValue(alice).knows(FactId("fact-ledger")))
        assertEquals("Bob told her", learned.knowledge.entry(alice, FactId("fact-ledger"))?.via)
    }

    @Test
    fun `memories are stored and indexed on the character`() {
        val memory = Memory(
            id = EntityId("mem-1"),
            characterId = alice,
            content = "Bob mentioned the lamps go out at the same hour.",
            importance = 4,
            createdAt = instance.worldClock.now,
            source = MemorySource.DIALOGUE,
        )
        val stored = engine.applyImmediately(instance, dev.charaly.runtime.domain.events.MemoryCreated(memory)).asInstance()
        assertEquals(1, stored.memories.size)
        assertEquals(listOf("mem-1"), stored.characters.getValue(alice).memoryIds.map { it.value })
    }

    @Test
    fun `a memory for an unknown character is rejected`() {
        val memory = Memory(EntityId("m"), CharacterId("ghost"), "boo")
        assertTrue(engine.applyImmediately(instance, dev.charaly.runtime.domain.events.MemoryCreated(memory)) is EventApplication.Rejected)
    }

    @Test
    fun `advancing a thread activates it implicitly`() {
        val advanced = engine.applyImmediately(
            instance,
            StoryThreadAdvanced(ThreadId("thread-lamps"), stage = 1, note = "third night in a row"),
        ).asInstance()
        val thread = advanced.storyThreads.getValue(ThreadId("thread-lamps"))
        assertEquals(1, thread.stage)
        assertEquals("progress implies the thread is active", StoryThreadStatus.ACTIVE, thread.status)
    }

    @Test
    fun `thread stage regression is rejected`() {
        val advanced = engine.applyImmediately(instance, StoryThreadAdvanced(ThreadId("thread-ledger"), stage = 3)).asInstance()
        val result = engine.applyImmediately(advanced, StoryThreadAdvanced(ThreadId("thread-ledger"), stage = 1))
        assertTrue(result is EventApplication.Rejected)
        assertTrue((result as EventApplication.Rejected).validation.errors.contains(EventRejection.STAGE_REGRESSION))
    }

    // ------------------------------------------------------------------
    // Scenes
    // ------------------------------------------------------------------

    @Test
    fun `a scene can only start where its participants actually are`() {
        val result = engine.applyImmediately(
            instance,
            SceneStarted(SceneId("scene-x"), library, listOf(alice, bob)),
        )
        assertTrue(result is EventApplication.Rejected)
        assertTrue((result as EventApplication.Rejected).validation.errors.contains(EventRejection.NOT_CO_LOCATED))
    }

    @Test
    fun `a valid scene starts and enrolls its participants`() {
        val started = engine.applyImmediately(
            instance,
            SceneStarted(SceneId("scene-1"), library, listOf(alice), objective = "closing up"),
        ).asInstance()
        val scene = started.worldState.activeScenes.getValue(SceneId("scene-1"))
        assertTrue(scene.isActive())
        assertTrue(started.characters.getValue(alice).isPresentIn(SceneId("scene-1")))
        assertEquals(SceneId("scene-1"), started.currentSceneId)
    }

    @Test
    fun `ending a scene clears it from the world`() {
        val started = engine.applyImmediately(instance, SceneStarted(SceneId("scene-1"), library, listOf(alice))).asInstance()
        val ended = engine.applyImmediately(started, SceneEnded(SceneId("scene-1"), "closing time")).asInstance()
        assertNull(ended.worldState.activeScenes[SceneId("scene-1")])
        assertNull(ended.currentSceneId)
        assertFalse(ended.characters.getValue(alice).isPresentIn(SceneId("scene-1")))
    }

    @Test
    fun `moving a character away drops them from the local scene`() {
        val started = engine.applyImmediately(instance, SceneStarted(SceneId("scene-1"), library, listOf(alice))).asInstance()
        val moved = engine.applyImmediately(started, CharacterMoved(alice, from = library, to = square)).asInstance()
        assertFalse(moved.characters.getValue(alice).isPresentIn(SceneId("scene-1")))
        assertFalse(moved.worldState.activeScenes.getValue(SceneId("scene-1")).participants.contains(alice))
    }

    @Test
    fun `a duplicate scene id is rejected`() {
        val started = engine.applyImmediately(instance, SceneStarted(SceneId("scene-1"), library, listOf(alice))).asInstance()
        val again = engine.applyImmediately(started, SceneStarted(SceneId("scene-1"), library, listOf(alice)))
        assertTrue(again is EventApplication.Rejected)
    }

    // ------------------------------------------------------------------
    // Variables and clock
    // ------------------------------------------------------------------

    @Test
    fun `world variables are set and keep their declared type`() {
        val updated = engine.applyImmediately(instance, WorldVariableSet("library_lamp", "off")).asInstance()
        val variable = updated.worldState.variables.getValue("library_lamp")
        assertEquals("off", variable.value)
        assertEquals(dev.charaly.runtime.domain.WorldVariableType.BOOLEAN, variable.type)
        assertEquals(false, variable.asBoolean())
    }

    @Test
    fun `activities are applied`() {
        val updated = engine.applyImmediately(
            instance,
            dev.charaly.runtime.domain.events.CharacterActivityChanged(alice, CharacterActivity.INVESTIGATING, mood = "focused"),
        ).asInstance()
        val runtime = updated.characters.getValue(alice)
        assertEquals(CharacterActivity.INVESTIGATING, runtime.activity)
        assertEquals("focused", runtime.mood)
    }

    @Test
    fun `clock advance fires due events and records the change`() {
        val queued = engine.scheduleAfter(
            instance,
            CharacterMoved(bob, from = square, to = library),
            StoryDuration(45),
        ).asScheduled().instance

        val drained = engine.advanceClock(queued, StoryDuration(60))
        assertEquals(StoryTime(1, 22, 0), drained.instance.worldClock.now)
        assertTrue(drained.applied.any { it.payload is CharacterMoved })
    }

    @Test
    fun `validation reports every error at once`() {
        val validation = engine.validateEvent(
            instance,
            dev.charaly.runtime.domain.events.ScheduledEvent(
                id = EntityId("evt-test"),
                scheduledAt = StoryTime.START,
                origin = EventOrigin.USER,
                payload = CharacterMoved(CharacterId("ghost"), from = null, to = LocationId("atlantis")),
                sequence = 0,
            ),
        )
        assertFalse(validation.isValid)
        assertTrue(validation.errors.contains(EventRejection.UNKNOWN_CHARACTER))
        assertTrue(validation.errors.contains(EventRejection.UNKNOWN_LOCATION))
        assertTrue(validation.describe().contains("unknown character"))
    }

    @Test
    fun `applied events are recorded in the audit log`() {
        val before = instance.worldState.eventLog
        val moved = engine.applyImmediately(instance, CharacterMoved(bob, from = square, to = library)).asInstance()
        assertEquals(before.size + 1, moved.worldState.eventLog.size)
        // A completed move is not an event "in force"; the location is the truth.
        assertFalse(moved.worldState.activeEvents.contains(moved.worldState.eventLog.last()))
    }

    @Test
    fun `a started scene stays in the active event list`() {
        val started = engine.applyImmediately(
            instance,
            dev.charaly.runtime.domain.events.SceneStarted(SceneId("scene-1"), library, listOf(alice)),
        ).asInstance()
        assertTrue(started.worldState.activeEvents.contains(started.worldState.eventLog.last()))
    }

    private fun EventApplication.asInstance(): dev.charaly.runtime.domain.StoryInstance {
        assertTrue("expected Applied, got $this", this is EventApplication.Applied)
        return (this as EventApplication.Applied).instance
    }

    private fun ScheduleResult.asScheduled(): ScheduleResult.Scheduled {
        assertTrue("expected Scheduled, got $this", this is ScheduleResult.Scheduled)
        return this as ScheduleResult.Scheduled
    }
}
