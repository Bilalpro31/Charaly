package dev.charaly.runtime.session

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.engine.SampleWorlds
import dev.charaly.runtime.inference.InferenceEngine
import dev.charaly.runtime.inference.InferenceRequest
import dev.charaly.runtime.inference.InferenceResult
import dev.charaly.runtime.inference.LoadOutcome
import dev.charaly.runtime.inference.ModelInfo
import dev.charaly.runtime.inference.ModelLoadRequest
import dev.charaly.runtime.inference.StreamChunk
import dev.charaly.runtime.inference.StopReason
import dev.charaly.runtime.persistence.CharalyRepository
import dev.charaly.runtime.persistence.InMemoryCharalyStorage
import dev.charaly.runtime.persistence.JsonCharalyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 10 B — "a turn must not do its work on the thread that draws the screen".
 *
 * ## What was wrong
 *
 * `CharalyRuntime.respond` was a bare `flow { ... }`, so it executed in **the collector's**
 * context. In the app the collector is `viewModelScope`, which is the Android main thread.
 * Only the native decode was elsewhere, because `LocalLlamaInferenceEngine.stream` carries
 * its own `flowOn(Dispatchers.IO)`.
 *
 * Everything else a turn does therefore ran on main: reading the pack from storage,
 * assembling the prompt, the memory write pipeline, `repository.saveInstance` (twice a
 * turn, encoding the whole story to JSON on the way), the event engine and chapter
 * derivation. None of that is the model being slow, and all of it is charged to the frame
 * budget of the screen the player is waiting on.
 *
 * ## Why the assertion is about a THREAD and not a duration
 *
 * A timing assertion would pass on a fast CI machine and fail on a loaded one, and it
 * would say nothing about *where* the work ran. "The save did not happen on the thread
 * that collected the flow" is a fact that holds on every machine.
 */
class TurnDispatcherTest {

    private val alice = CharacterId(SampleWorlds.alice.id.value)
    private val pack = SampleWorlds.libraryPack()

    /** Records which thread each storage operation ran on. */
    private class ThreadRecordingRepository(
        private val delegate: CharalyRepository,
    ) : CharalyRepository by delegate {
        @Volatile
        var saveThread: Thread? = null

        override suspend fun saveInstance(instance: StoryInstance) {
            saveThread = Thread.currentThread()
            delegate.saveInstance(instance)
        }
    }

    private fun runtime(
        repository: CharalyRepository,
        turnDispatcher: kotlinx.coroutines.CoroutineDispatcher,
        text: String,
    ) = CharalyRuntime(
        repository = repository,
        engine = ScriptedEngine(text),
        turnDispatcher = turnDispatcher,
    )

    private fun oneTurn(
        turnDispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ): Thread? = runBlocking {
        val recording = ThreadRecordingRepository(JsonCharalyRepository(InMemoryCharalyStorage()))
        val rt = runtime(recording, turnDispatcher, "Merhaba! Nasılsın?")
        rt.createPack(pack)
        var instance = rt.startStory(pack, StoryInstanceId("dispatcher-test"))
        rt.respond(instance, "Merhaba, nasılsın?", alice).collect { update ->
            if (update is GenerationUpdate.Finished) instance = update.instance ?: instance
        }
        recording.saveThread
    }

    @Test
    fun `a turn's persistence does not run on the collecting thread`() {
        val collectorThread = Thread.currentThread()
        val saveThread = oneTurn(Dispatchers.Default)
        assertNotNull("the turn never saved, so nothing was measured", saveThread)
        assertNotEquals(
            "the story was written from the thread that collects the flow - in the app, " +
                "that is the Android main thread",
            collectorThread,
            saveThread,
        )
    }

    @Test
    fun `the dispatcher is a real knob, not decoration`() {
        // Unconfined runs the flow body on the collecting thread. If the save still moved
        // away from it here, the test above would be proving something about the test
        // harness rather than about the runtime.
        val collectorThread = Thread.currentThread()
        val saveThread = oneTurn(Dispatchers.Unconfined)
        assertNotNull(saveThread)
        assertTrue(
            "with an unconfined dispatcher the save must stay on the collecting thread",
            saveThread === collectorThread,
        )
    }

    @Test
    fun `updates still arrive, in order, after the body moves`() = runBlocking {
        val repository = JsonCharalyRepository(InMemoryCharalyStorage())
        val rt = runtime(repository, Dispatchers.Default, "Merhaba! Nasılsın?")
        rt.createPack(pack)
        val instance = rt.startStory(pack, StoryInstanceId("dispatcher-order"))
        val seen = mutableListOf<String>()
        rt.respond(instance, "Merhaba, nasılsın?", alice).collect { update ->
            when (update) {
                is GenerationUpdate.Started -> seen += "started"
                is GenerationUpdate.Chunk -> seen += "chunk:${update.text}"
                is GenerationUpdate.Finished -> seen += "finished:${update.text}"
                is GenerationUpdate.Failed -> seen += "failed:${update.error}"
                else -> Unit
            }
        }
        assertTrue("the turn produced nothing at all: $seen", seen.isNotEmpty())
        assertTrue("the turn must begin with Started", seen.first() == "started")
        assertTrue("the turn must end with Finished", seen.last().startsWith("finished:"))
        assertTrue(
            "the reply must reach the collector intact: $seen",
            seen.any { it == "finished:Merhaba! Nasılsın?" },
        )
    }

    @Test
    fun `turn timings are attributed to every phase the brief asks for`() = runBlocking {
        val repository = JsonCharalyRepository(InMemoryCharalyStorage())
        val rt = runtime(repository, Dispatchers.Default, "Merhaba! Nasılsın?")
        rt.createPack(pack)
        val instance = rt.startStory(pack, StoryInstanceId("dispatcher-perf"))
        var perf: TurnPerf? = null
        rt.respond(instance, "Merhaba, nasılsın?", alice).collect { update ->
            if (update is GenerationUpdate.Finished) perf = update.perf
        }
        val measured = perf
        assertNotNull("no timings were reported for the turn", measured)
        // A scripted engine cannot measure its own clocks, so the native-only figures stay
        // absent on purpose. What must always be present is the runtime's own attribution:
        // without `dispatchMillis` the client half of the first-token time is unattributed.
        assertTrue(
            "send -> inference call must be measured on every turn",
            measured!!.dispatchMillis >= 0L,
        )
        assertTrue("context build must be measured", measured.contextBuildMillis >= 0L)
        assertTrue("residency must be measured", measured.residencyMillis >= 0L)
        val summary = measured.summary()
        listOf(
            "dispatch_to_inference_call=",
            "tokenize=",
            "eval=",
            "threads_decode=",
            "threads_prompt=",
        ).forEach { key ->
            assertTrue("the developer readout must carry '$key':\n$summary", summary.contains(key))
        }
    }

    /** One token, then EOG. Measures nothing itself - it is here to be measured. */
    private class ScriptedEngine(private val reply: String) : InferenceEngine {
        override suspend fun loadModel(request: ModelLoadRequest): LoadOutcome =
            LoadOutcome.Failed(dev.charaly.runtime.inference.InferenceError.ModelNotLoaded())

        override suspend fun unloadModel() = Unit
        override suspend fun generate(request: InferenceRequest): InferenceResult =
            InferenceResult(text = reply, stopReason = StopReason.COMPLETED)

        override fun stream(request: InferenceRequest): Flow<StreamChunk> = flow {
            emit(StreamChunk(text = reply, done = false, tokenCount = 1))
            emit(StreamChunk(text = "", done = true, tokenCount = 1))
        }

        override fun stop() = Unit
        override fun isLoaded(): Boolean = true
        override fun modelInfo(): ModelInfo? = null
    }
}
