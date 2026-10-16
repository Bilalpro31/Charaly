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
import kotlinx.coroutines.sync.withLock
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
         * Workers for evaluating a *prompt*, as opposed to [threads] for decoding a token.
         *
         * PHASE 10. Left at 0, which now means "the device's core count, capped at 16" -
         * see the native side for why these two pools must be set separately.
         *
         * Before this existed the batch pool was llama.cpp's `GGML_DEFAULT_N_THREADS`, a
         * flat 4, and nothing in Kotlin could reach it. Measured on real Qwen3-4B weights
         * (Phase10TurkishLatencyTest): prompt evaluation, which is 85-95% of the time to
         * first token, ran at ~3.3 tokens/second on an eight-core host.
         */
        val threadsBatch: Int = 0,
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
     * Serialises model loads.
     *
     * llama.cpp keeps one process-wide model/context, and two concurrent
     * `loadModel` calls would unload each other's weights from under the
     * in-flight load (`unload()` frees the handle the other coroutine just
     * created). The UI already serialises with `modelLoadLock`; the engine
     * must not depend on that caller-side discipline, so loads and unloads
     * are one operation here too.
     */
    private val loadMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Held for the whole of a decode.
     *
     * ## Why a second lock exists beside [loadMutex]
     *
     * `loadModel`/`unloadModel` were serialised against each other but *not* against
     * `generate`/`stream`, so `unload()` could run while a decode was in flight and free
     * `llama_context` underneath it. The JNI layer refuses to be freed mid-decode (it holds
     * its own session mutex for the duration), which turns the race into "the free blocks
     * until the reply ends" - a silent, multi-second stall on the model switch that the user
     * experiences as a hung app.
     *
     * Holding this gate across a decode makes the intent explicit and testable: a load or
     * an unload *asks the decode to stop and then waits*, and only ever frees a context that
     * nothing is reading. The ordering is always [loadMutex] then this gate, so the two can
     * never deadlock against each other.
     */
    private val generationGate = kotlinx.coroutines.sync.Mutex()

    /**
     * The lifecycle state machine for the one resident model.
     *
     * Pure data plus a transition table, so "is unloading during a generation legal?"
     * has an answer that is asserted in a unit test rather than discovered on a device.
     * Every entry point below reports its real transitions into it.
     */
    private val lifecycle = dev.charaly.runtime.model.ModelLifecycleMachine()

    /** Illegal transitions that were reconciled rather than honoured. Should stay 0. */
    private val lifecycleMisses = java.util.concurrent.atomic.AtomicInteger(0)

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

    /** The native clocks for the most recent turn, or null before one is measured. */
    @Volatile
    private var lastTurnTimings: dev.charaly.runtime.inference.InferenceTimings? = null

    /**
     * Whether the loaded model declares a chat template in its GGUF header.
     *
     * Decides whether [buildPrompt] renders the model's own template (instruct models) or the
     * plain turn format (base models). Set from the header at load, never assumed.
     */
    @Volatile
    private var hasChatTemplate: Boolean = false

    override fun lastTimings(): dev.charaly.runtime.inference.InferenceTimings? = lastTurnTimings

    override fun lastDiagnostic(): String = nativeDiagnostic

    /** The lifecycle state of the one resident model. See [dev.charaly.runtime.model.ModelLifecycle]. */
    fun lifecycle(): dev.charaly.runtime.model.ModelLifecycle = lifecycleMachine().state

    private fun lifecycleMachine(): dev.charaly.runtime.model.ModelLifecycleMachine = lifecycle

    /** Count of transitions the table rejected and that were reconciled. A test asserts 0. */
    fun rejectedTransitions(): Int = lifecycleMisses.get()

    /**
     * Reports a transition the engine is *certain* of.
     *
     * The table is a detector, not a gate: if it rejects an edge that the engine just
     * observed to be true (a load that returned a handle IS loaded), reality wins and the
     * miss is counted. Silently accepting an illegal edge, or silently dropping a real one,
     * are both worse than a counter a test can read.
     */
    private fun enter(next: dev.charaly.runtime.model.ModelLifecycle): dev.charaly.runtime.model.ModelLifecycle {
        val result = lifecycle.transition(next)
        if (result is dev.charaly.runtime.model.ModelLifecycleMachine.TransitionResult.Rejected) {
            lifecycleMisses.incrementAndGet()
            lifecycle.reset(next)
        }
        return next
    }

    /**
     * Brings a GGUF into native memory.
     *
     * The whole operation is one exclusive section: [loadMutex] against other loads and
     * unloads, [generationGate] against a decode that is still running. A load *replaces*
     * the resident model, so it starts by asking any running generation to stop and then
     * waits for the gate - the native context is never freed underneath a decode.
     */
    override suspend fun loadModel(request: ModelLoadRequest): LoadOutcome = loadMutex.withLock {
        withContext(Dispatchers.IO) {
            requestStop()
            generationGate.withLock { loadModelUnderGate(request) }
        }
    }

    /** [loadModel]'s body, already holding both locks. */
    private suspend fun loadModelUnderGate(request: ModelLoadRequest): LoadOutcome {
        return withContext(Dispatchers.IO) {
        unload()
        enter(dev.charaly.runtime.model.ModelLifecycle.LOADING)

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

        // Full header parse and architecture contract, before any native call.
        //
        // The 4-byte magic only proves the file *starts* like a model. A
        // corrupt or truncated header, or an architecture this llama.cpp
        // build does not register, becomes an abort inside
        // llama_model_load_from_file / llama_init_from_model - a SIGABRT or
        // GGML_ASSERT that a Kotlin try/catch cannot see. Rejecting it here is
        // the only layer that can.
        when (val parsed = runCatching {
            file.inputStream().use { dev.charaly.runtime.model.gguf.GgufReader.read(it) }
        }.getOrNull()) {
            is dev.charaly.runtime.model.gguf.GgufReadResult.Failure ->
                return@withContext LoadOutcome.Failed(
                    InferenceError.InvalidModel(request.path, parsed.reason),
                )
            is dev.charaly.runtime.model.gguf.GgufReadResult.Success -> {
                val architecture = parsed.metadata.architecture
                if (architecture.isNotBlank() &&
                    !dev.charaly.runtime.model.EngineCapabilities.supports(architecture)
                ) {
                    return@withContext LoadOutcome.Failed(
                        InferenceError.Unsupported(
                            dev.charaly.runtime.model.EngineVerdict.of(architecture).reason,
                        ),
                    )
                }
            }
            null ->
                return@withContext LoadOutcome.Failed(
                    InferenceError.InvalidModel(request.path, "the file could not be read"),
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
                threadsBatch = config.threadsBatch,
                gpuLayers = config.gpuLayers,
            ) { percent, stage -> request.progress(LoadProgress(percent, stage)) }
            if (opened == 0L) {
                val reason = LlamaNative.lastError().ifBlank { "the native loader refused the file" }
                nativeDiagnostic = "Model load failed: $reason"
                unload()
                enter(dev.charaly.runtime.model.ModelLifecycle.LOAD_FAILED)
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

            // Whether this model was published with a chat template. An instruct model only
            // ends its turn at its own end-of-turn token when the prompt is in the format it
            // was trained on, so this flag decides between the model's template and the plain
            // turn format. Read from the header, never assumed from the architecture.
            hasChatTemplate = metadata["tokenizer.chat_template"]?.isNotBlank() == true

            val modelInfo = ModelInfo(
                // The registry's id, so "which model is loaded" is answerable with the same
                // identifier every other layer uses. See [ModelLoadRequest.installedModelId].
                id = request.installedModelId.ifBlank { file.nameWithoutExtension },
                path = request.path,
                displayName = request.displayName.ifBlank { file.nameWithoutExtension },
                parameterCount = metadata["parameters"]?.toLongOrNull() ?: 0L,
                quantLevel = metadata["general.file_type"]?.toIntOrNull()
                    ?.let { dev.charaly.runtime.model.gguf.GgufMetadata.FILE_TYPE_LABELS[it] }
                    ?: metadata["quantization"].orEmpty(),
                // Read from native, not assumed. Falling back to the requested value would
                // report a context size llama.cpp never granted.
                contextSize = nativeContextSize(opened),
                parameterSizeBytes = file.length(),
                metadata = metadata,
            )
            info.set(modelInfo)
            enter(dev.charaly.runtime.model.ModelLifecycle.LOADED)
            LoadOutcome.Loaded(modelInfo)
        } catch (error: OutOfMemoryError) {
            // Out of memory is a fact about this device, and it is survivable: nothing is
            // left allocated, the engine holds no model, and the user gets a sentence about
            // memory rather than a process death. `unload()` in each catch is what makes the
            // "no crash" part true - a half-built handle must never stay behind.
            unload()
            enter(dev.charaly.runtime.model.ModelLifecycle.LOAD_FAILED)
            nativeDiagnostic = "Model load failed: the device ran out of memory."
            LoadOutcome.Failed(InferenceError.OutOfMemory(file.length()))
        } catch (error: UnsatisfiedLinkError) {
            unload()
            enter(dev.charaly.runtime.model.ModelLifecycle.LOAD_FAILED)
            nativeDiagnostic = "Native engine unavailable: the llama.cpp library is not in this build."
            LoadOutcome.Failed(
                InferenceError.Unsupported("native llama.cpp is not available in this build"),
            )
        } catch (error: Throwable) {
            unload()
            enter(dev.charaly.runtime.model.ModelLifecycle.LOAD_FAILED)
            nativeDiagnostic = "Model load failed: ${error.message ?: error.javaClass.simpleName}"
            LoadOutcome.Failed(
                InferenceError.InvalidModel(request.path, error.message ?: "load failed"),
            )
        }
        }
    }

    /**
     * Drops the resident model and its native context together.
     *
     * Waits for a running decode to end rather than freeing its context: `GENERATING ->
     * unload` is not a legal transition (see [dev.charaly.runtime.model.ModelLifecycleMachine]),
     * and the way to make it legal is to stop the generation first, not to free faster.
     */
    override suspend fun unloadModel() = loadMutex.withLock {
        withContext(Dispatchers.IO) {
            requestStop()
            generationGate.withLock { unload() }
        }
    }

    override suspend fun generate(request: InferenceRequest): InferenceResult =
        generationGate.withLock { withContext(Dispatchers.IO) {
        val current = requireHandle()
        generating.set(true)
        enter(dev.charaly.runtime.model.ModelLifecycle.GENERATING)
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
            enter(dev.charaly.runtime.model.ModelLifecycle.LOADED)
        }
        } }

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
                // The gate is held for the whole decode, so `unloadModel` and `loadModel`
                // queue behind it instead of freeing `llama_context` mid-token. They ask for
                // a stop first (see `requestStop()` in both), so the wait is one token long.
                generationGate.withLock {
                try {
                    enter(dev.charaly.runtime.model.ModelLifecycle.GENERATING)
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
                } finally {
                    // Leaving GENERATING is unconditional: a cancelled, failed and completed
                    // decode all end with the model still resident and nothing running.
                    enter(dev.charaly.runtime.model.ModelLifecycle.LOADED)
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

    /** [requestStop] for the caller that only wants "whatever is running, stop". */
    private fun requestStop() {
        val current = handle.get()
        if (current != 0L) requestStop(current)
    }

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
            val text = LlamaNative.generate(
                handle = nativeHandle,
                prompt = buildPrompt(request, nativeHandle),
                maxTokens = request.params.maxTokens,
                temperature = request.params.temperature,
                topP = request.params.topP,
                topK = request.params.topK,
                // PHASE 8: minP was configured on every profile and carried through
                // SamplerSettings -> GenerationParams, then dropped at the JNI boundary.
                minP = request.params.minP,
                repeatPenalty = request.params.repeatPenalty,
                seed = request.params.seed,
                // Array, not List: native indexes this as a jobjectArray, and a
                // Kotlin List arrives as a java.util.List that it cannot read.
                stopSequences = request.params.stopSequences.toTypedArray(),
                tokenCallback = onToken,
            )
            recordTimings()
            text
        } catch (error: UnsatisfiedLinkError) {
            throw InferenceError.Unsupported(
                "native llama.cpp is not available in this build",
            )
        }
    }

    /**
     * Reads the native generation path's own clocks back into Kotlin.
     *
     * Purely observational: a missing or malformed reply leaves the previous reading in
     * place, and nothing here can change what was decoded. It is the data behind the
     * developer-mode "STORY TURN PERF" line, and it is never rendered to a normal player.
     */
    private fun recordTimings() {
        val parsed = NativeReturnParser.parseKeyValuesCatching("generate stats") {
            LlamaNative.lastGenerateStats()
        }
        val values = (parsed as? NativeReturnParser.KeyValues.Ok)?.values ?: return
        if (values.isEmpty()) return
        val promptMicros = values["prompt_micros"]?.toLongOrNull() ?: return
        lastTurnTimings = dev.charaly.runtime.inference.InferenceTimings(
            promptMicros = promptMicros,
            tokenizeMicros = values["tokenize_micros"]?.toLongOrNull() ?: 0L,
            evalMicros = values["eval_micros"]?.toLongOrNull() ?: 0L,
            generationMicros = values["generation_micros"]?.toLongOrNull() ?: 0L,
            firstTokenMicros = values["first_token_micros"]?.toLongOrNull() ?: 0L,
            promptTokens = values["prompt_tokens"]?.toIntOrNull() ?: 0,
            generatedTokens = values["generated_tokens"]?.toIntOrNull() ?: 0,
            reusedTokens = values["reused_tokens"]?.toIntOrNull() ?: 0,
            contextSize = values["context_size"]?.toIntOrNull() ?: config.contextSize,
            cachedTokens = values["cache_tokens"]?.toIntOrNull() ?: 0,
            reuseFallback = values["reuse_fallback"] == "1",
            stopReason = values["stop_reason"].orEmpty().ifBlank { "none" },
            budget = values["budget"]?.toIntOrNull() ?: 0,
            requestedMaxTokens = values["requested_max"]?.toIntOrNull() ?: 0,
            threads = values["threads"]?.toIntOrNull() ?: 0,
            threadsBatch = values["threads_batch"]?.toIntOrNull() ?: 0,
        )
    }

    /**
     * Builds a plain-text prompt.
     *
     * Small local models are chat-tuned but template-agnostic, so Charaly uses a
     * simple, explicit turn format rather than guessing at a template. This is
     * the one place that decides how the context reads to the model.
     */
    internal fun buildPromptInternal(request: InferenceRequest): String = buildPlainPrompt(request)

    private fun buildPrompt(request: InferenceRequest, nativeHandle: Long): String {
        // An instruct model only ends its turn at its own end-of-turn token when the prompt
        // is in the format it was trained on. The plain turn format is a foreign one: the
        // model rambles to the token cap and never emits EOG. So a model that declares a chat
        // template gets its template applied; a base model keeps the plain format.
        if (hasChatTemplate) {
            val templated = applyChatTemplate(request, nativeHandle)
            if (templated.isNotBlank()) return templated
        }
        return buildPlainPrompt(request)
    }

    /**
     * Renders the request with the loaded model's own chat template.
     *
     * The system block is the first message, the transcript follows, and the template opens
     * the assistant's turn. A named speaker is kept by prefixing the assistant content with
     * the name, so a multi-character scene still says who is talking - the template has no
     * role for "Marinette", only for "assistant".
     */
    private fun applyChatTemplate(request: InferenceRequest, nativeHandle: Long): String {
        val flat = mutableListOf<String>()
        if (request.systemPrompt.isNotBlank()) {
            flat += "system"
            flat += request.systemPrompt
        }
        request.messages.forEach { message ->
            val role = when (message.role) {
                dev.charaly.runtime.inference.ChatRole.SYSTEM -> "system"
                dev.charaly.runtime.inference.ChatRole.USER -> "user"
                dev.charaly.runtime.inference.ChatRole.ASSISTANT -> "assistant"
            }
            val content = if (message.role == dev.charaly.runtime.inference.ChatRole.ASSISTANT && !message.name.isNullOrBlank()) {
                "${message.name}: ${message.content}"
            } else {
                message.content
            }
            flat += role
            flat += content
        }
        return LlamaNative.applyChatTemplate(nativeHandle, flat.toTypedArray())
    }

    /**
     * The plain turn format, used for base models and as the fallback when a model declares a
     * template that cannot be applied.
     */
    private fun buildPlainPrompt(request: InferenceRequest): String = buildString {
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

    /**
     * The prompt-evaluation pool this engine was configured with.
     *
     * 0 means "the device's core count, capped at 16" - see [EngineConfig.threadsBatch].
     */
    override fun configThreadsBatch(): Int = config.threadsBatch

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
        // The weights are gone; the file is not. "Nothing is resident" is READY again -
        // which is exactly the READY != LOADED distinction this engine exists to keep.
        enter(dev.charaly.runtime.model.ModelLifecycle.READY)
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