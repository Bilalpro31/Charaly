package dev.charaly.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
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
import dev.charaly.app.ui.art.CharacterAvatar
import dev.charaly.app.ui.components.BodyProse
import dev.charaly.app.ui.components.CharalyCard
import dev.charaly.app.ui.components.CharalyFilterChip
import dev.charaly.app.ui.components.CharalyGhostButton
import dev.charaly.app.ui.components.CharalyIconButton
import dev.charaly.app.ui.components.CharalyPrimaryButton
import dev.charaly.app.ui.components.DetailRow
import dev.charaly.app.ui.components.Eyebrow
import dev.charaly.app.ui.components.SectionHeader
import dev.charaly.app.ui.components.SkeletonList
import dev.charaly.app.ui.components.StatPill
import dev.charaly.app.ui.theme.Charaly
import dev.charaly.app.ui.theme.toColor
import dev.charaly.runtime.presentation.CharacterCard
import dev.charaly.runtime.presentation.NewStorySnapshot
import dev.charaly.runtime.presentation.NewStoryStep
import dev.charaly.runtime.presentation.PersonaCard
import dev.charaly.runtime.presentation.ScenarioCard

/**
 * The New Story wizard.
 *
 * Four questions, in the order a player actually asks them:
 *   1. where do we begin,
 *   2. who am I,
 *   3. who is already here,
 *   4. is this right?
 *
 * Every step reads from [NewStorySnapshot], which is derived from the real pack: the
 * scenarios offered are the pack's scenarios, and the cast offered is the pack's
 * cast. There is no way to reach another pack's character from here.
 */
@Composable
fun NewStoryScreen(
    snapshot: NewStorySnapshot?,
    loading: Boolean,
    onBack: () -> Unit,
    onStep: (NewStoryStep) -> Unit,
    onDraftChange: ((dev.charaly.runtime.presentation.NewStoryDraft) -> dev.charaly.runtime.presentation.NewStoryDraft) -> Unit,
    onEnterStory: (String) -> Unit,
    onOpenModels: () -> Unit,
) {
    if (snapshot == null) {
        NewStoryMissing(loading = loading, onBack = onBack)
        return
    }

    val draft = snapshot.step

    Column(Modifier.fillMaxSize()) {
        NewStoryTopBar(
            step = snapshot.step,
            packTitle = snapshot.packTitle,
            tagline = snapshot.packTagline,
            onBack = { if (snapshot.step.previous == null) onBack() else onStep(snapshot.step.previous!!) },
        )

        StepDots(
            current = snapshot.step,
            onSelect = onStep,
            modifier = Modifier.padding(horizontal = Charaly.tokens.spacing.gutter),
        )

        Box(Modifier.weight(1f)) {
            AnimatedContent(
                targetState = snapshot.step,
                transitionSpec = {
                    (fadeIn(tween(180)) + androidx.compose.animation.slideInHorizontally(tween(180)) { it / 12 })
                        .togetherWith(fadeOut(tween(120)))
                },
                label = "step",
            ) { step ->
                when (step) {
                    NewStoryStep.SCENARIO -> ScenarioStep(snapshot, onDraftChange)
                    NewStoryStep.PERSONA -> PersonaStep(snapshot, onDraftChange)
                    NewStoryStep.CAST -> CastStep(snapshot, onDraftChange)
                    NewStoryStep.REVIEW -> ReviewStep(snapshot, onDraftChange)
                }
            }
        }

        NewStoryFooter(
            snapshot = snapshot,
            // Null, not a no-op lambda: on the first step there is nothing to go back to,
            // and a Back button that silently does nothing is worse than no button.
            onBack = snapshot.step.previous?.let { previous -> { onStep(previous) } },
            onNext = { snapshot.step.next?.let(onStep) },
            onEnter = { onEnterStory(snapshot.draft.packId) },
            onOpenModels = onOpenModels,
        )
    }
}

@Composable
private fun NewStoryTopBar(
    step: NewStoryStep,
    packTitle: String,
    tagline: String,
    onBack: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = Charaly.tokens.spacing.xs,
                    end = Charaly.tokens.spacing.gutter,
                    top = Charaly.tokens.spacing.xl,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CharalyIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                onClick = onBack,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
            ) {
                Eyebrow("Step ${step.stepNumber} of ${NewStoryStep.entries.size}")
                Text(
                    text = step.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = step.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StepDots(
    current: NewStoryStep,
    onSelect: (NewStoryStep) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(top = Charaly.tokens.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        NewStoryStep.entries.forEach { step ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(
                        if (step.ordinal <= current.ordinal) {
                            Charaly.accent.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                    )
                    .clickable { onSelect(step) }
                    .semantics { contentDescription = "Go to ${step.title}" },
            )
        }
    }
}

@Composable
private fun ScenarioStep(
    snapshot: NewStorySnapshot,
    onDraftChange: ((dev.charaly.runtime.presentation.NewStoryDraft) -> dev.charaly.runtime.presentation.NewStoryDraft) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Charaly.tokens.spacing.gutter,
            end = Charaly.tokens.spacing.gutter,
            top = Charaly.tokens.spacing.lg,
            bottom = Charaly.tokens.spacing.lg,
        ),
        verticalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.sm),
    ) {
        items(snapshot.scenarios, key = { it.id }) { scenario ->
            SelectableScenarioCard(
                scenario = scenario,
                selected = snapshot.selectedScenarioTitle == scenario.title,
                onClick = { onDraftChange { it.copy(scenarioId = scenario.id) } },
            )
        }
    }
}

@Composable
private fun PersonaStep(
    snapshot: NewStorySnapshot,
    onDraftChange: ((dev.charaly.runtime.presentation.NewStoryDraft) -> dev.charaly.runtime.presentation.NewStoryDraft) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Charaly.tokens.spacing.gutter,
            end = Charaly.tokens.spacing.gutter,
            top = Charaly.tokens.spacing.lg,
            bottom = Charaly.tokens.spacing.lg,
        ),
        verticalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.sm),
    ) {
        items(snapshot.personas, key = { it.id }) { persona ->
            SelectablePersonaCard(
                persona = persona,
                selected = snapshot.selectedPersonaTitle == persona.name,
                onClick = { onDraftChange { it.copy(personaId = persona.id) } },
            )
        }
    }
}

@Composable
private fun CastStep(
    snapshot: NewStorySnapshot,
    onDraftChange: ((dev.charaly.runtime.presentation.NewStoryDraft) -> dev.charaly.runtime.presentation.NewStoryDraft) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Text(
            text = "Tap someone to make them the person you are talking to. Everyone you tap is " +
                "placed in the opening scene.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                start = Charaly.tokens.spacing.gutter,
                end = Charaly.tokens.spacing.gutter,
                top = Charaly.tokens.spacing.lg,
            ),
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = Charaly.tokens.spacing.sm),
            contentPadding = PaddingValues(horizontal = Charaly.tokens.spacing.gutter),
            verticalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.xs),
        ) {
            items(snapshot.castable, key = { it.id }) { character ->
                CastSelectRow(
                    character = character,
                    isFocus = character.id == snapshot.draft.focusCharacterId,
                    inCast = character.id in snapshot.draft.castCharacterIds,
                    onToggle = {
                        onDraftChange { draft ->
                            val selected = character.id in draft.castCharacterIds
                            val nextCast = if (selected) {
                                draft.castCharacterIds - character.id
                            } else {
                                draft.castCharacterIds + character.id
                            }
                            draft.copy(
                                castCharacterIds = nextCast,
                                // The most recently picked character is who you talk to.
                                focusCharacterId = if (selected) {
                                    draft.focusCharacterId
                                } else {
                                    character.id
                                },
                            )
                        }
                    },
                )
            }
        }
    }
}

/**
 * Review.
 *
 * The last screen before the story exists, so it states exactly what is about to be
 * true: which world, which role, who is present, where, when, and with which model
 * profile. Nothing is hidden and nothing is defaulted silently.
 */
@Composable
private fun ReviewStep(
    snapshot: NewStorySnapshot,
    onDraftChange: ((dev.charaly.runtime.presentation.NewStoryDraft) -> dev.charaly.runtime.presentation.NewStoryDraft) -> Unit,
) {
    val review = snapshot.review
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = Charaly.tokens.spacing.gutter,
                end = Charaly.tokens.spacing.gutter,
                top = Charaly.tokens.spacing.lg,
                bottom = Charaly.tokens.spacing.lg,
            ),
    ) {
        CharalyCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Eyebrow("World")
                Text(
                    text = review.worldTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = snapshot.packTagline,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Box(Modifier.height(Charaly.tokens.spacing.sm))

        CharalyCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Eyebrow("Your role")
                Text(
                    text = review.roleName,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = review.roleTagline,
                    style = MaterialTheme.typography.bodySmall,
                    color = Charaly.accent.accent,
                )
            }
        }

        Box(Modifier.height(Charaly.tokens.spacing.sm))

        CharalyCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                DetailRow("Opens", "${review.timeLabel}  ·  ${review.locationName}")
                DetailRow("In the room", review.castNames.joinToString(" · ").ifBlank { "Nobody yet" })
                DetailRow("Model", review.modelName)
                DetailRow("Profile", review.profileName)
                if (!snapshot.modelReady) {
                    Text(
                        text = "No model is installed. You can enter the story and choose one there.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Charaly.colors.warning,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    CharalyTextLink(label = "Open models", onClick = {})
                }
            }
        }

        if (review.castNames.isNotEmpty()) {
            Box(Modifier.height(Charaly.tokens.spacing.md))
            SectionHeader(title = "Your cast")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.sm)) {
                items(review.castNames, key = { it }) { name ->
                    CharacterAvatar(
                        seed = name,
                        accent = Charaly.accent.primary,
                        name = name,
                        modifier = Modifier.size(44.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CharalyTextLink(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = Charaly.accent.primary,
        modifier = Modifier
            .padding(top = 6.dp)
            .clickable { onClick() },
    )
}

@Composable
private fun NewStoryFooter(
    snapshot: NewStorySnapshot,
    /** Null on the first step, where there is nothing to go back to. */
    onBack: (() -> Unit)?,
    onNext: () -> Unit,
    onEnter: () -> Unit,
    onOpenModels: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(
                start = Charaly.tokens.spacing.gutter,
                end = Charaly.tokens.spacing.gutter,
                top = Charaly.tokens.spacing.sm,
                bottom = Charaly.tokens.spacing.md,
            ),
    ) {
        if (!snapshot.modelReady) {
            Row(
                modifier = Modifier.padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "A local model is needed for replies.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Charaly.colors.warning,
                    modifier = Modifier.weight(1f),
                )
                CharalyGhostButton(
                    label = "Models",
                    onClick = onOpenModels,
                    modifier = Modifier.height(40.dp),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.xs)) {
            // The wizard's first step has nothing to go back to, so the button is absent
            // rather than shown disabled - a permanently dead button is worse than none.
            if (onBack != null) {
                CharalyGhostButton(
                    label = "Back",
                    onClick = onBack,
                    modifier = Modifier.weight(1f),
                )
            }
            if (snapshot.step == NewStoryStep.REVIEW) {
                CharalyPrimaryButton(
                    label = "Enter Story",
                    icon = Icons.Filled.PlayArrow,
                    onClick = onEnter,
                    enabled = snapshot.canAdvance,
                    // Give the primary action the extra width; when there is no Back to
                    // share the row with, it simply takes the whole line.
                    modifier = Modifier.weight(if (onBack != null) 1.4f else 1f),
                    container = Charaly.accent.accent,
                )
            } else {
                CharalyPrimaryButton(
                    label = "Next",
                    onClick = onNext,
                    enabled = snapshot.canAdvance,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SelectableScenarioCard(
    scenario: ScenarioCard,
    selected: Boolean,
    onClick: () -> Unit,
) {
    CharalyCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        container = if (selected) {
            Charaly.accent.primary.copy(alpha = 0.10f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        border = if (selected) Charaly.accent.primary.copy(alpha = 0.5f) else null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = scenario.title,
                    style = MaterialTheme.typography.titleLarge,
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
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            SelectionMark(selected)
        }
    }
}

@Composable
private fun SelectablePersonaCard(
    persona: PersonaCard,
    selected: Boolean,
    onClick: () -> Unit,
) {
    CharalyCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        container = if (selected) {
            Charaly.accent.primary.copy(alpha = 0.10f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        border = if (selected) Charaly.accent.primary.copy(alpha = 0.5f) else null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = persona.name,
                    style = MaterialTheme.typography.titleLarge,
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
                    modifier = Modifier.padding(top = 6.dp),
                )
                if (persona.suggestedNames.isNotEmpty()) {
                    Text(
                        text = "Often near: ${persona.suggestedNames.joinToString(", ")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            SelectionMark(selected)
        }
    }
}

@Composable
private fun SelectionMark(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(
                if (selected) Charaly.accent.primary else Color.Transparent,
            )
            .border(
                width = 1.5.dp,
                color = if (selected) Charaly.accent.primary else MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Selected",
                tint = Charaly.accent.onAccent,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun CastSelectRow(
    character: CharacterCard,
    isFocus: Boolean,
    inCast: Boolean,
    onToggle: () -> Unit,
) {
    CharalyCard(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        container = if (inCast) {
            Charaly.accent.primary.copy(alpha = 0.08f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CharacterAvatar(
                seed = character.name,
                accent = character.accent.toColor(),
                name = character.name,
                modifier = Modifier.size(40.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = Charaly.tokens.spacing.sm),
            ) {
                Text(
                    text = character.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = character.tagline.ifBlank { character.role },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isFocus) {
                Text(
                    text = "TALKING TO",
                    style = MaterialTheme.typography.labelSmall,
                    color = Charaly.accent.accent,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            SelectionMark(inCast)
        }
    }
}

@Composable
private fun NewStoryMissing(loading: Boolean, onBack: () -> Unit) {
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
        if (loading) {
            SkeletonList(count = 3, modifier = Modifier.padding(top = 40.dp))
        } else {
            dev.charaly.app.ui.components.EmptyStateView(
                state = dev.charaly.runtime.presentation.EmptyState(
                    title = "That world is not available.",
                    body = "Go back and pick a Story Pack to step into.",
                    artSeed = "charaly-empty-newstory",
                ),
                modifier = Modifier.padding(top = 40.dp),
            ) {
                CharalyPrimaryButton(label = "Back", onClick = onBack)
            }
        }
    }
}