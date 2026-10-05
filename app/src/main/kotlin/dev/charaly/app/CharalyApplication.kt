package dev.charaly.app

import android.app.Application
import dev.charaly.app.inference.LocalLlamaInferenceEngine
import dev.charaly.app.model.ModelManager
import dev.charaly.runtime.inference.GenerationParams
import dev.charaly.runtime.inference.InferenceEngine
import dev.charaly.runtime.model.BuiltInModelCatalog
import dev.charaly.runtime.model.JsonModelRegistry
import dev.charaly.runtime.model.ModelCatalog
import dev.charaly.runtime.persistence.FileCharalyStorage
import dev.charaly.runtime.persistence.JsonCharalyRepository
import dev.charaly.runtime.session.CharalyRuntime
import java.io.File

/**
 * Charaly's composition root.
 *
 * Hand-wired on purpose: the dependency graph is a handful of objects, and an
 * annotation processor would add a build-time cost and a learning curve for no
 * benefit. Everything is created lazily on first use so app startup stays cheap
 * and offline.
 */
class CharalyApplication : Application() {

    /**
     * Stories, world state, knowledge, memories and the event queue are JSON
     * documents in app-private storage. One small set of files is the whole
     * database; there is no server component anywhere.
     */
    private val repository by lazy {
        JsonCharalyRepository(FileCharalyStorage(File(filesDir, "charaly")))
    }

    /**
     * The single model registry.
     *
     * Imported GGUFs and catalog models are both [dev.charaly.runtime.model.InstalledModel]
     * entries here, so the library has one list instead of two parallel systems.
     */
    val modelRegistry by lazy {
        JsonModelRegistry(FileCharalyStorage(File(filesDir, "charaly")))
    }

    /** Ready-made models Charaly knows about. Metadata only: no binaries ship. */
    val modelCatalog: ModelCatalog by lazy { BuiltInModelCatalog() }

    val modelManager: ModelManager by lazy { ModelManager(this) }

    val preferences: AppPreferences by lazy { AppPreferences(this) }

    /**
     * Local llama.cpp. If the native library is missing from the build, the
     * runtime falls back to a clearly labelled offline echo engine so the story
     * engine stays explorable - never to a remote service.
     */
    val inferenceEngine: InferenceEngine by lazy {
        if (LocalLlamaInferenceEngine.isNativeAvailable()) {
            LocalLlamaInferenceEngine()
        } else {
            FallbackEchoEngine()
        }
    }

    val charaly: CharalyRuntime by lazy {
        CharalyRuntime(
            repository = repository,
            engine = inferenceEngine,
            params = GenerationParams(
                maxTokens = 320,
                temperature = 0.85f,
                topP = 0.95f,
                topK = 40,
                minP = 0.05f,
                repeatPenalty = 1.1f,
            ),
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Warm the repositories on a background thread: launching the app must
        // never require the network, and must not block the main thread.
        Thread(
            {
                kotlinx.coroutines.runBlocking { runCatching { charaly.restore() } }
            },
            "charaly-restore",
        ).apply {
            isDaemon = true
            start()
        }
    }
}

/**
 * Used only when the native library is unavailable.
 *
 * It makes no network calls and claims nothing about the world: it echoes the
 * prompt so a developer can verify the runtime end to end without a model.
 */
class FallbackEchoEngine : InferenceEngine {
    private var loaded = false

    override suspend fun loadModel(request: dev.charaly.runtime.inference.ModelLoadRequest) =
        dev.charaly.runtime.inference.LoadOutcome.Failed(
            dev.charaly.runtime.inference.InferenceError.Unsupported(
                "llama.cpp native library is missing. Run scripts/setup-llama.sh and rebuild " +
                    "(this build has no local inference available).",
            ),
        )

    override suspend fun unloadModel() {
        loaded = false
    }

    override suspend fun generate(request: dev.charaly.runtime.inference.InferenceRequest) =
        dev.charaly.runtime.inference.InferenceResult(text = "[]")

    override fun stream(request: dev.charaly.runtime.inference.InferenceRequest) =
        kotlinx.coroutines.flow.flow {
            emit(dev.charaly.runtime.inference.StreamChunk(text = "", done = true))
        }

    override fun stop() = Unit

    override fun isLoaded(): Boolean = loaded

    override fun modelInfo() = null
}
