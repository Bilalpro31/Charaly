package dev.charaly.runtime.integration

import dev.charaly.runtime.context.ContextBuilder
import dev.charaly.runtime.director.ProposedActionParser
import dev.charaly.runtime.director.SceneDirector
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.StoryDuration
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.Scene
import dev.charaly.runtime.domain.WorldDefinition
import dev.charaly.runtime.domain.events.CharacterMoved
import dev.charaly.runtime.domain.events.EventOrigin
import dev.charaly.runtime.engine.EventEngine
import dev.charaly.runtime.engine.ScheduleResult
import dev.charaly.runtime.engine.SampleWorlds
import dev.charaly.runtime.engine.StoryInstanceFactory
import dev.charaly.runtime.inference.InferenceEngine
import dev.charaly.runtime.inference.MockInferenceEngine
import dev.charaly.runtime.inference.RecordingInferenceEngine
import dev.charaly.runtime.persistence.CharalyStorage
import dev.charaly.runtime.persistence.InMemoryCharalyStorage
import dev.charaly.runtime.persistence.JsonCharalyRepository
import dev.charaly.runtime.session.CharalyError
import dev.charaly.runtime.session.CharalyRuntime
import dev.charaly.runtime.session.GenerationUpdate
import dev.charaly.runtime.session.WorldDefinitionResolver
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The integration proof.
 *
 * This suite walks the entire pipeline exactly as the app does:
 *
 * ```
 * StoryPack
 *   -> StoryInstance
 *   -> schedule an event
 *   -> advance the world clock
 *   -> process the event
 *   -> verify world state
 *   -> build a scene
 *   -> build character-specific inference context
 *   -> mock InferenceEngine
 *   -> receive the streamed response
 *   -> persist and restore
 * ```
 *
 * It runs on a plain JVM: no device, no emulator, no server, no model file.
 */
class StoryPipelineIntegrationTest {

    private val pack = dev.charaly.runtime.engine.SampleWorlds.libraryPack()
    private val alice = CharacterId("alice")
    private val bob = CharacterId("bob")
    private val library = CharacterId("alice").let { CharacterId("library") }
    private val square = CharacterId("square")

    // ------------------------------------------------------------------
    // The full pipeline, wired by hand
    // ------------------------------------------------------------------

    @Test
    fun `pack to streamed response, layer by layer`() = runTest {
        // 1. StoryPack -> StoryInstance
        var instance: StoryInstance = StoryInstanceFactory.create(pack, StoryInstanceId("s1"))
        assertEquals(2, instance.characters.size)
        assertEquals(library, instance.characters.getValue(alice).locationId)
        assertEquals(square, instance.characters.getValue(bob).locationId)

        val definition = WorldDefinition(pack.characters, pack.locations)
        val engine = EventEngine(definition)

        // 2. Schedule a deterministic event: Bob follows Alice into the library
        //    after 30 story minutes.
        instance = engine.scheduleAfter(
            instance = instance,
            payload = CharacterMoved(bob, from = square, to = library),
            by = StoryDuration(30),
            origin = EventOrigin.ENGINE,
            note = "Bob follows the light",
        ).scheduled()
        assertEquals(1, instance.eventQueue.size)
        assertEquals("nothing has happened yet", square, instance.characters.getValue(bob).locationId)

        // 3. Advance the world clock -> 4. process the due event.
        val batch = engine.advanceClock(instance, StoryDuration(30))
        instance = batch.instance
        assertTrue(batch.isClean)
        assertTrue(batch.applied.any { it.payload is CharacterMoved })

        // 5. Verify the authoritative world state.
        assertEquals(library, instance.characters.getValue(bob).locationId)
        assertEquals(21, instance.worldClock.now.hour)
        assertEquals(30, instance.worldClock.now.minute)
        assertTrue(instance.eventQueue.isEmpty())

        // 6. Build a scene deterministically. Alice's scene now includes Bob,
        //    because he is physically present.
        val scene: Scene = SceneDirector(definition).directScene(instance, alice)!!
        assertEquals(library, scene.locationId)
        assertEquals(setOf(alice, bob), scene.participantSet())

        // 7. Build character-specific inference context.
        val context = ContextBuilder(definition).buildContext(
            instance = instance,
            scene = scene,
            characterId = alice,
            userInput = "Have you seen anyone in here?",
        )
        assertEquals("Alice", context.character.name)
        assertFalse(
            "world truth must not leak into Alice's prompt",
            context.systemPrompt().contains("locked inside the cabinet"),
        )
        assertTrue(
            "the request must carry the user input",
            context.userInput == "Have you seen anyone in here?",
        )

        // 8. Stream through a mock engine. 9. Receive the response.
        val recorder = RecordingInferenceEngine()
        recorder.loadModel(dev.charaly.runtime.inference.ModelLoadRequest(path = "/tmp/mock.gguf"))
        val request = ContextBuilder(definition).buildRequest(context)
        val chunks = recorder.stream(request).toList()

        val reply = chunks.filterNot { it.done }.joinToString("") { it.text }
        assertTrue("tokens must actually stream", chunks.count { !it.done } > 1)
        assertTrue(reply.contains("Have you seen anyone in here?"))
        assertEquals("the request reached the engine", 1, recorder.streamCount)
    }

    @Test
    fun `a buildRequest carries the character specific context`() = runTest {
        val instance = StoryInstanceFactory.create(pack, StoryInstanceId("s1"))
        val definition = WorldDefinition(pack.characters, pack.locations)
        val scene = SceneDirector(definition).directScene(instance, alice)!!
        val request = ContextBuilder(definition).buildRequest(
            instance = instance,
            scene = scene,
            characterId = alice,
            userInput = "Have you seen anyone in here?",
        )
        assertEquals(alice, request.speakerId)
        assertEquals("Have you seen anyone in here?", request.messages.last().content)
        assertFalse(
            "the raw prompt must not leak world truth to Alice",
            request.systemPrompt.contains("locked inside the cabinet"),
        )
    }

    // ------------------------------------------------------------------
    // The same pipeline through the production facade
    // ------------------------------------------------------------------

    @Test
    fun `the CharalyRuntime facade drives a full turn and persists it`() = runTest {
        val storage = InMemoryCharalyStorage()
        val repository = JsonCharalyRepository(storage)
        val engine = loadedEngine { "Alice looks up from the ledger. \"Nobody. And that is the problem.\"" }
        val runtime = CharalyRuntime(repository, engine)

        runtime.createPack(pack)
        val instance = runtime.startStory(pack, StoryInstanceId("s1"))

        val updates = runtime.respond(instance, "Who else has been in here?", alice).toList()

        val finished = updates.filterIsInstance<GenerationUpdate.Finished>().single()
        assertTrue(finished.text.contains("Nobody"))

        // Streaming really happened.
        val streamed = updates.filterIsInstance<GenerationUpdate.Chunk>()
        assertTrue("the UI must receive partial output", streamed.size > 1)
        assertEquals("chunks must accumulate", finished.text, streamed.last().accumulated)

        // The scene was committed as an event, not just derived.
        val persisted = repository.getInstance(StoryInstanceId("s1"))!!
        assertNotNull(persisted.currentSceneId)
        assertTrue(persisted.conversation.size >= 2)
        assertEquals("the user line is saved", "Who else has been in here?", persisted.conversation.entries.first().text)
        assertEquals("the reply is saved", finished.text, persisted.conversation.entries.last().text)
    }

    @Test
    fun `the runtime restores a story after a restart and continues it`() = runTest {
        val storage = InMemoryCharalyStorage()
        val first = CharalyRuntime(JsonCharalyRepository(storage), loadedEngine())
        first.createPack(pack)
        val started = first.startStory(pack, StoryInstanceId("s1"))
        // GenerationUpdate.Finished carries the authoritative post-turn snapshot.
        val afterTurn = first.respond(started, "Hello.", alice).toList()
            .filterIsInstance<GenerationUpdate.Finished>()
            .single()
            .instance!!
        first.advance(afterTurn, StoryDuration.hours(2))

        // "Close the app."
        // "Open it again."
        val reopened = CharalyRuntime(JsonCharalyRepository(storage), loadedEngine())
        val restored = reopened.restore()
        assertEquals(1, restored.packs.size)
        assertEquals(1, restored.instances.size)
        assertTrue(restored.failures.isEmpty())

        val continued = restored.instances.single()
        assertEquals("the clock survived the restart", 23, continued.worldClock.now.hour)
        assertEquals("the transcript survived", 2, continued.conversation.size)

        val more = reopened.respond(continued, "And now?", alice).toList()
        assertTrue(more.any { it is GenerationUpdate.Finished })
        assertEquals(
            "two more lines after the restart",
            4,
            reopened.loadStory(StoryInstanceId("s1"))!!.conversation.size,
        )
    }

    @Test
    fun `the runtime resolves static definitions from the pack`() = runTest {
        val repository = JsonCharalyRepository(InMemoryCharalyStorage())
        val runtime = CharalyRuntime(repository, loadedEngine())
        runtime.createPack(pack)
        val instance = runtime.startStory(pack, StoryInstanceId("s1"))

        val definition = runtime.definitionFor(instance)
        assertEquals("Alice", definition.character(alice)?.name)
        assertTrue(
            "personality must come from the pack, not be lost",
            definition.character(alice)?.personality?.contains("dry") == true,
        )
    }

    // ------------------------------------------------------------------
    // The LLM action boundary
    // ------------------------------------------------------------------

    @Test
    fun `plain narration never changes the world`() = runTest {
        val storage = InMemoryCharalyStorage()
        val repository = JsonCharalyRepository(storage)
        // The model says Alice walks somewhere. That is text, not an event.
        val engine = loadedEngine { "Alice walks into the library and opens the locked cabinet." }
        val runtime = CharalyRuntime(repository, engine)
        runtime.createPack(pack)
        val instance = runtime.startStory(pack, StoryInstanceId("s1"))

        val updates = runtime.respond(instance, "Where did she go?", alice).toList()

        val persisted = repository.getInstance(StoryInstanceId("s1"))!!
        assertEquals(
            "narration must not teleport anybody",
            library,
            persisted.characters.getValue(alice).locationId,
        )
        assertTrue(
            "no action proposals are emitted from prose",
            updates.none { it is GenerationUpdate.AppliedAction },
        )
        assertTrue(
            "the narration is still saved as text",
            persisted.conversation.entries.last().text.contains("locked cabinet"),
        )
    }

    @Test
    fun `an explicit machine readable action does change the world`() = runTest {
        val storage = InMemoryCharalyStorage()
        val repository = JsonCharalyRepository(storage)
        val engine = loadedEngine {
            "Fine. I'll follow you.\n" +
                "<charaly:action type=\"move\" character=\"bob\" target=\"The Library\" reason=\"follows the light\"/>"
        }
        val runtime = CharalyRuntime(repository, engine)
        runtime.createPack(pack)
        val instance = runtime.startStory(pack, StoryInstanceId("s1"))
        assertEquals(square, instance.characters.getValue(bob).locationId)

        val updates = runtime.respond(instance, "Show me.", bob).toList()

        val applied = updates.filterIsInstance<GenerationUpdate.AppliedAction>().single()
        assertEquals("bob", applied.review.action.characterId)
        assertTrue(applied.changes.isNotEmpty())

        // The proposal was validated and applied through the event engine.
        val persisted = repository.getInstance(StoryInstanceId("s1"))!!
        assertEquals(library, persisted.characters.getValue(bob).locationId)
        assertTrue(persisted.worldState.eventLog.isNotEmpty())
    }

    @Test
    fun `an invalid proposed action is rejected and changes nothing`() = runTest {
        val storage = InMemoryCharalyStorage()
        val repository = JsonCharalyRepository(storage)
        val engine = loadedEngine {
            "<charaly:action type=\"move\" character=\"alice\" target=\"The Moon\"/> I float away."
        }
        val runtime = CharalyRuntime(repository, engine)
        runtime.createPack(pack)
        val instance = runtime.startStory(pack, StoryInstanceId("s1"))

        val updates = runtime.respond(instance, "Go on then.", alice).toList()

        assertTrue(updates.any { it is GenerationUpdate.RejectedAction })
        val persisted = repository.getInstance(StoryInstanceId("s1"))!!
        assertEquals(library, persisted.characters.getValue(alice).locationId)
    }

    @Test
    fun `only explicit tags are parsed as actions`() {
        // Prose that merely looks action-ish must produce zero proposals.
        assertEquals(0, ProposedActionParser.parse("Alice moves to the library.").size)
        assertEquals(0, ProposedActionParser.parse("I will go to the square.").size)
        assertEquals(0, ProposedActionParser.parse("").size)

        val parsed = ProposedActionParser.parse(
            "Sure.\n<charaly:action type=\"move\" character=\"bob\" target=\"library\" reason=\"follows\"/>",
        )
        assertEquals(1, parsed.size)
        assertEquals("bob", parsed.single().characterId)
        assertEquals("library", parsed.single().target)
    }

    // ------------------------------------------------------------------
    // Generation control
    // ------------------------------------------------------------------

    @Test
    fun `generation without a model fails cleanly instead of guessing`() = runTest {
        // No model loaded: the runtime must refuse rather than invent a reply.
        val unloaded = MockInferenceEngine()
        val runtime = CharalyRuntime(JsonCharalyRepository(InMemoryCharalyStorage()), unloaded)
        runtime.createPack(pack)
        val instance = runtime.startStory(pack, StoryInstanceId("s1"))
        val updates = runtime.respond(instance, "Hello?", alice).toList()
        val failure = updates.filterIsInstance<GenerationUpdate.Failed>().single()
        assertTrue(failure.error is CharalyError.ModelNotLoaded)
    }

    @Test
    fun `stop cancels an in flight generation`() = runTest {
        // Slow enough that the stream is still producing tokens when stop() lands, which is the
        // only situation a stop is *for*. A stop with nothing in flight is a no-op - the same as
        // the native engine, and the reason `respond` can call it after every completed turn.
        val engine = MockInferenceEngine(chunkSize = 1, delayMillis = 50)
        val runtime = CharalyRuntime(JsonCharalyRepository(InMemoryCharalyStorage()), engine)
        runtime.createPack(pack)
        runtime.startStory(pack, StoryInstanceId("s1"))
        engine.loadModel(dev.charaly.runtime.inference.ModelLoadRequest(path = "mock"))

        var failure: Throwable? = null
        val collecting = launch {
            try {
                engine.stream(
                    dev.charaly.runtime.inference.InferenceRequest(
                        systemPrompt = "",
                        messages = emptyList(),
                    ),
                ).collect { }
            } catch (error: Throwable) {
                failure = error
            }
        }

        // Let the first token through, so the engine is genuinely mid-generation.
        runCurrent()
        runtime.stop()
        collecting.join()

        assertTrue(
            "expected a cancellation, got $failure",
            failure is dev.charaly.runtime.inference.InferenceError.Cancelled,
        )
    }

    /**
     * A stop between turns is not a cancellation.
     *
     * `respond` calls `stop()` in a `finally` after every completed turn, so an engine that
     * latched a cancel regardless of state would fail the *second* turn of every conversation
     * with a cancellation nobody asked for. Pinned here because it is invisible until someone
     * plays a conversation longer than one turn.
     */
    @Test
    fun `a stop after a completed turn does not cancel the next one`() = runTest {
        // Loaded, as the UI would have it: `respond` refuses to generate otherwise, and a refusal
        // is a `Failed` frame rather than a `Finished` one - which is what made the first
        // version of this test fail with no explanation.
        val engine = loadedEngine()
        val runtime = CharalyRuntime(JsonCharalyRepository(InMemoryCharalyStorage()), engine)
        runtime.createPack(pack)
        var instance = runtime.startStory(pack, StoryInstanceId("s1"))

        var turns = 0
        repeat(3) {
            var last: GenerationUpdate.Finished? = null
            runtime.respond(instance, "hello", alice).collect { update ->
                if (update is GenerationUpdate.Finished) last = update
            }
            val finished = requireNotNull(last) { "turn $it produced no Finished frame" }
            instance = requireNotNull(finished.instance) { "turn $it carried no snapshot" }
            turns++
        }
        assertEquals(3, turns)
        // And the story really did grow, so this is not three no-op turns that "passed".
        assertTrue(instance.conversation.entries.size >= 6)
    }

    @Test
    fun `the deterministic clock advances and fires queued events`() = runTest {
        val storage = InMemoryCharalyStorage()
        val repository = JsonCharalyRepository(storage)
        val runtime = CharalyRuntime(repository, MockInferenceEngine())
        runtime.createPack(pack)
        var instance = runtime.startStory(pack, StoryInstanceId("s1"))

        // Queue Bob's arrival 20 minutes out.
        instance = EventEngine(WorldDefinition(pack.characters, pack.locations))
            .scheduleAfter(
                instance,
                CharacterMoved(bob, from = square, to = library),
                StoryDuration(20),
                EventOrigin.ENGINE,
            )
            .scheduled()
        assertEquals(1, instance.eventQueue.size)

        // Ten minutes: not yet.
        instance = runtime.advance(instance, StoryDuration(10))
        assertEquals(square, instance.characters.getValue(bob).locationId)
        assertEquals(1, instance.eventQueue.size)

        // Ten more: due.
        instance = runtime.advance(instance, StoryDuration(10))
        assertEquals(library, instance.characters.getValue(bob).locationId)
        assertTrue(instance.eventQueue.isEmpty())
    }

    @Test
    fun `the whole world is a pure function of its event sequence`() = runTest {
        suspend fun runOnce(): StoryInstance {
            val repository = JsonCharalyRepository(InMemoryCharalyStorage())
            val runtime = CharalyRuntime(repository, MockInferenceEngine())
            var instance = runtime.startStory(pack, StoryInstanceId("run"))
            val engine = EventEngine(WorldDefinition(pack.characters, pack.locations))
            instance = engine.scheduleAfter(
                instance,
                CharacterMoved(bob, from = square, to = library),
                StoryDuration(15),
            ).scheduled()
            return engine.advanceClock(instance, StoryDuration(60)).instance
        }

        val a = runOnce()
        val b = runOnce()
        assertEquals(a.worldState, b.worldState)
        assertEquals(a.knowledge, b.knowledge)
        assertEquals(a.eventQueue, b.eventQueue)
    }

    @Test
    fun `a model that fails mid generation does not corrupt the story`() = runTest {
        val repository = JsonCharalyRepository(InMemoryCharalyStorage())
        val runtime = CharalyRuntime(repository, ExplodingEngine())
        runtime.createPack(pack)
        val instance = runtime.startStory(pack, StoryInstanceId("s1"))

        val updates = runtime.respond(instance, "Hello?", alice).toList()
        assertTrue(updates.any { it is GenerationUpdate.Failed })

        // The world is intact and reloadable.
        val reloaded = repository.getInstance(StoryInstanceId("s1"))
        assertNotNull(reloaded)
        assertEquals(2, reloaded!!.characters.size)
        assertEquals(library, reloaded.characters.getValue(alice).locationId)
    }

    /** An engine that reports being loaded but throws on generation. */
    private class ExplodingEngine : InferenceEngine {
        override suspend fun loadModel(request: dev.charaly.runtime.inference.ModelLoadRequest) =
            dev.charaly.runtime.inference.LoadOutcome.Loaded(
                dev.charaly.runtime.inference.ModelInfo("boom", request.path, "Boom"),
            )

        override suspend fun unloadModel() = Unit

        override suspend fun generate(request: dev.charaly.runtime.inference.InferenceRequest) =
            throw dev.charaly.runtime.inference.InferenceError.GenerationFailed("simulated native crash")

        override fun stream(request: dev.charaly.runtime.inference.InferenceRequest) =
            kotlinx.coroutines.flow.flow<dev.charaly.runtime.inference.StreamChunk> {
                throw dev.charaly.runtime.inference.InferenceError.GenerationFailed("simulated native crash")
            }

        override fun stop() = Unit

        override fun isLoaded(): Boolean = true

        override fun modelInfo() = dev.charaly.runtime.inference.ModelInfo("boom", "boom", "Boom")
    }

    @Test
    fun `the runtime can resolve definitions without the pack present`() = runTest {
        val storage: CharalyStorage = InMemoryCharalyStorage()
        val repository = JsonCharalyRepository(storage)
        val runtime = CharalyRuntime(repository, loadedEngine())
        runtime.createPack(pack)
        val instance = runtime.startStory(pack, StoryInstanceId("s1"))

        // Simulate the pack being deleted (e.g. a corrupt import).
        val orphan = instance.copy(storyPackId = dev.charaly.runtime.domain.StoryPackId("missing"))
        val resolver = WorldDefinitionResolver(repository)
        val definition = resolver.forInstance(orphan)

        assertEquals("identity degrades but data survives", 2, definition.characters.size)
        assertEquals(2, definition.locations.size)
    }

    @Test
    fun `a scene started by the director is committed through the event engine`() = runTest {
        val repository = JsonCharalyRepository(InMemoryCharalyStorage())
        val runtime = CharalyRuntime(repository, loadedEngine())
        runtime.createPack(pack)
        val instance = runtime.startStory(pack, StoryInstanceId("s1"))

        val derived = SceneDirector(WorldDefinition(pack.characters, pack.locations))
            .directScene(instance, alice)!!
        assertFalse(instance.worldState.activeScenes.containsKey(derived.id))

        val updates = runtime.respond(instance, "Hello?", alice).toList()
        val started = updates.filterIsInstance<GenerationUpdate.Started>().single()
        val persisted = repository.getInstance(StoryInstanceId("s1"))!!

        assertEquals(started.scene.id, persisted.currentSceneId)
        assertTrue(persisted.worldState.activeScenes.containsKey(started.scene.id))
        assertTrue(
            "the character must be enrolled in the scene",
            persisted.characters.getValue(alice).isPresentIn(started.scene.id),
        )
    }

    @Test
    fun `every applied event leaves a trace in the world`() = runTest {
        val repository = JsonCharalyRepository(InMemoryCharalyStorage())
        val runtime = CharalyRuntime(repository, MockInferenceEngine())
        runtime.createPack(pack)
        var instance = runtime.startStory(pack, StoryInstanceId("s1"))
        val before = instance.worldState.eventLog.size

        instance = runtime.advance(instance, StoryDuration(45))
        assertTrue(
            "advancing the clock must be auditable",
            instance.worldState.eventLog.size > before,
        )
        assertTrue(instance.worldState.eventLog.size > 0)
        assertEquals(
            "the runtime must not be the only way to read history",
            instance.worldState.describe().contains("World @"),
            true,
        )
    }

    /** A mock engine with a model already loaded, as the UI would have. */
    private fun loadedEngine(
        responder: (dev.charaly.runtime.inference.InferenceRequest) -> String = { "[mock] ${it.messages.lastOrNull()?.content.orEmpty()}" },
    ): MockInferenceEngine = MockInferenceEngine(
        responder = responder,
        info = dev.charaly.runtime.inference.ModelInfo("mock", "/tmp/mock.gguf", "Mock"),
        initiallyLoaded = true,
    )

    private fun ScheduleResult.scheduled(): StoryInstance {
        assertTrue("expected a scheduled event, got $this", this is ScheduleResult.Scheduled)
        return (this as ScheduleResult.Scheduled).instance
    }

    @Test
    fun `character knowledge stays separated across a full turn`() = runTest {
        val repository = JsonCharalyRepository(InMemoryCharalyStorage())
        val runtime = CharalyRuntime(repository, loadedEngine())
        runtime.createPack(pack)
        val instance = runtime.startStory(pack, StoryInstanceId("s1"))

        runtime.respond(instance, "Do you know where the ledger is?", alice).toList()

        val persisted = repository.getInstance(StoryInstanceId("s1"))!!
        assertFalse(
            "Alice still does not know",
            persisted.knowledge.knows(alice, dev.charaly.runtime.domain.FactId("fact-ledger")),
        )
        assertTrue(
            "and Bob does",
            persisted.knowledge.knows(bob, dev.charaly.runtime.domain.FactId("fact-ledger")),
        )
        assertEquals("world truth is unchanged", 2, persisted.knowledge.factCount)
    }
}
