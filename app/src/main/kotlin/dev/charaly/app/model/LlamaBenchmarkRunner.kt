package dev.charaly.app.model

import dev.charaly.app.inference.LocalLlamaInferenceEngine
import dev.charaly.runtime.inference.LoadOutcome
import dev.charaly.runtime.inference.ModelLoadRequest
import dev.charaly.runtime.model.BenchmarkFailedException
import dev.charaly.runtime.model.BenchmarkFailure
import dev.charaly.runtime.model.BenchmarkPhase
import dev.charaly.runtime.model.BenchmarkRecord
import dev.charaly.runtime.model.BenchmarkStore
import dev.charaly.runtime.model.DeviceMemoryProbe
import dev.charaly.runtime.model.InstalledModel
import dev.charaly.runtime.model.LoadOutcomeForBenchmark
import dev.charaly.runtime.model.ModelBenchmark
import dev.charaly.runtime.model.ModelBenchmarkRunner
import dev.charaly.runtime.model.ModelRegistry

/**
 * The only code path in Charaly that produces a tok/s figure.
 *
 * ## What a benchmark is allowed to touch
 *
 * A benchmark must not be able to change a story. That is guaranteed by construction
 * rather than by discipline: this class receives a model and a store, and there is no
 * parameter, field or read through which a
 * [dev.charaly.runtime.domain.StoryInstance] could reach it. No world state, no memories,
 * no event log, no transcript, no story context - the prompt is a constant in the runtime
 * module. Nothing it does can be observed by a story, because nothing it does is connected
 * to one.
 *
 * ## The one-model-at-a-time problem
 *
 * The app keeps a single model resident, so a benchmark and a story cannot both hold one.
 * The runner therefore records what was loaded, lets the benchmark take the slot, and puts
 * the previous model back afterwards. If it cannot put it back, that is reported as a
 * failure rather than swallowed - leaving a story pointed at no model is worse than
 * declining to run a benchmark.
 *
 * ## Why it says CPU today
 *
 * `LocalLlamaInferenceEngine.EngineConfig.gpuLayers` is 0 and this build compiles CPU
 * backends only. [devices] reports whatever ggml's own registry says, and the UI renders
 * only that, so this build cannot display "GPU accelerated" - not because a flag was left
 * off, but because there is no GPU backend for it to report.
 */
class LlamaBenchmarkRunner(
    private val registry: ModelRegistry,
    private val store: BenchmarkStore,
    /**
     * The engine to measure.
     *
     * Takes the [dev.charaly.runtime.model.LocalInferenceEngine] port rather than the concrete
     * llama.cpp engine, so the whole runner - loading, timing, recording, restoring - is
     * testable without the native library. A scripted engine that reports a real observation
     * stands in for the native one; what it does not stand in for is the timing itself, which
     * `ModelBenchmarkRunnerTest` asserts against a recorded observation.
     */
    private val engineProvider: () -> dev.charaly.runtime.model.LocalInferenceEngine = {
        LocalLlamaInferenceEngine()
    },
    private val memoryProbe: DeviceMemoryProbe = DeviceMemoryProbe.fromProcStatus(),
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {

    /**
     * Measures one model and records the result.
     *
     * Never throws for a measurement that did not happen. Every failure path returns a
     * [BenchmarkFailedException] carrying one of the few sentences the UI is allowed to
     * show, so a native error string cannot reach a player.
     */
    suspend fun run(
        model: InstalledModel,
        onPhase: (BenchmarkPhase) -> Unit = {},
    ): Result<ModelBenchmark> {
        val engine = engineProvider()
        val resident = engine.modelInfo()?.let { info -> registry.list().firstOrNull { it.id == info.id } }

        val result = try {
            ModelBenchmarkRunner.run(
                engine = engine,
                modelId = model.id,
                memoryProbe = memoryProbe,
                progress = onPhase,
                load = { onLoadProgress ->
                    val startedAt = System.nanoTime()
                    val outcome = engine.loadModel(
                        ModelLoadRequest(
                            path = model.absolutePath,
                            displayName = model.displayName,
                            progress = { stage -> onLoadProgress(stage.percent) },
                        ),
                    )
                    val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L
                    when (outcome) {
                        is LoadOutcome.Loaded -> LoadOutcomeForBenchmark.Loaded(elapsedMillis)
                        // The engine's wording is deliberately dropped here: a load can fail
                        // for a dozen reasons and mapping them into sentences belongs to the
                        // UI, which has one sentence for all of them.
                        is LoadOutcome.Failed -> LoadOutcomeForBenchmark.Failed(
                            outcome.error.message.orEmpty().ifBlank { "load failed" },
                        )
                    }
                },
                // The same clock the record is stamped with, so the two cannot disagree.
                nowEpochMs = nowEpochMs,
            )
        } catch (error: BenchmarkFailedException) {
            Result.failure(error)
        } catch (error: Throwable) {
            Result.failure(BenchmarkFailedException(BenchmarkFailure.CouldNotComplete))
        }

        val restored = restoreResidentModel(engine, resident)

        return result
            .onSuccess { benchmark ->
                val devices = engine.computeDevices()
                store.record(
                    BenchmarkRecord(
                        benchmark = benchmark,
                        backendLabel = describeBackend(devices),
                        threads = engine.configThreads(),
                        gpuLayers = engine.configGpuLayers(),
                        contextSize = model.effectiveContextTokens(),
                        devices = devices,
                    ),
                )
                markLoadSucceeded(model, benchmark.measuredAtEpochMsOr(nowEpochMs()))
            }
            .fold(
                onSuccess = { Result.success(it) },
                onFailure = { failure ->
                    if (!restored) {
                        // The slot could not be restored, so the story behind it may be
                        // broken. Saying so is the difference between a benchmark the user
                        // retries and a bug they report.
                        Result.failure(BenchmarkFailedException(BenchmarkFailure.CouldNotComplete))
                    } else {
                        Result.failure(failure)
                    }
                },
            )
    }

    /** The stored measurement for a model, or null when this device has never run one. */
    suspend fun recordFor(modelId: String): BenchmarkRecord? = store.forModel(modelId)

    suspend fun allRecords(): List<BenchmarkRecord> = store.all()

    /**
     * Every stored measurement, keyed by model id.
     *
     * The shape the UI state holds, so the keying exists in exactly one place and a screen
     * cannot index a list by position and get a different model's speed.
     */
    suspend fun recordsByModelId(): Map<String, BenchmarkRecord> =
        allRecords().associateBy { record -> record.benchmark.modelId }

    /** Forgets a model's measurement. Called when the model itself is deleted. */
    suspend fun forget(modelId: String) = store.forget(modelId)

    /**
     * What the engine's own device registry reports.
     *
     * Read from ggml rather than from a constant, so it cannot disagree with the build.
     * An empty list means "could not tell", which the UI must render as unknown rather
     * than as "CPU".
     */
    fun devices(): List<String> = runCatching { engineProvider().computeDevices() }.getOrDefault(emptyList())

    // -------------------------------------------------------------------- private

    /**
     * Puts back whatever was resident before the benchmark.
     *
     * Returns false when the slot could not be restored, which the caller treats as a
     * failure. Leaving the resident model loaded is correct: the benchmark model is now
     * the one in memory, and unloading would leave a story with no model at all.
     */
    private suspend fun restoreResidentModel(
        engine: dev.charaly.runtime.inference.InferenceEngine,
        resident: InstalledModel?,
    ): Boolean {
        if (resident == null) {
            runCatching { engine.unloadModel() }
            return true
        }
        if (engine.modelInfo()?.id == resident.id) return true
        val restored = runCatching {
            engine.loadModel(
                ModelLoadRequest(path = resident.absolutePath, displayName = resident.displayName),
            )
        }.getOrNull()
        return restored is LoadOutcome.Loaded
    }

    private suspend fun markLoadSucceeded(model: InstalledModel, atEpochMs: Long) {
        runCatching {
            registry.update(
                model.copy(
                    compatibility = model.compatibility.copy(
                        loadFailed = false,
                        failureReason = "",
                        lastLoadedAtEpochMs = atEpochMs,
                    ),
                ),
            )
        }
    }

    private fun describeBackend(devices: List<String>): String {
        val accelerator = devices.firstOrNull {
            it.startsWith("GPU:") || it.startsWith("ACCELERATOR:")
        }?.substringAfter(':')
        return when {
            !accelerator.isNullOrBlank() -> accelerator
            devices.any { it.startsWith("CPU:") } -> "CPU"
            // "unknown" rather than an empty string: a record has to say the engine could
            // not tell, so nobody later reads the absence as a CPU measurement.
            else -> "unknown"
        }
    }
}

private fun ModelBenchmark.measuredAtEpochMsOr(fallback: Long): Long =
    if (measuredAtEpochMs > 0L) measuredAtEpochMs else fallback