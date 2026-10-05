package dev.charaly.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.charaly.app.ui.art.ArtworkHero
import dev.charaly.app.ui.components.CharalyCard
import dev.charaly.app.ui.components.CharalyGhostButton
import dev.charaly.app.ui.components.CharalyIconButton
import dev.charaly.app.ui.components.CharalyPrimaryButton
import dev.charaly.app.ui.components.CharalyTextButton
import dev.charaly.app.ui.components.DetailRow
import dev.charaly.app.ui.components.EmptyStateView
import dev.charaly.app.ui.components.Eyebrow
import dev.charaly.app.ui.components.GenreChip
import dev.charaly.app.ui.components.HairLine
import dev.charaly.app.ui.components.SectionHeader
import dev.charaly.app.ui.components.StatusDot
import dev.charaly.app.ui.theme.Charaly
import dev.charaly.runtime.domain.PackArtwork
import dev.charaly.runtime.model.InstalledModel
import dev.charaly.runtime.model.ModelBinding
import dev.charaly.runtime.model.ModelRegistry
import dev.charaly.runtime.model.formatBytes
import dev.charaly.runtime.presentation.CatalogModelCard
import dev.charaly.runtime.presentation.EmptyState
import dev.charaly.runtime.presentation.InstalledModelCard
import dev.charaly.runtime.presentation.ModelDetailSnapshot
import dev.charaly.runtime.presentation.ModelLibraryPresenter
import dev.charaly.runtime.presentation.ModelLibrarySnapshot
import dev.charaly.runtime.presentation.ProfileCard
import dev.charaly.runtime.presentation.ResolvedTheme
import dev.charaly.runtime.presentation.StoryUse
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The model library, and the model behind it.
 *
 * Two rules shape this screen more than anything else:
 *
 * 1. **Everything is local.** Charaly ships no model and declares no INTERNET
 *    permission, so the only way a model arrives is a GGUF the user picked with
 *    the system file picker. The catalog below that is therefore a *reference*,
 *    not a shop: a card that cannot be downloaded says exactly why instead of
 *    showing a progress bar that goes nowhere.
 * 2. **The card never invents state.** Ready, not loaded, unsupported, too large
 *    - all of it comes from the presenter, and a model that has not been loaded
 *    says "Not loaded" rather than pretending to be running.
 */

// ---------------------------------------------------------------------------
// Library
// ---------------------------------------------------------------------------

@Composable
fun ModelLibraryScreen(
    snapshot: dev.charaly.runtime.presentation.ModelLibrarySnapshot,
    busy: Boolean,
    onQueryChange: (String) -> Unit,
    onImport: (android.net.Uri) -> Unit,
    onOpenModel: (String) -> Unit,
    onUseModel: (String) -> Unit,
    onDeleteModel: (String) -> Unit,
    onVerifyModel: (String) -> Unit,
) {
    // The picker is the only way a model enters the app, so the launcher lives
    // with the screen that offers the action.
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> uri?.let(onImport) },
    )

    // The presenter owns the filtering, so the field is a pure mirror of state.
    val query = snapshot.query

    val installed = snapshot.installed
    val recommended = snapshot.recommended
    val others = snapshot.others
    val nothingLeft = query.isNotBlank() && installed.isEmpty() && recommended.isEmpty() &&
        others.isEmpty() && snapshot.awaitingEngineUpdate.isEmpty()

    fun act(id: String, action: (String) -> Unit) = action(id)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Charaly.tokens.spacing.gutter,
            end = Charaly.tokens.spacing.gutter,
            top = Charaly.tokens.spacing.md,
            bottom = Charaly.tokens.spacing.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.sm),
    ) {
        item(key = "title") {
            LibraryTitle(busy = busy)
        }

        item(key = "search") {
            ModelSearchField(query = query, onQueryChange = onQueryChange)
        }

        item(key = "import") {
            CharalyPrimaryButton(
                label = "Import GGUF",
                onClick = { picker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                loading = busy,
                icon = Icons.Filled.Add,
            )
        }

        item(key = "active") {
            ActiveModelStrip(snapshot)
        }

        val emptyState = snapshot.emptyState
        if (emptyState != null) {
            item(key = "empty") {
                EmptyStateView(state = emptyState, action = {
                    CharalyPrimaryButton(
                        label = emptyState.actionLabel.ifBlank { "Import GGUF" },
                        onClick = { picker.launch(arrayOf("*/*")) },
                        enabled = !busy,
                        icon = Icons.Filled.Add,
                    )
                })
            }
        }

        if (installed.isNotEmpty()) {
            item(key = "installed-header") {
                SectionHeader(
                    title = "Installed",
                    subtitle = installedSubtitle(snapshot, installed.size, query),
                )
            }
            items(installed, key = { "installed-${it.id}" }) { card ->
                InstalledModelRow(
                    card = card,
                    onOpen = onOpenModel,
                    onUse = onUseModel,
                    onVerify = onVerifyModel,
                    onDelete = onDeleteModel,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (recommended.isNotEmpty()) {
            item(key = "recommended-header") {
                SectionHeader(
                    title = "Recommended",
                    subtitle = "Picked to hold a long roleplay on a phone.",
                )
            }
            items(recommended, key = { "recommended-${it.id}" }) { card ->
                CatalogModelRow(card = card, downloadNote = snapshot.downloadNote, modifier = Modifier.fillMaxWidth())
            }
        }

        if (others.isNotEmpty()) {
            item(key = "others-header") {
                SectionHeader(
                    title = "More models",
                    subtitle = "Everything else Charaly knows about.",
                )
            }
            items(others, key = { "other-${it.id}" }) { card ->
                CatalogModelRow(card = card, downloadNote = snapshot.downloadNote, modifier = Modifier.fillMaxWidth())
            }
        }

        // Real models this build genuinely cannot load. Shown rather than hidden: a
        // user who has heard of Gemma 4 deserves to be told why it is not here today,
        // rather than being left to wonder whether Charaly knows the model exists.
        if (snapshot.awaitingEngineUpdate.isNotEmpty()) {
            item(key = "awaiting-header") {
                SectionHeader(
                    title = "Coming",
                    subtitle = "Real models Charaly knows about but cannot load yet.",
                )
            }
            items(snapshot.awaitingEngineUpdate, key = { "awaiting-${it.id}" }) { card ->
                AwaitingEngineRow(card = card, modifier = Modifier.fillMaxWidth())
            }
        }

        if (nothingLeft) {
            item(key = "no-match") {
                NoMatchNote(query = query, onClear = { onQueryChange("") })
            }
        }
    }
}

/** Title, subtitle, and a quiet progress note while the library is working. */
@Composable
private fun LibraryTitle(busy: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Models",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Your models live on this device. Nothing is uploaded, and nothing is downloaded for you.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (busy) {
            StatusDot(
                label = "Working…",
                color = Charaly.colors.warning,
                pulsing = true,
                modifier = Modifier.padding(start = Charaly.tokens.spacing.sm),
            )
        }
    }
}

/** The search box. Deliberately not a Material text field: it is a filter. */
@Composable
private fun ModelSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = Charaly.tokens.radii.shapeSm,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = "Search models",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Charaly.tokens.icons.small),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 14.dp),
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = "Search models",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(Charaly.accent.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                CharalyIconButton(
                    icon = Icons.Filled.Close,
                    contentDescription = "Clear the model search",
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(48.dp),
                )
            }
        }
    }
}

/** The strip that answers "which model is running right now?". */
@Composable
private fun ActiveModelStrip(snapshot: ModelLibrarySnapshot) {
    val active = snapshot.installed.firstOrNull { it.id == snapshot.activeModelId }
    CharalyCard(
        modifier = Modifier.fillMaxWidth(),
        container = MaterialTheme.colorScheme.surfaceContainer,
        border = Charaly.accent.primary.copy(alpha = 0.30f),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Memory,
                    contentDescription = null,
                    tint = Charaly.accent.primary,
                    modifier = Modifier.size(Charaly.tokens.icons.small),
                )
                Spacer(Modifier.width(6.dp))
                Eyebrow("Active model")
            }
            if (active == null) {
                Text(
                    text = "No model in use yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Import a GGUF from this device, then choose it below to start generating.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = active.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.md),
                ) {
                    StatusDot(
                        label = if (active.isLoaded) "Ready" else "Not loaded",
                        color = statusColorFor(active.stateLabel),
                    )
                    Text(
                        text = active.sizeLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = snapshot.downloadNote,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One installed model: what it is, whether it works here, and what to do. */
@Composable
private fun InstalledModelRow(
    card: InstalledModelCard,
    onOpen: (String) -> Unit,
    onUse: (String) -> Unit,
    onVerify: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmingDelete by remember(card.id) { mutableStateOf(false) }

    CharalyCard(
        modifier = modifier,
        onClick = { onOpen(card.id) },
        border = if (card.isActive) Charaly.accent.primary.copy(alpha = 0.45f) else null,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = card.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "${card.originLabel} · ${card.sizeLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (card.isActive) {
                    StatusDot(
                        label = "In use",
                        color = Charaly.accent.primary,
                        modifier = Modifier.padding(start = Charaly.tokens.spacing.sm),
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.md),
            ) {
                StatusDot(
                    label = card.stateLabel,
                    color = statusColorFor(card.stateLabel),
                    pulsing = card.isActive && !card.isLoaded,
                )
                if (card.profileCount > 0) {
                    Text(
                        text = "${card.profileCount} saved ${if (card.profileCount == 1) "profile" else "profiles"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Size is already in the card's second line, so it is not repeated.
            ChipRow(card.detailRows.filterNot { it.label == "Size" }.map { "${it.label}: ${it.value}" })

            if (!card.isReady) {
                Text(
                    text = card.verdictMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColorFor(card.stateLabel),
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (card.actions.canUse) {
                    CharalyPrimaryButton(
                        label = "Use",
                        onClick = { onUse(card.id) },
                        icon = Icons.Filled.Check,
                    )
                }
                Spacer(Modifier.weight(1f))
                CharalyTextButton(
                    label = "Details",
                    onClick = { onOpen(card.id) },
                    icon = Icons.AutoMirrored.Filled.ArrowForward,
                    contentColor = Charaly.accent.primary,
                )
            }

            HairLine()

            Row(verticalAlignment = Alignment.CenterVertically) {
                CharalyTextButton(
                    label = "Verify",
                    onClick = { onVerify(card.id) },
                    icon = Icons.Filled.VerifiedUser,
                )
                Spacer(Modifier.weight(1f))
                CharalyTextButton(
                    label = "Delete",
                    onClick = { confirmingDelete = true },
                    icon = Icons.Filled.Delete,
                    contentColor = Charaly.colors.danger,
                )
            }
        }
    }

    if (confirmingDelete) {
        ConfirmRemoveDialog(
            name = card.displayName,
            onConfirm = {
                confirmingDelete = false
                onDelete(card.id)
            },
            onDismiss = { confirmingDelete = false },
        )
    }
}

/**
 * A catalog entry.
 *
 * Downloads are offered only when this build can actually fetch a file. Charaly
 * declares no INTERNET permission, so in practice the button is disabled and the
 * card says why. There is no progress bar here, and there never will be: the app
 * has no network to make progress against.
 */
@Composable
private fun CatalogModelRow(
    card: CatalogModelCard,
    downloadNote: String,
    modifier: Modifier = Modifier,
) {
    CharalyCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.xs)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = card.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = card.publisher,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (card.isRecommended) {
                    Eyebrow(
                        text = "Recommended",
                        modifier = Modifier.padding(start = Charaly.tokens.spacing.sm),
                    )
                }
            }

            if (card.description.isNotBlank()) {
                Text(
                    text = card.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            ChipRow(
                listOfNotNull(
                    card.parameterLabel.takeIf { it.isNotBlank() },
                    card.sizeLabel.takeIf { it.isNotBlank() },
                    card.quantization.takeIf { it.isNotBlank() },
                    card.contextLabel.takeIf { it.isNotBlank() },
                    card.ramLabel.takeIf { it.isNotBlank() },
                    card.roleplayLabel.takeIf { it.isNotBlank() },
                ),
            )

            if (card.license.isNotBlank()) {
                Text(
                    text = "Licence: ${card.license}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HairLine()

            when {
                card.isInstalled -> {
                    CharalyGhostButton(
                        label = "Installed",
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.fillMaxWidth(),
                        icon = Icons.Filled.Check,
                    )
                    Caption(if (card.actions.disabledReason.isNotBlank()) card.actions.disabledReason else "This model is already on your device.")
                }

                else -> {
                    CharalyPrimaryButton(
                        label = "Download",
                        // This build ships no download pipeline, so nothing is ever
                        // fetched here. The control states the truth instead.
                        onClick = {},
                        modifier = Modifier.fillMaxWidth(),
                        enabled = card.actions.canDownload,
                        icon = Icons.Filled.Download,
                    )
                    Caption(
                        if (card.actions.canDownload) downloadNote
                        else card.actions.disabledReason.ifBlank { "This build cannot download models." },
                    )
                }
            }
        }
    }
}

/** Detail screen. One model, the profile it runs with, and the stories using it. */
@Composable
fun ModelDetailScreen(
    snapshot: dev.charaly.runtime.presentation.ModelDetailSnapshot?,
    onBack: () -> Unit,
    onUse: (String) -> Unit,
    onDelete: (String) -> Unit,
    onVerify: (String) -> Unit,
    onOpenStory: (String) -> Unit,
) {
    if (snapshot == null) {
        MissingModelBody(onBack = onBack)
        return
    }

    val binding = snapshot.binding
    val boundProfile = snapshot.builtInProfiles.firstOrNull { it.isBound }
        ?: snapshot.profiles.firstOrNull { it.isBound }
    var advancedOpen by rememberSaveable(snapshot.id) { mutableStateOf(false) }
    var confirmingDelete by remember(snapshot.id) { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = Charaly.tokens.spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.sm),
    ) {
        item(key = "hero") {
            ModelHero(snapshot = snapshot, onBack = onBack)
        }

        item(key = "headline") {
            Column(Modifier.pageGutter()) {
                Spacer(Modifier.height(Charaly.tokens.spacing.xs))
                Text(
                    text = snapshot.displayName,
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Charaly.tokens.spacing.xs))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.sm),
                ) {
                    StatusDot(
                        label = snapshot.stateLabel,
                        color = statusColorFor(snapshot.stateLabel),
                    )
                    if (snapshot.isActive) {
                        Eyebrow("In use")
                    }
                }
                Spacer(Modifier.height(Charaly.tokens.spacing.xs))
                Text(
                    text = snapshot.verdictMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (snapshot.verdictLevel.equals("READY", ignoreCase = true)) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        statusColorFor(snapshot.stateLabel)
                    },
                )
            }
        }

        item(key = "details") {
            Column(Modifier.pageGutter()) {
                SectionHeader(title = "Details")
                Spacer(Modifier.height(Charaly.tokens.spacing.xs))
                CharalyCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        snapshot.detailRows.forEach { row ->
                            DetailRow(label = row.label, value = row.value)
                        }
                    }
                }
            }
        }

        item(key = "profile-bound") {
            Column(Modifier.pageGutter()) {
                SectionHeader(
                    title = "Model profile",
                    subtitle = "How this model is prompted when a story runs.",
                )
                Spacer(Modifier.height(Charaly.tokens.spacing.xs))
                CharalyCard(
                    modifier = Modifier.fillMaxWidth(),
                    border = if (boundProfile != null) Charaly.accent.primary.copy(alpha = 0.35f) else null,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.xs)) {
                        if (boundProfile != null) {
                            Text(
                                text = boundProfile.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = boundProfile.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                text = "No profile bound yet",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "A profile decides the temperature, the prose style and how much story context this model is given.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HairLine()
                        Eyebrow("Sampling", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ChipRow(samplingChips(binding))
                    }
                }
            }
        }

        if (snapshot.builtInProfiles.isNotEmpty()) {
            item(key = "profiles-header") {
                Column(Modifier.pageGutter()) {
                    SectionHeader(
                        title = "Available profiles",
                        subtitle = "The presets Charaly ships with. The one in use is marked.",
                    )
                }
            }
            items(snapshot.builtInProfiles, key = { "profile-${it.id}" }) { profile ->
                ProfileOptionCard(profile = profile, modifier = Modifier.pageGutter())
            }
        }

        item(key = "advanced") {
            Column(Modifier.pageGutter()) {
                if (binding == null) {
                    Text(
                        text = "Open a story to see the sampler settings this model runs with.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    CharalyTextButton(
                        label = if (advancedOpen) "Hide sampler settings" else "Advanced sampler settings",
                        onClick = { advancedOpen = !advancedOpen },
                        icon = if (advancedOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    )
                    AnimatedVisibility(visible = advancedOpen) {
                        Column {
                            Spacer(Modifier.height(Charaly.tokens.spacing.xs))
                            CharalyCard(modifier = Modifier.fillMaxWidth()) {
                                Column {
                                    ModelLibraryPresenter.advancedRows(binding).forEach { row ->
                                        DetailRow(label = row.label, value = row.value)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item(key = "stories-header") {
            Column(Modifier.pageGutter()) {
                SectionHeader(
                    title = "Stories using this model",
                    subtitle = if (snapshot.usedByStories.isEmpty()) {
                        "Nothing is bound to it yet."
                    } else {
                        "${snapshot.usedByStories.size} on this device"
                    },
                )
            }
        }

        items(snapshot.usedByStories, key = { "story-${it.storyId}" }) { story ->
            StoryUseRow(
                story = story,
                onOpen = onOpenStory,
                modifier = Modifier.pageGutter(),
            )
        }

        item(key = "actions") {
            Column(Modifier.pageGutter()) {
                Spacer(Modifier.height(Charaly.tokens.spacing.xs))
                HairLine()
                Spacer(Modifier.height(Charaly.tokens.spacing.md))
                CharalyPrimaryButton(
                    label = "Use this model",
                    onClick = { onUse(snapshot.id) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = snapshot.actions.canUse && !snapshot.isActive,
                    icon = Icons.Filled.Check,
                )
                if (!snapshot.actions.canUse && snapshot.actions.disabledReason.isNotBlank()) {
                    Spacer(Modifier.height(Charaly.tokens.spacing.xs))
                    Text(
                        text = snapshot.actions.disabledReason,
                        style = MaterialTheme.typography.labelSmall,
                        color = Charaly.colors.danger,
                    )
                }
                Spacer(Modifier.height(Charaly.tokens.spacing.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.sm)) {
                    CharalyGhostButton(
                        label = "Verify",
                        onClick = { onVerify(snapshot.id) },
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.VerifiedUser,
                    )
                    CharalyGhostButton(
                        label = "Remove model",
                        onClick = { confirmingDelete = true },
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.Delete,
                        contentColor = Charaly.colors.danger,
                    )
                }
            }
        }
    }

    if (confirmingDelete) {
        ConfirmRemoveDialog(
            name = snapshot.displayName,
            onConfirm = {
                confirmingDelete = false
                onDelete(snapshot.id)
            },
            onDismiss = { confirmingDelete = false },
        )
    }
}

/** The generated banner behind the back affordance. Deterministic per model id. */
@Composable
private fun ModelHero(snapshot: ModelDetailSnapshot, onBack: () -> Unit) {
    ArtworkHero(
        artwork = PackArtwork.generated(seed = snapshot.id),
        theme = ResolvedTheme.BRAND,
        scrimStrength = 0.45f,
        modifier = Modifier
            .fillMaxWidth()
            .height(176.dp),
    ) {
        CharalyIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back to models",
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = Charaly.tokens.spacing.xs, top = Charaly.tokens.spacing.xs)
                .size(48.dp),
            tint = Color.White,
            container = Charaly.colors.scrimTop.copy(alpha = 0.40f),
        )
    }
}

/** A profile the user can recognise by its voice rather than its numbers. */
@Composable
private fun ProfileOptionCard(
    profile: ProfileCard,
    modifier: Modifier = Modifier,
) {
    CharalyCard(
        modifier = modifier,
        container = if (profile.isBound) {
            Charaly.accent.primary.copy(alpha = 0.10f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        border = if (profile.isBound) Charaly.accent.primary.copy(alpha = 0.55f) else null,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (profile.isBound) {
                    Eyebrow("In use", color = Charaly.accent.primary)
                }
            }
            Text(
                text = profile.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.xs)) {
                GenreChip(profile.temperament.label)
                GenreChip(profile.narrativeStyleLabel)
            }
        }
    }
}

/** One story that is bound to this model. */
@Composable
private fun StoryUseRow(
    story: StoryUse,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    CharalyCard(
        modifier = modifier,
        onClick = { onOpen(story.storyId) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.AutoStories,
                contentDescription = null,
                tint = Charaly.accent.accent,
                modifier = Modifier.size(Charaly.tokens.icons.medium),
            )
            Spacer(Modifier.width(Charaly.tokens.spacing.sm))
            Column(Modifier.weight(1f)) {
                Text(
                    text = story.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${story.packTitle} · ${story.profileName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(Charaly.tokens.spacing.xs))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Open ${story.title}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Charaly.tokens.icons.small),
            )
        }
    }
}

/** The model is gone: say so plainly and offer the way back. */
@Composable
private fun MissingModelBody(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = Charaly.tokens.spacing.xs, top = Charaly.tokens.spacing.xs)) {
            CharalyIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back to models",
                onClick = onBack,
                modifier = Modifier.size(48.dp),
            )
        }
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            EmptyStateView(
                state = EmptyState(
                    title = "This model is no longer installed.",
                    body = "It was removed from this device, so there is nothing left to describe. Import a GGUF from the library and pick it again.",
                    artSeed = "charaly-empty-model-detail",
                ),
                action = {
                    CharalyGhostButton(label = "Back to models", onClick = onBack)
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------

/** Page padding, in one place, so every row lines up with the header. */
@Composable
private fun Modifier.pageGutter(): Modifier =
    this.padding(horizontal = Charaly.tokens.spacing.gutter)

/** A wrapping row of quiet chips. FlowRow so a long model never scrolls sideways. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(chips: List<String>, modifier: Modifier = Modifier) {
    if (chips.isEmpty()) return
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.xs),
    ) {
        chips.forEach { chip -> GenreChip(chip) }
    }
}

/** The small explanatory line under an action. */
@Composable
private fun Caption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The installed-count line under the "Installed" header. */
private fun installedSubtitle(snapshot: ModelLibrarySnapshot, visible: Int, query: String): String = when {
    visible == 0 -> ""
    query.isNotBlank() -> "$visible matching \"$query\""
    else -> "$visible on this device · ${formatBytes(snapshot.totalInstalledBytes)} used"
}

/**
 * Colour for an install state.
 *
 * The label always says the same thing as the colour, so this never has to be
 * the only signal.
 */
@Composable
private fun statusColorFor(stateLabel: String): Color = when {
    stateLabel.equals("Ready", ignoreCase = true) -> Charaly.colors.success
    stateLabel.contains("not", ignoreCase = true) -> Charaly.colors.danger
    else -> Charaly.colors.warning
}

/** The four numbers that actually change how a model feels. */
private fun samplingChips(binding: ModelBinding?): List<String> {
    if (binding == null) return listOf("Not set yet")
    val interesting = listOf("Temperature", "Top P", "Top K", "Min P")
    return ModelLibraryPresenter.advancedRows(binding)
        .filter { it.label in interesting }
        .map { "${it.label} ${it.value}" }
}

/** Nothing matched the search box. */
/**
 * A real model this build cannot load.
 *
 * Deliberately not a Download button. The whole point of showing this row is that the
 * honest answer is "not yet, and here is exactly why" - a greyed-out Download would
 * still be a promise the app cannot keep.
 */
@Composable
private fun AwaitingEngineRow(
    card: dev.charaly.runtime.presentation.AwaitingEngineCard,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(
                horizontal = Charaly.tokens.spacing.gutter,
                vertical = Charaly.tokens.spacing.xs,
            )
            .clip(Charaly.tokens.radii.shapeLg)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(Charaly.tokens.spacing.md)
            .semantics {
                contentDescription = "${card.name}. ${card.reason}"
            },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = card.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            StatusDot(
                label = "Coming",
                color = Charaly.accent.accent,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Text(
            text = "${card.publisher} · ${card.parameterLabel}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            text = card.reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (card.upstreamId.isNotBlank()) {
            Text(
                text = card.upstreamId,
                style = MaterialTheme.typography.labelSmall,
                color = Charaly.accent.accent,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun NoMatchNote(query: String, onClear: () -> Unit) {
    CharalyCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "No model matches \"$query\".",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            CharalyTextButton(label = "Clear search", onClick = onClear)
        }
    }
}

/** Deleting a model removes its file, so it always asks first. */
@Composable
private fun ConfirmRemoveDialog(
    name: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("Remove ${name}?") },
        text = {
            Text(
                "The file is deleted from this device. Stories already created keep their own copy of the settings they were created with.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Remove", color = Charaly.colors.danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Keep it") }
        },
    )
}


/** Case-insensitive name filter for an installed card. */
private fun InstalledModelCard.matches(query: String): Boolean {
    if (query.isBlank()) return true
    return displayName.contains(query, ignoreCase = true) ||
        originLabel.contains(query, ignoreCase = true) ||
        stateLabel.contains(query, ignoreCase = true) ||
        detailRows.any { it.label.contains(query, ignoreCase = true) || it.value.contains(query, ignoreCase = true) }
}

/** Case-insensitive name filter for a catalog card. */
private fun CatalogModelCard.matches(query: String): Boolean {
    if (query.isBlank()) return true
    return name.contains(query, ignoreCase = true) ||
        publisher.contains(query, ignoreCase = true) ||
        parameterLabel.contains(query, ignoreCase = true) ||
        quantization.contains(query, ignoreCase = true) ||
        roleplayLabel.contains(query, ignoreCase = true) ||
        license.contains(query, ignoreCase = true)
}