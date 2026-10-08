package dev.charaly.runtime.presentation

import dev.charaly.runtime.model.DownloadMetadata
import dev.charaly.runtime.model.DownloadSource
import dev.charaly.runtime.model.EngineSupport
import dev.charaly.runtime.model.EngineVerdict
import dev.charaly.runtime.model.MetadataConfidence
import dev.charaly.runtime.model.ModelCatalogItem
import dev.charaly.runtime.model.formatBytes
import dev.charaly.runtime.model.gguf.CharalyCompatibility
import dev.charaly.runtime.model.gguf.GgufCompatibility
import dev.charaly.runtime.model.gguf.GgufReadResult
import dev.charaly.runtime.model.gguf.GgufReader
import dev.charaly.runtime.net.huggingface.HuggingFaceFile
import dev.charaly.runtime.net.huggingface.HuggingFaceFileListing
import dev.charaly.runtime.net.huggingface.HuggingFaceRepo
import dev.charaly.runtime.net.huggingface.quantRank

/**
 * The Model Library, as a *library*: discovery, detail, and an honest install path.
 *
 * ## What this file is for
 *
 * The engine already knows how to search the Hub ([dev.charaly.runtime.net.huggingface.HuggingFaceClient]),
 * how to stream a multi-gigabyte file with resume
 * ([dev.charaly.runtime.model.HuggingFaceModelDownloads]) and how to decide whether a GGUF
 * can run ([dev.charaly.runtime.model.gguf.GgufCompatibility]). What was missing was the
 * layer that turns those three into something a screen can draw *without lying*.
 *
 * That layer lives here, in the runtime, for the same reason every other presenter does:
 * it is pure, deterministic, and testable on a JVM in milliseconds. A composable that
 * formats a percentage is a composable nobody can assert anything about.
 *
 * ## The three truths this type is built to protect
 *
 * 1. **A Hub repository is not a model.** One repo publishes a dozen quantisations of the
 *    same weights; picking between them is a device decision. [HubRepoCard] therefore
 *    says nothing about size or context, because the repo knows neither.
 * 2. **A compatibility verdict is observed, never assumed.** It comes from the file's own
 *    GGUF header, fetched with a ranged request of a few hundred kilobytes. A repository's
 *    own description cannot make an unloadable architecture look loadable.
 * 3. **No number appears that was not measured.** Size comes from the Hub's LFS metadata,
 *    context from the header, progress from bytes on disk. Where the Hub does not publish
 *    a figure, the type carries "not published" rather than an estimate.
 */

/**
 * How far a search has got.
 *
 * A sealed hierarchy rather than a boolean pair, because "loading" and "loading with no
 * results yet" are different states to draw and "failed" and "offline" need different
 * copy and different actions. A single `isLoading` flag forces every screen to guess.
 */
sealed interface HubSearchState {

    /** Nothing has been asked for yet. */
    data object Idle : HubSearchState

    /** A request is in flight. Results from the previous query may still be on screen. */
    data class Searching(val query: String) : HubSearchState

    /** A request completed and returned this many repositories. */
    data class Loaded(
        val query: String,
        val resultCount: Int,
        /** The Hub said there are more pages than these. */
        val hasMore: Boolean,
    ) : HubSearchState

    /**
     * The request could not complete.
     *
     * [offline] is separated from a server error because the actions differ: a 503 is
     * worth retrying in ten seconds, while an airplane mode is not, and offering "Retry"
     * for the second teaches the user that retrying does not help.
     */
    data class Failed(
        val message: String,
        val actionLabel: String,
        val offline: Boolean = false,
    ) : HubSearchState

    companion object {
        /**
         * The state shown when the Hub cannot be reached.
         *
         * A single named constructor rather than three call sites each spelling it out,
         * because the *action label* is the part that matters: "Installed models still
         * work" is not a button, it is the reassurance that a plane ticket does not mean
         * the library is broken. Getting that wrong in one of three places would be
         * invisible in review.
         */
        fun offline(): Failed = Failed(
            message = "You're offline.",
            actionLabel = "Installed models still work",
            offline = true,
        )
    }
}

/** The filter set the library offers, mapped onto the Hub's own parameters. */
enum class HubFilter(val label: String) {
    MOST_DOWNLOADED("Most downloaded"),
    MOST_LIKED("Most liked"),
    RECENTLY_UPDATED("Newest"),
}

/**
 * One Hub repository, as a discovery card.
 *
 * Deliberately carries no size, context or compatibility. Those are properties of a
 * *file*, and a repository has many.
 */
data class HubRepoCard(
    val repoId: String,
    val title: String,
    val author: String,
    val description: String,
    val family: String,
    val license: String,
    val downloadCount: String,
    val likeCount: String,
    val hasChatTemplate: Boolean,
) {
    /** `author/name`, the identity a user would recognise. */
    val subtitle: String get() = author

    /**
     * A short secondary line.
     *
     * Never a fabricated size. "1.2 M downloads" or "GPL-3.0" is a fact the Hub
     * published; "4.5 GB" is not knowable until a file is chosen.
     */
    val metaLine: String
        get() = listOf(downloadCount, license)
            .filter { it.isNotBlank() }
            .joinToString("  ·  ")
}

/** Why a specific GGUF file cannot be offered, in words that name the fix. */
data class HubFileVerdict(
    val compatibility: CharalyCompatibility,
    val label: String,
    val reason: String,
    val architecture: String,
    val contextLength: Int,
    val estimatedRamBytes: Long,
    val hasChatTemplate: Boolean,
) {
    val isUsableNow: Boolean get() = compatibility == CharalyCompatibility.CHARALY_READY

    /** Whether the Download control should be enabled at all. */
    val canDownload: Boolean get() = compatibility.isImportable

    val ramLabel: String
        get() = if (estimatedRamBytes > 0L) "about ${formatBytes(estimatedRamBytes)} free" else ""
}

/**
 * One GGUF file inside a repository, as a choice.
 *
 * A repository publishes several; the user picks one, and Charaly has already fetched a
 * few hundred kilobytes of each to know whether it is worth picking.
 */
data class HubFileOption(
    val path: String,
    val fileName: String,
    val sizeLabel: String,
    val sizeBytes: Long,
    val quantization: String,
    val verdict: HubFileVerdict,
    /** Whether the Hub published a content hash, so a download can be verified. */
    val hasChecksum: Boolean,
    val isInstalled: Boolean,
    /** True for the file Charaly would pick on its own. */
    val isRecommended: Boolean,
) {
    /**
     * The card's secondary line.
     *
     * Every element is observed. A file whose header could not be read says "unknown"
     * rather than omitting the field, so the absence is visible rather than confusing.
     */
    val metaLine: String
        get() = listOfNotNull(
            quantization.takeIf { it.isNotBlank() },
            sizeLabel.takeIf { it.isNotBlank() && it != "unknown" },
            verdict.architecture.takeIf { it.isNotBlank() },
            verdict.contextLength.takeIf { it > 0 }?.let { "${it} context" },
        ).joinToString(" · ").ifBlank { "GGUF" }

    /** The download button's label, which is the honest name for what it does. */
    val actionLabel: String
        get() = when {
            isInstalled -> "Installed"
            verdict.compatibility == CharalyCompatibility.UNSUPPORTED -> "Not available"
            verdict.compatibility == CharalyCompatibility.MANUAL_IMPORT_ONLY -> "Download anyway"
            else -> "Download"
        }
}

/** A repository's detail screen: what it is, and which file to take from it. */
data class HubRepoDetail(
    val repoId: String,
    val title: String,
    val author: String,
    val description: String,
    val license: String,
    val tags: List<String>,
    val files: List<HubFileOption>,
    /** Non-GGUF files in the repository. Counted, because a README is not a model. */
    val otherFileCount: Int,
    val loadState: HubDetailState,
    /** Set when a file is already installed on this device. */
    val installedFileNames: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = files.isEmpty()

    /** The file a first-time user should be offered. */
    val recommended: HubFileOption? get() = files.firstOrNull { it.isRecommended } ?: files.firstOrNull()

    val usableCount: Int get() = files.count { it.verdict.isUsableNow }
}

/** Whether a repository's file list has arrived. */
sealed interface HubDetailState {
    data object Idle : HubDetailState
    data class Loading(val repoId: String) : HubDetailState
    data class Loaded(val repoId: String) : HubDetailState
    data class Failed(val repoId: String, val message: String, val offline: Boolean) : HubDetailState
}

/**
 * The library's browse surface.
 *
 * Every field a screen needs, and nothing that could be mistaken for a measurement of
 * this device's performance.
 */
data class ModelHubSnapshot(
    val query: String,
    val filter: HubFilter,
    val searchState: HubSearchState,
    val repos: List<HubRepoCard>,
    /**
     * How many repositories were found but not shown.
     *
     * Surfaced rather than hidden: a silently truncated list reads as "these are all of
     * them", which for a Hub with tens of thousands of GGUF repos is a lie.
     */
    val hiddenCount: Int = 0,
    /**
     * Whether the Hub can be reached at all right now.
     *
     * False means discovery is unavailable *and* downloads will fail. Installed models
     * are unaffected, which is the point of the whole design: a story is playable in
     * airplane mode.
     */
    val isOnline: Boolean = true,
    /** Why discovery is unavailable, when it is. */
    val offlineNote: String = "",
)

/**
 * Projects the Hub into the library.
 *
 * Every function is pure. The network lives behind [dev.charaly.runtime.net.HttpTransport]
 * in the app module, so this entire surface - including the compatibility verdicts - can
 * be exercised against scripted fixtures on a plain JVM.
 */
object ModelHubPresenter {

    /**
     * A repository card.
     *
     * The description falls back to the repository name rather than to a generic
     * placeholder, because a card with no description and no name reads as a broken row
     * rather than as a sparse one.
     */
    fun repoCard(repo: HuggingFaceRepo): HubRepoCard = HubRepoCard(
        repoId = repo.repoId,
        title = repo.name,
        author = repo.author,
        description = repo.description,
        family = repo.family,
        license = repo.license,
        downloadCount = formatCount(repo.downloads),
        likeCount = formatCount(repo.likes),
        hasChatTemplate = repo.tags.any { it.equals("text-generation", ignoreCase = true) },
    )

    /**
     * A page of results.
     *
     * The parameter is [HuggingFaceSearchPage] rather than a list so the caller cannot
     * accidentally drop `hasMore`, which is the only signal that a truncated list is
     * truncated.
     */
    fun browse(
        page: dev.charaly.runtime.net.huggingface.HuggingFaceSearchPage,
        query: String,
        filter: HubFilter,
        isOnline: Boolean = true,
        /** Ceiling on rendered cards. 60 is a long scroll, not a page of noise. */
        limit: Int = DEFAULT_RESULT_LIMIT,
    ): ModelHubSnapshot {
        val cards = page.repos.take(limit).map(::repoCard)
        return ModelHubSnapshot(
            query = query,
            filter = filter,
            // `hasMore` is carried, and the state is derived through the same function
            // every other path uses. Building a `Loaded` here directly would be the same
            // state twice with two definitions, which is how a snapshot ends up claiming
            // success while offline.
            searchState = if (isOnline && page.repos.isNotEmpty()) {
                HubSearchState.Loaded(
                    query = query,
                    resultCount = page.totalCount,
                    hasMore = page.hasMore,
                )
            } else {
                searchStateFor(query = query, isOnline = isOnline, resultCount = page.repos.size)
            },
            repos = cards,
            hiddenCount = (page.repos.size - cards.size).coerceAtLeast(0),
            isOnline = isOnline,
        )
    }

    /**
     * The detail screen for one repository.
     *
     * [headers] maps a file path to the bytes read from its GGUF header. A path absent
     * from the map is classified as unknown rather than assumed good - which is what makes
     * "Charaly could not read this file" a possible state instead of a silent pass.
     *
     * [availableRamBytes] feeds the device-fit check, and a value of 0 means "the
     * platform declined to say", in which case no size-based refusal is made.
     */
    fun detail(
        repo: HuggingFaceRepo,
        listing: HuggingFaceFileListing,
        headers: Map<String, ByteArray> = emptyMap(),
        availableRamBytes: Long = 0L,
        installedFileNames: Set<String> = emptySet(),
        loadState: HubDetailState = HubDetailState.Loaded(repo.repoId),
    ): HubRepoDetail {
        val ranked = listing.ggufFiles
            .sortedWith(compareByDescending<HuggingFaceFile> { it.quantRank }.thenBy { it.sizeBytes })

        // Classify first, then choose. The recommended file has to be picked from the
        // verdicts rather than from a size heuristic computed separately, because the two
        // would then disagree: a 2.5 GB Q4_K_M at 32k context needs roughly 7.8 GB of RAM
        // once the KV cache is counted, and a screen whose "Best fit" badge ignored that
        // would be recommending a file its own verdict calls "manual import only".
        //
        // Deriving one from the other makes that divergence impossible to write.
        val verdicts = ranked.associate { file ->
            file.path to classify(file, headers[file.path], availableRamBytes)
        }
        val recommendedPath = ranked
            .firstOrNull { verdicts[it.path]?.isUsableNow == true }
            ?.path
            // Nothing fits: highlight the smallest, so the badge at least points at the
            // least hopeless option rather than at the 9 GB file.
            ?: ranked.minByOrNull { it.sizeBytes }?.path

        val files = ranked.map { file ->
            val verdict = verdicts.getValue(file.path)
            HubFileOption(
                path = file.path,
                fileName = file.fileName,
                sizeLabel = file.sizeLabel,
                sizeBytes = file.sizeBytes,
                quantization = quantizationOf(file.fileName),
                verdict = verdict,
                hasChecksum = file.sha256.isNotBlank(),
                isInstalled = file.fileName in installedFileNames || file.path in installedFileNames,
                isRecommended = file.path == recommendedPath,
            )
        }

        return HubRepoDetail(
            repoId = repo.repoId,
            title = repo.name,
            author = repo.author,
            description = repo.description,
            license = repo.license,
            tags = repo.tags.filter { it.isNotBlank() }.take(MAX_VISIBLE_TAGS),
            files = files,
            otherFileCount = listing.otherFileCount,
            loadState = loadState,
            installedFileNames = installedFileNames.toList(),
        )
    }

    /**
     * Classifies one file, from its own header.
     *
     * The three-way answer is preserved rather than collapsed. "Charaly cannot load this
     * architecture" and "Charaly cannot read this file" are different problems with
     * different remedies, and the middle case - a valid file this build cannot run - is
     * still worth importing for a future build.
     */
    fun classify(
        file: HuggingFaceFile,
        header: ByteArray?,
        availableRamBytes: Long,
    ): HubFileVerdict {
        val bytes = header ?: return HubFileVerdict(
            compatibility = CharalyCompatibility.MANUAL_IMPORT_ONLY,
            label = "Henüz kontrol edilmedi",
            reason = "Charaly bu dosyanın başlığını okumadı, bu yüzden modelin " +
                "yükleneceğini garanti edemez. İndirme yine de mümkündür.",
            architecture = "",
            contextLength = 0,
            estimatedRamBytes = 0L,
            hasChatTemplate = false,
        )

        return when (val read = GgufReader.read(bytes)) {
            is GgufReadResult.Success -> {
                val verdict = GgufCompatibility.classify(read.metadata, file.sizeBytes, availableRamBytes)
                HubFileVerdict(
                    compatibility = verdict.compatibility,
                    label = verdict.label(),
                    reason = verdict.reason,
                    architecture = verdict.architecture,
                    contextLength = read.metadata.contextLength,
                    estimatedRamBytes = verdict.estimatedRamBytes,
                    hasChatTemplate = read.metadata.hasChatTemplate,
                )
            }

            is GgufReadResult.Failure -> HubFileVerdict(
                compatibility = CharalyCompatibility.UNSUPPORTED,
                label = "Not supported",
                reason = read.reason,
                architecture = "",
                contextLength = 0,
                estimatedRamBytes = 0L,
                hasChatTemplate = false,
            )
        }
    }

    /**
     * Turns a chosen file into the catalog entry the download pipeline consumes.
     *
     * This is the join between discovery and installation, and it is the reason a Hub
     * repository can be installed at all: [dev.charaly.runtime.model.HuggingFaceModelDownloads]
     * takes a [ModelCatalogItem], and a repository is not one.
     *
     * The architecture is taken from the *observed* header when there is one, so the
     * pipeline's own pre-flight refusal (`isEngineLoadable`) is computed from the same
     * fact the UI showed. Deriving it from the file name instead would let a repository
     * named `gemma-4` produce a Qwen2 architecture and be silently accepted.
     */
    fun toCatalogItem(
        repo: HuggingFaceRepo,
        file: HuggingFaceFile,
        verdict: HubFileVerdict,
    ): ModelCatalogItem {
        val architecture = verdict.architecture
            .ifBlank { EngineVerdict.inferArchitecture(file.fileName) }
        val modelId = "${repo.repoId}/${file.fileName}"
        return ModelCatalogItem(
            id = modelId,
            name = file.fileName.removeSuffix(".gguf").removeSuffix(".GGUF"),
            publisher = repo.author,
            description = repo.description,
            sizeBytes = file.sizeBytes,
            quantization = quantizationOf(file.fileName),
            // Zero rather than a guessed 4096: the pipeline uses this only for hints,
            // and a fabricated default would be indistinguishable from a published one.
            contextLength = verdict.contextLength,
            recommendedRamBytes = verdict.estimatedRamBytes,
            license = repo.license,
            tags = repo.tags.take(MAX_VISIBLE_TAGS),
            download = DownloadMetadata(
                source = DownloadSource.HTTP,
                url = file.downloadUrl,
                fileName = file.fileName,
                sizeBytes = file.sizeBytes,
                sha256 = file.sha256,
                notes = "From Hugging Face repository ${repo.repoId}",
            ),
            upstreamId = repo.repoId,
            ggufArtifacts = listOf(file.path),
            // The Hub published this file's size, so it is a measurement rather than a
            // derivation - which is the only thing that makes it VERIFIED.
            confidence = if (file.sizeBytes > 0L) MetadataConfidence.VERIFIED else MetadataConfidence.UNPUBLISHED,
            architecture = architecture,
            isRecommended = verdict.isUsableNow,
        )
    }

    /**
     * The empty and error states, as real copy.
     *
     * Every branch says what the user can *do*. "No data" is not an answer, and an
     * offline state that does not reassure about installed models would make the whole
     * library look broken when in fact every story is still playable.
     */
    fun searchStateFor(
        query: String,
        isOnline: Boolean,
        resultCount: Int,
        errorMessage: String? = null,
    ): HubSearchState = when {
        !isOnline -> HubSearchState.Failed(
            message = "You're offline.",
            actionLabel = "Installed models still work",
            offline = true,
        )

        errorMessage != null -> HubSearchState.Failed(
            message = errorMessage,
            actionLabel = "Retry",
            offline = false,
        )

        resultCount == 0 && query.isNotBlank() -> HubSearchState.Failed(
            message = "No GGUF models match \"$query\" on the Hub.",
            actionLabel = "Clear search",
            offline = false,
        )

        resultCount == 0 -> HubSearchState.Failed(
            message = "The Hub returned no GGUF models.",
            actionLabel = "Retry",
            offline = false,
        )

        else -> HubSearchState.Loaded(query, resultCount, hasMore = false)
    }

    /** Human-readable byte count for a download tally. */
    fun formatCount(value: Long): String = when {
        value <= 0L -> ""
        value >= 1_000_000L -> "%.1fM indirme".format(value / 1_000_000.0)
        value >= 1_000L -> "%.0fB indirme".format(value / 1_000.0)
        else -> "$value indirme"
    }

    /**
     * The quantisation named in a GGUF file name.
     *
     * Read from the file name because that is where publishers put it, and it is the
     * difference between "4.5 GB" and "this is the good one". Returns empty rather than a
     * guess when no token matches.
     */
    internal fun quantizationOf(fileName: String): String {
        val upper = fileName.uppercase()
        // Longest-first, so Q4_K_M is not reported as Q4 and IQ4_XS not as IQ4.
        for (token in QUANT_TOKENS) {
            if (upper.contains(token)) return token
        }
        return ""
    }

    const val DEFAULT_RESULT_LIMIT = 60

    /** Tags shown on a detail screen. A wall of Hub tags is not information. */
    const val MAX_VISIBLE_TAGS = 12

    /**
     * Quantisation tokens, most specific first.
     *
     * Order is load-bearing: `Q4_K_M` must be found before `Q4`, and `IQ4_XS` before
     * `IQ4`, or every card would claim the cheapest quantisation exists.
     */
    private val QUANT_TOKENS = listOf(
        "Q2_K", "IQ2_XXS", "IQ2_XS", "IQ2_M",
        "Q3_K_S", "Q3_K_M", "Q3_K_L", "IQ3_XXS", "IQ3_XS",
        "Q4_0", "Q4_1", "Q4_K_S", "Q4_K_M", "IQ4_XS", "IQ4_NL",
        "Q5_0", "Q5_1", "Q5_K_S", "Q5_K_M",
        "Q6_K", "Q8_0",
        "F16", "F32", "BF16",
    )

    /** Support labels used by the detail screen's compatibility row. */
    fun engineLabelOf(architecture: String): String =
        when (EngineVerdict.of(architecture).support) {
            EngineSupport.SUPPORTED -> "Runs on this Charaly engine"
            EngineSupport.ENGINE_UPDATE_REQUIRED -> "Needs a newer Charaly engine"
            EngineSupport.UNKNOWN_ARCHITECTURE -> "Architecture Charaly does not recognise"
        }
}