package dev.charaly.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
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
import dev.charaly.app.ui.components.SectionHeader
import dev.charaly.app.ui.components.StatusDot
import dev.charaly.app.ui.components.TinySpinner
import dev.charaly.app.ui.theme.Charaly
import dev.charaly.app.ui.theme.toColor
import dev.charaly.runtime.model.InstalledModel
import dev.charaly.runtime.model.ModelProfile
import dev.charaly.runtime.model.SamplingTemperament
import dev.charaly.runtime.presentation.MemoryPanelSnapshot
import dev.charaly.runtime.presentation.StoryInfoSnapshot
import dev.charaly.runtime.presentation.StorySnapshot
import dev.charaly.runtime.presentation.WorldPanelSnapshot

/**
 * The story's context sheets.
 *
 * Everything a curious player wants to know about the world they are standing in,
 * without leaving the story and without opening a developer tool: who is here, what
 * the world is doing, what anyone remembers, which model is speaking, and how the
 * story is structured into chapters.
 *
 * None of these sheets can change the world. They render projections of the real
 * [dev.charaly.runtime.domain.StoryInstance]; the only exceptions are the explicit
 * actions, and each of those goes back through the runtime.
 */
@Composable
fun StorySheetContent(
    sheet: StorySheet,
    onSheetChange: (StorySheet) -> Unit,
    snapshot: StorySnapshot,
    worldPanel: WorldPanelSnapshot?,
    memoryPanel: MemoryPanelSnapshot?,
    infoPanel: StoryInfoSnapshot?,
    installedModels: List<InstalledModel>,
    profiles: List<ModelProfile>,
    engineLabel: String,
    onAdvanceClock: (Long) -> Unit,
    onRebind: (InstalledModel?, ModelProfile) -> Unit,
    onOpenModels: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(
                start = Charaly.tokens.spacing.gutter,
                end = Charaly.tokens.spacing.gutter,
                top = Charaly.tokens.spacing.md,
                bottom = Charaly.tokens.spacing.lg,
            ),
    ) {
        SheetHeader(
            title = when (sheet) {
                StorySheet.INFO -> "Story"
                StorySheet.CHARACTERS -> "Characters"
                StorySheet.WORLD -> "World state"
                StorySheet.MEMORY -> "Memory"
                StorySheet.MODEL -> "Model"
                StorySheet.SCENE -> "Scene"
                StorySheet.SESSION -> "Session"
            },
            subtitle = sheetSubtitle(sheet),
            onClose = null,
        )

        // The menu is the index for everything else: one screen that lists the world
        // rather than a strip of seven icons nobody can decode.
        LazyRow(
            modifier = Modifier.padding(bottom = Charaly.tokens.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(StorySheet.entries.size, key = { StorySheet.entries[it].name }) { index ->
                val entry = StorySheet.entries[index]
                CharalyFilterChip(
                    label = entry.label(),
                    selected = entry == sheet,
                    onClick = { onSheetChange(entry) },
                )
            }
        }

        when (sheet) {
            StorySheet.INFO, StorySheet.SCENE, StorySheet.SESSION -> InfoSheetContent(
                snapshot = snapshot,
                info = infoPanel,
                onAdvanceClock = onAdvanceClock,
            )

            StorySheet.CHARACTERS -> CharactersSheetContent(snapshot = snapshot)

            StorySheet.WORLD -> WorldSheetContent(world = worldPanel)

            StorySheet.MEMORY -> MemorySheetContent(memory = memoryPanel)

            StorySheet.MODEL -> ModelSheetContent(
                snapshot = snapshot,
                installedModels = installedModels,
                profiles = profiles,
                engineLabel = engineLabel,
                onRebind = onRebind,
                onOpenModels = onOpenModels,
            )
        }
    }
}

private fun sheetSubtitle(sheet: StorySheet): String = when (sheet) {
    StorySheet.WORLD -> "The deterministic truth, not the model's guess"
    StorySheet.MEMORY -> "The story remembers this"
    StorySheet.MODEL -> "How replies are generated here"
    else -> ""
}

@Composable
private fun SheetHeader(
    title: String,
    subtitle: String,
    onClose: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.padding(bottom = Charaly.tokens.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onClose != null) {
            CharalyIconButton(
                icon = Icons.Filled.Close,
                contentDescription = "Close",
                onClick = onClose,
            )
        }
    }
}

/** Story info, scene and chapters: one sheet, because they are the same question. */
@Composable
private fun InfoSheetContent(
    snapshot: StorySnapshot,
    info: StoryInfoSnapshot?,
    onAdvanceClock: (Long) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = Charaly.tokens.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.xs),
    ) {
        item(key = "where") {
            CharalyCard(container = MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    Eyebrow("Right now")
                    DetailRow("Location", snapshot.locationName.ifBlank { "Somewhere" })
                    DetailRow("Time", snapshot.timeLabel)
                    DetailRow("Chapter", snapshot.chapterLabel)
                    DetailRow("Your role", snapshot.personaName.ifBlank { "You" })
                    DetailRow("Model", snapshot.modelDetail)
                }
            }
        }

        item(key = "participants") {
            CharalyCard(container = MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    Eyebrow("In the room")
                    if (snapshot.participants.isEmpty()) {
                        Text(
                            text = "Nobody else is here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    } else {
                        snapshot.participants.forEach { participant ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CharacterAvatar(
                                    seed = participant.name,
                                    accent = participant.accent.toColor(),
                                    name = participant.name,
                                    modifier = Modifier.size(26.dp),
                                )
                                Text(
                                    text = participant.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 10.dp),
                                )
                                Text(
                                    text = participant.activityLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (info != null && info.chapters.isNotEmpty()) {
            item(key = "chapters") {
                CharalyCard(container = MaterialTheme.colorScheme.surfaceContainer) {
                    Column {
                        Eyebrow("Chapters")
                        info.chapters.forEach { chapter ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (chapter.isCurrent) {
                                                snapshot.theme.primary.let { it.toColor() }.copy(alpha = 0.2f)
                                            } else {
                                                MaterialTheme.colorScheme.surfaceContainerHigh
                                            },
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = chapter.index.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (chapter.isCurrent) {
                                            snapshot.theme.primary.let { it.toColor() }
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 10.dp),
                                ) {
                                    Text(
                                        text = chapter.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = "${chapter.locationName}  ·  ${chapter.turnCount} turns",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item(key = "clock") {
            CharalyCard(container = MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    Eyebrow("Story clock")
                    Text(
                        text = "Move time forward and the world reacts: scheduled events fire, " +
                            "characters go where they were going, and anything waiting on a " +
                            "condition gets another chance to happen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Row(
                        modifier = Modifier.padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(10L to "+10 min", 60L to "+1 hour", 480L to "+8 hours").forEach { (minutes, label) ->
                            CharalyGhostButton(
                                label = label,
                                onClick = { onAdvanceClock(minutes) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CharactersSheetContent(snapshot: StorySnapshot) {
    if (snapshot.participants.isEmpty()) {
        EmptyStateView(
            state = dev.charaly.runtime.presentation.EmptyState(
                title = "No one is here yet.",
                body = "Characters appear as the world brings them into the scene.",
                artSeed = "charaly-empty-cast",
            ),
        )
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(bottom = Charaly.tokens.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.xs),
    ) {
        items(snapshot.participants.size, key = { snapshot.participants[it].id }) { index ->
            val participant = snapshot.participants[index]
            CharalyCard(container = MaterialTheme.colorScheme.surfaceContainer) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CharacterAvatar(
                        seed = participant.name,
                        accent = participant.accent.toColor(),
                        name = participant.name,
                        modifier = Modifier.size(40.dp),
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 10.dp),
                    ) {
                        Text(
                            text = participant.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = participant.activityLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (participant.isFocus) {
                        Eyebrow("Talking to")
                    }
                }
            }
        }
    }
}

/**
 * The world-state inspection sheet.
 *
 * Deliberately not JSON: "who is where and what is unresolved" is what a reader
 * wants. The raw state lives in the developer panel.
 */
@Composable
private fun WorldSheetContent(world: WorldPanelSnapshot?) {
    if (world == null || world.isEmpty) {
        EmptyStateView(
            state = dev.charaly.runtime.presentation.EmptyState(
                title = "No world state yet.",
                body = "Once the story starts, everything the engine knows appears here.",
                artSeed = "charaly-empty-world",
            ),
        )
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(bottom = Charaly.tokens.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.xs),
    ) {
        item(key = "scene") {
            CharalyCard(container = MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    Eyebrow("Current scene")
                    DetailRow("Location", world.sceneLocation)
                    DetailRow("Time", world.sceneTime)
                    if (world.weather.isNotBlank()) DetailRow("Weather", world.weather)
                    DetailRow("Present", world.presentNames.joinToString(", ").ifBlank { "Nobody" })
                }
            }
        }

        item(key = "characters") {
            CharalyCard(container = MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    Eyebrow("Characters")
                    world.characters.forEach { character ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CharacterAvatar(
                                seed = character.name,
                                accent = character.accent.toColor(),
                                name = character.name,
                                modifier = Modifier.size(28.dp),
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 10.dp),
                            ) {
                                Text(
                                    text = character.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "${character.locationName}  ·  ${character.activity}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = character.relationshipSummary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = "${character.knowsCount} facts  ${character.memoryCount} memories",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            )
                        }
                    }
                }
            }
        }

        if (world.threads.isNotEmpty()) {
            item(key = "threads") {
                CharalyCard(container = MaterialTheme.colorScheme.surfaceContainer) {
                    Column {
                        Eyebrow("Active threads")
                        world.threads.forEach { thread ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Stage ${thread.stage}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Charaly.accent.accent,
                                    modifier = Modifier.width(64.dp),
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = thread.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = thread.statusLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (world.objectives.isNotEmpty()) {
            item(key = "objectives") {
                CharalyCard(container = MaterialTheme.colorScheme.surfaceContainer) {
                    Column {
                        Eyebrow("What they want")
                        world.objectives.forEach { goal ->
                            Text(
                                text = "· $goal",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
        }

        if (world.recentEvents.isNotEmpty()) {
            item(key = "events") {
                CharalyCard(container = MaterialTheme.colorScheme.surfaceContainer) {
                    Column {
                        Eyebrow("Coming up")
                        world.recentEvents.forEach { event ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier
                                        .size(5.dp)
                                        .clip(CircleShape)
                                        .background(Charaly.accent.secondary),
                                )
                                Text(
                                    text = event.summary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 10.dp),
                                )
                                Text(
                                    text = event.timeLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Memory as a story feature: "the story remembers this", with no database talk. */
@Composable
private fun MemorySheetContent(memory: MemoryPanelSnapshot?) {
    if (memory == null || memory.emptyState != null) {
        EmptyStateView(
            state = memory?.emptyState ?: dev.charaly.runtime.presentation.EmptyState(
                title = "Nothing remembered yet.",
                body = "Memories appear here as the story accumulates them.",
                artSeed = "charaly-empty-memory",
            ),
        )
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(bottom = Charaly.tokens.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.sm),
    ) {
        items(memory.sections.size, key = { memory.sections[it].title }) { index ->
            val section = memory.sections[index]
            CharalyCard(container = MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    Eyebrow(section.title)
                    Text(
                        text = section.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    section.memories.forEach { memoryRow ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 5.dp)
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(importanceColor(memoryRow.importance)),
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 10.dp),
                            ) {
                                Text(
                                    text = memoryRow.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "${memoryRow.ownerName}  ·  ${memoryRow.importanceLabel}  ·  " +
                                        memoryRow.sourceLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun importanceColor(importance: Int): Color = when (importance) {
    5 -> Charaly.accent.accent
    4 -> Charaly.accent.primary
    3 -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.outline
}

/**
 * The model sheet.
 *
 * Three lines first: which model, is it ready, and how creative it is allowed to be.
 * Everything else is behind "Advanced", because eleven sampler sliders is not a
 * consumer feature.
 */
@Composable
private fun ModelSheetContent(
    snapshot: StorySnapshot,
    installedModels: List<InstalledModel>,
    profiles: List<ModelProfile>,
    engineLabel: String,
    onRebind: (InstalledModel?, ModelProfile) -> Unit,
    onOpenModels: () -> Unit,
) {
    var advanced by remember { mutableStateOf(false) }
    val binding = snapshot.run {
        infoBinding(installedModels)
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = Charaly.tokens.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.xs),
    ) {
        item(key = "status") {
            CharalyCard(container = MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    StatusDot(
                        label = if (snapshot.modelReady) "Ready" else "Not ready",
                        color = if (snapshot.modelReady) Charaly.colors.success else Charaly.colors.warning,
                        pulsing = !snapshot.modelReady,
                    )
                    DetailRow("Model", snapshot.modelName.ifBlank { "No model bound" })
                    DetailRow("Engine", engineLabel)
                    if (!snapshot.modelReady) {
                        Text(
                            text = "This story needs a local model before it can continue.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Charaly.colors.warning,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        CharalyTextButton(label = "Open models", onClick = onOpenModels)
                    }
                }
            }
        }

        item(key = "sampling") {
            CharalyCard(container = MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    Eyebrow("Sampling")
                    Text(
                        text = "How expressive this story is allowed to be.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    LazyRow(
                        modifier = Modifier.padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(SamplingTemperament.entries.size, key = { SamplingTemperament.entries[it].name }) { index ->
                            val temperament = SamplingTemperament.entries[index]
                            val isBound = binding?.sampler?.temperament == temperament
                            CharalyFilterChip(
                                label = temperament.label,
                                selected = isBound,
                                onClick = {
                                    val profile = profiles.firstOrNull { it.sampler.temperament == temperament }
                                    if (profile != null) onRebind(null, profile)
                                },
                            )
                        }
                    }
                }
            }
        }

        if (installedModels.isNotEmpty()) {
            item(key = "models") {
                CharalyCard(container = MaterialTheme.colorScheme.surfaceContainer) {
                    Column {
                        Eyebrow("Models on this device")
                        installedModels.forEach { model ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = model.displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = "${model.sizeLabel}  ·  Local",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                CharalyTextButton(
                                    label = "Use",
                                    onClick = { onRebind(model, profiles.first()) },
                                )
                            }
                        }
                    }
                }
            }
        }

        item(key = "profiles") {
            CharalyCard(container = MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    Eyebrow("Profile")
                    profiles.forEach { profile ->
                        val isBound = binding?.profileId == profile.id ||
                            binding?.profileName == profile.name
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = profile.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "${profile.sampler.temperament.label}  ·  " +
                                        profile.narrativeStyle.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (isBound) {
                                Eyebrow("In use")
                            } else {
                                CharalyTextButton(
                                    label = "Use",
                                    onClick = { onRebind(null, profile) },
                                )
                            }
                        }
                    }
                }
            }
        }

        item(key = "advanced") {
            CharalyCard(container = MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    CharalyTextButton(
                        label = if (advanced) "Hide advanced" else "Advanced",
                        onClick = { advanced = !advanced },
                    )
                    AnimatedVisibility(visible = advanced) {
                        Column {
                            val rows = binding?.let {
                                dev.charaly.runtime.presentation.ModelLibraryPresenter.advancedRows(it)
                            }.orEmpty()
                            if (rows.isEmpty()) {
                                Text(
                                    text = "Open a story to see the sampler settings it runs with.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            } else {
                                rows.forEach { row ->
                                    DetailRow(row.label, row.value)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The binding of the open story, when one of the installed models matches it. */
private fun StorySnapshot.infoBinding(installed: List<InstalledModel>): dev.charaly.runtime.model.ModelBinding? =
    installed.firstOrNull { it.displayName == modelName }?.let { model ->
        dev.charaly.runtime.model.ModelBinding.from(
            installedModelId = model.id,
            modelDisplayName = model.displayName,
            profile = dev.charaly.runtime.model.ModelProfileLibrary.resolve(model.profiles.firstOrNull()?.id),
            maxContextTokens = model.effectiveContextTokens(),
        )
    }
