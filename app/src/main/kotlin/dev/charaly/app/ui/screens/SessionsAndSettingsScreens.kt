package dev.charaly.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.charaly.app.ui.CharalyUiState
import dev.charaly.app.ui.art.ArtworkHero
import dev.charaly.app.ui.components.BodyProse
import dev.charaly.app.ui.components.CastAvatars
import dev.charaly.app.ui.components.CharalyCard
import dev.charaly.app.ui.components.CharalyFilterChip
import dev.charaly.app.ui.components.CharalyGhostButton
import dev.charaly.app.ui.components.CharalyIconButton
import dev.charaly.app.ui.components.CharalyPrimaryButton
import dev.charaly.app.ui.components.CharalyTextButton
import dev.charaly.app.ui.components.DetailRow
import dev.charaly.app.ui.components.EmptyStateView
import dev.charaly.app.ui.components.Eyebrow
import dev.charaly.app.ui.components.HairLine
import dev.charaly.app.ui.components.PackArtGlyph
import dev.charaly.app.ui.components.StatusDot
import dev.charaly.app.ui.components.VerticalSpacer
import dev.charaly.app.ui.theme.AccentTokens
import dev.charaly.app.ui.theme.Charaly
import dev.charaly.app.ui.theme.CharalyColors
import dev.charaly.app.ui.theme.CharalyTypography
import dev.charaly.app.ui.theme.LocalStoryAccent
import dev.charaly.app.ui.theme.readableOn
import dev.charaly.app.ui.theme.toColor
import dev.charaly.runtime.domain.PackArtwork
import dev.charaly.runtime.presentation.ChapterCard
import dev.charaly.runtime.presentation.EmptyState
import dev.charaly.runtime.presentation.SessionCard
import dev.charaly.runtime.presentation.SessionSortOrder
import dev.charaly.runtime.presentation.SessionsSnapshot
import dev.charaly.runtime.presentation.StoryInfoSnapshot
import dev.charaly.runtime.presentation.StoryLine
import kotlinx.coroutines.launch

/**
 * Sessions, the session summary, Settings and onboarding.
 *
 * These four screens share a job: they show what the stories are rather than
 * running one. Sessions is a history, the session detail is a summary you can act
 * on, Settings is mostly switches, and onboarding is three sentences.
 *
 * The rules they all follow:
 *  - the shared component library draws everything, so a card here looks like a
 *    card on the story screen;
 *  - the story's own accent is provided locally, so a story keeps its colour on
 *    its summary screen without Charaly's surfaces changing;
 *  - nothing destructive happens without a question first.
 */

/** How many lines of the story the summary screen will show. A taste, not a transcript. */
private const val PREVIEW_LINE_COUNT = 3

// ---------------------------------------------------------------------------
// Sessions
// ---------------------------------------------------------------------------

/**
 * The history of every story the user has started.
 *
 * A card is a bookmark, not a log: what the story is called, where it stopped,
 * who is in it, and one button to pick it up again. Everything else is one tap
 * away on the detail screen.
 */
@Composable
fun SessionsScreen(
    snapshot: SessionsSnapshot,
    onQueryChange: (String) -> Unit,
    onSortChange: (SessionSortOrder) -> Unit,
    onOpenSession: (String) -> Unit,
    onContinue: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onBranch: (String) -> Unit,
    onExplorePacks: () -> Unit,
) {
    val spacing = Charaly.tokens.spacing
    val sessions = snapshot.sessions
    val subtitle = if (snapshot.totalCount == 1) {
        "1 story"
    } else {
        "${snapshot.totalCount} stories"
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(horizontal = spacing.gutter, vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        item(key = "sessions-header") {
            Column(Modifier.padding(bottom = spacing.xxs)) {
                Text(
                    text = "Sessions",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item(key = "sessions-search") {
            OutlinedTextField(
                value = snapshot.query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = Charaly.tokens.radii.shapeSm,
                label = { Text("Search stories") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        modifier = Modifier.size(Charaly.tokens.icons.medium),
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Charaly.accent.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                ),
            )
        }

        item(key = "sessions-sort") {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                items(SessionSortOrder.entries.size) { index ->
                    val order = SessionSortOrder.entries[index]
                    CharalyFilterChip(
                        label = order.label,
                        selected = order == snapshot.sortOrder,
                        onClick = { onSortChange(order) },
                    )
                }
            }
        }

        if (sessions.isEmpty()) {
            item(key = "sessions-empty") {
                EmptyStateView(
                    state = snapshot.emptyState ?: EmptyState(
                        title = "No stories yet.",
                        body = "Start a story from a Story Pack and it will appear here, ready to pick up.",
                        actionLabel = "Explore Story Packs",
                        artSeed = "charaly-empty-sessions",
                    ),
                    action = {
                        CharalyPrimaryButton(
                            label = "Explore Story Packs",
                            onClick = onExplorePacks,
                        )
                    },
                )
            }
        } else {
            items(count = sessions.size, key = { index -> sessions[index].id }) { index ->
                val card = sessions[index]
                CompositionLocalProvider(LocalStoryAccent provides AccentTokens.of(card.theme)) {
                    SessionCardItem(
                        card = card,
                        onOpen = { onOpenSession(card.id) },
                        onContinue = { onContinue(card.id) },
                        onRename = { name -> onRename(card.id, name) },
                        onDelete = { onDelete(card.id) },
                        onBranch = { onBranch(card.id) },
                    )
                }
            }
        }
    }
}

/**
 * One story in the list.
 *
 * The whole card opens the summary; "Continue" goes straight back into the story,
 * which is the thing people actually want ninety percent of the time. The three
 * dangerous verbs hide behind one button so the card stays calm.
 */
@Composable
private fun SessionCardItem(
    card: SessionCard,
    onOpen: () -> Unit,
    onContinue: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onBranch: () -> Unit,
) {
    val spacing = Charaly.tokens.spacing
    var actionsOpen by remember(card.id) { mutableStateOf(false) }
    var renameOpen by remember(card.id) { mutableStateOf(false) }
    var deleteOpen by remember(card.id) { mutableStateOf(false) }

    CharalyCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpen,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = card.packTitle.ifBlank { "Your stories" },
                        style = MaterialTheme.typography.labelMedium,
                        color = Charaly.accent.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = card.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                CharalyIconButton(
                    icon = Icons.Filled.MoreVert,
                    contentDescription = "More actions for ${card.title}",
                    onClick = { actionsOpen = !actionsOpen },
                )
            }

            Text(
                text = "${card.locationName}  ·  ${card.timeLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = spacing.xs),
            )

            if (card.chapterTitle.isNotBlank()) {
                Text(
                    text = card.chapterTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = Charaly.accent.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = spacing.xs),
                )
            }

            if (card.castNames.isNotEmpty()) {
                CastAvatars(
                    names = card.castNames,
                    accents = card.castAccents,
                    modifier = Modifier.padding(top = spacing.md),
                )
            }

            val captions = listOf(
                playedCaption(card.lastPlayedLabel),
                countLabel(card.turnCount, "turn", "turns"),
                countLabel(card.memoryCount, "memory", "memories"),
            )
            Text(
                text = captions.joinToString("  ·  "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = spacing.sm),
            )

            if (card.isBranch) {
                Text(
                    text = "Branched from an earlier story",
                    style = MaterialTheme.typography.labelSmall,
                    color = Charaly.accent.secondary,
                    modifier = Modifier.padding(top = spacing.xxs),
                )
            }

            HairLine(modifier = Modifier.padding(top = spacing.md))

            CharalyPrimaryButton(
                label = "Continue",
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = spacing.md),
            )

            if (actionsOpen) {
                HairLine(modifier = Modifier.padding(top = spacing.md))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = spacing.xxs),
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CharalyTextButton(
                        label = "Rename",
                        icon = Icons.Filled.Edit,
                        onClick = {
                            actionsOpen = false
                            renameOpen = true
                        },
                    )
                    CharalyTextButton(
                        label = "Branch",
                        icon = Icons.AutoMirrored.Filled.CallSplit,
                        onClick = {
                            actionsOpen = false
                            onBranch()
                        },
                    )
                    CharalyTextButton(
                        label = "Delete",
                        icon = Icons.Filled.DeleteOutline,
                        contentColor = Charaly.colors.danger,
                        onClick = {
                            actionsOpen = false
                            deleteOpen = true
                        },
                    )
                }
            }
        }
    }

    if (renameOpen) {
        StoryRenameDialog(
            title = "Rename story",
            initialValue = card.title,
            onDismiss = { renameOpen = false },
            onConfirm = { name ->
                renameOpen = false
                onRename(name)
            },
        )
    }

    if (deleteOpen) {
        ConfirmDeleteDialog(
            storyTitle = card.title,
            onDismiss = { deleteOpen = false },
            onConfirm = {
                deleteOpen = false
                onDelete()
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Session detail
// ---------------------------------------------------------------------------

/**
 * One story, summarised.
 *
 * Deliberately not a transcript reader. It answers "where am I, who is here, what
 * will this story cost me and can I still get into it" and then gets out of the
 * way. A story with many thousands of lines stays a summary screen.
 */
@Composable
fun SessionDetailScreen(
    snapshot: SessionsSnapshot,
    instanceId: String,
    info: StoryInfoSnapshot?,
    lines: List<StoryLine>,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onRename: (String) -> Unit,
    onBranch: () -> Unit,
    onDelete: () -> Unit,
    onExplorePacks: () -> Unit,
) {
    val spacing = Charaly.tokens.spacing
    val card = snapshot.sessions.firstOrNull { it.id == instanceId }

    // The story may have been deleted from another surface, or the route may
    // have been restored after a reinstall. Say so plainly instead of crashing.
    if (card == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.gutter, vertical = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CharalyIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    onClick = onBack,
                )
            }
            HairLine()
            EmptyStateView(
                state = EmptyState(
                    title = "This story is no longer here.",
                    body = "It may have been deleted. Everything else in your library is untouched.",
                    actionLabel = "Explore Story Packs",
                    artSeed = "charaly-empty-session-detail",
                ),
                modifier = Modifier.padding(top = spacing.xl),
                action = {
                    CharalyPrimaryButton(
                        label = "Explore Story Packs",
                        onClick = onExplorePacks,
                    )
                },
            )
        }
        return
    }

    val chapters = info?.chapters.orEmpty()
    val previewLines = lines.take(PREVIEW_LINE_COUNT)
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }

    CompositionLocalProvider(LocalStoryAccent provides AccentTokens.of(card.theme)) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(bottom = spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            item(key = "session-hero") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                ) {
                    ArtworkHero(
                        artwork = PackArtwork.generated(seed = card.packId),
                        theme = card.theme,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .padding(spacing.gutter),
                        ) {
                            Text(
                                text = card.packTitle.ifBlank { "Story" },
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.80f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = card.title,
                                style = MaterialTheme.typography.headlineMedium,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                            Text(
                                text = "${card.locationName}  ·  ${card.timeLabel}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.74f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = spacing.xxs),
                            )
                        }
                    }
                    CharalyIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        onClick = onBack,
                        tint = Color.White,
                        container = Color.Black.copy(alpha = 0.38f),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = spacing.sm, top = spacing.md),
                    )
                }
            }

            item(key = "session-continue") {
                Column(Modifier.padding(horizontal = spacing.gutter)) {
                    CharalyPrimaryButton(
                        label = "Continue story",
                        onClick = onContinue,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = spacing.md),
                    )
                    Text(
                        text = listOf(
                            playedCaption(card.lastPlayedLabel),
                            countLabel(card.turnCount, "turn", "turns"),
                            countLabel(card.memoryCount, "memory", "memories"),
                        ).joinToString("  ·  "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = spacing.sm),
                    )
                }
            }

            item(key = "session-chapters-header") {
                Column(
                    modifier = Modifier.padding(
                        start = spacing.gutter,
                        end = spacing.gutter,
                        top = spacing.gutter,
                    ),
                ) {
                    Eyebrow("Chapters")
                    Text(
                        text = "Where this story has been so far",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            if (chapters.isEmpty()) {
                item(key = "session-chapters-empty") {
                    Text(
                        text = "This story has no chapters yet. They appear here as the world moves on.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Charaly.colors.narration,
                        modifier = Modifier.padding(horizontal = spacing.gutter, vertical = spacing.xs),
                    )
                }
            } else {
                items(count = chapters.size, key = { index -> "session-chapter-${chapters[index].index}" }) { index ->
                    ChapterItem(
                        chapter = chapters[index],
                        modifier = Modifier.padding(horizontal = spacing.gutter),
                    )
                }
            }

            item(key = "session-story-header") {
                Column(Modifier.padding(horizontal = spacing.gutter, vertical = spacing.sm)) {
                    Eyebrow("Story")
                }
            }

            item(key = "session-story-info") {
                CharalyCard(
                    modifier = Modifier.padding(horizontal = spacing.gutter),
                    contentPadding = PaddingValues(
                        horizontal = spacing.md,
                        vertical = spacing.xs,
                    ),
                ) {
                    Column {
                        if (info == null) {
                            Text(
                                text = "The details of this story are still being read from this device.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Charaly.colors.narration,
                                modifier = Modifier.padding(vertical = spacing.sm),
                            )
                        } else {
                            if (info.isBranch) {
                                Text(
                                    text = "Branched from ${info.branchFrom.ifBlank { "an earlier story" }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Charaly.accent.secondary,
                                    modifier = Modifier.padding(
                                        top = spacing.xxs,
                                        bottom = spacing.xs,
                                    ),
                                )
                            }
                            DetailRow(
                                label = "World",
                                value = info.packTitle.ifBlank { card.packTitle }.ifBlank { "A world of your own" },
                            )
                            DetailRow(label = "Your role", value = roleLabel(info))
                            DetailRow(label = "Opening", value = info.scenarioTitle.ifBlank { "Not named yet" })
                            DetailRow(label = "Story clock", value = info.storyClock)
                            DetailRow(label = "Created", value = info.createdLabel)
                            DetailRow(label = "Last played", value = info.lastPlayedLabel)
                            DetailRow(label = "Model", value = modelBindingLabel(info))
                        }
                    }
                }
            }

            item(key = "session-preview-header") {
                Column(
                    modifier = Modifier.padding(
                        start = spacing.gutter,
                        end = spacing.gutter,
                        top = spacing.gutter,
                    ),
                ) {
                    Eyebrow("Preview")
                    Text(
                        text = "The opening of the story, read only",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            item(key = "session-preview") {
                CharalyCard(modifier = Modifier.padding(horizontal = spacing.gutter)) {
                    Column {
                        if (previewLines.isEmpty()) {
                            Text(
                                text = "The first words of this story will appear here once it begins.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Charaly.colors.narration,
                                modifier = Modifier.padding(vertical = spacing.xxs),
                            )
                        } else {
                            previewLines.forEachIndexed { index, line ->
                                Column {
                                    Text(
                                        text = line.speakerName.ifBlank { "Narration" },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = line.speakerAccent.toColor(),
                                    )
                                    Text(
                                        text = line.rawText,
                                        style = CharalyTypography.narration,
                                        color = Charaly.colors.narration,
                                        maxLines = 4,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (index != previewLines.lastIndex) {
                                    HairLine(modifier = Modifier.padding(vertical = spacing.sm))
                                }
                            }
                        }
                    }
                }
            }

            item(key = "session-actions") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.gutter, vertical = spacing.gutter),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    HairLine()
                    Text(
                        text = "This story",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CharalyGhostButton(
                        label = "Rename",
                        icon = Icons.Filled.Edit,
                        onClick = { renameOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    CharalyGhostButton(
                        label = "Branch",
                        icon = Icons.AutoMirrored.Filled.CallSplit,
                        onClick = { onBranch() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    CharalyGhostButton(
                        label = "Delete",
                        icon = Icons.Filled.DeleteOutline,
                        onClick = { deleteOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                        contentColor = Charaly.colors.danger,
                    )
                }
            }
        }

        if (renameOpen) {
            StoryRenameDialog(
                title = "Rename story",
                initialValue = card.title,
                onDismiss = { renameOpen = false },
                onConfirm = { name ->
                    renameOpen = false
                    onRename(name)
                },
            )
        }

        if (deleteOpen) {
            ConfirmDeleteDialog(
                storyTitle = card.title,
                onDismiss = { deleteOpen = false },
                onConfirm = {
                    deleteOpen = false
                    onDelete()
                },
            )
        }
    }
}

/** One chapter in the story's timeline. The current one carries the accent. */
@Composable
private fun ChapterItem(
    chapter: ChapterCard,
    modifier: Modifier = Modifier,
) {
    val accent = Charaly.accent.primary
    val captions = buildList {
        add(countLabel(chapter.turnCount, "turn", "turns"))
        val people = chapter.participantNames.take(3).joinToString(", ")
        if (people.isNotBlank()) {
            val extra = chapter.participantNames.size - 3
            add(if (extra > 0) "$people +$extra" else people)
        }
    }

    CharalyCard(
        modifier = modifier.fillMaxWidth(),
        container = if (chapter.isCurrent) {
            accent.copy(alpha = 0.10f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        border = if (chapter.isCurrent) accent.copy(alpha = 0.55f) else null,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(Charaly.tokens.radii.shapeXs)
                        .background(
                            if (chapter.isCurrent) {
                                accent.copy(alpha = 0.22f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = chapter.index.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (chapter.isCurrent) {
                            accent
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Spacer(Modifier.width(Charaly.tokens.spacing.sm))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = chapter.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = listOf(chapter.locationName, chapter.timeLabel)
                            .filter { it.isNotBlank() }
                            .joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (chapter.isCurrent) {
                    Spacer(Modifier.width(Charaly.tokens.spacing.xs))
                    StatusDot(label = "Now", color = accent)
                }
            }

            if (chapter.summary.isNotBlank()) {
                Text(
                    text = chapter.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Charaly.colors.narration,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Charaly.tokens.spacing.sm),
                )
            }

            Text(
                text = captions.joinToString("  ·  "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Charaly.tokens.spacing.sm),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Shared dialogs
// ---------------------------------------------------------------------------

/** Asks for a new name. Blank input keeps the confirm button disabled. */
@Composable
private fun StoryRenameDialog(
    title: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = Charaly.tokens.radii.shapeSm,
                label = { Text("Story name") },
            )
        },
        confirmButton = {
            CharalyPrimaryButton(
                label = "Save",
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim()) },
            )
        },
        dismissButton = {
            CharalyTextButton(label = "Cancel", onClick = onDismiss)
        },
    )
}

/** Deleting a story removes its memories too, so the question is explicit. */
@Composable
private fun ConfirmDeleteDialog(
    storyTitle: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete this story?") },
        text = {
            Text(
                "“$storyTitle” and everything it remembers will be removed from this " +
                    "device. This cannot be undone.",
            )
        },
        confirmButton = {
            CharalyPrimaryButton(
                label = "Delete",
                onClick = onConfirm,
                container = Charaly.colors.danger,
                contentColor = readableOn(Charaly.colors.danger),
            )
        },
        dismissButton = {
            CharalyTextButton(label = "Keep story", onClick = onDismiss)
        },
    )
}

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

/**
 * Settings.
 *
 * Five cards, each with one job. Nothing on this screen is clever: switches for
 * preferences, a link to the model library, and a short factual paragraph about
 * privacy. There is no raw state, no JSON and no account anywhere in here.
 */
@Composable
fun SettingsScreen(
    state: CharalyUiState,
    onBack: () -> Unit,
    onSetDarkTheme: (Boolean) -> Unit,
    onSetReduceMotion: (Boolean) -> Unit,
    onSetDeveloperMode: (Boolean) -> Unit,
    onOpenDeveloper: () -> Unit,
    onOpenModels: () -> Unit,
) {
    val spacing = Charaly.tokens.spacing
    val installed = state.installedModels
    val active = installed.firstOrNull { it.id == state.activeModelId } ?: installed.firstOrNull()
    val modelSummary = if (active != null) {
        "${countLabel(installed.size, "model", "models")} installed  ·  Active: ${active.displayName}"
    } else {
        "No model installed yet. Import a GGUF and every story can speak."
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.gutter, vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CharalyIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                onClick = onBack,
            )
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = spacing.xs),
            )
        }
        HairLine()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = spacing.gutter, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item(key = "settings-appearance") {
                SettingsSectionCard(title = "Appearance") {
                    SettingsSwitchRow(
                        title = "Dark theme",
                        caption = "Charaly is designed to be read at night.",
                        checked = state.darkTheme,
                        contentDescription = "Dark theme",
                        onCheckedChange = onSetDarkTheme,
                    )
                    HairLine()
                    SettingsSwitchRow(
                        title = "Reduce motion",
                        // Accurate, not aspirational: this shortens the transitions to
                        // near-instant. Removing them entirely is the system setting,
                        // and a caption promising more than the switch delivers is the
                        // same class of lie as a non-functional control.
                        caption = "Shortens screen transitions across the app. To remove " +
                            "them entirely, use the system Remove animations setting.",
                        checked = state.reduceMotion,
                        contentDescription = "Reduce motion",
                        onCheckedChange = onSetReduceMotion,
                    )
                }
            }

            item(key = "settings-models") {
                SettingsSectionCard(title = "Models") {
                    SettingsNavRow(
                        title = "Model library",
                        caption = modelSummary,
                        contentDescription = "Open the model library. $modelSummary",
                        onClick = onOpenModels,
                    )
                }
            }

            item(key = "settings-privacy") {
                SettingsSectionCard(title = "Privacy") {
                    BodyProse(
                        text = "Charaly declares no internet permission, so the app " +
                            "cannot open a connection to a server even by accident. " +
                            "There is no telemetry, no analytics, no account and no " +
                            "sign-in. Your stories, memories, worlds and models are " +
                            "files in this app's own storage, and they are removed " +
                            "when you uninstall it.",
                        modifier = Modifier.padding(vertical = spacing.xs),
                    )
                }
            }

            item(key = "settings-developer") {
                SettingsSectionCard(title = "Developer") {
                    SettingsSwitchRow(
                        title = "Developer mode",
                        caption = "Reveals world state, prompt output and generation timings.",
                        checked = state.developerMode,
                        contentDescription = "Developer mode",
                        onCheckedChange = onSetDeveloperMode,
                    )
                    if (state.developerMode) {
                        CharalyTextButton(
                            label = "Open developer panel",
                            icon = Icons.AutoMirrored.Filled.ArrowForward,
                            onClick = onOpenDeveloper,
                            modifier = Modifier.padding(top = spacing.xxs),
                        )
                    }
                }
            }

            item(key = "settings-about") {
                SettingsSectionCard(title = "About") {
                    DetailRow(label = "App", value = "Charaly")
                    DetailRow(label = "Version", value = "Charaly 0.4.0")
                    BodyProse(
                        text = "The story runtime is deterministic: the same world state " +
                            "and the same words always produce the same scene, so a " +
                            "story can be picked up again exactly where it stopped.",
                        modifier = Modifier.padding(
                            top = spacing.xs,
                            bottom = spacing.xs,
                        ),
                    )
                }
            }
        }
    }
}

/** A titled card. The only grouping element Settings needs. */
@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    CharalyCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(
                    top = Charaly.tokens.spacing.xxs,
                    bottom = Charaly.tokens.spacing.xs,
                ),
            )
            content()
        }
    }
}

/**
 * A preference row.
 *
 * The whole row is the switch target, which is what a thumb-sized control on a
 * phone should be, and the row carries the description so the switch itself does
 * not need a label to be announced twice.
 */
@Composable
private fun SettingsSwitchRow(
    title: String,
    caption: String,
    checked: Boolean,
    contentDescription: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Charaly.tokens.radii.shapeSm)
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .heightIn(min = 48.dp)
            .padding(vertical = Charaly.tokens.spacing.sm)
            .semantics { this.contentDescription = contentDescription },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (caption.isNotBlank()) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.width(Charaly.tokens.spacing.sm))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A row that goes somewhere. The chevron is decoration, so it has no label. */
@Composable
private fun SettingsNavRow(
    title: String,
    caption: String,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Charaly.tokens.radii.shapeSm)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(vertical = Charaly.tokens.spacing.sm)
            .semantics { this.contentDescription = contentDescription },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (caption.isNotBlank()) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.width(Charaly.tokens.spacing.sm))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Charaly.tokens.icons.large),
        )
    }
}

// ---------------------------------------------------------------------------
// Onboarding
// ---------------------------------------------------------------------------

/** One onboarding page. The glyph is generated, so nothing is downloaded. */
private data class OnboardingPage(
    val seed: String,
    val accent: Color,
    val title: String,
    val body: String,
)

/**
 * Three sentences about what this app is, and nothing else.
 *
 * The point of this screen is that it makes no promise Charaly cannot keep on a
 * device with no network: your stories stay here, a Story Pack is a world rather
 * than a chat, and the model runs locally.
 */
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
) {
    val spacing = Charaly.tokens.spacing
    val pages = remember {
        listOf(
            OnboardingPage(
                seed = "charaly-onboarding-keeps-stories",
                accent = CharalyColors.BrandViolet,
                title = "Your stories stay with you.",
                body = "Charaly is local-first. Your stories, memories and worlds are " +
                    "files on this device and never leave it.",
            ),
            OnboardingPage(
                seed = "charaly-onboarding-builds-worlds",
                accent = CharalyColors.BrandRose,
                title = "Build worlds, not just chats.",
                body = "A Story Pack holds characters, places, events and lore, so a " +
                    "story has a world that remembers what happened in it.",
            ),
            OnboardingPage(
                seed = "charaly-onboarding-local-model",
                accent = CharalyColors.BrandAmber,
                title = "Run AI on your device.",
                body = "Replies come from a local GGUF model running through " +
                    "llama.cpp. No account, no key, no network.",
            ),
        )
    }

    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        initialPage = 0,
        initialPageOffsetFraction = 0f,
        pageCount = { pages.size },
    )
    val isLastPage = pagerState.currentPage >= pages.lastIndex

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = spacing.gutter),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Eyebrow("Charaly", modifier = Modifier.weight(1f))
            Text(
                text = "${pagerState.currentPage + 1} of ${pages.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) { page ->
            OnboardingPageContent(page = pages[page])
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = spacing.md),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            pages.indices.forEach { index ->
                PageDot(index = index, selected = index == pagerState.currentPage)
                if (index != pages.lastIndex) {
                    Spacer(Modifier.width(spacing.xs))
                }
            }
        }

        CharalyPrimaryButton(
            label = if (isLastPage) "Explore Charaly" else "Next",
            icon = if (isLastPage) Icons.AutoMirrored.Filled.ArrowForward else null,
            onClick = {
                if (isLastPage) {
                    onFinish()
                } else {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = spacing.xl),
        )
    }
}

/** One page: a generated mark, a claim, and the sentence behind it. */
@Composable
private fun OnboardingPageContent(page: OnboardingPage) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = Charaly.tokens.spacing.xl),
        verticalArrangement = Arrangement.Center,
    ) {
        PackArtGlyph(seed = page.seed, accent = page.accent)
        VerticalSpacer(height = Charaly.tokens.spacing.xl)
        Text(
            text = page.title,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        VerticalSpacer(height = Charaly.tokens.spacing.sm)
        Text(
            text = page.body,
            style = MaterialTheme.typography.bodyLarge,
            color = Charaly.colors.narration,
        )
    }
}

/** A page indicator. Not clickable, so it is described rather than labelled. */
@Composable
private fun PageDot(index: Int, selected: Boolean) {
    Box(
        modifier = Modifier
            .size(if (selected) 9.dp else 6.dp)
            .clip(CircleShape)
            .background(
                if (selected) {
                    Charaly.accent.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
            )
            .semantics {
                contentDescription = if (selected) {
                    "Page ${index + 1}, showing now"
                } else {
                    "Page ${index + 1}"
                }
            },
    )
}

// ---------------------------------------------------------------------------
// Small human helpers
// ---------------------------------------------------------------------------

/** "1 turn" / "12 turns". Never a bare number next to an icon. */
private fun countLabel(count: Int, singular: String, plural: String): String =
    if (count == 1) "1 $singular" else "$count $plural"

/** RelativeTime can legitimately say "never"; the card should not. */
private fun playedCaption(label: String): String = when {
    label.isBlank() -> "Not played yet"
    label.equals("never", ignoreCase = true) -> "Not played yet"
    else -> "Played $label"
}

/** The player's persona, as one line: name, then the role they chose. */
private fun roleLabel(info: StoryInfoSnapshot): String {
    val name = info.personaName.trim()
    val role = info.personaRole.trim()
    return when {
        name.isNotBlank() && role.isNotBlank() -> "$name — $role"
        name.isNotBlank() -> name
        role.isNotBlank() -> role
        else -> "No role chosen"
    }
}

/** Which model this story is pinned to, and with which profile. */
private fun modelBindingLabel(info: StoryInfoSnapshot): String {
    val model = info.binding.modelDisplayName.trim().ifBlank { "No model bound" }
    val profile = info.binding.profileName.trim().ifBlank { "Default profile" }
    return "$model  ·  $profile"
}