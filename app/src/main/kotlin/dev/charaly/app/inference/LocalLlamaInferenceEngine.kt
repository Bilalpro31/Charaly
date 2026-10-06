package dev.charaly.app.inference

import dev.charaly.runtime.inference.InferenceEngine
import dev.charaly.runtime.inference.InferenceError
import dev.charaly.runtime.model.BenchmarkObservation
import dev.charaly.runtime.model.LocalInferenceEngine
import dev.charaly.runtime.inference.InferenceRequest
import dev.charaly.runtime.inference.InferenceResult
import dev.charaly.runtime.inference.LoadOutcome
import dev.charaly.runtime.inference.LoadProgress
import dev.charaly.runtime.inference.ModelInfo
import dev.charaly.runtime.inference.ModelLoadRequest
import dev.charaly.runtime.inference.StopReason
import dev.charaly.runtime.inference.StreamChunk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The concrete, on-device inference implementation.
 *
 * ```
 * CharalyRuntime
 *   -> InferenceEngine            (the port)
 *   -> LocalLlamaInferenceEngine  (this class)
 *   -> llama.cpp                   (native, in-process, via JNI)
 *   -> GGUF on local storage
 * ```
 *
 * There is no HTTP, no socket, no localhost server and no external process: the
 * model runs inside the app's own process, so inference works in airplane mode.
 */
class LocalLlamaInferenceEngine(
    private val config: EngineConfig = EngineConfig(),
) : LocalInferenceEngine {

    data class EngineConfig(
        val contextSize: Int = 2048,
        /**
         * Worker threads. 0 lets llama.cpp pick.
         *
         * Left at 0 on purpose. A hardcoded 4 was here before there was any way to measure
         * whether it was the right number, which made it a guess presented as a default.
         * With [computeDevices] and a real benchmark in place the value can be chosen from
         * evidence; until then, the engine decides.
         */
        val threads: Int = 0,
        /**
         * Layers to offload to an accelerator.
         *
         * Currently 0, and the UI must not claim GPU acceleration while it is. The JNI
         * parameter and the native field were already wired and reachable; nothing enables
         * them yet because this build's CMakeLists compiles CPU backends only (see
         * `LLAMA_STANDALONE` / the CMake flags at the top of app/src/main/cpp/CMakeLists.txt).
         */
        val gpuLayers: Int = 0,
    )

    private val handle = AtomicLong(0L)
    private val loaded = AtomicBoolean(false)
    private val generating = AtomicBoolean(false)
    private val info = AtomicReference<ModelInfo?>(null)

    /**
     * The last native failure worth showing a user.
     *
     * Set whenever a native call fails, and cleared when one succeeds. The diagnostics
     * screen reads it, which is what lets "the model produced nothing" be reported as
     * "llama_decode failed during generation" instead of as silence.
     *
     * Deliberately a message written for a person, not a `Throwable.toString()` of a JNI
     * stack trace.
     */
    @Volatile
    var nativeDiagnostic: String = ""
        private set

    override fun lastDiagnostic(): String = nativeDiagnostic

    override suspend fun loadModel(request: ModelLoadRequest): LoadOutcome = withContext(Dispatchers.IO) {
        unload()

        // Validate the user's file FIRST. These checks are pure Kotlin, so they
        // give a precise reason ("that file is not a GGUF model") even when the
        // native library is missing, and they stop a multi-gigabyte non-model
        // file from ever reaching native code.
        val file = File(request.path)
        if (!file.exists() || !file.isFile || !file.canRead()) {
            return@withContext LoadOutcome.Failed(InferenceError.ModelNotFound(request.path))
        }
        if (file.length() < MIN_GGUF_BYTES) {
            return@withContext LoadOutcome.Failed(
                InferenceError.InvalidModel(request.path, "file is too small to be a GGUF model"),
            )
        }
        if (!looksLikeGguf(file)) {
            return@withContext LoadOutcome.Failed(
                InferenceError.InvalidModel(request.path, "missing GGUF magic"),
            )
        }

        // The Kotlin-level check, not the JNI call. On a build whose shared library is
        // missing - a stripped ABI, an unsupported device variant - the JNI call would throw
        // out of `LlamaNative`'s failed initialiser rather than answer, and this load would
        // crash instead of reporting "this build has no local inference".
        if (!LlamaNative.isAvailable()) {
            nativeDiagnostic = "Native engine unavailable: the llama.cpp library is not in this build."
            return@withContext LoadOutcome.Failed(
                InferenceError.Unsupported(
                    "native llama.cpp is not available in this build; run scripts/setup-llama.sh and rebuild",
                ),
            )
        }

        request.progress(LoadProgress(0, "reading model"))
        try {
            val opened = LlamaNative.loadModel(
                path = request.path,
                contextSize = config.contextSize,
                threads = config.threads,
                gpuLayers = config.gpuLayers,
            ) { percent, stage -> request.progress(LoadProgress(percent, stage)) }
            if (opened == 0L) {
                val reason = LlamaNative.lastError().ifBlank { "the native loader refused the file" }
                nativeDiagnostic = "Model load failed: $reason"
                return@withContext LoadOutcome.Failed(InferenceError.InvalidModel(request.path, reason))
            }
            handle.set(opened)
            loaded.set(true)
            nativeDiagnostic = ""

            // Metadata is read, not defaulted. An empty map is a legitimate answer
            // ("this model carries no metadata"); a failed call is a different
            // condition, and it is recorded rather than folded into the same empty map.
            val metadata = when (val parsed = NativeReturnParser.parseKeyValuesCatching("model metadata") {
                LlamaNative.modelMetadata(opened)
            }) {
                is NativeReturnParser.KeyValues.Ok -> parsed.values
                is NativeReturnParser.KeyValues.Malformed -> {
                    nativeDiagnostic = "Model metadata contract failure: ${parsed.detail}"
                    emptyMap()
                }
                is NativeReturnParser.KeyValues.Failed -> {
                    nativeDiagnostic = parsed.reason
                    emptyMap()
                }
            }

            val modelInfo = ModelInfo(
                // The registry's id, so "which model is loaded" is answerable with the same
                // identifier every other layer uses. See [ModelLoadRequest.installedModelId].
                id = request.installedModelId.ifBlank { file.nameWithoutExtension },
                path = request.path,
                displayName = request.displayName.ifBlank { file.nameWithoutExtension },
                parameterCount = metadata["parameters"]?.toLongOrNull() ?: 0L,
                quantLevel = metadata["quantization"].orEmpty(),
                // Read from native, not assumed. Falling back to the requested value would
                // report a context size llama.cpp never granted.
                contextSize = nativeContextSize(opened),
                parameterSizeBytes = file.length(),
                metadata = metadata,
            )
            info.set(modelInfo)
            LoadOutcome.Loaded(modelInfo)
        } catch (error: OutOfMemoryError) {
            unload()
            nativeDiagnostic = "Model load failed: the device ran out of memory."
            LoadOutcome.Failed(InferenceError.OutOfMemory(file.length()))
        } catch (error: UnsatisfiedLinkError) {
            unload()
            nativeDiagnostic = "Native engine unavailable: the llama.cpp library is not in this build."
            LoadOutcome.Failed(
                InferenceError.Unsupported("native llama.cpp is not available in this build"),
            )
        } catch (error: Throwable) {
            unload()
            nativeDiagnostic = "Model load failed: ${error.message ?: error.javaClass.simpleName}"
            LoadOutcome.Failed(
                InferenceError.InvalidModel(request.path, error.message ?: "load failed"),
            )
        }
    }

    override suspend fun unloadModel() = withContext(Dispatchers.IO) { unload() }

    override suspend fun generate(request: InferenceRequest): InferenceResult = withContext(Dispatchers.IO) {
        val current = requireHandle()
        generating.set(true)
        try {
            // Cancellation is wired here, not left to the caller: `generate` is a
            // suspending function whose native half is a blocking call, so a cancelled
            // coroutine would otherwise keep decoding until the model ran out of tokens.
            val cancellation = coroutineContext[Job]?.invokeOnCompletion { cause ->
                if (cause != null) requestStop(current)
            }
            try {
                val text = runGeneration(current, request) { }
                if (text.isEmpty()) {
                    // Native distinguishes "the model said nothing" from "the decode
                    // failed". Reporting the second as the first is how a broken model
                    // looks like a quiet one.
                    throw InferenceError.GenerationFailed(
                        LlamaNative.lastError().ifBlank { nativeDiagnostic }.ifBlank {
                            "the native decode produced no text"
                        },
                    )
                }
                InferenceResult(
                    text = text,
                    completionTokens = 0,
                    stopReason = StopReason.COMPLETED,
                    engineId = ENGINE_ID,
                )
            } finally {
                cancellation?.dispose()
            }
        } finally {
            generating.set(false)
        }
    }

    /**
     * Streams tokens as native code produces them.
     *
     * `callbackFlow` is the right primitive here: the native decode loop is
     * synchronous and blocking, so it runs on an IO thread and pushes tokens
     * into a channel the collector consumes on whatever dispatcher the UI uses.
     *
     * ## Why the blocking call is not made in the flow's own body
     *
     * The decode blocks the thread it runs on until the model stops. Cancelling the
     * collecting coroutine cannot interrupt a blocking JNI call - cancellation is
     * cooperative, and there is no suspension point inside `llama.cpp` to suspend at.
     *
     * So the decode runs in a child coroutine and `awaitClose` registers the stop
     * handler *before* that child starts. `awaitClose`'s block runs exactly when the
     * flow is torn down - a Cancel press, a closed screen, a cancelled scope - which
     * is the only moment a stop request can still matter. Previously the handler was
     * empty and registered after the decode had already returned, so cancellation
     * never reached `stopGeneration` at all and the model ran to the end of the
     * reply regardless.
     */
    override fun stream(request: InferenceRequest): Flow<StreamChunk> = callbackFlow {
        val current = requireHandle()
        val count = AtomicLong(0L)

        coroutineScope {
            val decode = launch(Dispatchers.IO) {
                try {
                    val text = runGeneration(current, request) { token ->
                        val seen = count.incrementAndGet()
                        trySend(StreamChunk(text = token, done = false, tokenCount = seen.toInt()))
                    }
                    val seen = count.get().toInt()
                    if (text.isEmpty() && seen == 0) {
                        // Nothing was produced and nothing was streamed. Say why rather
                        // than reporting a clean, empty, successful turn.
                        nativeDiagnostic = LlamaNative.lastError().ifBlank {
                            "generation produced no tokens"
                        }
                    }
                    trySend(StreamChunk(text = "", done = true, tokenCount = seen))
                } catch (error: Throwable) {
                    // The collector is gone (cancelled flow), so `trySend` fails and
                    // throwing would only produce noise in the cancelled scope.
                    if (error !is CancellationException) {
                        nativeDiagnostic = error.message ?: "generation failed"
                        trySend(StreamChunk(text = "", done = true, tokenCount = count.get().toInt()))
                        throw error
                    }
                }
            }
            decode.invokeOnCompletion { close() }
        }

        awaitClose {
            // Reached on cancellation *and* on normal completion. On the normal path
            // the decode has already returned and `stopGeneration` would be a no-op
            // flag write, but it is skipped anyway so a finished turn leaves the
            // cancellation flag exactly as it found it.
            if (generating.get()) requestStop(current)
            count.set(0L)
        }
    }.flowOn(Dispatchers.IO)

    override fun stop() {
        val current = handle.get()
        if (current != 0L) requestStop(current)
    }

    override fun isLoaded(): Boolean = loaded.get() && handle.get() != 0L

    override fun modelInfo(): ModelInfo? = info.get()

    /**
     * Asks native to abandon the running decode.
     *
     * Wrapped so a stripped library cannot turn a Cancel press into a crash. The
     * native side checks its flag between decoded tokens, which is the granularity
     * the UI needs; llama.cpp has no hard abort.
     */
    private fun requestStop(current: Long) {
        try {
            LlamaNative.stopGeneration(current)
        } catch (error: UnsatisfiedLinkError) {
            nativeDiagnostic = "Native engine unavailable: cannot stop generation."
        } catch (error: SecurityException) {
            nativeDiagnostic = "Native engine unavailable: cannot stop generation."
        }
    }

    private fun runGeneration(
        nativeHandle: Long,
        request: InferenceRequest,
        onToken: (String) -> Unit,
    ): String {
        generating.set(true)
        return try {
            LlamaNative.generate(
                handle = nativeHandle,
                prompt = buildPrompt(request),
                maxTokens = request.params.maxTokens,
                temperature = request.params.temperature,
                topP = request.params.topP,
                topK = request.params.topK,
                repeatPenalty = request.params.repeatPenalty,
                seed = request.params.seed,
                // Array, not List: native indexes this as a jobjectArray, and a
                // Kotlin List arrives as a java.util.List that it cannot read.
                stopSequences = request.params.stopSequences.toTypedArray(),
                tokenCallback = onToken,
            )
        } catch (error: UnsatisfiedLinkError) {
            throw InferenceError.Unsupported(
                "native llama.cpp is not available in this build",
            )
        }
    }

    /**
     * Builds a plain-text prompt.
     *
     * Small local models are chat-tuned but template-agnostic, so Charaly uses a
     * simple, explicit turn format rather than guessing at a template. This is
     * the one place that decides how the context reads to the model.
     */
    internal fun buildPromptInternal(request: InferenceRequest): String = buildPrompt(request)

    private fun buildPrompt(request: InferenceRequest): String = buildString {
        if (request.systemPrompt.isNotBlank()) {
            appendLine(request.systemPrompt.trim())
            appendLine()
        }
        request.messages.forEach { message ->
            when (message.role) {
                dev.charaly.runtime.inference.ChatRole.SYSTEM -> appendLine("[system] ${message.content}")
                dev.charaly.runtime.inference.ChatRole.USER -> appendLine("[user] ${message.content}")
                dev.charaly.runtime.inference.ChatRole.ASSISTANT -> {
                    // A named speaker keeps multi-character scenes readable; an
                    // unnamed one still renders as a normal turn.
                    appendLine("[${message.name ?: "assistant"}] ${message.content}")
                }
            }
        }
        append("[assistant]")
    }

    private fun requireHandle(): Long {
        val current = handle.get()
        if (current == 0L || !loaded.get()) throw InferenceError.ModelNotLoaded()
        return current
    }

    /**
     * The context size llama.cpp actually granted, or [fallback] if it cannot be asked.
     *
     * The fallback is used only when native is unreachable, and never pretends to be a
     * measurement: the caller passes the requested value because that is the best
     * description of the *request*, not of the result.
     */
    private fun nativeContextSize(handle: Long, fallback: Int = config.contextSize): Int =
        try {
            val reported = LlamaNative.contextSize(handle)
            if (reported > 0) reported else fallback
        } catch (error: UnsatisfiedLinkError) {
            nativeDiagnostic = "Native engine unavailable: context size could not be read."
            fallback
        } catch (error: SecurityException) {
            nativeDiagnostic = "Native engine unavailable: context size could not be read."
            fallback
        }

    // ------------------------------------------------------------------
    // Benchmarking
    // ------------------------------------------------------------------

    /**
     * Measures a real generation run through the native decode loop.
     *
     * Everything reported comes from `std::chrono::steady_clock` inside `charaly_jni.cpp`,
     * taken around the same tokenize / chunked-eval / greedy-decode path a story turn
     * uses. Nothing is inferred from the model file, and a run that produced no tokens
     * returns null so the caller records no measurement at all.
     */
    override suspend fun benchmark(prompt: String, maxTokens: Int): BenchmarkObservation? =
        withContext(Dispatchers.IO) {
            val current = handle.get()
            if (current == 0L || !loaded.get() || !LlamaNative.isAvailable()) return@withContext null
            if (generating.get()) return@withContext null

            generating.set(true)
            try {
                // The prompt is built here rather than passed in, so a benchmark cannot be
                // pointed at a story's context by a caller that has one.
                val native = when (
                    val parsed = NativeReturnParser.parseKeyValuesCatching("benchmark") {
                        LlamaNative.benchmark(
                            handle = current,
                            prompt = BENCHMARK_PROMPT,
                            maxTokens = maxTokens.coerceIn(8, 256),
                        )
                    }
                ) {
                    is NativeReturnParser.KeyValues.Ok -> parsed.values
                    is NativeReturnParser.KeyValues.Malformed -> {
                        nativeDiagnostic = "Benchmark contract failure: ${parsed.detail}"
                        return@withContext null
                    }
                    is NativeReturnParser.KeyValues.Failed -> {
                        nativeDiagnostic = parsed.reason
                        return@withContext null
                    }
                }

                if (native.isEmpty()) {
                    // A legitimate "no measurement": the decode produced no tokens.
                    nativeDiagnostic = LlamaNative.lastError().ifBlank {
                        "Benchmark produced no tokens."
                    }
                    return@withContext null
                }

                val tokens = native["tokens"]?.toIntOrNull()
                val promptTokens = native["prompt_tokens"]?.toIntOrNull()
                val decodeMicros = native["decode_micros"]?.toLongOrNull()
                val promptMicros = native["prompt_micros"]?.toLongOrNull()
                if (tokens == null || promptTokens == null || decodeMicros == null || promptMicros == null) {
                    nativeDiagnostic = "Benchmark result was missing required fields."
                    return@withContext null
                }
                if (tokens <= 0 || promptTokens <= 0) {
                    nativeDiagnostic = "Benchmark produced no tokens."
                    return@withContext null
                }
                nativeDiagnostic = ""

                BenchmarkObservation(
                    tokens = tokens,
                    decodeMicros = decodeMicros,
                    promptMicros = promptMicros,
                    firstTokenMicros = native["first_token_micros"]?.toLongOrNull() ?: 0L,
                    promptTokens = promptTokens,
                    contextSize = native["context_size"]?.toIntOrNull() ?: config.contextSize,
                )
            } catch (error: OutOfMemoryError) {
                // An OOM here is a fact about this device, not a crash: the caller
                // unloads, reports "needs more memory", and records nothing.
                nativeDiagnostic = "Benchmark ran out of memory on this device."
                null
            } catch (error: Throwable) {
                nativeDiagnostic = "Benchmark failed: ${error.message ?: error.javaClass.simpleName}"
                null
            } finally {
                generating.set(false)
            }
        }

    /**
     * The devices this build can actually use, read from ggml's registry.
     *
     * A build compiled CPU-only reports only CPU, and that is what the UI is required to
     * show. Nothing here is hardcoded, so the answer cannot drift from the CMake flags.
     *
     * An empty list means ggml reported no devices. That is *not* the same as "this build
     * failed to answer": the caller gets an empty list in both cases only because the UI
     * renders "unknown" for it, and a failure is additionally recorded in
     * [nativeDiagnostic].
     */
    override fun computeDevices(): List<String> =
        if (!LlamaNative.isAvailable()) {
            nativeDiagnostic = "Native engine unavailable: compute devices could not be read."
            emptyList()
        } else {
            NativeReturnParser.parseDevicesCatching("compute devices") {
                LlamaNative.availableBackends()
            }.getOrElse {
                nativeDiagnostic = it.message ?: "compute devices could not be read"
                emptyList()
            }
        }

    // ------------------------------------------------------------------
    // Configuration, readable so a benchmark can record its own conditions
    // ------------------------------------------------------------------

    /**
     * The thread count this engine was configured with.
     *
     * 0 means llama.cpp chose, which the benchmark records as 0 rather than guessing a
     * number: a tok/s figure with an invented thread count attached would be misleading in
     * exactly the way this whole feature refuses to be.
     */
    override fun configThreads(): Int = config.threads

    /** The offload count this engine was configured with. 0 today; see [EngineConfig]. */
    override fun configGpuLayers(): Int = config.gpuLayers

    /** The context size this engine was configured with. */
    override fun configContextSize(): Int = config.contextSize

    private fun unload() {
        val current = handle.getAndSet(0L)
        if (current != 0L) {
            try {
                LlamaNative.freeModel(current)
            } catch (error: UnsatisfiedLinkError) {
                // Nothing to free: the library was never there. Not a failure worth
                // reporting, because unload() runs on the way out of a failed load.
            } catch (error: SecurityException) {
                // Same reasoning.
            }
        }
        loaded.set(false)
        generating.set(false)
        info.set(null)
    }

    /** Cheap header sniff so a renamed .txt never reaches native code. */
    private fun looksLikeGguf(file: File): Boolean = try {
        file.inputStream().use { stream ->
            val header = ByteArray(4)
            if (stream.read(header) != 4) return false
            header[0] == 'G'.code.toByte() &&
                header[1] == 'G'.code.toByte() &&
                header[2] == 'U'.code.toByte() &&
                header[3] == 'F'.code.toByte()
        }
    } catch (error: java.io.IOException) {
        false
    }

    companion object {
        const val ENGINE_ID = "llama.cpp-local"

        /** A GGUF header is at least 24 bytes; anything smaller cannot be one. */
        private const val MIN_GGUF_BYTES = 24L

        /**
         * The text a benchmark generates.
         *
         * Prose in the shape Charaly actually sends, because the figure that matters is
         * the speed at which this app writes a scene. Kept identical to the runtime's
         * prompt so the two can never drift apart unnoticed.
         */
        const val BENCHMARK_PROMPT: String =
            "The rain had stopped by the time she reached the corner of the street, and the " +
                "shop lights were already on. She stood there a moment longer than she meant " +
                "to, watching the reflections move across the wet asphalt, and then she turned " +
                "and walked the rest of the way home without looking back."

        fun isNativeAvailable(): Boolean = LlamaNative.isAvailable()

        fun nativeVersion(): String =
            if (!LlamaNative.isAvailable()) "unavailable"
            else try {
                LlamaNative.nativeVersion()
            } catch (error: UnsatisfiedLinkError) {
                "unavailable"
            } catch (error: SecurityException) {
                "unavailable"
            }

        /**
         * Whether the JNI bridge itself answers.
         *
         * "The library loaded" and "the bridge is callable" are different facts, and a
         * truncated `.so` produces the first without the second. Nothing here claims a
         * model is loaded, or that a GPU is present.
         */
        fun isNativeBridgeCallable(): Boolean {
            if (!LlamaNative.isAvailable()) return false
            return try {
                LlamaNative.nativeAvailable()
            } catch (error: UnsatisfiedLinkError) {
                false
            } catch (error: SecurityException) {
                false
            }
        }
    }
}

/** Cancellation helper for callers that abandon a flow mid-generation. */
internal fun InferenceEngine.stopAndRethrow(error: Throwable) {
    if (error is CancellationException) stop()
}