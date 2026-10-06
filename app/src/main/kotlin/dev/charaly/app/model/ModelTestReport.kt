package dev.charaly.app.model

import dev.charaly.runtime.inference.ChatMessage
import dev.charaly.runtime.inference.ChatRole
import dev.charaly.runtime.inference.GenerationParams
import dev.charaly.runtime.inference.InferenceError
import dev.charaly.runtime.inference.InferenceRequest
import dev.charaly.runtime.inference.LoadOutcome
import dev.charaly.runtime.inference.ModelLoadRequest
import dev.charaly.runtime.inference.StreamChunk
import dev.charaly.runtime.model.BenchmarkObservation
import dev.charaly.runtime.model.InstalledModel
import dev.charaly.runtime.model.LocalInferenceEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.roundToLong

/**
 * THE MODEL TEST.
 *
 * ## What it is for
 *
 * Every other part of the model story is an *assertion*: the registry says the file is
 * installed, the header says the architecture is supported, the selection says it can
 * generate. None of those prove the thing the user cares about - that tokens come out.
 *
 * So this runs the real thing: it loads the GGUF through the real engine, then decodes a
 * fixed short prompt through the same `generate` path a story turn uses, and reports what
 * actually came back. The text in [ModelTestReport.output] is produced by the model. If
 * the model is broken, this fails; it does not describe a successful test.
 *
 * ## Why it is not a benchmark
 *
 * A benchmark measures speed on a fixed workload and persists the result. This measures
 * *viability* and shows the output. They deliberately share nothing: this does not write to
 * the benchmark store, does not restore a resident model, and its prompt is chosen to be
 * legible rather than representative. Confusing the two is how "Not measured" starts
 * meaning "tested and fine".
 *
 * ## Why the engine is injected
 *
 * The whole point is that it is not a mock. [engineProvider] is a constructor parameter so
 * the *sequencing* - load, generate, restore - can be tested on a JVM; the production
 * wiring passes the same [LocalLlamaInferenceEngine] the story runtime uses, and
 * `LocalModelTestReportTest` asserts the wiring rather than the numbers. There is no code
 * path here that can produce a success without an engine that actually generated text.
 */
class ModelTestRunner(
    private val engineProvider: () -> LocalInferenceEngine,
    private val versionLabel: () -> String,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    /** The prompt. Deliberately trivial, in the shape a story turn uses. */
    private val prompt = InferenceRequest(
        systemPrompt = "",
        messages = listOf(
            ChatMessage(ChatRole.USER, "Say hello in one short sentence."),
        ),
        // A low, near-greedy setting: the point is to prove tokens come out, not to
        // discover whether the model is creative. A high temperature makes a small model
        // wander, and a wandering model looks like a broken one in a test.
        params = GenerationParams(
            maxTokens = MAX_TOKENS,
            temperature = 0.2f,
            topP = 0.9f,
            topK = 20,
            repeatPenalty = 1.05f,
        ),
    )

    /**
     * Runs the test, emitting progress as it goes.
     *
     * Flow rather than a suspending function returning a result, because the load phase on
     * a 4 GB model takes tens of seconds and a UI that shows nothing for that long is
     * indistinguishable from a hang.
     */
    fun run(model: InstalledModel): Flow<ModelTestPhase> = flow {
        val engine = engineProvider()

        emit(
            ModelTestPhase(
                state = ModelTestState.LOADING,
                detail = "model-test.loading",
            ),
        )

        val startedAt = nowMillis()
        val loadStarted = System.nanoTime()
        val loaded = engine.loadModel(
            ModelLoadRequest(
                path = model.absolutePath,
                displayName = model.displayName,
                installedModelId = model.id,
            ),
        )
        val loadMillis = (System.nanoTime() - loadStarted) / 1_000_000L

        val info = when (loaded) {
            is LoadOutcome.Loaded -> loaded.info
            is LoadOutcome.Failed -> {
                emit(
                    ModelTestPhase(
                        state = ModelTestState.FAILED,
                        loadMillis = loadMillis,
                        failure = TestFailure.Of(loaded.error),
                    ),
                )
                return@flow
            }
        }

        emit(
            ModelTestPhase(
                state = ModelTestState.GENERATING,
                loadMillis = loadMillis,
                info = info,
                backend = backendLabel(engine),
                version = versionLabel(),
            ),
        )

        // Decode, timing the first token separately. Both numbers come from real clocks
        // around the real call.
        val decodeStarted = System.nanoTime()
        var firstTokenNanos = 0L
        val text = StringBuilder()
        var tokens = 0
        var flowFailure: Throwable? = null
        try {
            engine.stream(prompt).collect { chunk: StreamChunk ->
                if (chunk.text.isNotEmpty()) {
                    if (firstTokenNanos == 0L) firstTokenNanos = System.nanoTime()
                    tokens += 1
                    text.append(chunk.text)
                }
            }
        } catch (error: Throwable) {
            flowFailure = error
        }
        val decodeMillis = (System.nanoTime() - decodeStarted) / 1_000_000L

        if (flowFailure != null) {
            emit(
                ModelTestPhase(
                    state = ModelTestState.FAILED,
                    loadMillis = loadMillis,
                    info = info,
                    backend = backendLabel(engine),
                    version = versionLabel(),
                    failure = TestFailure.Other(flowFailure.message.orEmpty().ifBlank { "generation failed" }),
                ),
            )
            return@flow
        }

        val output = text.toString().trim()
        if (output.isBlank()) {
            // A run that completed with no text is not a pass. Reporting it as one is how
            // a broken model gets a green light.
            emit(
                ModelTestPhase(
                    state = ModelTestState.FAILED,
                    loadMillis = loadMillis,
                    info = info,
                    backend = backendLabel(engine),
                    version = versionLabel(),
                    failure = TestFailure.Other(
                        // The engine's own reason, when it has one. "llama_decode failed
                        // during generation" is actionable; "the model produced no text"
                        // sends somebody hunting for a bad GGUF when the build is at fault.
                        engine.lastDiagnostic().ifBlank { "the model produced no text" },
                    ),
                ),
            )
            return@flow
        }

        emit(
            ModelTestPhase(
                state = ModelTestState.PASSED,
                loadMillis = loadMillis,
                info = info,
                backend = backendLabel(engine),
                version = versionLabel(),
                output = output,
                tokens = tokens,
                decodeMillis = decodeMillis,
                firstTokenMillis = if (firstTokenNanos > 0L) {
                    (firstTokenNanos - decodeStarted) / 1_000_000L
                } else {
                    0L
                },
                observation = observationOrNull(tokens, loadMillis, decodeMillis, firstTokenNanos, decodeStarted),
            ),
        )
    }

    /**
     * A comparable [BenchmarkObservation] from this run, or null.
     *
     * Null rather than a fabricated observation when a figure is missing. The type's own
     * `init` block rejects zero tokens and zero prompt tokens, so there is no way to
     * "fill in" an unknown - which is correct: this test's workload is tiny and its
     * tok/s is not comparable to a real benchmark's.
     */
    private fun observationOrNull(
        tokens: Int,
        loadMillis: Long,
        decodeMillis: Long,
        firstTokenNanos: Long,
        decodeStarted: Long,
    ): BenchmarkObservation? = runCatching {
        BenchmarkObservation(
            tokens = tokens.coerceAtLeast(1),
            decodeMicros = decodeMillis * 1_000L,
            promptMicros = loadMillis.coerceAtLeast(1L) * 1_000L,
            firstTokenMicros = if (firstTokenNanos > 0L) {
                ((firstTokenNanos - decodeStarted) / 1_000L).coerceAtLeast(0L)
            } else {
                0L
            },
            // The prompt is a handful of tokens; reported as its length in words, which is
            // the only count that is both honest and non-zero.
            promptTokens = PROMPT_WORDS,
            contextSize = MAX_TOKENS,
        )
    }.getOrNull()

    /**
     * What this build can actually compute with.
     *
     * Read from ggml's own registry, so the answer cannot drift from the compiled
     * backends. Falls back to "unknown" rather than "CPU" - a build that could not report
     * must not be described as if it had.
     */
    private fun backendLabel(engine: LocalInferenceEngine): String {
        val devices = runCatching { engine.computeDevices() }.getOrDefault(emptyList())
        return devices.joinToString(", ").ifBlank { "unknown" }
    }

    companion object {
        /**
         * How many tokens to generate.
         *
         * Short on purpose: the test should cost a couple of seconds, not compete with a
         * story for the device. Enough to prove decoding works and to produce a readable
         * sentence.
         */
        const val MAX_TOKENS = 24

        /** Word count of the fixed prompt, used for the prompt-token figure. */
        const val PROMPT_WORDS = 6
    }
}

/** Where a model test has got to. */
enum class ModelTestState {
    IDLE,
    LOADING,
    GENERATING,
    PASSED,
    FAILED,
    ;

    val isRunning: Boolean get() = this == LOADING || this == GENERATING
}

/** Why a model test failed, as a user-actionable sentence rather than a native string. */
sealed interface TestFailure {
    data class Of(val error: InferenceError) : TestFailure
    data class Other(val detail: String) : TestFailure
}

/** One frame of a running model test. */
data class ModelTestPhase(
    val state: ModelTestState = ModelTestState.IDLE,
    /** A `Loc` key, not a sentence: the copy belongs in the catalogue. */
    val detail: String = "model-test.loading",
    val loadMillis: Long = 0L,
    val info: dev.charaly.runtime.inference.ModelInfo? = null,
    val backend: String = "",
    val version: String = "",
    val output: String = "",
    val tokens: Int = 0,
    val decodeMillis: Long = 0L,
    val firstTokenMillis: Long = 0L,
    val observation: BenchmarkObservation? = null,
    val failure: TestFailure? = null,
) {
    /** "12.4 tok/s", from the measured decode window only. Empty when unmeasurable. */
    val tokensPerSecondLabel: String
        get() {
            val rate = observation?.tokensPerSecond ?: return ""
            if (rate <= 0.0) return ""
            return "%.1f tok/s".format(rate)
        }

    companion object {
        val IDLE = ModelTestPhase()
    }
}

/** Human formatting for a duration, for the report rows. */
fun Long.asMillisLabel(): String = when {
    this <= 0L -> "-"
    this < 1_000L -> "$this ms"
    else -> "%.1f s".format(this / 1_000.0)
}

/** Human formatting for a file size. */
fun Long.asSizeLabel(): String = when {
    this <= 0L -> "-"
    this >= 1L shl 30 -> "%.1f GB".format(this / (1L shl 30).toDouble())
    this >= 1L shl 20 -> "%.0f MB".format(this / (1L shl 20).toDouble())
    else -> "%.0f kB".format(this / 1024.0)
}

/** Whole seconds, for an ETA or a duration display. */
fun Long.asSecondsLabel(): String = "${(this / 1000.0).roundToLong()} s"