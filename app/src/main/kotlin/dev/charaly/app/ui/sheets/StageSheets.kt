package dev.charaly.app.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.charaly.runtime.presentation.Loc
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.charaly.app.ui.components.CharalyAction
import dev.charaly.app.ui.components.CharalyEmptyState
import dev.charaly.app.ui.components.CharalyIconButton
import dev.charaly.app.ui.components.CharalyPill
import dev.charaly.app.ui.components.CharalyPresence
import dev.charaly.app.ui.components.CharalySectionHeader
import dev.charaly.app.ui.components.PresenceStyle
import dev.charaly.app.ui.design.Charaly
import dev.charaly.app.ui.design.CharalyShapes
import dev.charaly.app.ui.screens.StageSheet
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.WorldDefinition
import dev.charaly.runtime.presentation.ChatStage
import dev.charaly.runtime.presentation.EmptyState
import dev.charaly.runtime.presentation.MemoryCard
import dev.charaly.runtime.presentation.PersonRow
import dev.charaly.runtime.presentation.StoryContext
import dev.charaly.runtime.presentation.StoryContextPresenter

/**
 * THE FOUR CONTEXTUAL SHEETS.
 *
 * ## Why four, and why these four
 *
 * The chat screen used to offer seven destinations: story, characters, world state, memory,
 * model, scene, session. That list is organised by *engine subsystem*, which is how this
 * codebase is arranged and not how someone thinks about standing in a room.
 *
 * A player mid-scene has four questions:
 *
 * ```
 *   WORLD    where am I, and when is it?
 *   MEMORY   what does this person remember about me?
 *   PEOPLE   who is here, and how do they feel about me?
 *   STORY    what am I supposed to be doing?
 * ```
 *
 * The old Model, Scene and Session sheets were not deleted: the engine's own view is one
 * Settings toggle away, gated behind developer mode. They are simply not four of the seven
 * things a player is offered while trying to be in a story.
 *
 * ## The hard rule
 *
 * Nothing in these four projections may contain an id, a score, a tier name or an event
 * type. That is asserted by the runtime's product-presentation tests rather than by review,
 * because it is exactly the kind of thing that leaks in through one field during a later
 * change.
 *
 * ## Each tab carries its own answer
 *
 * The moment of deciding whether to open a sheet is *before* opening it. A tab labelled
 * only "PEOPLE" makes the reader open it to find out whether anyone is; "PEOPLE · 2 here"
 * answers the question that prompted the tap, and the sheet becomes a confirmation. Every
 * figure is counted from the same projection the sheet renders, so a tab and its contents
 * cannot disagree.
 *
 * An empty answer renders as no suffix at all. "MEMORY ·" is noise.
 */
@Composable
fun StageSheetContent(
    sheet: StageSheet,
    stage: ChatStage,
    context: StoryContext?,
    onSelectSheet: (StageSheet) -> Unit,
    onDismiss: () -> Unit,
    onSelectSpeaker: (String) -> Unit,
    /**
     * Moves the world clock, in minutes.
     *
     * Never a setter: the same `advance` a real turn takes, through the event engine, so
     * the hours that pass are hours the world actually experiences. The UI has no way to
     * put a time on the clock and no interest in one.
     */
    onAdvanceTime: (Int) -> Unit = {},
    /**
     * What the story is asking of the player right now - active objectives, titles and
     * descriptions only.
     *
     * Handed in rather than derived here: the sheet projects `StoryContext`, which is
     * about the scene and its people, and an objective is a promise between the player and
     * the pack. It answers the STORY sheet's question - "what am I supposed to be doing?" -
     * in the player's own terms.
     */
    objectives: List<dev.charaly.app.ui.packstore.PackObjectiveRow> = emptyList(),
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CharalyShapes.sheet)
            .background(Charaly.surface.base)
            .navigationBarsPadding(),
    ) {
        // The V5 drag handle: a small bar that says "this layer can be pulled down",
        // before any content claims the eye. It is the only decoration on the sheet's
        // top edge - no title strip, no grabber text.
        Box(
            Modifier
                .padding(top = Charaly.space.sm, bottom = Charaly.space.sm)
                .align(Alignment.CenterHorizontally)
                .size(width = 40.dp, height = 4.dp)
                .clip(CharalyShapes.pill)
                .background(Charaly.surface.elevated),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = Charaly.space.gutter),
            horizontalArrangement = Arrangement.spacedBy(Charaly.space.xs),
            modifier = Modifier.padding(top = Charaly.space.sm, bottom = Charaly.space.xs),
        ) {
            items(StageSheet.PLAYER_FACING.size, key = { StageSheet.PLAYER_FACING[it].name }) { index ->
                val entry = StageSheet.PLAYER_FACING[index]
                val summary = summaryOf(entry, stage, context)
                CharalyPill(
                    label = if (summary.isBlank()) entry.title else "${entry.title} · $summary",
                    selected = entry == sheet,
                    // PHASE 9: the active tab is now a toggle. It used to call
                    // `onSelectSheet(entry)` unconditionally, which re-selected what was
                    // already selected - so tapping the pill you could see was selected did
                    // nothing at all, and the reader had to find the X in the heading to get
                    // rid of a panel they had just opened. Selected-state controls that look
                    // like tabs are expected to toggle; this is the same gesture on the same
                    // pill, and it closes the sheet exactly as the X and system back do.
                    onClick = {
                        if (entry == sheet) onDismiss() else onSelectSheet(entry)
                    },
                    contentDescription = buildString {
                        append(entry.title)
                        append(". ").append(entry.caption)
                        if (summary.isNotBlank()) append(". ").append(summary)
                    },
                )
            }
        }

        // One vertical scroll owner. The sheet is a layer *over* the scene, so its height
        // is bounded and the transcript keeps its own scroll behind it.
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(SHEET_HEIGHT),
            contentPadding = PaddingValues(
                start = Charaly.space.gutter,
                end = Charaly.space.gutter,
                bottom = Charaly.space.lg,
            ),
            verticalArrangement = Arrangement.spacedBy(Charaly.space.md),
        ) {
            item(key = "heading") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = sheet.title,
                            style = MaterialTheme.typography.headlineMedium,
                            color = Charaly.ink.primary,
                            modifier = Modifier.semantics { heading() },
                        )
                        if (sheet.caption.isNotBlank()) {
                            Text(
                                text = sheet.caption,
                                style = MaterialTheme.typography.bodySmall,
                                color = Charaly.ink.muted,
                            )
                        }
                    }
                    CharalyIconButton(
                        icon = Icons.Filled.Close,
                        contentDescription = Loc.t("a11y.close_sheet", sheet.title),
                        onClick = onDismiss,
                    )
                }
            }

            when (sheet) {
                StageSheet.WORLD -> worldSheet(stage, context)
                StageSheet.MEMORY -> memorySheet(context)
                StageSheet.PEOPLE -> peopleSheet(context, onSelectSpeaker)
                StageSheet.STORY -> storySheet(stage, context, objectives)
                StageSheet.SKIP_TIME -> skipTimeSheet(stage, onAdvanceTime)
            }
        }
    }
}

/**
 * The sheet's height.
 *
 * Bounded rather than wrapping its content, because a sheet that grows to fit forty
 * memories stops being a layer over the scene and becomes the screen. Overflow scrolls
 * inside it instead.
 */
private val SHEET_HEIGHT: Dp = 460.dp

/**
 * The live answer on each tab.
 *
 * Empty is a legitimate answer and produces no suffix, so the label is never left with a
 * dangling separator.
 */
private fun summaryOf(
    sheet: StageSheet,
    stage: ChatStage,
    context: StoryContext?,
): String = when (sheet) {
    StageSheet.WORLD -> context?.world?.locationName?.takeIf { it.isNotBlank() }.orEmpty()
    StageSheet.MEMORY -> context?.memory
        ?.takeIf { it.isNotEmpty() }
        ?.let { "${it.size} remembered" }
        .orEmpty()
    StageSheet.PEOPLE -> stage.people
        .count { it.isHere }
        .takeIf { it > 0 }
        ?.let { "$it here" }
        .orEmpty()
    StageSheet.STORY -> context?.story?.threads
        ?.takeIf { it.isNotEmpty() }
        ?.let { "${it.size} open" }
        .orEmpty()
    // Never a countdown: "57 minutes" would promise a precision the choice does not offer.
    StageSheet.SKIP_TIME -> ""
}

/**
 * SKIP TIME.
 *
 * ## Three honest choices
 *
 * Ten minutes, an hour, and - when the clock's own reading makes the question meaningful -
 * morning. Each one is a request to the engine, not a slider: the hours are *experienced*,
 * which is why turning the clock is worth offering at all.
 */
private fun LazyListScope.skipTimeSheet(
    stage: ChatStage,
    onAdvanceTime: (Int) -> Unit,
) {
    item(key = "skip-lead") {
        Text(
            text = "Saat yalnızca dünyada ilerleyerek geçer. Arada geçen her şey yaşanmış olur.",
            style = MaterialTheme.typography.bodyMedium,
            color = Charaly.ink.secondary,
        )
    }

    // "Until morning" only where the clock is legible enough for the question to mean
    // anything. Where it is not, the option is simply not offered rather than guessed at.
    val untilMorning = minutesUntilMorning(stage.clockLabel)

    item(key = "skip-options") {
        Column(verticalArrangement = Arrangement.spacedBy(Charaly.space.xs)) {
            CharalyAction(label = "+10 dakika", onClick = { onAdvanceTime(10) }, fillWidth = true)
            CharalyAction(label = "+1 saat", onClick = { onAdvanceTime(60) }, fillWidth = true)
            if (untilMorning != null) {
                CharalyAction(
                    label = "Sabaha kadar",
                    onClick = { onAdvanceTime(untilMorning) },
                    fillWidth = true,
                )
            }
        }
    }
}

/**
 * The day, drawn as an arc with the present moment marked on it.
 *
 * A day is the unit a reader reasons in when deciding whether to let time pass, and a
 * glowing bar from amber into darkness says "three quarters of your daylight is gone"
 * faster than any number. The gradient stays inside Charaly's two accents - this is the
 * world's light draining, not a fourth theme colour.
 */
@Composable
private fun DayArc(timeLabel: String, modifier: Modifier = Modifier) {
    val elapsed = minutesIntoDay(timeLabel) ?: 12 * 60
    val fraction = (elapsed / (24f * 60f)).coerceIn(0.03f, 0.97f)

    Box(modifier = modifier.fillMaxWidth().height(14.dp)) {
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(6.dp)
                .clip(CharalyShapes.pill)
                .background(
                    Brush.horizontalGradient(
                        listOf(Charaly.signal.amber, Charaly.surface.overlay),
                    )
                ),
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(fraction))
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CharalyShapes.pill)
                    .background(Charaly.ink.primary)
                    .border(2.dp, Charaly.signal.amber, CharalyShapes.pill),
            )
            Box(Modifier.weight(1f - fraction))
        }
    }
}

/** Minutes past midnight in "22:40". Null when the label is not a clock reading. */
internal fun minutesIntoDay(clockLabel: String): Int? {
    val parts = clockLabel.trim().split(":")
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}

/**
 * Minutes from "22:40" to seven in the morning. Null when the label is not a clock reading.
 *
 * Arithmetic on the engine's own label rather than on a second clock, so the answer cannot
 * disagree with the number shown in the header.
 */
internal fun minutesUntilMorning(clockLabel: String): Int? {
    val now = minutesIntoDay(clockLabel) ?: return null
    val morning = 7 * 60
    return if (morning > now) morning - now else (24 * 60 - now) + morning
}

/**
 * WORLD.
 *
 * ## Translated, not dumped
 *
 * The engine holds this scene's location id, its variables and its character runtimes. What
 * a reader needs is a sentence: where you are, what it looks like, who is nearby.
 *
 * The "recently" block is the world's own record of a change, in the world's own words -
 * not a dump of the event log, and never an event class name.
 */
private fun LazyListScope.worldSheet(
    stage: ChatStage,
    context: StoryContext?,
) {
    val world = context?.world
    val location = world?.locationName?.takeIf { it.isNotBlank() }
        ?: stage.contextLine.substringBefore(" · ").takeIf { it.isNotBlank() }

    // ---- the clock, first and large -------------------------------------
    //
    // The exact reading, in amber and at display size, because "where am I and when is it"
    // is this sheet's whole question and "Evening" is true for four hours. Under it, the
    // day drawn as an arc with the present moment marked on it - a day is the unit a
    // reader actually reasons in when they ask whether to let time pass.
    val clock = world?.clockLabel?.takeIf { it.isNotBlank() } ?: stage.clockLabel
    if (clock.isNotBlank()) {
        item(key = "clock") {
            Column {
                Text(
                    text = clock,
                    style = MaterialTheme.typography.displaySmall,
                    color = Charaly.signal.amber,
                    modifier = Modifier.semantics { heading() },
                )
                val sub = listOfNotNull(world?.dayLabel, world?.timeOfDay)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                if (sub.isNotBlank()) {
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.titleSmall,
                        color = Charaly.ink.primary,
                        modifier = Modifier.padding(top = Charaly.space.xs),
                    )
                }
                DayArc(
                    timeLabel = clock,
                    modifier = Modifier.padding(top = Charaly.space.md),
                )
            }
        }
    }

    item(key = "where") {
        Column {
            Text(
                text = location ?: "Bir yer",
                style = MaterialTheme.typography.headlineSmall,
                color = Charaly.ink.primary,
                modifier = Modifier.semantics { heading() },
            )
            if (!world?.locationDescription.isNullOrBlank()) {
                Text(
                    text = world.locationDescription,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Charaly.ink.secondary,
                    modifier = Modifier.padding(top = Charaly.space.xs),
                )
            }
            if (world != null) {
                Row(
                    modifier = Modifier.padding(top = Charaly.space.sm),
                    horizontalArrangement = Arrangement.spacedBy(Charaly.space.xs),
                ) {
                    world.timeOfDay.takeIf { it.isNotBlank() }?.let { CharalyPill(label = it) }
                    world.dayLabel.takeIf { it.isNotBlank() }?.let { CharalyPill(label = it) }
                    world.region.takeIf { it.isNotBlank() }?.let { CharalyPill(label = it) }
                }
            }
        }
    }

    val moment = stage.moments.firstOrNull()?.text.orEmpty()
    if (moment.isNotBlank()) {
        item(key = "changed") {
            Column {
                CharalySectionHeader(title = Loc.t("sheet.recently"), micro = true)
                Text(
                    text = moment,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Charaly.ink.secondary,
                    modifier = Modifier.padding(top = Charaly.space.xs),
                )
            }
        }
    }

    val nearby = world?.nearby.orEmpty()
    item(key = "nearby") {
        Column {
            CharalySectionHeader(title = Loc.t("sheet.nearby"), micro = true)
            Spacer(Modifier.height(Charaly.space.xs))
            if (nearby.isEmpty()) {
                Text(
                    text = world?.presenceLabel?.takeIf { it.isNotBlank() } ?: Loc.t("sheet.just_you"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Charaly.ink.muted,
                )
            } else {
                nearby.forEach { name ->
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Charaly.ink.secondary,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * MEMORY.
 *
 * ## Importance hierarchy, and no ids
 *
 * Pinned first, then most important, then most recent - because "what she is holding onto"
 * is a better answer than "everything, newest first".
 *
 * No internal ids, no store terminology, no scores. The memory's own text, who holds it,
 * and when. [MemoryCard] is the same type the full memory screen uses, so the two cannot
 * drift apart in wording.
 */
private fun LazyListScope.memorySheet(context: StoryContext?) {
    val cards = context?.memory.orEmpty()
    if (cards.isEmpty()) {
        item(key = "memory-empty") {
            CharalyEmptyState(
                state = EmptyState(
                    title = Loc.t("sheet.no_memories_title"),
                    body = Loc.t("sheet.no_memories_body_long"),
                    artSeed = "charaly-empty-memory",
                ),
            )
        }
        return
    }

    val ordered = cards.sortedWith(
        compareByDescending<MemoryCard> { it.isPinned }
            .thenByDescending { it.importance }
            .thenBy { it.timeLabel },
    )

    ordered.forEach { card ->
        item(key = "memory-${card.id}") {
            MemoryRow(card = card)
        }
    }
}

@Composable
private fun MemoryRow(card: MemoryCard) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CharalyShapes.soft)
            .background(Charaly.surface.base)
            .padding(Charaly.space.md),
    ) {
        Text(
            text = card.text,
            style = MaterialTheme.typography.bodyLarge,
            color = Charaly.ink.primary,
        )
        Row(
            modifier = Modifier.padding(top = Charaly.space.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Charaly.space.xs),
        ) {
            // Importance as a word, never as a number.
            CharalyPill(label = card.importanceLabel, selected = card.importance >= 4)
            if (card.isSecret) CharalyPill(label = "Bir sır")
            Text(
                text = listOfNotNull(
                    card.ownerName.takeIf { it.isNotBlank() },
                    card.timeLabel.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = Charaly.ink.muted,
            )
        }
    }
}

/**
 * PEOPLE.
 *
 * ## Two sections, because "everyone" is not a useful list
 *
 * **Here now** is who can actually be spoken to. **Worth knowing** is anyone the story still
 * has a claim on - a promise made, a secret known - read from the live ledger rather than
 * from the pack, so it changes as the story does.
 *
 * A flat list of every character a pack contains is the old pack detail screen's mistake
 * repeated one level down, and it puts nineteen names in front of a reader who has met two.
 */
private fun LazyListScope.peopleSheet(
    context: StoryContext?,
    onSelectSpeaker: (String) -> Unit,
) {
    val people = context?.people.orEmpty()
    if (people.isEmpty()) {
        item(key = "people-empty") {
            CharalyEmptyState(
                state = EmptyState(
                    title = Loc.t("sheet.nobody_title"),
                    body = Loc.t("sheet.nobody_body_long"),
                    artSeed = "charaly-empty-people",
                ),
            )
        }
        return
    }

    val here = people.filter { it.isHere }
    val elsewhere = people.filterNot { it.isHere }

    if (here.isNotEmpty()) {
        item(key = "people-here") {
            CharalySectionHeader(title = Loc.t("sheet.here_now"), micro = true)
        }
        here.forEach { person ->
            item(key = "person-here-${person.id}") {
                PersonRowCard(person = person, onSelect = onSelectSpeaker)
            }
        }
    }

    if (elsewhere.isNotEmpty()) {
        item(key = "people-elsewhere") {
            CharalySectionHeader(
                title = Loc.t("sheet.worth_knowing"),
                micro = true,
                caption = Loc.t("sheet.elsewhere_in_world"),
            )
        }
        elsewhere.forEach { person ->
            item(key = "person-away-${person.id}") {
                PersonRowCard(person = person, onSelect = onSelectSpeaker)
            }
        }
    }
}

/**
 * One person.
 *
 * Portrait, name, one sentence. The standing is a *phrase* - "trusting", "guarded" - never
 * a number, because a reader has no reference frame for "trust 62" and reads it as a score
 * out of a hundred in a game they are not playing.
 */
@Composable
private fun PersonRowCard(person: PersonRow, onSelect: (String) -> Unit) {
    CharalyPresence(
        name = person.name,
        accent = Charaly.atmosphere.accent,
        markSize = 44.dp,
        status = if (person.isHere) PresenceStyle.HERE else PresenceStyle.ELSEWHERE,
        caption = listOfNotNull(
            person.standing.takeIf { it.isNotBlank() },
            person.role.takeIf { it.isNotBlank() && it != person.standing },
        ).joinToString(" · "),
        onClick = { onSelect(person.id) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Charaly.space.xs)
            .semantics {
                contentDescription = buildString {
                    append(person.name)
                    if (person.standing.isNotBlank()) append(". ").append(person.standing)
                    append(if (person.isHere) ". Here now" else ". Elsewhere")
                }
            },
    )
}

/**
 * STORY.
 *
 * ## Recent moments, open threads, and the current demand
 *
 * In that order, because that is the order a reader asks in: what just happened, what is
 * still going, and what am I supposed to do about it.
 *
 * Progress is a word - "Underway", "Nearly there" - because a 0-100 bar is an engine number
 * on a product screen. The underlying value still decides the wording, so the information
 * survives; only the false precision is lost.
 */
private fun LazyListScope.storySheet(
    stage: ChatStage,
    context: StoryContext?,
    objectives: List<dev.charaly.app.ui.packstore.PackObjectiveRow>,
) {
    val story = context?.story

    // What the story is asking of the player, first: this sheet's question is "what am I
    // supposed to be doing?", and an answer the player can act on outranks a recap. Only
    // active, non-secret objectives arrive here - the projection decides that, so a hidden
    // agenda cannot leak out of a sheet.
    if (objectives.isNotEmpty()) {
        item(key = "story-objectives") {
            Column {
                CharalySectionHeader(title = "Görevler", micro = true)
                objectives.forEach { row ->
                    Text(
                        text = row.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Charaly.ink.primary,
                        modifier = Modifier.padding(top = Charaly.space.xs),
                    )
                    if (row.description.isNotBlank()) {
                        Text(
                            text = row.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = Charaly.ink.muted,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }

    item(key = "story-now") {
        Column {
            CharalySectionHeader(title = Loc.t("sheet.right_now"), micro = true)
            Text(
                text = story?.currentBeat?.takeIf { it.isNotBlank() }
                    ?: stage.moments.firstOrNull()?.text
                    ?: Loc.t("sheet.nothing_pressing"),
                style = MaterialTheme.typography.headlineSmall,
                color = Charaly.ink.primary,
                modifier = Modifier.padding(top = Charaly.space.xs),
            )
        }
    }

    if (story != null && story.elapsed.isNotBlank()) {
        item(key = "story-elapsed") {
            Text(
                text = story.elapsed,
                style = MaterialTheme.typography.bodySmall,
                color = Charaly.ink.muted,
            )
        }
    }

    val threads = story?.threads.orEmpty()
    if (threads.isNotEmpty()) {
        item(key = "story-threads") {
            CharalySectionHeader(
                title = Loc.t("sheet.still_going_on"),
                micro = true,
                caption = if (threads.size == 1) "1 thread" else "${threads.size} threads",
            )
        }
        threads.forEach { thread ->
            item(key = "thread-${thread.id}") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = thread.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Charaly.ink.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(Charaly.space.xs))
                    CharalyPill(label = thread.progress.ifBlank { thread.status })
                }
            }
        }
    }
}

/**
 * Builds the four sheets from authoritative state.
 *
 * A thin wrapper so the screen does not have to know the projections come from
 * `StoryContextPresenter`, and so a test builds the same four sheets from the same instance
 * the runtime produced.
 */
object StageSheets {

    fun build(
        definition: WorldDefinition,
        instance: StoryInstance,
    ): StoryContext = StoryContextPresenter.build(definition, instance)
}