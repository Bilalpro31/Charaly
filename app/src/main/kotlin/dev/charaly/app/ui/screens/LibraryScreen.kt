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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.charaly.app.ui.art.PackArtworkHero
import dev.charaly.app.ui.components.CharalyAction
import dev.charaly.app.ui.components.CharalyEmptyState
import dev.charaly.app.ui.components.CharalyIconButton
import dev.charaly.app.ui.components.CharalyQuietAction
import dev.charaly.app.ui.components.CharalySearchField
import dev.charaly.app.ui.components.CharalySectionHeader
import dev.charaly.app.ui.components.CharalySkeleton
import dev.charaly.app.ui.components.tappable
import dev.charaly.app.ui.design.Charaly
import dev.charaly.app.ui.design.CharalyAtmosphere
import dev.charaly.app.ui.design.CharalyShapes
import dev.charaly.runtime.presentation.LibraryShelf
import dev.charaly.runtime.presentation.LayoutPolicy
import dev.charaly.runtime.presentation.ShelfStory

/**
 * LIBRARY.
 *
 * ## A shelf, not a session manager
 *
 * Every story you have made, in one vertical list, each card carrying its world, its
 * current moment and who is there. Continue is the whole interaction.
 *
 * The previous version of this screen offered rename, branch and delete on every row plus
 * a detail screen with a full transcript and a chapter list - which made a shelf of living
 * stories read as a database of records. Those actions still exist, behind one explicit
 * "Details" tap per story, because removing a story is a decision and not a swipe.
 */
@Composable
fun LibraryScreen(
    shelf: LibraryShelf,
    loading: Boolean,
    policy: LayoutPolicy,
    onQueryChange: (String) -> Unit,
    onContinue: (String) -> Unit,
    onOpenStory: (String) -> Unit,
    onOpenDetails: (String) -> Unit,
    onOpenWorlds: () -> Unit,
) {
    val gutter = if (policy.cardColumns > 1) Charaly.space.gutterWide else Charaly.space.gutter

    if (loading) {
        LibrarySkeleton(gutter)
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = gutter,
            end = gutter,
            bottom = Charaly.space.section,
        ),
        verticalArrangement = Arrangement.spacedBy(Charaly.space.md),
    ) {
        item(key = "title") {
            Column {
                Text(
                    text = Loc.t("library.title"),
                    style = MaterialTheme.typography.displaySmall,
                    color = Charaly.ink.primary,
                    modifier = Modifier.semantics { heading() },
                )
                if (shelf.totalCount > 0) {
                    Text(
                        text = if (shelf.totalCount == 1) {
                            "1 story on this device"
                        } else {
                            "${shelf.totalCount} stories on this device"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Charaly.ink.muted,
                    )
                }
            }
        }

        if (shelf.totalCount > 3) {
            item(key = "search") {
                CharalySearchField(
                    value = shelf.query,
                    onValueChange = onQueryChange,
                    placeholder = "Search your stories",
                )
            }
        }

        shelf.emptyState?.let { state ->
            item(key = "empty") {
                CharalyEmptyState(
                    state = state,
                    action = {
                        if (state.actionLabel.contains("world", ignoreCase = true)) {
                            CharalyAction(label = state.actionLabel, onClick = onOpenWorlds)
                        }
                    },
                )
            }
        }

        items(
            count = shelf.stories.size,
            key = { index -> shelf.stories[index].id },
        ) { index ->
            val story = shelf.stories[index]
            ShelfRow(
                story = story,
                isNewest = index == 0,
                onContinue = { onContinue(story.id) },
                onOpenDetails = { onOpenDetails(story.id) },
            )
        }
    }
}

/**
 * One story.
 *
 * ## The newest one is the obvious one
 *
 * The first card gets the Continue action; the rest get a play affordance in the corner and
 * a Details link. That asymmetry is the whole library's interaction model: almost always
 * you are resuming exactly one story, and the screen should make that one tap away without
 * making the other nine look like it forgot to offer one.
 */
@Composable
private fun ShelfRow(
    story: ShelfStory,
    isNewest: Boolean,
    onContinue: () -> Unit,
    onOpenDetails: () -> Unit,
) {
    val atmosphere = CharalyAtmosphere.of(story.theme)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CharalyShapes.soft)
            .background(Charaly.surface.raised)
            .tappable { if (isNewest) onContinue() else onOpenDetails() }
            .padding(Charaly.space.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = story.worldName.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = atmosphere.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = story.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Charaly.ink.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isNewest) {
                CharalyIconButton(
                    icon = Icons.Filled.PlayArrow,
                    contentDescription = Loc.t("a11y.continue_story", story.title),
                    onClick = onContinue,
                    container = Charaly.atmosphere.accent.copy(alpha = 0.16f),
                    tint = atmosphere.accent,
                )
            }
        }

        // The same sentence Home shows, from the same presenter. A shelf that described a
        // story differently from the lobby would be two truths about one thing.
        Text(
            text = story.moment,
            style = MaterialTheme.typography.bodyMedium,
            color = Charaly.ink.secondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Charaly.space.xs),
        )

        Row(
            modifier = Modifier.padding(top = Charaly.space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = listOfNotNull(
                    story.contextLine.takeIf { it.isNotBlank() },
                    story.lastPlayedLabel.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = Charaly.ink.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            CharalyQuietAction(label = Loc.t("library.details"), onClick = onOpenDetails)
        }
    }
}

/**
 * One story, in full.
 *
 * The transcript as prose, with the world moments interleaved. There is no composer here:
 * this is a story you are *reading*, not one you are playing, and offering a text field
 * would blur that distinction.
 */
@Composable
fun StoryRecordScreen(
    title: String,
    moment: String,
    contextLine: String,
    beats: List<dev.charaly.runtime.presentation.Beat>,
    chapters: List<dev.charaly.runtime.presentation.ChapterCard>,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onDelete: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Charaly.space.gutter,
            end = Charaly.space.gutter,
            top = Charaly.space.xxl,
            bottom = Charaly.space.section,
        ),
        verticalArrangement = Arrangement.spacedBy(Charaly.space.md),
    ) {
        item(key = "back") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CharalyIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = Loc.t("a11y.back_to_library"),
                    onClick = onBack,
                )
                Spacer(Modifier.height(Charaly.space.xs))
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Charaly.ink.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
            }
        }

        item(key = "summary") {
            Column {
                Text(
                    text = "“$moment”",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Charaly.ink.secondary,
                )
                if (contextLine.isNotBlank()) {
                    Text(
                        text = contextLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = Charaly.ink.muted,
                    )
                }
            }
        }

        item(key = "actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(Charaly.space.xs)) {
                CharalyAction(
                    label = Loc.t("action.continue"),
                    onClick = onContinue,
                    icon = Icons.Filled.PlayArrow,
                )
                CharalyQuietAction(
                    label = Loc.t("library.delete"),
                    onClick = onDelete,
                    contentColor = Charaly.ink.muted,
                )
            }
        }

        if (chapters.isNotEmpty()) {
            item(key = "chapters-header") {
                CharalySectionHeader(
                    title = Loc.t("library.chapters"),
                    micro = true,
                    caption = if (chapters.size == 1) "1 chapter" else "${chapters.size} chapters",
                )
            }
            chapters.forEach { chapter ->
                item(key = "chapter-${chapter.index}") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CharalyShapes.soft)
                            .background(if (chapter.isCurrent) Charaly.surface.raised else Charaly.surface.base)
                            .padding(Charaly.space.md),
                    ) {
                        Text(
                            text = chapter.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = Charaly.ink.primary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (chapter.summary.isNotBlank()) {
                            Text(
                                text = chapter.summary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Charaly.ink.secondary,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = listOfNotNull(
                                chapter.locationName.takeIf { it.isNotBlank() },
                                chapter.timeLabel.takeIf { it.isNotBlank() },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = Charaly.ink.muted,
                        )
                    }
                }
            }
        }

        if (beats.isNotEmpty()) {
            item(key = "transcript-header") {
                CharalySectionHeader(title = Loc.t("library.what_happened"), micro = true)
            }
            beats.forEach { beat ->
                item(key = "beat-${beat.id}") {
                    val text = beat.dialogue.ifBlank { beat.narration }.ifBlank { beat.action }
                    if (text.isNotBlank()) {
                        Text(
                            text = if (beat.dialogue.isNotBlank()) "“$text”" else text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (beat.role == dev.charaly.runtime.presentation.BeatRole.PLAYER) {
                                Charaly.ink.muted
                            } else {
                                Charaly.ink.prose
                            },
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The artwork a story card draws, exposed so a preview can build one. */
@Composable
private fun LibrarySkeleton(gutter: Dp) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = gutter, vertical = Charaly.space.xl),
        verticalArrangement = Arrangement.spacedBy(Charaly.space.md),
    ) {
        CharalySkeleton(Modifier.fillMaxWidth(0.45f), height = 40.dp)
        repeat(3) {
            CharalySkeleton(
                Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                shape = CharalyShapes.soft,
            )
        }
    }
}