package dev.charaly.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.charaly.app.AppPreferences
import dev.charaly.app.CharalyApplication
import dev.charaly.app.inference.LocalLlamaInferenceEngine
import dev.charaly.app.model.HuggingFaceServices
import dev.charaly.app.model.LlamaBenchmarkRunner
import dev.charaly.app.model.ModelEntry
import dev.charaly.app.model.ModelManager
import dev.charaly.runtime.model.BenchmarkPhase
import dev.charaly.runtime.model.BenchmarkRecord
import dev.charaly.runtime.model.DownloadProgress
import dev.charaly.runtime.model.DownloadState
import dev.charaly.runtime.model.ModelCatalogItem
import dev.charaly.runtime.net.NetworkCapability
import dev.charaly.runtime.net.huggingface.HuggingFaceClient
import dev.charaly.runtime.net.huggingface.HuggingFaceFile
import dev.charaly.runtime.net.huggingface.HuggingFaceFileListing
import dev.charaly.runtime.net.huggingface.HuggingFaceRepo
import dev.charaly.runtime.net.huggingface.quantRank
import dev.charaly.runtime.presentation.HubDetailState
import dev.charaly.runtime.presentation.HubFileOption
import dev.charaly.runtime.presentation.HubFilter
import dev.charaly.runtime.presentation.HubRepoCard
import dev.charaly.runtime.presentation.HubRepoDetail
import dev.charaly.runtime.presentation.HubSearchState
import dev.charaly.runtime.presentation.ModelHubPresenter
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.PersonaBinding
import dev.charaly.runtime.domain.StoryDuration
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.StoryPackId
import dev.charaly.runtime.domain.WorldDefinition
import dev.charaly.runtime.engine.StoryCreationOptions
import dev.charaly.runtime.inference.LoadOutcome
import dev.charaly.runtime.inference.ModelLoadRequest
import dev.charaly.runtime.model.InstalledModel
import dev.charaly.runtime.model.ModelCatalog
import dev.charaly.runtime.model.ModelDownloadManager
import dev.charaly.runtime.model.ModelProfile
import dev.charaly.runtime.model.ModelRegistry
import dev.charaly.runtime.model.UnavailableModelDownloads
import dev.charaly.runtime.presentation.PackShowcase
import dev.charaly.runtime.presentation.Loc
import dev.charaly.runtime.presentation.PackShowcaseBuilder
import dev.charaly.runtime.presentation.SessionCard
import dev.charaly.runtime.pack.DemoStoryPacks
import dev.charaly.runtime.presentation.BenchmarkPresenter
import dev.charaly.runtime.presentation.CharacterImportPresenter
import dev.charaly.runtime.presentation.GenerationPhase
import dev.charaly.runtime.presentation.LibrarySnapshot
import dev.charaly.runtime.presentation.MemoryPanelSnapshot
import dev.charaly.runtime.presentation.ModelDetailSnapshot
import dev.charaly.runtime.presentation.ModelLibrarySnapshot
import dev.charaly.runtime.presentation.NewStoryDraft
import dev.charaly.runtime.presentation.NewStorySnapshot
import dev.charaly.runtime.presentation.NewStoryStep
import dev.charaly.runtime.presentation.NewStoryPresenter
import dev.charaly.runtime.presentation.PackDetailSnapshot
import dev.charaly.runtime.presentation.RegistryModelStatus
import dev.charaly.runtime.presentation.StoryInfoSnapshot
import dev.charaly.runtime.presentation.StorySnapshot
import dev.charaly.runtime.presentation.WorldPanelSnapshot
import dev.charaly.runtime.session.CharalyError
import dev.charaly.runtime.session.CharalyRuntime
import dev.charaly.runtime.session.GenerationUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The whole app's UI state, in one immutable value.
 *
 * Two rules hold everywhere in this file:
 *  1. the ViewModel owns no world state - it holds the latest snapshot the runtime
 *     handed back and projects it through the presenters in the runtime module;
 *  2. every world-affecting action is a call into [CharalyRuntime], which routes it
 *     through the event engine. There is no path from a button to `WorldState`.
 */
data class CharalyUiState(
    val loading: Boolean = true,
    val packs: List<StoryPack> = emptyList(),
    val instances: List<StoryInstance> = emptyList(),
    val installedModels: List<InstalledModel> = emptyList(),
    val activeModelId: String = "",
    /**
     * The id of the model the inference engine currently holds in RAM.
     *
     * Deliberately *not* the same thing as [model]: this is residency, a performance fact
     * that flips back to null whenever the process restarts or a load fails. Nothing gates
     * a button on it. See [dev.charaly.runtime.model.ModelSelection].
     */
    val loadedModelId: String? = null,
    /**
     * THE authoritative "which model, and can it run" answer.
     *
     * Every screen and every layer reads this one value. It exists because the device bug
     * it fixes was four layers answering that question differently - registry YES, story
     * YES, engine NO, chat NO - and the chat screen believed the weakest of them and told
     * a user with a working imported GGUF to go and connect a model.
     */
    val model: dev.charaly.runtime.model.ModelSelection = dev.charaly.runtime.model.ModelSelection(),

    // story session
    val story: StoryInstance? = null,
    val streamingText: String = "",
    val generationPhase: GenerationPhase = GenerationPhase.IDLE,
    val failureMessage: String = "",

    // worlds (discovery)
    val worldsQuery: String = "",
    val worldsGenres: Set<String> = emptySet(),

    // library (the reader's own stories)
    val libraryQuery: String = "",

    // models
    val modelFilter: dev.charaly.runtime.presentation.ModelFilter =
        dev.charaly.runtime.presentation.ModelFilter.ALL,
    val modelBusy: Boolean = false,

    /**
     * Where an import has got to.
     *
     * ## Why this is not a boolean
     *
     * Importing a GGUF is a multi-step operation whose two halves take wildly different
     * amounts of time: the SAF copy is bounded by storage throughput and can run for
     * minutes on a 4 GB file, while the header read is a few hundred kilobytes and is
     * effectively instant.
     *
     * A single "importing" flag cannot describe both. It is either true for the whole
     * operation - so the user watches a spinner that looks frozen - or false the moment
     * the copy lands - so the UI briefly claims the model is absent while it is being
     * registered, which is the confusing half.
     *
     * So the two steps are separate states, and the screen can say "Importing…" while
     * bytes move and "Checking…" while the header is parsed. [READY] is the terminal
     * success state: the file is on disk and registered. It is *not* [ModelSelection]'s
     * `LOADED` - the weights have not been read into RAM yet, and the runtime resolves
     * that lazily on the first turn.
     */
    val modelImport: ModelImportState = ModelImportState.NONE,

    // ---- imported characters --------------------------------------------
    //
    // The import flow's own state. `READING` and `PREVIEW` are separate steps rather than
    // one flag because nothing may be written before the user confirms: the preview has to
    // be a screen the user can back out of, not a spinner that resolves into a saved
    // record.
    val characterImport: dev.charaly.runtime.presentation.CharacterImportSnapshot =
        dev.charaly.runtime.presentation.CharacterImportPresenter.idle(),
    /** The user's imported characters, for the cast picker and the library screen. */
    val importedCharacters: List<dev.charaly.runtime.persistence.ImportedCharacter> = emptyList(),
    /**
     * The picked file's bytes, held between preview and confirmation.
     *
     * Held so that confirming cannot re-read the file and import something other than what
     * was previewed. Cleared on commit *and* on cancel, so it cannot survive into a later
     * import.
     */
    val pendingCardBytes: ByteArray? = null,
    /** How the pending card was stored, so its JSON can be kept for future importers. */
    val pendingCardSource: CharCardFileSource? = null,
    /** The card's own text. Kept for the library record, never rendered. */
    val pendingCardJson: String = "",

    // ---- the model test --------------------------------------------------
    //
    // The result of a real load-and-generate, for Settings → Diagnostics. Held in state so
    // the screen renders it without recomputing, and reset by [clearModelTest] rather
    // than on navigation, so a result survives the user going away and coming back.
    val modelTest: dev.charaly.app.model.ModelTestPhase = dev.charaly.app.model.ModelTestPhase.IDLE,

    // ---- benchmarks ------------------------------------------------------
    //
    // Live phase for the one model being measured, and the measured records keyed by model
    // id. Records are held in state so the library can render a measurement without
    // suspending in a composable.
    val benchmarkPhase: dev.charaly.runtime.model.BenchmarkPhase? = null,
    val benchmarkModelId: String = "",
    val benchmarkRecords: Map<String, dev.charaly.runtime.model.BenchmarkRecord> = emptyMap(),
    val benchmarkFailureMessage: String = "",

    // Model Hub
    val hubQuery: String = "",
    val hubFilter: HubFilter = HubFilter.MOST_DOWNLOADED,
    val hubRepos: List<HubRepoCard> = emptyList(),
    val hubState: HubSearchState = HubSearchState.Idle,
    val hubDetail: HubRepoDetail? = null,
    val hubDetailState: HubDetailState = HubDetailState.Idle,
    /** Live progress for the one download in flight. */
    val download: ModelDownloads.UiState = ModelDownloads.UiState.IDLE,
    /**
     * Whether the device can reach the internet.
     *
     * Affects exactly one thing - the Model Library's ability to browse and download. Every
     * story on this device is unaffected, which is why this is a single flag rather than a
     * "the app is offline" mode that gates the UI.
     */
    val isOnline: Boolean = true,

    // new story wizard
    val newStoryDraft: NewStoryDraft? = null,
    val newStoryStep: NewStoryStep = NewStoryStep.SCENARIO,
    /** Set by createStory; the shell uses it to hand off to the immersive screen. */
    val lastCreatedStoryId: String? = null,

    // app preferences
    val onboardingComplete: Boolean = false,
    val developerMode: Boolean = false,
    val darkTheme: Boolean = true,
    val reduceMotion: Boolean = false,
    /**
     * The active UI language, as a two-letter code.
     *
     * Held in state rather than read from `Locale.getDefault()` at each call site: the user
     * can pick a language that is not the device language, and every presenter has to be
     * able to see that immediately without waiting for a configuration change.
     */
    val languageTag: String = "en",
    val lastFailedGeneration: String = "",

    val notice: String? = null,
    val error: String? = null,
) {
    /**
     * Whether a turn can be requested.
     *
     * Reads [model] - the authoritative selection - rather than [loadedModelId]. A model
     * that is installed and bound but still loading is generatable: the runtime resolves it
     * on the first turn.
     */
    val canGenerate: Boolean
        get() = model.canGenerate && !generationPhase.isBusy() && story != null

    val hasInstalledModel: Boolean get() = installedModels.isNotEmpty()
}

/**
 * What one repository lookup returned: the record, its file list, and the headers read.
 *
 * A named type rather than a [Triple] because the three parts have different optionality
 * and a positional `Pair(repo, listing, headers)` would need destructuring that the
 * compiler cannot check - which is how a `headers` map silently ends up being the file
 * listing in one of the two call sites.
 */
/**
 * A card file that has been chosen and read, before it has been parsed.
 *
 * A named type rather than a `Triple` because the three parts have different meanings: the
 * name is for the UI, the bytes are the card, and the source decides how to get the card's
 * own text back out for storage. Destructuring them positionally at the one call site is
 * the kind of thing that quietly swaps a name for a byte array.
 */
/**
 * What a GGUF import is doing right now.
 *
 * Deliberately excludes "loaded into RAM": that is
 * [dev.charaly.runtime.model.ModelSelection.isResident], a fact about the engine rather
 * than about the import, and conflating the two is what makes a screen show a model as
 * "ready" the moment a file lands on disk.
 */
enum class ModelImportState {
    /** No import is running. */
    NONE,

    /** Bytes are moving from the picked file into app-private storage. */
    IMPORTING,

    /** The copy finished; the GGUF header is being read and the entry registered. */
    VERIFYING,

    /** The file is on disk and registered. The weights are not in RAM yet. */
    READY,
    ;

    val isRunning: Boolean get() = this == IMPORTING || this == VERIFYING
}

/**
 * A card file that has been chosen and read, before it has been parsed.
 *
 * A named type rather than a `Triple` because the three parts have different meanings: the
 * name is for the UI, the bytes are the card, and the source decides how to get the card's
 * own text back out for storage. Destructuring them positionally at the one call site is
 * the kind of thing that quietly swaps a name for a byte array.
 */
private data class PickedCard(
    val name: String,
    val bytes: ByteArray,
    val source: CharCardFileSource,
)

/**
 * What one repository lookup returned: the record, its file list, and the headers read.
 *
 * A named type rather than a [Triple] because the three parts have different optionality
 * and a positional `Pair(repo, listing, headers)` would need destructuring that the
 * compiler cannot check - which is how a `headers` map silently ends up being the file
 * listing in one of the two call sites.
 */
private data class HubRepoPayload(
    val repo: HuggingFaceRepo,
    val listing: HuggingFaceFileListing,
    val headers: Map<String, ByteArray>,
)

/**
 * Rebuilds a Hub record from a card, for the detail lookup.
 *
 * The list endpoint and the detail lookup are two different requests, and the second does
 * not need tags to be re-fetched - the file tree carries no tags. So the card's own
 * fields are carried over and only the identity is reconstructed, which means a detail
 * screen opened from a search result shows the same title and description the result did.
 */
private fun String.toRepoRecord(cards: List<HubRepoCard>): HuggingFaceRepo {
    val card = cards.firstOrNull { it.repoId == this }
    return HuggingFaceRepo(
        repoId = this,
        author = card?.author ?: substringBefore('/'),
        name = card?.title ?: substringAfter('/'),
        description = card?.description.orEmpty(),
        license = card?.license.orEmpty(),
    )
}

/**
 * Rebuilds the Hub record from an already-projected detail.
 *
 * The download path needs the same identity fields the detail screen showed, so the two
 * cannot disagree about which repository a file belongs to.
 */
private fun HubRepoDetail.toRepoRecord(): HuggingFaceRepo = HuggingFaceRepo(
    repoId = repoId,
    author = author,
    name = title,
    description = description,
    license = license,
    tags = tags,
)

/** What the model situation is, in terms the UI can act on. */
sealed interface EngineStatus {
    data object Unknown : EngineStatus
    data object Loading : EngineStatus
    data class Ready(val name: String) : EngineStatus
    data class Failed(val reason: String) : EngineStatus
}

class CharalyViewModel(
    private val runtime: CharalyRuntime,
    private val models: ModelManager,
    private val registry: ModelRegistry,
    private val catalog: ModelCatalog,
    private val preferences: AppPreferences,
    /**
     * The real download pipeline.
     *
     * Injected rather than reached for through the application so the view model stays
     * constructible in a test. `CharalyApplication` wires the Android-backed
     * implementation; a test wires `UnavailableModelDownloads` and the library honestly
     * reports that downloads are unavailable.
     */
    private val downloads: ModelDownloadManager = UnavailableModelDownloads(),
    /**
     * Hub discovery.
     *
     * Null when no transport is wired. The model library then says "internet connection
     * required" instead of showing a search field that silently returns nothing - a
     * control that looks functional and is not is worse than one that is honestly
     * absent. Installed models are unaffected either way, which is the whole point of the
     * offline-first design.
     */
    private val hub: HuggingFaceServices? = null,
    /**
     * Connectivity, for the offline indicator.
     *
     * Null in tests, which is treated as offline - the pessimistic reading, and the same
     * one `NetworkCapability.offline` defaults to. Only the Hub's reachability depends on
     * it; nothing else in the app asks.
     */
    private val network: dev.charaly.app.net.NetworkStatus? = null,
    /**
     * Reads the files the user picks.
     *
     * A port rather than a `Context`, so the import flow is testable on the JVM without a
     * content provider, a storage grant or a device - and so a test can hand in a file
     * that is not a card and assert on what the user is told. Null in tests, which makes
     * every import fail with "The file could not be opened" rather than crashing.
     */
    private val cardFiles: dev.charaly.app.ui.CardFileReader? = null,
    /**
     * Where measured benchmarks are stored.
     *
     * Injected for the same reason as everything else here: the view model never reaches
     * for a singleton, so a test can assert on what it records. Null means benchmarks
     * report that they could not measure.
     */
    private val benchmarkStore: dev.charaly.runtime.model.BenchmarkStore? = null,
    /**
     * The engine a story would use.
     *
     * Taken as the port rather than as a concrete class so the benchmark measures the
     * engine that is actually installed, and so a test can pass a scripted engine and
     * assert the flow without native code.
     */
    private val inferenceEngine: dev.charaly.runtime.inference.InferenceEngine? = null,
) : ViewModel() {

    private val draftJson = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _state = MutableStateFlow(CharalyUiState())
    val state: StateFlow<CharalyUiState> = _state.asStateFlow()

    private val engineStatus = MutableStateFlow<EngineStatus>(EngineStatus.Unknown)

    /**
     * Serialises native model loads.
     *
     * llama.cpp holds exactly one model, so two overlapping `loadModel` calls unload each
     * other's work. Boot, opening a story and creating one all load, and on a device they
     * can overlap. See [loadModel].
     */
    private val modelLoadLock = kotlinx.coroutines.sync.Mutex()

    private var generationJob: Job? = null

    /**
     * The model test's collection job.
     *
     * Separate from [generationJob] on purpose: cancelling a story generation must not
     * abandon a test the user is watching, and a test must not be able to cancel a story
     * mid-sentence. Both hold the same native engine, though, which is why the test refuses
     * to start while another model load is in flight.
     */
    private var testJob: Job? = null

    init {
        applyLanguage()
        refresh()
        observeNetwork()
    }

    /**
     * Pushes the stored language into the runtime catalogue and re-reads the preferences.
     *
     * Called at construction so no presenter is ever asked for a string before the locale
     * is known, and again whenever the user changes it.
     */
    fun applyLanguage() {
        val stored = preferences.languageTag.takeIf { it.isNotBlank() }
            ?: java.util.Locale.getDefault().language
        Loc.use(stored)
        _state.update { it.copy(languageTag = Loc.code()) }
    }

    /**
     * Switches the UI language.
     *
     * Persisted so it survives a reboot, and applied to the runtime catalogue immediately
     * so the presenters - which own most of the product's copy - re-render in the new
     * language without waiting for an Android configuration change.
     */
    fun setLanguage(tag: String) {
        preferences.languageTag = tag
        applyLanguage()
        _state.update { it.copy(languageTag = Loc.code()) }
    }

    /**
     * Keeps the offline indicator honest.
     *
     * Observed rather than polled, so it changes when connectivity does. On going offline
     * the Hub state is replaced with the offline state immediately - leaving stale search
     * results on screen next to a download button that is about to fail would be worse
     * than showing the truth a moment earlier.
     *
     * Deliberately does *not* touch installed models, the active story, or anything else:
     * that is the entire point of a local-first product, and a connectivity callback that
     * could clear a library would be a serious defect.
     */
    private fun observeNetwork() {
        val status = network ?: return
        viewModelScope.launch {
            status.observe().collect { online ->
                val wasOnline = _state.value.isOnline
                _state.update { it.copy(isOnline = online) }
                if (wasOnline && !online && _state.value.hubRepos.isNotEmpty()) {
                    _state.update {
                        it.copy(hubState = HubSearchState.offline(), hubDetail = null)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Model Hub: discovery
    // ------------------------------------------------------------------

    /**
     * Searchs the Hub's GGUF index.
     *
     * Debounced by the caller rather than here, because a composable's own
     * `LaunchedEffect` keyed on the query is the only place that knows when typing has
     * paused. This method performs exactly one request.
     *
     * A failure keeps the previous results on screen and sets [HubSearchState.Failed],
     * because clearing a list on error makes a flaky network look like "the Hub has
     * nothing".
     */
    fun searchHub(query: String) {
        val services = hub ?: run {
            _state.update { it.copy(hubState = HubSearchState.offline()) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(hubState = HubSearchState.Searching(query)) }
            val filter = _state.value.hubFilter
            // One page, not a flow: the library shows one screen of results and re-queries
            // when the filter or the term changes. Collecting a flow here would leave a
            // second page arriving under the first and no way to tell which is which.
            val outcome = runCatching {
                services.client.searchGguf(
                    query = query,
                    sort = when (filter) {
                        HubFilter.MOST_DOWNLOADED -> HuggingFaceClient.Sort.DOWNLOADS
                        HubFilter.MOST_LIKED -> HuggingFaceClient.Sort.LIKES
                        HubFilter.RECENTLY_UPDATED -> HuggingFaceClient.Sort.LAST_MODIFIED
                    },
                )
            }
            outcome
                .onSuccess { page ->
                    _state.update {
                        it.copy(
                            hubQuery = query,
                            hubRepos = ModelHubPresenter.browse(page, query, filter).repos,
                            hubState = ModelHubPresenter.searchStateFor(
                                query = query,
                                isOnline = true,
                                resultCount = page.repos.size,
                            ),
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            hubState = HubSearchState.Failed(
                                message = error.message?.takeIf { it.isNotBlank() }
                                    ?: "The model library could not be reached.",
                                actionLabel = "Retry",
                                offline = NetworkCapability.offline(),
                            ),
                        )
                    }
                }
        }
    }

    /**
     * Reads one repository's file list and classifies each file from its own header.
     *
     * The header fetch is a ranged request for a few hundred kilobytes per file, so this
     * is a few hundred kilobytes in total rather than a few gigabytes - which is what
     * lets the library say "ready for Charaly" *before* asking anyone to spend their
     * data.
     */
    fun openHubRepo(repoId: String) {
        val services = hub ?: run {
            _state.update {
                it.copy(
                    hubDetail = null,
                    hubDetailState = HubDetailState.Failed(
                        repoId = repoId,
                        message = "You're offline.",
                        offline = true,
                    ),
                )
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(hubDetail = null, hubDetailState = HubDetailState.Loading(repoId)) }
            val ram = models.availableRamBytes()
            val outcome = runCatching {
                val listing = services.listFiles(repoId)
                val headers = mutableMapOf<String, ByteArray>()
                // Only the files a user is likely to consider: the best few, best first.
                // Classifying all thirty quantisations of a large repository would cost
                // thirty header fetches to answer a question about three of them.
                val candidates = listing.ggufFiles
                    .sortedWith(
                        compareByDescending<HuggingFaceFile> { it.quantRank }.thenBy { it.sizeBytes },
                    )
                    .take(HEADER_CLASSIFY_LIMIT)
                for (file in candidates) {
                    headers[file.path] = services.client.fetchHeader(file) ?: ByteArray(0)
                }
                HubRepoPayload(
                    repo = repoId.toRepoRecord(_state.value.hubRepos),
                    listing = listing,
                    headers = headers,
                )
            }
            outcome
                .onSuccess { payload ->
                    val detail = ModelHubPresenter.detail(
                        repo = payload.repo,
                        listing = payload.listing,
                        headers = payload.headers,
                        availableRamBytes = ram,
                        // Matched on file name, because that is what both sides know: the
                        // Hub's path and the path Charaly wrote to its own storage.
                        installedFileNames = _state.value.installedModels
                            .map { it.absolutePath.substringAfterLast('/') }
                            .toSet(),
                    )
                    _state.update { it.copy(hubDetail = detail, hubDetailState = HubDetailState.Loaded(repoId)) }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            hubDetail = null,
                            hubDetailState = HubDetailState.Failed(
                                repoId = repoId,
                                message = error.message?.takeIf { message -> message.isNotBlank() }
                                    ?: "This model could not be opened.",
                                offline = NetworkCapability.offline(),
                            ),
                        )
                    }
                }
        }
    }

    fun closeHubRepo() = _state.update { it.copy(hubDetail = null, hubDetailState = HubDetailState.Idle) }

    fun setHubQuery(query: String) {
        _state.update { it.copy(hubQuery = query) }
        if (query.isBlank()) return
        searchHub(query)
    }

    fun setHubFilter(filter: HubFilter) {
        _state.update { it.copy(hubFilter = filter) }
        // Re-run rather than re-sorting locally: the Hub's ranking is the source of truth
        // for "most downloaded", and a local sort would be a different, worse answer.
        searchHub(_state.value.hubQuery)
    }

    /**
     * Starts a real download of one Hub file.
     *
     * Goes through [ModelDownloadManager], which is the resumable, checksum-verifying,
     * install-then-register pipeline. The item is built from the *observed* header via
     * [ModelHubPresenter.toCatalogItem], so the pipeline's own refusal of an architecture
     * this engine cannot load is computed from the same fact the UI displayed.
     */
    fun startHubDownload(detail: HubRepoDetail, file: HubFileOption) {
        val services = hub ?: run {
            _state.update { it.copy(error = "Internet connection required.") }
            return
        }
        val repo = detail.toRepoRecord()
        viewModelScope.launch {
            // Re-resolve the file from the Hub rather than trusting the row's copy: a
            // listing can change between opening a repository and pressing Download, and
            // the download needs the *current* URL, size and LFS hash. A file that has
            // gone away says so instead of starting a transfer that cannot finish.
            val source = runCatching { services.listFiles(detail.repoId) }
                .getOrNull()
                ?.ggufFiles
                ?.firstOrNull { it.path == file.path }
            if (source == null) {
                _state.update { it.copy(error = "That file is no longer available on the Hub.") }
                return@launch
            }
            val item = ModelHubPresenter.toCatalogItem(repo, source, file.verdict)
            // Held so Resume and Retry can re-issue exactly this item, which is the only
            // thing carrying the Hub URL, the real size and the LFS hash that
            // verification needs.
            pendingDownloadItem = item
            _state.update { it.copy(modelBusy = true, download = ModelDownloads.UiState(catalogId = item.id)) }
            downloads.download(item).collect { frame ->
                _state.update { it.copy(download = frame.toUiState()) }
                finishDownload(frame)
            }
            _state.update { it.copy(modelBusy = false) }
        }
    }

    /**
     * Resumes a paused or failed download.
     *
     * Re-issues the same resolved item rather than restarting: the pipeline reads the
     * partial file's length back off disk and sends a ranged request from there, so a
     * pause that was followed by the process being killed costs the user nothing.
     *
     * The item is kept rather than reconstructed, because only it carries the Hub URL,
     * the real size and the LFS hash the verification step needs.
     */
    fun resumeDownload() {
        val item = pendingDownloadItem ?: return
        viewModelScope.launch {
            _state.update { it.copy(modelBusy = true) }
            downloads.download(item).collect { frame ->
                _state.update { it.copy(download = frame.toUiState()) }
                finishDownload(frame)
            }
            _state.update { it.copy(modelBusy = false) }
        }
    }

    fun pauseDownload() = downloads.pause(_state.value.download.catalogId)

    fun cancelDownload() {
        downloads.cancel(_state.value.download.catalogId)
        pendingDownloadItem = null
        _state.update { it.copy(download = ModelDownloads.UiState.IDLE, modelBusy = false) }
    }

    /**
     * The item behind the download currently in view.
     *
     * Held so Resume and Retry have something real to re-issue. Deliberately a single
     * value rather than a map: one transfer at a time is the correct behaviour on a phone,
     * where two multi-gigabyte downloads compete for the same storage.
     */
    private var pendingDownloadItem: ModelCatalogItem? = null

    /**
     * What happens when a download reaches a terminal frame.
     *
     * A READY frame means the pipeline has already verified the checksum, parsed the
     * header, moved the file into place and written a registry entry - so the only thing
     * left is to refresh the registry and make the model selectable. Split out because
     * both the initial download and a resume arrive here, and duplicating it would be how
     * one of them quietly forgets to activate.
     */
    private suspend fun finishDownload(frame: DownloadProgress) {
        when (frame.state) {
            DownloadState.READY -> {
                val installed = runCatching { registry.list() }.getOrDefault(_state.value.installedModels)
                val installedModel = installed.firstOrNull { it.id == frame.installedModelId }
                _state.update {
                    it.copy(
                        installedModels = installed,
                        notice = frame.message.ifBlank { "${frame.catalogId} is ready" },
                    )
                }
                // Activate immediately. A model that installs successfully but needs a
                // second tap to become usable is a broken install.
                if (installedModel != null) selectModel(installedModel)
            }

            DownloadState.FAILED, DownloadState.CANCELLED, DownloadState.UNSUPPORTED ->
                _state.update { it.copy(modelBusy = false) }

            else -> Unit
        }
    }

    /** The state the library shows when no Hub transport is reachable. */
    private fun hubOffline() = HubSearchState.Failed(
        message = "You're offline.",
        actionLabel = "Installed models still work",
        offline = true,
    )

    // ------------------------------------------------------------------
    // Boot
    // ------------------------------------------------------------------

    /**
     * Offline restore: read local documents, seed the demo packs if the library is
     * empty, then load the model the user last used.
     *
     * No network is involved anywhere in this function. That is why the app works
     * in airplane mode once a model is on the device.
     */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val restored = runCatching { runtime.restore() }.getOrElse { error ->
                _state.update {
                    it.copy(loading = false, error = error.message ?: "could not read local stories")
                }
                return@launch
            }

            // Seeding is idempotent per pack, not "only when the library is empty":
            // an existing install gets the new demo worlds without losing the user's
            // own packs, and a fresh install still has something to play with.
            val existingIds = restored.packs.map { it.id }.toSet()
            val missing = DemoStoryPacks.all.filterNot { it.id in existingIds }
            runCatching { missing.forEach { runtime.createPack(it) } }
            val packs = runCatching { runtime.listPacks() }.getOrDefault(restored.packs)

            // Imported GGUF files and registry entries are reconciled on every
            // launch, so "installed" always means "a file exists". Interrupted
            // transfers are swept first: a `.part` left by a process that was killed
            // mid-copy is not a model, and on a phone it is often several gigabytes of
            // storage the user cannot otherwise reclaim.
            runCatching { models.cleanupPartialCopies() }
            runCatching { models.syncRegistry(registry) }
            val installed = runCatching { registry.list() }.getOrDefault(emptyList())
            val active = runCatching { registry.activeId() }.getOrNull().orEmpty()

            val restoredInstances = runCatching { runtime.listInstances() }.getOrDefault(restored.instances)
            runCatching { restoredInstances.forEach { touchIfNewer(it) } }

            // The user's imported characters and this device's measurements, read on launch
            // so the cast picker and the model library are populated before anything asks
            // for them. Both are best-effort: a library that will not read leaves the app
            // fully usable, which is the same rule stories follow.
            val imported = runCatching { runtime.listImportedCharacters() }.getOrDefault(emptyList())
            val records = benchmarkRunner()?.let { runner ->
                runCatching { runner.recordsByModelId() }.getOrDefault(emptyMap())
            }.orEmpty()

            val status = when (val model = installed.firstOrNull { it.id == active }) {
                null -> EngineStatus.Failed(Loc.t("model.no_model_yet"))
                else -> loadModel(model)
            }

            _state.update {
                it.copy(
                    loading = false,
                    packs = packs,
                    instances = restoredInstances,
                    installedModels = installed,
                    activeModelId = active,
                    onboardingComplete = preferences.onboardingComplete,
                    developerMode = preferences.developerMode,
                    darkTheme = preferences.darkTheme,
                    reduceMotion = preferences.reduceMotion,
                    languageTag = Loc.code(),
                    importedCharacters = imported,
                    benchmarkRecords = records,
                    notice = if (restored.failures.isEmpty()) {
                        null
                    } else {
                        "${restored.failures.size} story file(s) could not be read and were skipped."
                    },
                )
            }
            engineStatus.value = status
            // The selection is derived from the registry, so it must be recomputed once
            // boot has settled the registry and the engine.
            syncModelState()
        }
    }

    // ------------------------------------------------------------------
    // Projections
    //
    // Every screen renders one of these. They are pure functions of authoritative state,
    // assembled here and nowhere else, so a screen cannot disagree with the engine about
    // who is speaking, where the player is, or what a world contains.
    // ------------------------------------------------------------------

    private fun now(): Long = System.currentTimeMillis()

    fun definitions(): Map<String, WorldDefinition> = _state.value.packs.associate { pack ->
        pack.id.value to WorldDefinition(pack.characters, pack.locations)
    }

    /** HOME - the world lobby. */
    fun lobbySnapshot() = dev.charaly.runtime.presentation.LobbyPresenter.build(
        nowEpochMs = now(),
        instances = _state.value.instances,
        packs = _state.value.packs,
        definitions = definitions(),
        model = registryModelStatus(),
    )

    /** WORLDS - discovery. */
    fun worldsSnapshot(
        query: String = _state.value.worldsQuery,
        genres: Set<String> = _state.value.worldsGenres,
    ) = dev.charaly.runtime.presentation.WorldsFeedPresenter.build(
        nowEpochMs = now(),
        instances = _state.value.instances,
        packs = _state.value.packs,
        query = query,
        selectedGenres = genres,
    )

    /** LIBRARY - every story on this device. */
    fun libraryShelf(query: String = _state.value.libraryQuery) =
        dev.charaly.runtime.presentation.LibraryShelfPresenter.build(
            nowEpochMs = now(),
            instances = _state.value.instances,
            query = query,
        )

    /** THE STAGE - the currently open story. */
    fun stageSnapshot(): dev.charaly.runtime.presentation.ChatStage? {
        val current = _state.value
        val instance = current.story ?: return null
        return dev.charaly.runtime.presentation.ChatStagePresenter.build(
            instance = instance,
            pack = current.packs.firstOrNull { it.id == instance.storyPackId },
            definition = definitionFor(instance),
            phase = current.generationPhase,
            streamingText = current.streamingText,
            failureMessage = current.failureMessage,
            // The authoritative selection, not "is something resident". This single line
            // is the chat half of the reported bug.
            model = current.model,
        )
    }

    /**
     * The four contextual sheets.
     *
     * Built from the same instance the stage renders, so a tab's summary and its contents
     * cannot come from different worlds.
     */
    fun stageContext(): dev.charaly.runtime.presentation.StoryContext? {
        val instance = _state.value.story ?: return null
        return dev.charaly.app.ui.sheets.StageSheets.build(
            definition = definitionFor(instance),
            instance = instance,
        )
    }

    /** A world's showcase. The strict projection: no roster, no events, no locations. */
    fun showcase(packId: String): dev.charaly.runtime.presentation.PackShowcase? {
        val pack = packById(packId) ?: return null
        val hasStory = _state.value.instances.any { it.storyPackId == pack.id }
        return dev.charaly.runtime.presentation.PackShowcaseBuilder.build(pack, canContinue = hasStory)
    }

    /** The most recent playthrough of a world, for the showcase's Continue action. */
    fun resumableStory(packId: String): SessionCard? =
        dev.charaly.runtime.presentation.SessionsPresenter.build(
            nowEpochMs = now(),
            instances = _state.value.instances.filter { it.storyPackId.value == packId },
            packs = _state.value.packs,
        ).sessions.firstOrNull()

    /** The pack behind a route, or null for one that no longer exists. */
    fun packById(packId: String): StoryPack? =
        _state.value.packs.firstOrNull { it.id.value == packId }

    /** The full pack snapshot: used by Developer Mode, where a roster genuinely is the point. */
    fun packDetailSnapshot(packId: String): PackDetailSnapshot? {
        val pack = packById(packId)
        return dev.charaly.runtime.presentation.PackDetailPresenter.buildOrNull(
            nowEpochMs = now(),
            pack = pack,
            instances = _state.value.instances,
            defaultProfileName = profileOf(pack?.defaultModelProfileId.orEmpty()).name,
        )
    }

    /** MODEL HUB - the hero and the card list. */
    fun modelHubSnapshot(
        filter: dev.charaly.runtime.presentation.ModelFilter = _state.value.modelFilter,
    ): ModelHubScreenSnapshot {
        val snapshot = dev.charaly.runtime.presentation.ModelLibraryPresenter.build(
            installedModels = _state.value.installedModels,
            catalog = catalog.items,
            activeModelId = _state.value.activeModelId,
            loadedModelId = _state.value.loadedModelId,
            // This build declares INTERNET and wires a real transfer pipeline, so the
            // library is not lying when it offers a Download button. The flag reflects the
            // pipeline's own availability, and the pipeline still refuses a model the
            // engine cannot load.
            downloadsAvailable = downloads.availability() == null,
            availableRamBytes = models.availableRamBytes(),
            query = "",
            // This device's own measurements. Absent for a model that was never measured,
            // and the presenter renders that as "Not measured" rather than guessing.
            benchmarks = _state.value.benchmarkRecords,
            measuringModelId = _state.value.benchmarkModelId,
            measuringPhaseLabel = benchmarkPhaseLabel(_state.value.benchmarkPhase),
        )
        return ModelHubScreenSnapshot(
            hero = dev.charaly.runtime.presentation.ModelStagePresenter.hero(
                snapshot = snapshot,
                engineLabel = runtime.engineInfo(),
                // The active model's own verdict, so the hero and the card beneath it read
                // from the same measurement and cannot disagree.
                speed = snapshot.installed
                    .firstOrNull { it.isActive }
                    ?.speed
                    ?: snapshot.installed.firstOrNull()?.speed
                    ?: dev.charaly.runtime.presentation.BenchmarkPresenter.verdict(record = null),
            ),
            cards = dev.charaly.runtime.presentation.ModelBrowsePresenter.browse(
                installed = snapshot.installed,
                repos = _state.value.hubRepos,
                filter = filter,
            ),
            canDownload = snapshot.canDownload,
            totalInstalledBytes = snapshot.totalInstalledBytes,
            // The library's speed banner: how much has been measured, and what a running
            // benchmark is doing. Its own field so the count has exactly one source.
            speedSummary = snapshot.speedSummary,
        )
    }

    /**
     * A running benchmark's phase, as words.
     *
     * Every one of these is a thing that actually happened: the loader reported its own
     * percentage, or the decode started. Nothing here advances on a timer, which is what
     * keeps a benchmark bar from being theatre.
     */
    private fun benchmarkPhaseLabel(phase: BenchmarkPhase?): String = when (phase) {
        null -> ""
        BenchmarkPhase.PREPARING -> "Preparing…"
        is BenchmarkPhase.LOADING -> "Loading model… ${phase.percent}%"
        BenchmarkPhase.MEASURING -> "Generating…"
        BenchmarkPhase.COMPLETE -> "Done"
    }

    /** One installed model, in full. */
    fun modelDetail(modelId: String): ModelDetailSnapshot? {
        val current = _state.value
        val model = current.installedModels.firstOrNull { it.id == modelId } ?: return null
        val binding = current.story?.takeIf { it.modelBinding.installedModelId == modelId }?.modelBinding
        return dev.charaly.runtime.presentation.ModelLibraryPresenter.detail(
            model = model,
            binding = binding,
            storiesUsing = current.instances.filter { it.modelBinding.installedModelId == modelId },
            isLoaded = current.loadedModelId == modelId,
            availableRamBytes = models.availableRamBytes(),
        )
    }

    /** ENTER WORLD - the opening, role and cast. */
    fun enterWorldSnapshot(): NewStorySnapshot? {
        val draft = _state.value.newStoryDraft ?: return null
        val pack = _state.value.packs.firstOrNull { it.id.value == draft.packId } ?: return null
        return dev.charaly.runtime.presentation.NewStoryPresenter.build(
            draft = draft,
            pack = pack,
            installedModels = _state.value.installedModels,
            activeModelId = _state.value.activeModelId,
            step = _state.value.newStoryStep,
            // The user's own characters, offered alongside the pack's cast. Read from state
            // rather than from a repository so the wizard stays a pure projection and the
            // screen cannot suspend.
            imported = _state.value.importedCharacters,
            inUseBy = importedUsage(),
            // One selection, resolved once, shared with the runtime that will create the
            // story. The wizard and the story therefore cannot name different models.
            model = dev.charaly.runtime.model.ModelSelectionResolver.resolveForNewStory(
                packDefaultProfileId = pack.defaultModelProfileId,
                requestedModelId = draft.modelId,
                installed = _state.value.installedModels,
                activeModelId = _state.value.activeModelId,
                residentModelId = if (runtime.isModelLoaded()) _state.value.loadedModelId else null,
                fileExists = { candidate -> modelFileExists(candidate) },
                engineSupports = { candidate ->
                    candidate.architecture.isBlank() ||
                        dev.charaly.runtime.model.EngineCapabilities.supports(candidate.architecture)
                },
            ),
        )
    }

    /**
 * The world clock, for the developer panel.
 *
 * Read from the live instance rather than formatted from a cached string, so the panel and
 * the stage header cannot show different times for the same story.
 */
    fun worldClockLine(): String {
        val story = _state.value.story ?: return ""
        return "${story.worldClock.now.formatClock()} · Day ${story.worldClock.now.day}"
    }

    /**
     * What one plain turn costs, in words.
     *
     * Quoted from the runtime's policy rather than restated, so the panel documents the
     * rule that is actually running. Including the ceiling matters: a developer who jumps
     * four hours manually should be able to see what conversation would have cost instead.
     */
    fun turnCostLabel(): String {
        val plain = runtime.turnCost(userInputChars = 0, appliedActions = 0).minutes
        val withActions = runtime.turnCost(userInputChars = 0, appliedActions = 2).minutes
        val ceiling = dev.charaly.runtime.session.TurnClockPolicy.MAX_TURN_MINUTES
        return "A turn costs $plain min, $withActions min with actions, at most $ceiling."
    }

    /** The imported-characters library screen. */
    fun importedCharactersSnapshot(): dev.charaly.runtime.presentation.ImportedCharactersSnapshot =
        dev.charaly.runtime.presentation.CharacterImportPresenter.library(
            imported = _state.value.importedCharacters,
            inUseBy = importedUsage(),
        )

    /** A story being read rather than played. */
    fun storyRecord(instanceId: String): StoryRecordSnapshot? {
        val instance = _state.value.instances.firstOrNull { it.id.value == instanceId } ?: return null
        val pack = _state.value.packs.firstOrNull { it.id == instance.storyPackId }
        return StoryRecordSnapshot(
            title = instance.displayTitle,
            worldName = instance.packTitle,
            moment = dev.charaly.runtime.presentation.StoryMomentPresenter.sentenceFor(instance),
            contextLine = listOfNotNull(
                dev.charaly.runtime.presentation.StoryContextPresenter
                    .timeOfDayLabel(instance.worldClock.now.hour)
                    .takeIf { it.isNotBlank() },
                "Day ${instance.worldClock.now.day}".takeIf { instance.worldClock.now.day > 1 },
            ).joinToString(" · "),
            beats = dev.charaly.runtime.presentation.StoryPresenter.build(
                instance = instance,
                pack = pack,
                definition = definitionFor(instance),
            ).lines.mapNotNull { it.toBeat() },
            chapters = dev.charaly.runtime.presentation.StoryInfoPresenter.build(
                nowEpochMs = now(),
                instance = instance,
                pack = pack,
            ).chapters.map { chapter ->
                dev.charaly.runtime.presentation.ChapterCard(
                    index = chapter.index,
                    title = chapter.title,
                    summary = chapter.summary,
                    locationName = chapter.locationName,
                    participantNames = chapter.participantNames,
                    timeLabel = chapter.timeLabel,
                    turnCount = chapter.turnCount,
                    isCurrent = chapter.isCurrent,
                )
            },
        )
    }

    /**
     * The model status the lobby and the settings screen both read.
     *
     * One adapter, so "is my model ready" cannot have two different answers depending on
     * which screen asked.
     */
    private fun registryModelStatus() = RegistryModelStatus(
        resolved = _state.value.model,
        installed = currentInstalledModel(),
        engineLabel = runtime.engineInfo(),
        loading = engineStatus.value is EngineStatus.Loading,
    )

    /**
     * The download in flight, as the hub draws it.
     *
     * A projection of the pipeline's own frames, assembled here so the screen never has to
     * do arithmetic and never has a place to invent a percentage.
     */
    fun transferProgress(): dev.charaly.runtime.presentation.TransferProgress =
        dev.charaly.runtime.presentation.ModelStagePresenter.transfer(_state.value.download.lastFrame())

    /** The active or loaded model's display name, or an honest "none yet". */
    fun currentModelName(): String = currentInstalledModel()?.displayName ?: "No model installed"

    /** Selects an installed model by id. Returns false when the id names nothing. */
    fun selectModelById(modelId: String): Boolean {
        val model = installedModel(modelId) ?: return false
        selectModel(model)
        return true
    }

    fun deleteModelById(modelId: String): Boolean {
        val model = installedModel(modelId) ?: return false
        deleteModel(model)
        return true
    }

    fun verifyModelById(modelId: String): Boolean {
        val model = installedModel(modelId) ?: return false
        verifyModel(model)
        return true
    }

    /**
     * Throws away the last reply and generates it again.
     *
     * The stage's Retry action. A failure that offers "Try again" and cannot is worse than
     * one that offers nothing.
     */
    fun retryGeneration() {
        val story = _state.value.story ?: return
        regenerate()
        if (story.conversation.lastUserEntry() == null) {
            // Nothing to retry: the turn never reached the transcript. Send an empty line
            // so the character continues rather than leaving the stage inert.
            continueGeneration()
        }
    }

    /** A one-line notice, used for actions that have no screen of their own. */
    fun announce(message: String) = _state.update { it.copy(notice = message) }

    fun profileOf(profileId: String): ModelProfile =
        dev.charaly.runtime.model.ModelProfileLibrary.resolve(profileId)

    fun engineStatus(): EngineStatus = engineStatus.value

    fun installedModel(modelId: String): InstalledModel? =
        _state.value.installedModels.firstOrNull { it.id == modelId }

    fun catalogItems() = catalog.items

    // ------------------------------------------------------------------
    // Navigation-facing actions
    // ------------------------------------------------------------------

    /** Opens a story and marks it played. */
    fun openStory(instanceId: String) {
        viewModelScope.launch {
            val instance = runtime.loadStory(StoryInstanceId(instanceId)) ?: return@launch
            val touched = runCatching { runtime.touch(instance, now()) }.getOrDefault(instance)
            bindModelForStory(touched)
            syncModelState()
            _state.update {
                it.copy(
                    story = touched,
                    streamingText = "",
                    generationPhase = GenerationPhase.IDLE,
                    failureMessage = "",
                    lastFailedGeneration = "",
                )
            }
            refreshInstances()
        }
    }

    /** The most recent playthrough of a pack, opened directly. */
    fun continueMostRecent(packId: String) {
        viewModelScope.launch {
            preferences.newStoryDraftJson = ""
            val instance = runCatching { runtime.continueStory(StoryPackId(packId)) }.getOrNull()
                ?: return@launch
            val touched = runCatching { runtime.touch(instance, now()) }.getOrDefault(instance)
            bindModelForStory(touched)
            _state.update { it.copy(story = touched, failureMessage = "", streamingText = "") }
            refreshInstances()
        }
    }

    // ------------------------------------------------------------------
    // New story
    // ------------------------------------------------------------------

    fun beginNewStory(packId: String) {
        val pack = _state.value.packs.firstOrNull { it.id.value == packId } ?: return
        setDraft(NewStoryPresenter.initialDraft(pack), NewStoryStep.SCENARIO)
    }

    /**
     * Makes sure a draft exists for [packId], restoring or seeding one if it does not.
     *
     * ## Why the wizard needed this
     *
     * `Route.EnterWorld` is restored from the saved back stack after process death, but the
     * draft used to live only in the ViewModel. A user who imported a multi-gigabyte GGUF
     * through the system picker - long enough, and memory-hungry enough, for the process to
     * be killed - came back to a restored wizard with no draft. The screen then rendered
     * "This world is not here." with no Continue control, and no way to reach the story they
     * were setting up.
     *
     * Called from the shell whenever the route is on screen, so entering the wizard always
     * has a draft behind it. A draft for a *different* pack is replaced, because a draft is
     * only ever meaningful for the world being entered.
     */
    fun ensureNewStoryDraft(packId: String) {
        val pack = _state.value.packs.firstOrNull { it.id.value == packId } ?: return
        val existing = _state.value.newStoryDraft
        if (existing != null && existing.packId == packId) return
        val restored = restoredDraft()?.takeIf { it.packId == packId }
        setDraft(restored ?: NewStoryPresenter.initialDraft(pack), NewStoryStep.SCENARIO)
    }

    /**
     * Discards the draft.
     *
     * Cancelling story setup must leave the model state untouched - and does, because the
     * draft and the registry are entirely separate documents. What it must not do is leave
     * a stale draft behind that a later visit to the same world would silently restore.
     */
    fun cancelNewStory() {
        preferences.newStoryDraftJson = ""
        _state.update { it.copy(newStoryDraft = null, newStoryStep = NewStoryStep.SCENARIO) }
    }

    fun newStoryStep(step: NewStoryStep) = _state.update { it.copy(newStoryStep = step) }

    fun updateDraft(transform: (NewStoryDraft) -> NewStoryDraft) {
        _state.update { current ->
            val draft = current.newStoryDraft ?: return@update current
            val updated = transform(draft)
            current.copy(newStoryDraft = updated, newStoryStep = NewStoryPresenter.stepFor(updated))
        }
        persistDraft()
    }

    private fun setDraft(draft: NewStoryDraft, step: NewStoryStep) {
        _state.update { it.copy(newStoryDraft = draft, newStoryStep = step) }
        persistDraft()
    }

    private fun persistDraft() {
        val draft = _state.value.newStoryDraft ?: return
        preferences.newStoryDraftJson = runCatching {
            draftJson.encodeToString(NewStoryDraft.serializer(), draft)
        }.getOrDefault("")
    }

    private fun restoredDraft(): NewStoryDraft? {
        val raw = preferences.newStoryDraftJson.takeIf { it.isNotBlank() } ?: return null
        return runCatching { draftJson.decodeFromString(NewStoryDraft.serializer(), raw) }.getOrNull()
    }

    /**
     * Creates the StoryInstance and opens it.
     *
     * The draft is turned into [StoryCreationOptions] here, the *resolved* model
     * binding is stored inside the instance, and the runtime builds the world
     * through the event engine. The UI never constructs world state.
     */
    fun createStory(packId: String) {
        val current = _state.value
        val pack = current.packs.firstOrNull { it.id.value == packId } ?: return
        val draft = current.newStoryDraft ?: return

        viewModelScope.launch {
            val binding = NewStoryPresenter.resolveBinding(
                pack = pack,
                draft = draft,
                installedModels = current.installedModels,
                activeModelId = current.activeModelId,
                nowEpochMs = now(),
            )
            val scenario = pack.scenario(draft.scenarioId)
            val persona = pack.persona(draft.personaId)

            // The imported-character path goes through `startStoryWithImported`, which merges the
            // user's cast into a *copy* of the pack. A story with imported characters
            // therefore owns a world of its own and cannot affect any other playthrough of
            // the same pack - which is the isolation guarantee the rest of the codebase is
            // built on, and the reason this is not `startStory` with extra ids.
            val importedIds = draft.importedCharacterIds.map(::CharacterId).toSet()

            val instance = runCatching {
                val options = StoryCreationOptions(
                    instanceId = StoryInstanceId("story-${now()}"),
                    title = draft.storyTitle,
                    scenario = scenario,
                    persona = persona?.let {
                        PersonaBinding.from(it, draft.userName.ifBlank { it.name })
                    } ?: PersonaBinding.EMPTY,
                    startLocationId = scenario?.startLocationId,
                    focusCharacterId = draft.focusCharacterId.takeIf { it.isNotBlank() }
                        ?.let(::CharacterId),
                    castCharacterIds = draft.castCharacterIds.map(::CharacterId),
                    modelBinding = binding,
                    nowEpochMs = now(),
                )
                if (importedIds.isEmpty()) {
                    runtime.startStory(pack = pack, options = options)
                } else {
                    runtime.startStoryWithImported(
                        pack = pack,
                        options = options,
                        importedCharacterIds = importedIds,
                    )
                }
            }.getOrElse { error ->
                _state.update { it.copy(error = error.message ?: "could not start this story") }
                return@launch
            }

            bindModelForStory(instance)
            syncModelState()
            val brought = draft.importedCharacterIds.size
            _state.update {
                it.copy(
                    story = instance,
                    newStoryDraft = null,
                    newStoryStep = NewStoryStep.SCENARIO,
                    lastCreatedStoryId = instance.id.value,
                    notice = when (brought) {
                        0 -> "Stepped into ${instance.displayTitle}"
                        1 -> "Stepped into ${instance.displayTitle} with 1 imported character"
                        else -> "Stepped into ${instance.displayTitle} with $brought imported characters"
                    },
                )
            }
            refreshInstances()
        }
    }

    /** Clears the one-shot "a story was created" signal after the shell uses it. */
    fun consumeCreatedStory() = _state.update { it.copy(lastCreatedStoryId = null) }

    /**
     * The pack whose story setup is waiting, or null.
     *
     * Set while the wizard is open. Its only job is to give the Model Library a way back:
     * reaching Models from the story's "Change the voice" control used to be a one-way
     * trip, because that route hides the navigation and has no back control of its own. A
     * user who went to import a GGUF there had no visible way to return to the story they
     * were building.
     */
    fun pendingStorySetupPackId(): String? = _state.value.newStoryDraft?.packId

    /** True while a story is waiting for its user to finish setting it up. */
    fun hasPendingStorySetup(): Boolean = _state.value.newStoryDraft != null

    // ------------------------------------------------------------------
    // Character card import
    // ------------------------------------------------------------------

    /**
     * Reads a card the user picked, without writing anything.
     *
     * Two steps, and the gap between them is the design: `READING` shows while the file is
     * parsed, `PREVIEW` shows what was found, and the only way to reach storage is
     * [confirmCharacterImport]. A user who backs out of the preview has therefore mutated
     * nothing at all - which is the one claim a cancel button can honestly make.
     *
     * The bytes are read once and held, so confirming does not re-read the file and cannot
     * import something different from what was previewed.
     */
    fun readCharacterCard(uri: android.net.Uri) {
        _state.update { it.copy(characterImport = CharacterImportPresenter.reading(displayNameOf(uri))) }
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    PickedCard(
                        name = displayNameOf(uri),
                        bytes = readAllBytes(uri),
                        source = CharCardFileSource.of(uri),
                    )
                }
            }
            loaded.fold(
                onSuccess = { picked ->
                    val preview = runtime.previewCharacterCard(picked.bytes, picked.name)
                    _state.update {
                        it.copy(
                            characterImport = CharacterImportPresenter.fromResult(picked.name, preview),
                            pendingCardSource = picked.source,
                            pendingCardBytes = picked.bytes,
                            pendingCardJson = picked.source.jsonOf(picked.bytes),
                        )
                    }
                },
                onFailure = {
                    _state.update {
                        it.copy(
                            characterImport = CharacterImportPresenter.failed(
                                displayNameOf(uri),
                                dev.charaly.runtime.compat.CharacterCardError.UNREADABLE,
                            ),
                            pendingCardSource = null,
                            pendingCardBytes = null,
                            pendingCardJson = "",
                        )
                    }
                },
            )
        }
    }

    /**
     * Writes the previewed card.
     *
     * The single commit point. Nothing before this touched storage, so a failure here
     * leaves the library unchanged rather than half-written.
     */
    fun confirmCharacterImport() {
        val state = _state.value
        val preview = state.characterImport.preview ?: return
        val bytes = state.pendingCardBytes
        if (bytes == null || !state.characterImport.canConfirm) {
            // The bytes are gone, so the preview on screen describes a file we can no longer
            // re-read. Importing from the preview alone would risk writing something other
            // than what was shown.
            _state.update {
                it.copy(
                    characterImport = CharacterImportPresenter.failed(
                        state.characterImport.sourceName,
                        dev.charaly.runtime.compat.CharacterCardError.UNREADABLE,
                    ),
                )
            }
            return
        }

        viewModelScope.launch {
            val source = state.pendingCardSource
            val saved = runCatching {
                runtime.commitImportedCharacter(
                    preview = preview,
                    // The raw JSON is kept for a future importer version. `jsonOf` returns
                    // the card's own text for a JSON file and the decoded text for a PNG,
                    // so what is stored is always the card rather than a picture of it.
                    rawJson = source?.jsonOf(bytes).orEmpty().ifBlank { bytes.toString(Charsets.UTF_8) },
                    nowEpochMs = now(),
                )
            }.getOrElse {
                _state.update {
                    it.copy(
                        characterImport = CharacterImportPresenter.failed(
                            state.characterImport.sourceName,
                            dev.charaly.runtime.compat.CharacterCardError.UNREADABLE,
                        ),
                    )
                }
                return@launch
            }

            clearPendingCard()
            _state.update {
                it.copy(
                    characterImport = CharacterImportPresenter.imported(saved.preview),
                    notice = "Imported ${saved.name}",
                )
            }
            refreshImportedCharacters()
        }
    }

    /** Backing out of the preview. Writes nothing, by construction. */
    fun cancelCharacterImport() {
        clearPendingCard()
        _state.update { it.copy(characterImport = CharacterImportPresenter.cancelled()) }
    }

    /** Deletes an imported character from the user's library. */
    fun deleteImportedCharacter(id: String) {
        viewModelScope.launch {
            runCatching { runtime.deleteImportedCharacter(CharacterId(id)) }
            refreshImportedCharacters()
        }
    }

    /**
     * Adds or removes an imported character from the New Story draft's cast.
     *
     * Takes the caller's intent rather than inferring it from membership, so a screen cannot
     * desynchronise the draft by passing the wrong boolean - the same shape the pack's own
     * cast toggle uses.
     */
    fun toggleImportedCharacter(id: String, selected: Boolean) {
        _state.update { state ->
            val draft = state.newStoryDraft ?: return@update state
            state.copy(
                newStoryDraft = draft.copy(
                    importedCharacterIds = if (selected) {
                        draft.importedCharacterIds + id
                    } else {
                        draft.importedCharacterIds - id
                    },
                ),
            )
        }
    }

    private fun clearPendingCard() = _state.update {
        it.copy(pendingCardSource = null, pendingCardBytes = null, pendingCardJson = "")
    }

    private suspend fun refreshImportedCharacters() {
        val imported = runCatching { runtime.listImportedCharacters() }.getOrDefault(emptyList())
        _state.update { it.copy(importedCharacters = imported) }
    }

    // ------------------------------------------------------------------
    // Model test (Diagnostics)
    // ------------------------------------------------------------------

    /**
     * Loads a model and generates one real reply with it.
     *
     * ## What this proves, and what it deliberately does not
     *
     * Everything else about a model is an assertion made by some layer: the registry says
     * the file exists, the header says the architecture is supported, the selection says
     * it can generate. None of those prove tokens come out. This runs the engine and shows
     * what it said.
     *
     * So there is no success path that does not decode. A model that fails to load, fails
     * to generate, or generates nothing is reported as a failure with the engine's own
     * reason, and the text in the report came out of the model.
     *
     * It is not a benchmark: nothing is persisted, and the figure shown is labelled as
     * duration-only because a 24-token prompt says nothing comparable about a story turn.
     */
    fun runModelTest() {
        // One at a time, for the same reason benchmarks are: a single native handle.
        if (_state.value.modelTest.state.isRunning) return
        val model = currentInstalledModel()
        if (model == null) {
            _state.update {
                it.copy(
                    modelTest = dev.charaly.app.model.ModelTestPhase(
                        state = dev.charaly.app.model.ModelTestState.FAILED,
                        detail = "model-test.no_model",
                    ),
                )
            }
            return
        }

        testJob?.cancel()
        testJob = viewModelScope.launch {
            modelTestRunner()
                .run(model)
                .collect { phase -> _state.update { it.copy(modelTest = phase) } }
        }
    }

    /** Clears a finished test so the screen goes back to "not run yet". */
    fun clearModelTest() {
        testJob?.cancel()
        _state.update { it.copy(modelTest = dev.charaly.app.model.ModelTestPhase.IDLE) }
    }

    /**
     * The model under test: the one the open story is bound to, else the active one.
     *
     * Read through [CharalyUiState.model] rather than from `activeModelId` alone, so the
     * test runs against the model the user is actually about to write with.
     */
    fun modelTestSubject(): InstalledModel? = currentInstalledModel()

    // ------------------------------------------------------------------
    // Model benchmark
    // ------------------------------------------------------------------

    /**
     * Measures one installed model.
     *
     * Every state this method writes comes from a real event: the loader's own percentage,
     * then the decode, then the finished record. There is no timer and no simulated
     * progress - a benchmark that animated while nothing was being measured would be the
     * exact fabrication this codebase's speed labels exist to avoid.
     *
     * The model is restored to the user afterwards, so measuring never costs a story its
     * model.
     */
    fun benchmarkModel(modelId: String) {
        // One benchmark at a time: the app holds a single model resident, so two concurrent
        // runs would fight over the same native handle.
        if (_state.value.benchmarkModelId.isNotBlank()) return
        val model = _state.value.installedModels.firstOrNull { it.id == modelId } ?: return
        // No store means this build cannot record a measurement, so the honest response is
        // to say so rather than to run a benchmark whose result would be discarded.
        val runner = benchmarkRunner() ?: run {
            _state.update {
                it.copy(
                    benchmarkFailureMessage = BenchmarkPresenter.messageFor(
                        dev.charaly.runtime.model.BenchmarkFailedException(
                            dev.charaly.runtime.model.BenchmarkFailure.NoNativeEngine,
                        ),
                    ),
                )
            }
            return
        }

        viewModelScope.launch {
            _state.update {
                it.copy(
                    benchmarkModelId = modelId,
                    benchmarkPhase = BenchmarkPhase.PREPARING,
                    benchmarkFailureMessage = "",
                )
            }
            val result = runner.run(model) { phase ->
                _state.update { current -> current.copy(benchmarkPhase = phase) }
            }
            val byModelId = runner.recordsByModelId()

            result.fold(
                onSuccess = { measured ->
                    _state.update { current ->
                        current.copy(
                            benchmarkModelId = "",
                            benchmarkPhase = null,
                            benchmarkRecords = byModelId,
                            benchmarkFailureMessage = "",
                            // A measured model has demonstrably loaded, so its card can say
                            // so without a separate verification pass.
                            installedModels = current.installedModels.map { installed ->
                                if (installed.id != modelId) {
                                    installed
                                } else {
                                    installed.copy(
                                        compatibility = installed.compatibility.copy(
                                            loadFailed = false,
                                            failureReason = "",
                                            lastLoadedAtEpochMs = measured.measuredAtEpochMs,
                                        ),
                                    )
                                }
                            },
                        )
                    }
                },
                onFailure = { failure ->
                    _state.update { current ->
                        current.copy(
                            benchmarkModelId = "",
                            benchmarkPhase = null,
                            benchmarkRecords = byModelId,
                            benchmarkFailureMessage = BenchmarkPresenter.messageFor(failure),
                        )
                    }
                },
            )
        }
    }

    /** Drops a stored measurement, so the card goes back to "Not measured". */
    fun forgetBenchmark(modelId: String) {
        val runner = benchmarkRunner() ?: return
        viewModelScope.launch {
            runCatching { runner.forget(modelId) }
            val byModelId = runner.recordsByModelId()
            _state.update { it.copy(benchmarkRecords = byModelId) }
        }
    }

    /**
     * The one benchmark runner.
     *
     * Prefers the app's own inference engine, because that is the engine a story will
     * actually use: benchmarking a differently-configured instance would measure code the
     * user never runs. Falls back to a fresh engine only when the app is running without
     * native llama.cpp, in which case the benchmark will report that it could not measure
     * rather than inventing a figure.
     */
    private fun benchmarkRunner(): LlamaBenchmarkRunner? {
        val store = benchmarkStore ?: return null
        return LlamaBenchmarkRunner(
            registry = registry,
            store = store,
            engineProvider = {
                (inferenceEngine as? LocalLlamaInferenceEngine) ?: LocalLlamaInferenceEngine()
            },
            // The runtime module's own clock. Threaded through rather than called here so a
            // test can pin "now" and the stored measurement's timestamp is deterministic
            // instead of being whatever the wall clock happened to be.
            nowEpochMs = { now() },
        )
    }

    /**
     * The model test's engine.
     *
     * The *same* engine the story runtime uses, not a second instance. That is deliberate:
     * the test exists to answer "will inference work on this device", and the only way to
     * answer that honestly is to ask the engine the app actually talks to. A fresh
     * `LocalLlamaInferenceEngine()` would be a second llama.cpp context competing for RAM
     * with the first, which on a mid-range phone is precisely the condition the test is
     * supposed to be measuring.
     */
    private fun modelTestRunner(): dev.charaly.app.model.ModelTestRunner =
        dev.charaly.app.model.ModelTestRunner(
            engineProvider = {
                (inferenceEngine as? LocalLlamaInferenceEngine) ?: LocalLlamaInferenceEngine()
            },
            versionLabel = { LocalLlamaInferenceEngine.nativeVersion() },
        )

    // ------------------------------------------------------------------
    // Projections
    // ------------------------------------------------------------------

    // ---- benchmark and import helpers ----------------------------------

    /**
     * How many stories each imported character appears in.
     *
     * Read from the running instances rather than tracked in a counter, so a character
     * deleted from the library, or a story deleted outright, cannot leave a stale "in 2
     * stories" behind.
     */
    fun importedUsage(): Map<CharacterId, Int> {
        val instances = _state.value.instances
        return _state.value.importedCharacters.associate { record ->
            CharacterId(record.preview.id) to instances.count { instance ->
                instance.characters.containsKey(CharacterId(record.preview.id))
            }
        }
    }

    private fun displayNameOf(uri: android.net.Uri): String =
        cardFiles?.displayName(uri).orEmpty().ifBlank { "character card" }

    /**
     * Reads the picked file, bounded so a mis-tap on a video cannot exhaust memory.
     *
     * The cap is enforced *while* reading rather than after: a 4 GB file read into a byte
     * array has already caused the problem by the time its length is known.
     */
    private fun readAllBytes(uri: android.net.Uri): ByteArray =
        cardFiles?.read(uri) ?: error("could not open the selected file")

    // ------------------------------------------------------------------
    // Library filters
    // ------------------------------------------------------------------

    fun setWorldsQuery(query: String) = _state.update { it.copy(worldsQuery = query) }

    fun toggleWorldsGenre(genre: String) = _state.update { current ->
        val genres = if (genre in current.worldsGenres) {
            current.worldsGenres - genre
        } else {
            current.worldsGenres + genre
        }
        current.copy(worldsGenres = genres)
    }

    fun clearWorldsFilters() =
        _state.update { it.copy(worldsQuery = "", worldsGenres = emptySet()) }

    fun setLibraryQuery(query: String) = _state.update { it.copy(libraryQuery = query) }

    fun setModelFilter(filter: dev.charaly.runtime.presentation.ModelFilter) =
        _state.update { it.copy(modelFilter = filter) }

    // ------------------------------------------------------------------
    // Conversation
    // ------------------------------------------------------------------

    /**
     * Sends the player's line.
     *
     * Everything the composer does is routed through here, so a "+" menu button has
     * no privileged access to the world: it only changes the shape of the input.
     */
    fun send(text: String, characterId: CharacterId? = null) {
        val story = _state.value.story ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val speaker = characterId ?: story.focusCharacterId
        runGeneration(story, trimmed, speaker)
    }

    private fun runGeneration(instance: StoryInstance, input: String, speaker: CharacterId?) {
        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    generationPhase = GenerationPhase.THINKING,
                    streamingText = "",
                    failureMessage = "",
                    lastFailedGeneration = "",
                )
            }
            try {
                // LAZY LOAD.
                //
                // The engine may hold nothing yet - the import finished, the weights did not,
                // or the process was restarted. That is a normal state, not a failure, so it
                // is resolved here rather than by refusing the composer. Without this the
                // stage would sit at "connect a local model" forever while a perfectly valid
                // GGUF sat in the registry, and `runtime.respond` would return
                // `ModelNotLoaded` for a model that had never been asked to load.
                if (!ensureModelResident()) {
                    // The engine refused to load a model the registry considers usable. The
                    // engine's own reason is the specific one here - "not enough memory",
                    // "no native library" - and it is what the user can act on. The
                    // selection's own reason is empty in exactly this case, because from the
                    // registry's point of view nothing is wrong.
                    val engineReason = (engineStatus.value as? EngineStatus.Failed)?.reason
                    _state.update {
                        it.copy(
                            generationPhase = GenerationPhase.FAILED,
                            failureMessage = engineReason
                                ?.takeIf { reason -> reason.isNotBlank() }
                                ?: Loc.t("model.load_failed"),
                            lastFailedGeneration = engineReason.orEmpty(),
                        )
                    }
                    return@launch
                }

                runtime.respond(instance, input, speaker).collect { update ->
                    when (update) {
                        is GenerationUpdate.Started -> _state.update {
                            it.copy(generationPhase = GenerationPhase.STREAMING)
                        }

                        is GenerationUpdate.Chunk -> _state.update {
                            it.copy(generationPhase = GenerationPhase.STREAMING, streamingText = update.accumulated)
                        }

                        is GenerationUpdate.AppliedAction -> _state.update {
                            it.copy(notice = "World updated: ${update.changes.joinToString("; ")}")
                        }

                        is GenerationUpdate.RejectedAction -> _state.update {
                            it.copy(notice = "Ignored an invalid world action: ${update.review.reason}")
                        }

                        is GenerationUpdate.Finished -> {
                            val finished = update.instance
                            if (finished != null) {
                                _state.update {
                                    it.copy(
                                        story = finished,
                                        streamingText = "",
                                        generationPhase = if (update.stopReason ==
                                            dev.charaly.runtime.inference.StopReason.CANCELLED
                                        ) {
                                            GenerationPhase.STOPPED
                                        } else {
                                            GenerationPhase.IDLE
                                        },
                                    )
                                }
                            } else {
                                _state.update { it.copy(streamingText = "", generationPhase = GenerationPhase.IDLE) }
                            }
                            refreshInstances()
                        }

                        is GenerationUpdate.Failed -> {
                            val message = ErrorMessages.of(update.error)
                            _state.update {
                                it.copy(
                                    generationPhase = GenerationPhase.FAILED,
                                    failureMessage = message,
                                    lastFailedGeneration = message,
                                    streamingText = "",
                                )
                            }
                        }
                    }
                }
            } finally {
                _state.update {
                    if (it.generationPhase.isBusy()) {
                        it.copy(generationPhase = GenerationPhase.IDLE)
                    } else {
                        it
                    }
                }
            }
        }
    }

    /** Aborts native generation immediately. */
    fun stopGeneration() {
        generationJob?.cancel()
        runtime.stop()
        _state.update {
            it.copy(
                generationPhase = GenerationPhase.STOPPED,
                streamingText = "",
            )
        }
    }

    /** Throws away the last reply and generates it again. */
    fun regenerate() {
        val story = _state.value.story ?: return
        viewModelScope.launch {
            val (trimmed, lastInput) = runCatching { runtime.prepareRegeneration(story) }.getOrDefault(story to null)
            if (lastInput.isNullOrBlank()) {
                _state.update { it.copy(notice = "Nothing to regenerate yet") }
                return@launch
            }
            _state.update { it.copy(story = trimmed) }
            runGeneration(trimmed, lastInput, trimmed.focusCharacterId)
        }
    }

    /** Sends an empty line to let the character continue on their own. */
    fun continueGeneration() {
        val story = _state.value.story ?: return
        runGeneration(story, "", story.focusCharacterId)
    }

    /** Edits the last player line and re-runs from there. */
    fun editLastUserMessage(text: String) {
        val story = _state.value.story ?: return
        viewModelScope.launch {
            val lastCharacter = story.conversation.lastCharacterEntry()
            val trimmed = if (lastCharacter != null) {
                story.copy(conversation = story.conversation.dropFrom(lastCharacter.turn))
            } else {
                story
            }
            val saved = runCatching { runtime.save(trimmed) }.getOrDefault(trimmed)
            _state.update { it.copy(story = saved) }
            runGeneration(saved, text, saved.focusCharacterId)
        }
    }

    fun selectSpeaker(characterId: CharacterId) {
        val story = _state.value.story ?: return
        viewModelScope.launch {
            val saved = runCatching { runtime.setFocus(story, characterId) }.getOrDefault(story)
            _state.update { it.copy(story = saved) }
        }
    }

    /** Deterministic time travel. Never touches the model. */
    fun advanceClock(minutes: Long) {
        val story = _state.value.story ?: return
        viewModelScope.launch {
            val latest = runtime.loadStory(story.id) ?: story
            val advanced = runCatching { runtime.advance(latest, StoryDuration(minutes)) }.getOrNull()
                ?: return@launch
            _state.update {
                it.copy(
                    story = advanced,
                    notice = "Clock advanced to ${advanced.worldClock.now.formatClock()}",
                )
            }
            refreshInstances()
        }
    }

    // ------------------------------------------------------------------
    // Sessions
    // ------------------------------------------------------------------

    fun renameSession(instanceId: String, title: String) {
        viewModelScope.launch {
            val instance = runtime.loadStory(StoryInstanceId(instanceId)) ?: return@launch
            if (title.isBlank()) return@launch
            runCatching { runtime.renameStory(instance, title) }
            refreshInstances()
        }
    }

    fun deleteSession(instanceId: String) {
        viewModelScope.launch {
            runCatching { runtime.deleteStory(StoryInstanceId(instanceId)) }
            _state.update { current ->
                if (current.story?.id?.value == instanceId) {
                    current.copy(story = null, streamingText = "", generationPhase = GenerationPhase.IDLE)
                } else {
                    current
                }
            }
            refreshInstances()
        }
    }

    /** Branches a session into a new, independent story. */
    fun duplicateSession(instanceId: String) {
        viewModelScope.launch {
            val instance = runtime.loadStory(StoryInstanceId(instanceId)) ?: return@launch
            val branch = runCatching {
                runtime.duplicateStory(instance, StoryInstanceId("story-${now()}"), nowEpochMs = now())
            }.getOrNull() ?: return@launch
            _state.update { it.copy(notice = "Branched into ${branch.displayTitle}") }
            refreshInstances()
        }
    }

    // ------------------------------------------------------------------
    // Models
    // ------------------------------------------------------------------

    /**
     * Imports a GGUF the user picked, registers it, and loads it.
     *
     * Imported and downloaded models end up in exactly the same registry, so the
     * library has one list, not two.
     */
    fun importModel(uri: android.net.Uri) {
        viewModelScope.launch {
            // IMPORTING is a real state, not a spinner drawn over a finished list: a 4 GB
            // SAF copy takes a long time, and the whole time the model does not exist yet.
            _state.update { it.copy(modelBusy = true, modelImport = ModelImportState.IMPORTING, error = null) }
            models.importFrom(uri)
                .onSuccess { entry ->
                    _state.update { it.copy(modelImport = ModelImportState.VERIFYING) }
                    val registered = models.registerImported(entry, registry, catalog)

                    // Read the GGUF header *before* anything asks whether this model can
                    // run. Without it the registry entry has a blank architecture, and a
                    // blank architecture is read as "the engine does not know what this is"
                    // - which turned a perfectly good imported GGUF into a model whose Use
                    // control was disabled.
                    val verified = readAndRecordHeader(registered)

                    registry.setActive(registered.id)
                    val installed = runCatching { registry.list() }.getOrDefault(_state.value.installedModels)
                    _state.update {
                        it.copy(
                            modelBusy = false,
                            modelImport = ModelImportState.READY,
                            installedModels = installed,
                            activeModelId = registered.id,
                            notice = Loc.t("model.imported_ok", registered.displayName),
                        )
                    }
                    // State is complete and correct *before* the engine is touched, so the
                    // Continue button is usable immediately even while a multi-gigabyte
                    // model is still being read into memory.
                    syncModelState()

                    // Warm the engine in the background. Failure here is recorded and shown,
                    // but it no longer removes the model from the story flow.
                    launch { loadModel(verified) }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            modelBusy = false,
                            modelImport = ModelImportState.NONE,
                            error = importErrorMessage(error),
                        )
                    }
                    syncModelState()
                }
        }
    }

    /**
     * Reads the header and writes it into the registry entry.
     *
     * Returns the entry to load, with metadata when the read succeeded and the original
     * entry when it did not - an unreadable header is not a reason to refuse an import,
     * because the file is already on the device and the loader is the real authority.
     */
    private suspend fun readAndRecordHeader(model: InstalledModel): InstalledModel =
        models.readMetadata(ModelEntry(model.absolutePath, model.displayName, model.sizeBytes))
            .fold(
                onSuccess = { metadata ->
                    models.verifyInRegistry(
                        registry = registry,
                        modelId = model.id,
                        architecture = metadata.architecture.orEmpty(),
                        quantization = metadata.quantization.orEmpty(),
                        contextLength = metadata.contextLength?.toInt() ?: 0,
                    )
                    registry.get(model.id) ?: model
                },
                onFailure = { model },
            )

    /**
     * The message for a failed import, in user language.
     *
     * The raw exception text is never shown: `importFrom` fails with one of
     * `ModelManager`'s reason constants, which the user did not write and cannot act on.
     */
    private fun importErrorMessage(error: Throwable): String =
        when (error.message) {
            dev.charaly.app.model.ModelManager.REASON_NOT_GGUF -> Loc.t("model.invalid_gguf")
            dev.charaly.app.model.ModelManager.REASON_PARTIAL -> Loc.t("error.import_partial")
            dev.charaly.app.model.ModelManager.REASON_NO_SPACE -> Loc.t("error.no_space")
            dev.charaly.app.model.ModelManager.REASON_UNREADABLE -> Loc.t("error.import_unreadable")
            else -> Loc.t("error.import_failed")
        }

    fun selectModel(model: InstalledModel) {
        viewModelScope.launch {
            registry.setActive(model.id)
            _state.update {
                it.copy(
                    activeModelId = model.id,
                    installedModels = runCatching { registry.list() }.getOrDefault(it.installedModels),
                )
            }
            syncModelState()
            loadModel(model)
        }
    }

    fun deleteModel(model: InstalledModel) {
        viewModelScope.launch {
            models.delete(model)
            runCatching { registry.remove(model.id) }
            val installed = runCatching { registry.list() }.getOrDefault(emptyList())
            val active = runCatching { registry.activeId() }.getOrNull()
            _state.update {
                it.copy(
                    installedModels = installed,
                    activeModelId = active.orEmpty(),
                    loadedModelId = if (it.loadedModelId == model.id) null else it.loadedModelId,
                )
            }
            val next = installed.firstOrNull { it.id == active }
            engineStatus.value = if (next == null) {
                EngineStatus.Failed("No model installed yet. Import a GGUF to generate replies.")
            } else {
                loadModel(next)
            }
        }
    }

    /** Re-reads the GGUF header and marks the entry verified. */
    fun verifyModel(model: InstalledModel) {
        viewModelScope.launch {
            _state.update { it.copy(modelBusy = true) }
            models.readMetadata(dev.charaly.app.model.ModelEntry(model.absolutePath, model.displayName, model.sizeBytes))
                .onSuccess { metadata ->
                    models.verifyInRegistry(registry, model.id, metadata.architecture.orEmpty(), metadata.quantization.orEmpty(), metadata.contextLength?.toInt() ?: 0)
                }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "could not read the model header") }
                }
            _state.update { it.copy(modelBusy = false, installedModels = runCatching { registry.list() }.getOrDefault(_state.value.installedModels)) }
            syncModelState()
        }
    }

    /**
     * Points the current story at another model or profile.
     *
     * This changes the instance's binding only: no world state is touched, so a
     * story never silently becomes a different story.
     */
    fun rebindStory(model: InstalledModel?, profile: ModelProfile) {
        val story = _state.value.story ?: return
        viewModelScope.launch {
            val updated = if (model != null) {
                runCatching { runtime.rebindModel(story, model, profile) }.getOrNull()
            } else {
                runCatching { runtime.rebindProfile(story, profile) }.getOrNull()
            } ?: return@launch
            if (model != null) {
                loadModel(model)
            }
            _state.update { it.copy(story = updated, notice = "This story now runs ${profile.name}") }
            refreshInstances()
        }
    }

    /**
     * The model the UI should name.
     *
     * Read from [CharalyUiState.model], the one authoritative answer. The previous
     * version preferred the *loaded* id over the active one, which meant a model the user
     * had just selected was described by an older model that happened to still be resident.
     */
    private fun currentInstalledModel(): InstalledModel? {
        val current = _state.value
        return current.installedModels.firstOrNull { it.id == current.model.modelId }
            ?: current.installedModels.firstOrNull { it.id == current.activeModelId }
    }

    /**
     * Binds the engine to the model this story is bound to.
     *
     * ## Why a story with no binding adopts the active model
     *
     * A story created before any model was installed carries an empty binding - there was
     * nothing to bind to. Leaving it empty forever means the chat permanently claims the
     * story has no model even though the user has since imported one, which is the exact
     * shape of the reported bug. Adopting the active model here is not a silent swap: there
     * is nothing being swapped away from, and the binding is persisted so the identity is
     * then fixed for the rest of the playthrough.
     */
    private suspend fun bindModelForStory(instance: StoryInstance) {
        val boundId = instance.modelBinding.installedModelId
        if (boundId.isBlank()) {
            val active = _state.value.installedModels.firstOrNull { it.id == _state.value.activeModelId }
            if (active != null) {
                val rebound = runCatching {
                    runtime.rebindModel(instance, active, profileOf(instance.modelBinding.profileId))
                }.getOrNull()
                if (rebound != null) {
                    _state.update { it.copy(story = rebound) }
                }
            }
            syncModelState()
            return
        }
        viewModelScope.launch {
            val installed = runCatching { registry.list() }.getOrDefault(_state.value.installedModels)
            val model = installed.firstOrNull { it.id == boundId }
            if (model == null) {
                // Not silently replaced with a different model: this story's transcript,
                // sampler and context budget were resolved against that file.
                engineStatus.value = EngineStatus.Failed(Loc.t("model.missing_for_story"))
                syncModelState()
                return@launch
            }
            if (_state.value.loadedModelId == model.id && runtime.isModelLoaded()) {
                syncModelState()
                return@launch
            }
            loadModel(model)
        }
    }

    /**
     * Makes the engine hold [model], and records the outcome in the one place every other
     * layer reads.
     *
     * ## Why this is serialised
     *
     * llama.cpp holds ONE model. `LocalLlamaInferenceEngine.loadModel` unloads whatever is
     * resident before loading, so two concurrent calls race: the second unload frees the
     * handle the first one created, and the app goes on reporting a model the engine no
     * longer has. That race was reachable from three call sites at once - boot, opening a
     * story, and creating one - and it is the mechanism behind an intermittent "no model"
     * on a device that had just imported one. The mutex makes "load" one operation.
     */
    private suspend fun loadModel(model: InstalledModel): EngineStatus = modelLoadLock.withLock {
        loadModelLocked(model)
    }

    private suspend fun loadModelLocked(model: InstalledModel): EngineStatus {
        _state.update { it.copy(modelBusy = true) }
        engineStatus.value = EngineStatus.Loading
        val status = when (
            val outcome = runCatching {
                runtime.loadModel(
                    ModelLoadRequest(
                        path = model.absolutePath,
                        displayName = model.displayName,
                        // The registry id, so engine residency can be attributed to the
                        // right model rather than to a file name in a different namespace.
                        installedModelId = model.id,
                    ),
                )
            }.getOrNull()
        ) {
            is LoadOutcome.Loaded -> EngineStatus.Ready(outcome.info.displayName)
            is LoadOutcome.Failed -> EngineStatus.Failed(ErrorMessages.of(outcome.error))
            null -> EngineStatus.Failed(Loc.t("model.load_failed"))
        }
        _state.update {
            it.copy(
                modelBusy = false,
                // Honest either way: after a failed load the engine holds nothing, so
                // keeping the previous id would be a lie told to a second layer.
                loadedModelId = if (status is EngineStatus.Ready) model.id else null,
            )
        }
        engineStatus.value = status
        // Recorded in the registry, so a model that failed here is described as failed
        // everywhere afterwards instead of looking untouched until the next launch.
        runCatching {
            when (status) {
                is EngineStatus.Ready -> models.markLoadSuccess(registry, model.id)
                is EngineStatus.Failed -> models.markLoadFailure(registry, model.id, status.reason)
                else -> Unit
            }
        }
        _state.update {
            it.copy(installedModels = runCatching { registry.list() }.getOrDefault(it.installedModels))
        }
        syncModelState()
        return status
    }

    /**
     * Recomputes [CharalyUiState.model] from authoritative sources.
     *
     * Registry list + active id + the open story's binding + engine residency, resolved by
     * the one resolver every other layer uses. Called after anything that can change the
     * answer, so no screen can hold a stale opinion about whether a model is available.
     */
    private fun syncModelState() {
        _state.update { current -> current.copy(model = resolveModel(current)) }
    }

    private fun resolveModel(
        current: CharalyUiState = _state.value,
    ): dev.charaly.runtime.model.ModelSelection {
        val binding = current.story?.modelBinding
            ?: dev.charaly.runtime.model.ModelBindingResolver.resolve(
                packDefaultProfileId = "",
                installedModel = current.installedModels.firstOrNull { it.id == current.activeModelId },
            )
        return dev.charaly.runtime.model.ModelSelectionResolver.resolve(
            binding = binding,
            installed = current.installedModels,
            activeModelId = current.activeModelId,
            // Residency is read from the engine itself, not from a cached field, so a
            // restart or an external unload cannot leave the UI claiming a model is loaded.
            residentModelId = if (runtime.isModelLoaded()) current.loadedModelId else null,
            fileExists = { candidate -> modelFileExists(candidate) },
            engineSupports = { candidate ->
                candidate.architecture.isBlank() ||
                    dev.charaly.runtime.model.EngineCapabilities.supports(candidate.architecture)
            },
        )
    }

    private fun modelFileExists(model: InstalledModel): Boolean =
        runCatching { java.io.File(model.absolutePath).isFile }.getOrDefault(false)

    /**
     * Loads the story's model if it is not resident yet.
     *
     * The lazy half of the contract: a model becomes usable the moment it is registered and
     * bound, and the (potentially very slow) native load happens on the first turn instead
     * of blocking the import the user just performed. Returns true when the engine is ready
     * to generate.
     */
    private suspend fun ensureModelResident(): Boolean {
        val selection = _state.value.model
        if (!selection.canGenerate) return false
        if (selection.isResident && runtime.isModelLoaded()) return true
        val installed = _state.value.installedModels.firstOrNull { it.id == selection.modelId }
            ?: return false
        return loadModel(installed) is EngineStatus.Ready
    }

    // ------------------------------------------------------------------
    // Preferences
    // ------------------------------------------------------------------

    fun completeOnboarding() {
        preferences.onboardingComplete = true
        _state.update { it.copy(onboardingComplete = true) }
    }

    fun setDeveloperMode(enabled: Boolean) {
        preferences.developerMode = enabled
        _state.update { it.copy(developerMode = enabled) }
    }

    fun setDarkTheme(dark: Boolean) {
        preferences.darkTheme = dark
        _state.update { it.copy(darkTheme = dark) }
    }

    fun setReduceMotion(reduce: Boolean) {
        preferences.reduceMotion = reduce
        _state.update { it.copy(reduceMotion = reduce) }
    }

    /**
     * Saves a pack authored in the tool surface.
     *
     * The only write path into the pack store from the UI, and it goes through the runtime
     * rather than around it - so the resolver is invalidated and the pack is warm before
     * any story can be started from it. A pack written straight to the repository would
     * appear in the list and then fail on open, which is the kind of bug that only shows
     * up on a user's device.
     */
    fun saveCreatedPack(pack: dev.charaly.runtime.domain.StoryPack) {
        viewModelScope.launch {
            runCatching { runtime.createPack(pack) }
                .onSuccess { saved ->
                    val packs = runCatching { runtime.listPacks() }.getOrDefault(_state.value.packs)
                    _state.update {
                        it.copy(
                            packs = packs,
                            notice = "${saved.title} is ready to enter",
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "could not save that world") }
                }
        }
    }

    fun showNotice(message: String?) = _state.update { it.copy(notice = message) }

    fun showError(message: String?) = _state.update { it.copy(error = message) }

    fun consumeNotice() = _state.update { it.copy(notice = null, error = null) }

    // ------------------------------------------------------------------
    // Developer diagnostics (only reachable with Developer Mode on)
    // ------------------------------------------------------------------

    fun worldStateJson(): String = _state.value.story?.worldState?.describe().orEmpty()

    fun contextPreview(): String {
        val story = _state.value.story ?: return "No story open."
        val context = runCatching { runtime.previewContext(story) }.getOrNull() ?: return "No context."
        return context.systemPrompt()
    }

    fun promptEstimate(): Int {
        val story = _state.value.story ?: return 0
        val context = runCatching { runtime.previewContext(story) }.getOrNull() ?: return 0
        return runtime.promptEstimate(context)
    }

    fun modelDiagnostics(): String = runtime.modelDiagnostics()

    fun eventLog(): String = _state.value.story?.worldState?.eventLog
        ?.joinToString("\n") { it.value }
        .orEmpty()

    /**
     * Story health, for the developer panel.
     *
     * Never shown to a normal player: being told your story has contradictory facts is
     * not something anyone can act on, and it would read as the app being broken.
     */
    fun storyHealth(): String {
        val story = _state.value.story ?: return "No story open."
        val report = runCatching { runtime.storyHealth(story) }.getOrNull() ?: return "Could not analyse."
        return buildString {
            appendLine(report.summarise())
            if (report.findings.isEmpty()) return@buildString
            report.findings.forEach { finding ->
                appendLine()
                appendLine("[${finding.severity}] ${finding.kind}: ${finding.summary}")
                finding.evidence.take(EVIDENCE_LIMIT).forEach { appendLine("  - $it") }
            }
        }
    }

    fun storyHealthStatus(): String {
        val story = _state.value.story ?: return "NO STORY"
        val report = runCatching { runtime.storyHealth(story) }.getOrNull() ?: return "UNKNOWN"
        return report.status.name
    }

    /**
     * "What did the model actually receive?", section by section.
     *
     * Separate from [contextPreview] on purpose: the preview is the assembled prompt,
     * which is what you want when debugging a phrase, while this is the *budgeting* -
     * which sections survived, what they cost, and what was cut. A dropped section is
     * invisible in the assembled prompt and is exactly the thing worth finding.
     */
    /**
 * The causal graph, in plain text.
 *
 * Shown in developer mode only. The point of it is that "why did this happen?" has an
 * answer from the world rather than from the model's narration - so the graph has to be
 * inspectable, not merely present.
 */
fun causalityGraph(): String {
        val story = _state.value.story ?: return "No story open."
        val state = story.worldState
        if (state.causality.isEmpty()) {
            return "No causal links recorded yet. Every event starts as a cause rather than a reason."
        }
        return buildString {
            appendLine("${state.causality.size} links from ${state.eventLog.size} events")
            state.causality.values
                .sortedWith(compareBy({ it.reason.ordinal }, { it.effectId.value }))
                .take(CAUSAL_LIMIT)
                .forEach { link ->
                    appendLine("${link.reason.label}")
                    appendLine("  ${link.causeId.value} -> ${link.effectId.value}${link.detail}")
                }
        }
    }

    /** Threads, with the structure that says whether one is drifting. */
    fun storyThreads(): String {
        val story = _state.value.story ?: return "No story open."
        val threads = story.storyThreads.values.sortedWith(
            compareByDescending<dev.charaly.runtime.domain.StoryThread> { it.priority }.thenBy { it.id.value },
        )
        if (threads.isEmpty()) return "No story threads."
        return threads.joinToString("\n\n") { thread ->
            buildString {
                appendLine("${thread.title}  [${thread.kind.label}, ${thread.status.name.lowercase()}]")
                appendLine("  priority ${thread.priority}  stage ${thread.stage}  progress ${thread.progress}%")
                if (thread.nextBeat.isNotBlank()) appendLine("  next: ${thread.nextBeat}")
                if (thread.resolutionCondition.isNotBlank()) appendLine("  ends when: ${thread.resolutionCondition}")
                if (thread.consequenceIfAbandoned.isNotBlank()) {
                    appendLine("  if dropped: ${thread.consequenceIfAbandoned}")
                }
            }.trimEnd()
        }
    }

    /** What one character has worked out, for the inspector. */
    fun characterMinds(): String {
        val story = _state.value.story ?: return "No story open."
        val withMinds = story.knowledge.charactersWithMinds()
        if (withMinds.isEmpty()) return "Nobody has worked anything out yet."
        return withMinds.joinToString("\n\n") { id ->
            val mind = story.knowledge.mind(id)
            val name = story.characters[id]?.name ?: id.value
            buildString {
                appendLine("$name")
                mind.observations.forEach { appendLine("  saw: ${it.description}") }
                mind.beliefs.forEach { appendLine("  believes (${it.confidence}%): ${it.subject} ${it.claim}") }
                mind.suspicionList().forEach { appendLine("  suspects (${it.strength}%): ${it.subject} ${it.claim}") }
                mind.misconceptions.forEach {
                    appendLine("  WRONG: ${it.subject} ${it.claim} - actually, ${it.truth}")
                }
            }.trimEnd()
        }
    }

    /** Promises, goals and armed consequences. */
    fun commitments(): String {
        val story = _state.value.story ?: return "No story open."
        val ledger = story.worldState.commitments
        if (ledger.promises.isEmpty() && ledger.goals.isEmpty() && ledger.consequences.isEmpty()) {
            return "Nothing has been promised, pursued or set off yet."
        }
        return buildString {
            if (ledger.promises.isNotEmpty()) {
                appendLine("PROMISES")
                ledger.promises.forEach {
                    appendLine("  ${it.keeperId.value} -> ${it.beneficiaryId.value}: \"${it.text}\" [${it.status.name.lowercase()}]")
                }
            }
            if (ledger.goals.isNotEmpty()) {
                appendLine("GOALS")
                ledger.goals.forEach { appendLine("  ${it.ownerId.value}: ${it.describe()}") }
            }
            val pending = ledger.pendingConsequences()
            if (pending.isNotEmpty()) {
                appendLine("ARMED CONSEQUENCES")
                pending.forEach { appendLine("  ${it.describe()}") }
            }
        }.trimEnd()
    }

    fun contextSectionBreakdown(): String {
        val story = _state.value.story ?: return "No story open."
        val sections = runCatching { runtime.contextSections(story) }.getOrNull()
            ?: return "Could not build a context."
        if (sections.isEmpty()) return "No context could be built."
        return buildString {
            val total = sections.sumOf { it.chars }
            appendLine("total: $total chars across ${sections.size} sections")
            sections.forEach { section ->
                val cut = if (section.dropped > 0) "  (-${section.dropped} dropped)" else ""
                appendLine(
                    "[${section.priority.toString().padStart(2)}] " +
                        "${section.title.padEnd(22)} ${section.chars.toString().padStart(5)} chars" +
                        "  ${section.itemCount} items$cut",
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private fun definitionFor(instance: StoryInstance): WorldDefinition {
        val pack = _state.value.packs.firstOrNull { it.id == instance.storyPackId }
        return WorldDefinition(pack?.characters.orEmpty(), pack?.locations.orEmpty())
    }

    private fun refreshInstances() {
        viewModelScope.launch {
            val instances = runCatching { runtime.listInstances() }.getOrDefault(_state.value.instances)
            _state.update { it.copy(instances = instances) }
        }
    }

    private suspend fun touchIfNewer(instance: StoryInstance) {
        if (instance.sessionMeta.lastPlayedAtEpochMs <= 0L) {
            runCatching { runtime.touch(instance, instance.sessionMeta.createdAtEpochMs) }
        }
    }

    override fun onCleared() {
        generationJob?.cancel()
        super.onCleared()
    }

    companion object {
        /** Findings show at most this much evidence, so one finding cannot flood the panel. */
        private const val EVIDENCE_LIMIT = 4

        /** Causal links shown at most. The full graph belongs in a log, not a panel. */
        private const val CAUSAL_LIMIT = 40

        /**
         * How many playthroughs a pack showcase offers to continue.
         *
         * One is enough to make the affordance meaningful. More turns the storefront
         * back into a session list, which the Stories tab already is.
         */
        private const val SESSION_LIMIT_FOR_PACK = 1

        /**
         * How many of a repository's GGUF files get their header read.
         *
         * Three, not thirty. A large repository publishes a dozen quantisations and
         * classifying each one costs a ranged request; three covers "which is the good
         * one, which is the small one, and what does this engine make of it", and the rest
         * are still downloadable - they just show as unchecked, honestly, rather than
         * being classified at the cost of a screen full of requests.
         */
        private const val HEADER_CLASSIFY_LIMIT = 3

        

        fun factory(application: CharalyApplication): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CharalyViewModel(
                        runtime = application.charaly,
                        models = application.modelManager,
                        registry = application.modelRegistry,
                        catalog = application.modelCatalog,
                        preferences = application.preferences,
                        // The real pipeline. This build declares INTERNET and wires an
                        // HTTPS transport, so a Download button here does something real.
                        downloads = application.huggingFace.downloads,
                        // And the real Hub client, so search queries the Hub rather than
                        // filtering a bundled list.
                        hub = application.huggingFace,
                        // And the connectivity observer, so the offline indicator is real
                        // rather than a label that never changes.
                        network = dev.charaly.app.net.NetworkStatus(application),
                        // And the SAF reader behind character import. It asks for no
                        // storage permission: the system picker grants one file at a time.
                        cardFiles = dev.charaly.app.ui.ContentResolverCardFileReader(application),
                        // And the benchmark store, so a measured tok/s figure survives a
                        // restart instead of having to be re-measured every launch.
                        benchmarkStore = application.benchmarkStore,
                        // And the engine a story would actually use, so a benchmark measures
                        // the code the user runs rather than a differently-configured copy.
                        inferenceEngine = application.inferenceEngine,
                    ) as T
            }
    }
}

private fun GenerationPhase.isBusy(): Boolean =
    this == GenerationPhase.THINKING || this == GenerationPhase.STREAMING
