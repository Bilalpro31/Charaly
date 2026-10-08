package dev.charaly.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.charaly.app.ui.components.CharalyAction
import dev.charaly.app.ui.components.CharalyEmptyState
import dev.charaly.app.ui.components.CharalyIconButton
import dev.charaly.app.ui.components.CharalyPill
import dev.charaly.app.ui.components.CharalyPillRow
import dev.charaly.app.ui.components.CharalyQuietAction
import dev.charaly.app.ui.components.CharalySearchField
import dev.charaly.app.ui.components.CharalySectionHeader
import dev.charaly.app.ui.components.CharalySpinner
import dev.charaly.app.ui.components.tappable
import dev.charaly.app.ui.design.Charaly
import dev.charaly.app.ui.design.CharalyShapes
import dev.charaly.runtime.presentation.EmptyState
import dev.charaly.runtime.presentation.HubDetailState
import dev.charaly.runtime.presentation.HubRepoDetail
import dev.charaly.runtime.presentation.HubSearchState
import dev.charaly.runtime.presentation.ModelCard
import dev.charaly.runtime.presentation.Loc
import dev.charaly.runtime.presentation.ModelFilter
import dev.charaly.runtime.presentation.ModelHero
import dev.charaly.runtime.presentation.ModelStagePresenter
import dev.charaly.runtime.presentation.TransferProgress

/**
 * THE MODEL HUB.
 *
 * ## A library, not a settings screen
 *
 * Three sections, in the order a person actually needs them:
 *
 * ```
 *   CURRENT MODEL     what is speaking to my stories, and whether it is loaded
 *   ON THIS DEVICE    everything installed, with one action each
 *   EXPLORE MODELS    the Hub, searched live, filtered by what can be asked about
 * ```
 *
 * The previous version was a settings screen with a technical feel: sections, search
 * fields stacked on top of each other, and reference entries whose headings said
 * "Recommended" while offering no way to act on them. This one leads with the model that
 * is actually in charge, and every card below it either has a working action or says why
 * it does not.
 *
 * ## The rule the whole screen is built around
 *
 * **A control that says DOWNLOAD downloads.** Installed models reach the real resumable,
 * checksum-verifying pipeline. Reference entries describe models rather than offering
 * them, so they offer no download at all - and say where to look instead.
 *
 * Nothing on this screen invents a number. Size comes from the Hub's LFS metadata, context
 * from the file's own GGUF header, progress from bytes on disk, compatibility from the
 * engine's architecture table. Where a figure does not exist the screen says nothing
 * rather than approximating it, which is why the performance row is absent unless a
 * benchmark actually ran.
 */
@Composable
fun ModelHubScreen(
    hero: ModelHero,
    cards: List<ModelCard>,
    filter: ModelFilter,
    query: String,
    transfer: TransferProgress,
    hubState: HubSearchState,
    repoDetail: HubRepoDetail?,
    detailState: HubDetailState,
    isOnline: Boolean,
    busy: Boolean,
    /**
     * Where a Storage Access Framework import has got to.
     *
     * Defaults to "not running" so previews and wiring tests keep compiling. The shell
     * always supplies the real value, and the screen shows a surface only while
     * [ModelImportState.isRunning] - never a permanent row.
     */
    importState: dev.charaly.app.ui.ModelImportState = dev.charaly.app.ui.ModelImportState.NONE,
    hasInstalledModels: Boolean,
    onFilterChange: (ModelFilter) -> Unit,
    onQueryChange: (String) -> Unit,
    onOpenModel: (String) -> Unit,
    onUseModel: (String) -> Unit,
    onDeleteModel: (String) -> Unit,
    /**
     * Measures one model on this device.
     *
     * Defaults to a no-op so the previews and the wiring tests that build this screen keep
     * compiling; the real shell always supplies it. There is deliberately no "estimated"
     * variant of this control - the only way to get a speed number is to run the thing.
     */
    onBenchmarkModel: (String) -> Unit = {},
    /**
     * The library's speed banner: how much has been measured, and what a running benchmark
     * is doing. Supplied by the presenter so the count has one source.
     */
    speedSummary: dev.charaly.runtime.presentation.BenchmarkSummary? = null,
    /**
     * The sentence for a benchmark that did not complete, or empty.
     *
     * Shown as an inline notice rather than a snackbar: it is information about a card, and
     * a snackbar would have disappeared before the user looked at the model they measured.
     */
    benchmarkFailureMessage: String = "",
    onOpenRepo: (String) -> Unit,
    onCloseRepo: () -> Unit,
    onDownload: (HubRepoDetail, dev.charaly.runtime.presentation.HubFileOption) -> Unit,
    onPauseDownload: () -> Unit,
    onResumeDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onImport: () -> Unit,
    onRetrySearch: () -> Unit,
    /**
     * Returns to a story that is waiting to be started, when one is.
     *
     * This screen is full-screen: it hosts no navigation bar, and it used to have no back
     * control of its own. Reaching it from the story's "Change the voice" control was
     * therefore a one-way trip, and the user who went to import a GGUF was left with no
     * visible way to return to the story they had been setting up - the first half of the
     * reported "the Continue button disappeared" bug.
     *
     * Null whenever no story is pending, so this is not a permanent back arrow on a screen
     * the user opened deliberately from Home.
     */
    onBackToStory: (() -> Unit)? = null,
) {
    val installed = cards.filter { it.isInstalled }
    val discoverable = cards.filterNot { it.isInstalled }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Charaly.space.gutter,
            end = Charaly.space.gutter,
            bottom = Charaly.space.section,
        ),
        verticalArrangement = Arrangement.spacedBy(Charaly.space.md),
    ) {
        // ---- 0. the heading -------------------------------------------------
        item(key = "title") {
            Column {
                // A story is waiting. This is the way back to it, and it is the first thing
                // on the screen so it cannot be missed while an import is running.
                if (onBackToStory != null) {
                    CharalyQuietAction(
                        label = Loc.t("action.back_to_story"),
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        onClick = onBackToStory,
                    )
                    Spacer(Modifier.height(Charaly.space.sm))
                }
                Text(
                    text = Loc.t("models.title"),
                    style = MaterialTheme.typography.displaySmall,
                    color = Charaly.ink.primary,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(Charaly.space.xxs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The status line, in both directions. "Offline ready" is the claim the
                    // product wants to make, because everything installed keeps working
                    // with no connection at all.
                    CharalyPill(
                        label = ModelStagePresenter.statusLine(isOnline, hasInstalledModels),
                        selected = !isOnline,
                    )
                    if (busy) {
                        Spacer(Modifier.size(Charaly.space.xs))
                        CharalySpinner(color = Charaly.ink.muted)
                    }
                    // An import is not "busy" - it is a step the user is watching, and it is
                    // named rather than left as an anonymous spinner next to the title.
                    if (importState.isRunning) {
                        Spacer(Modifier.size(Charaly.space.xs))
                        Text(
                            text = Loc.t(
                                if (importState == dev.charaly.app.ui.ModelImportState.IMPORTING) {
                                    "model.importing"
                                } else {
                                    "model.verifying"
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = Charaly.atmosphere.accent,
                        )
                    }
                }
            }
        }

        // ---- 1. the current model ------------------------------------------
        //
        // One large compact block rather than a row in a list: "which model is speaking to
        // my story" is the single most consequential fact on this screen.
        item(key = "hero") {
            CurrentModelHero(
                hero = hero,
                // Only a hero that names an installed model has a detail screen to open.
                // With nothing installed the tap is inert, which is correct: there is no
                // model to show more of.
                onClick = { hero.id.takeIf { it.isNotBlank() }?.let(onOpenModel) },
            )
        }

        // ---- 2. speed: what this device has actually measured --------------
        //
        // Between the current model and the list, because it answers a question about every
        // model at once ("which of these have I actually tried?") rather than about one.
        // The banner never invents a rate: it counts measurements and, while one is running,
        // names it.
        if (speedSummary != null && speedSummary.measuredCount + speedSummary.unmeasuredCount > 0) {
            item(key = "speed-summary") {
                SpeedSummaryRow(summary = speedSummary, failureMessage = benchmarkFailureMessage)
            }
        }

        // ---- 3. the transfer, when there is one ---------------------------
        //
        // One at a time, so it is a single full-width surface rather than a per-card bar
        // nobody can find. It sits directly under the current model because that is where
        // the user is already looking when they start a download.
        if (transfer.stage != dev.charaly.runtime.presentation.TransferStage.READY_TO_FETCH) {
            item(key = "transfer") {
                DownloadSurface(
                    transfer = transfer,
                    onPause = onPauseDownload,
                    onResume = onResumeDownload,
                    onCancel = onCancelDownload,
                )
            }
        }

        // ---- 3b. the import, when one is running --------------------------
        //
        // Importing and downloading are different operations with different failure modes
        // and different progress stories - one has real byte counts and can be paused, the
        // other is a local copy that can only finish or fail - so they get separate
        // surfaces rather than one bar that has to lie about one of them.
        if (importState.isRunning) {
            item(key = "import-progress") {
                ImportSurface(state = importState)
            }
        }

        // ---- 3. installed ---------------------------------------------------
        if (installed.isNotEmpty()) {
            item(key = "installed-header") {
                CharalySectionHeader(
                    title = Loc.t("models.your_models"),
                    micro = true,
                    caption = "${installed.size} available to every story",
                )
            }
            installed.forEach { card ->
                item(key = "installed-${card.id}") {
                    ModelCardRow(
                        card = card,
                        onOpen = onOpenModel,
                        onUse = onUseModel,
                        onDelete = onDeleteModel,
                        onBenchmark = onBenchmarkModel,
                    )
                }
            }
        }

        // ---- 4. explore -----------------------------------------------------
        item(key = "explore-header") {
            CharalySectionHeader(
                title = Loc.t("models.explore"),
                micro = true,
                caption = if (isOnline) "Hugging Face Hub'dan" else ModelStagePresenter.OFFLINE_TITLE,
            )
        }

        item(key = "explore-search") {
            CharalySearchField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = "Modeller ve yayıncılar ara",
            )
        }

        item(key = "explore-filters") {
            CharalyPillRow(
                labels = ModelFilter.entries.map { it.label },
                selected = setOf(filter.label),
                onSelect = { label ->
                    ModelFilter.entries.firstOrNull { it.label == label }?.let(onFilterChange)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item(key = "explore-state") {
            BrowseStatus(
                state = hubState,
                isOnline = isOnline,
                onRetry = onRetrySearch,
            )
        }

        // The repository's files, inlined rather than pushed to a route: choosing a
        // quantisation is part of browsing, and a route per repository turns a two-tap
        // decision into six.
        if (repoDetail != null || detailState !is HubDetailState.Idle) {
            item(key = "repo-detail") {
                RepoDetailSurface(
                    detail = repoDetail,
                    state = detailState,
                    onClose = onCloseRepo,
                    onDownload = onDownload,
                )
            }
        }

        if (discoverable.isNotEmpty()) {
            item(key = "browse-header") {
                CharalySectionHeader(
                    title = Loc.t("models.browse"),
                    micro = true,
                    caption = "${discoverable.size} available",
                )
            }
            discoverable.forEach { card ->
                item(key = "browse-${card.id}") {
                    ModelCardRow(
                        card = card,
                        onOpen = { onOpenRepo(card.id) },
                        onUse = onUseModel,
                        onDelete = onDeleteModel,
                    )
                }
            }
        }

        item(key = "import-action") {
            CharalyQuietAction(
                label = Loc.t("models.import_cta"),
                icon = Icons.Filled.OpenInNew,
                onClick = onImport,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (installed.isEmpty() && discoverable.isEmpty()) {
            item(key = "empty") {
                CharalyEmptyState(
                    state = EmptyState(
                        title = Loc.t("models.empty_title"),
                        body = "Download one from the Hub, or import a GGUF from your device. " +
                            "Charaly'deki her şey bir model olmadan da çalışır.",
                        actionLabel = "GGUF İçe Aktar",
                        artSeed = "charaly-empty-models",
                    ),
                    action = {
                        CharalyAction(label = Loc.t("models.import_gguf_action"), onClick = onImport, enabled = !busy)
                    },
                )
            }
        }
    }
}

/**
 * The current model.
 *
 * ## Architecture, size, state - and nothing unmeasured
 *
 * The performance row appears only when something has actually been benchmarked. There is
 * no code path in the presenter that derives a rate from a size or a parameter count,
 * which is the property worth having: "47 tok/s" that was never measured is the single
 * easiest way for a model library to lose a user's trust.
 */
@Composable
private fun CurrentModelHero(hero: ModelHero, onClick: () -> Unit) {
    val needsAttention = hero.needsAttention

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CharalyShapes.soft)
            .background(Charaly.surface.raised)
            .then(if (hero.isLinked) Modifier.tappable(onClick) else Modifier)
            .padding(Charaly.space.lg),
    ) {
        Text(
            text = Loc.t("models.current"),
            style = MaterialTheme.typography.labelSmall,
            color = Charaly.ink.muted,
        )
        Spacer(Modifier.height(Charaly.space.xs))
        Text(
            text = hero.modelName,
            style = MaterialTheme.typography.headlineLarge,
            color = Charaly.ink.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )

        Spacer(Modifier.height(Charaly.space.sm))
        Row(
            horizontalArrangement = Arrangement.spacedBy(Charaly.space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CharalyPill(
                label = hero.stateLabel,
                selected = hero.isLoaded,
                accent = if (hero.isLoaded) Charaly.ink.primary else Charaly.atmosphere.accent,
            )
            if (hero.sizeLabel.isNotBlank()) CharalyPill(label = hero.sizeLabel)
            if (hero.architecture.isNotBlank()) CharalyPill(label = hero.architecture)
            if (hero.quantization.isNotBlank()) CharalyPill(label = hero.quantization)
        }

        // The speed line, and only ever from a real measurement.
        //
        // Three states, all of them words: a measured rate with the conditions it was taken
        // under, "Not measured", or the live phase of a benchmark in flight. There is no
        // fourth, because there is no honest way to produce one - a tok/s figure cannot be
        // derived from a model's size or a device's RAM class, and a number derived from
        // those looks exactly like a measurement to whoever reads it.
        val speed = hero.speed
        if (speed.isMeasuring) {
            Row(
                modifier = Modifier.padding(top = Charaly.space.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Charaly.space.sm),
            ) {
                CharalySpinner()
                Text(
                    text = speed.phaseLabel.ifBlank { "Measuring…" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Charaly.ink.secondary,
                )
            }
        } else {
            Column(modifier = Modifier.padding(top = Charaly.space.sm)) {
                Text(
                    text = speed.label.ifBlank { hero.measuredPerformance },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (speed.hasMeasurement) {
                        Charaly.ink.secondary
                    } else {
                        Charaly.ink.muted
                    },
                )
                // The evidence line: the rate and where it came from, together. Rendered
                // under the label rather than instead of it, so a reader always sees both
                // the verdict and its basis.
                if (speed.evidence.isNotBlank()) {
                    Text(
                        text = speed.evidence,
                        style = MaterialTheme.typography.bodySmall,
                        color = Charaly.ink.muted,
                    )
                }
                listOf(speed.latencyLabel, speed.memoryLabel)
                    .filter { it.isNotBlank() }
                    .takeIf { it.isNotEmpty() }
                    ?.let { extras ->
                        Text(
                            text = extras.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = Charaly.ink.muted,
                        )
                    }
            }
        }

        if (needsAttention) {
            Text(
                text = hero.problemLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = Charaly.atmosphere.accent,
                modifier = Modifier.padding(top = Charaly.space.sm),
            )
        }
    }
}

/**
 * One model card.
 *
 * ## The primary action is always visible
 *
 * Download is never behind a menu, and a card whose action is unavailable says *why* on
 * the card. A disabled button with no explanation is the single defect this screen exists
 * to avoid, because it teaches the user that the rest of the controls cannot be trusted
 * either.
 */
@Composable
private fun ModelCardRow(
    card: ModelCard,
    onOpen: (String) -> Unit,
    onUse: (String) -> Unit,
    onDelete: (String) -> Unit,
    /**
     * Runs a real measurement for this card.
     *
     * Defaulted to a no-op so a caller that does not offer benchmarking - a preview, a
     * wiring test - still renders a card without an inert control appearing on it.
     */
    onBenchmark: (String) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CharalyShapes.soft)
            .background(Charaly.surface.raised)
            .tappable { onOpen(card.id) }
            .padding(Charaly.space.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = card.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = Charaly.ink.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (card.publisher.isNotBlank()) {
                    Text(
                        text = card.publisher,
                        style = MaterialTheme.typography.bodySmall,
                        color = Charaly.ink.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            CharalyPill(
                label = card.stateLabel,
                selected = card.stateLabel == "HAZIR" || card.stateLabel == "ETKİN",
            )
        }

        if (card.description.isNotBlank()) {
            Text(
                text = card.description,
                style = MaterialTheme.typography.bodyMedium,
                color = Charaly.ink.secondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Charaly.space.xxs),
            )
        }

        val facts = listOfNotNull(
            card.sizeLabel.takeIf { it.isNotBlank() },
            card.quantization.takeIf { it.isNotBlank() },
            card.contextLabel.takeIf { it.isNotBlank() },
            // Only a measured rate becomes a pill. An unmeasured model shows its speed as a
            // line below the facts, with a Measure control, rather than as a "Not measured"
            // pill competing with the model's actual specifications for attention.
            card.speed.evidence.takeIf { it.isNotBlank() },
        )
        if (facts.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(top = Charaly.space.xs),
                horizontalArrangement = Arrangement.spacedBy(Charaly.space.xs),
            ) {
                facts.forEach { fact -> CharalyPill(label = fact) }
            }
        }

        // Why an action is unavailable, stated on the card.
        if (card.blockedReason.isNotBlank()) {
            Text(
                text = card.blockedReason,
                style = MaterialTheme.typography.bodySmall,
                color = Charaly.ink.muted,
                modifier = Modifier.padding(top = Charaly.space.xs),
            )
        }

        Row(
            modifier = Modifier.padding(top = Charaly.space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                card.actionLabel == "Modeli kullan" -> CharalyAction(
                    label = card.actionLabel,
                    onClick = { onUse(card.id) },
                )

                card.actionLabel == "Kullanımda" -> CharalyPill(label = Loc.t("models.in_use"), selected = true)

                card.actionIsDownload -> CharalyAction(
                    label = card.actionLabel,
                    onClick = { onOpen(card.id) },
                    icon = Icons.Filled.Download,
                )

                else -> CharalyQuietAction(
                    label = card.actionLabel.ifBlank { "Open" },
                    onClick = { onOpen(card.id) },
                )
            }

            Spacer(Modifier.weight(1f))

            // The Measure control.
            //
            // Offered only where the presenter said it can be: a model this build cannot load
            // would always fail, so it gets no button. While one is running the card shows a
            // spinner and the phase, and the control is gone - a button that says "Measuring"
            // and does nothing when tapped is the defect this codebase has already shipped
            // twice.
            when {
                card.speed.isMeasuring -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Charaly.space.xs),
                ) {
                    CharalySpinner()
                    Text(
                        text = card.speed.phaseLabel.ifBlank { "Measuring…" },
                        style = MaterialTheme.typography.bodySmall,
                        color = Charaly.ink.muted,
                    )
                }

                card.canBenchmark -> CharalyQuietAction(
                    label = Loc.t("models.measure"),
                    onClick = { onBenchmark(card.id) },
                )

                card.isInstalled -> CharalyQuietAction(
                    label = Loc.t("models.remove_short"),
                    onClick = { onDelete(card.id) },
                    contentColor = Charaly.ink.muted,
                )
            }
        }
    }
}

/**
 * The speed banner.
 *
 * ## What it says, and what it refuses to say
 *
 * It counts measurements and names a run in progress. It never states a rate, because no
 * single number describes "how fast is this phone" - and a banner that averaged several
 * models into one figure would be a number about no model in particular.
 *
 * The failure sentence is shown here rather than as a snackbar because it is a fact about a
 * card the user is looking at, and a toast would be gone before they read it.
 */
@Composable
private fun SpeedSummaryRow(
    summary: dev.charaly.runtime.presentation.BenchmarkSummary,
    failureMessage: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CharalyShapes.soft)
            .background(Charaly.surface.base)
            .padding(Charaly.space.md),
        verticalArrangement = Arrangement.spacedBy(Charaly.space.xxs),
    ) {
        if (summary.isRunning) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Charaly.space.sm),
            ) {
                CharalySpinner()
                Text(
                    text = summary.runningLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Charaly.ink.secondary,
                )
            }
        } else {
            Text(
                // "3 of 4 models measured on this device." Reads as a state, not a claim
                // about any of them.
                text = "${summary.measuredCount} of " +
                    "${summary.measuredCount + summary.unmeasuredCount} models measured on this device",
                style = MaterialTheme.typography.bodyMedium,
                color = Charaly.ink.secondary,
            )
        }
        if (summary.unmeasuredCount > 0 && !summary.isRunning) {
            Text(
                text = Loc.t("models.unmeasured_note"),
                style = MaterialTheme.typography.bodySmall,
                color = Charaly.ink.muted,
            )
        }
        if (failureMessage.isNotBlank()) {
            Text(
                text = failureMessage,
                style = MaterialTheme.typography.bodySmall,
                color = Charaly.ink.muted,
            )
        }
    }
}

/**
 * The download surface.
 *
 * ## Every number here was measured
 *
 * Name, file, downloaded / total, percentage, speed and remaining time all come from the
 * pipeline's own progress frames. The bar's fraction is null when the total is genuinely
 * unknown, and it draws as indeterminate in that case - because a bar that reports 0% for a
 * transfer whose size is not published is lying, and a bar that reports 100% for a paused
 * download is worse.
 *
 * The stage label comes from the state enum rather than from the screen, so VERIFYING and
 * INSTALLING are real pipeline stages and not animations standing in for work.
 */
/**
 * The import, mid-flight.
 *
 * ## Why there is no percentage on this bar
 *
 * A SAF copy reports no total until the copy finishes, and by then a percentage is
 * meaningless. The download surface above can show a real fraction because the server
 * declares a content length; this one cannot, so it shows an indeterminate bar and says
 * which of the two steps is happening. A bar invented from elapsed time would be the
 * single most-looked-at lie in the app, because it sits exactly where the user is
 * already anxious about whether their import worked.
 */
@Composable
private fun ImportSurface(state: dev.charaly.app.ui.ModelImportState) {
    val title = Loc.t(
        if (state == dev.charaly.app.ui.ModelImportState.IMPORTING) "model.importing" else "model.verifying",
    )
    val detail = Loc.t(
        if (state == dev.charaly.app.ui.ModelImportState.IMPORTING) {
            "model.importing_detail"
        } else {
            "model.verifying_detail"
        },
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CharalyShapes.soft)
            .background(Charaly.surface.elevated)
            .padding(Charaly.space.md)
            .semantics { contentDescription = "$title. $detail" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Charaly.ink.primary,
                modifier = Modifier.weight(1f),
            )
            CharalySpinner(color = Charaly.atmosphere.accent)
        }
        Spacer(Modifier.height(Charaly.space.xxs))
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = Charaly.ink.muted,
        )
    }
}

@Composable
private fun DownloadSurface(
    transfer: TransferProgress,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    var showDetail by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CharalyShapes.soft)
            .background(Charaly.surface.elevated)
            .padding(Charaly.space.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = transfer.stage.label,
                style = MaterialTheme.typography.titleMedium,
                color = Charaly.ink.primary,
                modifier = Modifier.weight(1f),
            )
            if (transfer.isRunning) CharalySpinner(color = Charaly.atmosphere.accent)
        }

        if (transfer.fileName.isNotBlank()) {
            Text(
                text = transfer.fileName,
                style = MaterialTheme.typography.bodySmall,
                color = Charaly.ink.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(Charaly.space.sm))

        // The bar. Indeterminate when the total is unknown, which is the honest rendering
        // of "we do not know how big this is".
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CharalyShapes.pill)
                .background(Charaly.surface.overlay)
                .semantics {
                    contentDescription = transfer.progressDescription()
                },
        ) {
            val fraction = transfer.fraction
            if (fraction == null) {
                Box(
                    Modifier
                        .fillMaxWidth(0.4f)
                        .height(4.dp)
                        .clip(CharalyShapes.pill)
                        .background(Charaly.atmosphere.accent.copy(alpha = 0.6f)),
                )
            } else {
                Box(
                    Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .height(4.dp)
                        .clip(CharalyShapes.pill)
                        .background(Charaly.atmosphere.accent),
                )
            }
        }

        Spacer(Modifier.height(Charaly.space.xs))
        Row {
            Text(
                text = transfer.transferred,
                style = MaterialTheme.typography.bodySmall,
                color = Charaly.ink.secondary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = listOfNotNull(
                    transfer.speed.takeIf { it.isNotBlank() },
                    transfer.remaining.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = Charaly.ink.muted,
            )
        }

        if (transfer.hasFailure) {
            Text(
                text = transfer.failureLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = Charaly.atmosphere.accent,
                modifier = Modifier.padding(top = Charaly.space.xs),
            )
            if (transfer.technicalDetail.isNotBlank()) {
                CharalyQuietAction(
                    label = if (showDetail) "Ayrıntıları gizle" else "Ayrıntılar",
                    onClick = { showDetail = !showDetail },
                )
                if (showDetail) {
                    Text(
                        text = transfer.technicalDetail,
                        style = MaterialTheme.typography.bodySmall,
                        color = Charaly.ink.muted,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.padding(top = Charaly.space.sm),
            horizontalArrangement = Arrangement.spacedBy(Charaly.space.xs),
        ) {
            if (transfer.canPause) {
                CharalyQuietAction(label = Loc.t("download.pause"), onClick = onPause)
            }
            if (transfer.canResume) {
                CharalyAction(
                    label = if (transfer.hasFailure) "Tekrar dene" else "Devam et",
                    onClick = onResume,
                )
            }
            if (transfer.canCancel) {
                CharalyQuietAction(
                    label = Loc.t("download.cancel"),
                    onClick = onCancel,
                    contentColor = Charaly.ink.muted,
                )
            }
        }
    }
}

/**
 * The browse status line.
 *
 * ## Offline is a first-class state, not an error screen
 *
 * The whole app keeps working with no connection: worlds, stories and installed models are
 * all local. So the offline copy says what still works, and the installed section above is
 * untouched. A library that reads as broken in airplane mode makes the user think the *app*
 * is broken.
 */
@Composable
private fun BrowseStatus(
    state: HubSearchState,
    isOnline: Boolean,
    onRetry: () -> Unit,
) {
    when (state) {
        is HubSearchState.Idle -> if (!isOnline) {
            OfflineNote()
        }

        is HubSearchState.Searching -> Row(verticalAlignment = Alignment.CenterVertically) {
            CharalySpinner(color = Charaly.ink.muted)
            Spacer(Modifier.size(Charaly.space.xs))
            Text(
                text = Loc.t("models.searching"),
                style = MaterialTheme.typography.bodyMedium,
                color = Charaly.ink.muted,
            )
        }

        is HubSearchState.Loaded -> Text(
            text = if (state.hasMore) {
                "${state.resultCount} models on the Hub. Showing the first page."
            } else {
                "${state.resultCount} models"
            },
            style = MaterialTheme.typography.bodySmall,
            color = Charaly.ink.muted,
        )

        is HubSearchState.Failed -> Column {
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.offline) Charaly.ink.secondary else Charaly.atmosphere.accent,
            )
            if (state.offline) {
                Text(
                    text = ModelStagePresenter.OFFLINE_BODY,
                    style = MaterialTheme.typography.bodySmall,
                    color = Charaly.ink.muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (!state.offline) {
                CharalyQuietAction(label = state.actionLabel, onClick = onRetry)
            }
        }
    }
}

@Composable
private fun OfflineNote() {
    Column {
        Text(
            text = ModelStagePresenter.OFFLINE_TITLE,
            style = MaterialTheme.typography.titleMedium,
            color = Charaly.ink.secondary,
        )
        Text(
            text = ModelStagePresenter.OFFLINE_BODY,
            style = MaterialTheme.typography.bodySmall,
            color = Charaly.ink.muted,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * One repository's files.
 *
 * The recommended file is marked rather than sorted silently: a repository publishes a
 * dozen quantisations and the choice is a *device* decision, which is why Charaly reads
 * each candidate's own GGUF header before recommending one rather than trusting the
 * repository's description.
 */
@Composable
private fun RepoDetailSurface(
    detail: HubRepoDetail?,
    state: HubDetailState,
    onClose: () -> Unit,
    onDownload: (HubRepoDetail, dev.charaly.runtime.presentation.HubFileOption) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CharalyShapes.soft)
            .background(Charaly.surface.raised)
            .padding(Charaly.space.md),
    ) {
        when (state) {
            is HubDetailState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CharalySpinner(color = Charaly.ink.muted)
                Spacer(Modifier.size(Charaly.space.xs))
                Text(
                    text = Loc.t("models.checking_repo", state.repoId),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Charaly.ink.muted,
                )
            }

            is HubDetailState.Failed -> Column {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Charaly.ink.secondary,
                )
                CharalyQuietAction(label = Loc.t("models.close"), onClick = onClose)
            }

            else -> if (detail != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = detail.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = Charaly.ink.primary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = detail.author,
                            style = MaterialTheme.typography.bodySmall,
                            color = Charaly.ink.muted,
                        )
                    }
                    CharalyIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = Loc.t("models.close_repo", detail.title),
                        onClick = onClose,
                    )
                }

                if (detail.description.isNotBlank()) {
                    Text(
                        text = detail.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Charaly.ink.secondary,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = Charaly.space.xs),
                    )
                }

                Spacer(Modifier.height(Charaly.space.sm))
                detail.files.forEach { file ->
                    FileRow(
                        file = file,
                        onDownload = { onDownload(detail, file) },
                    )
                }

                if (detail.otherFileCount > 0) {
                    Text(
                        text = "${detail.otherFileCount} other files in this repository. " +
                            "Charaly only installs GGUF models.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Charaly.ink.muted,
                        modifier = Modifier.padding(top = Charaly.space.xs),
                    )
                }
            }
        }
    }
}

/**
 * One downloadable file.
 *
 * The action label is derived from the *verdict*: "DOWNLOAD" only when Charaly can load it,
 * "NOT AVAILABLE" when it cannot, "INSTALLED" when the file is already here. A button that
 * says DOWNLOAD and then refuses is the defect this replaces.
 */
@Composable
private fun FileRow(
    file: dev.charaly.runtime.presentation.HubFileOption,
    onDownload: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Charaly.space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = file.fileName,
                style = MaterialTheme.typography.bodyMedium,
                color = Charaly.ink.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = file.metaLine,
                style = MaterialTheme.typography.bodySmall,
                color = Charaly.ink.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (file.isRecommended) {
                CharalyPill(label = Loc.t("models.best_fit"), selected = true)
            }
            if (!file.verdict.canDownload && file.verdict.reason.isNotBlank()) {
                Text(
                    text = file.verdict.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = Charaly.ink.muted,
                )
            }
        }
        if (file.verdict.canDownload && !file.isInstalled) {
            CharalyAction(
                label = Loc.t("models.download"),
                onClick = onDownload,
                icon = Icons.Filled.Download,
            )
        } else if (file.isInstalled) {
            CharalyPill(label = Loc.t("models.installed"), selected = true)
        }
    }
}

/** A progress sentence for a screen reader, built from measured values only. */
private fun TransferProgress.progressDescription(): String = buildString {
    append(stage.label)
    if (transferred.isNotBlank()) append(". ").append(transferred)
    fraction?.let { append(". ").append("${(it * 100).toInt()} percent") }
    if (remaining.isNotBlank()) append(". ").append(remaining)
}

/** The heights the transfer surface and the cards take, so previews agree with the screen. */
internal object ModelCardMetrics {
    val minHeight: Dp = 120.dp
    val heroHeight: Dp = 200.dp
}