package dev.charaly.app.storyloop

import dev.charaly.runtime.inference.InferenceEngine
import dev.charaly.runtime.inference.InferenceRequest
import dev.charaly.runtime.inference.InferenceResult
import dev.charaly.runtime.inference.LoadOutcome
import dev.charaly.runtime.inference.ModelLoadRequest
import dev.charaly.runtime.inference.StreamChunk
import dev.charaly.runtime.model.BenchmarkObservation
import dev.charaly.runtime.model.LocalInferenceEngine
import dev.charaly.runtime.session.TurnPerf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach

/**
 * The four latency phases of one turn, measured from outside the engine.
 *
 * ## Why a decorator rather than a field on [TurnPerf]
 *
 * `TurnPerf` is produced by the runtime, which starts its own clock when collection
 * begins. Two of the five phases the brief asks for happen *before* that: the hop from
 * the composer's Send to the runtime, and the hop from the runtime into the engine.
 * Only an observer that sits around the engine can see the second one, and only an
 * observer outside the runtime can see the first.
 *
 * So this decorator records its own wall clock at four moments - the call entering
 * `stream`, the first chunk leaving it, the last chunk leaving it, and the flow
 * returning - and hands the numbers to the report. It changes nothing about what the
 * engine does; it is a stopwatch bolted to the side of the pipe.
 *
 * `System.nanoTime()` throughout, never wall-clock: the report is durations, and a
 * clock adjustment mid-run would turn a duration into a lie.
 */
class Phase10TurnProbe(
    private val delegate: LocalInferenceEngine,
) : LocalInferenceEngine {

    /** Set when `stream` is entered; 0 otherwise. */
    @Volatile
    var streamEnteredNanos: Long = 0L

    @Volatile
    var firstChunkNanos: Long = 0L

    @Volatile
    var lastChunkNanos: Long = 0L

    @Volatile
    var streamReturnedNanos: Long = 0L

    /** Chunks seen by the observer. Compared with the runtime's own count. */
    @Volatile
    var chunkCount: Int = 0

    fun reset() {
        streamEnteredNanos = 0L
        firstChunkNanos = 0L
        lastChunkNanos = 0L
        streamReturnedNanos = 0L
        chunkCount = 0
    }

    override fun stream(request: InferenceRequest): Flow<StreamChunk> {
        streamEnteredNanos = System.nanoTime()
        return delegate.stream(request)
            .onEach { chunk ->
                if (chunk.done) {
                    streamReturnedNanos = System.nanoTime()
                    return@onEach
                }
                chunkCount++
                if (firstChunkNanos == 0L) firstChunkNanos = System.nanoTime()
                lastChunkNanos = System.nanoTime()
            }
    }

    override suspend fun loadModel(request: ModelLoadRequest): LoadOutcome = delegate.loadModel(request)
    override suspend fun unloadModel() = delegate.unloadModel()
    override suspend fun generate(request: InferenceRequest): InferenceResult = delegate.generate(request)
    override fun stop() = delegate.stop()
    override fun isLoaded(): Boolean = delegate.isLoaded()
    override fun modelInfo() = delegate.modelInfo()
    override fun configThreads(): Int = delegate.configThreads()
    override fun configGpuLayers(): Int = delegate.configGpuLayers()
    override fun lastDiagnostic(): String = delegate.lastDiagnostic()
    override fun lastTimings() = delegate.lastTimings()
    override fun configContextSize(): Int = delegate.configContextSize()
    override suspend fun benchmark(prompt: String, maxTokens: Int): BenchmarkObservation? =
        delegate.benchmark(prompt, maxTokens)
    override fun computeDevices(): List<String> = delegate.computeDevices()
}

/**
 * One turn's numbers, in the five phases the brief asks for, plus the runtime's own
 * breakdown for context.
 */
data class Phase10Timing(
    val turn: Int,
    /** 1. Send -> the inference call. Includes dispatch, residency and prompt assembly. */
    val sendToInferenceMillis: Long,
    /** 2. Prompt and context preparation, as the runtime measured it. */
    val contextBuildMillis: Long,
    /** 3. Inference start -> the first token the user could see. */
    val ttftMillis: Long,
    /** 3b. Of that: the engine's own tokenize + prompt evaluation. */
    val promptEvalMillis: Long?,
    val tokenizeMillis: Long?,
    val evalMillis: Long?,
    /** 4. First token -> last token. */
    val firstToLastMillis: Long,
    /** 5. Send -> the runtime's Finished update. */
    val totalMillis: Long,
    /** What is left after the decode: parsing, world events, persistence. */
    val postProcessMillis: Long,
    val residencyMillis: Long,
    val modelLoadMillis: Long?,
    val contextChars: Int,
    val promptTokens: Int?,
    val generatedTokens: Int?,
    val reusedTokens: Int?,
    val tokensPerSecond: Double?,
) {
    fun row(): String = buildString {
        appendLine("  turn $turn")
        appendLine("    1 send->inference call : ${sendToInferenceMillis}ms")
        appendLine("    2 context/prompt build: ${contextBuildMillis}ms  (chars=$contextChars)")
        appendLine(
            "    3 TTFT                 : ${ttftMillis}ms " +
                "(engine prompt_eval=${promptEvalMillis ?: "?"}ms " +
                "tokenize=${tokenizeMillis ?: "?"}ms eval=${evalMillis ?: "?"}ms)",
        )
        appendLine("    4 first->last token    : ${firstToLastMillis}ms")
        appendLine(
            "    5 total                : ${totalMillis}ms " +
                "(residency=${residencyMillis}ms post=${postProcessMillis}ms)",
        )
        appendLine(
            "      tokens               : prompt=${promptTokens ?: "?"} " +
                "reused=${reusedTokens ?: "?"} generated=${generatedTokens ?: "?"} " +
                "tok/s=${tokensPerSecond?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "?"}",
        )
    }

    companion object {
        /**
         * Derives the five phases from the probe's own clocks plus the runtime's report.
         *
         * `sendAtNanos` is taken by the caller at the instant it calls `respond`, which is
         * what "the Send button" means in a host run: there is no button on a phone here,
         * and pretending otherwise would be a measurement of something else.
         */
        fun of(
            turn: Int,
            sendAtNanos: Long,
            probe: Phase10TurnProbe,
            perf: TurnPerf?,
            finishedAtNanos: Long,
        ): Phase10Timing {
            val engineEntry = probe.streamEnteredNanos
            val first = probe.firstChunkNanos
            val last = probe.lastChunkNanos
            val returned = probe.streamReturnedNanos
            fun ms(from: Long, to: Long): Long =
                if (from <= 0L || to <= 0L || to < from) -1L else (to - from) / 1_000_000L

            val sendToCall = ms(sendAtNanos, engineEntry)
            val engineTtft = ms(engineEntry, first)
            val firstToLast = ms(first, last)
            val tail = ms(returned, finishedAtNanos)

            return Phase10Timing(
                turn = turn,
                // The engine's own TTFT is the truth for phase 3; the runtime's number is
                // measured from a different origin and is kept only as a cross-check.
                sendToInferenceMillis = sendToCall,
                contextBuildMillis = perf?.contextBuildMillis ?: -1L,
                ttftMillis = if (engineTtft >= 0) engineTtft else (perf?.ttftMillis ?: -1L),
                promptEvalMillis = perf?.promptEvalMillis,
                tokenizeMillis = perf?.tokenizeMillis,
                evalMillis = perf?.evalMillis,
                firstToLastMillis = firstToLast,
                totalMillis = perf?.totalMillis ?: ms(sendAtNanos, finishedAtNanos),
                postProcessMillis = tail,
                residencyMillis = perf?.residencyMillis ?: -1L,
                modelLoadMillis = perf?.modelLoadMillis,
                contextChars = perf?.contextChars ?: -1,
                promptTokens = perf?.promptTokens,
                generatedTokens = perf?.generatedTokens,
                reusedTokens = perf?.reusedTokens,
                tokensPerSecond = perf?.tokensPerSecond,
            )
        }
    }
}

/** Kept so the unused-import checker does not hide a deliberate dependency. */
internal val enginePort: Class<InferenceEngine> = InferenceEngine::class.java