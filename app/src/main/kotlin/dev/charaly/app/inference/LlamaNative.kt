package dev.charaly.app.inference

/**
 * JNI surface of the bundled llama.cpp build.
 *
 * Everything here is a thin, blocking call into native code. It is always
 * invoked from a background dispatcher by [LocalLlamaInferenceEngine]; the
 * native side never touches the UI thread.
 */
internal object LlamaNative {

    init {
        System.loadLibrary("charaly_llama")
    }

    /** True when native llama.cpp is present in this build. */
    external fun nativeAvailable(): Boolean

    external fun nativeVersion(): String

    /**
     * Loads a GGUF model. Calls [progressCallback] with (percent, stage) during
     * the load. Returns an opaque handle, or 0 on failure (see [lastError]).
     */
    external fun loadModel(
        path: String,
        contextSize: Int,
        threads: Int,
        gpuLayers: Int,
        progressCallback: (Int, String) -> Unit,
    ): Long

    /** Releases a model handle and frees the native context. */
    external fun freeModel(handle: Long)

    /** Human readable metadata for the loaded model, key=value pairs. */
    external fun modelMetadata(handle: Long): Map<String, String>

    /** Approximate context capacity for the loaded model. */
    external fun contextSize(handle: Long): Int

    /**
     * Runs a single completion synchronously.
     *
     * [tokenCallback] is invoked with each decoded token text as it is produced,
     * which is what makes streaming possible. Returns the accumulated text.
     */
    external fun generate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        seed: Long,
        stopSequences: List<String>,
        tokenCallback: (String) -> Unit,
    ): String

    /** Requests cancellation of the running generate() call. */
    external fun stopGeneration(handle: Long)

    /** Last native error message, if any. */
    external fun lastError(): String
}
