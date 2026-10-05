package dev.charaly.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.charaly.app.ui.art.ArtworkTile
import dev.charaly.app.ui.art.CharacterAvatar
import dev.charaly.app.ui.components.CharalyIconButton
import dev.charaly.app.ui.components.CharalyPrimaryButton
import dev.charaly.app.ui.components.Eyebrow
import dev.charaly.app.ui.components.SectionHeader
import dev.charaly.app.ui.devCenteredContent
import dev.charaly.app.ui.theme.Charaly
import dev.charaly.app.ui.theme.toColor
import dev.charaly.runtime.presentation.ResolvedTheme
import dev.charaly.runtime.presentation.WorldLocation
import dev.charaly.runtime.presentation.WorldPerson
import dev.charaly.runtime.presentation.WorldSnapshot

/**
 * The world as a browsable place list.
 *
 * This screen exists because "a local interactive universe simulator" means the user
 * walks *into* a world rather than picking a character to chat with. A location with a
 * door is something you enter; a chat list is something you submit to.
 *
 * Every place shows who is in it *right now*, from authoritative world state - so
 * walking into the ice cream shop genuinely surprises you with a man behind the counter
 * rather than with a roster you already picked from.
 */
/**
 * The world as a browsable place list.
 *
 * This screen exists because "a local interactive universe simulator" means the user
 * walks *into* a world rather than picking a character to chat with. A location with a
 * door is something you enter; a chat list is something you submit to.
 *
 * Every place shows who is in it *right now*, from authoritative world state - so
 * walking into the ice cream shop genuinely surprises you with a man behind the counter
 * rather than with a roster you already picked from.
 */
data class WorldLocationCard(
    val id: String,
    val name: String,
    val blurb: String,
    val artwork: dev.charaly.runtime.domain.PackArtwork,
    val accent: Long,
    /** Ids of the characters standing here at this moment. */
    val presentCharacterIds: List<String>,
    val presentCharacterNames: List<String>,
    /** How the world refers to the unnamed remainder of a crowd. */
    val backgroundCount: Int,
    val isHere: Boolean,
)

/** One character as the world screen shows them: here, doing something. */
data class WorldCharacterCard(
    val id: String,
    val name: String,
    val role: String,
    val locationName: String,
    val activityLabel: String,
    val accent: Long,
    val artwork: dev.charaly.runtime.domain.PackArtwork,
    val isHere: Boolean,
    /** "stranger" / "a friend" - relationship stage in the user's language. */
    val relationshipLabel: String,
)

/** Everything the world screen needs, already resolved from authoritative state. */
data class WorldSnapshot(
    val worldTitle: String,
    val currentLocationName: String,
    val timeLabel: String,
    val locations: List<WorldLocationCard>,
    val characters: List<WorldCharacterCard>,
) {
    val isEmpty: Boolean get() = locations.isEmpty() && characters.isEmpty()

    /** Everyone standing where the player currently is. */
    fun peopleHere(): List<WorldCharacterCard> = characters.filter { it.isHere }
}

@Composable
fun WorldScreen(
    snapshot: WorldSnapshot,
    onBack: () -> Unit,
    onEnterLocation: (String) -> Unit,
    onOpenCharacter: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (snapshot.isEmpty) {
        WorldEmptyState(onBack = onBack, modifier = modifier)
        return
    }
    val locations = snapshot.locations
    val characters = snapshot.characters
    val worldTitle = snapshot.worldTitle
    val currentLocationName = snapshot.currentLocationName
    val timeLabel = snapshot.timeLabel

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = Charaly.tokens.spacing.section),
    ) {
        item(key = "world-header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Charaly.tokens.spacing.xs, vertical = Charaly.tokens.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CharalyIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Leave the world",
                    onClick = onBack,
                )
                Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Text(
                        text = worldTitle,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = listOf(currentLocationName, timeLabel)
                            .filter { it.isNotBlank() }
                            .joinToString("  ·  "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }

        // Places first. This is the "you enter a world" direction: a door, not a list.
        if (locations.isNotEmpty()) {
            item(key = "places-header") {
                SectionHeader(
                    title = "Places",
                    subtitle = "Somewhere has people in it right now.",
                    modifier = Modifier.padding(
                        start = Charaly.tokens.spacing.gutter,
                        end = Charaly.tokens.spacing.gutter,
                        top = Charaly.tokens.spacing.md,
                    ),
                )
            }
            item(key = "places") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Charaly.tokens.spacing.gutter),
                    horizontalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.xs),
                ) {
                    items(locations, key = { it.id }) { place ->
                        PlaceTile(
                            card = place,
                            onClick = { onEnterLocation(place.id) },
                        )
                    }
                }
            }
        }

        if (characters.isNotEmpty()) {
            item(key = "people-header") {
                SectionHeader(
                    title = "People",
                    subtitle = "Everyone here, whether you have met them or not.",
                    modifier = Modifier.padding(
                        start = Charaly.tokens.spacing.gutter,
                        end = Charaly.tokens.spacing.gutter,
                        top = Charaly.tokens.spacing.xl,
                    ),
                )
            }
            items(characters, key = { "person-${it.id}" }) { person ->
                PersonRow(
                    card = person,
                    onClick = { onOpenCharacter(person.id) },
                    modifier = Modifier.padding(
                        horizontal = Charaly.tokens.spacing.gutter,
                        vertical = 4.dp,
                    ),
                )
            }
        }
    }
}

/**
 * A place, with its current occupants.
 *
 * The occupant line is the whole point: it is what makes walking somewhere feel like
 * discovering a populated city rather than picking a destination.
 */
@Composable
private fun PlaceTile(
    card: WorldLocation,
    onClick: () -> Unit,
) {
    val theme = ResolvedTheme.BRAND
    Column(
        modifier = Modifier
            .width(168.dp)
            .clip(Charaly.tokens.radii.shapeLg)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable { onClick() }
            .semantics {
                contentDescription = buildString {
                    append(card.name)
                    if (card.presentCharacterNames.isNotEmpty()) {
                        append(". Here now: ")
                        append(card.presentCharacterNames.joinToString(", "))
                    }
                }
            },
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(104.dp)) {
            ArtworkTile(
                artwork = card.artwork,
                theme = theme,
                aspect = 0f,
                modifier = Modifier.fillMaxWidth().height(104.dp),
            ) {
                if (card.isHere) {
                    Eyebrow(
                        text = "You are here",
                        color = Charaly.accent.accent,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
        Column(Modifier.padding(Charaly.tokens.spacing.sm)) {
            Text(
                text = card.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (card.blurb.isNotBlank()) {
                Text(
                    text = card.blurb,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            OccupancyLine(
                text = card.occupancyLine(),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * "André, and 2 others" - never an empty state for a place that should feel busy.
 *
 * The text comes from the presenter, which derives it from authoritative world state.
 * A place nobody is in reads "Empty right now", which is a fact about the world rather
 * than a missing field.
 */
@Composable
private fun OccupancyLine(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = Charaly.accent.accent,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.semantics { contentDescription = "Present: $text" },
    )
}

/** A person, where they are, and what they are doing - the "living NPC" proof. */
@Composable
private fun PersonRow(
    card: WorldPerson,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(Charaly.tokens.radii.shapeMd)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable { onClick() }
            .padding(Charaly.tokens.spacing.sm)
            .semantics {
                contentDescription = buildString {
                    append(card.name)
                    if (card.role.isNotBlank()) append(", ${card.role}")
                    append(", at ${card.locationName}")
                    if (card.activityLabel.isNotBlank()) append(", ${card.activityLabel}")
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CharacterAvatar(
            seed = card.id,
            accent = card.accent.toColor(),
            name = card.name,
            modifier = Modifier.size(42.dp),
        )
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = card.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (card.isHere) {
                    Eyebrow(
                        text = "here",
                        color = Charaly.accent.accent,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            Text(
                text = listOfNotNull(
                    card.role.takeIf { it.isNotBlank() },
                    card.locationName.takeIf { it.isNotBlank() },
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (card.activityLabel.isNotBlank()) {
                Text(
                    text = card.activityLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = card.accent.toColor(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun WorldEmptyState(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(Charaly.tokens.spacing.gutter),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        androidx.compose.material3.Icon(
            imageVector = Icons.Filled.Person,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = "This world is still being written.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = Charaly.tokens.spacing.md),
        )
        Text(
            text = "There are no places or people to show yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        CharalyPrimaryButton(
            label = "Back",
            onClick = onBack,
            modifier = Modifier.padding(top = Charaly.tokens.spacing.lg),
        )
    }
}

