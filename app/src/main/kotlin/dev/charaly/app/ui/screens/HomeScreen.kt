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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.charaly.app.R
import dev.charaly.app.ui.art.PackArtworkHero
import dev.charaly.app.ui.components.CharalyAction
import dev.charaly.app.ui.components.CharalyEmptyState
import dev.charaly.app.ui.components.CharalyHero
import dev.charaly.app.ui.components.CharalyIconButton
import dev.charaly.app.ui.components.CharalyPill
import dev.charaly.app.ui.components.CharalyPresence
import dev.charaly.app.ui.components.CharalyQuietAction
import dev.charaly.app.ui.components.CharalySectionHeader
import dev.charaly.app.ui.components.CharalySkeleton
import dev.charaly.app.ui.components.PresenceStyle
import dev.charaly.app.ui.components.tappable
import dev.charaly.app.ui.design.Charaly
import dev.charaly.app.ui.design.CharalyAtmosphere
import dev.charaly.app.ui.design.CharalyShapes
import dev.charaly.app.ui.design.toCompose
import dev.charaly.runtime.presentation.ContinueSurface
import dev.charaly.runtime.presentation.LayoutPolicy
import dev.charaly.runtime.presentation.LobbySnapshot
import dev.charaly.runtime.presentation.ModelStatusCard
import dev.charaly.runtime.presentation.StoryShelfItem
import dev.charaly.runtime.presentation.WorldWeight

/**
 * HOME - THE WORLD LOBBY.
 *
 * ## The one visual idea
 *
 * **A doorway.** Not a dashboard, not a control panel: somewhere you arrive and choose where
 * to go next.
 *
 * So the screen answers exactly three questions, in order, and refuses everything else:
 *
 * ```
 *   1. Where am I going?      the continuation surface, full bleed, if there is one
 *   2. Where could I go?      the worlds, ranked, with real visual weight
 *   3. What else is mine?     the shelf of other stories, quiet, below the fold
 * ```
 *
 * ## What is absent, and why that is the point
 *
 * No character counts. No location counts. No event counts. No "3 threads open".
 *
 * Those are the *engine's* inventory. Printing them on the surface a player arrives at is
 * what made the previous Home read as "a database containing 19 characters" rather than as
 * a library of living worlds. A user does not choose a world because it has twenty-six
 * locations; they choose it because they want to be somewhere.
 *
 * ## Layout is a decision, not a width check
 *
 * A phone gets one column with the lead world full-bleed. A wide screen gets two columns,
 * because a 900dp single column of cards is a stretch rather than a design. Both come from
 * [LayoutPolicy], which is unit tested on the JVM.
 */
@Composable
fun HomeScreen(
    snapshot: LobbySnapshot,
    loading: Boolean,
    policy: LayoutPolicy,
    onContinue: (String) -> Unit,
    onOpenWorld: (String) -> Unit,
    onOpenStory: (String) -> Unit,
    onOpenWorlds: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val gutter = if (policy.cardColumns > 1) Charaly.space.gutterWide else Charaly.space.gutter

    if (loading) {
        HomeSkeleton(gutter = gutter)
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = gutter,
            end = gutter,
            bottom = Charaly.space.section,
        ),
        verticalArrangement = Arrangement.spacedBy(Charaly.space.xl),
    ) {
        // ---- 0. the greeting and the question -------------------------------
        item(key = "headline") {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = snapshot.greeting,
                        style = MaterialTheme.typography.bodySmall,
                        color = Charaly.ink.muted,
                        modifier = Modifier.weight(1f),
                    )
                    CharalyIconButton(
                        icon = Icons.Filled.Tune,
                        contentDescription = stringResource(R.string.nav_settings),
                        onClick = onOpenSettings,
                    )
                }
                Spacer(Modifier.height(Charaly.space.xs))
                Text(
                    text = snapshot.headline,
                    style = MaterialTheme.typography.displayMedium,
                    color = Charaly.ink.primary,
                    modifier = Modifier.semantics { heading() },
                )
                if (snapshot.subline.isNotBlank()) {
                    Text(
                        text = snapshot.subline,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Charaly.ink.muted,
                        modifier = Modifier.padding(top = Charaly.space.xs),
                    )
                }
            }
        }

        // ---- 1. the continuation surface -------------------------------------
        //
        // Full-bleed artwork, the world's name, one sentence about what is happening, and
        // one action. This is the most important object in the app, and it is
        // deliberately the only object on the screen at that scale.
        snapshot.continueSurface?.let { surface ->
            item(key = "continue") {
                ContinueSurfaceCard(
                    surface = surface,
                    onContinue = { onContinue(surface.storyId) },
                    onOpenWorld = { onOpenWorlds() },
                )
            }
        }

        // ---- 2. the worlds ---------------------------------------------------
        item(key = "worlds-header") {
            CharalySectionHeader(
                title = stringResource(R.string.home_worlds),
                caption = if (snapshot.worlds.isEmpty()) {
                    ""
                } else {
                    stringResource(R.string.home_worlds_caption, snapshot.worlds.size)
                },
                trailing = {
                    CharalyQuietAction(label = stringResource(R.string.action_see_all), onClick = onOpenWorlds)
                },
            )
        }

        when {
            snapshot.worlds.isEmpty() -> item(key = "worlds-empty") {
                snapshot.emptyWorlds?.let { state ->
                    CharalyEmptyState(
                        state = state,
                        action = {
                            CharalyAction(
                                label = state.actionLabel.ifBlank { stringResource(R.string.action_find_world) },
                                onClick = onOpenWorlds,
                            )
                        },
                    )
                }
            }

            policy.cardColumns > 1 -> item(key = "worlds-grid") {
                // Chunked into rows so there is exactly one vertical scroll owner per
                // screen. A nested LazyVerticalGrid inside a lazy item throws at measure
                // time, which is the bug the runtime's `ResponsiveRows` exists to avoid.
                Column(verticalArrangement = Arrangement.spacedBy(Charaly.space.md)) {
                    snapshot.worlds.chunked(policy.cardColumns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(Charaly.space.md)) {
                            row.forEach { world ->
                                Box(Modifier.weight(1f)) {
                                    WorldFeedCard(
                                        title = world.title,
                                        premise = world.premise,
                                        hook = world.hook,
                                        genres = world.genres,
                                        artwork = world.artwork,
                                        atmosphere = CharalyAtmosphere.of(world.theme),
                                        weight = world.weight,
                                        lastPlayedLabel = world.lastPlayedLabel,
                                        onClick = { onOpenWorld(world.id) },
                                    )
                                }
                            }
                            repeat(policy.cardColumns - row.size) {
                                Box(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            else -> item(key = "worlds-feed") {
                Column(verticalArrangement = Arrangement.spacedBy(Charaly.space.xl)) {
                    snapshot.worlds.forEach { world ->
                        WorldFeedCard(
                            title = world.title,
                            premise = world.premise,
                            hook = world.hook,
                            genres = world.genres,
                            artwork = world.artwork,
                            atmosphere = CharalyAtmosphere.of(world.theme),
                            weight = world.weight,
                            lastPlayedLabel = world.lastPlayedLabel,
                            onClick = { onOpenWorld(world.id) },
                        )
                    }
                }
            }
        }

        // ---- 3. the shelf ----------------------------------------------------
        //
        // Below the fold and deliberately quiet. If the shelf competed with the
        // continuation surface there would be two objects at the same weight, and a screen
        // with two ideas has none.
        if (snapshot.stories.isNotEmpty()) {
            item(key = "shelf-header") {
                CharalySectionHeader(
                    title = stringResource(R.string.home_your_stories),
                    micro = true,
                    trailing = { CharalyQuietAction(label = stringResource(R.string.home_library), onClick = onOpenWorlds) },
                )
            }
            item(key = "shelf") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Charaly.space.md)) {
                    items(snapshot.stories.size, key = { snapshot.stories[it].id }) { index ->
                        val story = snapshot.stories[index]
                        ShelfCard(
                            story = story,
                            atmosphere = CharalyAtmosphere.of(story.theme),
                            onClick = { onOpenStory(story.id) },
                        )
                    }
                }
            }
        }

        // ---- 4. the model, and only if it needs saying -----------------------
        item(key = "model") {
            ModelStatusLine(card = snapshot.modelStatus, onClick = onOpenModels)
        }
    }
}

/**
 * The continuation surface.
 *
 * ## Four pieces of information, and no fifth
 *
 * ```
 *   PARIS                                  where
 *   Evening                                when, in story time
 *   "Something has changed since you left." what
 *   [ Continue ]                          the only action
 * ```
 *
 * The companion's name appears only when there is one, because "who was I talking to" is
 * the other half of "where was I" - and the presence dot is the same fact for someone who
 * cannot read the caption.
 */
@Composable
private fun ContinueSurfaceCard(
    surface: ContinueSurface,
    onContinue: () -> Unit,
    onOpenWorld: () -> Unit,
) {
    val atmosphere = CharalyAtmosphere.of(surface.theme)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(340.dp),
    ) {
        PackArtworkHero(
            artwork = surface.artwork,
            atmosphere = atmosphere,
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Charaly.space.lg),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Text(
                text = surface.worldName.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = atmosphere.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (surface.timeLabel.isNotBlank()) {
                Text(
                    text = surface.timeLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = Charaly.ink.secondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            // The load-bearing sentence, and the reason this screen exists.
            Spacer(Modifier.height(Charaly.space.md))
            Text(
                text = "“${surface.moment}”",
                style = MaterialTheme.typography.headlineSmall,
                color = Charaly.ink.primary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )

            if (surface.hasCompanion) {
                Spacer(Modifier.height(Charaly.space.md))
                CharalyPresence(
                    name = surface.companionName,
                    accent = surface.companionAccent.toCompose(),
                    markSize = 32.dp,
                    status = PresenceStyle.HERE,
                    caption = listOfNotNull(
                        surface.presenceLabel.takeIf { it.isNotBlank() },
                        surface.lastPlayedLabel.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                )
            }

            Spacer(Modifier.height(Charaly.space.lg))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CharalyAction(
                    label = stringResource(R.string.action_continue),
                    onClick = onContinue,
                    icon = Icons.Filled.PlayArrow,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(Charaly.space.xs))
                CharalyIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.home_browse_worlds),
                    onClick = onOpenWorld,
                )
            }
        }
    }
}

/**
 * One world in the feed.
 *
 * ## Weight is the hierarchy
 *
 * The first world is [WorldWeight.DOMINANT]: full bleed, tallest, and set in the display
 * size. The next two are FULL. The rest are QUIET - shorter, one line of premise, no hook.
 *
 * A feed of identically sized cards is a grid wearing a vertical hat. The weight itself is
 * decided by the presenter on the JVM, so "the first card is the lead" is an assertable
 * property rather than an index comparison buried in a composable.
 */
@Composable
private fun WorldFeedCard(
    title: String,
    premise: String,
    hook: String,
    genres: List<String>,
    artwork: dev.charaly.runtime.domain.PackArtwork,
    atmosphere: CharalyAtmosphere,
    weight: WorldWeight,
    lastPlayedLabel: String,
    onClick: () -> Unit,
) {
    val height = when (weight) {
        WorldWeight.DOMINANT -> 300.dp
        WorldWeight.FULL -> 220.dp
        WorldWeight.QUIET -> 150.dp
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
            if (weight != WorldWeight.QUIET && genres.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Charaly.space.xs),
                ) {
                    genres.take(2).forEach { genre -> CharalyPill(label = genre) }
                    if (lastPlayedLabel.isNotBlank()) {
                        CharalyPill(label = lastPlayedLabel)
                    }
                }
                Spacer(Modifier.height(Charaly.space.xs))
            }
            Text(
                text = title,
                style = when (weight) {
                    WorldWeight.DOMINANT -> MaterialTheme.typography.displaySmall
                    WorldWeight.FULL -> MaterialTheme.typography.headlineMedium
                    WorldWeight.QUIET -> MaterialTheme.typography.titleLarge
                },
                color = Charaly.ink.primary,
                maxLines = if (weight == WorldWeight.DOMINANT) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            if (weight != WorldWeight.QUIET && premise.isNotBlank()) {
                Text(
                    text = premise,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Charaly.ink.secondary,
                    maxLines = if (weight == WorldWeight.DOMINANT) 3 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (weight == WorldWeight.DOMINANT && hook.isNotBlank()) {
                Text(
                    text = hook,
                    style = MaterialTheme.typography.bodySmall,
                    color = atmosphere.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        // The whole card is the target: one large hit area rather than a small arrow in
        // the corner, which on a phone is the difference between usable and not.
        Box(
            Modifier
                .fillMaxSize()
                .tappable(onClick),
        )
    }
}

/** One story on the shelf. Quiet by design. */
@Composable
private fun ShelfCard(
    story: StoryShelfItem,
    atmosphere: CharalyAtmosphere,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(width = 220.dp, height = 148.dp)
            .clip(CharalyShapes.soft),
    ) {
        PackArtworkHero(
            artwork = story.artwork,
            atmosphere = atmosphere,
            modifier = Modifier.fillMaxSize(),
            strength = 0.8f,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Charaly.space.sm),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Text(
                text = story.worldName.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = atmosphere.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = story.title,
                style = MaterialTheme.typography.titleMedium,
                color = Charaly.ink.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    story.timeLabel.takeIf { it.isNotBlank() },
                    story.lastPlayedLabel.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = Charaly.ink.muted,
                maxLines = 1,
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .tappable(onClick),
        )
    }
}

/**
 * The model line.
 *
 * ## Why it is at the bottom and not the top
 *
 * "Is my model ready" is a real question and it is answered honestly here - but it is a
 * *setup* question, not an *arrival* question. Putting it above the continuation surface
 * would make the screen open with a warning, which is exactly the impression a local model
 * does not deserve: everything except generation already works with no network at all.
 *
 * So it sits last, and it is only loud when something is genuinely wrong.
 */
@Composable
private fun ModelStatusLine(
    card: ModelStatusCard,
    onClick: () -> Unit,
) {
    val needsAttention = !card.isReady
    val tint = if (card.isReady) Charaly.ink.secondary else Charaly.atmosphere.accent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .tappable(onClick)
            .padding(vertical = Charaly.space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CharalyShapes.pill)
                .background(tint),
        )
        Spacer(Modifier.size(Charaly.space.sm))
        Column(Modifier.weight(1f)) {
            Text(
                text = card.modelName,
                style = MaterialTheme.typography.bodyMedium,
                color = Charaly.ink.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOf(card.stateLabel, card.detailLabel)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = Charaly.ink.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        CharalyPill(
            label = stringResource(if (needsAttention) R.string.home_model_choose else R.string.home_model_ready),
            selected = needsAttention,
        )
    }
}

/** Skeletons shaped like the real screen, so nothing moves when the data lands. */
@Composable
private fun HomeSkeleton(gutter: Dp) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = gutter, vertical = Charaly.space.xl),
        verticalArrangement = Arrangement.spacedBy(Charaly.space.md),
    ) {
        CharalySkeleton(Modifier.fillMaxWidth(0.55f), height = 44.dp)
        CharalySkeleton(Modifier.fillMaxWidth(0.8f), height = 20.dp)
        CharalySkeleton(
            Modifier
                .fillMaxWidth()
                .height(340.dp),
            shape = CharalyShapes.soft,
        )
        CharalySkeleton(Modifier.fillMaxWidth(0.4f), height = 22.dp)
        CharalySkeleton(
            Modifier
                .fillMaxWidth()
                .height(220.dp),
            shape = CharalyShapes.soft,
        )
    }
}