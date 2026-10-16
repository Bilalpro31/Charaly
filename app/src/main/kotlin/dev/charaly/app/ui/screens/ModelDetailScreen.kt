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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.charaly.runtime.presentation.Loc
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.charaly.app.ui.components.CharalyAction
import dev.charaly.app.ui.components.CharalyEmptyState
import dev.charaly.app.ui.components.CharalyIconButton
import dev.charaly.app.ui.components.CharalyPill
import dev.charaly.app.ui.components.CharalyQuietAction
import dev.charaly.app.ui.components.CharalySectionHeader
import dev.charaly.app.ui.components.CharalySkeleton
import dev.charaly.app.ui.components.CharalySpinner
import dev.charaly.app.ui.components.CharalySurface
import dev.charaly.app.ui.components.tappable
import dev.charaly.app.ui.design.Charaly
import dev.charaly.app.ui.design.CharalyShapes
import dev.charaly.runtime.presentation.EmptyState
import dev.charaly.runtime.presentation.ModelDetailSnapshot
import dev.charaly.runtime.presentation.ProfileCard
import dev.charaly.runtime.presentation.StatChip
import dev.charaly.runtime.presentation.StoryUse

/**
 * ONE MODEL, IN FULL.
 *
 * ## A name, a state, and then the facts that justify them
 *
 * The hub is a browsing surface; this is the page a user lands on when they want to know
 * whether this specific file is going to work on this specific phone. So the order is
 * deliberate:
 *
 * ```
 *   1  the name, its size, its state      what am I looking at?
 *   2  the verdict                         will it run here?
 *   3  the facts                           from what, and how big?
 *   4  the actions                         use / re-check / remove
 *   5  who is speaking with it             which stories depend on it
 * ```
 *
 * The verdict is second because it is the question that decides whether anyone reads the
 * rest of this screen.
 *
 * ## What this screen refuses to do
 *
 * It refuses to show a benchmark that was not measured, a "recommended" badge with nothing
 * behind it, or a settings gear. Every row in the facts list comes from the file's own GGUF
 * header or the registry's record of where it came from, which is why the list is short -
 * there are six facts and they are all true.
 *
 * Removal is here, and it is the last thing on the page, because "which stories use this"
 * is the answer to "will I lose something?" - so the consequence is shown before the control.
 */
@Composable
fun ModelDetailScreen(
    snapshot: ModelDetailSnapshot?,
    loading: Boolean,
    onBack: () -> Unit,
    onUse: (String) -> Unit,
    onDelete: (String) -> Unit,
    onVerify: (String) -> Unit,
    onOpenStory: (String) -> Unit,
) {
    if (snapshot == null) {
        MissingModel(loading = loading, onBack = onBack)
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Charaly.space.gutter,
            end = Charaly.space.gutter,
            bottom = Charaly.space.section,
        ),
        verticalArrangement = Arrangement.spacedBy(Charaly.space.md),
    ) {
        item(key = "back") {
            CharalyIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = Loc.t("a11y.back_to_models"),
                onClick = onBack,
            )
        }

        // ---- 1. what it is --------------------------------------------------
        item(key = "identity") {
            Column {
                Text(
                    text = snapshot.displayName,
                    style = MaterialTheme.typography.headlineLarge,
                    color = Charaly.ink.primary,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(Charaly.space.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(Charaly.space.xs)) {
                    // The state is a *word*, never a coloured dot: "Ready" and "Could not
                    // load" are different worlds and must not look like one.
                    CharalyPill(
                        label = snapshot.stateLabel.uppercase(),
                        selected = snapshot.stateLabel == "Hazır",
                    )
                    CharalyPill(label = snapshot.sizeLabel)
                    CharalyPill(label = snapshot.originLabel)
                }
            }
        }

        // ---- 2. the verdict -------------------------------------------------
        //
        // One sentence, plainly. A wall of diagnostics here would be the engine talking to
        // the user rather than the app speaking to them.
        item(key = "verdict") {
            VerdictSentence(snapshot = snapshot)
        }

        // ---- 3. the facts ---------------------------------------------------
        if (snapshot.detailRows.isNotEmpty()) {
            item(key = "facts-header") {
                CharalySectionHeader(title = Loc.t("detail.what_this_is"), micro = true)
            }
            item(key = "facts") { DetailRows(snapshot.detailRows) }
        }

        // ---- 4. the actions -------------------------------------------------
        item(key = "actions") {
            ModelActions(
                snapshot = snapshot,
                onUse = onUse,
                onVerify = onVerify,
                onDelete = onDelete,
            )
        }

        // ---- 5. the stories that depend on it --------------------------------
        //
        // Before the stories, the profile: the model is the voice, the profile is the
        // temperament, and a user changing one wants to know the other.
        if (snapshot.profiles.isNotEmpty() || snapshot.builtInProfiles.isNotEmpty()) {
            // Read into a local: the binding is a property on a type from another module, so
            // a smart cast on it is not available here.
            val boundProfile = snapshot.binding?.profileName.orEmpty()
            item(key = "profile-header") {
                CharalySectionHeader(
                    title = Loc.t("detail.how_it_speaks"),
                    micro = true,
                    caption = if (boundProfile.isNotBlank()) {
                        "$boundProfile olarak bağlı"
                    } else {
                        "Geçerli hikâyenize bağlı profil"
                    },
                )
            }
            val bound = (snapshot.profiles + snapshot.builtInProfiles).distinctBy { it.id }
            if (bound.isNotEmpty()) {
                item(key = "profile") { ProfileSurface(bound) }
            }
        }

        if (snapshot.usedByStories.isNotEmpty()) {
            item(key = "stories-header") {
                CharalySectionHeader(
                    title = Loc.t("detail.stories_using"),
                    micro = true,
                    caption = "${snapshot.usedByStories.size} would lose their voice if you removed it",
                )
            }
            snapshot.usedByStories.forEach { use ->
                item(key = "story-${use.storyId}") {
                    StoryUseRow(use = use, onOpen = onOpenStory)
                }
            }
        }
    }
}

/**
 * The verdict.
 *
 * ## Colour is never the message
 *
 * The level reaches the eye as a word, because `DeviceFitLevel` has four values and only
 * one of them is a problem. A red panel that a colour-blind user reads as "fine" is a lie
 * the accessibility pass is supposed to catch, and this is the panel where it would hurt
 * most.
 */
@Composable
private fun VerdictSentence(snapshot: ModelDetailSnapshot) {
    val problem = snapshot.verdictLevel != "READY"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CharalyShapes.soft)
            .background(if (problem) Charaly.surface.overlay else Charaly.surface.raised)
            .padding(Charaly.space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!problem) {
            IconCheck()
            Spacer(Modifier.width(Charaly.space.sm))
        }
        Text(
            text = snapshot.verdictMessage,
            style = MaterialTheme.typography.bodyLarge,
            color = if (problem) Charaly.ink.primary else Charaly.ink.secondary,
        )
    }
}

@Composable
private fun IconCheck() {
    androidx.compose.material3.Icon(
        imageVector = Icons.Filled.CheckCircle,
        contentDescription = null,
        tint = Charaly.ink.muted,
        modifier = Modifier.size(18.dp),
    )
}

/**
 * The facts.
 *
 * A label on the left, a value on the right, one row per fact. Deliberately not a grid of
 * statistic tiles: six tiles for six strings is the dashboard look this app does not have,
 * and a table of short rows is also what a screen reader reads most sensibly.
 */
@Composable
internal fun DetailRows(rows: List<StatChip>, modifier: Modifier = Modifier) {
    CharalySurface(modifier = modifier.fillMaxWidth(), padding = PaddingValues(0.dp)) {
        Column {
            rows.forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = Charaly.space.md, vertical = Charaly.space.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = row.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Charaly.ink.muted,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(Charaly.space.md))
                    Text(
                        text = row.value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Charaly.ink.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * The actions.
 *
 * ## One filled action, and it is always the one the reader came for
 *
 * The primary control depends on the model: an installed model that is not speaking to
 * anything offers USE THIS MODEL, and one that already is offers a quiet "In use" instead.
 * Everything unavailable is *absent* rather than greyed out, with one exception -
 * [dev.charaly.runtime.model.ModelActions.disabledReason] - which is printed underneath, so
 * a control that cannot be pressed always has its reason on screen.
 */
@Composable
private fun ModelActions(
    snapshot: ModelDetailSnapshot,
    onUse: (String) -> Unit,
    onVerify: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val actions = snapshot.actions
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(Charaly.space.sm)) {
            if (snapshot.isActive) {
                CharalyPill(label = Loc.t("models.in_use"), selected = true)
            } else if (actions.canUse) {
                CharalyAction(
                    label = Loc.t("models.use_this"),
                    onClick = { onUse(snapshot.id) },
                )
            }

            if (actions.canVerify) {
                CharalyQuietAction(label = Loc.t("models.recheck"), onClick = { onVerify(snapshot.id) })
            }
        }

        if (actions.disabledReason.isNotBlank()) {
            Text(
                text = actions.disabledReason,
                style = MaterialTheme.typography.bodySmall,
                color = Charaly.ink.muted,
                modifier = Modifier.padding(top = Charaly.space.xs),
            )
        }

        if (actions.canDelete) {
            // Remove is a quiet action at the bottom of the page, not a filled button near
            // the top: it is irreversible, and the page has just told the user which stories
            // depend on this file.
            CharalyQuietAction(
                label = Loc.t("models.remove"),
                onClick = { onDelete(snapshot.id) },
                contentColor = Charaly.ink.muted,
                modifier = Modifier.padding(top = Charaly.space.xs),
            )
        }
    }
}

/**
 * The temperament rows.
 *
 * Read-only on this screen. Choosing a profile is a story-level decision and belongs to
 * ENTER WORLD, so this list only says which one is bound - a page that offers an editing
 * control for a setting it cannot persist is how settings rot.
 */
@Composable
private fun ProfileSurface(profiles: List<ProfileCard>) {
    CharalySurface(
        modifier = Modifier.fillMaxWidth(),
        padding = PaddingValues(0.dp),
    ) {
        Column {
            profiles.forEach { profile ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Charaly.space.md, vertical = Charaly.space.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = profile.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (profile.isBound) Charaly.ink.primary else Charaly.ink.secondary,
                        )
                        Text(
                            text = profile.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = Charaly.ink.muted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (profile.isBound) {
                        CharalyPill(label = Loc.t("models.bound"), selected = true)
                    }
                }
            }
        }
    }
}

/** One story that would lose its voice if the model were removed. */
@Composable
private fun StoryUseRow(use: StoryUse, onOpen: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CharalyShapes.soft)
            .background(Charaly.surface.raised)
            .tappable { onOpen(use.storyId) }
            .padding(Charaly.space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = use.title,
                style = MaterialTheme.typography.titleMedium,
                color = Charaly.ink.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    use.packTitle.takeIf { it.isNotBlank() },
                    use.profileName.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = Charaly.ink.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(Charaly.space.sm))
        CharalyQuietAction(label = Loc.t("models.open"), onClick = { onOpen(use.storyId) })
    }
}

/**
 * The model is gone.
 *
 * Reached by deep link or by a delete performed on another screen while this page was open.
 * The empty state says which of the two it is, because "this model no longer exists" and
 * "still loading" demand opposite behaviour from the user.
 */
@Composable
private fun MissingModel(loading: Boolean, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(Charaly.space.gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CharalyIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = Loc.t("a11y.back_to_models"),
                onClick = onBack,
            )
            if (loading) {
                Spacer(Modifier.width(Charaly.space.sm))
                CharalySpinner(color = Charaly.ink.muted)
            }
        }

        if (loading) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Charaly.space.gutter),
                verticalArrangement = Arrangement.spacedBy(Charaly.space.md),
            ) {
                CharalySkeleton(Modifier.fillMaxWidth(0.7f), height = 44.dp)
                CharalySkeleton(Modifier.fillMaxWidth(), height = 72.dp)
                CharalySkeleton(Modifier.fillMaxWidth(), height = 220.dp)
            }
        } else {
            CharalyEmptyState(
                state = EmptyState(
                    title = Loc.t("detail.missing_title"),
                    body = Loc.t("detail.missing_body"),
                    actionLabel = Loc.t("a11y.back_to_models"),
                    artSeed = "charaly-empty-model-detail",
                ),
                action = { CharalyAction(label = Loc.t("a11y.back_to_models"), onClick = onBack) },
            )
        }
    }
}
