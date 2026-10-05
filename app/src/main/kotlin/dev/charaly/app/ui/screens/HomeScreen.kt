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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.charaly.app.ui.devCenteredContent
import dev.charaly.app.ui.ProfileButton
import dev.charaly.app.ui.art.ArtworkHero
import dev.charaly.app.ui.art.CharacterAvatar
import dev.charaly.app.ui.components.CharalyCard
import dev.charaly.app.ui.components.CharalyGhostButton
import dev.charaly.app.ui.components.CharalyIconButton
import dev.charaly.app.ui.components.CharalyPrimaryButton
import dev.charaly.app.ui.components.Eyebrow
import dev.charaly.app.ui.components.SectionHeader
import dev.charaly.app.ui.theme.Charaly
import dev.charaly.app.ui.theme.toColor
import dev.charaly.runtime.presentation.ContinueCard
import dev.charaly.runtime.presentation.HomeSnapshot
import dev.charaly.runtime.presentation.ModelStatusCard
import dev.charaly.runtime.presentation.PackCard
import dev.charaly.runtime.presentation.SessionCard

/**
 * Home.
 *
 * A launchpad, in a deliberate priority order:
 *   1. continue the story you were in,
 *   2. the worlds you can step into,
 *   3. what you were recently doing,
 *   4. a quiet line telling you whether a model is ready.
 *
 * There is no story-writing interface here and no raw pack data: Home is the one
 * screen that is allowed to be a bit sparse, because everything on it is a decision
 * rather than a tool.
 */
@Composable
fun HomeScreen(
    snapshot: HomeSnapshot,
    loading: Boolean,
    onOpenSettings: () -> Unit,
    onOpenLibrary: () -> Unit,
    onCreatePack: () -> Unit,
    onContinueStory: (String) -> Unit,
    onOpenPack: (String) -> Unit,
    onOpenSession: (String) -> Unit,
    onOpenSessions: () -> Unit,
    onOpenModels: () -> Unit,
) {
    if (loading) {
        HomeSkeleton()
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .devCenteredContent(),
        contentPadding = PaddingValues(bottom = Charaly.tokens.spacing.section),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item(key = "greeting") {
            HomeGreeting(
                greeting = snapshot.greeting,
                subtitle = snapshot.subtitle,
                onOpenSettings = onOpenSettings,
            )
        }

        snapshot.continueCard?.let { card ->
            item(key = "continue") {
                ContinueStoryCard(
                    card = card,
                    onContinue = { onContinueStory(card.storyId) },
                )
            }
        }

        item(key = "worlds-header") {
            SectionHeader(
                title = "Your worlds",
                subtitle = if (snapshot.packs.isEmpty()) {
                    "No story packs yet"
                } else {
                    "${snapshot.packs.size} worlds to step into"
                },
                modifier = Modifier.padding(
                    start = Charaly.tokens.spacing.gutter,
                    end = Charaly.tokens.spacing.gutter,
                    top = Charaly.tokens.spacing.section,
                ),
                action = {
                    CharalyIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "See all story packs",
                        // The arrow means "show me the library", not "open settings".
                        onClick = onOpenLibrary,
                    )
                },
            )
        }

        if (snapshot.packs.isEmpty()) {
            item(key = "worlds-empty") {
                FirstRunCard(onCreatePack = onCreatePack)
            }
        } else {
            item(key = "worlds-row") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Charaly.tokens.spacing.gutter),
                    horizontalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.sm),
                ) {
                    items(snapshot.packs, key = { it.id }) { pack ->
                        HomePackCard(pack = pack, onClick = { onOpenPack(pack.id) })
                    }
                }
            }
        }

        if (snapshot.recentSessions.isNotEmpty()) {
            item(key = "recent-header") {
                SectionHeader(
                    title = "Recent sessions",
                    modifier = Modifier.padding(
                        start = Charaly.tokens.spacing.gutter,
                        end = Charaly.tokens.spacing.gutter,
                        top = Charaly.tokens.spacing.section,
                    ),
                    action = {
                        CharalyGhostButton(
                            label = "All",
                            onClick = onOpenSessions,
                        )
                    },
                )
            }
            items(snapshot.recentSessions, key = { it.id }) { session ->
                RecentSessionRow(
                    session = session,
                    onClick = { onOpenSession(session.id) },
                    modifier = Modifier.padding(
                        horizontal = Charaly.tokens.spacing.gutter,
                        vertical = 5.dp,
                    ),
                )
            }
        }

        item(key = "model") {
            ModelStatusStrip(
                card = snapshot.modelStatus,
                onClick = onOpenModels,
                modifier = Modifier.padding(
                    start = Charaly.tokens.spacing.gutter,
                    end = Charaly.tokens.spacing.gutter,
                    top = Charaly.tokens.spacing.section,
                ),
            )
        }
    }
}

@Composable
private fun HomeGreeting(
    greeting: String,
    subtitle: String,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = Charaly.tokens.spacing.gutter,
                end = Charaly.tokens.spacing.gutter,
                top = Charaly.tokens.spacing.xl,
                bottom = Charaly.tokens.spacing.lg,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Eyebrow("Charaly")
            Text(
                text = greeting,
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        ProfileButton(onClick = onOpenSettings)
    }
}

/**
 * The one big thing on Home.
 *
 * It shows a companion rather than a pack: "who was I talking to" is the question a
 * returning user actually has.
 */
@Composable
private fun ContinueStoryCard(
    card: ContinueCard,
    onContinue: () -> Unit,
) {
    val accent = card.theme.primary.toColor()
    Box(
        modifier = Modifier
            .padding(horizontal = Charaly.tokens.spacing.gutter)
            .fillMaxWidth()
            .height(214.dp)
            .clip(Charaly.tokens.radii.shapeXl),
    ) {
        ArtworkHero(
            artwork = dev.charaly.runtime.domain.PackArtwork.generated(seed = card.packId),
            theme = card.theme,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Charaly.tokens.spacing.lg),
            ) {
                Eyebrow("Continue story", color = accent)
                Spacer(Modifier.height(Charaly.tokens.spacing.md))
                Text(
                    text = card.storyTitle,
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = card.packTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CharacterAvatar(
                        seed = card.companionName,
                        accent = card.companionAccent.toColor(),
                        name = card.companionName,
                        modifier = Modifier.size(40.dp),
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = Charaly.tokens.spacing.sm),
                    ) {
                        Text(
                            text = card.companionName,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = listOf(card.locationLine, card.lastPlayedLabel)
                                .filter { it.isNotBlank() }
                                .joinToString("  ·  "),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.7f),
                            maxLines = 1,
                        )
                    }
                    CharalyPrimaryButton(
                        label = "Continue",
                        icon = Icons.Filled.PlayArrow,
                        onClick = onContinue,
                        container = accent,
                        contentColor = Charaly.accent.onAccent,
                    )
                }
            }
        }
    }
}

/** A compact pack card for the Home carousel. */
@Composable
private fun HomePackCard(pack: PackCard, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(168.dp)
            .height(238.dp)
            .clip(Charaly.tokens.radii.shapeLg)
            .semantics { contentDescription = "${pack.title}. ${pack.tagline}" },
    ) {
        ArtworkHero(
            artwork = pack.artwork,
            theme = pack.theme,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Charaly.tokens.spacing.sm),
            ) {
                Spacer(Modifier.weight(1f))
                Text(
                    text = pack.genres.take(2).joinToString(" · ").uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.66f),
                    maxLines = 1,
                )
                Text(
                    text = pack.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    text = "${pack.characterCount} characters  ·  ${pack.locationCount} places",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.62f),
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(Charaly.tokens.radii.shapeLg)
                .androidxClickable(onClick),
        )
    }
}

/** One recent session, dense enough to show four on a phone. */
@Composable
private fun RecentSessionRow(
    session: SessionCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CharalyCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        container = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOf(session.locationName, session.lastPlayedLabel)
                        .filter { it.isNotBlank() }
                        .joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (session.chapterTitle.isNotBlank()) {
                    Text(
                        text = session.chapterTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = Charaly.accent.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            if (session.castNames.isNotEmpty()) {
                CastStack(
                    names = session.castNames,
                    accents = session.castAccents,
                    modifier = Modifier.padding(start = Charaly.tokens.spacing.sm),
                )
            }
        }
    }
}

/**
 * The model status line.
 *
 * One sentence, one colour, one action. It is the only place a user learns that a
 * story cannot generate yet, and it links straight to the fix.
 */
@Composable
private fun ModelStatusStrip(
    card: ModelStatusCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusColor = if (card.isReady) Charaly.colors.success else Charaly.colors.warning
    CharalyCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        container = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(statusColor),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Charaly.tokens.spacing.sm),
            ) {
                Text(
                    text = card.modelName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${card.stateLabel}  ·  ${card.detailLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CharalyIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Open models",
                onClick = onClick,
            )
        }
    }
}

/** Shown on a fresh install with no packs at all. */
@Composable
private fun FirstRunCard(onCreatePack: () -> Unit) {
    CharalyCard(
        modifier = Modifier.padding(horizontal = Charaly.tokens.spacing.gutter),
        container = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            Text(
                text = "No stories yet.",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Choose a Story Pack and step into your first world.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            CharalyPrimaryButton(
                label = "Create Story Pack",
                onClick = onCreatePack,
                modifier = Modifier.padding(top = Charaly.tokens.spacing.md),
            )
        }
    }
}

/** Overlapping avatars, small enough for a dense row. */
@Composable
fun CastStack(
    names: List<String>,
    accents: List<Long>,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 24.dp,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy((-size * 0.3f))) {
        names.take(3).forEachIndexed { index, name ->
            CharacterAvatar(
                seed = name,
                accent = accents.getOrNull(index)?.toColor() ?: Charaly.accent.primary,
                name = name,
                modifier = Modifier
                    .size(size)
                    .androidxRing(MaterialTheme.colorScheme.background),
            )
        }
    }
}

@Composable
private fun HomeSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Charaly.tokens.spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.md),
    ) {
        dev.charaly.app.ui.components.SkeletonBlock(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(38.dp),
        )
        dev.charaly.app.ui.components.SkeletonBlock(
            modifier = Modifier
                .fillMaxWidth()
                .height(214.dp),
            shape = Charaly.tokens.radii.shapeXl,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.sm)) {
            repeat(3) {
                dev.charaly.app.ui.components.SkeletonBlock(
                    modifier = Modifier
                        .width(168.dp)
                        .height(238.dp),
                    shape = Charaly.tokens.radii.shapeLg,
                )
            }
        }
        dev.charaly.app.ui.components.SkeletonBlock(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            shape = Charaly.tokens.radii.shapeLg,
        )
    }
}

private fun Modifier.androidxRing(color: Color): Modifier =
    this.border(1.5.dp, color, CircleShape)

private fun Modifier.androidxClickable(onClick: () -> Unit): Modifier =
    this.clickable { onClick() }
