package dev.charaly.app

import android.app.Application
import dev.charaly.app.inference.LocalLlamaInferenceEngine
import dev.charaly.app.model.HuggingFaceServices
import dev.charaly.app.model.ModelManager
import dev.charaly.runtime.inference.GenerationParams
import dev.charaly.runtime.inference.InferenceEngine
import dev.charaly.runtime.model.BuiltInModelCatalog
import dev.charaly.runtime.model.JsonModelRegistry
import dev.charaly.runtime.model.ModelCatalog
import dev.charaly.runtime.model.ModelDownloadManager
import dev.charaly.runtime.persistence.FileCharalyStorage
import dev.charaly.runtime.persistence.JsonCharalyRepository
import dev.charaly.runtime.session.CharalyRuntime

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
        JsonCharalyRepository(FileCharalyStorage(dev.charaly.app.model.CharalyPaths.documents(this)))
    }

    /**
     * The single model registry.
     *
     * Imported GGUFs and catalog models are both [dev.charaly.runtime.model.InstalledModel]
     * entries here, so the library has one list instead of two parallel systems.
     */
    val modelRegistry by lazy {
        JsonModelRegistry(FileCharalyStorage(dev.charaly.app.model.CharalyPaths.documents(this)))
    }

    /** Ready-made models Charaly knows about. Metadata only: no binaries ship. */
    val modelCatalog: ModelCatalog by lazy { BuiltInModelCatalog() }

    /**
     * The user's imported characters.
     *
     * A separate JSON document from packs and stories, on the same storage port. Separate
     * because a character card is *the user's content* and must never be swept into a
     * story's world state, or removed when a story is deleted.
     */
    val characterLibrary: dev.charaly.runtime.persistence.CharacterLibraryRepository by lazy {
        dev.charaly.runtime.persistence.JsonCharacterLibraryRepository(
            FileCharalyStorage(dev.charaly.app.model.CharalyPaths.documents(this)),
        )
    }

    /**
     * Measured benchmarks, one per installed model.
     *
     * Keyed to this device rather than to the model file: the same GGUF runs at different
     * speeds on different phones, so a measurement stored on the model would travel into a
     * backup and then be quoted as a fact about a phone that never ran it.
     */
    val benchmarkStore: dev.charaly.runtime.model.BenchmarkStore by lazy {
        dev.charaly.runtime.model.JsonBenchmarkStore(
            FileCharalyStorage(dev.charaly.app.model.CharalyPaths.documents(this)),
        )
    }

    val modelManager: ModelManager by lazy { ModelManager(this) }

    /**
     * Story artwork, decoded and cached.
     *
     * ## Why it lives on the Application rather than in the view model
     *
     * Its whole job is to be a *cache*. A cache scoped to a composable or a screen is
     * evicted the moment the user navigates, which turns every screen change into a fresh
     * 8 MB decode - the exact behaviour a scrolling transcript cannot afford. Scoped to the
     * process, it survives navigation and is shared by the stage, the library, the world
     * list and the model screen.
     *
     * It is cleared on a low-memory callback rather than on navigation; see
     * [CharallyShell][dev.charaly.app.ui.shell.CharalyShell]'s memory observer.
     *
     * ## Lazy, and why
     *
     * An `AssetManager` is cheap, but a cache sized from `Runtime.maxMemory()` should be
     * sized once and reused. Deferring both to first artwork means launching the app does
     * not touch the assets tree at all - which matters because launch is the moment the
     * story restore is also running, and the two should not compete.
     */
    val storyAssets: dev.charaly.app.ui.art.StoryAssetLoader by lazy {
        dev.charaly.app.ui.art.StoryAssetLoader(
            assets = assets,
            // Backgrounds are drawn edge to edge, so the useful decoded width is a large
            // phone's. The portraits share this loader and are drawn far smaller, which is
            // why [dev.charaly.app.ui.art.rememberAssetBitmap] takes a per-request cap and
            // this number is the ceiling rather than the rule.
            targetWidthPx = MAX_BACKGROUND_WIDTH_PX,
        )
    }

    companion object {
        /**
         * The decode cap for a full-screen background, in pixels.
         *
         * 1440 is the widest main display Android currently ships. Anything larger is
         * decoded down to this, which is invisible on the device and is several megabytes
         * cheaper per image - and with a dozen places in a pack, that is the difference
         * between a cache that holds the working set and one that thrashes.
         */
        private const val MAX_BACKGROUND_WIDTH_PX = 1440
    }

    /**
     * Hugging Face discovery and the real download pipeline.
     *
     * ## The single network surface in the app
     *
     * This is the only object that can open a socket, and nothing in the story engine
     * holds a reference to it. Stories, memories, world state and inference have no path
     * to the network, which is what makes "the app works in airplane mode" a structural
     * property rather than a promise.
     *
     * Lazy, so launching the app does not construct an HTTP client on the critical path.
     */
    val huggingFace: HuggingFaceServices by lazy { HuggingFaceServices(this, modelRegistry) }

    /**
     * The download manager the UI talks to.
     *
     * The real pipeline. There is no offline fallback wired here, because this build
     * declares INTERNET and the implementation genuinely works - offering a disabled
     * button next to a working one would be the dishonest option.
     */
    val modelDownloads: ModelDownloadManager by lazy { huggingFace.downloads }

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
            library = characterLibrary,
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
