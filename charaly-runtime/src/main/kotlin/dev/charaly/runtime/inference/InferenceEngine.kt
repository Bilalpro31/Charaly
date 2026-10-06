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
