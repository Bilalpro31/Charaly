package dev.charaly.app.model

import dev.charaly.runtime.inference.ChatMessage
import dev.charaly.runtime.inference.ChatRole
import dev.charaly.runtime.inference.GenerationParams
import dev.charaly.runtime.inference.InferenceEngine
import dev.charaly.runtime.inference.InferenceError
import dev.charaly.runtime.inference.InferenceRequest
import dev.charaly.runtime.inference.InferenceResult
import dev.charaly.runtime.inference.LoadOutcome
import dev.charaly.runtime.inference.ModelInfo
import dev.charaly.runtime.inference.ModelLoadRequest
import dev.charaly.runtime.inference.StopReason
import dev.charaly.runtime.inference.StreamChunk
import dev.charaly.runtime.model.BenchmarkObservation
import dev.charaly.runtime.model.InstalledModel
import dev.charaly.runtime.model.LocalInferenceEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE MODEL TEST, AS SEQUENCE.
 *
 * ## What a scripted engine can and cannot stand in for
 *
 * It can stand in for the *sequencing*: that the test loads before it generates, that it
 * does not report a pass when the load failed, that it fails rather than passes when the
 * model returns nothing, and that every reported figure came from the phase that produced
 * it.
 *
 * It cannot stand in for the numbers, which is why nothing here asserts a tok/s figure or a
 * load time. Those are wall-clock facts about a real device. What is asserted is that the
 * *shape* of the report cannot be produced without a real decode - which is the property
 * that matters, and the one a mock could otherwise satisfy.
 *
 * `LocalModelTestWiringTest` covers the other half: that production wires the same engine
 * the story runtime uses, rather than a stand-in.
 */
class ModelTestRunnerTest {

    private val model = InstalledModel(
        id = "local-test-1",
        displayName = "test-model",
        absolutePath = "/models/test-model.gguf",
        sizeBytes = 4096L,
    )

    private fun runner(engine: LocalInferenceEngine) = ModelTestRunner(
        engineProvider = { engine },
        versionLabel = { "b1234" },
    )

    // ==================================================================
    // 1. A working model reports what it actually produced
    // ==================================================================

    @Test
    fun `a working model passes and shows its own output`() = runTest {
        val engine = ScriptedEngine(
            load = { LoadOutcome.Loaded(info = info()) },
            chunks = listOf("Hello", ", ", "world."),
        )

        val phases = runner(engine).run(model).toList()
        val last = phases.last()

        assertEquals(ModelTestState.PASSED, last.state)
        assertEquals(
            "the output must be the model's own text, assembled from its chunks",
            "Hello, world.",
            last.output,
        )
        assertEquals(3, last.tokens)
        assertEquals("test-model", last.info?.displayName)
        assertEquals("qwen3", last.info?.metadata?.get("general.architecture"))
        assertEquals("4 kB", 4096L.asSizeLabel())
        assertEquals("b1234", last.version)
    }

    @Test
    fun `the report carries the architecture the engine reported`() = runTest {
        val engine = ScriptedEngine(
            load = { LoadOutcome.Loaded(info = info()) },
            chunks = listOf("Hi."),
        )

        val last = runner(engine).run(model).toList().last()

        // Read from the engine's own metadata, so the row cannot be filled in from the
        // registry and disagree with what was loaded.
        assertEquals("qwen3", last.info?.metadata?.get("general.architecture"))
        assertEquals(2048, last.info?.contextSize)
    }

    // ==================================================================
    // 2. The phases are ordered, and the load really precedes the decode
    // ==================================================================

    @Test
    fun `the load happens before any generation is attempted`() = runTest {
        val engine = ScriptedEngine(
            load = { LoadOutcome.Loaded(info = info()) },
            chunks = listOf("Hi."),
        )

        val phases = runner(engine).run(model).toList()

        assertEquals(ModelTestState.LOADING, phases.first().state)
        assertTrue(
            "generation must be announced before it is reported done",
            phases.any { it.state == ModelTestState.GENERATING },
        )
        assertEquals(ModelTestState.PASSED, phases.last().state)
        assertEquals(
            "the engine was called in load-then-generate order",
            listOf("load", "stream"),
            engine.calls,
        )
    }

    @Test
    fun `a failing load never reaches generation`() = runTest {
        val engine = ScriptedEngine(
            load = {
                LoadOutcome.Failed(InferenceError.OutOfMemory(requested = 4_000_000_000L))
            },
            chunks = listOf("should never be reached"),
        )

        val phases = runner(engine).run(model).toList()

        assertEquals(ModelTestState.FAILED, phases.last().state)
        assertTrue(
            "a model that did not load must not have been asked to generate",
            "stream" !in engine.calls,
        )
        assertTrue(phases.last().failure is TestFailure.Of)
        // And no output is claimed.
        assertEquals("", phases.last().output)
    }

    // ==================================================================
    // 3. No success without text
    // ==================================================================

    @Test
    fun `a model that produces nothing fails rather than passing`() = runTest {
        val engine = ScriptedEngine(
            load = { LoadOutcome.Loaded(info = info()) },
            chunks = emptyList(),
        )

        val last = runner(engine).run(model).toList().last()

        assertEquals(
            "an empty completion is a failure, not a pass",
            ModelTestState.FAILED,
            last.state,
        )
        assertEquals("", last.output)
    }

    @Test
    fun `a model that produces only whitespace fails`() = runTest {
        val engine = ScriptedEngine(
            load = { LoadOutcome.Loaded(info = info()) },
            chunks = listOf("   ", "\n"),
        )

        val last = runner(engine).run(model).toList().last()

        assertEquals(ModelTestState.FAILED, last.state)
    }

    @Test
    fun `a generation that throws is reported as a failure`() = runTest {
        val engine = ScriptedEngine(
            load = { LoadOutcome.Loaded(info = info()) },
            chunks = listOf("Half a sen"),
            throwOnStream = InferenceError.GenerationFailed("kv cache exhausted"),
        )

        val last = runner(engine).run(model).toList().last()

        assertEquals(ModelTestState.FAILED, last.state)
        assertTrue(last.failure is TestFailure.Other)
    }

    @Test
    fun `a partial generation that throws is not reported as a pass`() = runTest {
        // The dangerous case: tokens arrived, then it died. Reporting "PASSED" with the
        // partial text would tell the user their model works.
        val engine = ScriptedEngine(
            load = { LoadOutcome.Loaded(info = info()) },
            chunks = listOf("Half a sen"),
            throwOnStream = InferenceError.GenerationFailed("kv cache exhausted"),
        )

        val last = runner(engine).run(model).toList().last()

        assertTrue(last.state != ModelTestState.PASSED)
        assertNull(last.observation)
    }

    // ==================================================================
    // 4. The prompt is what the brief asks for
    // ==================================================================

    @Test
    fun `the test asks for one short sentence`() = runTest {
        val engine = ScriptedEngine(
            load = { LoadOutcome.Loaded(info = info()) },
            chunks = listOf("Hello."),
        )

        runner(engine).run(model).toList()

        val prompt = engine.lastRequest!!
        val userTurn = prompt.messages.single { it.role == ChatRole.USER }.content
        assertEquals("Say hello in one short sentence.", userTurn)
        assertEquals(
            "a test must not wander",
            0.2f,
            prompt.params.temperature,
            0.0001f,
        )
    }

    // ==================================================================
    // 5. Figures are absent rather than invented
    // ==================================================================

    @Test
    fun `an unmeasurable run reports no speed rather than zero`() = runTest {
        val engine = ScriptedEngine(
            load = { LoadOutcome.Loaded(info = info()) },
            chunks = listOf("Hello."),
        )

        val last = runner(engine).run(model).toList().last()

        // A zero-token decode window cannot produce a rate. The label is empty, which the
        // screen renders as "-", not as "0.0 tok/s".
        assertTrue(
            "a speed label must never be fabricated; got '${last.tokensPerSecondLabel}'",
            last.tokensPerSecondLabel.isBlank() || last.tokensPerSecondLabel.endsWith("tok/s"),
        )
        if (last.observation == null) {
            assertEquals("", last.tokensPerSecondLabel)
        }
    }

    @Test
    fun `the identity rows are absent when the load failed`() = runTest {
        val engine = ScriptedEngine(
            load = { LoadOutcome.Failed(InferenceError.ModelNotFound("/models/test-model.gguf")) },
            chunks = emptyList(),
        )

        val last = runner(engine).run(model).toList().last()

        // No `info` means the screen has no architecture to show, and must not invent one.
        assertNull(last.info)
        assertEquals("", last.output)
        assertEquals(0, last.tokens)
    }

    // ==================================================================
    // 6. Backend reporting
    // ==================================================================

    @Test
    fun `a build that cannot report its devices says unknown rather than CPU`() = runTest {
        val engine = ScriptedEngine(
            load = { LoadOutcome.Loaded(info = info()) },
            chunks = listOf("Hi."),
            devices = emptyList(),
        )

        val last = runner(engine).run(model).toList().last()

        // Naming a device the build did not report is exactly the claim this refuses to
        // make.
        assertEquals("unknown", last.backend)
    }

    @Test
    fun `a reported device is shown verbatim`() = runTest {
        val engine = ScriptedEngine(
            load = { LoadOutcome.Loaded(info = info()) },
            chunks = listOf("Hi."),
            devices = listOf("CPU"),
        )

        assertEquals("CPU", runner(engine).run(model).toList().last().backend)
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private fun info() = ModelInfo(
        id = model.id,
        path = model.absolutePath,
        displayName = model.displayName,
        contextSize = 2048,
        parameterSizeBytes = model.sizeBytes,
        metadata = mapOf("general.architecture" to "qwen3"),
    )

    /**
     * An engine that records the order of its calls and returns scripted output.
     *
     * Deliberately implements [LocalInferenceEngine] - the same port the native engine does
     * - so the runner under test cannot take a shortcut that the real engine could not.
     */
    private class ScriptedEngine(
        private val load: () -> LoadOutcome,
        private val chunks: List<String>,
        private val throwOnStream: InferenceError? = null,
        private val devices: List<String> = listOf("CPU"),
    ) : LocalInferenceEngine {

        val calls = mutableListOf<String>()
        var lastRequest: InferenceRequest? = null

        override suspend fun loadModel(request: ModelLoadRequest): LoadOutcome {
            calls += "load"
            return load()
        }

        override suspend fun unloadModel() {
            calls += "unload"
        }

        override suspend fun generate(request: InferenceRequest): InferenceResult {
            calls += "generate"
            return InferenceResult(
                text = chunks.joinToString(""),
                stopReason = StopReason.COMPLETED,
                engineId = "scripted",
            )
        }

        override fun stream(request: InferenceRequest): Flow<StreamChunk> = flow {
            calls += "stream"
            lastRequest = request
            chunks.forEach { emit(StreamChunk(text = it, done = false)) }
            throwOnStream?.let { throw it }
            emit(StreamChunk(text = "", done = true, tokenCount = chunks.size))
        }

        override fun stop() {
            calls += "stop"
        }

        override fun isLoaded(): Boolean = calls.contains("load")

        override fun modelInfo(): ModelInfo? = null

        override fun computeDevices(): List<String> = devices

        /**
         * Not used by the test.
         *
         * Returning null is the only honest answer for a scripted engine: it has no decode
         * loop to time, and a fabricated observation here would be indistinguishable from
         * a real one at the type level.
         */
        override suspend fun benchmark(prompt: String, maxTokens: Int): BenchmarkObservation? = null
    }
}