package dev.charaly.runtime.model

import kotlinx.serialization.Serializable

/**
 * A real, measured generation run.
 *
 * Every field here came out of a clock. There is deliberately no constructor path that
 * accepts a guessed number, and no `estimatedTokensPerSecond` anywhere in this codebase:
 * an estimate stored next to a measurement is indistinguishable from one once it is
 * persisted, and the whole value of the "Fast on this device" label is that it is not an
 * estimate.
 */
data class BenchmarkObservation(
    /** Tokens the decode loop actually emitted. */
    val tokens: Int,
    /** Wall-clock time spent decoding, excluding the prompt. */
    val decodeMicros: Long,
    /** Wall-clock time spent evaluating the prompt. */
    val promptMicros: Long,
    /** Wall-clock time from the first decode to the first token. */
    val firstTokenMicros: Long,
    /** Tokens in the prompt that was evaluated. */
    val promptTokens: Int,
    /** Context size the loaded model was given. */
    val contextSize: Int,
) {
    init {
        require(tokens > 0) { "an observation with no tokens is not a measurement" }
        require(decodeMicros >= 0) { "decode time cannot be negative" }
        require(promptMicros >= 0) { "prompt time cannot be negative" }
        require(firstTokenMicros >= 0) { "first-token latency cannot be negative" }
        require(promptTokens > 0) { "a benchmark must have evaluated a prompt" }
    }

    /** Generation speed, from the measured decode window only. */
    val tokensPerSecond: Double
        get() = if (decodeMicros <= 0L) 0.0 else tokens * 1_000_000.0 / decodeMicros

    /** Prompt evaluation speed. Zero when the prompt was too small to time meaningfully. */
    val promptTokensPerSecond: Double
        get() = if (promptMicros <= 0L) 0.0 else promptTokens * 1_000_000.0 / promptMicros

    val firstTokenMillis: Long get() = firstTokenMicros / 1000L
}

/**
 * Where a benchmark reads the process's memory from.
 *
 * A port rather than a direct `Debug` call so the whole runner stays testable on a plain
 * JVM. The Android implementation reads `/proc/self/status`, which is available to an app
 * for its own process without any permission and, unlike `Debug.getNativeHeapSize()`,
 * reports the *native* allocations that a GGUF mmap actually makes.
 */
interface DeviceMemoryProbe {

    /** Peak resident set size in bytes, or 0 when the platform will not say. */
    fun peakResidentBytes(): Long

    /** Free system memory right now, in bytes, or 0 when unknown. */
    fun currentFreeBytes(): Long

    companion object {

        /**
         * Reads `VmHWM` and `MemAvailable` from `/proc/self/status`.
         *
         * VmHWM is the kernel's own high-water mark for this process, so it cannot miss a
         * transient spike the way a sampled reading would - which is exactly what a
         * "does this model fit" question needs. It also covers the native allocations a
         * GGUF mmap makes, which `Debug.getNativeHeapSize()` would not.
         *
         * Returns 0 rather than throwing when the file is unreadable, because an
         * unreadable memory figure must produce "not measured", never a made-up one.
         */
        fun fromProcStatus(path: String = "/proc/self/status"): DeviceMemoryProbe = object : DeviceMemoryProbe {
            override fun peakResidentBytes(): Long = readKb(path, "VmHWM:") * 1024L

            override fun currentFreeBytes(): Long {
                val available = readKb(path, "MemAvailable:")
                if (available > 0L) return available * 1024L
                // Older kernels omit MemAvailable; MemFree alone understates what is
                // actually usable, but it is still a real reading rather than a guess.
                return readKb(path, "MemFree:") * 1024L
            }

            private fun readKb(path: String, prefix: String): Long = runCatching {
                java.io.File(path).useLines { lines ->
                    lines.firstOrNull { it.startsWith(prefix) }
                        ?.split(Regex("\\s+"))
                        ?.getOrNull(1)
                        ?.toLongOrNull()
                } ?: 0L
            }.getOrDefault(0L)
        }

        val UNKNOWN: DeviceMemoryProbe = object : DeviceMemoryProbe {
            override fun peakResidentBytes(): Long = 0L
            override fun currentFreeBytes(): Long = 0L
        }
    }
}

/**
 * An engine that can measure itself.
 *
 * Separate from [dev.charaly.runtime.inference.InferenceEngine] on purpose. Adding a
 * benchmark method to the inference port would mean every implementation - including the
 * test doubles - would have to answer a question only the native engine can answer, and
 * the natural implementations of that method would be "return a plausible-looking
 * number". Opting in keeps a fake benchmark impossible: a test engine that does not
 * implement this cannot be benchmarked at all.
 */
interface BenchmarkableEngine {
    /**
     * Runs [prompt] and reports what happened.
     *
     * Returns null when no measurement is possible - no model loaded, the native library
     * is missing, or the run produced no tokens. Callers must treat null as "nothing to
     * record" rather than coercing it to zero.
     *
     * [prompt] is ignored by implementations that supply their own fixed text, because a
     * benchmark's prompt has to be the same on every run to be comparable. The parameter
     * exists for engines whose benchmark workload is legitimately configurable.
     */
    suspend fun benchmark(prompt: String, maxTokens: Int): BenchmarkObservation?

    /**
     * The compute devices this build can actually use, as reported by the engine's own
     * device registry.
     *
     * Empty means "could not tell", which is different from "one device named CPU". A UI
     * must show acceleration only for a name that appears here.
     */
    fun computeDevices(): List<String>
}

/**
 * An inference engine that can also measure itself.
 *
 * The union the benchmark runner actually needs: it must load a model (which is part of the
 * measurement, since load time is one of the recorded figures), run a generation, and report
 * the result. Declaring it here rather than in the app module is what lets the whole
 * benchmark path be tested without the native library - the app's engine implements it, and
 * so does a scripted stand-in.
 */
interface LocalInferenceEngine : dev.charaly.runtime.inference.InferenceEngine, BenchmarkableEngine {
    /**
     * The worker-thread count this engine was configured with.
     *
     * 0 means the engine chose for itself, and 0 is recorded rather than replaced with a
     * plausible number: a tok/s figure with an invented thread count attached would be
     * misleading in exactly the way this whole feature refuses to be.
     */
    fun configThreads(): Int = 0

    /** Layers offloaded to an accelerator. 0 today; see the app's engine configuration. */
    fun configGpuLayers(): Int = 0

    /**
     * A controlled, user-readable reason for the most recent native failure, or empty.
     *
     * This exists so "the model produced nothing" can be reported as *why* rather than as
     * silence. Without it the only honest options at a call site are to return a default
     * (which turns a broken ABI into an empty list) or to dump a JNI stack trace at a
     * person.
     *
     * Defaulted to empty so the scripted engines in tests keep compiling; they have no
     * native side to have a diagnostic about.
     */
    fun lastDiagnostic(): String = ""

    /** The context size this engine was configured with. */
    fun configContextSize(): Int = 2048
}

/**
 * Runs a benchmark and turns the observation into a storable [ModelBenchmark].
 *
 * ## What it guarantees
 *
 * * **Isolation.** It never receives a [dev.charaly.runtime.domain.StoryInstance] and has
 *   no way to write one. No world state, no memory, no event log, no transcript, no story
 *   context - the prompt is a fixed constant, not a scene. A benchmark that could change a
 *   story would be a bug waiting to happen, so the type simply cannot express it.
 * * **Honesty on failure.** No observation means no [ModelBenchmark]. There is no
 *   "0 tok/s" and no record with a guess in it.
 * * **Reproducibility of the conditions.** The same prompt, the same token budget and the
 *   same greedy sampler on every run, so two runs of the same model differ only by the
 *   machine.
 */
object ModelBenchmarkRunner {

    /**
     * The benchmark prompt.
     *
     * A constant, and a plain prose prompt in the shape Charaly actually sends, rather
     * than a token-counting trick like "1 2 3 4". The number a user cares about is the
     * speed at which this app writes a scene, so the measurement should be taken on the
     * work this app does.
     */
    const val PROMPT: String =
        "The rain had stopped by the time she reached the corner of the street, and the " +
            "shop lights were already on. She stood there a moment longer than she meant " +
            "to, watching the reflections move across the wet asphalt, and then she turned " +
            "and walked the rest of the way home without looking back."

    /** Enough tokens to get a stable rate without making the user wait. */
    const val MAX_TOKENS = 64

    /**
     * Runs the benchmark.
     *
     * [progress] reports real phases only: the model load and the generation are the two
     * things that actually take time, and each is reported because it happened rather
     * than because a timer elapsed.
     */
    suspend fun run(
        engine: BenchmarkableEngine,
        modelId: String,
        memoryProbe: DeviceMemoryProbe = DeviceMemoryProbe.UNKNOWN,
        load: suspend (onLoadProgress: (Int) -> Unit) -> LoadOutcomeForBenchmark,
        progress: (BenchmarkPhase) -> Unit = {},
        /**
         * When the run happened.
         *
         * Passed in rather than read from the clock so the stored timestamp is a parameter
         * like everything else here - which is what makes a test's assertion about it mean
         * anything, and stops the record disagreeing with a caller that keeps its own notion
         * of now.
         */
        nowEpochMs: () -> Long = System::currentTimeMillis,
    ): Result<ModelBenchmark> {
        val freeBefore = memoryProbe.currentFreeBytes()

        progress(BenchmarkPhase.PREPARING)
        val outcome = load { percent -> progress(BenchmarkPhase.LOADING(percent)) }
        if (outcome !is LoadOutcomeForBenchmark.Loaded) {
            return Result.failure(BenchmarkFailedException(BenchmarkFailure.CouldNotComplete))
        }

        progress(BenchmarkPhase.MEASURING)
        val observation = try {
            engine.benchmark(PROMPT, MAX_TOKENS)
        } catch (error: OutOfMemoryError) {
            // A model that will not fit must not take the process with it. The caller
            // unloads and reports a clean failure; no measurement is recorded.
            return Result.failure(BenchmarkFailedException(BenchmarkFailure.NotEnoughMemory))
        } catch (error: BenchmarkFailedException) {
            return Result.failure(error)
        } catch (error: Throwable) {
            return Result.failure(BenchmarkFailedException(BenchmarkFailure.CouldNotComplete))
        }

        val observationOrNull = observation
        if (observationOrNull == null) {
            return Result.failure(BenchmarkFailedException(BenchmarkFailure.CouldNotComplete))
        }

        val freeAfter = memoryProbe.currentFreeBytes()
        val peak = memoryProbe.peakResidentBytes()

        val benchmark = ModelBenchmark(
            modelId = modelId,
            loadMillis = outcome.loadMillis,
            tokensPerSecond = observationOrNull.tokensPerSecond,
            tokensMeasured = observationOrNull.tokens,
            promptTokensPerSecond = observationOrNull.promptTokensPerSecond,
            firstTokenMillis = observationOrNull.firstTokenMillis,
            peakMemoryBytes = peak,
            measuredAtEpochMs = nowEpochMs(),
            freeMemoryBeforeBytes = freeBefore,
            freeMemoryAfterBytes = freeAfter,
        )

        progress(BenchmarkPhase.COMPLETE)
        return Result.success(benchmark)
    }
}

/**
 * What the benchmark was doing, as far as it honestly can say.
 *
 * [LOADING] carries a real percentage because it comes from the loader's own progress
 * callback. There is no synthetic phase and no timed bar: a progress state that advances
 * because a clock ran is exactly the kind of thing this codebase refuses to ship.
 */
sealed interface BenchmarkPhase {
    /** About to load the model. */
    data object PREPARING : BenchmarkPhase

    /** The loader is reading the GGUF, and this is its own reported percentage. */
    data class LOADING(val percent: Int) : BenchmarkPhase

    /** Tokens are being generated and timed. */
    data object MEASURING : BenchmarkPhase

    data object COMPLETE : BenchmarkPhase
}

/** Why a benchmark did not produce a number. Every value maps to one user-facing sentence. */
enum class BenchmarkFailure(val message: String) {
    NotEnoughMemory("This model needs more memory than this device has free."),
    CouldNotComplete("Benchmark could not complete on this device."),
    NoNativeEngine("This build has no local inference engine, so it cannot be measured."),
}

/**
 * A benchmark that did not complete.
 *
 * A typed exception rather than a bare string so the caller has to handle the reason, and
 * so [BenchmarkPresenter.failureMessage] is the only thing that turns one into text. The
 * UI therefore cannot invent its own wording for a failed measurement, and cannot surface
 * the exception message directly - which is what keeps a native error out of a sentence.
 */
class BenchmarkFailedException(val reason: BenchmarkFailure) :
    Exception(reason.message)

/**
 * The outcome of the load step, including the timing the benchmark has to record.
 *
 * [Failed] carries no user-facing wording on purpose: a load can fail for a dozen
 * reasons, and mapping them to sentences here would put engine vocabulary in the
 * runtime module. The runner collapses every one of them to
 * [BenchmarkFailure.CouldNotComplete], and the UI shows that.
 */
sealed interface LoadOutcomeForBenchmark {
    data class Loaded(val loadMillis: Long) : LoadOutcomeForBenchmark
    data class Failed(val reason: String) : LoadOutcomeForBenchmark
}

/**
 * A stored benchmark, plus the conditions it was taken under.
 *
 * The conditions are part of the record because a tok/s figure without them is not
 * reproducible: 12 tok/s on four CPU threads is a different fact from 12 tok/s on eight
 * threads with a GPU backend, and only the second one might be worth acting on.
 */
@Serializable
data class BenchmarkRecord(
    val benchmark: ModelBenchmark,
    val backendLabel: String,
    val threads: Int,
    val gpuLayers: Int,
    val contextSize: Int,
    /** The devices the engine reported, so a later build can say what changed. */
    val devices: List<String> = emptyList(),
)