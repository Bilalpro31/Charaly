package dev.charaly.runtime.presentation

import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.model.EngineSupport
import dev.charaly.runtime.model.InstallState
import dev.charaly.runtime.model.InstalledModel
import dev.charaly.runtime.model.ModelActions
import dev.charaly.runtime.model.ModelBinding
import dev.charaly.runtime.model.ModelCatalogItem
import dev.charaly.runtime.model.ModelProfile
import dev.charaly.runtime.model.ModelProfileLibrary
import dev.charaly.runtime.model.SamplingTemperament
import dev.charaly.runtime.model.formatBytes

// ---------------------------------------------------------------------------
// Sessions
// ---------------------------------------------------------------------------

enum class SessionSortOrder(val label: String) {
    RECENT("Recent"),
    TITLE("Title"),
    LONGEST("Longest"),
}

data class SessionsSnapshot(
    val sessions: List<SessionCard>,
    val sortOrder: SessionSortOrder,
    val query: String,
    val totalCount: Int,
    val emptyState: EmptyState?,
)

/** The Sessions screen: a history of stories, not a transcript dump. */
object SessionsPresenter {

    fun build(
        nowEpochMs: Long,
        instances: List<StoryInstance>,
        packs: List<StoryPack>,
        query: String = "",
        sortOrder: SessionSortOrder = SessionSortOrder.RECENT,
    ): SessionsSnapshot {
        val filtered = instances.filter { instance ->
            query.isBlank() ||
                instance.displayTitle.contains(query, ignoreCase = true) ||
                instance.packTitle.contains(query, ignoreCase = true) ||
                instance.persona.name.contains(query, ignoreCase = true)
        }

        val cards = filtered.map { instance ->
            val pack = packs.firstOrNull { it.id == instance.storyPackId }
            val definition = dev.charaly.runtime.domain.WorldDefinition(
                pack?.characters.orEmpty(),
                pack?.locations.orEmpty(),
            )
            SessionCard(
                id = instance.id.value,
                title = instance.displayTitle,
                packTitle = instance.packTitle,
                packId = instance.storyPackId.value,
                locationName = instance.currentLocation()?.name ?: "Somewhere in ${instance.packTitle}",
                chapterTitle = instance.currentChapter()?.title.orEmpty(),
                timeLabel = instance.worldClock.now.storyLabel(),
                lastPlayedLabel = RelativeTime.describe(nowEpochMs, instance.sessionMeta.lastPlayedAtEpochMs),
                castNames = HomePresenter.castNames(instance, pack?.let { definition }),
                castAccents = HomePresenter.castAccents(instance, pack?.let { definition }),
                turnCount = instance.conversation.size,
                memoryCount = instance.memories.size,
                modelName = instance.modelBinding.modelDisplayName.ifBlank { "No model bound" },
                personaName = instance.persona.name,
                theme = pack?.let { ResolvedTheme.of(it.identity.theme) } ?: ResolvedTheme.BRAND,
                isBranch = instance.branchOrigin != dev.charaly.runtime.domain.BranchOrigin.NONE,
            )
        }.sortedWith(
            when (sortOrder) {
                SessionSortOrder.RECENT -> compareByDescending { card ->
                    instances.firstOrNull { it.id.value == card.id }?.sessionMeta?.lastPlayedAtEpochMs ?: 0L
                }
                SessionSortOrder.TITLE -> compareBy { it.title.lowercase() }
                SessionSortOrder.LONGEST -> compareByDescending { it.turnCount }
            },
        )

        return SessionsSnapshot(
            sessions = cards,
            sortOrder = sortOrder,
            query = query,
            totalCount = instances.size,
            emptyState = if (cards.isEmpty()) {
                EmptyState(
                    title = if (instances.isEmpty()) "No stories yet." else "Nothing matches that.",
                    body = if (instances.isEmpty()) {
                        "Start a story from a Story Pack and it will appear here, ready to pick up."
                    } else {
                        "Try another search term."
                    },
                    actionLabel = if (instances.isEmpty()) "Explore Story Packs" else "Clear search",
                    artSeed = "charaly-empty-sessions",
                )
            } else {
                null
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Model library
// ---------------------------------------------------------------------------

data class InstalledModelCard(
    val id: String,
    val displayName: String,
    val sizeLabel: String,
    val originLabel: String,
    val stateLabel: String,
    val detailRows: List<StatChip>,
    /**
     * What this device has actually measured about this model.
     *
     * A [SpeedVerdict], not a string, so a card cannot render a tok/s figure without the
     * evidence line that says where the number came from. Unmeasured reads "Not measured",
     * which is the honest default - there is no field on this type for an estimate.
     */
    val speed: SpeedVerdict = SpeedVerdict(state = SpeedState.UNMEASURED, label = "Not measured"),
    /**
     * Whether a "Measure" control should be offered for this model.
     *
     * Derived from [speed] plus the card's own readiness, so a model this build cannot load
     * is never offered a benchmark it cannot produce.
     */
    val canBenchmark: Boolean = false,
    val isActive: Boolean,
    val isReady: Boolean,
    val isLoaded: Boolean,
    val verdictMessage: String,
    val accent: Long,
    val profileCount: Int,
    val actions: ModelActions,
    /**
     * Whether this build's engine can load the file.
     *
     * A GGUF the user imported can still have an architecture this build does not
     * register, so an installed model is not automatically a working one.
     */
    val engineSupportLabel: String = "",
    val engineVerdictReason: String = "",
    val needsEngineUpdate: Boolean = false,
)

data class CatalogModelCard(
    val id: String,
    val name: String,
    val publisher: String,
    val description: String,
    val sizeLabel: String,
    val parameterLabel: String,
    val quantization: String,
    val contextLabel: String,
    val ramLabel: String,
    val roleplayLabel: String,
    val license: String,
    val isRecommended: Boolean,
    val isInstalled: Boolean,
    val fitsDevice: Boolean,
    val actions: ModelActions,
    /**
     * Whether this build's engine can load the model at all.
     *
     * Kept separate from [actions] because they answer different questions: an engine
     * that cannot load a model is a *product* limitation, while "no network" is a
     * *build* limitation. Conflating them produces the worst possible message - telling
     * a user to import a file that still will not work.
     */
    val engineSupportLabel: String = "",
    val engineVerdictReason: String = "",
    /** True when the model is real but needs a newer engine. */
    val needsEngineUpdate: Boolean = false,
    /** The upstream publication id, shown so the entry is traceable. */
    val upstreamId: String = "",
)

/** A catalog entry the bundled engine cannot load yet. Shown, not hidden. */
data class AwaitingEngineCard(
    val id: String,
    val name: String,
    val publisher: String,
    val upstreamId: String,
    val parameterLabel: String,
    val reason: String,
)

data class ModelLibrarySnapshot(
    val installed: List<InstalledModelCard>,
    val recommended: List<CatalogModelCard>,
    val others: List<CatalogModelCard>,
    /**
     * How much of the library has been measured, and what a running benchmark is doing.
     *
     * Its own field rather than a derived count so a screen cannot compute "2 of 4 measured"
     * slightly differently from the cards, and so the running state has exactly one place
     * it is rendered from.
     */
    val speedSummary: BenchmarkSummary = BenchmarkSummary(
        measuredCount = 0,
        unmeasuredCount = 0,
        isRunning = false,
    ),
    /**
     * Real models this build cannot load yet, e.g. Gemma 4.
     *
     * A separate section rather than being mixed into the catalog: offering them in the
     * main list would imply they are installable today.
     */
    val awaitingEngineUpdate: List<AwaitingEngineCard> = emptyList(),
    val activeModelId: String,
    val totalInstalledBytes: Long,
    val canDownload: Boolean,
    val downloadNote: String,
    /** The active search term; filtering happens in the presenter, not in the view. */
    val query: String = "",
    val emptyState: EmptyState?,
)

data class ModelDetailSnapshot(
    val id: String,
    val displayName: String,
    val sizeLabel: String,
    val originLabel: String,
    val stateLabel: String,
    val detailRows: List<StatChip>,
    val verdictMessage: String,
    val verdictLevel: String,
    val isActive: Boolean,
    val binding: ModelBinding?,
    val profiles: List<ProfileCard>,
    val builtInProfiles: List<ProfileCard>,
    val usedByStories: List<StoryUse>,
    val actions: ModelActions,
)

data class ProfileCard(
    val id: String,
    val name: String,
    val summary: String,
    val temperament: SamplingTemperament,
    val narrativeStyleLabel: String,
    val isBuiltIn: Boolean,
    val isBound: Boolean,
)

data class StoryUse(
    val storyId: String,
    val title: String,
    val packTitle: String,
    val profileName: String,
)

/**
 * The model library.
 *
 * One registry, one list of installed models. A GGUF the user imported and a
 * model a catalog described are the same kind of thing, and the card says
 * "Not installed" until a real file exists. Downloads are offered only when the
 * device can actually perform them; otherwise the card says why, instead of
 * showing a progress bar that goes nowhere.
 */
object ModelLibraryPresenter {

    /**
     * Whether this build can download models at all.
     *
     * Passed in rather than asked for, because only the app module knows whether a transport
     * is installed - and because a presenter that reached for one would stop being testable
     * without a device. When it is false the library says so and points at import, which is
     * the honest alternative rather than a dead button.
     */
    /**
     * @param benchmarks this device's stored measurements, keyed by model id. A model with
     *   no entry renders "Not measured"; nothing is derived from its size or its
     *   architecture, because neither has any reliable relationship to tokens per second.
     * @param measuringModelId the model currently being measured, if any.
     */
    fun build(
        installedModels: List<InstalledModel>,
        catalog: List<ModelCatalogItem>,
        activeModelId: String,
        loadedModelId: String?,
        downloadsAvailable: Boolean = false,
        availableRamBytes: Long = 0L,
        query: String = "",
        benchmarks: Map<String, dev.charaly.runtime.model.BenchmarkRecord> = emptyMap(),
        measuringModelId: String = "",
        measuringPhaseLabel: String = "",
    ): ModelLibrarySnapshot {
        val installedCards = installedModels.map { model ->
            modelCard(
                model = model,
                isActive = model.id == activeModelId,
                isLoaded = model.id == loadedModelId,
                availableRamBytes = availableRamBytes,
                downloadsAvailable = downloadsAvailable,
                benchmark = benchmarks[model.id],
                isMeasuring = model.id == measuringModelId,
                measuringPhaseLabel = measuringPhaseLabel,
            )
        }

        val installedIds = installedModels.map { it.catalogId }.filter { it.isNotBlank() }.toSet()
        val catalogCards = catalog.map { item ->
            catalogCard(item, item.id in installedIds, availableRamBytes, downloadsAvailable)
        }

        return ModelLibrarySnapshot(
            speedSummary = BenchmarkPresenter.summary(
                installedIds = installedModels.map { it.id },
                records = benchmarks.values.toList(),
                measuringModelId = measuringModelId,
                measuringName = installedModels.firstOrNull { it.id == measuringModelId }
                    ?.displayName
                    .orEmpty(),
                phaseLabel = measuringPhaseLabel,
            ),
            installed = installedCards.filter { it.matches(query) },
            // A model the engine cannot load is kept out of the installable lists, and
            // surfaced in its own honest section instead.
            recommended = catalogCards
                .filter { it.isRecommended && it.engineSupportLabel.isEmpty() }
                // Never lead with a model this device cannot hold: a recommendation the
                // user cannot act on is worse than no recommendation at all.
                .filter { it.fitsDevice }
                .filter { it.matches(query) },
            others = catalogCards
                .filterNot { it.isRecommended }
                .filter { it.engineSupportLabel.isEmpty() }
                .filter { it.matches(query) },
            awaitingEngineUpdate = awaitingEngineUpdate(catalog, query),
            activeModelId = activeModelId,
            totalInstalledBytes = installedModels.sumOf { it.sizeBytes },
            canDownload = downloadsAvailable,
            downloadNote = if (downloadsAvailable) {
                // Both routes are real in this build: a download from the Hub, or an
                // import from a file the user already has. Saying so is what lets the
                // user pick either without wondering whether the button works.
                "Download a GGUF from Hugging Face, or import one you already have. " +
                    "Everything is verified before a model is used."
            } else {
                "This build of Charaly has no network access, so models are imported from your own files."
            },
            query = query,
            emptyState = if (installedCards.isEmpty() && catalogCards.isEmpty()) {
                EmptyState(
                    title = "No models installed.",
                    body = "Import a GGUF from your device and it becomes available to every story.",
                    actionLabel = "Import GGUF",
                    artSeed = "charaly-empty-models",
                )
            } else {
                null
            },
        )
    }

    private fun InstalledModelCard.matches(query: String): Boolean {
        if (query.isBlank()) return true
        return displayName.contains(query, ignoreCase = true) ||
            originLabel.contains(query, ignoreCase = true) ||
            stateLabel.contains(query, ignoreCase = true) ||
            // The speed line is searchable as well, so a user who has measured one model can
            // find it by typing "measured" rather than having to remember its name.
            speed.label.contains(query, ignoreCase = true) ||
            speed.evidence.contains(query, ignoreCase = true) ||
            detailRows.any { it.label.contains(query, ignoreCase = true) || it.value.contains(query, ignoreCase = true) }
    }

    private fun CatalogModelCard.matches(query: String): Boolean {
        if (query.isBlank()) return true
        return name.contains(query, ignoreCase = true) ||
            publisher.contains(query, ignoreCase = true) ||
            description.contains(query, ignoreCase = true) ||
            quantization.contains(query, ignoreCase = true) ||
            license.contains(query, ignoreCase = true)
    }

    fun modelCard(
        model: InstalledModel,
        isActive: Boolean,
        isLoaded: Boolean,
        availableRamBytes: Long,
        downloadsAvailable: Boolean,
        benchmark: dev.charaly.runtime.model.BenchmarkRecord? = null,
        isMeasuring: Boolean = false,
        measuringPhaseLabel: String = "",
    ): InstalledModelCard {
        val verdict = model.deviceVerdict(availableRamBytes)
        val engineVerdict = dev.charaly.runtime.model.EngineVerdict.of(model.architecture)
        val speed = BenchmarkPresenter.verdict(
            record = benchmark,
            isMeasuring = isMeasuring,
            phaseLabel = measuringPhaseLabel,
        )
        return InstalledModelCard(
            id = model.id,
            displayName = model.displayName,
            sizeLabel = model.sizeLabel,
            originLabel = if (model.origin == dev.charaly.runtime.model.ModelOrigin.DOWNLOADED) {
                "Downloaded"
            } else {
                "Imported"
            },
            stateLabel = when {
                model.compatibility.loadFailed -> "Could not load"
                isLoaded -> "Ready"
                model.compatibility.lastLoadedAtEpochMs > 0 -> "Installed"
                else -> "Installed"
            },
            detailRows = buildList {
                model.architecture.takeIf { it.isNotBlank() }?.let { add(StatChip("Architecture", it)) }
                model.quantization.takeIf { it.isNotBlank() }?.let { add(StatChip("Quantization", it)) }
                add(StatChip("Context", "${model.effectiveContextTokens()} tokens"))
                add(StatChip("Size", model.sizeLabel))
                if (model.sha256.isNotBlank()) {
                    add(StatChip("Verified", if (model.verified) "Yes" else "Not verified"))
                }
            },
            speed = speed,
            // A model this build cannot load cannot be measured either, so it is not offered
            // a Measure control - a button that would always fail is worse than no button.
            canBenchmark = speed.canBenchmark && engineVerdict.isLoadable && !model.compatibility.loadFailed,
            isActive = isActive,
            isReady = verdict.level == dev.charaly.runtime.model.DeviceFitLevel.READY,
            isLoaded = isLoaded,
            verdictMessage = verdict.message,
            accent = ResolvedTheme.BRAND.primary,
            profileCount = model.profiles.size,
            actions = ModelActions(
                // A file being present is not the same as this build being able to read
                // it: someone can import a GGUF whose architecture the bundled engine
                // does not register, and offering it as "Use" would fail at load time.
                canUse = !model.compatibility.loadFailed && engineVerdict.isLoadable,
                canDelete = true,
                canVerify = true,
                canDownload = false,
                canResume = false,
                canCancel = false,
                disabledReason = when {
                    model.compatibility.loadFailed -> verdict.message
                    !engineVerdict.isLoadable -> engineVerdict.reason
                    else -> ""
                },
            ),
            // A file already on the device still has to be loadable by this engine, so
            // the same derived verdict is shown here.
            engineSupportLabel = engineLabelOf(model.architecture),
            engineVerdictReason = engineVerdict.reason,
            needsEngineUpdate = engineVerdict.support ==
                dev.charaly.runtime.model.EngineSupport.ENGINE_UPDATE_REQUIRED,
        )
    }

    /** The badge text for an architecture, or empty when there is nothing to say. */
    private fun engineLabelOf(architecture: String): String =
        when (dev.charaly.runtime.model.EngineVerdict.of(architecture).support) {
            dev.charaly.runtime.model.EngineSupport.SUPPORTED -> ""
            dev.charaly.runtime.model.EngineSupport.ENGINE_UPDATE_REQUIRED -> "Needs engine update"
            dev.charaly.runtime.model.EngineSupport.UNKNOWN_ARCHITECTURE -> "Unrecognised"
        }

    fun catalogCard(
        item: ModelCatalogItem,
        isInstalled: Boolean,
        availableRamBytes: Long,
        downloadsAvailable: Boolean,
    ): CatalogModelCard {
        val fits = item.isSuitableForDevice(availableRamBytes)
        val engineVerdict = item.engineVerdict
        // Order matters. "You need a newer engine" must never be reported as
        // "import a GGUF instead": following that advice would still leave the user
        // with a file the app cannot load, which is worse than saying nothing can.
        val reason = when {
            isInstalled -> "Already installed"
            !fits -> "This model may exceed the available device memory."
            !downloadsAvailable -> "Import a GGUF from your device instead."
            !engineVerdict.isLoadable -> "Valid model - this build's engine cannot load it yet"
            else -> ""
        }
        return CatalogModelCard(
            id = item.id,
            name = item.name,
            publisher = item.publisher,
            description = item.description,
            sizeLabel = item.sizeLabel,
            parameterLabel = item.parameterLabel,
            quantization = item.quantization,
            contextLabel = "${item.contextLength} tokens",
            ramLabel = if (availableRamBytes > 0) {
                "${item.ramLabel} needed"
            } else {
                item.ramLabel
            },
            roleplayLabel = roleplayLabel(item.roleplaySuitability),
            license = item.license,
            isRecommended = item.isRecommended,
            isInstalled = isInstalled,
            fitsDevice = fits,
            actions = ModelActions(
                // An unloadable-by-this-build model is still worth downloading: the
                // file is real, it installs as ENGINE UNSUPPORTED, and the user
                // keeps it. Only storage/network actually block the transfer.
                canUse = false,
                canDelete = false,
                canVerify = false,
                canDownload = downloadsAvailable && fits && !isInstalled,
                canResume = false,
                canCancel = false,
                disabledReason = reason,
            ),
            engineSupportLabel = engineLabelOf(item.architecture),
            engineVerdictReason = engineVerdict.reason,
            needsEngineUpdate = item.needsEngineUpdate,
            upstreamId = item.upstreamId,
        )
    }

    /**
     * Catalog entries this build's engine cannot load.
     *
     * Listed rather than hidden: a user who has heard of a model should be told the
     * truth about why it is not available, not left wondering whether it exists.
     */
    fun awaitingEngineUpdate(
        catalog: List<ModelCatalogItem>,
        query: String = "",
    ): List<AwaitingEngineCard> = catalog
        .filter { it.needsEngineUpdate }
        .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) || it.publisher.contains(query, ignoreCase = true) }
        .map {
            AwaitingEngineCard(
                id = it.id,
                name = it.name,
                publisher = it.publisher,
                upstreamId = it.upstreamId,
                parameterLabel = it.parameterLabel,
                reason = it.engineVerdict.reason,
            )
        }

    /** The model detail screen: metadata, then the profile, then who uses it. */
    fun detail(
        model: InstalledModel,
        binding: ModelBinding?,
        storiesUsing: List<StoryInstance>,
        isLoaded: Boolean,
        availableRamBytes: Long,
    ): ModelDetailSnapshot {
        val verdict = model.deviceVerdict(availableRamBytes)
        val builtIn = ModelProfileLibrary.all
        val boundProfileId = binding?.profileId
        return ModelDetailSnapshot(
            id = model.id,
            displayName = model.displayName,
            sizeLabel = model.sizeLabel,
            originLabel = if (model.origin == dev.charaly.runtime.model.ModelOrigin.DOWNLOADED) {
                "Downloaded"
            } else {
                "Imported"
            },
            stateLabel = when {
                model.compatibility.loadFailed -> "Could not load"
                model.compatibility.engineSupport == EngineSupport.ENGINE_UPDATE_REQUIRED -> "Engine update required"
                model.compatibility.engineSupport == EngineSupport.UNKNOWN_ARCHITECTURE -> "Engine support unknown"
                isLoaded -> "Ready"
                else -> "Installed"
            },
            detailRows = listOf(
                StatChip("Architecture", model.architecture.ifBlank { "Unknown" }),
                StatChip("Quantization", model.quantization.ifBlank { "Unknown" }),
                StatChip("Size", model.sizeLabel),
                StatChip("Context", "${model.effectiveContextTokens()} tokens"),
                StatChip("Origin", if (model.origin == dev.charaly.runtime.model.ModelOrigin.DOWNLOADED) "Downloaded" else "Imported"),
                StatChip("Licence", "As supplied by the publisher"),
            ),
            verdictMessage = verdict.message,
            verdictLevel = verdict.level.name,
            isActive = binding != null,
            binding = binding,
            profiles = model.profiles.map { it.toCard(boundProfileId) },
            builtInProfiles = builtIn.map { it.toCard(boundProfileId) },
            usedByStories = storiesUsing.map { instance ->
                StoryUse(
                    storyId = instance.id.value,
                    title = instance.displayTitle,
                    packTitle = instance.packTitle,
                    profileName = instance.modelBinding.profileName.ifBlank { "Roleplay Balanced" },
                )
            },
            actions = ModelActions(
                canUse = !model.compatibility.loadFailed &&
                    (model.compatibility.engineSupport == null ||
                        model.compatibility.engineSupport == EngineSupport.SUPPORTED),
                canDelete = true,
                canVerify = true,
                canDownload = false,
                canResume = false,
                canCancel = false,
                disabledReason = if (model.compatibility.loadFailed) verdict.message else "",
            ),
        )
    }

    /** The installation state a card shows. Never invented. */
    fun installState(model: InstalledModel?, isLoaded: Boolean): InstallState = when {
        model == null -> InstallState.CATALOG
        model.compatibility.loadFailed -> InstallState.FAILED
        isLoaded -> InstallState.READY
        else -> InstallState.REGISTERED
    }

    fun roleplayLabel(suitability: Int): String = when (suitability) {
        5 -> "Excellent for roleplay"
        4 -> "Good for roleplay"
        3 -> "Usable for roleplay"
        2 -> "Limited"
        else -> "Not recommended"
    }

    fun profileCard(profile: ModelProfile, boundProfileId: String?): ProfileCard = ProfileCard(
        id = profile.id,
        name = profile.name,
        summary = profile.description.ifBlank { profile.summaryLine },
        temperament = profile.sampler.temperament,
        narrativeStyleLabel = profile.narrativeStyle.label,
        isBuiltIn = profile.isBuiltIn,
        isBound = profile.id == boundProfileId,
    )

    private fun ModelProfile.toCard(boundProfileId: String?): ProfileCard =
        profileCard(this, boundProfileId)

    /** Sampler values in user language for the Advanced section. */
    fun advancedRows(binding: ModelBinding): List<StatChip> = listOf(
        StatChip("Temperature", "%.2f".format(binding.sampler.temperature)),
        StatChip("Top P", "%.2f".format(binding.sampler.topP)),
        StatChip("Top K", binding.sampler.topK.toString()),
        StatChip("Min P", "%.3f".format(binding.sampler.minP)),
        StatChip("Repeat penalty", "%.2f".format(binding.sampler.repeatPenalty)),
        StatChip("Max tokens", binding.sampler.maxTokens.toString()),
        StatChip("Context", "${binding.maxContextTokens} tokens"),
        StatChip("Stop sequences", binding.sampler.stopSequences.takeIf { it.isNotEmpty() }
            ?.joinToString(", ") ?: "None"),
        StatChip("Thinking", binding.thinkingBehavior.label),
    )

    fun totalSizeLabel(bytes: Long): String = formatBytes(bytes)
}