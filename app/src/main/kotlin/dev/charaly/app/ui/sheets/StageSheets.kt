package dev.charaly.app.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Charaly.surface.raised)
            .navigationBarsPadding(),
    ) {
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
                    onClick = { onSelectSheet(entry) },
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
                StageSheet.STORY -> storySheet(stage, context)
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

    item(key = "where") {
        Column {
            Text(
                text = location ?: "Somewhere",
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
                    text = world?.presenceLabel?.takeIf { it.isNotBlank() } ?: "Just you",
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
                    body = "Memories appear here as the story accumulates them - what people " +
                        "experienced, and what they decided about you.",
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
            if (card.isSecret) CharalyPill(label = "A secret")
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
                    body = "People arrive as the story needs them. Open this again once " +
                        "someone has walked in.",
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
                caption = "Elsewhere in this world",
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
) {
    val story = context?.story

    item(key = "story-now") {
        Column {
            CharalySectionHeader(title = Loc.t("sheet.right_now"), micro = true)
            Text(
                text = story?.currentBeat?.takeIf { it.isNotBlank() }
                    ?: stage.moments.firstOrNull()?.text
                    ?: "Nothing is pressing yet.",
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