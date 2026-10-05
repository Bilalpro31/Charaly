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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.charaly.app.ui.devCenteredContent
import dev.charaly.app.ui.art.ArtworkHero
import dev.charaly.app.ui.art.CharacterAvatar
import dev.charaly.app.ui.components.BodyProse
import dev.charaly.app.ui.components.CharalyCard
import dev.charaly.app.ui.components.CharalyFilterChip
import dev.charaly.app.ui.components.CharalyGhostButton
import dev.charaly.app.ui.components.CharalyIconButton
import dev.charaly.app.ui.components.CharalyPrimaryButton
import dev.charaly.app.ui.components.CharalyTextButton
import dev.charaly.app.ui.components.DetailRow
import dev.charaly.app.ui.components.EmptyStateView
import dev.charaly.app.ui.components.Eyebrow
import dev.charaly.app.ui.components.GenreChip
import dev.charaly.app.ui.components.SectionHeader
import dev.charaly.app.ui.components.SkeletonList
import dev.charaly.app.ui.components.StatPill
import dev.charaly.app.ui.theme.Charaly
import dev.charaly.app.ui.theme.toColor
import dev.charaly.runtime.presentation.CharacterCard
import dev.charaly.runtime.presentation.EventCard
import dev.charaly.runtime.presentation.FactionCard
import dev.charaly.runtime.presentation.LocationCard
import dev.charaly.runtime.presentation.LoreCard
import dev.charaly.runtime.presentation.PackDetailSnapshot
import dev.charaly.runtime.presentation.PersonaCard
import dev.charaly.runtime.presentation.ScenarioCard
import dev.charaly.runtime.presentation.SessionCard
import dev.charaly.runtime.presentation.ThreadCard

/**
 * Story Pack detail: the universe overview.
 *
 * Tapping a pack does NOT start a chat. It opens here first, so "entering a world"
 * feels like arriving somewhere rather than launching an app. The only two actions
 * on this screen are [Continue] and [New Story], and they are at the very top,
 * directly under the hero.
 */
@Composable
fun PackDetailScreen(
    snapshot: PackDetailSnapshot?,
    loading: Boolean,
    onBack: () -> Unit,
    onContinue: (String) -> Unit,
    onNewStory: () -> Unit,
    onOpenCharacter: (String) -> Unit,
    onOpenLocation: (String) -> Unit,
    onOpenSession: (String) -> Unit,
    onOpenModels: () -> Unit,
    onEdit: (PackEditorTarget, String) -> Unit,
) {
    if (snapshot == null) {
        DetailMissingState(loading = loading, onBack = onBack)
        return
    }

    Box(
        Modifier
            .fillMaxSize()
            .devCenteredContent(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = Charaly.tokens.spacing.section),
        ) {
            item(key = "hero") {
                PackHero(snapshot = snapshot, onBack = onBack)
            }

            item(key = "actions") {
                Row(
                    modifier = Modifier.padding(
                        start = Charaly.tokens.spacing.gutter,
                        end = Charaly.tokens.spacing.gutter,
                        top = Charaly.tokens.spacing.md,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.xs),
                ) {
                    CharalyPrimaryButton(
                        label = "Continue",
                        icon = Icons.Filled.PlayArrow,
                        onClick = { snapshot.activeStories.firstOrNull()?.let { onContinue(it.id) } },
                        enabled = snapshot.canContinue,
                        modifier = Modifier.weight(1f),
                    )
                    CharalyGhostButton(
                        label = "New Story",
                        icon = Icons.Filled.Add,
                        onClick = onNewStory,
                        modifier = Modifier.weight(1f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (!snapshot.canContinue) {
                    Text(
                        text = "You have not stepped into this world yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            start = Charaly.tokens.spacing.gutter,
                            top = 6.dp,
                        ),
                    )
                }
            }

            item(key = "stats") {
                LazyRow(
                    contentPadding = PaddingValues(
                        start = Charaly.tokens.spacing.gutter,
                        end = Charaly.tokens.spacing.gutter,
                        top = Charaly.tokens.spacing.md,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.xs),
                ) {
                    items(snapshot.stats.size) { index ->
                        val stat = snapshot.stats[index]
                        StatPill(label = stat.label, value = stat.value)
                    }
                }
            }

            if (snapshot.fandomNotice.isNotBlank()) {
                item(key = "notice") {
                    NoticeCard(
                        text = snapshot.fandomNotice,
                        modifier = Modifier.padding(
                            horizontal = Charaly.tokens.spacing.gutter,
                            vertical = Charaly.tokens.spacing.sm,
                        ),
                    )
                }
            }

            if (snapshot.characters.isNotEmpty()) {
                item(key = "characters-header") {
                    SectionHeader(
                        title = "Characters",
                        subtitle = "Who lives in this world",
                        modifier = Modifier.padding(
                            start = Charaly.tokens.spacing.gutter,
                            end = Charaly.tokens.spacing.gutter,
                            top = Charaly.tokens.spacing.md,
                        ),
                    )
                }
                item(key = "characters-row") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = Charaly.tokens.spacing.gutter),
                        horizontalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.sm),
                    ) {
                        items(snapshot.characters, key = { it.id }) { character ->
                            CharacterTile(
                                character = character,
                                onClick = { onOpenCharacter(character.id) },
                                onEdit = { onEdit(PackEditorTarget.CHARACTER, character.id) },
                            )
                        }
                    }
                }
            }

            if (snapshot.locations.isNotEmpty()) {
                item(key = "locations-header") {
                    SectionHeader(
                        title = "Locations",
                        subtitle = "Places you can end up",
                        modifier = Modifier.padding(
                            start = Charaly.tokens.spacing.gutter,
                            end = Charaly.tokens.spacing.gutter,
                            top = Charaly.tokens.spacing.section,
                        ),
                    )
                }
                item(key = "locations-row") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = Charaly.tokens.spacing.gutter),
                        horizontalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.sm),
                    ) {
                        items(snapshot.locations, key = { it.id }) { location ->
                            LocationTile(
                                location = location,
                                onClick = { onOpenLocation(location.id) },
                            )
                        }
                    }
                }
            }

            if (snapshot.threads.isNotEmpty()) {
                item(key = "threads-header") {
                    SectionHeader(
                        title = "Story threads",
                        subtitle = "What is already going on",
                        modifier = Modifier.padding(
                            start = Charaly.tokens.spacing.gutter,
                            end = Charaly.tokens.spacing.gutter,
                            top = Charaly.tokens.spacing.section,
                        ),
                    )
                }
                items(snapshot.threads, key = { it.id }) { thread ->
                    ThreadTile(
                        thread = thread,
                        modifier = Modifier.padding(
                            horizontal = Charaly.tokens.spacing.gutter,
                            vertical = 5.dp,
                        ),
                    )
                }
            }

            if (snapshot.worldOverview().isNotBlank()) {
                item(key = "world-header") {
                    SectionHeader(
                        title = "World",
                        modifier = Modifier.padding(
                            start = Charaly.tokens.spacing.gutter,
                            end = Charaly.tokens.spacing.gutter,
                            top = Charaly.tokens.spacing.section,
                        ),
                    )
                }
                item(key = "world-body") {
                    Column(
                        modifier = Modifier.padding(
                            horizontal = Charaly.tokens.spacing.gutter,
                            vertical = Charaly.tokens.spacing.xs,
                        ),
                    ) {
                        BodyProse(snapshot.worldOverview())
                        if (snapshot.era.isNotBlank() || snapshot.tone.isNotBlank()) {
                            Text(
                                text = listOf(snapshot.era, snapshot.tone)
                                    .filter { it.isNotBlank() }
                                    .joinToString("  ·  "),
                                style = MaterialTheme.typography.labelMedium,
                                color = Charaly.accent.accent,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }
                    }
                }
            }

            if (snapshot.factions.isNotEmpty()) {
                item(key = "factions-header") {
                    SectionHeader(
                        title = "Factions",
                        modifier = Modifier.padding(
                            start = Charaly.tokens.spacing.gutter,
                            end = Charaly.tokens.spacing.gutter,
                            top = Charaly.tokens.spacing.section,
                        ),
                    )
                }
                items(snapshot.factions, key = { it.id }) { faction ->
                    FactionTile(
                        faction = faction,
                        modifier = Modifier.padding(
                            horizontal = Charaly.tokens.spacing.gutter,
                            vertical = 5.dp,
                        ),
                    )
                }
            }

            if (snapshot.events.isNotEmpty()) {
                item(key = "events-header") {
                    SectionHeader(
                        title = "Events",
                        subtitle = "What this world does on its own",
                        modifier = Modifier.padding(
                            start = Charaly.tokens.spacing.gutter,
                            end = Charaly.tokens.spacing.gutter,
                            top = Charaly.tokens.spacing.section,
                        ),
                    )
                }
                items(snapshot.events, key = { it.id }) { event ->
                    EventTile(
                        event = event,
                        modifier = Modifier.padding(
                            horizontal = Charaly.tokens.spacing.gutter,
                            vertical = 5.dp,
                        ),
                        onEdit = { onEdit(PackEditorTarget.EVENT, event.id) },
                    )
                }
            }

            if (snapshot.scenarios.isNotEmpty()) {
                item(key = "scenarios-header") {
                    SectionHeader(
                        title = "Ways in",
                        subtitle = "Each new story can begin differently",
                        modifier = Modifier.padding(
                            start = Charaly.tokens.spacing.gutter,
                            end = Charaly.tokens.spacing.gutter,
                            top = Charaly.tokens.spacing.section,
                        ),
                    )
                }
                items(snapshot.scenarios, key = { it.id }) { scenario ->
                    ScenarioTile(
                        scenario = scenario,
                        modifier = Modifier.padding(
                            horizontal = Charaly.tokens.spacing.gutter,
                            vertical = 5.dp,
                        ),
                    )
                }
            }

            if (snapshot.personas.isNotEmpty()) {
                item(key = "personas-header") {
                    SectionHeader(
                        title = "Roles",
                        subtitle = "Who you could be in this world",
                        modifier = Modifier.padding(
                            start = Charaly.tokens.spacing.gutter,
                            end = Charaly.tokens.spacing.gutter,
                            top = Charaly.tokens.spacing.section,
                        ),
                    )
                }
                items(snapshot.personas, key = { it.id }) { persona ->
                    PersonaTile(
                        persona = persona,
                        modifier = Modifier.padding(
                            horizontal = Charaly.tokens.spacing.gutter,
                            vertical = 5.dp,
                        ),
                    )
                }
            }

            if (snapshot.lore.isNotEmpty()) {
                item(key = "lore-header") {
                    SectionHeader(
                        title = "Lore",
                        modifier = Modifier.padding(
                            start = Charaly.tokens.spacing.gutter,
                            end = Charaly.tokens.spacing.gutter,
                            top = Charaly.tokens.spacing.section,
                        ),
                    )
                }
                items(snapshot.lore, key = { it.id }) { lore ->
                    LoreTile(
                        lore = lore,
                        modifier = Modifier.padding(
                            horizontal = Charaly.tokens.spacing.gutter,
                            vertical = 5.dp,
                        ),
                    )
                }
            }

            if (snapshot.activeStories.isNotEmpty()) {
                item(key = "stories-header") {
                    SectionHeader(
                        title = "Active stories",
                        subtitle = "Your playthroughs of this world",
                        modifier = Modifier.padding(
                            start = Charaly.tokens.spacing.gutter,
                            end = Charaly.tokens.spacing.gutter,
                            top = Charaly.tokens.spacing.section,
                        ),
                    )
                }
                items(snapshot.activeStories, key = { it.id }) { story ->
                    ActiveStoryTile(
                        story = story,
                        onOpen = { onOpenSession(story.id) },
                        modifier = Modifier.padding(
                            horizontal = Charaly.tokens.spacing.gutter,
                            vertical = 5.dp,
                        ),
                    )
                }
            }

            item(key = "model-note") {
                ModelProfileNote(
                    profileName = snapshot.defaultProfileName,
                    hasModel = snapshot.activeStories.any { it.modelName != NO_MODEL_LABEL },
                    onOpenModels = onOpenModels,
                    modifier = Modifier.padding(
                        start = Charaly.tokens.spacing.gutter,
                        end = Charaly.tokens.spacing.gutter,
                        top = Charaly.tokens.spacing.section,
                    ),
                )
            }
        }
    }
}

/** Title block over the artwork, with a back affordance. */
@Composable
private fun PackHero(snapshot: PackDetailSnapshot, onBack: () -> Unit) {
    // Layout is decided by HeroPresenter, not guessed at measure time: the tagline is
    // trimmed to a budget and the hero grows to fit it. That is what removes overlap
    // rather than hiding it behind an ellipsis mid-render.
    val hero = dev.charaly.runtime.presentation.HeroPresenter.forContent(
        eyebrow = snapshot.genres.take(3).joinToString(" · ").ifBlank { "Story pack" },
        title = snapshot.title,
        tagline = snapshot.tagline,
        chips = snapshot.genres,
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(hero.heightDp.dp),
    ) {
        ArtworkHero(
            artwork = snapshot.artwork,
            theme = snapshot.theme,
            modifier = Modifier.fillMaxSize(),
            scrimStrength = 0.92f,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = Charaly.tokens.spacing.gutter,
                        end = Charaly.tokens.spacing.gutter,
                        top = Charaly.tokens.spacing.xxl,
                        bottom = Charaly.tokens.spacing.md,
                    ),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CharalyIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        onClick = onBack,
                        container = Color.Black.copy(alpha = 0.32f),
                        tint = Color.White,
                    )
                }
                Spacer(Modifier.weight(1f))
                Eyebrow(
                    text = hero.eyebrow,
                    color = snapshot.theme.accent.toColor(),
                )
                Text(
                    text = hero.title,
                    // A long title steps down a size rather than overflowing or being
                    // silently cut. The decision is in HeroPresenter; this just applies it.
                    style = if (hero.title.length > dev.charaly.runtime.presentation.HeroPresenter.LONG_TITLE_CHARS) {
                        MaterialTheme.typography.headlineLarge
                    } else {
                        MaterialTheme.typography.displaySmall
                    },
                    color = Color.White,
                    modifier = Modifier.padding(top = 6.dp),
                )
                if (hero.tagline.isNotBlank()) {
                    Text(
                        text = hero.tagline,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.82f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (hero.chips.isNotEmpty()) {
                    Row(
                        modifier = Modifier.padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        hero.chips.forEach { GenreChip(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CharacterTile(
    character: CharacterCard,
    onClick: () -> Unit,
    onEdit: () -> Unit,
) {
    CharalyCard(
        onClick = onClick,
        modifier = Modifier.width(150.dp),
        container = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        CharacterAvatar(
            seed = character.name,
            accent = character.accent.toColor(),
            name = character.name,
            modifier = Modifier.size(52.dp),
        )
        Text(
            text = character.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            text = character.tagline.ifBlank { character.role },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp),
        )
        if (character.locationName.isNotBlank()) {
            Text(
                text = character.locationName,
                style = MaterialTheme.typography.labelSmall,
                color = character.accent.toColor(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        CharalyTextButton(
            label = "Edit",
            onClick = onEdit,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, start = 0.dp),
        )
    }
}

@Composable
private fun LocationTile(location: LocationCard, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(168.dp)
            .height(122.dp)
            .clip(Charaly.tokens.radii.shapeLg)
            .clickable { onClick() },
    ) {
        ArtworkHero(
            artwork = location.artwork,
            theme = dev.charaly.runtime.presentation.ResolvedTheme(
                primary = location.accent,
                secondary = location.accent,
                accent = location.accent,
                ink = 0xFFFFFFFF,
                surface = 0xFF16151C,
                mood = "",
            ),
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Charaly.tokens.spacing.sm),
            ) {
                Spacer(Modifier.weight(1f))
                Text(
                    text = location.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = location.summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ThreadTile(thread: ThreadCard, modifier: Modifier = Modifier) {
    CharalyCard(modifier = modifier.fillMaxWidth(), container = MaterialTheme.colorScheme.surfaceContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(
                        if (thread.isOpen) {
                            Charaly.accent.primary.copy(alpha = 0.18f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = thread.stage.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (thread.isOpen) {
                        Charaly.accent.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = Charaly.tokens.spacing.sm),
            ) {
                Text(
                    text = thread.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = thread.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (thread.participantNames.isNotEmpty()) {
                    Text(
                        text = thread.participantNames.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = Charaly.accent.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Text(
                text = thread.statusLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FactionTile(faction: FactionCard, modifier: Modifier = Modifier) {
    CharalyCard(modifier = modifier.fillMaxWidth(), container = MaterialTheme.colorScheme.surfaceContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(faction.accent.toColor()),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = Charaly.tokens.spacing.sm),
            ) {
                Text(
                    text = faction.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (faction.motto.isNotBlank()) {
                    Text(
                        text = faction.motto,
                        style = MaterialTheme.typography.bodySmall,
                        color = faction.accent.toColor(),
                    )
                }
                Text(
                    text = faction.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (faction.memberNames.isNotEmpty()) {
                    Text(
                        text = faction.memberNames.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * An authored event, described in the same words the engine uses.
 *
 * Showing "move marinette to rooftop" next to "when the world lines up" is how a
 * pack author understands their own pack, and it is how a reader understands that
 * the world moves on its own.
 */
@Composable
private fun EventTile(event: EventCard, modifier: Modifier = Modifier, onEdit: () -> Unit) {
    CharalyCard(modifier = modifier.fillMaxWidth(), container = MaterialTheme.colorScheme.surfaceContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = event.triggerLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = Charaly.accent.accent,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (event.description.isNotBlank()) {
                    Text(
                        text = event.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            CharalyTextButton(
                label = "Edit",
                onClick = onEdit,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (event.effects.isNotEmpty()) {
            Text(
                text = event.effects.joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun ScenarioTile(scenario: ScenarioCard, modifier: Modifier = Modifier) {
    CharalyCard(modifier = modifier.fillMaxWidth(), container = MaterialTheme.colorScheme.surfaceContainer) {
        Text(
            text = scenario.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = scenario.tagline,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            text = "${scenario.timeLabel}  ·  ${scenario.locationName}",
            style = MaterialTheme.typography.labelSmall,
            color = Charaly.accent.accent,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (scenario.castNames.isNotEmpty()) {
            Text(
                text = scenario.castNames.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

@Composable
private fun PersonaTile(persona: PersonaCard, modifier: Modifier = Modifier) {
    CharalyCard(modifier = modifier.fillMaxWidth(), container = MaterialTheme.colorScheme.surfaceContainer) {
        Text(
            text = persona.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = persona.tagline,
            style = MaterialTheme.typography.labelMedium,
            color = Charaly.accent.accent,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            text = persona.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun LoreTile(lore: LoreCard, modifier: Modifier = Modifier) {
    CharalyCard(modifier = modifier.fillMaxWidth(), container = MaterialTheme.colorScheme.surfaceContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = lore.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (lore.isSecret) {
                Text(
                    text = "SECRET",
                    style = MaterialTheme.typography.labelSmall,
                    color = Charaly.accent.secondary,
                    modifier = Modifier
                        .clip(CircleShape)
                        .border(1.dp, Charaly.accent.secondary.copy(alpha = 0.5f), CircleShape)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        Text(
            text = lore.content,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun ActiveStoryTile(
    story: SessionCard,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CharalyCard(onClick = onOpen, modifier = modifier.fillMaxWidth(), container = MaterialTheme.colorScheme.surfaceContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = story.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${story.locationName}  ·  ${story.lastPlayedLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (story.chapterTitle.isNotBlank()) {
                    Text(
                        text = story.chapterTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = Charaly.accent.accent,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            CharalyIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Open ${story.title}",
                onClick = onOpen,
            )
        }
    }
}

@Composable
private fun NoticeCard(text: String, modifier: Modifier = Modifier) {
    CharalyCard(
        modifier = modifier.fillMaxWidth(),
        container = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Eyebrow("About this pack")
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun ModelProfileNote(
    profileName: String,
    hasModel: Boolean,
    onOpenModels: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CharalyCard(modifier = modifier.fillMaxWidth(), container = MaterialTheme.colorScheme.surfaceContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (hasModel) "Stories here run on your local model" else "A model is needed to play",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (hasModel) {
                        "Default profile: $profileName. You can change it per story."
                    } else {
                        "Charaly generates replies on this device with a GGUF model."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            CharalyTextButton(label = "Open models", onClick = onOpenModels)
        }
    }
}

@Composable
private fun DetailMissingState(loading: Boolean, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        if (loading) {
            SkeletonList(count = 3, modifier = Modifier.padding(top = 90.dp))
        } else {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.padding(
                        start = Charaly.tokens.spacing.xs,
                        top = Charaly.tokens.spacing.xl,
                    ),
                ) {
                    CharalyIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        onClick = onBack,
                    )
                }
                EmptyStateView(
                    state = dev.charaly.runtime.presentation.EmptyState(
                        title = "That world is not here.",
                        body = "It may have been deleted. Go back and pick another.",
                        actionLabel = "Back to library",
                        artSeed = "charaly-empty-pack",
                    ),
                    modifier = Modifier.padding(top = 40.dp),
                ) {
                    CharalyPrimaryButton(label = "Back", onClick = onBack)
                }
            }
        }
    }
}

/** The one place the app admits a story has no model yet. */
private const val NO_MODEL_LABEL = "No model bound"

/** The short world overview shown under the World heading. */
internal fun PackDetailSnapshot.worldOverview(): String {
    val parts = mutableListOf<String>()
    if (description.isNotBlank()) parts += description
    val loreText = lore.sortedByDescending { it.importance }.firstOrNull()?.content
    if (!loreText.isNullOrBlank()) parts += loreText
    return parts.joinToString("\n\n")
}