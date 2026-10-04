package dev.charaly.app.inference

import dev.charaly.runtime.inference.InferenceEngine
import dev.charaly.runtime.inference.InferenceError
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
) : InferenceEngine {

    data class EngineConfig(
        val contextSize: Int = 2048,
        val threads: Int = 0,
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

        if (!LlamaNative.nativeAvailable()) {
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
                id = file.nameWithoutExtension,
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

        fun isNativeAvailable(): Boolean = runCatching { LlamaNative.nativeAvailable() }.getOrDefault(false)

        fun nativeVersion(): String = runCatching { LlamaNative.nativeVersion() }.getOrDefault("unavailable")
    }
}

/** Cancellation helper for callers that abandon a flow mid-generation. */
internal fun InferenceEngine.stopAndRethrow(error: Throwable) {
    if (error is CancellationException) stop()
}
