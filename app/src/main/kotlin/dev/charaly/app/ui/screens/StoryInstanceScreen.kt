package dev.charaly.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.charaly.app.ui.art.CharacterAvatar
import dev.charaly.app.ui.components.CharalyCard
import dev.charaly.app.ui.components.CharalyFilterChip
import dev.charaly.app.ui.components.CharalyGhostButton
import dev.charaly.app.ui.components.CharalyIconButton
import dev.charaly.app.ui.components.CharalyPrimaryButton
import dev.charaly.app.ui.components.CharalyTextButton
import dev.charaly.app.ui.components.EmptyStateView
import dev.charaly.app.ui.components.Eyebrow
import dev.charaly.app.ui.components.TinySpinner
import dev.charaly.app.ui.theme.Charaly
import dev.charaly.app.ui.theme.CharalyTypography
import dev.charaly.app.ui.theme.toColor
import dev.charaly.runtime.model.InstalledModel
import dev.charaly.runtime.model.ModelProfile
import dev.charaly.runtime.presentation.ComposerMode
import dev.charaly.runtime.presentation.GenerationPhase
import dev.charaly.runtime.presentation.LineKind
import dev.charaly.runtime.presentation.MemoryPanelSnapshot
import dev.charaly.runtime.presentation.NarrativeSegment
import dev.charaly.runtime.presentation.ParticipantChip
import dev.charaly.runtime.presentation.SegmentKind
import dev.charaly.runtime.presentation.isBusy
import dev.charaly.runtime.presentation.StoryInfoSnapshot
import dev.charaly.runtime.presentation.StoryLine
import dev.charaly.runtime.presentation.LayoutPolicy
import dev.charaly.runtime.presentation.StorySnapshot
import dev.charaly.runtime.presentation.WorldPanelSnapshot
import kotlinx.coroutines.launch

/**
 * The story itself.
 *
 * What makes this feel like a story rather than a chat app:
 *  * the header states where and when you are, in story time, not wall time;
 *  * a character reply is split into spoken lines, narration and action, and each
 *    is typeset differently, so the screen reads like prose;
 *  * world events appear as timeline cards, which is where the user learns that the
 *    simulation is real;
 *  * generation shows a *person* thinking ("Marinette is thinking…"), not "loading";
 *  * the chrome gets out of the way: no bottom bar, no settings.
 *
 * The composer deliberately has no privileged access to the world. Its mode buttons
 * shape the player's *input*; whatever the player does still has to travel through
 * the runtime and the event engine to change anything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryInstanceScreen(
    snapshot: StorySnapshot?,
    loading: Boolean,
    worldPanel: WorldPanelSnapshot?,
    memoryPanel: MemoryPanelSnapshot?,
    infoPanel: StoryInfoSnapshot?,
    storyId: String,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onRegenerate: () -> Unit,
    onContinue: () -> Unit,
    onEditLast: (String) -> Unit,
    onSelectSpeaker: (dev.charaly.runtime.domain.CharacterId) -> Unit,
    onAdvanceClock: (Long) -> Unit,
    onOpenModels: () -> Unit,
    onRebind: (InstalledModel?, ModelProfile) -> Unit,
    onRetry: () -> Unit,
    onOpenWorld: () -> Unit = {},
    installedModels: List<InstalledModel>,
    profiles: List<ModelProfile>,
    engineLabel: String,
) {
    if (snapshot == null) {
        StoryMissing(loading = loading, onBack = onBack)
        return
    }

    var sheet by remember { mutableStateOf<StorySheet?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Follow the conversation as it grows, including streamed chunks.
    LaunchedEffect(snapshot.lines.size) {
        val target = snapshot.lines.lastIndex
        if (target >= 0) listState.animateScrollToItem(target)
    }

    // Layout is decided by LayoutPolicy, not by a width check inside the composable, so
    // the tablet behaviour is unit tested. A phone gets one column; a wide tablet gets
    // the conversation plus a persistent world panel, which is the one place "do not
    // simply scale phone UI" actually pays off.
    // Width drives the layout, so a rotation or a fold changes it.
    val widthDp = LocalConfiguration.current.screenWidthDp
    val layout = remember(widthDp) { LayoutPolicy.forWidth(widthDp) }
    val contextPaneWidth = with(LocalDensity.current) {
        layout.contextWidthDp(widthDp).dp
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .imePadding(),
        ) {
            StoryHeader(
                snapshot = snapshot,
                onBack = onBack,
                onOpenSheet = { sheet = it },
                onOpenWorld = onOpenWorld,
            )

            ParticipantStrip(
                snapshot = snapshot,
                onSelectSpeaker = onSelectSpeaker,
            )

            Box(Modifier.weight(1f)) {
                val opening = snapshot.emptyState
                if (snapshot.lines.isEmpty() && opening != null) {
                    EmptyStateView(
                        state = opening,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 40.dp),
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = Charaly.tokens.spacing.gutter,
                            end = Charaly.tokens.spacing.gutter,
                            top = Charaly.tokens.spacing.md,
                            bottom = Charaly.tokens.spacing.lg,
                        ),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(
                            count = snapshot.lines.size,
                            key = { index -> snapshot.lines[index].id },
                        ) { index ->
                            StoryLineView(
                                line = snapshot.lines[index],
                                isLast = index == snapshot.lines.lastIndex,
                            )
                        }
                        if (snapshot.phase.isBusy()) {
                            item(key = "thinking") {
                                ThinkingRow(snapshot = snapshot)
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = snapshot.failureMessage.isNotBlank(),
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically(),
            ) {
                FailureBar(
                    message = snapshot.failureMessage,
                    actions = snapshot.failureActions,
                    onAction = { action ->
                        when (action) {
                            "Open Models" -> onOpenModels()
                            else -> onRetry()
                        }
                    },
                )
            }

            StoryComposer(
                snapshot = snapshot,
                onSend = onSend,
                onStop = onStop,
                onRegenerate = onRegenerate,
                onContinue = onContinue,
                onEditLast = onEditLast,
                onOpenModelSheet = { sheet = StorySheet.MODEL },
            )
        }

        // The persistent context pane. Only rendered when the layout policy says the
        // screen is wide enough for two columns to both be readable - a squeezed prose
        // column is a worse experience than one full-width one.
        if (layout.storySplitPane) {
            StoryContextPane(
                snapshot = snapshot,
                worldPanel = worldPanel,
                memoryPanel = memoryPanel,
                infoPanel = infoPanel,
                modifier = Modifier
                    .width(contextPaneWidth)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceContainer),
            )
        }
        }

        if (sheet != null) {
            ModalBottomSheet(
                onDismissRequest = { sheet = null },
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surface,
                dragHandle = null,
            ) {
                StorySheetContent(
                    sheet = sheet!!,
                    onSheetChange = { sheet = it },
                    snapshot = snapshot,
                    worldPanel = worldPanel,
                    memoryPanel = memoryPanel,
                    infoPanel = infoPanel,
                    installedModels = installedModels,
                    profiles = profiles,
                    engineLabel = engineLabel,
                    onAdvanceClock = onAdvanceClock,
                    onRebind = { model, profile ->
                        onRebind(model, profile)
                        sheet = null
                        scope.launch { sheetState.hide() }
                    },
                    onOpenModels = {
                        sheet = null
                        onOpenModels()
                    },
                )
            }
        }
    }
}

/**
 * The persistent right-hand pane on a wide tablet.
 *
 * Deliberately a *summary*, not a second copy of the bottom sheets: the things a player
 * wants to glance at while reading - where they are, who is here, what is unresolved,
 * and the last few memories. Everything else is still one tap away in the sheets.
 */
@Composable
private fun StoryContextPane(
    snapshot: StorySnapshot,
    worldPanel: WorldPanelSnapshot?,
    memoryPanel: MemoryPanelSnapshot?,
    infoPanel: StoryInfoSnapshot?,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = Charaly.tokens.spacing.md,
            end = Charaly.tokens.spacing.md,
            top = Charaly.tokens.spacing.md,
            bottom = Charaly.tokens.spacing.section,
        ),
        verticalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.sm),
    ) {
        item(key = "ctx-scene") {
            Column {
                Eyebrow(text = "Scene")
                Text(
                    text = snapshot.locationName.ifBlank { "Nowhere in particular" },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    text = snapshot.timeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (worldPanel != null && worldPanel.presentNames.isNotEmpty()) {
            item(key = "ctx-present") {
                Column {
                    Eyebrow(text = "Present")
                    worldPanel.presentNames.take(6).forEach { name ->
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }

        if (worldPanel != null && worldPanel.threads.isNotEmpty()) {
            item(key = "ctx-threads") {
                Column {
                    Eyebrow(text = "Active threads")
                    worldPanel.threads
                        .filter { it.isOpen }
                        .take(4)
                        .forEach { thread ->
                            Row(
                                modifier = Modifier.padding(top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = thread.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = thread.statusLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Charaly.accent.accent,
                                    modifier = Modifier.padding(start = 6.dp),
                                )
                            }
                        }
                }
            }
        }

        if (memoryPanel != null && memoryPanel.totalCount > 0) {
            item(key = "ctx-memories") {
                Column {
                    Eyebrow(text = "Recent memories")
                    memoryPanel.sections
                        .flatMap { it.memories }
                        .takeLast(5)
                        .reversed()
                        .forEach { memory ->
                            Text(
                                text = memory.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                }
            }
        }

        if (infoPanel != null) {
            item(key = "ctx-story") {
                Column {
                    Eyebrow(text = "This story")
                    Text(
                        text = infoPanel.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Text(
                        text = "${infoPanel.chapters.size} chapters · ${infoPanel.binding.modelDisplayName
                            .ifBlank { "no model bound" }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * The context sheets the story screen can open.
 *
 * Seven destinations is too many for an icon row, so the story menu opens as one
 * sheet that indexes the rest.
 */
enum class StorySheet {
    INFO,
    CHARACTERS,
    WORLD,
    MEMORY,
    MODEL,
    SCENE,
    SESSION,
    ;

    fun label(): String = when (this) {
        INFO -> "Story"
        CHARACTERS -> "Characters"
        WORLD -> "World state"
        MEMORY -> "Memory"
        MODEL -> "Model"
        SCENE -> "Scene"
        SESSION -> "Session"
    }
}

@Composable
private fun StoryHeader(
    snapshot: StorySnapshot,
    onBack: () -> Unit,
    onOpenSheet: (StorySheet) -> Unit,
    onOpenWorld: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        snapshot.theme.primary.let { Color(it).copy(alpha = 0.16f) },
                        MaterialTheme.colorScheme.background,
                    ),
                ),
            )
            .padding(horizontal = Charaly.tokens.spacing.xs, vertical = Charaly.tokens.spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CharalyIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Leave story",
                onClick = onBack,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
            ) {
                Text(
                    text = snapshot.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = snapshot.sceneLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            CharalyIconButton(
                icon = Icons.Filled.Public,
                contentDescription = "Look around the world",
                onClick = onOpenWorld,
            )
            CharalyIconButton(
                icon = Icons.Filled.MoreHoriz,
                contentDescription = "Story options",
                onClick = { onOpenSheet(StorySheet.INFO) },
            )
        }
        // A hairline of context, not chrome: what chapter you are in.
        Row(
            modifier = Modifier.padding(start = 10.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Eyebrow(
                text = "${snapshot.chapterLabel}  ·  ${snapshot.timeLabel}",
                color = snapshot.theme.accent.toColor(),
            )
        }
    }
}

/** Compact participant row: who is in the room, and who you are talking to. */
@Composable
private fun ParticipantStrip(
    snapshot: StorySnapshot,
    onSelectSpeaker: (dev.charaly.runtime.domain.CharacterId) -> Unit,
) {
    if (snapshot.participants.isEmpty()) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = Charaly.tokens.spacing.gutter),
        horizontalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.xs),
        modifier = Modifier.padding(bottom = Charaly.tokens.spacing.xs),
    ) {
        items(snapshot.participants.size, key = { snapshot.participants[it].id }) { index ->
            val chip = snapshot.participants[index]
            ParticipantPill(
                chip = chip,
                onClick = { onSelectSpeaker(dev.charaly.runtime.domain.CharacterId(chip.id)) },
            )
        }
    }
}

@Composable
private fun ParticipantPill(chip: ParticipantChip, onClick: () -> Unit) {
    val active = chip.isFocus
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(
                if (active) chip.accent.toColor().copy(alpha = 0.16f) else Color.Transparent,
            )
            .border(
                width = 1.dp,
                color = if (active) chip.accent.toColor().copy(alpha = 0.45f) else Color.Transparent,
                shape = CircleShape,
            )
            .clickable { onClick() }
            .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
            .semantics { contentDescription = "Talk to ${chip.name}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CharacterAvatar(
            seed = chip.name,
            accent = chip.accent.toColor(),
            name = chip.name,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = chip.name,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) chip.accent.toColor() else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * One rendered line.
 *
 * Dialogue, narration and action are typeset differently on purpose: that is the
 * difference between a story and a log of tokens.
 */
@Composable
private fun StoryLineView(line: StoryLine, isLast: Boolean) {
    when (line.kind) {
        LineKind.PLAYER -> PlayerLine(line)
        LineKind.DIALOGUE -> DialogueLine(line, isLast)
        LineKind.NARRATION -> NarrationLine(line)
        LineKind.ACTION -> ActionLine(line)
        LineKind.WORLD_EVENT -> WorldEventLine(line)
        LineKind.SYSTEM -> SystemLine(line)
    }
}

@Composable
private fun PlayerLine(line: StoryLine) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = 18.dp,
                        bottomEnd = 6.dp,
                    ),
                )
                .background(Charaly.accent.primary.copy(alpha = 0.18f))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = line.rawText,
                style = CharalyTypography.dialogue,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = line.timeLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 3.dp, end = 4.dp),
        )
    }
}

@Composable
private fun DialogueLine(line: StoryLine, isLast: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        CharacterAvatar(
            seed = line.speakerName,
            accent = line.speakerAccent.toColor(),
            name = line.speakerName,
            modifier = Modifier.size(30.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
        ) {
            Text(
                text = line.speakerName,
                style = MaterialTheme.typography.labelMedium,
                color = line.speakerAccent.toColor(),
            )
            Text(
                text = "“${line.rawText}”",
                style = CharalyTypography.dialogue,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (isLast) {
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    line.segments.forEach { _ ->
                        StreamingDot()
                    }
                }
            }
        }
    }
}

@Composable
private fun NarrationLine(line: StoryLine) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, start = 8.dp, end = 24.dp),
    ) {
        Text(
            text = line.rawText,
            style = CharalyTypography.narration.copy(fontStyle = FontStyle.Italic),
            color = Charaly.colors.narration,
        )
    }
}

@Composable
private fun ActionLine(line: StoryLine) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, start = 8.dp, end = 24.dp),
    ) {
        Text(
            text = "* ${line.rawText} *",
            style = CharalyTypography.narration,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A world event, as a timeline card.
 *
 * This is the visible proof that the simulation is real: the text here came from the
 * event log, not from a model's imagination.
 */
@Composable
private fun WorldEventLine(line: StoryLine) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(Charaly.accent.secondary),
        )
        Text(
            text = line.eventSummary.ifBlank { line.rawText },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
        )
        Text(
            text = line.timeLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SystemLine(line: StoryLine) {
    Text(
        text = line.rawText,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
    )
}

/** "Marinette is thinking…" — a person, not a spinner. */
@Composable
private fun ThinkingRow(snapshot: StorySnapshot) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(Charaly.accent.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            TinySpinner(color = Charaly.accent.primary)
        }
        Text(
            text = snapshot.phaseLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

@Composable
private fun StreamingDot() {
    val transition = rememberInfiniteTransition(label = "stream")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(600), repeatMode = RepeatMode.Reverse),
        label = "stream-alpha",
    )
    Box(
        Modifier
            .size(3.dp)
            .clip(CircleShape)
            .background(Charaly.accent.primary.copy(alpha = alpha)),
    )
}

/** The failure surface: one sentence and up to two actions. */
@Composable
private fun FailureBar(
    message: String,
    actions: List<String>,
    onAction: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = Charaly.tokens.spacing.gutter, vertical = Charaly.tokens.spacing.sm),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        if (actions.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                actions.forEach { action ->
                    CharalyTextButton(
                        label = action,
                        onClick = { onAction(action) },
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

/**
 * The composer.
 *
 * A rounded surface you can grow into, a "+" that offers ways of acting rather than
 * a list of features, and generation controls that stay out of the way once a story
 * is going.
 */
@Composable
private fun StoryComposer(
    snapshot: StorySnapshot,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onRegenerate: () -> Unit,
    onContinue: () -> Unit,
    onEditLast: (String) -> Unit,
    onOpenModelSheet: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(ComposerMode.SAY) }
    var showModes by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val generating = snapshot.phase.isBusy()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .padding(
                start = Charaly.tokens.spacing.sm,
                end = Charaly.tokens.spacing.sm,
                top = Charaly.tokens.spacing.xs,
                bottom = Charaly.tokens.spacing.xs,
            ),
    ) {
        AnimatedVisibility(visible = showModes) {
            LazyRow(
                modifier = Modifier.padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(ComposerMode.entries.size, key = { ComposerMode.entries[it].name }) { index ->
                    val entry = ComposerMode.entries[index]
                    CharalyFilterChip(
                        label = entry.label,
                        selected = entry == mode,
                        onClick = { mode = entry },
                    )
                }
            }
        }

        if (mode != ComposerMode.SAY) {
            Text(
                text = mode.hint,
                style = MaterialTheme.typography.labelSmall,
                color = Charaly.accent.accent,
                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
            )
        }

        Row(verticalAlignment = Alignment.Bottom) {
            CharalyIconButton(
                icon = if (showModes) Icons.Filled.Close else Icons.Filled.Add,
                contentDescription = if (showModes) "Close actions" else "Open actions",
                onClick = { showModes = !showModes },
            )

            ComposerField(
                value = text,
                onValueChange = { text = it },
                placeholder = snapshot.composerHint,
                enabled = snapshot.composerEnabled,
                onSend = {
                    val composed = mode.compose(text)
                    if (composed.isNotBlank()) {
                        onSend(composed)
                        text = ""
                    }
                },
                modifier = Modifier.weight(1f),
            )

            Spacer(Modifier.width(6.dp))

            if (generating) {
                CharalyIconButton(
                    icon = Icons.Filled.Stop,
                    contentDescription = "Stop generating",
                    onClick = onStop,
                    container = MaterialTheme.colorScheme.surfaceContainerHigh,
                )
            } else {
                CharalyIconButton(
                    icon = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    onClick = {
                        val composed = mode.compose(text)
                        if (composed.isNotBlank()) {
                            onSend(composed)
                            text = ""
                        }
                    },
                    enabled = text.isNotBlank() && snapshot.composerEnabled,
                    container = if (text.isNotBlank() && snapshot.composerEnabled) {
                        Charaly.accent.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    tint = if (text.isNotBlank() && snapshot.composerEnabled) {
                        Charaly.accent.onAccent
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        if (editing) {
            EditLastLineDialog(
                initial = snapshot.lastUserText,
                onDismiss = { editing = false },
                onSubmit = { revised ->
                    editing = false
                    if (revised.isNotBlank()) onEditLast(revised)
                },
            )
        }

        Row(
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ComposerMiniAction(
                icon = Icons.Filled.Refresh,
                label = "Regenerate",
                onClick = onRegenerate,
            )
            ComposerMiniAction(
                icon = Icons.Filled.AutoAwesome,
                label = "Continue",
                onClick = onContinue,
            )
            if (snapshot.lastUserText.isNotBlank() && !generating) {
                ComposerMiniAction(
                    icon = Icons.Filled.Edit,
                    label = "Edit last",
                    onClick = { editing = true },
                )
            }
            Spacer(Modifier.weight(1f))
            CharalyTextButton(
                label = snapshot.modelName,
                onClick = onOpenModelSheet,
                contentColor = if (snapshot.modelReady) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    Charaly.colors.warning
                },
            )
        }
    }
}

/**
 * The input surface.
 *
 * A `BasicTextField` inside a rounded fill rather than an `OutlinedTextField`: the
 * composer is the most touched object in the app and it should look like a place you
 * type into, not like a form field.
 */
@Composable
private fun ComposerField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(Charaly.tokens.radii.composerShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .heightIn(min = 44.dp, max = 140.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(Charaly.accent.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            maxLines = 5,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ComposerMiniAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    CharalyTextButton(
        label = label,
        icon = icon,
        onClick = onClick,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.heightIn(min = 36.dp),
    )
}

/**
 * Edit the last thing the player said.
 *
 * Submitting drops the previous reply from the transcript and generates again from
 * the revised line. World state is deliberately *not* rolled back: what already
 * happened in the world happened.
 */
@Composable
private fun EditLastLineDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit your last line") },
        text = {
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = { value = it },
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(Charaly.accent.primary),
                minLines = 2,
                maxLines = 6,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(Charaly.tokens.radii.shapeSm)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(12.dp)
                    .semantics { contentDescription = "Your line" },
            )
        },
        confirmButton = {
            CharalyTextButton(label = "Send again", onClick = { onSubmit(value) })
        },
        dismissButton = {
            CharalyTextButton(
                label = "Keep",
                onClick = onDismiss,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
private fun StoryMissing(loading: Boolean, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = Charaly.tokens.spacing.xs, top = Charaly.tokens.spacing.xl)) {
            CharalyIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                onClick = onBack,
            )
        }
        if (loading) {
            dev.charaly.app.ui.components.SkeletonList(count = 3, modifier = Modifier.padding(top = 40.dp))
        } else {
            EmptyStateView(
                state = dev.charaly.runtime.presentation.EmptyState(
                    title = "This story is not here.",
                    body = "It may have been deleted. Open another one from your sessions.",
                    actionLabel = "Back",
                    artSeed = "charaly-empty-story",
                ),
                modifier = Modifier.padding(top = 40.dp),
            ) {
                CharalyPrimaryButton(label = "Back", onClick = onBack)
            }
        }
    }
}