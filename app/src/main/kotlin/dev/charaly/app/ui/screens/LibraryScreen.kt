package dev.charaly.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.charaly.app.R
import dev.charaly.app.ui.art.PackArtworkHero
import dev.charaly.app.ui.components.CharalyEmptyState
import dev.charaly.app.ui.components.CharalyPill
import dev.charaly.app.ui.components.CharalySectionHeader
import dev.charaly.app.ui.components.CharalySkeleton
import dev.charaly.app.ui.components.tappable
import dev.charaly.app.ui.design.Charaly
import dev.charaly.app.ui.design.CharalyAtmosphere
import dev.charaly.app.ui.design.CharalyShapes
import dev.charaly.runtime.presentation.LayoutPolicy
import dev.charaly.runtime.presentation.LibraryShelf
import dev.charaly.runtime.presentation.Loc
import dev.charaly.runtime.presentation.ShelfStory
import dev.charaly.runtime.presentation.WorldFeedItem
import dev.charaly.runtime.presentation.WorldsFeedSnapshot

/**
 * LIBRARY.
 *
 * ## The V5 shape
 *
 * Four sections, each answering one question:
 *
 * ```
 *   Continue playing     where was I?
 *   Previously on…       what happened before that?
 *   Story packs          what worlds are here?
 *   People you've met    who is in them?
 * ```
 *
 * The poster grid is two columns because a shelf of stories is a *set you choose between*,
 * and choice reads side-by-side. Every poster is a 3:4.1 card with its play affordance in
 * the corner - the same shape the sessions list and the pack showcase use, so a story looks
 * like itself everywhere it appears.
 *
 * ## What is deliberately absent
 *
 * No rename, no branch, no delete on any card. Destructive actions still exist, behind one
 * explicit "Details" tap per story on the Sessions screen, because removing a story is a
 * decision and not a swipe.
 */
@Composable
fun LibraryScreen(
    shelf: LibraryShelf,
    worlds: WorldsFeedSnapshot,
    loading: Boolean,
    policy: LayoutPolicy,
    onQueryChange: (String) -> Unit,
    onContinue: (String) -> Unit,
    onOpenStory: (String) -> Unit,
    onOpenDetails: (String) -> Unit,
    onOpenWorld: (String) -> Unit,
    onOpenSessions: () -> Unit,
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
            top = Charaly.space.xxl,
            bottom = Charaly.space.section,
        ),
        verticalArrangement = Arrangement.spacedBy(Charaly.space.lg),
    ) {
        item(key = "title") {
            Text(
                text = Loc.t("library.title"),
                style = MaterialTheme.typography.displaySmall,
                color = Charaly.ink.primary,
                modifier = Modifier.semantics { heading() },
            )
        }

        // ---- Continue playing -------------------------------------------------
        val live = shelf.stories.filter { it.isLive }
        if (live.isNotEmpty()) {
            item(key = "continue-header") {
                CharalySectionHeader(
                    title = stringResource(R.string.library_continue_playing),
                    micro = true,
                )
            }
            storyPosterGrid(live) { story ->
                StoryPoster(
                    story = story,
                    onClick = { onOpenStory(story.id) },
                )
            }
        }

        // ---- Previously on… ---------------------------------------------------
        val earlier = shelf.stories.filterNot { it.isLive }
        if (earlier.isNotEmpty()) {
            item(key = "previously-header") {
                CharalySectionHeader(
                    title = stringResource(R.string.library_previously_on),
                    micro = true,
                )
            }
            storyPosterGrid(earlier) { story ->
                StoryPoster(
                    story = story,
                    onClick = { onOpenStory(story.id) },
                )
            }
        }

        // ---- Story packs ------------------------------------------------------
        if (worlds.worlds.isNotEmpty()) {
            item(key = "packs-header") {
                CharalySectionHeader(
                    title = stringResource(R.string.library_story_packs),
                    micro = true,
                )
            }
            packPosterGrid(worlds.worlds) { world ->
                PackPoster(
                    world = world,
                    onClick = { onOpenWorld(world.id) },
                )
            }
        }

        // ---- The honest empties -----------------------------------------------
        if (live.isEmpty() && earlier.isEmpty() && worlds.worlds.isEmpty()) {
            item(key = "empty") {
                shelf.emptyState?.let { state ->
                    CharalyEmptyState(state = state)
                }
            }
        }
    }
}

/**
 * Two columns of posters, chunked into rows so there is exactly one vertical scroll owner
 * per screen. A nested LazyVerticalGrid inside a lazy item throws at measure time.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.storyPosterGrid(
    items: List<ShelfStory>,
    item: @Composable (ShelfStory) -> Unit,
) {
    items.chunked(2).forEachIndexed { rowIndex, row ->
        item(key = "story-row-$rowIndex") {
            Row(horizontalArrangement = Arrangement.spacedBy(Charaly.space.sm)) {
                row.forEach { story ->
                    Box(Modifier.weight(1f)) { item(story) }
                }
                repeat(2 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.packPosterGrid(
    items: List<WorldFeedItem>,
    item: @Composable (WorldFeedItem) -> Unit,
) {
    items.chunked(2).forEachIndexed { rowIndex, row ->
        item(key = "pack-row-$rowIndex") {
            Row(horizontalArrangement = Arrangement.spacedBy(Charaly.space.sm)) {
                row.forEach { world ->
                    Box(Modifier.weight(1f)) { item(world) }
                }
                repeat(2 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * One story as a poster.
 *
 * V5's shape: a 3:4.1 cover with a scrim, the play affordance bottom-left, the story's
 * name under it, and its world as a small muted line. The same sentence Home shows, from
 * the same presenter - a shelf that described a story differently from the lobby would be
 * two truths about one thing.
 */
@Composable
private fun StoryPoster(
    story: ShelfStory,
    onClick: () -> Unit,
) {
    val atmosphere = CharalyAtmosphere.of(story.theme)

    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4.1f)
                .clip(CharalyShapes.soft)
                .tappable(onClick),
        ) {
            PackArtworkHero(
                artwork = story.artwork,
                atmosphere = atmosphere,
                modifier = Modifier.fillMaxSize(),
                strength = 0.8f,
            )
            // The bottom scrim, so the play affordance reads over any artwork.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                androidx.compose.ui.graphics.Color.Transparent,
                                androidx.compose.ui.graphics.Color(0xC0000000),
                            ),
                            startY = 400f,
                        ),
                    ),
            )
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(Charaly.space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    text = story.contextLine.ifBlank { story.lastPlayedLabel },
                    style = MaterialTheme.typography.labelMedium,
                    color = androidx.compose.ui.graphics.Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = story.title,
            style = MaterialTheme.typography.titleMedium,
            color = Charaly.ink.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Charaly.space.xs),
        )
        Text(
            text = story.worldName,
            style = MaterialTheme.typography.bodySmall,
            color = Charaly.ink.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One pack as a poster: cover, episode count affordance, name, genre. */
@Composable
private fun PackPoster(
    world: WorldFeedItem,
    onClick: () -> Unit,
) {
    val atmosphere = CharalyAtmosphere.of(world.theme)

    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4.1f)
                .clip(CharalyShapes.soft)
                .tappable(onClick),
        ) {
            PackArtworkHero(
                artwork = world.artwork,
                atmosphere = atmosphere,
                modifier = Modifier.fillMaxSize(),
                strength = 0.8f,
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                androidx.compose.ui.graphics.Color.Transparent,
                                androidx.compose.ui.graphics.Color(0xC0000000),
                            ),
                            startY = 400f,
                        ),
                    ),
            )
            if (world.lastPlayedLabel.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(Charaly.space.sm),
                ) {
                    CharalyPill(label = world.lastPlayedLabel)
                }
            }
        }
        Text(
            text = world.title,
            style = MaterialTheme.typography.titleMedium,
            color = Charaly.ink.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Charaly.space.xs),
        )
        if (world.genres.isNotEmpty()) {
            Text(
                text = world.genres.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = Charaly.ink.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Whether a story still reads as alive - the same rule the Sessions screen uses. */
private val ShelfStory.isLive: Boolean
    get() = lastPlayedLabel == "Şimdi" ||
        lastPlayedLabel.endsWith("dk önce") ||
        lastPlayedLabel.endsWith("sa önce") ||
        lastPlayedLabel == "Dün"

/** Skeletons shaped like the real screen, so nothing moves when the data lands. */
@Composable
private fun LibrarySkeleton(gutter: androidx.compose.ui.unit.Dp) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = gutter, vertical = Charaly.space.xl),
        verticalArrangement = Arrangement.spacedBy(Charaly.space.md),
    ) {
        CharalySkeleton(Modifier.fillMaxWidth(0.45f), height = 40.dp)
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(Charaly.space.sm)) {
                repeat(2) {
                    CharalySkeleton(
                        Modifier
                            .weight(1f)
                            .aspectRatio(3f / 4.1f),
                        shape = CharalyShapes.soft,
                    )
                }
            }
        }
    }
}
