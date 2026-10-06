package dev.charaly.app.inference

/**
 * JNI surface of the bundled llama.cpp build.
 *
 * Everything here is a thin, blocking call into native code. It is always
 * invoked from a background dispatcher by [LocalLlamaInferenceEngine]; the
 * native side never touches the UI thread.
 */
internal object LlamaNative {

    /**
     * Whether the shared library actually loaded.
     *
     * ## Why the load is caught here rather than at the call site
     *
     * `System.loadLibrary` throws `UnsatisfiedLinkError` - and because this is an object's
     * static initialiser, the failure is *sticky*: the class is marked erroneous and every
     * later access throws `NoClassDefFoundError` instead, forever.
     *
     * That matters because the load fails for reasons that are ordinary on a real device and
     * have nothing to do with the user's model file:
     *
     * ```
     *   an ABI split stripped lib/arm64-v8a    (sideloaded build on the wrong architecture)
     *   the .so is present but its deps are not
     *   a device variant the prebuilt library was not built for
     * ```
     *
     * Left uncaught, any of those turned "Charaly has no local inference on this phone"
     * into a crash instead of a sentence the user can read. Catching here makes the whole
     * class permanently usable and permanently answering "not available", so every caller
     * gets one consistent, catchable answer.
     */
    private val loaded: Boolean = try {
        System.loadLibrary("charaly_llama")
        true
    } catch (error: UnsatisfiedLinkError) {
        false
    } catch (error: SecurityException) {
        false
    }

    /**
     * Whether native llama.cpp is usable in this build.
     *
     * Kotlin-level, so it cannot throw on a build where the library is absent - which is
     * what lets [LocalLlamaInferenceEngine] decide *before* it starts a load whether one is
     * possible at all.
     */
    fun isAvailable(): Boolean = loaded

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

    // ---- benchmarking ----------------------------------------------------
    //
    // A benchmark needs the native loop's own timings, because measuring from Kotlin
    // would only ever measure the JNI boundary and not the decode. These two calls
    // return real numbers taken inside charaly_jni.cpp with a steady clock.

    /**
     * Runs one fixed prompt and reports what actually happened.
     *
     * Returns key=value pairs (`tokens`, `elapsed_micros`, `prompt_micros`,
     * `first_token_micros`) so the Kotlin side parses one shape rather than a struct,
     * matching how [modelMetadata] already crosses the boundary. An empty map means the
     * native call failed, and the caller must treat that as "no measurement" rather
     * than as zero.
     */
    external fun benchmark(
        handle: Long,
        prompt: String,
        maxTokens: Int,
    ): Map<String, String>

    /**
     * Which compute backends this build was actually compiled with.
     *
     * Read from ggml's registry rather than hardcoded, so the answer cannot drift from
     * the CMake flags. An empty list means CPU only, and the UI must then say CPU only.
     */
    external fun availableBackends(): List<String>
}
