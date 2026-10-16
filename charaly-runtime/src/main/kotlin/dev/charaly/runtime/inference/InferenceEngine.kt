package dev.charaly.runtime.inference

import dev.charaly.runtime.domain.CharacterId
import kotlinx.coroutines.flow.Flow

/**
 * The inference port. The Charaly runtime depends on THIS interface only.
 *
 * The concrete Android implementation is
 * `dev.charaly.app.inference.LocalLlamaInferenceEngine`, which calls llama.cpp
 * through JNI in the app process. Tests use `MockInferenceEngine`.
 *
 * There is deliberately no network implementation: Charaly's default (and, in
 * this phase, only) path is on-device GGUF inference.
 */
interface InferenceEngine {

    /** Load a GGUF model. Must report progress and must not block the caller. */
    suspend fun loadModel(request: ModelLoadRequest): LoadOutcome

    /** Release the model and its native resources. Safe to call when unloaded. */
    suspend fun unloadModel()

    /** One-shot generation. */
    suspend fun generate(request: InferenceRequest): InferenceResult

    /**
     * Streaming generation. Emits partial text as it is produced so the UI can
     * render tokens without waiting for the whole reply. Cancelling the
     * collecting coroutine (or calling [stop]) must abort generation promptly.
     */
    fun stream(request: InferenceRequest): Flow<StreamChunk>

    /** Request cancellation of an in-flight generation. */
    fun stop()

    fun isLoaded(): Boolean

    /** Metadata of the currently loaded model, or null. */
    fun modelInfo(): ModelInfo?
}

data class ModelLoadRequest(
    /** Absolute path to a GGUF file inside app-writable or SAF-granted storage. */
    val path: String,
    val displayName: String = "",
    /**
     * The [dev.charaly.runtime.model.InstalledModel.id] this file is known by.
     *
     * ## Why the engine has to be told
     *
     * The engine used to name the model after the file (`my-model.gguf` -> `"my-model"`),
     * while the registry names it `"local-my-model-42"`. Those are different namespaces, so
     * "is the model I selected the one that is loaded?" could never be answered: the
     * comparison silently always failed, and a story correctly bound to a correctly loaded
     * model read as "not loaded".
     *
     * Carrying the registry id through the load makes the engine's answer comparable with
     * every other layer's, which is what [dev.charaly.runtime.model.ModelSelection] needs
     * in order to be the single source of truth. The file name remains as the fallback so
     * an engine used without a registry still reports something meaningful.
     */
    val installedModelId: String = "",
    /** 0..100, reported while the model is being read. */
    val progress: (LoadProgress) -> Unit = {},
)

data class LoadProgress(
    val percent: Int,
    val stage: String,
)

sealed interface LoadOutcome {
    data class Loaded(val info: ModelInfo) : LoadOutcome
    data class Failed(val error: InferenceError) : LoadOutcome
}

data class ModelInfo(
    val id: String,
    val path: String,
    val displayName: String,
    val parameterCount: Long = 0L,
    val quantLevel: String = "",
    val contextSize: Int = 2048,
    val parameterSizeBytes: Long = 0L,
    val metadata: Map<String, String> = emptyMap(),
)

data class GenerationParams(
    val maxTokens: Int = 256,
    val temperature: Float = 0.8f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.1f,
    val seed: Long = -1L,
    val stopSequences: List<String> = emptyList(),
)

data class InferenceRequest(
    val systemPrompt: String,
    val messages: List<ChatMessage>,
    val params: GenerationParams = GenerationParams(),
    /** Optional pre-built prompt for engines that do not use chat templates. */
    val rawPrompt: String? = null,
    val speakerId: CharacterId? = null,
)

data class ChatMessage(
    val role: ChatRole,
    val content: String,
    val name: String? = null,
) {
    companion object {
        fun user(content: String) = ChatMessage(ChatRole.USER, content)
        fun assistant(content: String, name: String? = null) = ChatMessage(ChatRole.ASSISTANT, content, name)
        fun system(content: String) = ChatMessage(ChatRole.SYSTEM, content)
    }
}

enum class ChatRole {
    SYSTEM,
    USER,
    ASSISTANT,
}

data class StreamChunk(
    val text: String,
    val done: Boolean = false,
    val tokenCount: Int = 0,
)

data class InferenceResult(
    val text: String,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val stopReason: StopReason = StopReason.COMPLETED,
    val engineId: String = "unknown",
)

/**
 * The engine's own clocks for one real turn, taken around the actual prompt the app sent.
 *
 * Distinct from [dev.charaly.runtime.model.BenchmarkObservation], which measures a fixed
 * prompt for the speed label. These are per-turn and exist for the developer-mode
 * "STORY TURN PERF" readout: `promptTokens` counts what was evaluated this call, while
 * `reusedTokens` counts the leading tokens whose key/value state came from the previous
 * turn - so the two together show whether prefix caching paid off.
 *
 * Every field came from a clock inside the engine. An engine that cannot measure returns
 * null (the default on the interface), never zeros pretending to be a measurement.
 */
data class InferenceTimings(
    val promptMicros: Long,
    /** Time to tokenize the prompt. */
    val tokenizeMicros: Long = 0L,
    /** Time to evaluate the prompt into the KV cache (prompt time minus tokenize time). */
    val evalMicros: Long = 0L,
    val generationMicros: Long,
    val firstTokenMicros: Long,
    val promptTokens: Int,
    val generatedTokens: Int,
    val reusedTokens: Int,
    val contextSize: Int,
    /** Tokens the previous turn left in the cache (0 on a cold call). */
    val cachedTokens: Int = 0,
    /** True when the engine had to clear the cache instead of reusing a prefix. */
    val reuseFallback: Boolean = false,
    /** Why the decode stopped: `eog`, `length`, `stop_sequence`, `cancelled`, `none`. */
    val stopReason: String = "none",
    /** The generation budget the engine was allowed (`maxTokens` clipped to the window). */
    val budget: Int = 0,
    /** The `maxTokens` the caller asked for. */
    val requestedMaxTokens: Int = 0,
    /** Worker threads the loaded context decodes one token with. */
    val threads: Int = 0,
    /**
     * PHASE 10: worker threads the loaded context evaluates a PROMPT with.
     *
     * A separate number on purpose. llama.cpp runs two pools and picks between them with
     * `batched ? n_threads_batch : n_threads`, and time to first token is dominated by the
     * batch one - so a readout that names only [threads] describes the half of the work
     * that is not the bottleneck.
     */
    val threadsBatch: Int = 0,
) {
    /** Tokens/second through the prompt-evaluation pool. The TTFT driver. */
    val promptTokensPerSecondActual: Double
        get() = if (evalMicros <= 0L) 0.0 else promptTokens * 1_000_000.0 / evalMicros
    val promptMillis: Long get() = promptMicros / 1000L
    val generationMillis: Long get() = generationMicros / 1000L
    val firstTokenMillis: Long get() = firstTokenMicros / 1000L
    val tokensPerSecond: Double
        get() = if (generationMicros <= 0L) 0.0 else generatedTokens * 1_000_000.0 / generationMicros
    val promptTokensPerSecond: Double
        get() = if (promptMicros <= 0L) 0.0 else promptTokens * 1_000_000.0 / promptMicros
}

enum class StopReason {
    COMPLETED,
    /** Hit maxTokens. */
    LENGTH,
    /** Hit a stop sequence. */
    STOP_SEQUENCE,
    /** User pressed stop / flow cancelled. */
    CANCELLED,
    ERROR,
}

/** Explicit failure taxonomy. Never leaks engine internals to the UI. */
sealed class InferenceError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class ModelNotLoaded : InferenceError("No model is loaded. Load a GGUF model first.")
    class ModelNotFound(val path: String) : InferenceError("Model file not found: $path")
    class InvalidModel(val path: String, detail: String) : InferenceError("Not a valid GGUF model ($path): $detail")
    class OutOfMemory(val requested: Long) : InferenceError("Not enough memory to load model (need ~${requested / (1 shl 20)} MiB)")
    class GenerationFailed(detail: String, cause: Throwable? = null) : InferenceError("Generation failed: $detail", cause)
    class Cancelled : InferenceError("Generation cancelled")
    class Unsupported(detail: String) : InferenceError(detail)
}
