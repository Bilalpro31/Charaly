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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
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
         * evidence; until then the engine decides.
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

    override suspend fun loadModel(request: ModelLoadRequest): LoadOutcome = withContext(Dispatchers.IO) {
        unload()

        // Validate the user's file FIRST. These checks are pure Kotlin, so they
        // give a precise reason ("that file is not a GGUF model") even when the
        // native library is missing, and they stop a multi-gigabyte non-model
        // file from ever reaching native code.
        val file = java.io.File(request.path)
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
                return@withContext LoadOutcome.Failed(
                    InferenceError.InvalidModel(request.path, LlamaNative.lastError().ifBlank { "native loader refused the file" }),
                )
            }
            handle.set(opened)
            loaded.set(true)
            val metadata = runCatching { LlamaNative.modelMetadata(opened) }.getOrDefault(emptyMap())
            val modelInfo = ModelInfo(
                // The registry's id, so "which model is loaded" is answerable with the same
                // identifier every other layer uses. See [ModelLoadRequest.installedModelId].
                id = request.installedModelId.ifBlank { file.nameWithoutExtension },
                path = request.path,
                displayName = request.displayName.ifBlank { file.nameWithoutExtension },
                parameterCount = metadata["parameters"]?.toLongOrNull() ?: 0L,
                quantLevel = metadata["quantization"].orEmpty(),
                contextSize = runCatching { LlamaNative.contextSize(opened) }.getOrDefault(config.contextSize),
                parameterSizeBytes = file.length(),
                metadata = metadata,
            )
            info.set(modelInfo)
            LoadOutcome.Loaded(modelInfo)
        } catch (error: Throwable) {
            unload()
            LoadOutcome.Failed(
                InferenceError.OutOfMemory(file.length()),
            ).takeIf { error is OutOfMemoryError }
                ?: LoadOutcome.Failed(InferenceError.InvalidModel(request.path, error.message ?: "load failed"))
        }
    }

    override suspend fun unloadModel() = withContext(Dispatchers.IO) { unload() }

    override suspend fun generate(request: InferenceRequest): InferenceResult = withContext(Dispatchers.IO) {
        val current = requireHandle()
        generating.set(true)
        try {
            val text = runGeneration(current, request) { }
            InferenceResult(
                text = text,
                completionTokens = 0,
                stopReason = StopReason.COMPLETED,
                engineId = ENGINE_ID,
            )
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
     */
    override fun stream(request: InferenceRequest): Flow<StreamChunk> = callbackFlow {
        val current = requireHandle()
        generating.set(true)
        var count = 0
        try {
            val text = runGeneration(current, request) { token ->
                count++
                trySend(StreamChunk(text = token, done = false, tokenCount = count))
            }
            trySend(StreamChunk(text = "", done = true, tokenCount = count))
            if (text.isEmpty() && count == 0) {
                // An empty but successful generation is still a completion.
                trySend(StreamChunk(text = "", done = true, tokenCount = 0))
            }
        } catch (error: Throwable) {
            trySend(StreamChunk(text = "", done = true, tokenCount = count))
            throw error
        } finally {
            generating.set(false)
        }
        awaitClose { }
    }.flowOn(Dispatchers.IO)

    override fun stop() {
        val current = handle.get()
        if (current != 0L && generating.get()) {
            LlamaNative.stopGeneration(current)
        }
    }

    override fun isLoaded(): Boolean = loaded.get() && handle.get() != 0L

    override fun modelInfo(): ModelInfo? = info.get()

    private fun runGeneration(
        nativeHandle: Long,
        request: InferenceRequest,
        onToken: (String) -> Unit,
    ): String = LlamaNative.generate(
        handle = nativeHandle,
        prompt = buildPrompt(request),
        maxTokens = request.params.maxTokens,
        temperature = request.params.temperature,
        topP = request.params.topP,
        topK = request.params.topK,
        repeatPenalty = request.params.repeatPenalty,
        seed = request.params.seed,
        stopSequences = request.params.stopSequences,
        tokenCallback = onToken,
    )

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
                val native = runCatching {
                    LlamaNative.benchmark(
                        handle = current,
                        prompt = BENCHMARK_PROMPT,
                        maxTokens = maxTokens.coerceIn(8, 256),
                    )
                }.getOrNull() ?: return@withContext null

                val tokens = native["tokens"]?.toIntOrNull() ?: return@withContext null
                val promptTokens = native["prompt_tokens"]?.toIntOrNull() ?: return@withContext null
                val decodeMicros = native["decode_micros"]?.toLongOrNull() ?: return@withContext null
                val promptMicros = native["prompt_micros"]?.toLongOrNull() ?: return@withContext null
                if (tokens <= 0 || promptTokens <= 0) return@withContext null

                BenchmarkObservation(
                    tokens = tokens,
                    decodeMicros = decodeMicros,
                    promptMicros = promptMicros,
                    firstTokenMicros = native["first_token_micros"]?.toLongOrNull() ?: 0L,
                    promptTokens = promptTokens,
                    contextSize = native["context_size"]?.toIntOrNull() ?: config.contextSize,
                )
            } catch (error: Throwable) {
                // An OOM here is a fact about this device, not a crash: the caller
                // unloads, reports "needs more memory", and records nothing.
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
     */
    override fun computeDevices(): List<String> =
        if (LlamaNative.isAvailable()) {
            runCatching { LlamaNative.availableBackends() }.getOrDefault(emptyList())
        } else {
            emptyList()
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
            runCatching { LlamaNative.freeModel(current) }
        }
        loaded.set(false)
        generating.set(false)
        info.set(null)
    }

    /** Cheap header sniff so a renamed .txt never reaches native code. */
    private fun looksLikeGguf(file: java.io.File): Boolean = runCatching {
        file.inputStream().use { stream ->
            val header = ByteArray(4)
            if (stream.read(header) != 4) return false
            header[0] == 'G'.code.toByte() &&
                header[1] == 'G'.code.toByte() &&
                header[2] == 'U'.code.toByte() &&
                header[3] == 'F'.code.toByte()
        }
    }.getOrDefault(false)

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
            else runCatching { LlamaNative.nativeVersion() }.getOrDefault("unavailable")
    }
}

/** Cancellation helper for callers that abandon a flow mid-generation. */
internal fun InferenceEngine.stopAndRethrow(error: Throwable) {
    if (error is CancellationException) stop()
}
