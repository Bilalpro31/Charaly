package dev.charaly.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.charaly.app.ui.Gap
import dev.charaly.app.ui.art.ArtworkHero
import dev.charaly.app.ui.components.CharalyCard
import dev.charaly.app.ui.components.CharalyFilterChip
import dev.charaly.app.ui.components.CharalyGhostButton
import dev.charaly.app.ui.components.CharalyIconButton
import dev.charaly.app.ui.components.CharalyPrimaryButton
import dev.charaly.app.ui.components.EmptyStateView
import dev.charaly.app.ui.components.Eyebrow
import dev.charaly.app.ui.components.GenreChip
import dev.charaly.app.ui.components.SectionHeader
import dev.charaly.app.ui.components.SkeletonList
import dev.charaly.app.ui.components.StatusDot
import dev.charaly.app.ui.components.CharalySearchField
import dev.charaly.app.ui.components.ChunkedCardRows
import dev.charaly.app.ui.theme.Charaly
import dev.charaly.runtime.presentation.LibrarySection
import dev.charaly.runtime.presentation.LibrarySnapshot
import dev.charaly.runtime.presentation.PackCard
import dev.charaly.runtime.presentation.PackSortOrder

/** What the pack detail screen asked the shell to open. */
enum class PackEditorTarget { CHARACTER, LOCATION, EVENT }

/**
 * Story Packs — the library.
 *
 * This is the screen that has to feel like browsing worlds rather than rows of a
 * database, so it leads with artwork and titles, keeps the filter row thin, and
 * groups packs into Featured / Recently played / All rather than one flat list.
 *
 * Everything it renders comes from the real `StoryPack` objects; the counts on each
 * card are the actual number of characters and locations in that pack.
 */
@Composable
fun StoryPackLibraryScreen(
    snapshot: LibrarySnapshot,
    onQueryChange: (String) -> Unit,
    onToggleGenre: (String) -> Unit,
    onClearFilters: () -> Unit,
    onSortChange: (PackSortOrder) -> Unit,
    onOpenPack: (String) -> Unit,
    onCreatePack: () -> Unit,
) {
    val tablet = dev.charaly.app.ui.CharalyLayout.isTablet()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = Charaly.tokens.spacing.section),
    ) {
        item(key = "title") {
            Column(
                modifier = Modifier.padding(
                    start = Charaly.tokens.spacing.gutter,
                    end = Charaly.tokens.spacing.gutter,
                    top = Charaly.tokens.spacing.xl,
                    bottom = Charaly.tokens.spacing.md,
                ),
            ) {
                Eyebrow("Library")
                Text(
                    text = "Story Packs",
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = "Worlds with their own characters, places, events and lore.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        item(key = "search") {
            Row(
                modifier = Modifier.padding(horizontal = Charaly.tokens.spacing.gutter),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CharalySearchField(
                    value = snapshot.query,
                    onValueChange = onQueryChange,
                    placeholder = "Search worlds, genres, characters",
                    modifier = Modifier.weight(1f),
                )
                if (snapshot.selectedGenres.isNotEmpty() || snapshot.query.isNotBlank()) {
                    CharalyIconButton(
                        icon = Icons.Filled.Close,
                        contentDescription = "Clear search and filters",
                        onClick = onClearFilters,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }

        if (snapshot.availableGenres.isNotEmpty()) {
            item(key = "genres") {
                LazyRow(
                    contentPadding = PaddingValues(
                        horizontal = Charaly.tokens.spacing.gutter,
                        vertical = Charaly.tokens.spacing.sm,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.xs),
                ) {
                    items(snapshot.availableGenres, key = { it }) { genre ->
                        CharalyFilterChip(
                            label = genre,
                            selected = genre in snapshot.selectedGenres,
                            onClick = { onToggleGenre(genre) },
                        )
                    }
                }
            }
        }

        item(key = "sort") {
            Row(
                modifier = Modifier.padding(
                    start = Charaly.tokens.spacing.gutter,
                    end = Charaly.tokens.spacing.gutter,
                    bottom = Charaly.tokens.spacing.sm,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${snapshot.visiblePacks} of ${snapshot.totalPacks} worlds",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                PackSortOrder.entries.forEach { order ->
                    CharalyFilterChip(
                        label = order.label,
                        selected = snapshot.sortOrder == order,
                        onClick = { onSortChange(order) },
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }

        val empty = snapshot.emptyState
        if (empty != null) {
            item(key = "empty") {
                EmptyStateView(
                    state = empty,
                    modifier = Modifier.padding(top = Charaly.tokens.spacing.xxl),
                ) {
                    CharalyPrimaryButton(
                        label = empty.actionLabel,
                        onClick = if (snapshot.totalPacks == 0) onCreatePack else onClearFilters,
                    )
                }
            }
            return@LazyColumn
        }

        snapshot.sections.forEach { section ->
            item(key = "section-${section.title}") {
                SectionHeader(
                    title = section.title,
                    subtitle = section.subtitle,
                    modifier = Modifier.padding(
                        start = Charaly.tokens.spacing.gutter,
                        end = Charaly.tokens.spacing.gutter,
                        top = Charaly.tokens.spacing.md,
                    ),
                )
            }
            if (tablet) {
                // A tablet gets two columns of the *same* card rather than one wide
                // row: stretching a phone layout sideways is not a tablet layout.
                //
                // This is deliberately a chunked set of Rows and NOT a LazyVerticalGrid.
                // A vertically scrollable layout inside a LazyColumn item is measured
                // with an infinite max height and throws; the outer LazyColumn stays
                // the single scroll owner. See ResponsiveRows for the full rationale.
                item(key = "grid-${section.title}") {
                    ChunkedCardRows(
                        items = section.packs,
                        keyOf = { pack: PackCard -> "${section.title}-${pack.id}" },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Charaly.tokens.spacing.gutter),
                    ) { pack ->
                        LibraryPackCard(
                            pack = pack,
                            onClick = { onOpenPack(pack.id) },
                        )
                    }
                }
            } else {
                items(section.packs, key = { "${section.title}-${it.id}" }) { pack ->
                    LibraryPackCard(
                        pack = pack,
                        onClick = { onOpenPack(pack.id) },
                        modifier = Modifier.padding(
                            horizontal = Charaly.tokens.spacing.gutter,
                            vertical = 6.dp,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * A library row: cover art on the left, the world described on the right.
 *
 * The artwork is the point. A text-only list of packs is the single biggest reason a
 * story library feels like a database, so the cover takes a third of the row and the
 * text gets the rest.
 */
@Composable
private fun LibraryPackCard(
    pack: PackCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = Charaly.tokens.radii.shapeLg
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .semantics { contentDescription = "${pack.title}. ${pack.tagline}" }
            .clickable { onClick() },
    ) {
        Box(
            modifier = Modifier
                .width(112.dp)
                .height(140.dp),
        ) {
            ArtworkHero(
                artwork = pack.artwork,
                theme = pack.theme,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(Charaly.tokens.spacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = pack.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (pack.isDemo) {
                    Text(
                        text = "DEMO",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
            }
            Text(
                text = pack.tagline,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
            if (pack.genres.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    pack.genres.forEach { GenreChip(it) }
                }
            }
            Text(
                text = "${pack.characterCount} characters  ·  ${pack.locationCount} locations" +
                    if (pack.sessionCount > 0) "  ·  ${pack.sessionCount} stories" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = when (pack.lastPlayedLabel) {
                    "New" -> "Not played yet"
                    else -> "Last played ${pack.lastPlayedLabel}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = Charaly.accent.accent,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}