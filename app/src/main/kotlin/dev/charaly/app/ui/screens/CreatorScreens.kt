package dev.charaly.app.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.charaly.app.ui.art.ArtworkHero
import dev.charaly.app.ui.art.CharacterAvatar
import dev.charaly.app.ui.art.PackArt
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
import dev.charaly.app.ui.components.HairLine
import dev.charaly.app.ui.components.SectionHeader
import dev.charaly.app.ui.components.StatPill
import dev.charaly.app.ui.components.VerticalSpacer
import dev.charaly.app.ui.theme.Charaly
import dev.charaly.app.ui.theme.toColor
import dev.charaly.runtime.domain.CharacterActivity
import dev.charaly.runtime.domain.CharacterDefinition
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.EventCondition
import dev.charaly.runtime.domain.EventEffect
import dev.charaly.runtime.domain.EventTrigger
import dev.charaly.runtime.domain.Location
import dev.charaly.runtime.domain.PackArtwork
import dev.charaly.runtime.domain.PackColor
import dev.charaly.runtime.domain.PackEventDefinition
import dev.charaly.runtime.domain.PackTheme
import dev.charaly.runtime.domain.RelationshipDelta
import dev.charaly.runtime.domain.RelationshipType
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.StoryThreadStatus
import dev.charaly.runtime.domain.StoryTime
import dev.charaly.runtime.engine.effectPreview
import dev.charaly.runtime.model.ModelProfileLibrary
import dev.charaly.runtime.pack.PackAuthoring
import dev.charaly.runtime.presentation.CharacterCard
import dev.charaly.runtime.presentation.EmptyState
import dev.charaly.runtime.presentation.LocationCard
import dev.charaly.runtime.presentation.ResolvedTheme

/**
 * The authoring screens: the Story Pack creator and the three editors.
 *
 * Two rules hold across all of them:
 *  * every control here is one of Charaly's own components, so authoring looks
 *    like the rest of the app rather than like a developer form;
 *  * every save goes through the domain model's own constructors, which validate.
 *    A screen that cannot produce an invalid object is worth more than one that
 *    validates after the fact.
 */

// ---------------------------------------------------------------------------
// Shared authoring widgets
// ---------------------------------------------------------------------------

/**
 * A text field that looks like part of Charaly rather than a Material form.
 *
 * `OutlinedTextField`'s floating label is the most recognisable "developer form"
 * element in Material, and the creator is exactly where that would look wrong.
 * The box is a filled surface with the same radius as a card, which is what makes
 * an editing screen feel like the rest of the product.
 */
@Composable
private fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    Column(modifier = modifier.padding(vertical = 6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 5.dp)
                .clip(Charaly.tokens.radii.shapeSm)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .heightIn(min = 22.dp),
        ) {
            if (value.isEmpty() && placeholder.isNotBlank()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                minLines = minLines,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(Charaly.accent.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = label },
            )
        }
    }
}

/** A one-item-per-line text area, joined back into the field's single string. */
@Composable
private fun LinesField(
    label: String,
    values: List<String>,
    onValuesChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
) {
    LabeledField(
        label = label,
        value = values.joinToString("\n"),
        onValueChange = { raw -> onValuesChange(raw.lines().map { it.trim() }.filter { it.isNotEmpty() }) },
        modifier = modifier,
        placeholder = hint,
        singleLine = false,
        minLines = 3,
    )
}

/**
 * A number field.
 *
 * Digits only, always clamped into range, because every numeric field in the
 * domain model has a `require` behind it and a half-typed value must never be
 * able to crash a screen.
 */
@Composable
private fun NumberField(
    label: String,
    value: Long,
    onChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
    min: Long = 0L,
    max: Long = 999L,
) {
    LabeledField(
        label = label,
        value = value.toString(),
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(6)
            if (digits.isNotEmpty()) {
                onChange(digits.toLongOrNull()?.coerceIn(min, max) ?: min)
            }
        },
        modifier = modifier,
    )
}

/** A day / hour / minute picker over [StoryTime], with every bound clamped. */
@Composable
private fun TimeFields(time: StoryTime, onChange: (StoryTime) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.xs),
    ) {
        NumberField(
            label = "Day",
            value = time.day.toLong(),
            onChange = { onChange(time.copy(day = it.coerceIn(1L, 99L).toInt())) },
            min = 1L,
            max = 99L,
            modifier = Modifier.weight(1f),
        )
        NumberField(
            label = "Hour",
            value = time.hour.toLong(),
            onChange = { onChange(time.copy(hour = it.coerceIn(0L, 23L).toInt())) },
            max = 23L,
            modifier = Modifier.weight(1f),
        )
        NumberField(
            label = "Minute",
            value = time.minute.toLong(),
            onChange = { onChange(time.copy(minute = it.coerceIn(0L, 59L).toInt())) },
            max = 59L,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A hex colour field with a live swatch beside it. */
@Composable
private fun HexField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LabeledField(
            label = label,
            value = value,
            onValueChange = onValueChange,
            placeholder = "#8B7BF0",
            modifier = Modifier.weight(1f),
        )
        Swatch(hex = value, onPick = onPick, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp))
    }
}

/**
 * A collapsible section.
 *
 * The character editor has ten sections; showing them all at once turns editing
 * into a wall of fields, which is how authoring tools lose people.
 */
@Composable
private fun CollapsibleSection(
    title: String,
    subtitle: String = "",
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")

    CharalyCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        container = MaterialTheme.colorScheme.surfaceContainer,
        onClick = { expanded = !expanded },
    ) {
        Column(Modifier.animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (subtitle.isNotBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(rotation),
                )
            }
            if (expanded) {
                Column(Modifier.padding(top = 4.dp)) { content() }
            }
        }
    }
}

/** A labelled switch row: at least 48dp tall, with a caption for the why. */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String = "",
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
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
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** A -/+ stepper over a small integer range. */
@Composable
private fun StepperRow(
    title: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    valueLabel: String = value.toString(),
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        CharalyIconButton(
            icon = Icons.Filled.Remove,
            contentDescription = "Decrease $title",
            onClick = { onChange((value - 1).coerceIn(range.first, range.last)) },
            enabled = value > range.first,
        )
        Text(
            text = valueLabel,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        CharalyIconButton(
            icon = Icons.Filled.Add,
            contentDescription = "Increase $title",
            onClick = { onChange((value + 1).coerceIn(range.first, range.last)) },
            enabled = value < range.last,
        )
    }
}

/** The sticky Back / Save bar every editor ends with. */
@Composable
private fun EditorFooter(
    backLabel: String,
    onBack: () -> Unit,
    saveLabel: String,
    onSave: () -> Unit,
    saveEnabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .padding(
                start = Charaly.tokens.spacing.gutter,
                end = Charaly.tokens.spacing.gutter,
                top = Charaly.tokens.spacing.sm,
                bottom = Charaly.tokens.spacing.md,
            ),
        horizontalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.xs),
    ) {
        CharalyGhostButton(label = backLabel, onClick = onBack, modifier = Modifier.weight(1f))
        CharalyPrimaryButton(
            label = saveLabel,
            onClick = onSave,
            enabled = saveEnabled,
            modifier = Modifier.weight(1.3f),
        )
    }
}

/** A quiet paragraph of guidance, used wherever an author needs a rule explained. */
@Composable
private fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

/** An honest warning, in the warning colour rather than the error colour. */
@Composable
private fun WarningText(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 5.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(Charaly.colors.warning),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = Charaly.colors.warning,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** A back bar used by the editors and the read-only detail screens. */
@Composable
private fun ScreenHeader(
    title: String,
    subtitle: String,
    accent: Color,
    onBack: () -> Unit,
    avatarSize: androidx.compose.ui.unit.Dp = 40.dp,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = Charaly.tokens.spacing.xs,
                end = Charaly.tokens.spacing.gutter,
                top = Charaly.tokens.spacing.xl,
                bottom = Charaly.tokens.spacing.xs,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CharalyIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            onClick = onBack,
        )
        CharacterAvatar(
            seed = title,
            accent = accent,
            name = title.ifBlank { "?" },
            modifier = Modifier
                .padding(start = 4.dp)
                .size(avatarSize),
        )
        Column(Modifier.padding(start = 10.dp)) {
            Text(
                text = title.ifBlank { "Untitled" },
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The shared "this is gone" state for an editor or a detail screen. */
@Composable
private fun MissingThing(state: EmptyState, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = Charaly.tokens.spacing.xs, top = Charaly.tokens.spacing.xl)) {
            CharalyIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                onClick = onBack,
            )
        }
        EmptyStateView(
            state = state,
            modifier = Modifier.padding(top = Charaly.tokens.spacing.xxl),
        ) {
            CharalyPrimaryButton(label = "Back", onClick = onBack)
        }
    }
}

// ---------------------------------------------------------------------------
// Story Pack creator
// ---------------------------------------------------------------------------

private enum class CreatorStep(val title: String) {
    IDENTITY("Identity"),
    WORLD("World"),
    CHARACTERS("Characters"),
    LOCATIONS("Locations"),
    EVENTS("Events"),
    SCENARIOS("Openings"),
    MODEL("Model"),
    REVIEW("Review"),
}

/** A character the author has declared but not written yet. */
private data class CharacterStub(val name: String, val role: String)

/** A place the author has declared but not written yet. */
private data class LocationStub(val name: String, val summary: String)

/** An event the author has declared but not wired up yet. */
private data class EventStub(val name: String, val note: String)

/** A way into the world. Its start place is an index into the location list. */
private data class ScenarioStub(val title: String, val tagline: String, val locationIndex: Int)

/** Colours offered by the swatches, so a new world is never stuck on grey. */
private val AccentPresets = listOf(
    "#8B7BF0",
    "#E0659B",
    "#F2B25C",
    "#5BD6A4",
    "#5FB8F2",
    "#F2735F",
    "#C6D94C",
    "#B0A8FF",
)

@Composable
private fun Swatch(
    hex: String,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    description: String = "Colour $hex",
) {
    // The tap target is 48dp; the circle inside it stays chip-sized.
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .semantics { contentDescription = description }
            .clickable {
                val index = AccentPresets.indexOf(hex.trim().uppercase())
                onPick(AccentPresets[(index + 1).mod(AccentPresets.size)])
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(PackColor.parse(hex).toColor())
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
    }
}

/** The scrollable body of one wizard step. */
@Composable
private fun CreatorScroll(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Charaly.tokens.spacing.gutter),
    ) {
        content()
        VerticalSpacer(Charaly.tokens.spacing.xl)
    }
}

/**
 * The Story Pack creator: an eight-step wizard.
 *
 * It builds a real [StoryPack] through [PackAuthoring.pack], which validates every
 * cross reference at construction time. That validation is the point: a pack that
 * referenced a character who does not exist would otherwise fail much later, as a
 * silently rejected world event.
 */
@Composable
fun PackCreatorScreen(
    packs: List<StoryPack>,
    onBack: () -> Unit,
    onSave: (StoryPack) -> Unit,
) {
    var step by remember { mutableIntStateOf(0) }
    var title by remember { mutableStateOf("") }
    var tagline by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var genreText by remember { mutableStateOf("") }
    var primary by remember { mutableStateOf("#8B7BF0") }
    var secondary by remember { mutableStateOf("#E0659B") }
    var accent by remember { mutableStateOf("#F2B25C") }
    var era by remember { mutableStateOf("") }
    var tone by remember { mutableStateOf("") }
    var worldRules by remember { mutableStateOf("") }
    var fandomNotice by remember { mutableStateOf("") }
    var profileId by remember { mutableStateOf(ModelProfileLibrary.BALANCED) }

    val characters = remember {
        mutableStateListOf(CharacterStub(name = "The Protagonist", role = ""))
    }
    val locations = remember {
        mutableStateListOf(LocationStub(name = "The First Place", summary = ""))
    }
    val events = remember {
        mutableStateListOf(EventStub(name = "The story begins", note = ""))
    }
    val scenarios = remember {
        mutableStateListOf(ScenarioStub(title = "The Beginning", tagline = "", locationIndex = 0))
    }

    val genres = remember(genreText) { genreText.split(',').map { it.trim() }.filter { it.isNotEmpty() } }
    val rules = remember(worldRules) {
        worldRules.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }
    val theme = remember(primary, secondary, accent, tone) {
        ResolvedTheme.of(
            PackTheme(
                primaryHex = primary,
                secondaryHex = secondary,
                accentHex = accent,
                mood = tone,
            ),
        )
    }
    val draftArtwork = remember(title) { PackArtwork.generated(seed = title) }
    val profile = remember(profileId) { ModelProfileLibrary.resolve(profileId) }
    val existingIds = remember(packs) { packs.map { it.id.value }.toSet() }

    // Only the identity step has a hard minimum: everything else can honestly be
    // finished later, and the review step says so out loud.
    val canAdvance = title.isNotBlank()

    Column(Modifier.fillMaxSize()) {
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
                contentDescription = if (step == 0) "Close creator" else "Previous step",
                onClick = { if (step == 0) onBack() else step -= 1 },
            )
            Column(Modifier.padding(start = 4.dp)) {
                Eyebrow("Step ${step + 1} of ${CreatorStep.entries.size}")
                SectionHeader(title = CreatorStep.entries[step].title)
            }
        }

        LazyRow(
            contentPadding = PaddingValues(
                start = Charaly.tokens.spacing.gutter,
                end = Charaly.tokens.spacing.gutter,
                bottom = Charaly.tokens.spacing.sm,
            ),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(CreatorStep.entries.size, key = { CreatorStep.entries[it].name }) { index ->
                CharalyFilterChip(
                    label = CreatorStep.entries[index].title,
                    selected = index == step,
                    onClick = { step = index },
                )
            }
        }

        Box(Modifier.weight(1f)) {
            when (CreatorStep.entries[step]) {
                CreatorStep.IDENTITY -> CreatorScroll {
                    LabeledField(
                        label = "Title",
                        value = title,
                        onValueChange = { title = it },
                        placeholder = "The Last Kingdom",
                    )
                    LabeledField(
                        label = "Tagline",
                        value = tagline,
                        onValueChange = { tagline = it },
                        placeholder = "The crown remembers everything.",
                    )
                    LabeledField(
                        label = "Description",
                        value = description,
                        onValueChange = { description = it },
                        singleLine = false,
                        minLines = 4,
                        placeholder = "What is this world about?",
                    )
                    LabeledField(
                        label = "Genres",
                        value = genreText,
                        onValueChange = { genreText = it },
                        placeholder = "Fantasy, Political, Adventure",
                    )
                    Caption("Separate genres with commas. They become the chips on your pack card.")
                    if (genres.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier.padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.xs),
                        ) {
                            items(genres, key = { it }) { GenreChip(it) }
                        }
                    }
                    Eyebrow("Accent colours", modifier = Modifier.padding(top = Charaly.tokens.spacing.md))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Swatch(primary, { primary = it }, description = "Primary colour $primary")
                        Swatch(secondary, { secondary = it }, description = "Secondary colour $secondary")
                        Swatch(accent, { accent = it }, description = "Accent colour $accent")
                    }
                    HexField("Primary hex", primary, { primary = it }, { primary = it })
                    HexField("Secondary hex", secondary, { secondary = it }, { secondary = it })
                    HexField("Accent hex", accent, { accent = it }, { accent = it })
                    Caption("Tap a swatch to cycle the palette. The cover redraws as you type.")
                    PackArt(
                        artwork = draftArtwork,
                        theme = theme,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Charaly.tokens.spacing.sm)
                            .heightIn(min = 150.dp)
                            .clip(Charaly.tokens.radii.shapeLg),
                    )
                }

                CreatorStep.WORLD -> CreatorScroll {
                    LabeledField(
                        label = "Era",
                        value = era,
                        onValueChange = { era = it },
                        placeholder = "Late summer, an age of charters",
                    )
                    LabeledField(
                        label = "Tone",
                        value = tone,
                        onValueChange = { tone = it },
                        placeholder = "Courtly, tired, a little cruel",
                    )
                    LinesField(
                        label = "World rules and lore",
                        values = rules,
                        onValuesChange = { worldRules = it.joinToString("\n") },
                        hint = "One rule per line. Each becomes a piece of world lore.",
                    )
                    LabeledField(
                        label = "Fandom notice",
                        value = fandomNotice,
                        onValueChange = { fandomNotice = it },
                        singleLine = false,
                        minLines = 2,
                        placeholder = "Optional: make the nature of this pack explicit",
                    )
                }

                CreatorStep.CHARACTERS -> CreatorScroll {
                    SectionHeader(
                        title = "Characters",
                        subtitle = "Everyone who can appear in a scene",
                    )
                    Caption(
                        "Names only for now. Open any character afterwards to write their voice, " +
                            "their goals and what they must not know.",
                    )
                    characters.forEachIndexed { index, stub ->
                        CharalyCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = Charaly.tokens.spacing.sm),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LabeledField(
                                    label = "Character ${index + 1}",
                                    value = stub.name,
                                    onValueChange = { raw ->
                                        characters[index] = stub.copy(name = raw)
                                    },
                                    placeholder = "Name",
                                    modifier = Modifier.weight(1f),
                                )
                                CharalyIconButton(
                                    icon = Icons.Filled.Remove,
                                    contentDescription = "Remove ${stub.name.ifBlank { "character ${index + 1}" }}",
                                    onClick = { characters.removeAt(index) },
                                    modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
                                )
                            }
                            LabeledField(
                                label = "Role",
                                value = stub.role,
                                onValueChange = { raw -> characters[index] = stub.copy(role = raw) },
                                placeholder = "Cartographer, rival, older sister",
                            )
                        }
                    }
                    CharalyTextButton(
                        label = "Add a character",
                        onClick = {
                            characters.add(CharacterStub(name = "Character ${characters.size + 1}", role = ""))
                        },
                        icon = Icons.Filled.Add,
                    )
                    if (characters.isEmpty()) {
                        WarningText("No characters yet. A story cannot start without anyone to play.")
                    }
                }

                CreatorStep.LOCATIONS -> CreatorScroll {
                    SectionHeader(title = "Locations", subtitle = "Where scenes can happen")
                    Caption(
                        "Places decide where movement is legal. Every place is connected to every " +
                            "other one at first; tighten that in the location editor.",
                    )
                    locations.forEachIndexed { index, stub ->
                        CharalyCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = Charaly.tokens.spacing.sm),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LabeledField(
                                    label = "Location ${index + 1}",
                                    value = stub.name,
                                    onValueChange = { raw -> locations[index] = stub.copy(name = raw) },
                                    placeholder = "Name",
                                    modifier = Modifier.weight(1f),
                                )
                                CharalyIconButton(
                                    icon = Icons.Filled.Remove,
                                    contentDescription = "Remove ${stub.name.ifBlank { "location ${index + 1}" }}",
                                    onClick = { locations.removeAt(index) },
                                    modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
                                )
                            }
                            LabeledField(
                                label = "One-line summary",
                                value = stub.summary,
                                onValueChange = { raw -> locations[index] = stub.copy(summary = raw) },
                                placeholder = "What happens here",
                            )
                        }
                    }
                    CharalyTextButton(
                        label = "Add a location",
                        onClick = {
                            locations.add(LocationStub(name = "Location ${locations.size + 1}", summary = ""))
                        },
                        icon = Icons.Filled.Add,
                    )
                    if (locations.isEmpty()) {
                        WarningText("No locations yet. One default place will be created for you.")
                    }
                }

                CreatorStep.EVENTS -> CreatorScroll {
                    SectionHeader(title = "Events", subtitle = "How the world moves on its own")
                    Caption(
                        "Each event starts as a story-start beat. Open it afterwards to add real " +
                            "conditions and structured effects.",
                    )
                    events.forEachIndexed { index, stub ->
                        CharalyCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = Charaly.tokens.spacing.sm),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LabeledField(
                                    label = "Event ${index + 1}",
                                    value = stub.name,
                                    onValueChange = { raw -> events[index] = stub.copy(name = raw) },
                                    placeholder = "Name",
                                    modifier = Modifier.weight(1f),
                                )
                                CharalyIconButton(
                                    icon = Icons.Filled.Remove,
                                    contentDescription = "Remove ${stub.name.ifBlank { "event ${index + 1}" }}",
                                    onClick = { events.removeAt(index) },
                                    modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
                                )
                            }
                            LabeledField(
                                label = "What happens",
                                value = stub.note,
                                onValueChange = { raw -> events[index] = stub.copy(note = raw) },
                                placeholder = "The lamps go out across the whole quarter",
                            )
                        }
                    }
                    CharalyTextButton(
                        label = "Add an event",
                        onClick = {
                            events.add(EventStub(name = "Event ${events.size + 1}", note = ""))
                        },
                        icon = Icons.Filled.Add,
                    )
                    if (events.isEmpty()) {
                        WarningText("No events yet, so nothing will ever change on its own.")
                    }
                }

                CreatorStep.SCENARIOS -> CreatorScroll {
                    SectionHeader(title = "Starting scenarios", subtitle = "Ways into this world")
                    Caption("Each opening picks where the story begins. You can add more cast and events later.")
                    scenarios.forEachIndexed { index, stub ->
                        CharalyCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = Charaly.tokens.spacing.sm),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LabeledField(
                                    label = "Opening ${index + 1}",
                                    value = stub.title,
                                    onValueChange = { raw -> scenarios[index] = stub.copy(title = raw) },
                                    placeholder = "Title",
                                    modifier = Modifier.weight(1f),
                                )
                                CharalyIconButton(
                                    icon = Icons.Filled.Remove,
                                    contentDescription = "Remove ${stub.title.ifBlank { "opening ${index + 1}" }}",
                                    onClick = { scenarios.removeAt(index) },
                                    modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
                                )
                            }
                            LabeledField(
                                label = "Tagline",
                                value = stub.tagline,
                                onValueChange = { raw -> scenarios[index] = stub.copy(tagline = raw) },
                                placeholder = "The morning the letter arrives",
                            )
                            if (locations.isEmpty()) {
                                Caption("Add a location first, then choose where this opening begins.")
                            } else {
                                Eyebrow("Begins at")
                                LazyRow(
                                    modifier = Modifier.padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    items(locations.size, key = { "opening-location-$it" }) { locationIndex ->
                                        CharalyFilterChip(
                                            label = locations[locationIndex].name.ifBlank { "Location ${locationIndex + 1}" },
                                            selected = stub.locationIndex == locationIndex,
                                            onClick = {
                                                scenarios[index] = stub.copy(locationIndex = locationIndex)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    CharalyTextButton(
                        label = "Add an opening",
                        onClick = {
                            scenarios.add(
                                ScenarioStub(
                                    title = "Opening ${scenarios.size + 1}",
                                    tagline = "",
                                    locationIndex = 0,
                                ),
                            )
                        },
                        icon = Icons.Filled.Add,
                    )
                    if (scenarios.isEmpty()) {
                        WarningText("No openings yet. The first location will be used as the start.")
                    }
                }

                CreatorStep.MODEL -> CreatorScroll {
                    SectionHeader(
                        title = "Model profile",
                        subtitle = "How the local model should behave in this world",
                    )
                    Caption(
                        "The default for stories from this pack. A story copies the resolved settings " +
                            "when it is created, so changing this later will not alter existing stories.",
                    )
                    FlowChips(
                        labels = ModelProfileLibrary.all.map { it.name },
                        selected = setOf(profile.name),
                        onToggle = { name ->
                            ModelProfileLibrary.all.firstOrNull { it.name == name }?.let { profileId = it.id }
                        },
                    )
                    CharalyCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Charaly.tokens.spacing.md),
                        container = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Column {
                            Text(
                                text = profile.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            BodyProse(
                                text = profile.description.ifBlank { profile.summaryLine },
                                modifier = Modifier.padding(top = 6.dp),
                            )
                            HairLine(modifier = Modifier.padding(vertical = Charaly.tokens.spacing.sm))
                            DetailRow(label = "Sampler", value = profile.sampler.temperament.label)
                            DetailRow(label = "Narrative style", value = profile.narrativeStyle.label)
                            if (profile.responseFormat.isNotBlank()) {
                                DetailRow(label = "Response format", value = profile.responseFormat)
                            }
                        }
                    }
                }

                CreatorStep.REVIEW -> CreatorScroll {
                    PackArt(
                        artwork = draftArtwork,
                        theme = theme,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp)
                            .clip(Charaly.tokens.radii.shapeLg),
                    )
                    SectionHeader(
                        title = title.ifBlank { "Untitled world" },
                        modifier = Modifier.padding(top = Charaly.tokens.spacing.md),
                    )
                    if (tagline.isNotBlank()) {
                        Text(
                            text = tagline,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (description.isNotBlank()) {
                        BodyProse(text = description, modifier = Modifier.padding(top = 8.dp))
                    }
                    if (genres.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier.padding(top = Charaly.tokens.spacing.sm),
                            horizontalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.xs),
                        ) {
                            items(genres, key = { it }) { GenreChip(it) }
                        }
                    }
                    LazyRow(
                        modifier = Modifier.padding(top = Charaly.tokens.spacing.md),
                        horizontalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.xs),
                    ) {
                        items(4, key = { index -> "review-stat-$index" }) { index ->
                            val stats = listOf(
                                "Characters" to characters.size.toString(),
                                "Locations" to locations.size.toString(),
                                "Events" to events.size.toString(),
                                "Openings" to scenarios.size.toString(),
                            )
                            StatPill(label = stats[index].first, value = stats[index].second)
                        }
                    }
                    DetailRow(label = "Model profile", value = profile.name)
                    DetailRow(label = "Era", value = era.ifBlank { "Not set" })
                    DetailRow(label = "Tone", value = tone.ifBlank { "Not set" })
                    DetailRow(label = "World rules", value = "${rules.size} piece(s) of lore")

                    Eyebrow("Before you publish", modifier = Modifier.padding(top = Charaly.tokens.spacing.lg))
                    if (characters.isEmpty()) {
                        WarningText("No characters yet. A story cannot start without anyone to play.")
                    }
                    if (locations.isEmpty()) {
                        WarningText("No locations yet. One default place will be created for you.")
                    }
                    if (events.isEmpty()) {
                        WarningText("No events yet, so nothing will change on its own.")
                    }
                    if (scenarios.isEmpty()) {
                        WarningText("No openings yet. The first location will be used as the start.")
                    }
                    if (rules.isEmpty()) {
                        WarningText("No world rules yet. The model will invent lore instead of using yours.")
                    }
                    if (tagline.isBlank()) {
                        WarningText("No tagline. Your pack will be listed with only its title.")
                    }
                    if (genres.isEmpty()) {
                        WarningText("No genres. Packs are easier to find with at least one.")
                    }
                    Caption("You can open every character, place and event afterwards and write them properly.")
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .navigationBarsPadding()
                .padding(
                    start = Charaly.tokens.spacing.gutter,
                    end = Charaly.tokens.spacing.gutter,
                    top = Charaly.tokens.spacing.sm,
                    bottom = Charaly.tokens.spacing.md,
                ),
            horizontalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.xs),
        ) {
            if (step > 0) {
                CharalyGhostButton(label = "Back", onClick = { step -= 1 }, modifier = Modifier.weight(1f))
            }
            if (step < CreatorStep.entries.lastIndex) {
                CharalyPrimaryButton(
                    label = "Next",
                    onClick = { step += 1 },
                    enabled = canAdvance,
                    modifier = Modifier.weight(1.4f),
                )
            } else {
                CharalyPrimaryButton(
                    label = "Create Story Pack",
                    onClick = {
                        onSave(
                            buildPack(
                                title = title,
                                tagline = tagline,
                                description = description,
                                genres = genres,
                                theme = theme,
                                era = era,
                                tone = tone,
                                rules = rules,
                                fandomNotice = fandomNotice,
                                profileId = profileId,
                                characters = characters.toList(),
                                locations = locations.toList(),
                                events = events.toList(),
                                scenarios = scenarios.toList(),
                                existingIds = existingIds,
                            ),
                        )
                    },
                    enabled = title.isNotBlank(),
                    modifier = Modifier.weight(1.4f),
                )
            }
        }
    }
}

/** A wrapping row of filter chips, used where the list of choices is a set. */
@Composable
private fun FlowChips(
    labels: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { label ->
                    CharalyFilterChip(
                        label = label,
                        selected = label in selected,
                        onClick = { onToggle(label) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * Assembles the pack.
 *
 * Every entry is a placeholder with a real id and real cross references, so the
 * resulting pack is immediately valid and immediately editable: the creator never
 * produces something the engine would reject at story start. If the author left
 * the world without a place, one is added here rather than failing later.
 */
private fun buildPack(
    title: String,
    tagline: String,
    description: String,
    genres: List<String>,
    theme: ResolvedTheme,
    era: String,
    tone: String,
    rules: List<String>,
    fandomNotice: String,
    profileId: String,
    characters: List<CharacterStub>,
    locations: List<LocationStub>,
    events: List<EventStub>,
    scenarios: List<ScenarioStub>,
    existingIds: Set<String>,
): StoryPack {
    val safeTitle = title.trim().ifBlank { "Untitled world" }
    val base = slug(safeTitle)
    var packId = base
    var attempt = 2
    while (packId in existingIds) {
        packId = "$base-$attempt"
        attempt++
    }

    // A world with no place cannot start a story, so a default one is guaranteed.
    val locationStubs = locations.ifEmpty { listOf(LocationStub(name = "The First Place", summary = "")) }
    val placeIds = locationStubs.indices.map { "place-${it + 1}" }
    val personIds = characters.indices.map { "person-${it + 1}" }
    val homePlace = placeIds.first()

    val builtLocations = locationStubs.mapIndexed { index, stub ->
        val name = stub.name.trim().ifBlank { "Location ${index + 1}" }
        val summary = stub.summary.trim()
        PackAuthoring.location(
            id = placeIds[index],
            name = name,
            summary = summary.ifBlank { "A place in $safeTitle" },
            description = summary.ifBlank {
                "$name, part of $safeTitle. Open the location editor to describe it properly."
            },
            connections = placeIds - placeIds[index],
            accent = colorHex(theme.primary),
            seed = "$packId-${placeIds[index]}",
        )
    }

    val builtCharacters = characters.mapIndexed { index, stub ->
        val role = stub.role.trim()
        PackAuthoring.character(
            id = personIds[index],
            name = stub.name.trim().ifBlank { "Character ${index + 1}" },
            tagline = role,
            description = role.ifBlank {
                "A character in $safeTitle. Open the character editor to write them properly."
            },
            personality = "",
            role = role,
            location = homePlace,
            seed = "$packId-${personIds[index]}",
        )
    }

    val firstPerson = personIds.firstOrNull()
    val builtEvents = events.mapIndexed { index, stub ->
        val note = stub.note.trim()
        PackAuthoring.event(
            id = "event-${index + 1}",
            title = stub.name.trim().ifBlank { "Event ${index + 1}" },
            trigger = EventTrigger.StoryStart(),
            description = note.ifBlank { "Something the world does on its own." },
            seed = "A moment that belongs to $safeTitle.",
            effects = listOf(
                if (firstPerson != null) {
                    EventEffect.ChangeActivity(
                        characterId = CharacterId(firstPerson),
                        activity = CharacterActivity.IDLE,
                    )
                } else {
                    // No cast yet, so the one effect that needs no ids is used.
                    EventEffect.SetVariable(key = "world_open", value = "true")
                },
            ),
            participants = listOfNotNull(firstPerson),
            location = homePlace,
            seedArt = "$packId-event-${index + 1}",
        )
    }

    val builtScenarios = scenarios.mapIndexed { index, stub ->
        val place = placeIds.getOrNull(stub.locationIndex) ?: homePlace
        val eventId = "event-${index + 1}"
        PackAuthoring.scenario(
            id = "opening-${index + 1}",
            title = stub.title.trim().ifBlank { "Opening ${index + 1}" },
            tagline = stub.tagline.trim(),
            description = "A way into $safeTitle, beginning at ${builtLocations.first { it.id.value == place }.name}.",
            startTime = StoryTime(day = 1, hour = 9, minute = 0),
            startLocation = place,
            focus = firstPerson,
            cast = personIds,
            events = builtEvents.filter { it.id == eventId }.map { it.id },
            artSeed = "$packId-opening-${index + 1}",
        )
    }

    return PackAuthoring.pack(
        id = packId,
        title = safeTitle,
        description = description.trim().ifBlank { tagline.trim() },
        identity = PackAuthoring.identity(
            tagline = tagline.trim(),
            genres = genres,
            coverSeed = packId,
            mood = tone.trim(),
            primary = colorHex(theme.primary),
            secondary = colorHex(theme.secondary),
            accent = colorHex(theme.accent),
            era = era.trim(),
            tone = tone.trim(),
            notice = fandomNotice.trim(),
            featured = false,
            demo = false,
        ),
        characters = builtCharacters,
        locations = builtLocations,
        events = builtEvents,
        scenarios = builtScenarios,
        lore = rules.mapIndexed { index, line ->
            PackAuthoring.lore(id = "lore-${index + 1}", title = "Note ${index + 1}", content = line)
        },
        startTime = StoryTime(day = 1, hour = 9, minute = 0),
        startLocations = personIds.associateWith { homePlace },
        defaultModelProfileId = profileId,
    )
}

/** ARGB long back to a `#RRGGBB` string for the theme fields. */
private fun colorHex(value: Long): String = "#%06X".format(value and 0xFFFFFF)

/** A filesystem- and id-safe slug, used for the new pack's id. */
private fun slug(value: String): String =
    value.lowercase()
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .split('-')
        .filter { it.isNotEmpty() }
        .joinToString("-")
        .take(40)
        .ifBlank { "story-pack" }

// ---------------------------------------------------------------------------
// Character editor
// ---------------------------------------------------------------------------

/**
 * The character editor.
 *
 * Ten collapsible sections instead of one long form: a character has more fields
 * than anyone can hold in their head, and showing them all at once makes the
 * editor feel like a database row.
 */
@Composable
fun CharacterEditorScreen(
    pack: StoryPack?,
    characterId: String,
    onBack: () -> Unit,
    onSave: (CharacterDefinition) -> Unit,
) {
    val packOrNull = pack
    val original = packOrNull?.characters?.firstOrNull { it.id.value == characterId }
    if (packOrNull == null || original == null) {
        MissingThing(
            state = EmptyState(
                title = "That character is not here.",
                body = "It may have been removed from this pack.",
                artSeed = "charaly-missing-character",
            ),
            onBack = onBack,
        )
        return
    }

    var draft by remember { mutableStateOf(original) }
    // The domain model requires a non-blank name, so the raw text is kept here and
    // the object only ever receives a usable name.
    var nameText by remember { mutableStateOf(original.name) }
    val accent = draft.accentLong().takeIf { it != 0L }?.toColor() ?: Charaly.accent.primary

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = draft.name,
            subtitle = packOrNull.title,
            accent = accent,
            onBack = onBack,
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = Charaly.tokens.spacing.gutter,
                end = Charaly.tokens.spacing.gutter,
                bottom = Charaly.tokens.spacing.md,
            ),
        ) {
            item {
                CollapsibleSection("Identity", "Who they are on the page", initiallyExpanded = true) {
                    LabeledField(
                        label = "Name",
                        value = nameText,
                        onValueChange = { raw ->
                            nameText = raw
                            if (raw.isNotBlank()) draft = draft.copy(name = raw)
                        },
                    )
                    LabeledField(
                        label = "Tagline",
                        value = draft.tagline,
                        onValueChange = { raw -> draft = draft.copy(tagline = raw) },
                        placeholder = "The one line under their name",
                    )
                    LabeledField(
                        label = "Description",
                        value = draft.description,
                        onValueChange = { raw ->
                            draft = draft.copy(
                                description = raw,
                                shortDescription = raw.lineSequence().firstOrNull()?.trim().orEmpty(),
                            )
                        },
                        singleLine = false,
                        minLines = 3,
                        placeholder = "What they look like, what they do",
                    )
                    LabeledField(
                        label = "Role",
                        value = draft.identityRole,
                        onValueChange = { raw -> draft = draft.copy(identityRole = raw) },
                        placeholder = "Who they are to the story",
                    )
                    LabeledField(
                        label = "Background",
                        value = draft.background,
                        onValueChange = { raw -> draft = draft.copy(background = raw) },
                        singleLine = false,
                        minLines = 3,
                    )
                }

                CollapsibleSection("Personality") {
                    LabeledField(
                        label = "Personality",
                        value = draft.personality,
                        onValueChange = { raw -> draft = draft.copy(personality = raw) },
                        singleLine = false,
                        minLines = 3,
                    )
                    LinesField(
                        label = "Fears",
                        values = draft.fears,
                        onValuesChange = { draft = draft.copy(fears = it) },
                        hint = "One fear per line",
                    )
                }

                CollapsibleSection("Goals") {
                    LinesField(
                        label = "Goals",
                        values = draft.goals,
                        onValuesChange = { draft = draft.copy(goals = it) },
                        hint = "One goal per line",
                    )
                }

                CollapsibleSection("Speech style", "How their voice is built") {
                    LabeledField(
                        label = "Tone",
                        value = draft.speakingStyle.tone,
                        onValueChange = { raw ->
                            draft = draft.copy(speakingStyle = draft.speakingStyle.copy(tone = raw))
                        },
                    )
                    LabeledField(
                        label = "Vocabulary",
                        value = draft.speakingStyle.vocabulary,
                        onValueChange = { raw ->
                            draft = draft.copy(speakingStyle = draft.speakingStyle.copy(vocabulary = raw))
                        },
                    )
                    LinesField(
                        label = "Quirks",
                        values = draft.speakingStyle.quirks,
                        onValuesChange = { lines ->
                            draft = draft.copy(speakingStyle = draft.speakingStyle.copy(quirks = lines))
                        },
                        hint = "One habit per line",
                    )
                    LinesField(
                        label = "Never",
                        values = draft.speakingStyle.avoids,
                        onValuesChange = { lines ->
                            draft = draft.copy(speakingStyle = draft.speakingStyle.copy(avoids = lines))
                        },
                        hint = "Things this character would never do",
                    )
                }

                CollapsibleSection(
                    "Relationships",
                    "Read-only: relationships belong to the world, not the character",
                ) {
                    val relations = packOrNull.initialRelationships.filter {
                        it.sourceId.value == characterId || it.targetId.value == characterId
                    }
                    if (relations.isEmpty()) {
                        Caption("No ties recorded in this pack yet.")
                    } else {
                        relations.forEach { relation ->
                            val otherId = if (relation.sourceId.value == characterId) {
                                relation.targetId
                            } else {
                                relation.sourceId
                            }
                            DetailRow(
                                label = packOrNull.character(otherId)?.name ?: otherId.value,
                                value = relation.describe(CharacterId(characterId)),
                            )
                        }
                    }
                }

                CollapsibleSection(
                    "Knowledge boundaries",
                    "What this character must not know unless the world says so",
                ) {
                    LinesField(
                        label = "Boundaries",
                        values = draft.knowledgeBoundaries,
                        onValuesChange = { lines -> draft = draft.copy(knowledgeBoundaries = lines) },
                        hint = "One boundary per line",
                    )
                    Caption(
                        "These are prompt hints. The real gate is the KnowledgeStore: a fact stays " +
                            "unknown until an event discloses it.",
                    )
                }

                CollapsibleSection("Starting state") {
                    Eyebrow("Starts at")
                    if (packOrNull.locations.isEmpty()) {
                        Caption("This pack has no locations yet, so there is nowhere to start.")
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(packOrNull.locations, key = { it.id.value }) { location ->
                                CharalyFilterChip(
                                    label = location.name,
                                    selected = draft.startingLocationId == location.id,
                                    onClick = {
                                        draft = draft.copy(
                                            startingLocationId = if (draft.startingLocationId == location.id) {
                                                null
                                            } else {
                                                location.id
                                            },
                                        )
                                    },
                                )
                            }
                        }
                    }
                    Eyebrow("Starts as", modifier = Modifier.padding(top = Charaly.tokens.spacing.sm))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(CharacterActivity.entries.size, key = { CharacterActivity.entries[it].name }) { index ->
                            val activity = CharacterActivity.entries[index]
                            CharalyFilterChip(
                                label = activity.label(),
                                selected = draft.startingActivity == activity,
                                onClick = {
                                    draft = draft.copy(
                                        startingActivity = if (draft.startingActivity == activity) {
                                            null
                                        } else {
                                            activity
                                        },
                                    )
                                },
                            )
                        }
                    }
                }

                CollapsibleSection("Greeting") {
                    LabeledField(
                        label = "Greeting",
                        value = draft.greeting,
                        onValueChange = { raw -> draft = draft.copy(greeting = raw) },
                        singleLine = false,
                        minLines = 3,
                        placeholder = "What they say the first time you meet",
                    )
                    LinesField(
                        label = "Example dialogue",
                        values = draft.exampleDialogue,
                        onValuesChange = { lines -> draft = draft.copy(exampleDialogue = lines) },
                        hint = "One exchange per line",
                    )
                }

                CollapsibleSection("System instructions") {
                    LabeledField(
                        label = "Instructions",
                        value = draft.systemInstructions,
                        onValueChange = { raw -> draft = draft.copy(systemInstructions = raw) },
                        singleLine = false,
                        minLines = 4,
                        placeholder = "How this character behaves, in the author's words.",
                    )
                    Caption("Layered under the world rules in the system prompt.")
                }

                CollapsibleSection("Advanced", "Artwork, memory and metadata") {
                    HexField(
                        label = "Accent hex",
                        value = draft.accentHex,
                        onValueChange = { raw -> draft = draft.copy(accentHex = raw) },
                        onPick = { raw -> draft = draft.copy(accentHex = raw) },
                    )
                    LabeledField(
                        label = "Avatar uri",
                        value = draft.avatarUri.orEmpty(),
                        onValueChange = { raw -> draft = draft.copy(avatarUri = raw.ifBlank { null }) },
                        placeholder = "content://... or file path",
                    )
                    LabeledField(
                        label = "Faction id",
                        value = draft.factionId,
                        onValueChange = { raw -> draft = draft.copy(factionId = raw) },
                        placeholder = "Leave blank if they belong to no faction",
                    )
                    LabeledField(
                        label = "Artwork seed",
                        value = draft.artwork.seed,
                        onValueChange = { raw ->
                            draft = draft.copy(artwork = draft.artwork.copy(seed = raw))
                        },
                        placeholder = "Same seed, same face",
                    )
                    StepperRow(
                        title = "Default memory importance",
                        value = draft.memoryPolicy.defaultImportance,
                        range = 1..5,
                        onChange = { next ->
                            draft = draft.copy(
                                memoryPolicy = draft.memoryPolicy.copy(defaultImportance = next),
                            )
                        },
                        valueLabel = "${draft.memoryPolicy.defaultImportance}/5",
                    )
                    Caption(
                        "A pack with no factions yet cannot accept a faction id: an empty field is " +
                            "the only safe value right now.",
                    )
                }
            }
        }

        EditorFooter(
            backLabel = "Cancel",
            onBack = onBack,
            saveLabel = "Save character",
            onSave = { onSave(draft) },
            saveEnabled = draft.name.isNotBlank(),
        )
    }
}

/** A sentence-case label for an activity, used by every chip that offers one. */
private fun CharacterActivity.label(): String =
    name.lowercase().replaceFirstChar { it.uppercase() }

// ---------------------------------------------------------------------------
// Location editor
// ---------------------------------------------------------------------------

/** The location editor: name, summary, adjacency, rules, occupants and lore. */
@Composable
fun LocationEditorScreen(
    pack: StoryPack?,
    locationId: String,
    onBack: () -> Unit,
    onSave: (Location) -> Unit,
) {
    val packOrNull = pack
    val original = packOrNull?.locations?.firstOrNull { it.id.value == locationId }
    if (packOrNull == null || original == null) {
        MissingThing(
            state = EmptyState(
                title = "That place is not here.",
                body = "It may have been removed from this pack.",
                artSeed = "charaly-missing-location",
            ),
            onBack = onBack,
        )
        return
    }

    var draft by remember { mutableStateOf(original) }
    var nameText by remember { mutableStateOf(original.name) }
    val accent = draft.accentLong().takeIf { it != 0L }?.toColor() ?: Charaly.accent.primary
    val others = packOrNull.locations.filter { it.id != draft.id }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = draft.name,
            subtitle = packOrNull.title,
            accent = accent,
            onBack = onBack,
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = Charaly.tokens.spacing.gutter,
                end = Charaly.tokens.spacing.gutter,
                top = Charaly.tokens.spacing.sm,
                bottom = Charaly.tokens.spacing.md,
            ),
        ) {
            item {
                LabeledField(
                    label = "Name",
                    value = nameText,
                    onValueChange = { raw ->
                        nameText = raw
                        if (raw.isNotBlank()) draft = draft.copy(name = raw)
                    },
                )
                LabeledField(
                    label = "Summary",
                    value = draft.summaryLine,
                    onValueChange = { raw -> draft = draft.copy(summaryLine = raw) },
                    placeholder = "One line, used on the location card",
                )
                LabeledField(
                    label = "Description",
                    value = draft.description,
                    onValueChange = { raw -> draft = draft.copy(description = raw) },
                    singleLine = false,
                    minLines = 4,
                    placeholder = "What this place looks and feels like",
                )

                CollapsibleSection("Connected locations", "Movement is only legal along these edges") {
                    if (others.isEmpty()) {
                        Caption("This pack has no other places yet, so nowhere leads anywhere.")
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(others, key = { it.id.value }) { location ->
                                CharalyFilterChip(
                                    label = location.name,
                                    selected = location.id in draft.connections,
                                    onClick = {
                                        draft = draft.copy(
                                            connections = if (location.id in draft.connections) {
                                                draft.connections - location.id
                                            } else {
                                                draft.connections + location.id
                                            },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }

                CollapsibleSection("Rules") {
                    LinesField(
                        label = "Standing rules",
                        values = draft.rules,
                        onValuesChange = { lines -> draft = draft.copy(rules = lines) },
                        hint = "One rule per line",
                    )
                    Caption("Shown on the location card and read as scene colour.")
                }

                CollapsibleSection("Starting occupants") {
                    if (packOrNull.characters.isEmpty()) {
                        Caption("This pack has no characters yet.")
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(packOrNull.characters, key = { it.id.value }) { character ->
                                CharalyFilterChip(
                                    label = character.name,
                                    selected = character.id in draft.startingOccupants,
                                    onClick = {
                                        draft = draft.copy(
                                            startingOccupants = if (character.id in draft.startingOccupants) {
                                                draft.startingOccupants - character.id
                                            } else {
                                                draft.startingOccupants + character.id
                                            },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }

                CollapsibleSection("Lore") {
                    LabeledField(
                        label = "Lore",
                        value = draft.lore,
                        onValueChange = { raw -> draft = draft.copy(lore = raw) },
                        singleLine = false,
                        minLines = 3,
                        placeholder = "What happened here, and what everyone here believes",
                    )
                }

                CollapsibleSection("Advanced") {
                    HexField(
                        label = "Accent hex",
                        value = draft.accentHex,
                        onValueChange = { raw -> draft = draft.copy(accentHex = raw) },
                        onPick = { raw -> draft = draft.copy(accentHex = raw) },
                    )
                    LabeledField(
                        label = "Artwork seed",
                        value = draft.artwork.seed,
                        onValueChange = { raw -> draft = draft.copy(artwork = draft.artwork.copy(seed = raw)) },
                        placeholder = "Same seed, same picture",
                    )
                    SwitchRow(
                        title = "Indoors",
                        subtitle = "Off for streets, fields and anywhere else the sky is visible",
                        checked = draft.isInterior,
                        onCheckedChange = { raw -> draft = draft.copy(isInterior = raw) },
                    )
                }
            }
        }

        EditorFooter(
            backLabel = "Cancel",
            onBack = onBack,
            saveLabel = "Save location",
            onSave = { onSave(draft) },
            saveEnabled = draft.name.isNotBlank(),
        )
    }
}

// ---------------------------------------------------------------------------
// Event editor
// ---------------------------------------------------------------------------

private enum class TriggerKind(val label: String) {
    STORY_START("At the start"),
    DELAY("After a delay"),
    AT_TIME("At a story time"),
    CONDITION("When the world lines up"),
}

/** The condition kinds this editor can author, all with real ids from the pack. */
private enum class ConditionKind(val label: String) {
    VARIABLE("A variable equals"),
    AT_LOCATION("Someone is somewhere"),
    THREAD_STAGE("A thread is at least stage"),
    TIME_REACHED("The clock reaches"),
    KNOWS_FACT("Someone knows a fact"),
    OTHER("Another condition"),
}

/** The effect kinds this editor can author. */
private enum class EffectKind(val label: String) {
    MOVE("Move someone"),
    ACTIVITY("Change what someone is doing"),
    RELATIONSHIP("Change a relationship"),
    VARIABLE("Set a world variable"),
    ADVANCE_THREAD("Advance a thread"),
    GRANT_KNOWLEDGE("Teach someone a fact"),
    CREATE_MEMORY("Make someone remember"),
    START_SCENE("Open a scene"),
    OTHER("Another effect"),
}

private fun EventCondition.kind(): ConditionKind = when (this) {
    is EventCondition.VariableEquals -> ConditionKind.VARIABLE
    is EventCondition.CharacterAtLocation -> ConditionKind.AT_LOCATION
    is EventCondition.ThreadStageAtLeast -> ConditionKind.THREAD_STAGE
    is EventCondition.StoryTimeReached -> ConditionKind.TIME_REACHED
    is EventCondition.CharacterKnowsFact -> ConditionKind.KNOWS_FACT
    else -> ConditionKind.OTHER
}

private fun EventEffect.kind(): EffectKind = when (this) {
    is EventEffect.MoveCharacter -> EffectKind.MOVE
    is EventEffect.ChangeActivity -> EffectKind.ACTIVITY
    is EventEffect.ChangeRelationship -> EffectKind.RELATIONSHIP
    is EventEffect.SetVariable -> EffectKind.VARIABLE
    is EventEffect.AdvanceThread -> EffectKind.ADVANCE_THREAD
    is EventEffect.GrantKnowledge -> EffectKind.GRANT_KNOWLEDGE
    is EventEffect.CreateMemory -> EffectKind.CREATE_MEMORY
    is EventEffect.StartScene -> EffectKind.START_SCENE
    else -> EffectKind.OTHER
}

/** The kinds the pack can actually support right now. */
private fun conditionKinds(pack: StoryPack): List<ConditionKind> = buildList {
    add(ConditionKind.VARIABLE)
    if (pack.characters.isNotEmpty() && pack.locations.isNotEmpty()) add(ConditionKind.AT_LOCATION)
    if (pack.initialStoryThreads.isNotEmpty()) add(ConditionKind.THREAD_STAGE)
    add(ConditionKind.TIME_REACHED)
    if (pack.characters.isNotEmpty() && pack.initialKnowledge.facts.isNotEmpty()) {
        add(ConditionKind.KNOWS_FACT)
    }
}

private fun effectKinds(pack: StoryPack): List<EffectKind> = buildList {
    if (pack.characters.isNotEmpty() && pack.locations.isNotEmpty()) add(EffectKind.MOVE)
    if (pack.characters.isNotEmpty()) add(EffectKind.ACTIVITY)
    if (pack.characters.size > 1) add(EffectKind.RELATIONSHIP)
    add(EffectKind.VARIABLE)
    if (pack.initialStoryThreads.isNotEmpty()) add(EffectKind.ADVANCE_THREAD)
    if (pack.characters.isNotEmpty() && pack.initialKnowledge.facts.isNotEmpty()) {
        add(EffectKind.GRANT_KNOWLEDGE)
        add(EffectKind.CREATE_MEMORY)
    }
    if (pack.characters.isNotEmpty() && pack.locations.isNotEmpty()) add(EffectKind.START_SCENE)
}

/** A fresh condition of the requested kind, or null if the pack cannot support it. */
private fun newCondition(kind: ConditionKind, pack: StoryPack): EventCondition? = when (kind) {
    ConditionKind.VARIABLE -> EventCondition.VariableEquals(key = "mood", value = "tense")
    ConditionKind.AT_LOCATION -> pack.characters.firstOrNull()?.let { character ->
        pack.locations.firstOrNull()?.let { location ->
            EventCondition.CharacterAtLocation(characterId = character.id, locationId = location.id)
        }
    }
    ConditionKind.THREAD_STAGE -> pack.initialStoryThreads.firstOrNull()?.let { thread ->
        EventCondition.ThreadStageAtLeast(threadId = thread.id, stage = 1)
    }
    ConditionKind.TIME_REACHED -> EventCondition.StoryTimeReached(StoryTime(day = 1, hour = 20, minute = 0))
    ConditionKind.KNOWS_FACT -> pack.characters.firstOrNull()?.let { character ->
        pack.initialKnowledge.facts.firstOrNull()?.let { fact ->
            EventCondition.CharacterKnowsFact(characterId = character.id, factId = fact.id)
        }
    }
    ConditionKind.OTHER -> null
}

/** A fresh effect of the requested kind, or null if the pack cannot support it. */
private fun newEffect(kind: EffectKind, pack: StoryPack): EventEffect? = when (kind) {
    EffectKind.MOVE -> pack.characters.firstOrNull()?.let { character ->
        pack.locations.firstOrNull()?.let { location ->
            EventEffect.MoveCharacter(
                characterId = character.id,
                toLocationId = location.id,
                activity = CharacterActivity.IDLE,
            )
        }
    }
    EffectKind.ACTIVITY -> pack.characters.firstOrNull()?.let { character ->
        EventEffect.ChangeActivity(characterId = character.id, activity = CharacterActivity.TALKING)
    }
    EffectKind.RELATIONSHIP -> if (pack.characters.size > 1) {
        EventEffect.ChangeRelationship(
            sourceId = pack.characters[0].id,
            targetId = pack.characters[1].id,
            delta = RelationshipDelta(trust = 5),
            relationshipType = null,
            reason = "",
        )
    } else {
        null
    }
    EffectKind.VARIABLE -> EventEffect.SetVariable(key = "mood", value = "tense")
    EffectKind.ADVANCE_THREAD -> pack.initialStoryThreads.firstOrNull()?.let { thread ->
        EventEffect.AdvanceThread(threadId = thread.id, stage = thread.stage + 1)
    }
    EffectKind.GRANT_KNOWLEDGE -> pack.characters.firstOrNull()?.let { character ->
        pack.initialKnowledge.facts.firstOrNull()?.let { fact ->
            EventEffect.GrantKnowledge(characterId = character.id, factId = fact.id)
        }
    }
    EffectKind.CREATE_MEMORY -> pack.characters.firstOrNull()?.let { character ->
        EventEffect.CreateMemory(
            characterId = character.id,
            content = "Something happened that changed things.",
            importance = 3,
            relatedLocationId = pack.locations.firstOrNull()?.id,
        )
    }
    EffectKind.START_SCENE -> pack.locations.firstOrNull()?.let { location ->
        EventEffect.StartScene(
            locationId = location.id,
            participants = listOfNotNull(pack.characters.firstOrNull()?.id),
            objective = "",
        )
    }
    EffectKind.OTHER -> null
}

/**
 * The event editor.
 *
 * It writes straight into [EventTrigger], [EventCondition] and [EventEffect], so what
 * an author sees here is exactly what the engine will receive. There is deliberately
 * no free-text "what this does" box that could hide a state change: effects are a
 * closed set of typed cases, and the preview underneath shows the compiled result.
 */
@Composable
fun EventEditorScreen(
    pack: StoryPack?,
    eventId: String,
    onBack: () -> Unit,
    onSave: (PackEventDefinition) -> Unit,
) {
    val packOrNull = pack
    val original = packOrNull?.events?.firstOrNull { it.id == eventId }
    if (packOrNull == null || original == null) {
        MissingThing(
            state = EmptyState(
                title = "That event is not here.",
                body = "It may have been removed from this pack.",
                artSeed = "charaly-missing-event",
            ),
            onBack = onBack,
        )
        return
    }

    var draft by remember { mutableStateOf(original) }
    var titleText by remember { mutableStateOf(original.title) }
    var triggerKind by remember {
        mutableStateOf(
            when (original.trigger) {
                is EventTrigger.StoryStart -> TriggerKind.STORY_START
                is EventTrigger.AfterDelay -> TriggerKind.DELAY
                is EventTrigger.AtStoryTime -> TriggerKind.AT_TIME
                is EventTrigger.WhenConditionMet -> TriggerKind.CONDITION
            },
        )
    }
    var delayMinutes by remember {
        mutableIntStateOf((original.trigger as? EventTrigger.AfterDelay)?.delayMinutes?.toInt() ?: 60)
    }
    var pollMinutes by remember {
        mutableIntStateOf((original.trigger as? EventTrigger.WhenConditionMet)?.pollEveryMinutes?.toInt() ?: 30)
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = draft.title,
            subtitle = packOrNull.title,
            accent = Charaly.accent.accent,
            onBack = onBack,
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = Charaly.tokens.spacing.gutter,
                end = Charaly.tokens.spacing.gutter,
                top = Charaly.tokens.spacing.sm,
                bottom = Charaly.tokens.spacing.md,
            ),
        ) {
            item {
                LabeledField(
                    label = "Name",
                    value = titleText,
                    onValueChange = { raw ->
                        titleText = raw
                        if (raw.isNotBlank()) draft = draft.copy(title = raw)
                    },
                )
                LabeledField(
                    label = "Description",
                    value = draft.description,
                    onValueChange = { raw -> draft = draft.copy(description = raw) },
                    singleLine = false,
                    minLines = 2,
                    placeholder = "What the author intends to happen",
                )
                LabeledField(
                    label = "Narrative seed",
                    value = draft.narrativeSeed,
                    onValueChange = { raw -> draft = draft.copy(narrativeSeed = raw) },
                    singleLine = false,
                    minLines = 2,
                    placeholder = "The dramatic shape of this moment, for the model to read",
                )

                CollapsibleSection(
                    "Trigger",
                    "When this event tries to happen",
                    initiallyExpanded = true,
                ) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(TriggerKind.entries.size, key = { TriggerKind.entries[it].name }) { index ->
                            val kind = TriggerKind.entries[index]
                            CharalyFilterChip(
                                label = kind.label,
                                selected = kind == triggerKind,
                                onClick = {
                                    triggerKind = kind
                                    draft = draft.copy(
                                        trigger = when (kind) {
                                            TriggerKind.STORY_START -> EventTrigger.StoryStart()
                                            TriggerKind.DELAY ->
                                                EventTrigger.AfterDelay(delayMinutes.toLong().coerceAtLeast(0L))
                                            TriggerKind.AT_TIME ->
                                                EventTrigger.AtStoryTime(
                                                    (draft.trigger as? EventTrigger.AtStoryTime)?.time
                                                        ?: StoryTime(day = 1, hour = 18, minute = 0),
                                                )
                                            TriggerKind.CONDITION ->
                                                EventTrigger.WhenConditionMet(
                                                    pollMinutes.toLong().coerceAtLeast(0L),
                                                )
                                        },
                                    )
                                },
                            )
                        }
                    }
                    when (triggerKind) {
                        TriggerKind.STORY_START -> Caption("Fires in every opening, unless narrowed to specific ones.")
                        TriggerKind.DELAY -> NumberField(
                            label = "Minutes after the story starts",
                            value = delayMinutes.toLong(),
                            onChange = { minutes ->
                                delayMinutes = minutes.coerceIn(0L, 100_000L).toInt()
                                draft = draft.copy(
                                    trigger = EventTrigger.AfterDelay(
                                        delayMinutes.toLong().coerceAtLeast(0L),
                                    ),
                                )
                            },
                        )
                        TriggerKind.AT_TIME -> {
                            val time = (draft.trigger as? EventTrigger.AtStoryTime)?.time
                                ?: StoryTime(day = 1, hour = 18, minute = 0)
                            Eyebrow("Fires at")
                            TimeFields(
                                time = time,
                                onChange = { next -> draft = draft.copy(trigger = EventTrigger.AtStoryTime(next)) },
                            )
                        }
                        TriggerKind.CONDITION -> {
                            Caption("Checked after every world change and whenever the clock moves.")
                            NumberField(
                                label = "Checked every N minutes",
                                value = pollMinutes.toLong(),
                                onChange = { minutes ->
                                    pollMinutes = minutes.coerceIn(0L, 100_000L).toInt()
                                    draft = draft.copy(
                                        trigger = EventTrigger.WhenConditionMet(
                                            pollMinutes.toLong().coerceAtLeast(0L),
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }

                CollapsibleSection("Conditions", "All of these must hold, or nothing happens") {
                    if (draft.conditions.isEmpty()) {
                        Caption("No conditions: this event fires on its trigger alone.")
                    } else {
                        draft.conditions.forEachIndexed { index, condition ->
                            ConditionRow(
                                condition = condition,
                                pack = packOrNull,
                                onChange = { next ->
                                    val updated = draft.conditions.toMutableList()
                                    updated[index] = next
                                    draft = draft.copy(conditions = updated)
                                },
                                onRemove = {
                                    val updated = draft.conditions.toMutableList()
                                    updated.removeAt(index)
                                    draft = draft.copy(conditions = updated)
                                },
                            )
                        }
                    }
                    val kinds = conditionKinds(packOrNull)
                    if (kinds.isEmpty()) {
                        Caption("There is nothing in this pack to build a condition out of yet.")
                    } else {
                        CharalyTextButton(
                            label = "Add a condition",
                            onClick = {
                                newCondition(kinds.first(), packOrNull)?.let { fresh ->
                                    draft = draft.copy(conditions = draft.conditions + fresh)
                                }
                            },
                            icon = Icons.Filled.Add,
                        )
                        Caption("Added as \"${kinds.first().label}\". Pick its kind afterwards.")
                    }
                }

                CollapsibleSection("Effects", "What actually changes in the world", initiallyExpanded = true) {
                    if (draft.effects.isEmpty()) {
                        Caption("No effects: this event would fire and change nothing.")
                    } else {
                        draft.effects.forEachIndexed { index, effect ->
                            EffectRow(
                                effect = effect,
                                pack = packOrNull,
                                onChange = { next ->
                                    val updated = draft.effects.toMutableList()
                                    updated[index] = next
                                    draft = draft.copy(effects = updated)
                                },
                                onRemove = {
                                    val updated = draft.effects.toMutableList()
                                    updated.removeAt(index)
                                    draft = draft.copy(effects = updated)
                                },
                            )
                        }
                    }
                    val kinds = effectKinds(packOrNull)
                    if (kinds.isEmpty()) {
                        Caption("Add a character or a place to this pack first.")
                    } else {
                        CharalyTextButton(
                            label = "Add an effect",
                            onClick = {
                                newEffect(kinds.first(), packOrNull)?.let { fresh ->
                                    draft = draft.copy(effects = draft.effects + fresh)
                                }
                            },
                            icon = Icons.Filled.Add,
                        )
                        Caption("Added as \"${kinds.first().label}\". Pick its kind afterwards.")
                    }
                }

                CollapsibleSection("Repeat behaviour") {
                    SwitchRow(
                        title = "Can fire more than once",
                        subtitle = "Off means this event happens at most once per story",
                        checked = draft.repeatable,
                        onCheckedChange = { raw -> draft = draft.copy(repeatable = raw) },
                    )
                    NumberField(
                        label = "Cooldown minutes",
                        value = draft.cooldownMinutes,
                        onChange = { minutes -> draft = draft.copy(cooldownMinutes = minutes.coerceAtLeast(0L)) },
                    )
                    Caption("Story minutes, not wall-clock minutes.")
                }

                CollapsibleSection(
                    "What this changes",
                    "Compiled from the structured effects above",
                    initiallyExpanded = true,
                ) {
                    val preview = effectPreview(draft)
                    if (preview.isEmpty()) {
                        Caption("Nothing yet. Add an effect above.")
                    } else {
                        preview.forEach { line ->
                            Row(
                                modifier = Modifier.padding(vertical = 2.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Box(
                                    Modifier
                                        .padding(top = 6.dp)
                                        .size(4.dp)
                                        .clip(CircleShape)
                                        .background(Charaly.accent.accent),
                                )
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        EditorFooter(
            backLabel = "Cancel",
            onBack = onBack,
            saveLabel = "Save event",
            onSave = { onSave(draft) },
            saveEnabled = draft.title.isNotBlank(),
        )
    }
}

/** One condition row: what it is, what kind it is, and its own fields. */
@Composable
private fun ConditionRow(
    condition: EventCondition,
    pack: StoryPack,
    onChange: (EventCondition) -> Unit,
    onRemove: () -> Unit,
) {
    val kind = condition.kind()
    CharalyCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Charaly.tokens.spacing.sm),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = kind.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = condition.describe(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                CharalyIconButton(
                    icon = Icons.Filled.Remove,
                    contentDescription = "Remove condition",
                    onClick = onRemove,
                )
            }
            if (kind != ConditionKind.OTHER) {
                LazyRow(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(conditionKinds(pack), key = { it.name }) { option ->
                        CharalyFilterChip(
                            label = option.label,
                            selected = option == kind,
                            onClick = {
                                newCondition(option, pack)?.takeIf { it != condition }?.let(onChange)
                            },
                        )
                    }
                }
                when (condition) {
                    is EventCondition.VariableEquals -> {
                        LabeledField(
                            label = "Variable",
                            value = condition.key,
                            onValueChange = { raw -> onChange(condition.copy(key = raw)) },
                            placeholder = "e.g. lanterns_out",
                        )
                        LabeledField(
                            label = "Equals",
                            value = condition.value,
                            onValueChange = { raw -> onChange(condition.copy(value = raw)) },
                            placeholder = "e.g. true",
                        )
                    }
                    is EventCondition.CharacterAtLocation -> {
                        Eyebrow("Character")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(pack.characters, key = { "condition-char-${it.id.value}" }) { character ->
                                CharalyFilterChip(
                                    label = character.name,
                                    selected = character.id == condition.characterId,
                                    onClick = { onChange(condition.copy(characterId = character.id)) },
                                )
                            }
                        }
                        Eyebrow("Location", modifier = Modifier.padding(top = 8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(pack.locations, key = { "condition-place-${it.id.value}" }) { location ->
                                CharalyFilterChip(
                                    label = location.name,
                                    selected = location.id == condition.locationId,
                                    onClick = { onChange(condition.copy(locationId = location.id)) },
                                )
                            }
                        }
                    }
                    is EventCondition.ThreadStageAtLeast -> {
                        Eyebrow("Thread")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(pack.initialStoryThreads, key = { "condition-thread-${it.id.value}" }) { thread ->
                                CharalyFilterChip(
                                    label = thread.title,
                                    selected = thread.id == condition.threadId,
                                    onClick = { onChange(condition.copy(threadId = thread.id)) },
                                )
                            }
                        }
                        NumberField(
                            label = "At least stage",
                            value = condition.stage.toLong(),
                            onChange = { stage -> onChange(condition.copy(stage = stage.coerceIn(0L, 99L).toInt())) },
                            max = 99L,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    is EventCondition.StoryTimeReached -> {
                        Eyebrow("The clock reaches")
                        TimeFields(
                            time = condition.time,
                            onChange = { next -> onChange(condition.copy(time = next)) },
                        )
                    }
                    is EventCondition.CharacterKnowsFact -> {
                        Eyebrow("Character")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(pack.characters, key = { "knows-char-${it.id.value}" }) { character ->
                                CharalyFilterChip(
                                    label = character.name,
                                    selected = character.id == condition.characterId,
                                    onClick = { onChange(condition.copy(characterId = character.id)) },
                                )
                            }
                        }
                        Eyebrow("Fact", modifier = Modifier.padding(top = 8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(pack.initialKnowledge.facts, key = { "knows-fact-${it.id.value}" }) { fact ->
                                CharalyFilterChip(
                                    label = fact.subject,
                                    selected = fact.id == condition.factId,
                                    onClick = { onChange(condition.copy(factId = fact.id)) },
                                )
                            }
                        }
                    }
                    else -> Caption("This condition was authored somewhere else, so it is shown unchanged.")
                }
            }
        }
    }
}

/** One effect row: what it is, what kind it is, and its own fields. */
@Composable
private fun EffectRow(
    effect: EventEffect,
    pack: StoryPack,
    onChange: (EventEffect) -> Unit,
    onRemove: () -> Unit,
) {
    val kind = effect.kind()
    CharalyCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Charaly.tokens.spacing.sm),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = kind.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = effect.describe(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                CharalyIconButton(
                    icon = Icons.Filled.Remove,
                    contentDescription = "Remove effect",
                    onClick = onRemove,
                )
            }
            if (kind != EffectKind.OTHER) {
                LazyRow(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(effectKinds(pack), key = { it.name }) { option ->
                        CharalyFilterChip(
                            label = option.label,
                            selected = option == kind,
                            onClick = {
                                newEffect(option, pack)?.takeIf { it != effect }?.let(onChange)
                            },
                        )
                    }
                }
                when (effect) {
                    is EventEffect.MoveCharacter -> {
                        Eyebrow("Who moves")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(pack.characters, key = { "move-char-${it.id.value}" }) { character ->
                                CharalyFilterChip(
                                    label = character.name,
                                    selected = character.id == effect.characterId,
                                    onClick = { onChange(effect.copy(characterId = character.id)) },
                                )
                            }
                        }
                        Eyebrow("To", modifier = Modifier.padding(top = 8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(pack.locations, key = { "move-place-${it.id.value}" }) { location ->
                                CharalyFilterChip(
                                    label = location.name,
                                    selected = location.id == effect.toLocationId,
                                    onClick = { onChange(effect.copy(toLocationId = location.id)) },
                                )
                            }
                        }
                        Eyebrow("Doing", modifier = Modifier.padding(top = 8.dp))
                        ActivityChips(
                            selected = effect.activity,
                            onSelect = { activity -> onChange(effect.copy(activity = activity)) },
                        )
                    }
                    is EventEffect.ChangeActivity -> {
                        Eyebrow("Who")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(pack.characters, key = { "act-char-${it.id.value}" }) { character ->
                                CharalyFilterChip(
                                    label = character.name,
                                    selected = character.id == effect.characterId,
                                    onClick = { onChange(effect.copy(characterId = character.id)) },
                                )
                            }
                        }
                        Eyebrow("Doing", modifier = Modifier.padding(top = 8.dp))
                        ActivityChips(
                            selected = effect.activity,
                            onSelect = { activity -> onChange(effect.copy(activity = activity)) },
                        )
                        LabeledField(
                            label = "Mood",
                            value = effect.mood,
                            onValueChange = { raw -> onChange(effect.copy(mood = raw)) },
                            placeholder = "Optional: uneasy, relieved",
                        )
                    }
                    is EventEffect.ChangeRelationship -> {
                        // A relationship is directed and can never point at itself,
                        // so the other end is simply not offered as a choice.
                        Eyebrow("From")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(
                                pack.characters.filter { it.id != effect.targetId },
                                key = { "rel-from-${it.id.value}" },
                            ) { character ->
                                CharalyFilterChip(
                                    label = character.name,
                                    selected = character.id == effect.sourceId,
                                    onClick = { onChange(effect.copy(sourceId = character.id)) },
                                )
                            }
                        }
                        Eyebrow("To", modifier = Modifier.padding(top = 8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(
                                pack.characters.filter { it.id != effect.sourceId },
                                key = { "rel-to-${it.id.value}" },
                            ) { character ->
                                CharalyFilterChip(
                                    label = character.name,
                                    selected = character.id == effect.targetId,
                                    onClick = { onChange(effect.copy(targetId = character.id)) },
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.xs)) {
                            NumberField(
                                label = "Trust",
                                value = effect.delta.trust.toLong(),
                                onChange = { value ->
                                    onChange(
                                        effect.copy(
                                            delta = effect.delta.copy(
                                                trust = value.coerceIn(-100L, 100L).toInt(),
                                            ),
                                        ),
                                    )
                                },
                                min = -100L,
                                max = 100L,
                                modifier = Modifier.weight(1f),
                            )
                            NumberField(
                                label = "Affinity",
                                value = effect.delta.affinity.toLong(),
                                onChange = { value ->
                                    onChange(
                                        effect.copy(
                                            delta = effect.delta.copy(
                                                affinity = value.coerceIn(-100L, 100L).toInt(),
                                            ),
                                        ),
                                    )
                                },
                                min = -100L,
                                max = 100L,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        NumberField(
                            label = "Familiarity",
                            value = effect.delta.familiarity.toLong(),
                            onChange = { value ->
                                onChange(
                                    effect.copy(
                                        delta = effect.delta.copy(
                                            familiarity = value.coerceIn(-100L, 100L).toInt(),
                                        ),
                                    ),
                                )
                            },
                            min = -100L,
                            max = 100L,
                        )
                        Eyebrow("Now called", modifier = Modifier.padding(top = 8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(
                                listOf<RelationshipType?>(null) + RelationshipType.entries,
                                key = { "rel-type-${it?.name ?: "unchanged"}" },
                            ) { type ->
                                CharalyFilterChip(
                                    label = type?.label ?: "Leave unchanged",
                                    selected = effect.relationshipType == type,
                                    onClick = { onChange(effect.copy(relationshipType = type)) },
                                )
                            }
                        }
                        LabeledField(
                            label = "Reason",
                            value = effect.reason,
                            onValueChange = { raw -> onChange(effect.copy(reason = raw)) },
                            placeholder = "Why the world did this",
                        )
                    }
                    is EventEffect.SetVariable -> {
                        LabeledField(
                            label = "Variable",
                            value = effect.key,
                            onValueChange = { raw -> onChange(effect.copy(key = raw)) },
                        )
                        LabeledField(
                            label = "Value",
                            value = effect.value,
                            onValueChange = { raw -> onChange(effect.copy(value = raw)) },
                        )
                        Caption("The variable itself is declared in the pack's world state.")
                    }
                    is EventEffect.AdvanceThread -> {
                        Eyebrow("Thread")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(pack.initialStoryThreads, key = { "adv-thread-${it.id.value}" }) { thread ->
                                CharalyFilterChip(
                                    label = thread.title,
                                    selected = thread.id == effect.threadId,
                                    onClick = { onChange(effect.copy(threadId = thread.id)) },
                                )
                            }
                        }
                        NumberField(
                            label = "New stage",
                            value = effect.stage.toLong(),
                            onChange = { stage -> onChange(effect.copy(stage = stage.coerceIn(0L, 99L).toInt())) },
                            max = 99L,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Eyebrow("Status", modifier = Modifier.padding(top = 8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(
                                listOf<StoryThreadStatus?>(null) + StoryThreadStatus.entries,
                                key = { "adv-status-${it?.name ?: "unchanged"}" },
                            ) { status ->
                                CharalyFilterChip(
                                    label = status?.name?.lowercase()?.replaceFirstChar { c -> c.uppercase() }
                                        ?: "Leave unchanged",
                                    selected = effect.status == status,
                                    onClick = { onChange(effect.copy(status = status)) },
                                )
                            }
                        }
                        LabeledField(
                            label = "Note",
                            value = effect.note,
                            onValueChange = { raw -> onChange(effect.copy(note = raw)) },
                        )
                    }
                    is EventEffect.GrantKnowledge -> {
                        Eyebrow("Who learns")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(pack.characters, key = { "grant-char-${it.id.value}" }) { character ->
                                CharalyFilterChip(
                                    label = character.name,
                                    selected = character.id == effect.characterId,
                                    onClick = { onChange(effect.copy(characterId = character.id)) },
                                )
                            }
                        }
                        Eyebrow("Fact", modifier = Modifier.padding(top = 8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(pack.initialKnowledge.facts, key = { "grant-fact-${it.id.value}" }) { fact ->
                                CharalyFilterChip(
                                    label = fact.subject,
                                    selected = fact.id == effect.factId,
                                    onClick = { onChange(effect.copy(factId = fact.id)) },
                                )
                            }
                        }
                        LabeledField(
                            label = "Learned how",
                            value = effect.via,
                            onValueChange = { raw -> onChange(effect.copy(via = raw)) },
                            placeholder = "Optional: overheard, read in a letter",
                        )
                    }
                    is EventEffect.CreateMemory -> {
                        Eyebrow("Who remembers")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(pack.characters, key = { "mem-char-${it.id.value}" }) { character ->
                                CharalyFilterChip(
                                    label = character.name,
                                    selected = character.id == effect.characterId,
                                    onClick = { onChange(effect.copy(characterId = character.id)) },
                                )
                            }
                        }
                        LabeledField(
                            label = "What they remember",
                            value = effect.content,
                            onValueChange = { raw ->
                                // A memory needs content: blank text is simply not applied.
                                if (raw.isNotBlank()) onChange(effect.copy(content = raw))
                            },
                            singleLine = false,
                            minLines = 2,
                            placeholder = "The letter was still in her coat",
                        )
                        StepperRow(
                            title = "Importance",
                            value = effect.importance,
                            range = 1..5,
                            onChange = { importance -> onChange(effect.copy(importance = importance)) },
                            valueLabel = "${effect.importance}/5",
                        )
                        Eyebrow("Where", modifier = Modifier.padding(top = 8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(pack.locations, key = { "mem-place-${it.id.value}" }) { location ->
                                CharalyFilterChip(
                                    label = location.name,
                                    selected = effect.relatedLocationId == location.id,
                                    onClick = {
                                        onChange(
                                            effect.copy(
                                                relatedLocationId = if (effect.relatedLocationId == location.id) {
                                                    null
                                                } else {
                                                    location.id
                                                },
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    }
                    is EventEffect.StartScene -> {
                        Eyebrow("Where")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(pack.locations, key = { "scene-place-${it.id.value}" }) { location ->
                                CharalyFilterChip(
                                    label = location.name,
                                    selected = location.id == effect.locationId,
                                    onClick = { onChange(effect.copy(locationId = location.id)) },
                                )
                            }
                        }
                        Eyebrow("Who is there", modifier = Modifier.padding(top = 8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(pack.characters, key = { "scene-char-${it.id.value}" }) { character ->
                                CharalyFilterChip(
                                    label = character.name,
                                    selected = character.id in effect.participants,
                                    onClick = {
                                        onChange(
                                            effect.copy(
                                                participants = if (character.id in effect.participants) {
                                                    effect.participants - character.id
                                                } else {
                                                    effect.participants + character.id
                                                },
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                        LabeledField(
                            label = "Objective",
                            value = effect.objective,
                            onValueChange = { raw -> onChange(effect.copy(objective = raw)) },
                            placeholder = "Optional: what this scene is for",
                        )
                    }
                    else -> Caption("This effect was authored somewhere else, so it is shown unchanged.")
                }
            }
        }
    }
}

/** A scrolling row of every activity in the domain's closed set. */
@Composable
private fun ActivityChips(selected: CharacterActivity, onSelect: (CharacterActivity) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(CharacterActivity.entries.size, key = { "activity-${CharacterActivity.entries[it].name}" }) { index ->
            val activity = CharacterActivity.entries[index]
            CharalyFilterChip(
                label = activity.label(),
                selected = activity == selected,
                onClick = { onSelect(activity) },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Read-only detail screens
// ---------------------------------------------------------------------------

/** A character as a story reader meets them: read-only, and never a form. */
@Composable
fun CharacterDetailScreen(
    snapshot: CharacterCard?,
    packTitle: String,
    onBack: () -> Unit,
) {
    val card = snapshot
    if (card == null) {
        MissingThing(
            state = EmptyState(
                title = "That character is not here.",
                body = "It may have been removed from this pack since you opened it.",
                artSeed = "charaly-missing-character",
            ),
            onBack = onBack,
        )
        return
    }

    val accent = card.accent.takeIf { it != 0L }?.toColor() ?: Charaly.accent.primary

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = card.name,
            subtitle = packTitle.ifBlank { "Character" },
            accent = accent,
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = Charaly.tokens.spacing.gutter,
                end = Charaly.tokens.spacing.gutter,
                bottom = Charaly.tokens.spacing.section,
            ),
        ) {
            item {
                CharalyCard(
                    modifier = Modifier.fillMaxWidth(),
                    border = Charaly.colors.hairline,
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CharacterAvatar(
                                seed = card.name,
                                accent = accent,
                                name = card.name,
                                modifier = Modifier.size(64.dp),
                            )
                            Column(Modifier.padding(start = Charaly.tokens.spacing.md)) {
                                Text(
                                    text = card.name,
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Eyebrow(packTitle.ifBlank { "Story pack" })
                            }
                        }
                        if (card.tagline.isNotBlank()) {
                            Text(
                                text = card.tagline,
                                style = MaterialTheme.typography.titleMedium,
                                color = Charaly.accent.accent,
                                modifier = Modifier.padding(top = Charaly.tokens.spacing.md),
                            )
                        }
                        if (card.role.isNotBlank()) {
                            DetailRow(label = "Role", value = card.role)
                        }
                        if (card.locationName.isNotBlank()) {
                            DetailRow(label = "Starts in", value = card.locationName)
                        }
                        if (card.description.isNotBlank()) {
                            HairLine(modifier = Modifier.padding(vertical = Charaly.tokens.spacing.sm))
                            BodyProse(text = card.description)
                        }
                    }
                }
            }
        }
    }
}

/** A place as a story reader meets it: artwork first, then what the world says. */
@Composable
fun LocationDetailScreen(
    snapshot: LocationCard?,
    onBack: () -> Unit,
) {
    val card = snapshot
    if (card == null) {
        MissingThing(
            state = EmptyState(
                title = "That place is not here.",
                body = "It may have been removed from this pack since you opened it.",
                artSeed = "charaly-missing-location",
            ),
            onBack = onBack,
        )
        return
    }

    val artTheme = remember(card.accent) { heroTheme(card.accent) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = Charaly.tokens.spacing.section),
    ) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp),
            ) {
                ArtworkHero(
                    artwork = PackArtwork.generated(seed = card.id),
                    theme = artTheme,
                    modifier = Modifier.fillMaxSize(),
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
                        Row {
                            CharalyIconButton(
                                icon = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                onClick = onBack,
                                container = Color.Black.copy(alpha = 0.32f),
                                tint = Color.White,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = card.name,
                            style = MaterialTheme.typography.displaySmall,
                            color = Color.White,
                        )
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = Charaly.tokens.spacing.gutter)) {
                if (card.summary.isNotBlank()) {
                    Text(
                        text = card.summary,
                        style = MaterialTheme.typography.titleMedium,
                        color = Charaly.accent.accent,
                        modifier = Modifier.padding(top = Charaly.tokens.spacing.md),
                    )
                }
                if (card.description.isNotBlank()) {
                    BodyProse(
                        text = card.description,
                        modifier = Modifier.padding(top = Charaly.tokens.spacing.xs),
                    )
                }
                if (card.occupantNames.isNotEmpty()) {
                    DetailRow(
                        label = "Usually here",
                        value = card.occupantNames.joinToString(", "),
                        modifier = Modifier.padding(top = Charaly.tokens.spacing.sm),
                    )
                }
                if (card.rules.isNotEmpty()) {
                    SectionHeader(
                        title = "Rules of this place",
                        modifier = Modifier.padding(top = Charaly.tokens.spacing.md),
                    )
                    card.rules.forEach { rule ->
                        Row(
                            modifier = Modifier.padding(top = 8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Box(
                                Modifier
                                    .padding(top = 7.dp)
                                    .size(4.dp)
                                    .clip(CircleShape)
                                    .background(Charaly.accent.accent),
                            )
                            Text(
                                text = rule,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(start = 10.dp),
                            )
                        }
                    }
                }
                if (card.lore.isNotBlank()) {
                    SectionHeader(
                        title = "Lore",
                        modifier = Modifier.padding(top = Charaly.tokens.spacing.lg),
                    )
                    BodyProse(
                        text = card.lore,
                        modifier = Modifier.padding(top = Charaly.tokens.spacing.xs),
                    )
                }
            }
        }
    }
}

/** A one-accent theme for a location hero, with the brand violet as the fallback. */
private fun heroTheme(accent: Long): ResolvedTheme {
    val long = accent.takeIf { it != 0L } ?: PackColor.parse("#8B7BF0")
    return ResolvedTheme(
        primary = long,
        secondary = long,
        accent = long,
        ink = 0xFFF5F2FA,
        surface = 0xFF16151C,
        mood = "",
    )
}