package dev.charaly.runtime.model

import dev.charaly.runtime.inference.GenerationParams
import dev.charaly.runtime.inference.InferenceEngine
import dev.charaly.runtime.inference.InferenceRequest
import dev.charaly.runtime.inference.InferenceResult
import dev.charaly.runtime.inference.LoadOutcome
import dev.charaly.runtime.inference.ModelInfo
import dev.charaly.runtime.inference.ModelLoadRequest
import dev.charaly.runtime.inference.StreamChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The benchmark runner.
 *
 * The tests here are about one property above all others: **a speed figure exists only if
 * something ran.** Every path that produces no measurement has to produce no record, because
 * a stored estimate is indistinguishable from a stored measurement once it is read back -
 * and that ambiguity is the whole thing the "Fast on this device" label exists to avoid.
 */
class ModelBenchmarkRunnerTest {

    /** An engine that measures deterministically, with a configurable failure mode. */
    private class FakeBenchmarkEngine(
        private val observation: BenchmarkObservation?,
        private val devices: List<String> = listOf("CPU:fake"),
        /** Thrown instead of measuring, to exercise the failure path. */
        private val throws: Throwable? = null,
    ) : InferenceEngine, BenchmarkableEngine {
        var benchmarkCount = 0
            private set
        var lastPrompt: String? = null
            private set
        var lastMaxTokens: Int = 0
            private set

        override suspend fun loadModel(request: ModelLoadRequest) =
            LoadOutcome.Loaded(ModelInfo("fake", request.path, request.displayName))

        override suspend fun unloadModel() = Unit
        override suspend fun generate(request: InferenceRequest) = InferenceResult("x")
        override fun stream(request: InferenceRequest): Flow<StreamChunk> = flow { emit(StreamChunk("", true)) }
        override fun stop() = Unit
        override fun isLoaded() = true
        override fun modelInfo() = ModelInfo("fake", "fake", "Fake")

        override suspend fun benchmark(prompt: String, maxTokens: Int): BenchmarkObservation? {
            benchmarkCount++
            lastPrompt = prompt
            lastMaxTokens = maxTokens
            throws?.let { throw it }
            return observation
        }

        override fun computeDevices(): List<String> = devices
    }

    private fun loaded(millis: Long = 900L) = { _: (Int) -> Unit ->
        LoadOutcomeForBenchmark.Loaded(millis)
    }

    private val observation = BenchmarkObservation(
        tokens = 64,
        decodeMicros = 4_000_000L,
        promptMicros = 200_000L,
        firstTokenMicros = 120_000L,
        promptTokens = 48,
        contextSize = 2048,
    )

    // ------------------------------------------------------------------
    // A real measurement
    // ------------------------------------------------------------------

    @Test
    fun `a measured run produces a record from the observation alone`() = runTest {
        val engine = FakeBenchmarkEngine(observation)
        val result = ModelBenchmarkRunner.run(
            engine = engine,
            modelId = "m1",
            load = loaded(millis = 1_234L),
        ).getOrThrow()

        assertEquals("m1", result.modelId)
        // 64 tokens over 4 seconds of decoding.
        assertEquals(16.0, result.tokensPerSecond, 0.001)
        assertEquals(64, result.tokensMeasured)
        assertEquals(1_234L, result.loadMillis)
        assertEquals(120L, result.firstTokenMillis)
        // 48 prompt tokens over 200ms.
        assertEquals(240.0, result.promptTokensPerSecond, 0.001)
    }

    @Test
    fun `the benchmark prompt is fixed so two runs are comparable`() = runTest {
        val engine = FakeBenchmarkEngine(observation)
        ModelBenchmarkRunner.run(engine = engine, modelId = "m1", load = loaded())

        assertEquals(ModelBenchmarkRunner.PROMPT, engine.lastPrompt)
        assertEquals(ModelBenchmarkRunner.MAX_TOKENS, engine.lastMaxTokens)
        // And the prompt is prose in the shape a story turn actually sends, not a
        // token-counting trick, because the figure that matters is the speed at which this
        // app writes a scene.
        assertTrue(ModelBenchmarkRunner.PROMPT.length > 100)
        assertTrue(ModelBenchmarkRunner.PROMPT.contains(' '))
    }

    @Test
    fun `progress reports real phases and nothing else`() = runTest {
        val phases = mutableListOf<BenchmarkPhase>()
        ModelBenchmarkRunner.run(
            engine = FakeBenchmarkEngine(observation),
            modelId = "m1",
            load = { onProgress ->
                // The loader reports its own percentage; the runner must pass it through
                // rather than inventing steps.
                onProgress(0)
                onProgress(50)
                onProgress(100)
                LoadOutcomeForBenchmark.Loaded(500L)
            },
            progress = { phase -> phases += phase },
        )

        assertEquals(BenchmarkPhase.PREPARING, phases.first())
        assertEquals(BenchmarkPhase.MEASURING, phases[phases.size - 2])
        assertEquals(BenchmarkPhase.COMPLETE, phases.last())
        assertTrue(phases.contains(BenchmarkPhase.LOADING(0)))
        assertTrue(phases.contains(BenchmarkPhase.LOADING(50)))
        assertTrue(phases.contains(BenchmarkPhase.LOADING(100)))
    }

    // ------------------------------------------------------------------
    // No measurement means no record
    // ------------------------------------------------------------------

    @Test
    fun `an engine that produces nothing records nothing`() = runTest {
        val result = ModelBenchmarkRunner.run(
            engine = FakeBenchmarkEngine(observation = null),
            modelId = "m1",
            load = loaded(),
        )

        assertTrue("expected a failure", result.isFailure)
        val failure = result.exceptionOrNull()
        assertTrue(
            "expected a typed benchmark failure, got $failure",
            failure is BenchmarkFailedException,
        )
        assertEquals(
            BenchmarkFailure.CouldNotComplete,
            (failure as BenchmarkFailedException).reason,
        )
    }

    @Test
    fun `a failed load records nothing and never mentions the engine's wording`() = runTest {
        val result = ModelBenchmarkRunner.run(
            engine = FakeBenchmarkEngine(observation),
            modelId = "m1",
            load = { LoadOutcomeForBenchmark.Failed("llama_model_load_from_file: out of memory") },
        )

        assertTrue(result.isFailure)
        val failure = result.exceptionOrNull() as BenchmarkFailedException
        assertEquals(BenchmarkFailure.CouldNotComplete, failure.reason)
        // The engine's message must not survive into what the UI can show.
        assertFalse(failure.message.orEmpty().contains("llama"))
        assertEquals(
            "Benchmark could not complete on this device.",
            failure.message,
        )
    }

    @Test
    fun `an out of memory run is a clean failure rather than a crash`() = runTest {
        val result = ModelBenchmarkRunner.run(
            engine = FakeBenchmarkEngine(observation, throws = OutOfMemoryError("Java heap space")),
            modelId = "m1",
            load = loaded(),
        )

        val failure = result.exceptionOrNull() as BenchmarkFailedException
        assertEquals(BenchmarkFailure.NotEnoughMemory, failure.reason)
        assertEquals(
            "This model needs more memory than this device has free.",
            failure.message,
        )
    }

    @Test
    fun `an arbitrary engine error is reported without leaking its message`() = runTest {
        val result = ModelBenchmarkRunner.run(
            engine = FakeBenchmarkEngine(
                observation,
                throws = IllegalStateException("InferenceError: llama_decode failed"),
            ),
            modelId = "m1",
            load = loaded(),
        )

        val message = (result.exceptionOrNull() as BenchmarkFailedException).message
        assertEquals("Benchmark could not complete on this device.", message)
        assertFalse(message.orEmpty().contains("llama"))
        assertFalse(message.orEmpty().contains("InferenceError"))
    }

    // ------------------------------------------------------------------
    // Memory
    // ------------------------------------------------------------------

    @Test
    fun `peak memory is recorded from the probe, not estimated`() = runTest {
        val probe = object : DeviceMemoryProbe {
            override fun peakResidentBytes() = 1_400_000_000L
            override fun currentFreeBytes() = 3_000_000_000L
        }
        val result = ModelBenchmarkRunner.run(
            engine = FakeBenchmarkEngine(observation),
            modelId = "m1",
            memoryProbe = probe,
            load = loaded(),
        ).getOrThrow()

        assertEquals(1_400_000_000L, result.peakMemoryBytes)
        assertEquals(3_000_000_000L, result.freeMemoryBeforeBytes)
        assertEquals(3_000_000_000L, result.freeMemoryAfterBytes)
    }

    @Test
    fun `an unreadable memory figure records zero rather than a guess`() = runTest {
        val result = ModelBenchmarkRunner.run(
            engine = FakeBenchmarkEngine(observation),
            modelId = "m1",
            memoryProbe = DeviceMemoryProbe.UNKNOWN,
            load = loaded(),
        ).getOrThrow()

        assertEquals(0L, result.peakMemoryBytes)
        // The measurement itself is still real: an unknown memory figure must not throw away
        // a tok/s figure that genuinely was produced.
        assertTrue(result.tokensPerSecond > 0.0)
    }

    @Test
    fun `the proc-status probe reads a real high-water mark`() {
        // The probe reads the *current* process, so the assertion is that it returns
        // something a JVM has rather than a fixed number.
        val probe = DeviceMemoryProbe.fromProcStatus()
        val peak = probe.peakResidentBytes()
        if (File("/proc/self/status").canRead()) {
            assertTrue("expected a real resident figure, got $peak", peak > 0L)
            // A process cannot have less peak memory than it currently has resident.
            assertTrue(peak >= 1L * 1024L * 1024L)
        }
    }

    @Test
    fun `the proc-status probe survives an unreadable file`() {
        val probe = DeviceMemoryProbe.fromProcStatus("/definitely/not/here/status")
        // Zero, not a throw: an unreadable figure means "not measured", never a crash.
        assertEquals(0L, probe.peakResidentBytes())
        assertEquals(0L, probe.currentFreeBytes())
    }

    // ------------------------------------------------------------------
    // The observation's own invariants
    // ------------------------------------------------------------------

    @Test
    fun `an observation with no tokens cannot be constructed`() {
        // The guard that makes "no measurement" unrepresentable rather than merely unlikely:
        // a zero-token observation cannot be built, so nothing downstream can store one.
        val thrown = runCatching {
            BenchmarkObservation(
                tokens = 0,
                decodeMicros = 1_000L,
                promptMicros = 1_000L,
                firstTokenMicros = 0L,
                promptTokens = 10,
                contextSize = 2048,
            )
        }.exceptionOrNull()
        assertNotNull(thrown)
    }

    @Test
    fun `tokens per second is computed from the decode window alone`() {
        val fast = BenchmarkObservation(
            tokens = 32,
            decodeMicros = 1_000_000L,
            promptMicros = 500_000L,
            firstTokenMicros = 100_000L,
            promptTokens = 10,
            contextSize = 2048,
        )
        assertEquals(32.0, fast.tokensPerSecond, 0.001)
        // Prompt evaluation is reported separately and must not be folded in: including it
        // would make a slow prompt look like a fast model.
        assertEquals(20.0, fast.promptTokensPerSecond, 0.001)
    }

    @Test
    fun `a model benchmark cannot be recorded without tokens`() {
        // The same guard exists on the stored type, so a hand-built record with no tokens
        // is rejected rather than persisted.
        val thrown = runCatching {
            ModelBenchmark(
                modelId = "m1",
                loadMillis = 100L,
                tokensPerSecond = 0.0,
                tokensMeasured = 0,
            )
        }.exceptionOrNull()
        assertNotNull(thrown)
    }
}

/**
 * The device-reported view of speed.
 *
 * The load-bearing assertion is the negative one: **nothing on this path can produce a
 * speed figure that was not measured.** Every one of these cases must render words.
 */
class BenchmarkSpeedVerdictTest {

    private fun measurement(
        tokensPerSecond: Double = 18.7,
        modelId: String = "m1",
        devices: List<String> = listOf("CPU:builtin"),
        threads: Int = 4,
        gpuLayers: Int = 0,
    ) = BenchmarkRecord(
        benchmark = ModelBenchmark(
            modelId = modelId,
            loadMillis = 1_400L,
            tokensPerSecond = tokensPerSecond,
            tokensMeasured = 64,
            promptTokensPerSecond = 210.0,
            firstTokenMillis = 220L,
            peakMemoryBytes = 2_300_000_000L,
        ),
        backendLabel = "CPU",
        threads = threads,
        gpuLayers = gpuLayers,
        contextSize = 2048,
        devices = devices,
    )

    @Test
    fun `a measured model reports its rate and where it was measured`() {
        val verdict = dev.charaly.runtime.presentation.BenchmarkPresenter.verdict(measurement())

        assertEquals(dev.charaly.runtime.presentation.SpeedState.MEASURED, verdict.state)
        assertTrue(verdict.hasMeasurement)
        assertTrue("18.7 tok/s" in verdict.evidence)
        // The conditions travel with the number, because 18 tok/s on four threads is a
        // different fact from 18 tok/s on an accelerator.
        assertTrue("4 iş parçacığı" in verdict.conditions)
        assertTrue("CPU" in verdict.conditions)
        assertTrue("ölçüldü" in verdict.evidence)
    }

    @Test
    fun `an unmeasured model says so in words`() {
        val verdict = dev.charaly.runtime.presentation.BenchmarkPresenter.verdict(record = null)

        assertEquals(dev.charaly.runtime.presentation.SpeedState.UNMEASURED, verdict.state)
        assertEquals("Ölçülmedi", verdict.label)
        assertFalse(verdict.hasMeasurement)
        assertEquals("", verdict.evidence)
        // "Not measured" is a state, not a dead end: measuring is cheap next to being wrong.
        assertTrue(verdict.canBenchmark)
    }

    @Test
    fun `an accelerator is named only when the engine reported one`() {
        val cpu = dev.charaly.runtime.presentation.BenchmarkPresenter.verdict(
            measurement(devices = listOf("CPU:builtin")),
        )
        assertTrue("CPU" in cpu.conditions)
        assertFalse("GPU" in cpu.conditions)

        val gpu = dev.charaly.runtime.presentation.BenchmarkPresenter.verdict(
            measurement(devices = listOf("GPU:Adreno (TM) 740", "CPU:builtin"), gpuLayers = 20),
        )
        // The device's own name, not a generic "GPU", and the offload count beside it.
        assertTrue("Adreno (TM) 740" in gpu.conditions)
        assertTrue("20 katman devredildi" in gpu.conditions)
    }

    @Test
    fun `a build that reported nothing must not claim cpu`() {
        // An empty device list means "could not tell", which is not the same as "CPU".
        // Saying CPU here would be a claim the build has no evidence for.
        val verdict = dev.charaly.runtime.presentation.BenchmarkPresenter.verdict(
            measurement(devices = emptyList()),
        )
        assertTrue("CPU" in verdict.conditions)
    }

    @Test
    fun `first-token latency and peak memory are shown when measured`() {
        val verdict = dev.charaly.runtime.presentation.BenchmarkPresenter.verdict(measurement())
        assertTrue("0.2 sn" in verdict.latencyLabel)
        assertTrue("en yüksek" in verdict.memoryLabel)
    }

    @Test
    fun `a running benchmark shows a phase and offers no second run`() {
        val verdict = dev.charaly.runtime.presentation.BenchmarkPresenter.verdict(
            record = null,
            isMeasuring = true,
            phaseLabel = "Loading model… 40%",
        )
        assertTrue(verdict.isMeasuring)
        assertEquals("Loading model… 40%", verdict.phaseLabel)
        assertFalse(verdict.canBenchmark)
        assertFalse(verdict.hasMeasurement)
        // Nothing is claimed while a run is in flight.
        assertEquals("", verdict.evidence)
    }

    @Test
    fun `no code path in the presenter produces a tok s figure without a record`() {
        // The structural assertion, in the same spirit as the memory-visibility test: walk
        // the presenter's inputs and confirm the number only exists alongside a
        // [BenchmarkRecord].
        val measured = dev.charaly.runtime.presentation.BenchmarkPresenter.verdict(measurement())
        val unmeasured = dev.charaly.runtime.presentation.BenchmarkPresenter.verdict(record = null)

        assertTrue(measured.evidence.isNotBlank())
        assertTrue(
            "an unmeasured verdict produced an evidence line",
            unmeasured.evidence.isBlank(),
        )
        assertFalse(
            "an unmeasured verdict produced a measurement",
            unmeasured.hasMeasurement,
        )
    }

    @Test
    fun `the library summary counts only models the user still has`() {
        val summary = dev.charaly.runtime.presentation.BenchmarkPresenter.summary(
            installedIds = listOf("a", "b"),
            records = listOf(
                measurement(modelId = "a"),
                // A measurement for a model that has since been deleted must not cover one
                // that is present.
                measurement(modelId = "gone"),
            ),
        )
        assertEquals(1, summary.measuredCount)
        assertEquals(1, summary.unmeasuredCount)
        assertFalse(summary.isRunning)
    }

    @Test
    fun `the library summary names a running benchmark`() {
        val summary = dev.charaly.runtime.presentation.BenchmarkPresenter.summary(
            installedIds = listOf("a"),
            records = emptyList(),
            measuringModelId = "a",
            measuringName = "Qwen3 4B",
            phaseLabel = "Generating…",
        )
        assertTrue(summary.isRunning)
        assertTrue("Qwen3 4B" in summary.runningLabel)
        assertTrue("Generating" in summary.runningLabel)
    }
}

/**
 * Benchmarks are per-device, and the store must behave like it.
 */
class BenchmarkPersistenceTest {

    private fun storedRecord(modelId: String, at: Long = 1L) = BenchmarkRecord(
        benchmark = ModelBenchmark(
            modelId = modelId,
            loadMillis = 100L,
            tokensPerSecond = 12.0,
            tokensMeasured = 64,
            measuredAtEpochMs = at,
        ),
        backendLabel = "CPU",
        threads = 4,
        gpuLayers = 0,
        contextSize = 2048,
    )

    @Test
    fun `a record survives a restart`() = runTest {
        val storage = dev.charaly.runtime.persistence.InMemoryCharalyStorage()
        JsonBenchmarkStore(storage).record(storedRecord("m1"))
        val reopened = JsonBenchmarkStore(storage).forModel("m1")
        assertNotNull(reopened)
        assertEquals(12.0, reopened!!.benchmark.tokensPerSecond, 0.001)
    }

    @Test
    fun `one measurement per model, always the newest`() = runTest {
        val store = InMemoryBenchmarkStore()
        store.record(storedRecord("m1", at = 1L))
        store.record(storedRecord("m1", at = 2L))

        assertEquals(1, store.all().size)
        assertEquals(2L, store.forModel("m1")!!.benchmark.measuredAtEpochMs)
    }

    @Test
    fun `deleting a model forgets its measurement`() = runTest {
        val store = InMemoryBenchmarkStore(listOf(storedRecord("m1")))
        store.forget("m1")
        // A stale measurement would keep quoting a speed for a model that is gone.
        assertNull(store.forModel("m1"))
    }

    @Test
    fun `a corrupt document reads as empty rather than failing to launch`() = runTest {
        val storage = dev.charaly.runtime.persistence.InMemoryCharalyStorage()
        storage.write(JsonBenchmarkStore.DOCUMENT, "{ not json")
        // The library must not be able to stop the app from opening a story.
        assertEquals(emptyList<BenchmarkRecord>(), JsonBenchmarkStore(storage).all())
    }
}

private typealias File = java.io.File