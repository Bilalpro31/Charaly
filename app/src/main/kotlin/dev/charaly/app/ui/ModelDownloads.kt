package dev.charaly.app.ui

import dev.charaly.runtime.model.DownloadProgress
import dev.charaly.runtime.model.DownloadState
import dev.charaly.runtime.model.ModelCatalogItem
import dev.charaly.runtime.model.ModelDownloadManager
import dev.charaly.runtime.net.huggingface.HuggingFaceClient
import dev.charaly.runtime.net.huggingface.HuggingFaceFile
import dev.charaly.runtime.net.huggingface.HuggingFaceRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Model discovery and download, as observed state.
 *
 * ## Why this is a separate object rather than view-model state
 *
 * A download is a long-lived, independently observable thing: it survives a screen
 * change, it has exactly one owner, and two screens may want to watch it at once. Holding
 * it in the view model's `StateFlow` would mean every byte of progress triggered a
 * recomposition of the whole app.
 *
 * So the download is owned here and *projected* into state. The view model subscribes;
 * the UI reads a single `StateFlow<DownloadUiState>` that is already in the form a screen
 * can draw without doing arithmetic.
 *
 * ## What the UI may assume
 *
 * Nothing. Every field is either a measured byte count or an explicit "we do not know".
 * There is no interpolated percentage anywhere in this file, which is why the progress
 * bar in the download screen cannot lie: it renders [DownloadProgress.fraction], which is
 * null unless the real total is known and the download is actually running.
 */
class ModelDownloads(
    private val manager: ModelDownloadManager,
    private val scope: CoroutineScope,
    /** Discovery. Null when no transport is wired, which the UI reports honestly. */
    private val discovery: HuggingFaceClient? = null,
) {

    /**
     * One download's state, in the shape a screen draws.
     *
     * [frame] carries the pipeline's own progress object alongside the projection. The
     * projection is what a screen draws; the frame is what anything needing exact byte
     * counts or a real failure reason reads. Keeping both on one value means they cannot
     * drift, because the projection is derived from the frame rather than assembled
     * separately.
     */
    data class UiState(
        val catalogId: String = "",
        val state: DownloadState = DownloadState.CATALOG,
        /** The pipeline's own frame, or null when this state was not produced from one. */
        val frame: DownloadProgress? = null,
        val fraction: Float? = null,
        val transferred: String = "",
        val speed: String = "",
        val eta: String = "",
        val message: String = "",
        val compatibility: String = "",
        val isActive: Boolean = false,
        val isPausable: Boolean = false,
        val isCancellable: Boolean = false,
        val isRetryable: Boolean = false,
    ) {
        companion object {
            val IDLE = UiState()
        }
    }

    private val _progress = MutableStateFlow(UiState.IDLE)

    /** The download currently in view. One at a time: a phone should not run two 4 GB transfers. */
    val progress: StateFlow<UiState> = _progress.asStateFlow()

    private val _repos = MutableStateFlow<List<HuggingFaceRepo>>(emptyList())
    val repos: StateFlow<List<HuggingFaceRepo>> = _repos.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError.asStateFlow()

    private var activeJob: Job? = null

    /**
     * Whether downloads can run in this build, and why not if they cannot.
     *
     * Non-null means the library must say so rather than offering a dead button.
     */
    fun unavailableReason(): String? = manager.availability()?.message

    /**
     * Searches the Hub's GGUF index.
     *
     * A failure sets [searchError] and leaves the previous results in place, because
     * clearing a list on error makes a flaky network look like "no models exist".
     */
    fun search(query: String = "") {
        val client = discovery ?: run {
            _searchError.value = "Model discovery needs a network connection."
            return
        }
        scope.launch {
            _searching.value = true
            _searchError.value = null
            runCatching { client.searchGguf(query = query) }
                .onSuccess { page -> _repos.value = page.repos }
                .onFailure { error ->
                    _searchError.value = error.message ?: "Hub'a ulaşılamadı."
                }
            _searching.value = false
        }
    }

    /**
     * Starts or resumes a download, collecting real progress.
     *
     * Only one transfer runs at a time. A second request cancels the first rather than
     * silently interleaving two writes into two files - which on a phone means two
     * models competing for the same storage, with neither completing.
     */
    fun start(item: ModelCatalogItem) {
        activeJob?.cancel()
        activeJob = manager.download(item)
            .onEach { frame -> _progress.value = frame.toUiState() }
            .launchIn(scope)
    }

    fun pause() {
        _progress.value.catalogId.takeIf { it.isNotBlank() }?.let(manager::pause)
    }

    fun cancel() {
        _progress.value.catalogId.takeIf { it.isNotBlank() }?.let(manager::cancel)
        activeJob?.cancel()
        activeJob = null
    }

    /**
     * The last known state for one download, so a screen that re-appears is not blank.
     *
     * @see dev.charaly.runtime.model.HuggingFaceModelDownloads.lastProgress
     */
    fun lastKnown(catalogId: String): DownloadProgress? =
        (manager as? dev.charaly.runtime.model.HuggingFaceModelDownloads)?.lastProgress(catalogId)

    /**
     * The GGUF files a repository publishes, with real sizes.
     *
     * The best-fitting quantisation is returned first, which is the answer to "which of
     * these thirty files should I download".
     */
    suspend fun filesFor(repoId: String, availableRamBytes: Long = 0L): List<HuggingFaceFile> {
        val client = discovery ?: return emptyList()
        return runCatching { client.listFiles(repoId) }.getOrNull()?.ggufFiles.orEmpty()
    }

    }

/**
 * The frame behind a projected state.
 *
 * A real [DownloadProgress] when the state came from the pipeline, and an idle `CATALOG`
 * frame otherwise - which renders as "ready to download" rather than as an error, because
 * an idle pipeline is not a failed one.
 *
 * Top-level so the view model can reach it without holding a [ModelDownloads] whose only
 * remaining job would be to own a transfer it did not start.
 */
fun ModelDownloads.UiState.lastFrame(): DownloadProgress = frame ?: DownloadProgress(
    catalogId = catalogId,
    state = state,
    message = message,
)

/**
 * Projects a real progress frame into what a screen draws.
 *
 * ## Why this is top-level
 *
 * The view model collects the pipeline's flow directly, so it needs the projection without
 * holding a [ModelDownloads] whose only remaining job would be to own a transfer it did
 * not start. Keeping the projection a pure function of a [DownloadProgress] means the
 * same mapping is used whether the frame came from `ModelDownloads` or from the view
 * model's own collection - and there is only one mapping to be wrong about.
 *
 * ## Why it adds nothing
 *
 * Every label is either a measured byte count or an explicit empty string meaning "not
 * measurable". Nothing is interpolated, rounded up, or defaulted. That is what makes a bar
 * drawn from this state incapable of reporting a number that was not transferred.
 */
internal fun DownloadProgress.toUiState(): ModelDownloads.UiState = ModelDownloads.UiState(
    catalogId = catalogId,
    state = state,
    frame = this,
    // Null unless genuinely measurable. This is the whole reason the bar cannot lie.
    fraction = fraction,
    transferred = transferredLabel,
    // Empty string rather than "0 B/s": not-yet-measured and genuinely zero look
    // identical on a bar and mean different things.
    speed = bytesPerSecondLabel,
    eta = etaLabel,
    message = message,
    compatibility = compatibilityLabel,
    isActive = state.isActive,
    isPausable = state.isPausable,
    isCancellable = state.isCancellable,
    isRetryable = state.isRetryable,
)
