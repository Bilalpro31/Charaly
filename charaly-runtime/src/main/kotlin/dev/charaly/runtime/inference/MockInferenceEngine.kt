package dev.charaly.runtime.inference

import dev.charaly.runtime.domain.StoryTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Deterministic in-process stand-in for a local model.
 *
 * It exists so the whole pipeline (context -> engine -> stream -> persist) can be
 * tested on a plain JVM with no GGUF file, no device and no server. It is also
 * what the Android app falls back to when no model is loaded yet, so the UI stays
 * explorable offline.
 */
class MockInferenceEngine(
    private val engineId: String = "mock",
    private val delayMillis: Long = 0L,
    private val chunkSize: Int = 12,
    private val responder: (InferenceRequest) -> String = ::defaultReply,
    info: ModelInfo? = null,
    /** Set when the engine should behave as if a model is already loaded. */
    initiallyLoaded: Boolean = false,
) : InferenceEngine {

    private val loaded = AtomicBoolean(initiallyLoaded)
    private val cancelled = AtomicBoolean(false)
    private var info: ModelInfo? = info?.takeIf { initiallyLoaded }

    override suspend fun loadModel(request: ModelLoadRequest): LoadOutcome {
        request.progress(LoadProgress(0, "reading model"))
        request.progress(LoadProgress(50, "allocating"))
        request.progress(LoadProgress(100, "ready"))
        loaded.set(true)
        info = ModelInfo(
            id = request.path,
            path = request.path,
            displayName = request.displayName.ifBlank { request.path.substringAfterLast('/') },
        )
        return LoadOutcome.Loaded(info!!)
    }

    override suspend fun unloadModel() {
        loaded.set(false)
        info = null
    }

    override suspend fun generate(request: InferenceRequest): InferenceResult {
        val text = buildString {
            responder(request).chunked(chunkSize.coerceAtLeast(1)).forEach { chunk ->
                if (cancelled.getAndSet(false)) throw InferenceError.Cancelled()
                if (delayMillis > 0) delay(delayMillis)
                append(chunk)
            }
        }
        return InferenceResult(text = text, stopReason = StopReason.COMPLETED, engineId = engineId)
    }

    override fun stream(request: InferenceRequest): Flow<StreamChunk> = flow {
        var tokens = 0
        val full = responder(request)
        full.chunked(chunkSize.coerceAtLeast(1)).forEach { chunk ->
            if (cancelled.get()) throw InferenceError.Cancelled()
            if (delayMillis > 0) delay(delayMillis)
            tokens++
            emit(StreamChunk(text = chunk, done = false, tokenCount = tokens))
        }
        emit(StreamChunk(text = "", done = true, tokenCount = tokens))
    }

    override fun stop() {
        cancelled.set(true)
    }

    override fun isLoaded(): Boolean = loaded.get()

    override fun modelInfo(): ModelInfo? = info

    companion object {
        fun defaultReply(request: InferenceRequest): String {
            val user = request.messages.lastOrNull { it.role == ChatRole.USER }?.content.orEmpty()
            return "[mock] ${request.speakerId?.value?.let { "$it answers" } ?: "reply"}: $user"
        }
    }
}

/**
 * Canned script engine for tests that need exact output (for example to assert
 * that proposed actions were parsed and validated).
 */
class ScriptedInferenceEngine(
    private val script: List<String>,
    private val engineId: String = "scripted",
) : InferenceEngine {

    private val loaded = AtomicBoolean(false)
    private var index = 0
    var lastRequest: InferenceRequest? = null
        private set

    override suspend fun loadModel(request: ModelLoadRequest): LoadOutcome {
        loaded.set(true)
        return LoadOutcome.Loaded(ModelInfo(id = "scripted", path = request.path, displayName = "Scripted"))
    }

    override suspend fun unloadModel() {
        loaded.set(false)
    }

    override suspend fun generate(request: InferenceRequest): InferenceResult {
        lastRequest = request
        val text = next()
        return InferenceResult(text = text, engineId = engineId)
    }

    override fun stream(request: InferenceRequest): Flow<StreamChunk> = flow {
        lastRequest = request
        next().chunked(8).forEach { emit(StreamChunk(it)) }
        emit(StreamChunk("", done = true))
    }

    override fun stop() = Unit

    override fun isLoaded(): Boolean = loaded.get()

    override fun modelInfo(): ModelInfo? = if (loaded.get()) ModelInfo("scripted", "scripted", "Scripted") else null

    private fun next(): String = script.getOrElse(index) { "" }.also { index++ }
}

/** Records what the pipeline asked for. Useful in assertions. */
class RecordingInferenceEngine(
    private val delegate: InferenceEngine = MockInferenceEngine(),
) : InferenceEngine {
    val requests = mutableListOf<InferenceRequest>()
    var streamCount = 0
        private set
    var stopCount = 0
        private set

    override suspend fun loadModel(request: ModelLoadRequest): LoadOutcome = delegate.loadModel(request)

    override suspend fun unloadModel() = delegate.unloadModel()

    override suspend fun generate(request: InferenceRequest): InferenceResult {
        requests += request
        return delegate.generate(request)
    }

    override fun stream(request: InferenceRequest): Flow<StreamChunk> {
        requests += request
        streamCount++
        return delegate.stream(request)
    }

    override fun stop() {
        stopCount++
        delegate.stop()
    }

    override fun isLoaded(): Boolean = delegate.isLoaded()

    override fun modelInfo(): ModelInfo? = delegate.modelInfo()
}
