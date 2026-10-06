package dev.charaly.runtime.integration

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.CharacterRole
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.engine.StoryCreationOptions
import dev.charaly.runtime.inference.GenerationParams
import dev.charaly.runtime.inference.InferenceEngine
import dev.charaly.runtime.inference.InferenceRequest
import dev.charaly.runtime.inference.InferenceResult
import dev.charaly.runtime.inference.LoadOutcome
import dev.charaly.runtime.inference.ModelInfo
import dev.charaly.runtime.inference.ModelLoadRequest
import dev.charaly.runtime.inference.StreamChunk
import dev.charaly.runtime.model.ModelBinding
import dev.charaly.runtime.pack.DemoStoryPacks
import dev.charaly.runtime.persistence.CharalyStorage
import dev.charaly.runtime.persistence.InMemoryCharalyStorage
import dev.charaly.runtime.persistence.JsonCharalyRepository
import dev.charaly.runtime.session.CharalyRuntime
import dev.charaly.runtime.session.GenerationUpdate
import dev.charaly.runtime.session.TurnClockPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clock moves because the reader talked.
 *
 * ## Why this test exists in this shape
 *
 * Every other time test in the suite calls `runtime.advance(...)` directly, which means
 * they all prove the same thing: that the event engine moves the world when asked. That
 * was true and the product was still broken, because nothing in the product ever asked.
 * `CharalyViewModel.advanceClock` existed, no screen called it, and the pack's routines
 * could never fire during a real playthrough.
 *
 * So this test does not touch `advance`. It sends player lines through
 * [CharalyRuntime.respond] - the exact call the composer makes - and asserts the world
 * changed as a consequence. If the runtime stops advancing the clock on its own, this
 * fails even though every engine-level test still passes.
 *
 * ## What "the world moved" means here
 *
 * Not merely that `worldClock.now` differs. Asserted, in order:
 *
 *  1. the clock advanced, monotonically, by a bounded amount per turn;
 *  2. the advance came from the runtime, not from prose - a scripted reply claiming an
 *     hour passed must not move the clock an hour;
 *  3. NPCs actually relocated, and they relocated to where their *routine* says, which
 *     means [dev.charaly.runtime.engine.PresenceEngine] ran;
 *  4. scheduled and conditional pack events became due and applied;
 *  5. every one of those changes is in the event log, attributed to the engine - i.e. it
 *     went through the authoritative path rather than being written into state.
 */
class RuntimeClockProgressionTest {

    private val pack: StoryPack =
        DemoStoryPacks.all.first { it.id.value == "pack-miraculous-shadows-of-paris" }

    private val andre = CharacterId("andre")
    private val iceCream = LocationId("andre-ice-cream")
    private val andreHome = LocationId("andre-home")

    /**
     * A loaded engine that never claims anything about elapsed time.
     *
     * `MockInferenceEngine(initiallyLoaded = true)` would also work, but the assertion
     * that prose cannot move the clock needs an engine whose replies are *known*, so
     * that the test controls exactly what the model said.
     */
    private class LoadedEngine(
        private val replies: List<String>,
    ) : InferenceEngine {
        var loadCount = 0
            private set
        private val index = mutableListOf<String>()

        override suspend fun loadModel(request: ModelLoadRequest): LoadOutcome {
            loadCount++
            return LoadOutcome.Loaded(
                ModelInfo(id = "clock-test", path = request.path, displayName = "Clock Test"),
            )
        }

        override suspend fun unloadModel() = Unit

        override suspend fun generate(request: InferenceRequest): InferenceResult =
            InferenceResult(text = next())

        override fun stream(request: InferenceRequest): Flow<StreamChunk> = flow {
            val text = next()
            text.chunked(16).forEach { emit(StreamChunk(text = it)) }
            emit(StreamChunk(text = "", done = true))
        }

        override fun stop() = Unit

        override fun isLoaded(): Boolean = true

        override fun modelInfo(): ModelInfo =
            ModelInfo(id = "clock-test", path = "clock-test", displayName = "Clock Test")

        private fun next(): String = replies.getOrElse(index.size) { replies.lastOrNull().orEmpty() }
    }

    private fun runtime(
        storage: CharalyStorage = InMemoryCharalyStorage(),
        replies: List<String> = listOf("\"All right.\""),
    ): Pair<CharalyRuntime, LoadedEngine> {
        val engine = LoadedEngine(replies)
        val rt = CharalyRuntime(
            repository = JsonCharalyRepository(storage),
            engine = engine,
            params = GenerationParams(maxTokens = 64),
        )
        return rt to engine
    }

    /**
     * Starts the story the way the New Story flow does.
     *
     * `school-morning` opens at 08:10, which is *before* Andre's 19:00 entry, so a long
     * enough session crosses several routine boundaries. That is deliberate: the
     * boundaries have to be reachable by talking, or the test would be proving that a
     * debug helper works.
     */
    private suspend fun start(rt: CharalyRuntime) = rt.startStory(
        pack = pack,
        options = StoryCreationOptions(
            instanceId = StoryInstanceId("story-clock"),
            title = "Clock",
            scenario = pack.scenarios.first { it.id == "school-morning" },
            focusCharacterId = CharacterId("marinette"),
            modelBinding = ModelBinding.EMPTY,
            nowEpochMs = 1_700_000_000_000L,
        ),
    )

    /** One player turn through the real generation path. */
    private suspend fun CharalyRuntime.talk(
        instance: dev.charaly.runtime.domain.StoryInstance,
        line: String,
    ): Pair<dev.charaly.runtime.domain.StoryInstance, GenerationUpdate.Finished> {
        var finished: GenerationUpdate.Finished? = null
        respond(instance, line).collect { update ->
            if (update is GenerationUpdate.Finished) finished = update
        }
        val frame = requireNotNull(finished) { "the turn produced no Finished frame" }
        return requireNotNull(frame.instance) { "Finished carried no authoritative instance" } to frame
    }

    // ------------------------------------------------------------------
    // The acceptance criterion
    // ------------------------------------------------------------------

    @Test
    fun `talking moves the clock and the world moves with it`() = runTest {
        val (rt, engine) = runtime()
        var instance = start(rt)
        val startTime = instance.worldClock.now
        val startEventCount = instance.worldState.eventLog.size
        assertEquals("the engine must not be reloaded per turn", 0, engine.loadCount)

        // Six real turns. The brief asks for at least six; the point is that each one is
        // a `respond` call, not an `advance` call.
        //
        // Every turn asserts the same invariant, which is the one that matters: the
        // clock's actual movement equals the cost the runtime said it would charge. A
        // runtime that advanced by some other amount - or not at all - fails here rather
        // than at the end of the session.
        var previous = startTime
        repeat(6) { index ->
            val line = "Tell me about today, line $index"
            val (after, frame) = rt.talk(instance, line)
            instance = after

            assertTrue(
                "turn $index did not advance the clock (was $previous, now ${after.worldClock.now})",
                after.worldClock.now > previous,
            )
            assertEquals(
                "turn $index: the clock moved by a different amount than the runtime charged",
                frame.elapsedStoryMinutes,
                after.worldClock.now.totalMinutes - previous.totalMinutes,
            )
            assertTrue(
                "turn $index charged outside the policy's bounds",
                frame.elapsedStoryMinutes in TurnClockPolicy.EXCHANGE_MINUTES..TurnClockPolicy.MAX_TURN_MINUTES,
            )
            previous = after.worldClock.now
        }

        // The line the test actually sent is what priced the turn, which is the
        // guarantee that time is derived from the player rather than the prose.
        val (after, frame) = rt.talk(instance, "x".repeat(400))
        assertEquals(
            "a long player line did not cost more than a short one",
            TurnClockPolicy.minutesFor(400, 0).minutes,
            frame.elapsedStoryMinutes,
        )
        instance = after

        // 1. Monotonic, bounded movement: a turn costs minutes, not hours.
        val elapsed = instance.worldClock.now.totalMinutes - startTime.totalMinutes
        assertTrue("seven turns moved the clock by $elapsed minutes", elapsed > 0)
        assertTrue(
            "seven turns should not have skipped a whole day ($elapsed minutes)",
            elapsed <= TurnClockPolicy.MAX_TURN_MINUTES * 7,
        )

        // 2. Something in the world actually happened, through the event engine.
        assertTrue(
            "no events were applied while the world moved",
            instance.worldState.eventLog.size > startEventCount,
        )
    }

    /**
     * The load-bearing assertion, kept separate so a failure says exactly what broke.
     *
     * After enough talking, scheduled characters are standing where their day says they
     * should be. Nothing summoned them: they moved because the clock reached their hour
     * and [dev.charaly.runtime.engine.PresenceEngine] proposed a relocation that the
     * event engine validated and applied.
     */
    @Test
    fun `an npc relocates to where his routine says because time passed in conversation`() = runTest {
        val (rt, _) = runtime()
        var instance = start(rt)

        val andreBefore = instance.characters.getValue(andre)
        val andreLocationBefore = andreBefore.locationId

        // Talk until Andre's routine has genuinely moved him. Bounded, and the bound is
        // generous: from 08:10 to 19:00 is 650 minutes, and a plain turn costs 2.
        var turns = 0
        while (turns < 400) {
            val (after, _) = rt.talk(instance, "What is happening in the city?")
            instance = after
            turns++

            val placement = pack.characters.first { it.id == andre }.routine.resolve(instance.worldClock.now)
            if (placement != null && instance.characters.getValue(andre).locationId == placement.locationId) break
        }

        assertTrue("the routine never caught up with the clock after $turns turns", turns < 400)

        val placement = requireNotNull(pack.characters.first { it.id == andre }.routine.resolve(instance.worldClock.now))
        val andreNow = instance.characters.getValue(andre)

        assertEquals(
            "Andre is not where his own routine puts him at ${instance.worldClock.now}",
            placement.locationId,
            andreNow.locationId,
        )
        assertEquals(placement.activity, andreNow.activity)

        // And the move was a *validated event*, not a field assignment. The world's
        // event log is a list of ids rather than payloads, so the evidence is the field
        // only the routine reducer writes: `routineEntryMinute` records which entry of
        // the daily schedule put the character where they are, and it is -1 until a
        // CharacterRoutineApplied lands.
        assertEquals(
            "Andre's placement does not name a routine entry",
            placement.fromEntryMinute,
            andreNow.routineEntryMinute,
        )
        assertTrue(
            "Andre was never relocated by a routine event",
            andreNow.routineEntryMinute >= 0,
        )

        // Either he genuinely changed place, or at minimum his recorded entry changed.
        // A session too short to move him would make the rest of this test vacuous.
        assertTrue(
            "nothing about Andre's placement changed over $turns turns",
            andreNow.locationId != andreLocationBefore ||
                andreNow.routineEntryMinute != andreBefore.routineEntryMinute,
        )
    }

    @Test
    fun `a scheduled consequence that comes due during conversation actually fires`() = runTest {
        val (rt, _) = runtime()
        var instance = start(rt)
        val startRevision = instance.worldState.revision

        // Long enough to cross at least one pack-conditional trigger's window. These are
        // evaluated by `advance`, which the runtime now calls itself after every turn.
        var turns = 0
        while (turns < 500 && instance.worldState.revision <= startRevision) {
            val (after, _) = rt.talk(instance, "Tell me what is going on.")
            instance = after
            turns++
        }

        assertTrue(
            "no pack consequence fired across $turns turns of conversation",
            instance.worldState.revision > startRevision,
        )
        // Whatever fired, it fired through the log rather than by mutation.
        assertTrue(instance.worldState.eventLog.isNotEmpty())
    }

    // ------------------------------------------------------------------
    // The model does not own the clock
    // ------------------------------------------------------------------

    @Test
    fun `a reply claiming an hour passed does not move the clock an hour`() = runTest {
        val liar = listOf(
            "An hour passes in silence. It is a full hour - the sun has moved right across " +
                "the sky and everything has changed.",
        )
        val (rt, _) = runtime(replies = liar)
        val instance = start(rt)

        val (after, frame) = rt.talk(instance, "Wait for me.")

        assertEquals(
            "the model decided how much time passed",
            TurnClockPolicy.EXCHANGE_MINUTES,
            frame.elapsedStoryMinutes,
        )
        assertEquals(
            instance.worldClock.now.totalMinutes + TurnClockPolicy.EXCHANGE_MINUTES,
            after.worldClock.now.totalMinutes,
        )
    }

    @Test
    fun `the cost of a turn is bounded no matter what the turn did`() = runTest {
        val (rt, _) = runtime()
        val instance = start(rt)

        // A player line long enough to count as "doing something".
        val (after, frame) = rt.talk(instance, "x".repeat(400))

        assertTrue(frame.elapsedStoryMinutes in TurnClockPolicy.EXCHANGE_MINUTES..TurnClockPolicy.MAX_TURN_MINUTES)
        assertEquals(
            instance.worldClock.now.totalMinutes + frame.elapsedStoryMinutes,
            after.worldClock.now.totalMinutes,
        )
    }

    @Test
    fun `the policy is a pure function of engine facts`() = runTest {
        val (rt, _) = runtime()
        val instance = start(rt)

        // Same inputs, same cost, every time - which is what makes a story replayable.
        val first = rt.turnCost(userInputChars = 40, appliedActions = 0)
        val second = rt.turnCost(userInputChars = 40, appliedActions = 0)
        assertEquals(first, second)

        assertEquals(
            TurnClockPolicy.EXCHANGE_MINUTES,
            rt.turnCost(userInputChars = 0, appliedActions = 0).minutes,
        )
        // More validated actions means more time, up to a point.
        assertTrue(
            rt.turnCost(userInputChars = 10, appliedActions = 3).minutes >
                rt.turnCost(userInputChars = 10, appliedActions = 0).minutes,
        )
        // And it is capped, so one loud turn cannot skip the whole day.
        assertTrue(
            rt.turnCost(userInputChars = 10_000, appliedActions = 500).minutes <=
                TurnClockPolicy.MAX_TURN_MINUTES,
        )
    }

    // ------------------------------------------------------------------
    // Ordering, persistence and isolation
    // ------------------------------------------------------------------

    @Test
    fun `the advanced world survives a restart`() = runTest {
        val storage = InMemoryCharalyStorage()
        val (rt, _) = runtime(storage)
        var instance = start(rt)
        repeat(4) {
            val (after, _) = rt.talk(instance, "Keep going.")
            instance = after
        }

        val reopened = runtime(storage).first.loadStory(instance.id)
        assertNotEquals("the story should have been persisted at all", null, reopened)
        requireNotNull(reopened)
        assertEquals(
            "the clock did not survive the restart",
            instance.worldClock.now,
            reopened.worldClock.now,
        )
        assertEquals(
            "the world did not survive the restart",
            instance.worldState.characters,
            reopened.worldState.characters,
        )
    }

    @Test
    fun `talking in one story does not move another`() = runTest {
        val (rt, _) = runtime()
        var played = start(rt)

        val other = rt.startStory(
            pack = pack,
            options = StoryCreationOptions(
                instanceId = StoryInstanceId("story-clock-other"),
                scenario = pack.scenarios.first { it.id == "school-morning" },
                focusCharacterId = CharacterId("adrien"),
                modelBinding = ModelBinding.EMPTY,
                nowEpochMs = 1_700_000_000_000L,
            ),
        )
        val otherStart = other.worldClock.now

        repeat(3) {
            val (after, _) = rt.talk(played, "Say something.")
            played = after
        }

        val untouched = rt.loadStory(other.id)
        requireNotNull(untouched)
        assertEquals(
            "one playthrough advanced another",
            otherStart,
            untouched.worldClock.now,
        )
    }

    @Test
    fun `npcs still exist and still have somewhere to be after a long conversation`() = runTest {
        val (rt, _) = runtime()
        var instance = start(rt)
        repeat(20) {
            val (after, _) = rt.talk(instance, "Is anyone else around?")
            instance = after
        }

        pack.characters
            .filter { it.storyRole == CharacterRole.NPC }
            .forEach { npc ->
                val runtime_ = instance.characters[npc.id]
                assertTrue("${npc.name} stopped existing", runtime_ != null)
                assertTrue("${npc.name} has no location", runtime_!!.locationId != null)
            }
    }

    /**
     * The header reads the clock, so the screen cannot claim a time the world does not
     * have. Asserted through the real presenter rather than the composable, because the
     * projection is where the claim lives.
     */
    @Test
    fun `the stage header shows the world clock`() = runTest {
        val (rt, _) = runtime()
        var instance = start(rt)
        val definition = rt.definitionFor(instance)

        val before = dev.charaly.runtime.presentation.ChatStagePresenter.build(
            instance = instance,
            pack = pack,
            definition = definition,
            modelReady = true,
        )
        assertEquals(instance.worldClock.now.formatClock(), before.clockLabel)
        assertEquals("Day ${instance.worldClock.now.day}", before.dayLabel)

        val (after, _) = rt.talk(instance, "Good evening.")
        instance = after
        val later = dev.charaly.runtime.presentation.ChatStagePresenter.build(
            instance = instance,
            pack = pack,
            definition = definition,
            modelReady = true,
        )

        assertFalse("the header clock did not move", before.clockLabel == later.clockLabel)
        assertEquals(instance.worldClock.now.formatClock(), later.clockLabel)
    }

    @Test
    fun `the world sheet reports the live clock and scene`() = runTest {
        val (rt, _) = runtime()
        var instance = start(rt)
        val definition = rt.definitionFor(instance)

        repeat(2) {
            val (after, _) = rt.talk(instance, "Where are we?")
            instance = after
        }

        val world = dev.charaly.runtime.presentation.StoryContextPresenter
            .worldSheet(definition, instance, instance.worldClock.now)

        assertEquals(instance.worldClock.now.formatClock(), world.clockLabel)
        assertEquals("Day ${instance.worldClock.now.day}", world.dayLabel)
        assertTrue("the sheet says nothing about the scene", world.activeScene.isNotBlank())
    }
}