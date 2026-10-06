package dev.charaly.runtime.presentation

import dev.charaly.runtime.domain.PackArtwork
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.WorldDefinition
import dev.charaly.runtime.engine.EventEngine

/**
 * THE WORLD LOBBY.
 *
 * ## What this file is for
 *
 * The engine already decides everything: who is in the room, what changed, where the
 * player is standing, what a world is about. What was missing was a projection shaped
 * like a *doorway* rather than like a report.
 *
 * The old Home was a launchpad that happened to contain a dashboard: a greeting, a card,
 * a strip, a carousel, a list of recent sessions and a model status row. Every element
 * was correct and the sum read as inventory.
 *
 * This projection answers three questions, in this order, and refuses everything else:
 *
 * ```
 *   1. Where am I going?          one enormous continuation surface, if there is one
 *   2. Where could I go?          the worlds, as a feed with real visual weight
 *   3. What else is mine?         the stories shelf, quiet, below the fold
 * ```
 *
 * What is deliberately absent: character counts, location counts, event counts, thread
 * counts, memory totals, affinity numbers. None of them are things a player is about to
 * do. They are things the *engine* is about to do, and the engine does them without
 * being asked.
 *
 * ## Why it lives in the runtime module
 *
 * Same reason every other presenter does: it is a pure function of authoritative state,
 * so it can be asserted on a JVM in milliseconds with no device, no emulator and no
 * Compose. Every string on the lobby is asserted there rather than eyeballed here.
 */

// ---------------------------------------------------------------------------
// The continuation surface
// ---------------------------------------------------------------------------

/**
 * The one thing Home is about when there is a story to go back to.
 *
 * Every field is a *fact about the world*, not a statistic about the app. There is no
 * turn count, no memory total and no "3 threads" here, because the question a returning
 * player has is "what is happening in there", and the engine can answer exactly that.
 */
data class ContinueSurface(
    val storyId: String,
    /** "PARIS" - the world, not the story's working title. */
    val worldName: String,
    val packTitle: String,
    /**
     * One sentence describing the current moment.
     *
     * The load-bearing line of the whole screen. It is never written by hand here: it is
     * the most recent thing the world actually reported, or the open thread's own next
     * beat, or - if the world genuinely has nothing new - an honest sentence saying so.
     */
    val moment: String,
    val companionName: String,
    val companionAccent: Long,
    val companionArtwork: PackArtwork,
    /** The world's own artwork, edge to edge. */
    val artwork: PackArtwork,
    val theme: ResolvedTheme,
    /** "1 person here" - never blank, never a count of characters in the pack. */
    val presenceLabel: String,
    /** "Evening · Day 3", in story time. */
    val timeLabel: String,
    val lastPlayedLabel: String,
) {
    /** Whether there is somebody to walk back in with. */
    val hasCompanion: Boolean get() = companionName.isNotBlank()
}

// ---------------------------------------------------------------------------
// The worlds feed
// ---------------------------------------------------------------------------

/**
 * How much room a world gets.
 *
 * The brief asks for a feed rather than a grid, and for cards with *different* visual
 * weights. This enum is that decision, made before layout so it is testable: a feed of
 * identically sized cards is a grid wearing a vertical hat.
 */
enum class WorldWeight {
    /** The first world. Full-bleed, tall, the largest type on the screen. */
    DOMINANT,

    /** Everything else that has been stepped into, or is merely prominent. */
    FULL,

    /** The long tail. Narrower, quieter, still legible. */
    QUIET,
}

data class WorldFeedItem(
    val id: String,
    val title: String,
    /** One or two sentences. The pitch. */
    val premise: String,
    /** "Warm, breathless, a little melancholy underneath the jokes." */
    val tone: String,
    /**
     * A single hook line: "A city watched by heroes."
     *
     * Authored, never generated, and at most one per world - a feed of three lines each
     * is a specification rather than an invitation.
     */
    val hook: String,
    val genres: List<String>,
    val artwork: PackArtwork,
    val theme: ResolvedTheme,
    val weight: WorldWeight,
    /** "Yesterday" / "New". One fact, and only when it is known. */
    val lastPlayedLabel: String,
    /** How many stories exist here. The only count on the whole feed. */
    val sessionCount: Int,
)

// ---------------------------------------------------------------------------
// The stories shelf
// ---------------------------------------------------------------------------

/**
 * One story on the shelf below the fold.
 *
 * Quiet on purpose. The continuation surface is the screen's one idea; anything that
 * competes with it is a second idea, and a screen with two ideas has none.
 */
data class StoryShelfItem(
    val id: String,
    val title: String,
    val worldName: String,
    /** "Evening · Day 3" */
    val timeLabel: String,
    val lastPlayedLabel: String,
    val companionName: String,
    val companionAccent: Long,
    val theme: ResolvedTheme,
    val artwork: PackArtwork,
)

// ---------------------------------------------------------------------------
// The snapshot
// ---------------------------------------------------------------------------

data class LobbySnapshot(
    /** "Good evening" - small, warm, and the only greeting. */
    val greeting: String,
    /** "Where do you want to go?" - the headline. */
    val headline: String,
    /** One quiet line under the headline, or empty. */
    val subline: String,
    val continueSurface: ContinueSurface?,
    val worlds: List<WorldFeedItem>,
    val stories: List<StoryShelfItem>,
    val modelStatus: ModelStatusCard,
    /** Shown only when there are no worlds at all. */
    val emptyWorlds: EmptyState?,
) {
    val hasContinue: Boolean get() = continueSurface != null

    /** The screen's single visual idea, named so a test can assert it. */
    val leadIdea: LeadIdea
        get() = when {
            continueSurface != null -> LeadIdea.CONTINUE
            worlds.isNotEmpty() -> LeadIdea.DISCOVER
            else -> LeadIdea.EMPTY
        }
}

/**
 * Which of the three questions Home is currently asking.
 *
 * Enumerated rather than inferred at the call site, because "what does Home do when
 * there is nothing to continue and nothing to discover" is a design decision with three
 * distinct answers, and a boolean pair would let a screen quietly pick a fourth.
 */
enum class LeadIdea { CONTINUE, DISCOVER, EMPTY }

// ---------------------------------------------------------------------------
// The presenter
// ---------------------------------------------------------------------------

object LobbyPresenter {

    /**
     * The headline.
     *
     * A constant rather than a computed string, and deliberately in the user's language
     * rather than the product's: Home is a doorway, and "Where do you want to go?" is
     * the only question that belongs above the fold.
     */
    const val HEADLINE = "Where do you want to go?"

    /** How many stories sit on the shelf. Enough to recognise, few enough to stay quiet. */
    const val SHELF_LIMIT = 4

    /** Worlds shown at most. A feed, not an index. */
    const val FEED_LIMIT = 12

    fun build(
        nowEpochMs: Long,
        instances: List<StoryInstance>,
        packs: List<StoryPack>,
        definitions: Map<String, WorldDefinition>,
        model: ModelStatusLike,
    ): LobbySnapshot {
        val sorted = instances.sortedWith(
            compareByDescending<StoryInstance> { it.sessionMeta.lastPlayedAtEpochMs }
                .thenByDescending { it.updatedAt.totalMinutes },
        )

        val continueSurface = sorted.firstOrNull()?.let { instance ->
            continueSurface(nowEpochMs, instance, packs, definitions)
        }

        return LobbySnapshot(
            greeting = greetingFor(nowEpochMs),
            headline = HEADLINE,
            subline = subline(continueSurface, packs.size),
            continueSurface = continueSurface,
            worlds = feed(nowEpochMs, instances, packs),
            stories = shelf(nowEpochMs, sorted, definitions),
            modelStatus = modelStatus(model),
            emptyWorlds = if (packs.isEmpty()) {
                EmptyState(
                    title = "Your first world is waiting.",
                    body = "A world is a set of people, places and rules that remember " +
                        "what happened in them. Build one, or step into one that ships with Charaly.",
                    actionLabel = "Explore the model library",
                    artSeed = "charaly-empty-lobby",
                )
            } else {
                null
            },
        )
    }

    // ---------------------------------------------------------------- continue

    private fun continueSurface(
        nowEpochMs: Long,
        instance: StoryInstance,
        packs: List<StoryPack>,
        definitions: Map<String, WorldDefinition>,
    ): ContinueSurface {
        val pack = packs.firstOrNull { it.id == instance.storyPackId }
        val definition = definitions[instance.storyPackId.value]
        val now = instance.worldClock.now

        val companionId = instance.focusCharacterId
            ?: instance.worldState.characters.keys.minByOrNull { it.value }
        val companion = definition?.character(companionId)

        val nearby = StoryContextPresenter.playerId(instance)
            ?.let { playerId ->
                EventEngine.currentPlayerLocation(instance)
                    ?.let { instance.worldState.charactersAt(it) }
                    .orEmpty()
                    .map { it.characterId }
                    .filter { it != playerId }
                    .mapNotNull { definition?.character(it)?.name }
                    .distinct()
            }
            .orEmpty()

        return ContinueSurface(
            storyId = instance.id.value,
            // The world's name, not the story's working title: someone returning after a
            // week remembers where they were, not what they called the file.
            worldName = pack?.title ?: instance.packTitle,
            packTitle = instance.packTitle,
            moment = StoryMomentPresenter.sentenceFor(instance),
            companionName = companion?.name.orEmpty(),
            companionAccent = companion?.accentLong()?.takeIf { it != 0L }
                ?: ResolvedTheme.BRAND.primary,
            companionArtwork = companion?.artwork ?: PackArtwork.generated(instance.id.value),
            // The pack's own banner seed, so every playthrough of a world shares one
            // image rather than each hashing into a different one.
            artwork = PackArtwork.generated("banner-${instance.storyPackId.value}"),
            theme = pack?.let { ResolvedTheme.of(it.identity.theme) } ?: ResolvedTheme.BRAND,
            presenceLabel = StoryContextPresenter.presenceLabel(nearby.size),
            timeLabel = listOfNotNull(
                StoryContextPresenter.timeOfDayLabel(now.hour).takeIf { it.isNotBlank() },
                "Day ${now.day}".takeIf { now.day > 1 },
            ).joinToString(" · "),
            lastPlayedLabel = RelativeTime.describe(
                nowEpochMs,
                instance.sessionMeta.lastPlayedAtEpochMs,
            ),
        )
    }

    // -------------------------------------------------------------------- feed

    /**
     * The worlds, ordered and weighted.
     *
     * Ordering is *play history first*: the world you were last in is the world you most
     * likely want again, so it leads. Featured packs from the library are kept, because a
     * pack an author marked as a front door deserves to be one.
     */
    private fun feed(
        nowEpochMs: Long,
        instances: List<StoryInstance>,
        packs: List<StoryPack>,
    ): List<WorldFeedItem> = packs
        .sortedWith(
            compareByDescending<StoryPack> { pack -> instances.any { it.storyPackId == pack.id } }
                .thenByDescending { pack -> pack.identity.isFeatured }
                .thenByDescending { pack -> instances.count { story -> story.storyPackId == pack.id } }
                .thenBy { pack -> pack.title.lowercase() },
        )
        .take(FEED_LIMIT)
        .mapIndexed { index, pack -> feedItem(nowEpochMs, index, pack, instances) }

    private fun feedItem(
        nowEpochMs: Long,
        index: Int,
        pack: StoryPack,
        instances: List<StoryInstance>,
    ): WorldFeedItem {
        // The strict showcase projection: premise, hooks, tone, atmosphere. No roster, no
        // counts, no events. A feed card is a pitch, and the engine is the thing that
        // eventually delivers on it.
        val showcase = PackShowcaseBuilder.build(pack)
        val sessions = instances.filter { it.storyPackId == pack.id }
        return WorldFeedItem(
            id = pack.id.value,
            title = showcase.title,
            premise = showcase.premise,
            tone = showcase.tone,
            hook = showcase.hooks.firstOrNull().orEmpty(),
            genres = showcase.genres.take(2),
            artwork = showcase.artwork,
            theme = showcase.theme,
            // Visual weight, decided here rather than in the composable: the first card
            // is the screen's lead, and a screen whose cards all shout has no lead.
            weight = when (index) {
                0 -> WorldWeight.DOMINANT
                in 1..2 -> WorldWeight.FULL
                else -> WorldWeight.QUIET
            },
            lastPlayedLabel = sessions
                .maxByOrNull { it.sessionMeta.lastPlayedAtEpochMs }
                ?.let { RelativeTime.compact(nowEpochMs, it.sessionMeta.lastPlayedAtEpochMs) }
                ?: "",
            sessionCount = sessions.size,
        )
    }

    // ------------------------------------------------------------------- shelf

    private fun shelf(
        nowEpochMs: Long,
        sorted: List<StoryInstance>,
        definitions: Map<String, WorldDefinition>,
    ): List<StoryShelfItem> = sorted.drop(1).take(SHELF_LIMIT).map { instance ->
        val definition = definitions[instance.storyPackId.value]
        val companionId = instance.focusCharacterId
            ?: instance.worldState.characters.keys.minByOrNull { it.value }
        val companion = definition?.character(companionId)
        val now = instance.worldClock.now
        StoryShelfItem(
            id = instance.id.value,
            title = instance.displayTitle,
            worldName = instance.packTitle,
            timeLabel = listOfNotNull(
                StoryContextPresenter.timeOfDayLabel(now.hour).takeIf { it.isNotBlank() },
                "Day ${now.day}".takeIf { now.day > 1 },
            ).joinToString(" · "),
            lastPlayedLabel = RelativeTime.describe(
                nowEpochMs,
                instance.sessionMeta.lastPlayedAtEpochMs,
            ),
            companionName = companion?.name.orEmpty(),
            companionAccent = companion?.accentLong()?.takeIf { it != 0L }
                ?: ResolvedTheme.BRAND.primary,
            theme = ResolvedTheme.BRAND,
            artwork = PackArtwork.generated("banner-${instance.storyPackId.value}"),
        )
    }

    // ------------------------------------------------------------------ pieces

    private fun modelStatus(model: ModelStatusLike) = ModelStatusCard(
        modelName = model.displayName(),
        stateLabel = model.stateLabel(),
        detailLabel = model.detailLabel(),
        isReady = model.isReady(),
        accent = ResolvedTheme.BRAND.accent,
    )

    /**
     * The line under the headline.
     *
     * One fact, and only when it is worth saying. A subtitle that restates the headline,
     * or that counts worlds, is noise.
     */
    private fun subline(continueSurface: ContinueSurface?, worldCount: Int): String = when {
        continueSurface != null -> "Something is waiting for you."
        worldCount > 0 -> "$worldCount worlds to step into."
        else -> ""
    }
}

// ---------------------------------------------------------------------------
// Story moments
// ---------------------------------------------------------------------------

/**
 * A cinematic system moment.
 *
 * ## What a moment is
 *
 * The engine does a great deal of invisible work: someone walks in, night falls, a
 * promise comes due, a secret becomes known. That work is what makes a world feel alive,
 * and a world that changes without ever acknowledging it reads as broken.
 *
 * So a moment is a short line in the story's own language, placed in the transcript
 * where it happened. Never an event id, never a class name, never a score.
 *
 * ## The budget is the design
 *
 * A naive implementation emits a line per change and produces a ticker the user learns to
 * ignore. [StoryFeed] already caps how many qualify; this type adds the second half -
 * the *shape*. Moments are quiet, full-bleed and centred, with no avatar and no bubble,
 * so a reader's eye crosses them rather than stopping on them.
 */
data class StoryMoment(
    val id: String,
    val text: String,
    /** "Just now" / "2 hours ago", in story time. */
    val relativeLabel: String,
    val kind: FeedKind,
)

object StoryMomentPresenter {

    /** How many moments ever appear in a transcript. */
    const val MAX_MOMENTS = 3

    /**
     * "Something has changed since you left."
     *
     * ## The precedence, and why
     *
     * 1. **A world change the engine recorded.** The strongest possible answer, because
     *    the world itself produced it.
     * 2. **The open thread's next beat.** What the story is currently about.
     * 3. **An honest nothing.** "The world is quiet. Nothing has changed since you left."
     *
     * The third branch is the important one. A lobby that always claims something
     * happened is a lobby that has to lie on every quiet install, and the player learns
     * to stop believing it within a week.
     */
    fun sentenceFor(instance: StoryInstance): String {
        StoryFeed.build(instance).firstOrNull()?.let { return it.text }

        val beat = instance.storyThreads.values
            .filter { it.status.isOpen }
            .maxByOrNull { it.priority }
            ?.let { thread -> thread.nextBeat.ifBlank { thread.title } }
            ?.takeIf { it.isNotBlank() }
        if (beat != null) return beat

        return "The world is quiet. Nothing has changed since you left."
    }

    /**
     * The moments to place in a transcript.
     *
     * Capped hard, and never invented: this is a projection of what the world actually
     * did. An empty list is the correct answer for a story that has just started.
     */
    fun moments(
        instance: StoryInstance,
        limit: Int = MAX_MOMENTS,
    ): List<StoryMoment> = StoryFeed.build(instance).take(limit).map { line ->
        StoryMoment(
            id = line.id,
            text = line.text,
            relativeLabel = line.relativeLabel,
            kind = line.kind,
        )
    }

    /**
     * What a moment looks like, in words.
     *
     * Used by the moment composable's content description, so a screen reader announces
     * "Someone entered the room" rather than an unlabelled divider.
     */
    fun accessibleLabel(moment: StoryMoment): String =
        "Story moment: ${moment.text}"
}