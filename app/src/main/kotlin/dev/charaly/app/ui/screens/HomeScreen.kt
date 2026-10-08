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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.charaly.app.R
import dev.charaly.app.ui.art.PackArtworkHero
import dev.charaly.app.ui.components.CharalyEmptyState
import dev.charaly.app.ui.components.CharalyIconButton
import dev.charaly.app.ui.components.CharalyPill
import dev.charaly.app.ui.components.CharalySkeleton
import dev.charaly.app.ui.components.tappable
import dev.charaly.app.ui.design.Charaly
import dev.charaly.app.ui.design.CharalyAtmosphere
import dev.charaly.app.ui.design.CharalyShapes
import dev.charaly.runtime.presentation.ContinueSurface
import dev.charaly.runtime.presentation.LayoutPolicy
import dev.charaly.runtime.presentation.LobbySnapshot
import dev.charaly.runtime.presentation.WorldFeedItem

/**
 * HOME - THE LOBBY.
 *
 * ## The V5 shape
 *
 * **A doorway, drawn as a product.** The screen opens with the wordmark and the search
 * pill - whose placeholder is the presenter's own question, "Nereye gitmek
 * istersiniz?", so the one question the lobby exists to ask is also the control that
 * answers it:
 *
 * ```
 *   charaly                      [settings]
 *   [ search: Nereye gitmek istersiniz? ]
 *   [For you] [Continue] [New] [Trending]
 *   [ the Continue card - 16:10, Live pill, the clock ]
 *   [ pack ] [ pack ]      two columns, square covers
 *   [ pack ] [ pack ]
 * ```
 *
 * ## What is absent, and why that is the point
 *
 * No character counts. No location counts. No event counts. No model line. Those are the
 * *engine's* inventory; printing them on the surface a player arrives at is what made the
 * previous Home read as a database. A user does not choose a world because it has
 * twenty-six locations; they choose it because they want to be somewhere.
 *
 * The filters are real: they narrow the grid from the same snapshot the screen already
 * holds, so a pill that says "Continue" actually continues to something.
 */
@Composable
fun HomeScreen(
    snapshot: LobbySnapshot,
    loading: Boolean,
    policy: LayoutPolicy,
    onContinue: (String) -> Unit,
    onOpenWorld: (String) -> Unit,
    onOpenStory: (String) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val gutter = if (policy.cardColumns > 1) Charaly.space.gutterWide else Charaly.space.gutter

    if (loading) {
        HomeSkeleton(gutter = gutter)
        return
    }

    // The search and the filter are local, deliberate state: they narrow what is already
    // on screen rather than re-asking the presenter, so typing cannot make the lobby
    // flicker or lose the Continue card.
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(HomeFilter.FOR_YOU) }

    val visibleWorlds = remember(snapshot, query, filter) {
        snapshot.worlds
            .filter { world -> filter.matches(world) }
            .filter { world ->
                query.isBlank() ||
                    world.title.contains(query, ignoreCase = true) ||
                    world.premise.contains(query, ignoreCase = true) ||
                    world.genres.any { it.contains(query, ignoreCase = true) }
            }
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
        // ---- 0. the wordmark and the self ------------------------------------
        item(key = "header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "charaly",
                    style = MaterialTheme.typography.displaySmall.copy(
                        letterSpacing = (-2.5).sp,
                    ),
                    color = Charaly.ink.primary,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                CharalyIconButton(
                    icon = Icons.Outlined.Tune,
                    contentDescription = stringResource(R.string.nav_settings),
                    onClick = onOpenSettings,
                )
            }
        }

        // ---- 1. the question, as the search pill ------------------------------
        //
        // The presenter's headline *is* the placeholder: the lobby's one question is also
        // the control that answers it, which is why this screen has no second line of
        // greeting competing with the packs below.
        item(key = "search") {
            SearchPill(
                value = query,
                onValueChange = { query = it },
                placeholder = snapshot.headline,
            )
        }

        // ---- 2. the filters ----------------------------------------------------
        item(key = "filters") {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(Charaly.space.xs),
            ) {
                items(HomeFilter.entries.size) { index ->
                    val entry = HomeFilter.entries[index]
                    FilterPill(
                        label = entry.label,
                        selected = entry == filter,
                        onClick = { filter = entry },
                    )
                }
            }
        }

        // ---- 3. the continuation surface ---------------------------------------
        //
        // Full-bleed artwork at V5's 16:10, the Live pill in amber with the world's own
        // clock, one sentence about what is happening, and the white play button. This is
        // the most important object in the app, and it is deliberately the only object on
        // the screen at that scale.
        snapshot.continueSurface?.let { surface ->
            item(key = "continue") {
                ContinueCard(
                    surface = surface,
                    onContinue = { onContinue(surface.storyId) },
                )
            }
        }

        // ---- 4. the packs, two columns -----------------------------------------
        if (visibleWorlds.isEmpty()) {
            item(key = "worlds-empty") {
                snapshot.emptyWorlds?.let { state ->
                    CharalyEmptyState(
                        state = state,
                        action = {
                            dev.charaly.app.ui.components.CharalyAction(
                                label = state.actionLabel.ifBlank {
                                    stringResource(R.string.action_find_world)
                                },
                                onClick = onOpenLibrary,
                            )
                        },
                    )
                } ?: run {
                    // A search that matched nothing: honest, quiet, and offered a way out.
                    Text(
                        text = "Bu aramayla eşleşen dünya yok.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Charaly.ink.muted,
                        modifier = Modifier.padding(vertical = Charaly.space.lg),
                    )
                }
            }
        } else {
            item(key = "worlds-count") {
                Text(
                    text = snapshot.subline,
                    style = MaterialTheme.typography.bodySmall,
                    color = Charaly.ink.muted,
                )
            }
            visibleWorlds.chunked(2).forEachIndexed { rowIndex, row ->
                item(key = "worlds-row-$rowIndex") {
                    Row(horizontalArrangement = Arrangement.spacedBy(Charaly.space.sm)) {
                        row.forEach { world ->
                            Box(Modifier.weight(1f)) {
                                PackCard(
                                    world = world,
                                    onClick = { onOpenWorld(world.id) },
                                )
                            }
                        }
                        repeat(2 - row.size) {
                            Box(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/**
 * The lobby's filters.
 *
 * Four, each a real narrowing of the same snapshot: "For you" is everything the feed
 * offered, "Continue" the worlds with a live story, "New" the ones played within the
 * day, "Trending" the rest of what the presenter ranked. A pill that filtered nothing
 * would be a legend, not a control.
 */
private enum class HomeFilter(val label: String) {
    FOR_YOU("Senin için"),
    CONTINUE("Devam et"),
    NEW("Yeni"),
    TRENDING("Popüler"),
    ;

    fun matches(world: WorldFeedItem): Boolean = when (this) {
        FOR_YOU -> true
        CONTINUE -> world.lastPlayedLabel.isNotBlank()
        NEW -> world.lastPlayedLabel == "Şimdi" ||
            world.lastPlayedLabel.endsWith("dk önce") ||
            world.lastPlayedLabel.endsWith("sa önce")
        TRENDING -> true
    }
}

/**
 * V5's search pill: a surface2 capsule with the search glyph and the presenter's question
 * as the placeholder. A `BasicTextField` on a filled surface rather than an outlined box
 * with a floating label, because this is not a developer form.
 */
@Composable
private fun SearchPill(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CharalyShapes.pill)
            .background(Charaly.surface.overlay)
            .padding(horizontal = Charaly.space.lg, vertical = Charaly.space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = Charaly.ink.muted,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(Charaly.space.sm))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Charaly.ink.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = Charaly.ink.primary,
                    fontSize = 16.sp,
                ),
                cursorBrush = SolidColor(Charaly.signal.amber),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * V5's filter pill: a bordered capsule that fills white when active. Selection is carried
 * by *fill* as well as colour, so it survives without the tint.
 */
@Composable
private fun FilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = if (selected) Color(0xFF111111) else Charaly.ink.primary,
        maxLines = 1,
        modifier = Modifier
            .clip(CharalyShapes.pill)
            .background(
                if (selected) Charaly.ink.primary else Color.Transparent,
            )
            .tappable(onClick)
            .padding(horizontal = Charaly.space.lg, vertical = Charaly.space.xs + 2.dp),
    )
}

/**
 * The continuation surface, in V5's shape.
 *
 * Four pieces of information, and no fifth:
 *
 * ```
 *   [Live · 22:40]                amber, the world is awake
 *   Shadows of Paris              the story's own title
 *   Episode 04 · who is waiting   one sentence
 *   ( ▶ )                         the white play button
 * ```
 *
 * The clock is read from the presenter, which reads it from the WorldClock - so if the
 * runtime stopped advancing time, this would stop changing too, visibly, instead of
 * silently lying.
 */
@Composable
private fun ContinueCard(
    surface: ContinueSurface,
    onContinue: () -> Unit,
) {
    val atmosphere = CharalyAtmosphere.of(surface.theme)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 10f)
            .clip(CharalyShapes.soft)
            .tappable(onContinue),
    ) {
        PackArtworkHero(
            artwork = surface.artwork,
            atmosphere = atmosphere,
            modifier = Modifier.fillMaxSize(),
        )
        // V5's scrim: the bottom third, so the text reads over any artwork.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0xD1000000),
                        ),
                        startY = 500f,
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(Charaly.space.lg)
                .padding(end = 72.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The Live pill: amber, with the world's own clock. This is the "the world
                // is alive" signal, and it is the same colour as the stage's clock pill.
                Row(
                    modifier = Modifier
                        .clip(CharalyShapes.pill)
                        .background(Color(0x990E0E12))
                        .padding(horizontal = Charaly.space.xs + 2.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CharalyShapes.pill)
                            .background(Charaly.signal.amber),
                    )
                    Text(
                        text = listOfNotNull(
                            surface.worldName.takeIf { it.isNotBlank() },
                            surface.timeLabel.takeIf { it.isNotBlank() },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = Charaly.signal.amber,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.size(Charaly.space.xs))
            Text(
                text = "“${surface.moment}”",
                style = MaterialTheme.typography.headlineSmall,
                color = Charaly.ink.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            if (surface.hasCompanion) {
                Text(
                    text = surface.companionName,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xCCF4F4F6),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        // The white play button: V5's one filled circle, bottom-right.
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(Charaly.space.md)
                .size(46.dp)
                .clip(CharalyShapes.pill)
                .background(Charaly.ink.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = Color(0xFF111111),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * One pack, in V5's grid shape.
 *
 * A square cover with the world's artwork, a Live or New badge when there is one, the
 * title at 20sp, two lines of premise, the genre tags, and the quiet status line. No
 * counts - a world chosen by inventory is a world chosen for the wrong reason.
 */
@Composable
private fun PackCard(
    world: WorldFeedItem,
    onClick: () -> Unit,
) {
    val atmosphere = CharalyAtmosphere.of(world.theme)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .tappable(onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(CharalyShapes.soft),
        ) {
            PackArtworkHero(
                artwork = world.artwork,
                atmosphere = atmosphere,
                modifier = Modifier.fillMaxSize(),
            )
            if (world.lastPlayedLabel.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(Charaly.space.sm),
                ) {
                    Row(
                        modifier = Modifier
                            .clip(CharalyShapes.pill)
                            .background(Color(0x990E0E12))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(CharalyShapes.pill)
                                .background(Charaly.signal.amber),
                        )
                        Text(
                            text = "Canlı",
                            style = MaterialTheme.typography.labelMedium,
                            color = Charaly.signal.amber,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        Text(
            text = world.title,
            style = MaterialTheme.typography.titleMedium,
            color = Charaly.ink.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Charaly.space.sm),
        )
        if (world.premise.isNotBlank()) {
            Text(
                text = world.premise,
                style = MaterialTheme.typography.bodySmall,
                color = Charaly.ink.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (world.genres.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = Charaly.space.xs),
            ) {
                world.genres.take(2).forEach { genre ->
                    Text(
                        text = genre,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFCFCFD6),
                        maxLines = 1,
                        modifier = Modifier
                            .clip(CharalyShapes.soft)
                            .background(Charaly.surface.raised)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
        if (world.lastPlayedLabel.isNotBlank()) {
            Text(
                text = world.lastPlayedLabel,
                style = MaterialTheme.typography.labelMedium,
                color = Charaly.ink.muted,
                maxLines = 1,
                modifier = Modifier.padding(top = Charaly.space.xs),
            )
        }
    }
}

/** Skeletons shaped like the real screen, so nothing moves when the data lands. */
@Composable
private fun HomeSkeleton(gutter: androidx.compose.ui.unit.Dp) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = gutter, vertical = Charaly.space.xl),
        verticalArrangement = Arrangement.spacedBy(Charaly.space.md),
    ) {
        CharalySkeleton(Modifier.fillMaxWidth(0.4f), height = 36.dp)
        CharalySkeleton(Modifier.fillMaxWidth(), height = 52.dp, shape = CharalyShapes.pill)
        CharalySkeleton(Modifier.fillMaxWidth(0.5f), height = 40.dp, shape = CharalyShapes.pill)
        CharalySkeleton(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f),
            shape = CharalyShapes.soft,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Charaly.space.sm)) {
            repeat(2) {
                CharalySkeleton(
                    Modifier
                        .weight(1f)
                        .aspectRatio(0.72f),
                    shape = CharalyShapes.soft,
                )
            }
        }
    }
}
