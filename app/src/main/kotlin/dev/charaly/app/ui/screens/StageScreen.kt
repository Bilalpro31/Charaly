package dev.charaly.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.PriorityHigh
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import dev.charaly.app.ui.components.tappable
import androidx.compose.runtime.Composable
import dev.charaly.runtime.presentation.Loc
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.charaly.app.R
import dev.charaly.app.ui.art.CharacterMark
import dev.charaly.app.ui.art.PackArtworkHero
import dev.charaly.app.ui.art.SceneBackdrop
import dev.charaly.app.ui.components.CharalyEmptyState
import dev.charaly.app.ui.components.CharalyIconButton
import dev.charaly.app.ui.components.CharalyPill
import dev.charaly.app.ui.components.CharalyPresence
import dev.charaly.app.ui.components.CharalyQuietAction
import dev.charaly.app.ui.components.CharalySpinner
import dev.charaly.app.ui.components.PresenceStyle
import dev.charaly.app.ui.design.Charaly
import dev.charaly.app.ui.design.CharalyAtmosphere
import dev.charaly.app.ui.design.CharalyShapes
import dev.charaly.app.ui.design.toCompose
import dev.charaly.app.ui.theme.motionDuration
import dev.charaly.runtime.presentation.Beat
import dev.charaly.runtime.presentation.BeatRole
import dev.charaly.runtime.presentation.ChatStage
import dev.charaly.runtime.presentation.ComposerMode
import dev.charaly.runtime.presentation.LayoutPolicy
import dev.charaly.runtime.presentation.Presence
import dev.charaly.runtime.presentation.StageAction
import dev.charaly.runtime.presentation.StageFailure
import dev.charaly.runtime.presentation.StoryMoment
import dev.charaly.runtime.presentation.Whereabouts

/**
 * THE STAGE.
 *
 * ## A living scene, not a messenger
 *
 * Four things make this read as a scene rather than a chat log:
 *
 * 1. **The header almost disappears.** Back, who you are with, where and when, and one
 *    context control. No title bar, no story name, no settings.
 * 2. **Character dialogue is prose.** A name, a spoken line, and any stage direction - set
 *    as one paragraph. No bubbles on the character's side, because bubbles are a
 *    conversation metaphor and this is a scene.
 * 3. **World moments are cinematic.** Sparse, centred, quiet. They are how the reader
 *    learns the world did something on its own.
 * 4. **The composer is welded to the bottom edge**, through the keyboard, on every phone.
 *
 * ## The keyboard, in detail
 *
 * `imePadding()` goes on the *column*, not the composer. The activity declares
 * `adjustResize`, so Android shrinks the window and Compose reports the keyboard as an
 * inset; padding the column is what makes the transcript lose height while the composer
 * stays pinned to the bottom of what is left.
 *
 * Padding only the composer would leave a gap under the keyboard that swallows the input
 * field - the classic way a chat app ends up with an unreachable text box. That is a real
 * bug this arrangement avoids structurally.
 */
@Composable
fun StageScreen(
    stage: ChatStage?,
    loading: Boolean,
    policy: LayoutPolicy,
    sheet: StageSheet?,
    context: dev.charaly.runtime.presentation.StoryContext?,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onOpenSheet: (StageSheet) -> Unit,
    onDismissSheet: () -> Unit,
    onOpenModels: () -> Unit,
    onRetry: () -> Unit,
    onSelectSpeaker: (String) -> Unit,
    /**
     * The artwork cache.
     *
     * Nullable so previews compile and render without one, which is why the backdrop
     * treats a null loader as "no image" rather than as an error.
     */
    assetLoader: dev.charaly.app.ui.art.StoryAssetLoader? = null,
) {
    if (stage == null) {
        StageMissing(loading = loading, onBack = onBack)
        return
    }

    val atmosphere = CharalyAtmosphere.of(stage.theme)
    val listState = rememberLazyListState()

    // Follow the conversation, including streamed chunks. Keyed on the line count so a
    // re-render with the same lines does not yank the reader back down.
    LaunchedEffect(stage.beats.size) {
        val target = stage.beats.lastIndex
        if (target >= 0) listState.animateScrollToItem(target)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Charaly.surface.void),
    ) {
        // THE BACKDROP.
        //
        // This used to be a flat gradient derived from the pack's accent - the same wash in
        // every scene, in every location, at every hour. A rooftop, a classroom and the
        // tower all looked like one abstract field with dialogue over it.
        //
        // What replaces it reads only [dev.charaly.runtime.presentation.VisualSceneProjection]:
        // where the player actually is, what the story clock says the time is, and the
        // weather variable the pack declared. The UI chooses nothing - it draws a location
        // motif, so a school reads as a school and a rooftop reads as Paris, and both change
        // when the world clock moves them into night.
        SceneBackdrop(
            visual = stage.visuals.background,
            atmosphere = atmosphere,
            projection = stage.visuals,
            locationTags = stage.visuals.locationTags,
            modifier = Modifier.fillMaxSize(),
            // Light: the transcript needs the ground, not the whole picture darkened.
            strength = 0.82f,
            // The pack's own artwork, when it shipped any. The backdrop underneath stays
            // drawn either way, so this is an addition and never a dependency.
            packId = stage.packId,
            loader = assetLoader,
        )

        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .imePadding(),
            ) {
                StageHeader(
                    stage = stage,
                    onBack = onBack,
                    onOpenSheet = onOpenSheet,
                )

                // V5's scene note: the pack's name and the one-line honesty note, small
                // and low-contrast under the header. It says whose fiction this is before
                // the first line of it is read.
                if (stage.title.isNotBlank()) {
                    Text(
                        text = listOfNotNull(
                            stage.title.takeIf { it.isNotBlank() },
                            stringResource(R.string.chat_fiction_note),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = Charaly.ink.secondary.copy(alpha = 0.62f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = Charaly.space.lg),
                    )
                }

                if (sheet != null) {
                    // The sheet is a layer *over* the scene, drawn inline rather than in a
                    // dialog: a Material bottom sheet dims and detaches the content behind
                    // it, which is exactly wrong for something the reader is supposed to be
                    // able to glance past.
                    dev.charaly.app.ui.sheets.StageSheetContent(
                        sheet = sheet,
                        stage = stage,
                        context = context,
                        onSelectSheet = onOpenSheet,
                        onDismiss = onDismissSheet,
                        onSelectSpeaker = onSelectSpeaker,
                    )
                }

                Box(Modifier.weight(1f)) {
                    val opening = stage.opening
                    if (!stage.hasTranscript && opening != null) {
                        CharalyEmptyState(state = opening)
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = Charaly.space.gutter,
                                end = Charaly.space.gutter,
                                top = Charaly.space.md,
                                bottom = Charaly.space.lg,
                            ),
                            verticalArrangement = Arrangement.spacedBy(Charaly.space.md),
                        ) {
                            // Moments first: the world is already changing by the time the
                            // reader arrives, and showing them above the first line makes
                            // that legible.
                            items(
                                count = stage.moments.size,
                                key = { index -> "moment-${stage.moments[index].id}" },
                            ) { index ->
                                MomentLine(stage.moments[index])
                            }

                            items(
                                count = stage.beats.size,
                                key = { index -> stage.beats[index].id },
                            ) { index ->
                                BeatView(
                                    beat = stage.beats[index],
                                    isLast = index == stage.beats.lastIndex,
                                    packId = stage.packId,
                                    assetLoader = assetLoader,
                                )
                            }

                            if (stage.isGenerating) {
                                item(key = "thinking") {
                                    ThinkingLine(label = stage.phaseLabel)
                                }
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = stage.hasFailure,
                    enter = fadeIn(tween(motionDuration(Charaly.timing.quick))) +
                        slideInVertically { it / 2 },
                    exit = fadeOut(tween(motionDuration(Charaly.timing.quick))) +
                        slideOutVertically { it / 2 },
                ) {
                    stage.failure?.let { failure ->
                        FailureSurface(
                            failure = failure,
                            onAction = { action ->
                                when (action) {
                                    StageAction.RETRY -> onRetry()
                                    StageAction.OPEN_MODELS -> onOpenModels()
                                    StageAction.DISMISS -> onRetry()
                                }
                            },
                        )
                    }
                }

                StageComposer(
                    composer = stage.composer,
                    generating = stage.isGenerating,
                    onSend = onSend,
                    onStop = onStop,
                )
            }

            // A persistent context column, only where prose stays readable. A squeezed
            // dialogue column is worse than one full-width one, so this waits for real
            // room rather than appearing at the tablet breakpoint.
            if (policy.storySplitPane) {
                StageContextPane(
                    stage = stage,
                    modifier = Modifier
                        .width(policy.contextWidthDp(policy.widthDp).dp)
                        .fillMaxHeight()
                        .background(Charaly.surface.base),
                )
            }
        }
    }
}

/**
 * The header.
 *
 * ## V5's shape
 *
 * Back, the story's title (one line, ellipsised), then four glass controls:
 *
 * ```
 *   [ 22:40 ⌄ ]   the amber clock pill - opens the World sheet
 *   [ brain ]      the Memory sheet
 *   [ ! ]          the Story sheet
 *   [ menu ✦ ]     the story controls: Pace, Scene length, Tone
 * ```
 *
 * The clock is amber because it is the one header fact that *moves* while a story is read,
 * and amber is the reserved signal for "the world is alive". The rest is chrome, and chrome
 * competes with the fiction - so each control is a 48dp glass target and nothing more.
 */
@Composable
private fun StageHeader(
    stage: ChatStage,
    onBack: () -> Unit,
    onOpenSheet: (StageSheet) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = Charaly.space.xxs, vertical = Charaly.space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CharalyIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.chat_leave_story),
            onClick = onBack,
        )
        Text(
            text = stage.title,
            style = MaterialTheme.typography.titleMedium,
            color = Charaly.ink.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Charaly.space.xxs),
        )
        // The clock pill: amber, from the WorldClock, opening the World sheet.
        if (stage.clockLabel.isNotBlank()) {
            Row(
                modifier = Modifier
                    .clip(CharalyShapes.pill)
                    .background(Charaly.surface.glass)
                    .tappable { onOpenSheet(StageSheet.WORLD) }
                    .padding(horizontal = Charaly.space.xs + 2.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stage.clockLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = Charaly.signal.amber,
                    maxLines = 1,
                )
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Charaly.signal.amber,
                    modifier = Modifier.size(14.dp),
                )
            }
            Spacer(Modifier.width(2.dp))
        }
        CharalyIconButton(
            icon = Icons.Outlined.Psychology,
            contentDescription = stringResource(R.string.chat_memory),
            onClick = { onOpenSheet(StageSheet.MEMORY) },
        )
        CharalyIconButton(
            icon = Icons.Outlined.PriorityHigh,
            contentDescription = stringResource(R.string.chat_story),
            onClick = { onOpenSheet(StageSheet.STORY) },
        )
    }
}

/**
 * One beat of the transcript.
 *
 * ## Why the player's own lines are quiet
 *
 * A player's line is what they typed, so it needs no emphasis - and a bubble around it
 * would give the *user's* words the largest visual weight on the screen, which is exactly
 * backwards. So their lines are right-aligned and set slightly dimmer, and everything in
 * the world gets the ink.
 */
@Composable
/**
 * One line of the transcript.
 *
 * @param packId and @param assetLoader thread the pack's artwork through to the speaker's
 *   portrait. Both are threaded rather than read from an ambient source so this composable
 *   can be rendered in a preview with no assets at all - which is the state where the
 *   generated mark has to carry the whole thing.
 */
private fun BeatView(
    beat: Beat,
    isLast: Boolean,
    packId: String = "",
    assetLoader: dev.charaly.app.ui.art.StoryAssetLoader? = null,
) {
    // Resolved here rather than inside the `semantics {}` block: that block is not a
    // @Composable scope, so a `stringResource` call inside it does not compile.
    val saysDescription = stringResource(R.string.a11y_character_says, beat.speakerName, beat.dialogue)
    when (beat.role) {
        BeatRole.PLAYER -> PlayerBeat(beat)
        BeatRole.CHARACTER -> CharacterBeat(beat, isLast, saysDescription, packId, assetLoader)
        BeatRole.NARRATION -> NarrationBeat(beat)
        BeatRole.SYSTEM -> SystemBeat(beat)
    }
}

/**
 * The player's own line, as an editorial block rather than a chat bubble.
 *
 * ## The V5 shape
 *
 * V5 renders the player's move as a right-aligned block on glass, with the *mode* —
 * SÖYLE, YAP, DÜŞÜN — as a small amber word before the text. The mode is the
 * interesting fact: it says what kind of move this was, and it is the one thing a
 * re-read of the story benefits from seeing again.
 *
 * No tail on the corner, no heavy fill: the player's words are the quietest thing
 * on the stage, because everything else in the world gets the ink.
 */
@Composable
private fun PlayerBeat(beat: Beat) {
    val text = beat.dialogue.ifBlank { beat.narration }.ifBlank { beat.action }
    val mode = when {
        beat.action.isNotBlank() -> "YAP"
        beat.dialogue.isNotBlank() -> "SÖYLE"
        else -> ""
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .widthIn(max = 420.dp)
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 6.dp))
                .background(Charaly.surface.glassStrong)
                .padding(horizontal = Charaly.space.md, vertical = Charaly.space.sm),
        ) {
            if (mode.isNotBlank()) {
                Text(
                    text = mode,
                    style = MaterialTheme.typography.labelMedium,
                    color = Charaly.signal.amber,
                    modifier = Modifier.padding(top = 3.dp, end = Charaly.space.xs),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = Charaly.ink.secondary,
            )
        }
        if (beat.timeLabel.isNotBlank()) {
            Text(
                text = beat.timeLabel,
                style = MaterialTheme.typography.bodySmall,
                color = Charaly.ink.muted,
                modifier = Modifier.padding(top = 2.dp, end = 2.dp),
            )
        }
    }
}

/**
 * A character's reply, as prose.
 *
 * Name in the character's own accent, the spoken line in the brightest ink, the action in
 * italics and dimmer, narration in between. One paragraph, three voices - which is what a
 * reply actually sounds like when you read it.
 */
@Composable
/**
 * A character's line: their portrait, their name, what they said.
 *
 * The portrait resolves through the pack's artwork by `CharacterId`, so the same person
 * looks the same in every scene, in every language, and on every device.
 */
private fun CharacterBeat(
    beat: Beat,
    isLast: Boolean,
    saysDescription: String,
    packId: String = "",
    assetLoader: dev.charaly.app.ui.art.StoryAssetLoader? = null,
) {
    val accent = beat.speakerAccent.toCompose()
    // The V5 shape: the portrait stands outside the text block, and the block itself is
    // a dark panel with the speaker's name set in amber above their words - amber is the
    // reserved "the world is alive" signal, and a person speaking is the world being
    // alive. That panel is what makes a reply read as a *speech in a scene* rather than
    // as a message in a thread - the whole point of the stage.
    Row(Modifier.fillMaxWidth()) {
        // Seeded from the runtime character id, never the display name. Two characters
        // can share a name; they cannot share an id, and they cannot end up sharing a
        // face.
        CharacterMark(
            seed = beat.speakerPortraitSeed.ifBlank { beat.speakerName },
            accent = accent,
            name = beat.speakerName,
            modifier = Modifier.size(36.dp),
            // By id, never by the display name: a portrait resolved through "Adrien"
            // would break the moment the app is used in Turkish.
            characterId = beat.speakerCharacterId,
            packId = packId,
            loader = assetLoader,
        )
        Spacer(Modifier.width(Charaly.space.xs))
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 22.dp))
                .background(Charaly.surface.raised.copy(alpha = 0.82f))
                .padding(horizontal = Charaly.space.md, vertical = Charaly.space.sm),
        ) {
            Text(
                text = beat.speakerName,
                style = MaterialTheme.typography.labelMedium,
                color = Charaly.signal.amber,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (beat.dialogue.isNotBlank()) {
                Text(
                    text = "“${beat.dialogue}”",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Charaly.ink.primary,
                    modifier = Modifier
                        .padding(top = Charaly.space.xxs)
                        .semantics { contentDescription = saysDescription },
                )
            }
            if (beat.action.isNotBlank()) {
                Text(
                    text = beat.action,
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    color = Charaly.ink.muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (beat.narration.isNotBlank()) {
                Text(
                    text = beat.narration,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Charaly.ink.prose,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (beat.timeLabel.isNotBlank()) {
                Text(
                    text = beat.timeLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = Charaly.ink.muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun NarrationBeat(beat: Beat) {
    val text = beat.dialogue.ifBlank { beat.narration }
    if (text.isBlank()) return
    // V5: narration sits inside the same dark translucent bubble as speech, but italic
    // and avatars-less - present in the scene, but not speaking. The bubble keeps the
    // transcript one rhythm rather than two competing typographies.
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
        color = Charaly.ink.prose,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Charaly.surface.raised.copy(alpha = 0.74f))
            .padding(horizontal = Charaly.space.md, vertical = Charaly.space.sm),
    )
}

@Composable
private fun SystemBeat(beat: Beat) {
    Text(
        text = beat.dialogue.ifBlank { beat.narration },
        style = MaterialTheme.typography.bodySmall,
        color = Charaly.ink.muted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * A world moment.
 *
 * ## The amber pulse
 *
 * V5 renders the world's own changes as a small amber pill, inset from the left to
 * line up under the character who speaks. Amber is the reserved signal for "the world
 * is alive" — it is the one colour on the stage that belongs to nobody in the scene,
 * so a reader learns that an amber line is the *place* doing something, not a person.
 *
 * Three of these exist at most, and [StoryMoment] already guarantees they came from the
 * engine rather than from a narrator improvising.
 *
 * The wording is the whole point: "Someone has entered the room", never
 * `CharacterEnteredScene`.
 */
@Composable
private fun MomentLine(moment: StoryMoment) {
    // Hoisted for the same reason as the beat description: `semantics {}` is not a
    // @Composable scope.
    val momentDescription = stringResource(R.string.a11y_story_moment, moment.text)
    val accent = Charaly.atmosphere.accent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Charaly.space.section, top = Charaly.space.xxs, bottom = Charaly.space.xxs),
    ) {
        Text(
            text = moment.text,
            style = MaterialTheme.typography.labelMedium,
            color = accent,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(CharalyShapes.pill)
                .background(accent.copy(alpha = 0.14f))
                .border(1.dp, accent.copy(alpha = 0.30f), CharalyShapes.pill)
                .padding(horizontal = Charaly.space.md, vertical = Charaly.space.xs)
                .semantics { contentDescription = momentDescription },
        )
    }
}

/**
 * "Adrien is thinking…" - a person, not a spinner.
 *
 * V5 sets this line in the world's amber rather than in muted ink: it is the same
 * colour as the world's own moments, because while the model runs, the *world* is the
 * thing that is moving. The spinner keeps the accessibility contract - progress is
 * information, and it stays animated even under reduced motion.
 */
@Composable
private fun ThinkingLine(label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = Charaly.space.xxs),
    ) {
        CharalySpinner(color = Charaly.atmosphere.accent)
        Spacer(Modifier.width(Charaly.space.xs))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Charaly.atmosphere.accent,
        )
    }
}

/**
 * The composer.
 *
 * ## Welded to the bottom, through the keyboard
 *
 * The column above already carries `imePadding()`, so this sits at the bottom of whatever
 * is left and moves up with the keyboard. `navigationBarsPadding()` handles the gesture
 * inset when there is no keyboard, and the two do not stack because the inset consumed is
 * whichever is larger.
 *
 * 52dp minimum, because it is the single most-touched control in the app.
 */
@Composable
private fun StageComposer(
    composer: dev.charaly.runtime.presentation.Composer,
    generating: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(ComposerMode.SAY) }

    val sendEnabled = text.isNotBlank() && composer.isEnabled

    /**
     * The V5 composer: the mode lives *inside* the field, as a small amber word before
     * the text. Tapping it cycles SÖYLE → YAP → DÜŞÜN → …, which is the whole mode
     * selector - no tab bar, no expandable row, no second control competing with the
     * keyboard for the thumb.
     */
    fun cycleMode() {
        val modes = composer.modes
        if (modes.isEmpty()) return
        mode = modes[(modes.indexOf(mode) + 1).mod(modes.size)]
    }

    fun send() {
        val composed = mode.compose(text)
        if (composed.isNotBlank()) {
            onSend(composed)
            text = ""
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Charaly.surface.void)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(
                start = Charaly.space.sm,
                end = Charaly.space.sm,
                top = Charaly.space.xs,
                bottom = Charaly.space.xs,
            ),
    ) {
        // Why the composer is disabled, said out loud. A greyed-out input with no
        // explanation reads as a broken app.
        val blocked = composer.blockedReason()
        if (blocked.isNotBlank()) {
            Text(
                text = blocked,
                style = MaterialTheme.typography.bodySmall,
                color = Charaly.atmosphere.accent,
                modifier = Modifier.padding(
                    start = Charaly.space.sm,
                    bottom = Charaly.space.xxs,
                ),
            )
        }

        Row(verticalAlignment = Alignment.Bottom) {
            ComposerField(
                value = text,
                onValueChange = { text = it },
                placeholder = mode.hint.ifBlank { composer.hint },
                enabled = composer.isEnabled,
                modeLabel = mode.label,
                onCycleMode = { cycleMode() },
                onSend = ::send,
                modifier = Modifier.weight(1f),
            )

            Spacer(Modifier.width(Charaly.space.xs))

            if (generating) {
                CharalyIconButton(
                    icon = Icons.Filled.Stop,
                    contentDescription = Loc.t("chat.stop"),
                    onClick = onStop,
                    container = Charaly.surface.elevated,
                )
            } else {
                CharalyIconButton(
                    icon = Icons.AutoMirrored.Filled.Send,
                    contentDescription = Loc.t("chat.send"),
                    onClick = ::send,
                    enabled = sendEnabled,
                    container = if (sendEnabled) {
                        Charaly.atmosphere.accent
                    } else {
                        Charaly.surface.raised
                    },
                    tint = if (sendEnabled) Charaly.atmosphere.onAccent else Charaly.ink.muted,
                )
            }
        }
    }
}

/**
 * The input surface.
 *
 * A `BasicTextField` on a filled pill rather than an outlined box with a floating label.
 * It is the most-touched object in the product and it should look like somewhere you type,
 * not like a form field.
 *
 * ## The mode word, inside the field
 *
 * V5 puts the acting mode — SÖYLE, YAP, DÜŞÜN — *inside* the field as a small amber
 * word before the text. Tapping it cycles to the next mode. That single tappable word
 * replaces an expandable row of pills, which is the "devasa tab bar" the brief bans:
 * the selector is present exactly where the thumb already is and nowhere else.
 *
 * Bounded between 52dp and 160dp: a composer that grows toward the middle of the screen
 * pushes the transcript off, which is what an unbounded text field does on a tablet.
 */
@Composable
private fun ComposerField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    modeLabel: String = "",
    onCycleMode: () -> Unit = {},
) {
    Row(
        modifier = modifier
            .clip(CharalyShapes.pill)
            .background(Charaly.surface.raised)
            .padding(horizontal = Charaly.space.md, vertical = Charaly.space.sm)
            .heightIn(min = 52.dp, max = 160.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Charaly.space.xs),
    ) {
        if (modeLabel.isNotBlank()) {
            Text(
                text = modeLabel,
                style = MaterialTheme.typography.labelMedium,
                color = Charaly.atmosphere.accent,
                maxLines = 1,
                modifier = Modifier
                    .clip(CharalyShapes.pill)
                    .clickable(onClick = onCycleMode)
                    .padding(vertical = Charaly.space.xs)
                    .semantics {
                        role = Role.Button
                        contentDescription = Loc.t("action.ways_of_acting")
                    },
            )
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Charaly.ink.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { contentDescription = Loc.t("chat.your_line") },
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Charaly.ink.primary),
                cursorBrush = SolidColor(Charaly.atmosphere.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * A failure, in a sentence, with somewhere to go.
 *
 * ## The technical half is collapsed
 *
 * "HTTP 403" is what appears in a bug report. "That model could not be fetched." is what
 * happened. The first is below a disclosure, because a person reading a story app is not
 * debugging it - but it is *there*, because a person who is debugging it needs it.
 */
@Composable
private fun FailureSurface(
    failure: StageFailure,
    onAction: (StageAction) -> Unit,
) {
    var showDetail by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(
                horizontal = Charaly.space.gutter,
                vertical = Charaly.space.sm,
            ),
    ) {
        Text(
            text = failure.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Row(
            modifier = Modifier.padding(top = Charaly.space.xxs),
            horizontalArrangement = Arrangement.spacedBy(Charaly.space.xs),
        ) {
            failure.actions.forEach { action ->
                CharalyQuietAction(
                    label = action.label,
                    onClick = { onAction(action) },
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            if (failure.detail.isNotBlank()) {
                CharalyQuietAction(
                    label = if (showDetail) "Ayrıntıları gizle" else "Ayrıntılar",
                    onClick = { showDetail = !showDetail },
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        if (showDetail && failure.detail.isNotBlank()) {
            Text(
                text = failure.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = Charaly.space.xxs),
            )
        }
    }
}

/**
 * The persistent context column on a wide screen.
 *
 * A *summary*, not a second copy of the sheets: where you are, who is here, what is
 * unresolved. Everything else stays one tap away, because a panel that repeats the sheets
 * is a panel with two jobs.
 */
@Composable
private fun StageContextPane(
    stage: ChatStage,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = Charaly.space.md,
            end = Charaly.space.md,
            top = Charaly.space.xxl,
            bottom = Charaly.space.section,
        ),
        verticalArrangement = Arrangement.spacedBy(Charaly.space.md),
    ) {
        item(key = "ctx-where") {
            Column {
                Text(
                    text = Loc.t("chat.sheet_scene"),
                    style = MaterialTheme.typography.labelSmall,
                    color = Charaly.ink.muted,
                )
                Text(
                    text = stage.contextLine.ifBlank { "Bir yer" },
                    style = MaterialTheme.typography.titleMedium,
                    color = Charaly.ink.primary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        if (stage.people.isNotEmpty()) {
            item(key = "ctx-people") {
                Column {
                    Text(
                        text = Loc.t("chat.sheet_room"),
                        style = MaterialTheme.typography.labelSmall,
                        color = Charaly.ink.muted,
                        modifier = Modifier.padding(bottom = Charaly.space.xs),
                    )
                    stage.people.forEach { person ->
                        CharalyPresence(
                            name = person.name,
                            accent = person.accent.toCompose(),
                            markSize = 32.dp,
                            status = person.whereabouts.style(),
                            caption = person.relationshipLabel,
                            onClick = null,
                        )
                    }
                }
            }
        }

        if (stage.moments.isNotEmpty()) {
            item(key = "ctx-moments") {
                Column {
                    Text(
                        text = Loc.t("chat.sheet_recently"),
                        style = MaterialTheme.typography.labelSmall,
                        color = Charaly.ink.muted,
                        modifier = Modifier.padding(bottom = Charaly.space.xs),
                    )
                    stage.moments.forEach { moment ->
                        Text(
                            text = moment.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Charaly.ink.secondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(bottom = Charaly.space.xs),
                        )
                    }
                }
            }
        }
    }
}

/** Projects the runtime's presence enum onto the component's own, so there is one vocabulary. */
internal fun Whereabouts.style(): PresenceStyle = when (this) {
    Whereabouts.HERE -> PresenceStyle.HERE
    Whereabouts.NEARBY -> PresenceStyle.NEARBY
    Whereabouts.ELSEWHERE -> PresenceStyle.ELSEWHERE
}

@Composable
private fun StageMissing(loading: Boolean, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Charaly.surface.void),
    ) {
        Row(Modifier.padding(start = Charaly.space.xs, top = Charaly.space.xl)) {
            CharalyIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = Loc.t("action.back"),
                onClick = onBack,
            )
        }
        if (loading) {
            dev.charaly.app.ui.components.CharalySkeleton(
                Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .padding(Charaly.space.gutter),
                shape = CharalyShapes.soft,
            )
        } else {
            CharalyEmptyState(
                state = dev.charaly.runtime.presentation.EmptyState(
                    title = Loc.t("chat.missing_title"),
                    body = Loc.t("chat.missing_body"),
                    actionLabel = "Back",
                    artSeed = "charaly-empty-stage",
                ),
                action = { dev.charaly.app.ui.components.CharalyAction(label = Loc.t("action.back"), onClick = onBack) },
            )
        }
    }
}

/** A stage's context sheets. The four questions, plus the two tools. */
enum class StageSheet(val title: String, val caption: String) {
    WORLD("Dünya", "Nerede olduğunuz ve etrafınızda ne var"),
    MEMORY("Hafıza", "Onlar sizi nasıl hatırlıyor"),
    PEOPLE("İnsanlar", "Burada kimler var ve size nasıl duruyorlar"),
    STORY("Hikâye", "Ne çözülmemiş olarak duruyor"),
    ;

    companion object {
        /** The four, in sheet order. */
        val PLAYER_FACING: List<StageSheet> = listOf(WORLD, MEMORY, PEOPLE, STORY)
    }
}

/**
 * The stage's two tool sheets - not among the four questions, but reachable from the
 * composer's affordances: letting time pass, and tuning the story's controls.
 */
enum class StageToolSheet {
    /** +10 minutes, +1 hour, until morning. The clock moves only through the engine. */
    SKIP_TIME,

    /** Pace, scene length, tone - segment selectors, persisted per story. */
    CONTROLS,
}

/** The sheet host, drawn by the stage. Declared here to keep the stage file self-contained. */
/** The measure the prose column is capped at on a wide screen. */
internal val ProseMeasure: Dp = 640.dp
