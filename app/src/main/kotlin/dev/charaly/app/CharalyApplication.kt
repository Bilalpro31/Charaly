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
import dev.charaly.runtime.net.HttpTransport
import dev.charaly.runtime.pack.DemoStoryPacks
import dev.charaly.runtime.persistence.FileCharalyStorage
import dev.charaly.runtime.persistence.JsonCharalyRepository
import dev.charaly.runtime.session.CharalyRuntime
import dev.charaly.runtime.session.WorldDefinitionResolver
import dev.charaly.runtime.storypack.BuiltInStoryCatalog
import dev.charaly.runtime.storypack.JsonStoryPackRegistry
import dev.charaly.runtime.storypack.PackInstallStore
import dev.charaly.runtime.storypack.RemoteStoryCatalog
import dev.charaly.runtime.storypack.StoryCatalog
import dev.charaly.runtime.storypack.StoryCatalogItem
import dev.charaly.runtime.storypack.StoryPackDownloadManager
import dev.charaly.runtime.storypack.StoryPackDownloadMetadata
import dev.charaly.runtime.storypack.StoryPackDownloadSource
import dev.charaly.runtime.storypack.StoryPackInstaller

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

        /** The single crash record, in app-private storage. */
        const val CRASH_FILE = "last-crash.txt"

        /**
         * Where the story catalog is published.
         *
         * Discovery only: nothing in a story can reach it, and a fetch that fails leaves the
         * built-in listings on screen with a footnote.
         */
        const val STORY_CATALOG_URL = "https://catalog.charaly.dev/story-packs.json"
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

    /**
     * What is installed on this device, and which editions of it.
     *
     * Its own document from the pack *content* store on purpose: "installed" is a claim -
     * validated, checksummed, landed whole - and it is only made true here, after an import
     * has committed. See `dev.charaly.runtime.storypack.JsonStoryPackRegistry`.
     */
    val packRegistry: dev.charaly.runtime.storypack.StoryPackRegistry by lazy {
        JsonStoryPackRegistry(FileCharalyStorage(dev.charaly.app.model.CharalyPaths.documents(this)))
    }

    /**
     * Where installed pack editions physically live.
     *
     * Under `documents()` rather than `cacheDir`: a pack is user-visible content with saves
     * pinned to its editions, and the system may reclaim cache without asking. Staging
     * happens inside this tree and is swept on cold start.
     */
    val packStore: PackInstallStore by lazy {
        PackInstallStore(java.io.File(dev.charaly.app.model.CharalyPaths.documents(this), "installed-packs"))
    }

    /**
     * The one door content comes through: inspect, import, export, delete.
     *
     * Export counts stories per pack through the repository so the delete guard reads from
     * the same source the Library lists from - a second tally would eventually disagree and
     * either block a harmless delete or allow a harmful one.
     */
    val packInstaller: StoryPackInstaller by lazy {
        StoryPackInstaller(
            packs = repository,
            registry = packRegistry,
            store = packStore,
            engineVersion = BuildConfig.VERSION_NAME,
            storyCountFor = { packId ->
                repository.listInstances().count { it.storyPackId.value == packId }
            },
        )
    }

    /**
     * The single socket in the app.
     *
     * One transport for the model Hub and for the story catalog, because "the app needs the
     * network for X" should be answerable by listing one field - and because two transports
     * would eventually disagree about timeouts and user agents on the same connection.
     */
    private val httpTransport: HttpTransport by lazy {
        dev.charaly.app.net.AndroidHttpTransport()
    }

    /**
     * What the catalog *offers*, as opposed to what is installed.
     *
     * The built-in listings are the three demo packs expressed as ordinary catalog entries
     * - same ids-are-not-filenames rules, same download pipeline. Wrapped in the remote
     * catalog so a server can publish more, with the built-ins as the offline floor: a
     * catalog that cannot be fetched degrades to these, with a footnote, and never blanks
     * the screen.
     */
    val storyCatalog: StoryCatalog by lazy {
        val builtIn = BuiltInStoryCatalog(
            items = DemoStoryPacks.all.map { pack ->
                StoryCatalogItem(
                    id = "builtin.${pack.id.value}",
                    packId = pack.id.value,
                    packVersion = pack.semanticVersion,
                    title = pack.title,
                    description = pack.description,
                    author = pack.author,
                    premise = pack.identity.premise,
                    tags = pack.genreChips(),
                    sizeBytes = 0,
                    download = StoryPackDownloadMetadata(source = StoryPackDownloadSource.BUILTIN),
                )
            },
        )
        RemoteStoryCatalog(
            transport = httpTransport,
            manifestUrl = STORY_CATALOG_URL,
            fallback = builtIn,
        )
    }

    /**
     * Download -> verify -> install -> register, for catalog packs.
     *
     * Built-in packs take the *same* pipeline: `builtinSource` simply hands the exporter the
     * demo's own `StoryPack`, so the bytes are produced, checksummed, decoded, validated and
     * installed exactly like a downloaded one. A seeding shortcut here is how the pipeline
     * stops being the pipeline.
     */
    val packDownloads: StoryPackDownloadManager by lazy {
        StoryPackDownloadManager(
            transport = httpTransport,
            installer = packInstaller,
            temporaryDirectory = java.io.File(cacheDir, "story-downloads"),
            builtinSource = { item ->
                DemoStoryPacks.byId(item.packId)?.let { packInstaller.exportPack(it) }
            },
        )
    }

    val charaly: CharalyRuntime by lazy {
        CharalyRuntime(
            repository = repository,
            engine = inferenceEngine,
            // The world's static half, resolved through the pack *editions* a story pinned:
            // a playthrough begun in 1.1.0 reads 1.1.0's characters and places even after
            // 1.2.0 is installed beside it, which is the anti-corruption rule of the whole
            // pack system - and needs both halves wired to the installer to hold.
            resolver = WorldDefinitionResolver(repository, packInstaller),
            params = GenerationParams(
                maxTokens = 320,
                temperature = 0.85f,
                topP = 0.95f,
                topK = 40,
                minP = 0.05f,
                repeatPenalty = 1.1f,
            ),
            library = characterLibrary,
            // The one lazy loader. Every story turn goes through it, and so does the
            // ViewModel's own pre-flight, so "which model is resident" cannot have two
            // answers.
            residency = modelResidency,
            editions = packInstaller,
        )
    }

    /**
     * The single lazy loader for "this binding -> its weights in memory".
     *
     * Owns the load lock for the whole process, so a story turn and a model switch cannot
     * interleave into two half-loaded models. See [dev.charaly.app.model.LocalModelResidency].
     */
    val modelResidency: dev.charaly.app.model.LocalModelResidency by lazy {
        dev.charaly.app.model.LocalModelResidency(
            registry = modelRegistry,
            engine = inferenceEngine,
        )
    }

    override fun onCreate() {
        super.onCreate()
        // The last line of defence for a crash the app did not catch itself.
        //
        // ## What this can and cannot see
        //
        // This handler observes *uncaught Java/Kotlin throwables* - the ones that
        // escape a coroutine scope, a thread, or the main looper. It is installed
        // before anything else starts, so even a crash during the restore below
        // is recorded.
        //
        // It CANNOT see native crashes. A SIGSEGV or SIGABRT inside llama.cpp is
        // delivered as a signal to the process, not as a Java throwable, and no
        // Java handler runs for it - the kernel kills the process first. Those
        // crashes are recorded by the platform as tombstones in logcat, not here.
        // This file is for the Kotlin half of the app, and it is honest about
        // that limit rather than pretending to be a crash reporter.
        //
        // The previous handler is always invoked after the report is written, so
        // the system's own crash dialog and process death still happen exactly as
        // they did before - this only adds a record, it does not swallow anything.
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrashReport(thread, throwable) }
            previousHandler?.uncaughtException(thread, throwable)
        }
        // Warm the repositories on a background thread: launching the app must
        // never require the network, and must not block the main thread. The pack
        // installer's sweep rides along: an import interrupted by a crash leaves a
        // staging directory behind, and cold start is the moment that is cleaned up.
        Thread(
            {
                kotlinx.coroutines.runBlocking {
                    runCatching { charaly.restore() }
                    runCatching { packInstaller.sweep() }
                }
            },
            "charaly-restore",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Writes the last uncaught exception to app-private storage.
     *
     * One file, overwritten each time: the interesting crash is always the most
     * recent one, and a phone that just lost a multi-gigabyte import to a crash
     * has no room for a crash archive. The write is synchronous and wrapped -
     * a crash handler that throws would replace one crash with another.
     */
    private fun writeCrashReport(thread: Thread, throwable: Throwable) {
        val timestamp = java.text.SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            java.util.Locale.US,
        ).format(java.util.Date())
        val report = buildString {
            appendLine("Charaly crash report")
            appendLine("Time: $timestamp")
            appendLine("Thread: ${thread.name}")
            appendLine()
            appendLine(throwable.stackTraceToString())
            // The cause chain matters as much as the top frame: an import that
            // failed inside a registry write surfaces as the write's exception,
            // and the real story is one level down.
            var cause = throwable.cause
            while (cause != null) {
                appendLine()
                appendLine("Caused by: ${cause.stackTraceToString()}")
                cause = cause.cause
            }
        }
        java.io.File(filesDir, CRASH_FILE).writeText(report)
    }

    /**
     * The last crash report, or blank when the app has never crashed.
     *
     * Read by the developer panel, which is the one place a raw stack trace
     * belongs: it is behind Developer Mode, and it is the first thing to look at
     * when a user reports "the app closed".
     */
    fun readCrashReport(): String = runCatching {
        java.io.File(filesDir, CRASH_FILE).takeIf { it.isFile }?.readText().orEmpty()
    }.getOrDefault("")

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
