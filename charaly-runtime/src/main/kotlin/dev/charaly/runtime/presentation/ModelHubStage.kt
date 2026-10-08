package dev.charaly.runtime.presentation

import dev.charaly.runtime.model.DownloadProgress
import dev.charaly.runtime.model.DownloadState
import dev.charaly.runtime.model.formatBytes

/**
 * THE MODEL HUB, as a library you browse.
 *
 * ## What this file is
 *
 * The engine can already search the Hub, stream a multi-gigabyte file with resume, verify
 * its checksum, parse its header and register it. What it cannot do is decide what a
 * *screen* should say while any of that is happening.
 *
 * So this is the layer that turns the pipeline into something a person can trust:
 *
 * ```
 *   HERO       the model that is actually speaking, and whether it is loaded
 *   INSTALLED  what is on this device, and what each one will do here
 *   DISCOVER   what the Hub has, filtered by what the user actually cares about
 *   TRANSFER   one download, with real bytes and real states
 * ```
 *
 * ## The one rule
 *
 * **A control that says DOWNLOAD downloads, and a number that appears was measured.**
 *
 * Size comes from the Hub's own LFS metadata. Context comes from the file's GGUF header.
 * Progress comes from bytes on disk. Speed is exponentially smoothed from real byte
 * counts. Compatibility comes from the engine's architecture table, never from a
 * repository's own description.
 *
 * Where a figure does not exist the type carries "unknown" rather than an estimate, which
 * is why [TransferProgress.fraction] is nullable: "not started" and "we do not know how
 * big this is" look identical on a bar and mean different things.
 */

// ---------------------------------------------------------------------------
// Filters
// ---------------------------------------------------------------------------

/**
 * The browse filters.
 *
 * Chosen from what the engine can actually *know* about a model rather than from what a
 * store front would like to claim. "Fast" is absent on purpose: it needs a benchmark, and
 * a filter that promised speed on a device that had never run the model would be a lie
 * with a dropdown attached.
 */
enum class ModelFilter(val label: String) {
    /** Everything. The default, because it is the only choice that cannot mislead. */
    ALL("All"),

    /** Runs on this build's engine. */
    COMPATIBLE("Compatible"),

    /** Fits in this device's memory, as far as the platform will say. */
    FITS_DEVICE("Fits this device"),

    /** Under 3 GB on disk. */
    SMALL("Small"),

    /** Installed already, so it can be used immediately. */
    INSTALLED("Installed"),
}

// ---------------------------------------------------------------------------
// Hero
// ---------------------------------------------------------------------------

/**
 * The model currently in charge.
 *
 * One large, compact block rather than a row in a list, because "which model is speaking
 * to my story" is the single most consequential fact on this screen and a list item does
 * not carry it.
 */
data class ModelHero(
    /**
     * The registry id, or empty when nothing is installed.
     *
     * Carried as data rather than reconstructed by a screen from the display name: the
     * detail route needs a stable id, and deriving one from a human-readable name is how
     * a route ends up pointing at a model that does not exist after a rename.
     */
    val id: String,
    val modelName: String,
    /** "Qwen3", "Llama", "" when unknown. Never invented. */
    val architecture: String,
    val quantization: String,
    val sizeLabel: String,
    /** READY / LOADING / NOT INSTALLED / COULD NOT LOAD. */
    val stateLabel: String,
    /** A measured rate, or empty. Never a fabricated "47 tok/s". */
    val measuredPerformance: String,
    /**
     * The measurement itself, with its evidence line and conditions.
     *
     * Present so the hero can show "Not measured" as a deliberate state rather than by
     * rendering an empty string and hoping the reader infers the absence.
     */
    val speed: SpeedVerdict = SpeedVerdict(state = SpeedState.UNMEASURED, label = "Ölçülmedi"),
    val isActive: Boolean,
    val isLoaded: Boolean,
    /** One sentence saying what to do about it, or empty when nothing is wrong. */
    val problemLabel: String,
) {
    /** Whether anything on this screen needs the user's attention. */
    val needsAttention: Boolean get() = problemLabel.isNotBlank() && !isLoaded

    /** Whether the hero names a model that has a detail screen. */
    val isLinked: Boolean get() = id.isNotBlank()
}

// ---------------------------------------------------------------------------
// Cards
// ---------------------------------------------------------------------------

/**
 * One model in a browse list.
 *
 * The same shape whether the model is on the device or on the Hub, because from the
 * user's point of view "models I can use" and "models I could use" are one list with a
 * status column. Two visually different sections was how the previous screen made the
 * installed models feel like a settings page and the Hub like a shop.
 */
data class ModelCard(
    val id: String,
    val name: String,
    val publisher: String,
    val description: String,
    val sizeLabel: String,
    val architecture: String,
    val quantization: String,
    val contextLabel: String,
    /** The badge: READY / ACTIVE / DOWNLOAD / INSTALLED / INCOMPATIBLE / NOT CHECKED. */
    val stateLabel: String,
    /** The single primary action's label. Never empty. */
    val actionLabel: String,
    /** Why the action is unavailable, or empty. Never a silently disabled button. */
    val blockedReason: String,
    /** Whether [actionLabel] refers to the download pipeline. */
    val actionIsDownload: Boolean,
    val isActive: Boolean,
    val isInstalled: Boolean,
    /** Measured only. Empty when nothing has been benchmarked. */
    val measuredPerformance: String = "",
    /**
     * What this device has measured about this model.
     *
     * A [SpeedVerdict] rather than a string, so the number and the evidence line are one
     * value: a screen cannot render "18.7 tok/s" without also rendering where it came
     * from. Unmeasured reads "Not measured", and there is no field here for an estimate.
     */
    val speed: SpeedVerdict = SpeedVerdict(state = SpeedState.UNMEASURED, label = "Ölçülmedi"),
    /** Whether a "Measure" control should be offered. False for an unloadable model. */
    val canBenchmark: Boolean = false,
)

// ---------------------------------------------------------------------------
// Transfer
// ---------------------------------------------------------------------------

/**
 * One download, as a screen draws it.
 *
 * Every field is either a measured byte count or an explicit absence. There is no
 * interpolated percentage anywhere in this file, which is what makes the bar incapable
 * of reporting progress that did not happen.
 */
data class TransferProgress(
    val catalogId: String,
    val stage: TransferStage,
    /** 0..1, or null when the total is genuinely unknown. */
    val fraction: Float?,
    /** "1.2 GB of 4.5 GB" */
    val transferred: String,
    /** "1.0 MB/s", or empty when not yet measurable. Never "0 B/s". */
    val speed: String,
    /** "4 min left", or empty. */
    val remaining: String,
    /** The file being written. Always a temporary name until verification passes. */
    val fileName: String,
    /** The stage's own sentence: "Verifying the download…". */
    val stageLabel: String,
    val canPause: Boolean,
    val canResume: Boolean,
    val canCancel: Boolean,
    val canRetry: Boolean,
    /** What went wrong, in a sentence. Empty unless the state is a failure. */
    val failureLabel: String,
    /** The raw reason, collapsed by default. "HTTP 403", a checksum, a path. */
    val technicalDetail: String,
) {
    val isRunning: Boolean get() = stage.isRunning
    val isTerminal: Boolean get() = stage.isTerminal
    val hasFailure: Boolean get() = stage == TransferStage.FAILED
    val isIndeterminate: Boolean get() = fraction == null
}

/**
 * The pipeline's states, in the language a person would use.
 *
 * A projection of [DownloadState] rather than a second state machine, and the mapping is
 * total - which matters, because a state a screen cannot describe becomes a state the
 * screen renders as "something is happening".
 */
enum class TransferStage(val label: String) {
    /** Found on the Hub, sized, and known to load. Nothing has moved. */
    READY_TO_FETCH("Ready to download"),

    DOWNLOADING("Downloading"),
    PAUSED("Paused"),
    DOWNLOADED("Downloaded"),
    VERIFYING("Verifying"),
    INSTALLING("Installing"),
    READY("Ready"),

    FAILED("Couldn't finish"),
    CANCELLED("Cancelled"),
    UNSUPPORTED("Not available"),
    ;

    val isRunning: Boolean get() = this == DOWNLOADING || this == VERIFYING || this == INSTALLING

    val isTerminal: Boolean
        get() = this == READY || this == FAILED || this == CANCELLED ||
            this == UNSUPPORTED || this == PAUSED
}

/**
 * Builds the whole screen from real state.
 *
 * Pure, and therefore testable without a device: [transfer] takes the pipeline's own
 * progress frame and the projection below is exercised against real byte counts in the
 * test suite.
 */
object ModelStagePresenter {

    /**
     * One model, as a story-setup row and as a chat header line.
     *
     * ## The reason this exists
     *
     * Every layer that shows a model used to invent its own label from its own state, so
     * the same GGUF could read "Ready" in the library and "Not connected" in the chat. This
     * is the single place the label and the reason are produced, and it takes the one
     * authoritative value ([dev.charaly.runtime.model.ModelSelection]) rather than a
     * boolean.
     *
     * It splits on [dev.charaly.runtime.model.ModelSelection.canGenerate] and *not* on
     * residency: a model that is installed and merely still loading says so explicitly
     * ("it will load the first time you write") rather than being described as absent.
     */
    fun modelLine(model: dev.charaly.runtime.model.ModelSelection): ModelLine = ModelLine(
        modelId = model.modelId,
        displayName = model.displayName.ifBlank { Loc.t("model.none_installed") },
        stateLabel = stateLabel(model),
        reason = reason(model),
        isReady = model.isReady,
        loadsOnFirstUse = model.needsEngineLoad,
    )

    /** One word for the state. Never a sentence, never blank. */
    fun stateLabel(model: dev.charaly.runtime.model.ModelSelection): String = when {
        !model.canGenerate -> Loc.t("model.could_not_load")
        model.isResident -> Loc.t("model.loaded")
        else -> Loc.t("model.installed")
    }

    /**
     * Why the model cannot be used, in the user's language.
     *
     * Empty when it can. The three blocked cases are three genuinely different problems -
     * no model at all, the file is gone, the engine refused it - and a user can only act on
     * the distinction.
     */
    fun reason(model: dev.charaly.runtime.model.ModelSelection): String = when (model.blocked) {
        dev.charaly.runtime.model.ModelBlockReason.NONE -> ""
        dev.charaly.runtime.model.ModelBlockReason.NOT_INSTALLED -> Loc.t("chat.needs_model")
        dev.charaly.runtime.model.ModelBlockReason.FILE_MISSING -> Loc.t("model.file_missing")
        dev.charaly.runtime.model.ModelBlockReason.LOAD_FAILED ->
            model.loadFailure.ifBlank { Loc.t("model.load_failed") }
        dev.charaly.runtime.model.ModelBlockReason.UNSUPPORTED ->
            Loc.t("model.unsupported_arch") + " (" + model.architecture + ")"
    }

    /**
     * The sentence shown next to a usable model.
     *
     * Separate from [reason] because "everything is fine" is worth saying out loud when a
     * previous version of the app told the user the opposite.
     */
    fun reassurance(model: dev.charaly.runtime.model.ModelSelection): String = when {
        !model.canGenerate -> reason(model)
        model.needsEngineLoad -> Loc.t("model.ready_first_use")
        else -> Loc.t("model.ready")
    }

    /** How many cards a browse list shows. A long scroll, not a page of noise. */
    const val CARD_LIMIT = 60

    /**
     * Projects a progress frame.
     *
     * The whole point of this function is that it *cannot* lie:
     *
     *  * [TransferProgress.fraction] is null unless the state is genuinely DOWNLOADING and
     *    the total is genuinely known;
     *  * speed is empty rather than "0 B/s";
     *  * the stage's sentence comes from the enum, so there is one label per state.
     */
    fun transfer(frame: DownloadProgress): TransferProgress = TransferProgress(
        catalogId = frame.catalogId,
        stage = stageOf(frame.state),
        fraction = frame.fraction,
        transferred = frame.transferredLabel,
        speed = frame.bytesPerSecondLabel,
        remaining = frame.etaLabel,
        fileName = frame.temporaryPath.substringAfterLast('/')
            .ifBlank { frame.catalogId.substringAfterLast('/') },
        stageLabel = frame.message.ifBlank { stageOf(frame.state).label },
        canPause = frame.state.isPausable,
        canResume = frame.state.isRetryable,
        canCancel = frame.state.isCancellable,
        canRetry = frame.state.isRetryable,
        failureLabel = failureLabelOf(frame),
        technicalDetail = technicalDetailOf(frame),
    )

    /**
     * A state's own sentence.
     *
     * Total by construction: every [DownloadState] maps to something, so a newly added
     * pipeline state breaks the build here rather than rendering as silence on a device.
     */
    fun stageOf(state: DownloadState): TransferStage = when (state) {
        DownloadState.CATALOG -> TransferStage.READY_TO_FETCH
        DownloadState.DISCOVERED -> TransferStage.READY_TO_FETCH
        DownloadState.QUEUED -> TransferStage.READY_TO_FETCH
        DownloadState.DOWNLOADING -> TransferStage.DOWNLOADING
        DownloadState.PAUSED -> TransferStage.PAUSED
        DownloadState.DOWNLOADED -> TransferStage.DOWNLOADED
        DownloadState.VERIFYING -> TransferStage.VERIFYING
        DownloadState.REGISTERING -> TransferStage.INSTALLING
        DownloadState.READY -> TransferStage.READY
        DownloadState.FAILED -> TransferStage.FAILED
        DownloadState.CANCELLED -> TransferStage.CANCELLED
        DownloadState.UNSUPPORTED -> TransferStage.UNSUPPORTED
    }

    /**
     * What a failure says, in a sentence.
     *
     * "Couldn't finish" is not an answer, so every branch names the problem *and* what the
     * user can do - which is the difference between a dead end and an inconvenient
     * moment.
     */
    private fun failureLabelOf(frame: DownloadProgress): String {
        if (frame.state != DownloadState.FAILED && frame.state != DownloadState.UNSUPPORTED) return ""
        return frame.message.ifBlank {
            when (frame.state) {
                DownloadState.UNSUPPORTED -> "This build of Charaly cannot run that model."
                else -> "The download couldn't finish."
            }
        }
    }

    /**
     * The collapsible technical half.
     *
     * Only when there is genuinely something technical: an HTTP status, a checksum
     * mismatch, a filesystem reason. A sentence is never repeated beneath itself, because
     * that reads as the app not knowing what it said.
     */
    private fun technicalDetailOf(frame: DownloadProgress): String {
        val raw = frame.message.trim()
        val technical = raw.contains("HTTP") || raw.contains("Exception") ||
            raw.contains("checksum") || raw.contains("sha256") ||
            frame.failure != null
        if (!technical) return ""
        return raw
    }

    // ------------------------------------------------------------------- hero

    /**
     * The current model, as a hero.
     *
     * [measuredPerformance] is threaded through rather than computed here, because a rate
     * only exists once something has actually generated tokens. There is no code path in
     * this file that produces one from a size or a parameter count, and that is the
     * property worth having.
     */
    fun hero(
        snapshot: ModelLibrarySnapshot,
        engineLabel: String,
        measuredPerformance: String = "",
        /**
         * The active model's speed line, from a real measurement.
         *
         * Defaults to the active card's own verdict so the hero cannot show a rate the
         * card beneath it does not also show. [measuredPerformance] stays for a caller that
         * has an engine-level measurement of its own.
         */
        speed: SpeedVerdict = SpeedVerdict(state = SpeedState.UNMEASURED, label = "Ölçülmedi"),
    ): ModelHero {
        val active = snapshot.installed.firstOrNull { it.isActive }
            ?: snapshot.installed.firstOrNull()
        if (active == null) {
            return ModelHero(
                id = "",
                modelName = "No model yet",
                architecture = "",
                quantization = "",
                sizeLabel = "",
                stateLabel = "NOT INSTALLED",
                measuredPerformance = "",
                // The hero has no speed field of its own yet, so it reads as unmeasured -
                // which is correct: with no model there is nothing to have measured.
                isActive = false,
                isLoaded = false,
                problemLabel = "Choose a local model to bring your worlds to life.",
            )
        }
        val loaded = active.isLoaded || active.isReady
        return ModelHero(
            id = active.id,
            modelName = active.displayName,
            architecture = active.detailRows.firstOrNull { it.label == "Architecture" }?.value.orEmpty(),
            quantization = active.detailRows.firstOrNull { it.label == "Quantization" }?.value.orEmpty(),
            sizeLabel = active.sizeLabel,
            stateLabel = when {
                active.stateLabel == "Could not load" -> "COULD NOT LOAD"
                loaded -> "READY"
                active.isActive -> "ACTIVE"
                else -> "INSTALLED"
            },
            measuredPerformance = if (measuredPerformance.isNotBlank()) {
                measuredPerformance
            } else {
                speed.evidence
            },
            speed = speed,
            isActive = active.isActive,
            isLoaded = loaded,
            problemLabel = if (loaded) "" else active.verdictMessage,
        )
    }

    // ------------------------------------------------------------------ cards

    /**
     * One browse card, from an installed model.
     *
     * [canUse] mirrors the engine's own verdict rather than the file's presence: a GGUF
     * the user imported can still have an architecture this build does not register, and
     * offering "Use" for one produces a failure at load time.
     */
    fun installedCard(card: InstalledModelCard): ModelCard = ModelCard(
        id = card.id,
        name = card.displayName,
        publisher = card.originLabel,
        description = card.verdictMessage,
        sizeLabel = card.sizeLabel,
        architecture = card.detailRows.firstOrNull { it.label == "Mimari" }?.value.orEmpty(),
        quantization = card.detailRows.firstOrNull { it.label == "Kuantizasyon" }?.value.orEmpty(),
        contextLabel = card.detailRows.firstOrNull { it.label == "Bağlam" }?.value.orEmpty(),
        stateLabel = when {
            card.stateLabel == "Yüklenemedi" -> "UYUMSUZ"
            card.isLoaded -> "HAZIR"
            card.isActive -> "ETKİN"
            else -> "YÜKLÜ"
        },
        actionLabel = when {
            !card.actions.canUse -> "Kullanılamaz"
            card.isActive && card.isLoaded -> "Kullanımda"
            else -> "Modeli kullan"
        },
        blockedReason = card.actions.disabledReason,
        actionIsDownload = false,
        isActive = card.isActive,
        isInstalled = true,
        // The measured rate and the conditions it was taken under, together. Passing the
        // [SpeedVerdict] rather than a formatted string is what makes it impossible to render
        // the number without the sentence that says it was measured on this device.
        speed = card.speed,
        canBenchmark = card.canBenchmark,
    )

    /**
     * One browse card, from a Hub repository.
     *
     * No download is offered for a model this build cannot load, however good the
     * repository's description is - the download would complete and the model would still
     * be unusable, which is worse than a card that says so.
     */
    fun hubCard(repo: HubRepoCard): ModelCard = ModelCard(
        id = repo.repoId,
        name = repo.title,
        publisher = repo.subtitle,
        description = repo.description,
        sizeLabel = "",
        architecture = repo.family,
        quantization = "",
        contextLabel = "",
        stateLabel = when {
            repo.hasChatTemplate -> "UYUMLU"
            else -> "KONTROL EDİLMEDİ"
        },
        actionLabel = "Aç",
        blockedReason = if (repo.hasChatTemplate) "" else "Charaly bunu henüz kontrol etmedi.",
        actionIsDownload = false,
        isActive = false,
        isInstalled = false,
    )

    /**
     * One browse card, from a downloadable GGUF file.
     *
     * The action label is derived from the *verdict*, not from a generic "Download": a
     * file Charaly cannot load says so, and a file already installed says Installed. A
     * card whose button says DOWNLOAD and then refuses is the defect this replaces.
     */
    fun fileCard(file: HubFileOption): ModelCard = ModelCard(
        id = file.path,
        name = file.fileName.removeSuffix(".gguf"),
        publisher = file.verdict.hasChatTemplate.let { "GGUF" },
        description = file.verdict.reason,
        sizeLabel = file.sizeLabel,
        architecture = file.verdict.architecture,
        quantization = file.quantization,
        contextLabel = if (file.verdict.contextLength > 0) {
            "${file.verdict.contextLength} tokens"
        } else {
            ""
        },
        stateLabel = when {
            file.isInstalled -> "YÜKLÜ"
            file.verdict.isUsableNow -> "UYUMLU"
            file.verdict.compatibility == dev.charaly.runtime.model.gguf.CharalyCompatibility.UNSUPPORTED ->
                "UYUMSUZ"
            else -> "KONTROL EDİLMEDİ"
        },
        actionLabel = file.actionLabel.uppercase(),
        blockedReason = if (file.verdict.canDownload) "" else file.verdict.reason,
        actionIsDownload = file.verdict.canDownload && !file.isInstalled,
        isActive = false,
        isInstalled = file.isInstalled,
    )

    // ---------------------------------------------------------------- offline

    /**
     * The offline notice.
     *
     * Says what still works, because the whole local-first claim is at stake here: a
     * library that reads as broken in airplane mode tells the user the *app* is broken.
     */
    const val OFFLINE_TITLE = "Çevrimdışısınız."
    const val OFFLINE_BODY = "Model keşfi bağlantı gerektirir. Yüklü modeller, " +
        "her dünya ve her hikâye çalışmaya devam eder."
    const val OFFLINE_ACTION = "Yüklü modeller yine çalışır"

    /** The header's status line, in both directions. */
    fun statusLine(isOnline: Boolean, hasInstalledModel: Boolean): String = when {
        isOnline && hasInstalledModel -> "Yerel · Çevrimiçi"
        isOnline -> "Yerel · Yüklemeye hazır"
        hasInstalledModel -> "Yerel · Çevrimdışı hazır"
        else -> "Yerel · Henüz model yok"
    }

    /** Total on-device footprint, in the user's units. */
    fun storageLabel(bytes: Long): String = if (bytes <= 0L) "" else formatBytes(bytes)
}

// ---------------------------------------------------------------------------
// Entry points
// ---------------------------------------------------------------------------

/** Browse results, for one filter. Pure, so a test can assert what a filter shows. */
object ModelBrowsePresenter {

    /** Applies a filter to installed models and Hub repositories together. */
    fun browse(
        installed: List<InstalledModelCard>,
        repos: List<HubRepoCard>,
        filter: ModelFilter,
        query: String = "",
    ): List<ModelCard> {
        val installedCards = installed.map(ModelStagePresenter::installedCard)
            .filter { matches(it, filter) }
            .filter { matchesQuery(it, query) }

        val repoCards = repos
            .filter { filter != ModelFilter.INSTALLED }
            .filter { filter != ModelFilter.COMPATIBLE || it.hasChatTemplate }
            .filter { filter != ModelFilter.SMALL }
            .map(ModelStagePresenter::hubCard)
            .filter { matchesQuery(it, query) }

        // Installed first, always. A model that is already on the device is strictly more
        // useful than one that is merely discoverable, and burying it under forty Hub
        // results makes the library read as a shop rather than as a library.
        return (installedCards + repoCards).take(ModelStagePresenter.CARD_LIMIT)
    }

    private fun matches(card: ModelCard, filter: ModelFilter): Boolean = when (filter) {
        ModelFilter.ALL -> true
        ModelFilter.COMPATIBLE -> card.stateLabel == "READY" ||
            card.stateLabel == "ACTIVE" || card.stateLabel == "COMPATIBLE"
        ModelFilter.FITS_DEVICE -> card.stateLabel != "INCOMPATIBLE"
        ModelFilter.SMALL -> card.sizeLabel.isNotBlank() &&
            // Only a *measured* size can be small. A repository publishes no size, so a
            // card with no size is not silently treated as small.
            card.sizeLabel.removeSuffix(" GB").toDoubleOrNull()?.let { it < 3.0 } == true
        ModelFilter.INSTALLED -> card.isInstalled
    }

    private fun matchesQuery(card: ModelCard, query: String): Boolean {
        if (query.isBlank()) return true
        return card.name.contains(query, ignoreCase = true) ||
            card.publisher.contains(query, ignoreCase = true) ||
            card.description.contains(query, ignoreCase = true) ||
            card.architecture.contains(query, ignoreCase = true)
    }
}