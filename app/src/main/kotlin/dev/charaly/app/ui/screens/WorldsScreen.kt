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
import androidx.compose.material.icons.filled.Close
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
import dev.charaly.app.ui.components.CharalyEmptyState
import dev.charaly.app.ui.components.CharalyIconButton
import dev.charaly.app.ui.components.CharalyPill
import dev.charaly.app.ui.components.CharalyPillRow
import dev.charaly.app.ui.components.CharalySearchField
import dev.charaly.app.ui.components.CharalySkeleton
import dev.charaly.app.ui.components.tappable
import dev.charaly.app.ui.design.Charaly
import dev.charaly.app.ui.design.CharalyAtmosphere
import dev.charaly.app.ui.design.CharalyShapes
import dev.charaly.runtime.presentation.LayoutPolicy
import dev.charaly.runtime.presentation.WorldWeight
import dev.charaly.runtime.presentation.WorldsFeedPresenter
import dev.charaly.runtime.presentation.WorldsFeedSnapshot

/**
 * WORLDS - DISCOVERY.
 *
 * ## A vertical feed, and why
 *
 * The library used to be a grid of identically sized cards in labelled sections. A grid is
 * the right shape for a *catalogue* - a list of interchangeable things you are comparing -
 * and the wrong shape for a list of *places you could be*, which is what these are. Nothing
 * here is interchangeable: you are not choosing between two 4B models on a spec sheet, you
 * are deciding which world to spend an evening in.
 *
 * A vertical feed with weighted cards does what a grid cannot:
 *
 * ```
 *   leading card     full bleed, tall, display type. The world you were last in.
 *   next two         substantial. The rest of what deserves attention.
 *   the tail         shorter, quieter. Present, not competing.
 * ```
 *
 * ## What a card is
 *
 * Artwork, the world's name, its premise, and at most one hook. No character count, no
 * location count, no event count. Those are engine structures and this is a storefront.
 *
 * ## Why there is a search and a filter row
 *
 * Because a library of living worlds is a set you might want to narrow - by mood, by genre -
 * and the honest answer to "how do I find the drowned one" is a search box, not a
 * horizontally scrolling row of twenty genre chips.
 */
@Composable
fun WorldsScreen(
    snapshot: WorldsFeedSnapshot,
    loading: Boolean,
    policy: LayoutPolicy,
    onQueryChange: (String) -> Unit,
    onToggleGenre: (String) -> Unit,
    onClearFilters: () -> Unit,
    onOpenWorld: (String) -> Unit,
) {
    val gutter = if (policy.cardColumns > 1) Charaly.space.gutterWide else Charaly.space.gutter

    if (loading) {
        WorldsSkeleton(gutter = gutter)
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = gutter,
            end = gutter,
            bottom = Charaly.space.section,
        ),
        verticalArrangement = Arrangement.spacedBy(Charaly.space.lg),
    ) {
        item(key = "title") {
            Column {
                Text(
                    text = Loc.t("worlds.title"),
                    style = MaterialTheme.typography.displaySmall,
                    color = Charaly.ink.primary,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(Charaly.space.xs))
                Text(
                    text = snapshot.matchCountLabel().let {
                        if (snapshot.totalCount == 1) "1 world to step into" else "$it worlds to step into"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Charaly.ink.muted,
                )
            }
        }

        item(key = "search") {
            CharalySearchField(
                value = snapshot.query,
                onValueChange = onQueryChange,
                placeholder = WorldsFeedPresenter.SEARCH_HINT,
            )
        }

        // Only when there is something to filter *by*. An empty chip row is a control that
        // does nothing, which is worse than no control at all.
        if (snapshot.availableGenres.isNotEmpty()) {
            item(key = "genres") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CharalyPillRow(
                        labels = snapshot.availableGenres,
                        selected = snapshot.selectedGenres,
                        onSelect = onToggleGenre,
                        modifier = Modifier.weight(1f),
                    )
                    if (snapshot.selectedGenres.isNotEmpty()) {
                        CharalyIconButton(
                            icon = Icons.Filled.Close,
                            contentDescription = Loc.t("worlds.filter_clear"),
                            onClick = onClearFilters,
                        )
                    }
                }
            }
        }

        snapshot.emptyState?.let { state ->
            item(key = "empty") {
                CharalyEmptyState(
                    state = state,
                    action = {
                        if (state.actionLabel.contains("Clear", ignoreCase = true)) {
                            dev.charaly.app.ui.components.CharalyAction(
                                label = state.actionLabel,
                                onClick = onClearFilters,
                            )
                        }
                    },
                )
            }
        }

        items(
            count = snapshot.worlds.size,
            key = { index -> snapshot.worlds[index].id },
        ) { index ->
            val world = snapshot.worlds[index]
            WorldRow(
                title = world.title,
                universe = world.genres.joinToString(" · "),
                premise = world.premise,
                hook = world.hook,
                artwork = world.artwork,
                atmosphere = CharalyAtmosphere.of(world.theme),
                weight = world.weight,
                lastPlayedLabel = world.lastPlayedLabel,
                sessionsLabel = if (world.sessionCount > 0) {
                    if (world.sessionCount == 1) "1 story" else "${world.sessionCount} stories"
                } else {
                    ""
                },
                onClick = { onOpenWorld(world.id) },
            )
        }
    }
}

/**
 * One world, in the feed.
 *
 * ## The first card is a hero; the rest are panels
 *
 * That is not a stylistic flourish - it is the only way a vertical feed communicates
 * priority. Equal cards make the reader do the ranking, and a screen that makes the reader
 * rank things is a screen that has not done its job.
 */
@Composable
private fun WorldRow(
    title: String,
    universe: String,
    premise: String,
    hook: String,
    artwork: dev.charaly.runtime.domain.PackArtwork,
    atmosphere: CharalyAtmosphere,
    weight: WorldWeight,
    lastPlayedLabel: String,
    sessionsLabel: String,
    onClick: () -> Unit,
) {
    val height: Dp = when (weight) {
        WorldWeight.DOMINANT -> 380.dp
        WorldWeight.FULL -> 260.dp
        WorldWeight.QUIET -> 190.dp
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(CharalyShapes.soft)
            .background(Charaly.surface.base),
    ) {
        PackArtworkHero(
            artwork = artwork,
            atmosphere = atmosphere,
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Charaly.space.md),
            verticalArrangement = Arrangement.Bottom,
        ) {
            if (weight != WorldWeight.QUIET) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Charaly.space.xs),
                ) {
                    if (universe.isNotBlank()) CharalyPill(label = universe)
                    if (lastPlayedLabel.isNotBlank()) CharalyPill(label = lastPlayedLabel)
                    if (sessionsLabel.isNotBlank()) CharalyPill(label = sessionsLabel)
                }
                Spacer(Modifier.height(Charaly.space.sm))
            }

            Text(
                text = title,
                style = when (weight) {
                    WorldWeight.DOMINANT -> MaterialTheme.typography.displaySmall
                    WorldWeight.FULL -> MaterialTheme.typography.headlineLarge
                    WorldWeight.QUIET -> MaterialTheme.typography.titleLarge
                },
                color = Charaly.ink.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )

            // The premise is the one substantial paragraph, and it only appears where there
            // is room for it. A three-line premise squeezed into a 190dp card is the text
            // overlap this whole layout pass exists to prevent.
            if (weight == WorldWeight.DOMINANT && premise.isNotBlank()) {
                Text(
                    text = premise,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Charaly.ink.secondary,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Charaly.space.xs),
                )
            } else if (premise.isNotBlank()) {
                Text(
                    text = premise,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Charaly.ink.secondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            if (weight == WorldWeight.DOMINANT && hook.isNotBlank()) {
                Text(
                    text = hook,
                    style = MaterialTheme.typography.bodyMedium,
                    color = atmosphere.accent,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Charaly.space.sm),
                )
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .tappable(onClick),
        )
    }
}

@Composable
private fun WorldsSkeleton(gutter: Dp) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = gutter, vertical = Charaly.space.xl),
        verticalArrangement = Arrangement.spacedBy(Charaly.space.md),
    ) {
        CharalySkeleton(Modifier.fillMaxWidth(0.5f), height = 40.dp)
        CharalySkeleton(Modifier.fillMaxWidth(), height = 48.dp)
        CharalySkeleton(
            Modifier
                .fillMaxWidth()
                .height(380.dp),
            shape = CharalyShapes.soft,
        )
        CharalySkeleton(
            Modifier
                .fillMaxWidth()
                .height(260.dp),
            shape = CharalyShapes.soft,
        )
        CharalySkeleton(
            Modifier
                .fillMaxWidth()
                .height(190.dp),
            shape = CharalyShapes.soft,
        )
    }
}

/** Kept so the empty-state icon import is not accidental. */
internal val EmptyIconSize: Dp = 24.dp