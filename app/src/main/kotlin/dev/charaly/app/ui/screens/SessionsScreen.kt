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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.charaly.app.R
import dev.charaly.app.ui.art.PackArtworkHero
import dev.charaly.app.ui.components.CharalyEmptyState
import dev.charaly.app.ui.components.CharalyIconButton
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

/**
 * SESSIONS.
 *
 * ## The V5 shape
 *
 * Two sections, one question each:
 *
 * ```
 *   Playing now    the stories with life still in them - the ones touched today
 *   Earlier        everything else, newest first
 * ```
 *
 * A row is a *session*, not a database record: a cover, a title, when it was last touched,
 * and the last line anyone said - in italics, because it is dialogue, not metadata. The
 * live dot on a playing row is V5's amber: the world is still awake in there.
 */
@Composable
fun SessionsScreen(
    shelf: LibraryShelf,
    loading: Boolean,
    policy: LayoutPolicy,
    onContinue: (String) -> Unit,
    onOpenStory: (String) -> Unit,
    onOpenDetails: (String) -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val gutter = if (policy.cardColumns > 1) Charaly.space.gutterWide else Charaly.space.gutter

    if (loading) {
        SessionsSkeleton(gutter)
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
        verticalArrangement = Arrangement.spacedBy(Charaly.space.xs),
    ) {
        item(key = "title") {
            Text(
                text = stringResource(R.string.sessions_title),
                style = MaterialTheme.typography.displaySmall,
                color = Charaly.ink.primary,
                modifier = Modifier.semantics { heading() },
            )
        }

        shelf.emptyState?.let { state ->
            item(key = "empty") {
                CharalyEmptyState(
                    state = state,
                    action = {
                        dev.charaly.app.ui.components.CharalyAction(
                            label = state.actionLabel,
                            onClick = onOpenLibrary,
                        )
                    },
                )
            }
        }

        // ---- Playing now ------------------------------------------------------
        val playing = shelf.stories.filter { it.isLive }
        if (playing.isNotEmpty()) {
            item(key = "playing-header") {
                CharalySectionHeader(
                    title = stringResource(R.string.sessions_playing_now),
                    micro = true,
                )
            }
            items(
                count = playing.size,
                key = { index -> "playing-${playing[index].id}" },
            ) { index ->
                SessionRow(
                    story = playing[index],
                    onOpen = { onOpenStory(playing[index].id) },
                )
            }
        }

        // ---- Earlier ----------------------------------------------------------
        val earlier = shelf.stories.filterNot { it.isLive }
        if (earlier.isNotEmpty()) {
            item(key = "earlier-header") {
                CharalySectionHeader(
                    title = stringResource(R.string.sessions_earlier),
                    micro = true,
                )
            }
            items(
                count = earlier.size,
                key = { index -> "earlier-${earlier[index].id}" },
            ) { index ->
                SessionRow(
                    story = earlier[index],
                    onOpen = { onOpenStory(earlier[index].id) },
                )
            }
        }
    }
}

/**
 * Whether a story still reads as alive: touched within the day, in the same terms the
 * shelf's own relative-time labels use. "Daha önce" means it has been sitting.
 */
private val ShelfStory.isLive: Boolean
    get() = lastPlayedLabel == "Şimdi" ||
        lastPlayedLabel.endsWith("dk önce") ||
        lastPlayedLabel.endsWith("sa önce") ||
        lastPlayedLabel == "Dün"

/**
 * One session row.
 *
 * The cover carries the world's identity; the title carries the story's; the italic line
 * is the last thing anyone said. The amber dot on a live row is the same "the world is
 * awake" signal the Home card and the stage clock use.
 */
@Composable
private fun SessionRow(
    story: ShelfStory,
    onOpen: () -> Unit,
) {
    val atmosphere = CharalyAtmosphere.of(story.theme)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .tappable(onOpen)
            .padding(horizontal = Charaly.space.xxs, vertical = Charaly.space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CharalyShapes.soft),
        ) {
            PackArtworkHero(
                artwork = story.artwork,
                atmosphere = atmosphere,
                modifier = Modifier.fillMaxSize(),
                strength = 0.8f,
            )
        }
        Spacer(Modifier.width(Charaly.space.md))
        Column(Modifier.weight(1f)) {
            Text(
                text = story.title,
                style = MaterialTheme.typography.titleMedium,
                color = Charaly.ink.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    story.contextLine.takeIf { it.isNotBlank() },
                    story.lastPlayedLabel.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = Charaly.ink.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (story.moment.isNotBlank()) {
                Text(
                    text = story.moment,
                    style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                    color = Charaly.ink.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (story.isLive) {
            Spacer(Modifier.width(Charaly.space.sm))
            // The amber pulse: the world in that story is still awake.
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CharalyShapes.pill)
                    .background(Charaly.signal.amber),
            )
        } else {
            Spacer(Modifier.width(Charaly.space.sm))
            CharalyIconButton(
                icon = Icons.Filled.PlayArrow,
                contentDescription = Loc.t("a11y.continue_story", story.title),
                onClick = onOpen,
            )
        }
    }
}

/** Skeletons shaped like the real screen, so nothing moves when the data lands. */
@Composable
private fun SessionsSkeleton(gutter: androidx.compose.ui.unit.Dp) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = gutter, vertical = Charaly.space.xl),
        verticalArrangement = Arrangement.spacedBy(Charaly.space.md),
    ) {
        CharalySkeleton(Modifier.fillMaxWidth(0.4f), height = 40.dp)
        repeat(4) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CharalySkeleton(Modifier.size(64.dp), shape = CharalyShapes.soft)
                Spacer(Modifier.width(Charaly.space.md))
                Column(Modifier.weight(1f)) {
                    CharalySkeleton(Modifier.fillMaxWidth(0.6f), height = 18.dp)
                    CharalySkeleton(
                        Modifier
                            .fillMaxWidth(0.4f)
                            .padding(top = 6.dp),
                        height = 14.dp,
                    )
                }
            }
        }
    }
}
