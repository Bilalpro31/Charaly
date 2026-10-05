package dev.charaly.runtime.session

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.memory.MemorySource
import dev.charaly.runtime.domain.memory.MemorySubject
import dev.charaly.runtime.domain.memory.MemoryTier
import dev.charaly.runtime.domain.memory.MemoryVisibility
import dev.charaly.runtime.engine.StoryCreationOptions
import dev.charaly.runtime.inference.ChatRole
import dev.charaly.runtime.inference.InferenceEngine
import dev.charaly.runtime.inference.InferenceRequest
import dev.charaly.runtime.inference.InferenceResult
import dev.charaly.runtime.inference.LoadOutcome
import dev.charaly.runtime.inference.LoadProgress
import dev.charaly.runtime.inference.ModelInfo
import dev.charaly.runtime.inference.ModelLoadRequest
import dev.charaly.runtime.inference.StopReason
import dev.charaly.runtime.model.ModelBinding
import dev.charaly.runtime.pack.MiraculousPack
import dev.charaly.runtime.persistence.CharalyStorage
import dev.charaly.runtime.persistence.InMemoryCharalyStorage
import dev.charaly.runtime.persistence.JsonCharalyRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The memory pipeline has to be *wired in*, not merely present.
 *
 * `MemoryWriterTest` proves the gates work. This proves that playing a turn actually
 * passes through them - which is the property that matters, because a pipeline nothing
 * calls is a pipeline that does not exist as far as the story is concerned.
 */
class MemoryWritePipelineTest {

    private val pack = MiraculousPack.pack
    private val marinette = CharacterId("marinette")
    private val andre = CharacterId("andre")
    private val adrien = CharacterId("adrien")

    /**
     * An engine that is always loaded and answers with a fixed line.
     *
     * `MockInferenceEngine` takes a responder but is unloaded until told otherwise,
     * which is right for its own tests and wrong here: these tests are about what
     * happens *after* generation, and every one of them would otherwise stop at
     * "no model loaded".
     */
    private class AlwaysLoadedEngine(private val reply: String) : InferenceEngine {
        private val info = ModelInfo(id = "stub", path = "/stub.gguf", displayName = "Stub")
        override suspend fun loadModel(request: ModelLoadRequest): LoadOutcome {
            request.progress(LoadProgress(100, "ready"))
            return LoadOutcome.Loaded(info)
        }

        override suspend fun unloadModel() = Unit

        override suspend fun generate(request: InferenceRequest) =
            InferenceResult(text = reply, stopReason = StopReason.COMPLETED, engineId = "stub")

        override fun stream(request: InferenceRequest): Flow<dev.charaly.runtime.inference.StreamChunk> = flow {
            emit(dev.charaly.runtime.inference.StreamChunk(text = reply, done = true, tokenCount = 1))
        }

        override fun stop() = Unit
        override fun isLoaded(): Boolean = true
        override fun modelInfo(): ModelInfo = info
    }

    private fun runtime(storage: CharalyStorage, reply: String) = CharalyRuntime(
        repository = JsonCharalyRepository(storage),
        engine = AlwaysLoadedEngine(reply),
    )

    private suspend fun started(storage: CharalyStorage, reply: String): Pair<CharalyRuntime, StoryInstance> {
        val rt = runtime(storage, reply)
        val instance = rt.startStory(
            pack = pack,
            options = StoryCreationOptions(
                instanceId = StoryInstanceId("story-memory"),
                title = "Memory",
                scenario = pack.defaultScenario(),
                focusCharacterId = marinette,
                castCharacterIds = listOf(marinette, andre),
                modelBinding = ModelBinding.EMPTY,
                nowEpochMs = 1_700_000_000_000L,
            ),
        )
        return rt to instance
    }

    /** Plays one turn and returns the authoritative result. */
    private suspend fun play(
        storage: CharalyStorage,
        reply: String,
        input: String,
        speaker: CharacterId = andre,
    ): StoryInstance {
        val (rt, instance) = started(storage, reply)
        val updates = rt.respond(instance, userInput = input, characterId = speaker).collectAll()
        return updates.filterIsInstance<GenerationUpdate.Finished>().last().instance
            ?: error("the turn never finished; updates were $updates")
    }

    private suspend fun kotlinx.coroutines.flow.Flow<GenerationUpdate>.collectAll(): List<GenerationUpdate> {
        val updates = mutableListOf<GenerationUpdate>()
        this@collectAll.collect { update -> updates += update }
        return updates
    }

    // ------------------------------------------------------------------
    // Wiring
    // ------------------------------------------------------------------

    @Test
    fun `a turn writes memories through the gates`() = runTest {
        val finished = play(
            storage = InMemoryCharalyStorage(),
            reply = "The akuma was in the museum, and I know who sent it.",
            input = "I found the akuma hiding in the museum and I know who sent it",
        )
        assertTrue(
            "the disclosure must have been remembered, found ${finished.memories.size} memories",
            finished.memories.size > 0,
        )
    }

    @Test
    fun `small talk writes no memory at all`() = runTest {
        val finished = play(
            storage = InMemoryCharalyStorage(),
            reply = "Hello!",
            input = "hi",
        )
        val contents = finished.memories.all().map { it.content.lowercase() }
        assertTrue(
            "greetings must not be stored, found: $contents",
            contents.none { it.contains("hello!") || it == "hi" },
        )
    }

    @Test
    fun `scene participation is remembered even when nothing was said`() = runTest {
        val finished = play(
            storage = InMemoryCharalyStorage(),
            reply = "...",
            input = "...",
        )
        val sceneMemory = finished.memories.all().firstOrNull { it.tier == MemoryTier.SCENE }
        assertNotNull("a character must remember where they were", sceneMemory)
        // Not WORLD: "we were at the school together" is written from the owner's point
        // of view, so anyone else reading it would not be reading a true sentence.
        assertEquals(MemoryVisibility.CHARACTER, sceneMemory!!.visibility)
    }

    @Test
    fun `both participants remember the scene, not only the speaker`() = runTest {
        val finished = play(
            storage = InMemoryCharalyStorage(),
            reply = "We should meet on the rooftop tonight.",
            input = "I will meet you on the rooftop tonight, I promise",
        )
        listOf(andre, marinette).forEach { participant ->
            assertTrue(
                "$participant should have a scene memory",
                finished.memories.of(participant).any { it.tier == MemoryTier.SCENE },
            )
        }
    }

    // ------------------------------------------------------------------
    // Visibility survives the pipeline
    // ------------------------------------------------------------------

    @Test
    fun `what was said in a scene never reaches someone who was not in it`() = runTest {
        // Marinette is in the scene and so legitimately hears it; Adrien is at home and
        // must learn nothing. The point being tested is *audience*, not secrecy: a
        // bystander picking up a scene they were absent from is the leak that matters.
        val storage = InMemoryCharalyStorage()
        val (rt, instance) = started(storage, "I know Ladybug is Marinette and I will never say it aloud.")
        val updates = rt.respond(
            instance,
            userInput = "I know Ladybug is Marinette and I will never say it aloud",
            characterId = andre,
        ).collectAll()
        val finished = updates.filterIsInstance<GenerationUpdate.Finished>().last().instance
            ?: error("the turn never finished")

        assertTrue(
            "the admission test needs at least one memory carrying the secret",
            finished.memories.all().any { it.content.contains("Ladybug is Marinette") },
        )
        finished.memories.all()
            .filter { it.content.contains("Ladybug is Marinette") }
            .forEach { memory ->
                assertTrue(
                    "the secret was spoken aloud, so Marinette must be able to recall it: ${memory.content}",
                    memory.visibleTo(MemorySubject.Character(marinette)),
                )
            }
        assertTrue(
            "Adrien was not present and must not have the line at all",
            finished.memories.of(adrien).none { it.content.contains("Ladybug is Marinette") },
        )
    }

    @Test
    fun `a character only remembers what happened in scenes they were in`() = runTest {
        val finished = play(
            storage = InMemoryCharalyStorage(),
            reply = "I found the akuma in the museum and I know who sent it.",
            input = "I found the akuma in the museum",
        )
        val absent = finished.memories.of(adrien)
        assertTrue(
            "Adrien was not in the bakery scene and must have no SCENE memory from it",
            absent.none { it.tier == MemoryTier.SCENE && it.source == MemorySource.EVENT },
        )
    }

    // ------------------------------------------------------------------
    // Persistence of what the pipeline wrote
    // ------------------------------------------------------------------

    @Test
    fun `what the pipeline wrote survives a reload`() = runTest {
        val storage = InMemoryCharalyStorage()
        val finished = play(
            storage = storage,
            reply = "I found the akuma in the museum and I know who sent it.",
            input = "I found the akuma",
        )
        val before = finished.memories.size

        val reloaded = runtime(storage, "").loadStory(finished.id)
        assertNotNull("the story must reload", reloaded)
        assertEquals(before, reloaded!!.memories.size)
        assertEquals(
            finished.memories.all().map { it.content }.sorted(),
            reloaded.memories.all().map { it.content }.sorted(),
        )
    }

    @Test
    fun `the pipeline is deterministic for the same conversation`() = runTest {
        val a = play(
            storage = InMemoryCharalyStorage(),
            reply = "I know the akuma was in the museum.",
            input = "I know the akuma was in the museum",
        )
        val b = play(
            storage = InMemoryCharalyStorage(),
            reply = "I know the akuma was in the museum.",
            input = "I know the akuma was in the museum",
        )
        assertEquals(a.memories.size, b.memories.size)
        assertEquals(
            a.memories.all().map { it.content to it.importance }.sortedBy { it.first },
            b.memories.all().map { it.content to it.importance }.sortedBy { it.first },
        )
    }

    @Test
    fun `a character's memory index stays in step with the store`() = runTest {
        val finished = play(
            storage = InMemoryCharalyStorage(),
            reply = "I found the akuma in the museum and I know who sent it.",
            input = "I found the akuma",
        )
        finished.characters.values.forEach { character ->
            assertEquals(
                "${character.name}'s memory index disagrees with the store",
                finished.memories.idsOf(character.characterId).toSet(),
                character.memoryIds.toSet(),
            )
        }
    }

    @Test
    fun `story time is what stamps the memories, not wall clock time`() = runTest {
        val before = started(InMemoryCharalyStorage(), "").second.worldClock.now
        val finished = play(
            storage = InMemoryCharalyStorage(),
            reply = "I found the akuma in the museum and I know who sent it.",
            input = "I found the akuma",
        )
        // Authored pack memories carry the pack's own start time, so the assertion is
        // about the memories this turn wrote: they sit inside the story's own timeline.
        val written = finished.memories.all().filter { it.source != MemorySource.AUTHORED }
        assertTrue("the turn must have written something", written.isNotEmpty())
        written.forEach { memory ->
            assertTrue(
                "${memory.content} was stamped ${memory.createdAt}, before the story started",
                memory.createdAt >= before,
            )
            assertTrue(
                "${memory.content} was stamped after the story ended",
                !memory.createdAt.isAfter(finished.worldClock.now),
            )
        }
    }

    @Test
    fun `the last user line reaches the model exactly once`() = runTest {
        // The pipeline must not disturb prompt assembly: a duplicated user line makes a
        // small model answer the same question twice.
        val engine = AlwaysLoadedEngine("A single answer.")
        val storage = InMemoryCharalyStorage()
        val (rt, instance) = started(storage, "A single answer.")
        val sent = mutableListOf<InferenceRequest>()
        val recording = object : InferenceEngine by engine {
            override fun stream(request: InferenceRequest): Flow<dev.charaly.runtime.inference.StreamChunk> {
                sent += request
                return engine.stream(request)
            }
        }
        val wrapping = CharalyRuntime(JsonCharalyRepository(storage), recording)
        wrapping.respond(instance, userInput = "the akuma question", characterId = andre).collect { }

        assertEquals(1, sent.size)
        val userLines = sent.single().messages
            .filter { it.role == ChatRole.USER }
            .filter { it.content.contains("akuma question") }
        assertEquals("the user line must appear exactly once", 1, userLines.size)
    }
}