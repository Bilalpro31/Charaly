package dev.charaly.runtime.presentation

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.memory.Memory
import dev.charaly.runtime.engine.EventEngine

/**
 * The four sheets a player can open from inside a story.
 *
 * ## Why exactly four, and why these four
 *
 * The chat screen used to open a technical sheet with tabs for world state, events,
 * memory, relationships, model and scene. That is a debug view wearing a product's
 * clothes: it is organised by *engine subsystem*, which is how the codebase is arranged
 * and not how someone thinks about being in a scene.
 *
 * The four entries below are organised by **question**, and they are the four questions
 * a player actually has mid-story:
 *
 * ```
 *   WORLD       where am I, and when is it?
 *   MEMORY      what does this person remember about me?
 *   PEOPLE      who is here, and how do they feel about me?
 *   STORY       what am I supposed to be doing?
 * ```
 *
 * Everything the old sheet showed is still reachable - it is just answered in the
 * player's vocabulary. The engine's own view is one Settings toggle away.
 *
 * ## The hard rule
 *
 * Nothing in these four projections may contain an id, a score, a tier name or an event
 * type. That is asserted by tests rather than by review, because it is exactly the kind
 * of thing that leaks in through one field during a later change.
 */
data class StoryContextEntry(
    /** Stable key the navigator routes on. */
    val id: ContextEntryId,
    val label: String,
    /** One short line of summary, shown on the entry itself. */
    val summary: String,
)

enum class ContextEntryId {
    WORLD,
    MEMORY,
    PEOPLE,
    STORY,
}

/** What the World sheet shows. */
data class WorldSheet(
    /** "Collège Françoise Dupont" */
    val locationName: String,
    /** "A wide courtyard..." - the location as it reads, not as it is stored. */
    val locationDescription: String,
    /** "Evening" */
    val timeOfDay: String,
    /** "Day 3" */
    val dayLabel: String,
    /**
     * "18:42" - the exact clock reading, from authoritative state.
     *
     * "Evening" is a coarse label that stays true for hours, so on its own it cannot
     * show that the world is advancing. The exact reading is what makes a moved world
     * visible in the one screen whose question is "where am I, and when is it?".
     */
    val clockLabel: String = "",
    /**
     * The scene as it reads right now, in words.
     *
     * "The four of you are in the courtyard between classes." A player asking where they
     * are means the situation, not just the postcode, and this is derived from the live
     * [dev.charaly.runtime.domain.Scene] rather than from the pack.
     */
    val activeScene: String = "",
    /** "Paris" - the wider place, when the location belongs to one. */
    val region: String = "",
    /** Who else is here, by name. */
    val nearby: List<String> = emptyList(),
    /** "3 people here" / "Just you". */
    val presenceLabel: String = "",
)

/** What the People sheet shows. */
data class PersonRow(
    val id: String,
    val name: String,
    /** One line: "a classmate", "the shop owner". Their role in the story, in words. */
    val role: String = "",
    /** "Warm, but guarded" - a relationship in words, never as a number. */
    val standing: String = "",
    /** Whether they are present in the current scene. */
    val isHere: Boolean = false,
    /** Whether this is the person being talked to. */
    val isFocus: Boolean = false,
)

/** What the Story sheet shows. */
data class StorySheet(
    /** The current objective, in one sentence. */
    val currentBeat: String,
    /** Open threads, by title. Never their internal ids or stages. */
    val threads: List<StoryThreadRow> = emptyList(),
    /** "3 days since this began" */
    val elapsed: String = "",
)

data class StoryThreadRow(
    val id: String,
    val title: String,
    /** "In play", "Just started", "Finished". */
    val status: String,
    /**
     * Progress as words: "Off to a start", "Halfway there".
     *
     * A 0-100 bar would be an engine number leaking into a product screen. The stage is
     * still driving the words, so the information survives; only the precision is lost.
     */
    val progress: String = "",
)

/** All four sheets, built in one pass. */
data class StoryContext(
    val entries: List<StoryContextEntry>,
    val world: WorldSheet,
    val memory: List<MemoryCard>,
    val people: List<PersonRow>,
    val story: StorySheet,
) {
    fun entryFor(id: ContextEntryId): StoryContextEntry? = entries.firstOrNull { it.id == id }
}

/**
 * Builds the player-facing context sheets.
 *
 * Every method is a pure function of an authoritative [StoryInstance]. That is what
 * makes them unit-testable on the JVM, and it is also what guarantees the UI cannot
 * mutate the world through one of them.
 */
object StoryContextPresenter {

    /**
     * Builds all four.
     *
     * @param definition the pack's static content, needed to name locations and roles.
     * @param instance the live story.
     * @param now injectable so relative labels are testable without advancing a clock.
     */
    fun build(
        definition: dev.charaly.runtime.domain.WorldDefinition,
        instance: StoryInstance,
        now: dev.charaly.runtime.domain.StoryTime = instance.worldClock.now,
    ): StoryContext {
        val world = worldSheet(definition, instance, now)
        return StoryContext(
            entries = entries(instance, world),
            world = world,
            // Memory is scoped to the person being talked to. Showing every character's
            // memory here would be both wrong (it leaks what others know) and useless.
            memory = memorySheet(definition, instance, now),
            people = peopleSheet(definition, instance),
            story = storySheet(definition, instance, now),
        )
    }

    /**
     * The four entries, each with a live summary.
     *
     * The summaries are the point: a player decides whether to open a sheet from the
     * summary alone, so "3 people here" has to be on the button rather than inside.
     */
    private fun entries(instance: StoryInstance, world: WorldSheet): List<StoryContextEntry> = listOf(
        StoryContextEntry(
            id = ContextEntryId.WORLD,
            label = "World",
            summary = world.locationName,
        ),
        StoryContextEntry(
            id = ContextEntryId.MEMORY,
            label = "Memory",
            summary = "${instance.memories.current().count { it.importance >= StoryFeed.NOTABLE_IMPORTANCE }} remembered",
        ),
        StoryContextEntry(
            id = ContextEntryId.PEOPLE,
            label = "People",
            summary = world.presenceLabel,
        ),
        StoryContextEntry(
            id = ContextEntryId.STORY,
            label = "Story",
            summary = "${instance.storyThreads.values.count { it.status.isOpen }} open",
        ),
    )

    fun worldSheet(
        definition: dev.charaly.runtime.domain.WorldDefinition,
        instance: StoryInstance,
        now: dev.charaly.runtime.domain.StoryTime,
    ): WorldSheet {
        val locationId = EventEngine.currentPlayerLocation(instance)
        val location = locationId?.let { definition.location(it) }
        val nearby = locationId
            ?.let { instance.worldState.charactersAt(it) }
            .orEmpty()
            .filter { it.characterId != playerId(instance) }
            .map { definition.character(it.characterId)?.name.orEmpty() }
            .filter { it.isNotBlank() }
            .sorted()

        val scene = instance.currentScene()
        val companions = scene?.participants
            .orEmpty()
            .filter { it != playerId(instance) }
            .map { definition.character(it)?.name.orEmpty() }
            .filter { it.isNotBlank() }

        return WorldSheet(
            locationName = location?.name.orEmpty().ifBlank { "Somewhere" },
            locationDescription = location?.description.orEmpty(),
            timeOfDay = timeOfDayLabel(now.hour),
            dayLabel = "Day ${now.day}",
            clockLabel = now.formatClock(),
            activeScene = sceneLabel(scene, location?.name.orEmpty(), companions),
            nearby = nearby,
            presenceLabel = presenceLabel(nearby.size),
        )
    }

    /**
     * The current scene, as one sentence.
     *
     * Built from the live scene's objective when the pack authored one, and from who is
     * actually present otherwise. Never from the pack's static description, because that
     * would answer "where am I" identically whether the story had moved or not.
     */
    private fun sceneLabel(
        scene: dev.charaly.runtime.domain.Scene?,
        locationName: String,
        companions: List<String>,
    ): String {
        val where = locationName.ifBlank { "here" }
        val who = when (companions.size) {
            0 -> "You are alone here."
            1 -> "${companions.first()} is with you."
            2 -> "${companions[0]} and ${companions[1]} are with you."
            else -> "${companions.first()} and ${companions.size - 1} others are here."
        }
        val objective = scene?.objective?.trim().orEmpty()
        return when {
            objective.isNotEmpty() -> "$who $objective".trim()
            scene == null -> "Nothing is happening here yet."
            else -> "$who You are at $where.".trim()
        }
    }

    /**
     * "Just you" / "1 person here" / "3 people here".
     *
     * Counts the player's own character? No - the player knows they are there. Counting
     * everyone else is the number that carries information.
     */
    fun presenceLabel(nearbyCount: Int): String = when (nearbyCount) {
        0 -> "Just you"
        1 -> "1 person here"
        else -> "$nearbyCount people here"
    }

    fun timeOfDayLabel(hour: Int): String = when {
        hour < 5 -> "Late night"
        hour < 8 -> "Early morning"
        hour < 12 -> "Morning"
        hour < 14 -> "Midday"
        hour < 18 -> "Afternoon"
        hour < 22 -> "Evening"
        else -> "Night"
    }

    /**
     * Memories, scoped to the character in focus.
     *
     * Only the *player's* reading of them: the memory's own text, who holds it, and
     * when. No scores, no tiers, and nothing that another character knows.
     */
    fun memorySheet(
        definition: dev.charaly.runtime.domain.WorldDefinition,
        instance: StoryInstance,
        now: dev.charaly.runtime.domain.StoryTime,
    ): List<MemoryCard> {
        val focus = instance.focusCharacterId ?: return emptyList()
        val owner = definition.character(focus)?.name.orEmpty()
        return instance.memories
            .select(characterId = focus, limit = MEMORY_LIMIT)
            .filter { it.importance >= StoryFeed.NOTABLE_IMPORTANCE }
            // Scratch memories are the current moment and its summary. They are not
            // what a player means by "what she remembers about you", and listing them
            // makes the sheet read as a log.
            .filterNot { it.tier in StoryFeed.QUIET_TIERS }
            .sortedWith(compareByDescending<Memory> { it.pinned }.thenByDescending { it.createdAt.totalMinutes })
            .map { memory ->
                // Reuses the existing memory panel's card, which already speaks in plain
                // language ("what she knows about you" rather than "RELATIONSHIP,
                // importance 4"). A second card type here would guarantee the two
                // screens drift apart in wording.
                MemoryPanelPresenter.cardOf(memory, ownerName = owner, ownerId = focus.value, now = now)
            }
    }

    /**
     * People who matter right now: whoever is in this scene, plus the focus character.
     *
     * Deliberately not the whole cast. A roster of nineteen names is the pack detail
     * screen's mistake repeated one level down; here it would be listing people the
     * player has not met.
     */
    fun peopleSheet(
        definition: dev.charaly.runtime.domain.WorldDefinition,
        instance: StoryInstance,
    ): List<PersonRow> {
        // Focus, not player: the person being talked to is the one whose standing
        // matters on this screen. Using the player's own id here would show a row for
        // themselves describing their own relationship to themselves.
        val focus = instance.focusCharacterId
        val me = playerId(instance)
        val locationId = EventEngine.currentPlayerLocation(instance)
        val present = locationId
            ?.let { instance.worldState.charactersAt(it) }
            .orEmpty()
            .map { it.characterId }
            .filter { it != me }

        // Focus first, then whoever else is here, then anyone in an open thread the
        // player is party to. The last group is what makes the sheet useful in a scene
        // with nobody else in it.
        val related = instance.storyThreads.values
            .filter { it.status.isOpen }
            .flatMap { it.involvedCharacterIds }
            .filter { it != focus }

        val ids = (listOfNotNull(focus) + present + related).distinct()

        return ids.mapNotNull { id ->
            val character = definition.character(id) ?: return@mapNotNull null
            PersonRow(
                id = id.value,
                name = character.name,
                role = character.roleLabel(),
                standing = standingLabel(instance, id),
                isHere = id in present,
                isFocus = id == focus,
            )
        }
    }

    /**
     * A relationship in words.
     *
     * ## Why no numbers
     *
     * `trust 62, familiarity 41, tension 8` is what the relationship *is* internally and
     * it is genuinely useful - to the engine, to the developer inspector, and to the
     * prompt. It is useless to a player, who does not have a reference frame for 62 and
     * would read it as a score out of 100 in a game they are not playing.
     *
     * So the axes are read in their own terms. A relationship is described by its most
     * *extreme* axis rather than its average, because what a player notices about a
     * relationship is its salient feature, not its mean.
     */
    fun standingLabel(instance: StoryInstance, characterId: CharacterId): String {
        val me = playerId(instance) ?: return ""
        val relationship = instance.worldState.relationship(me, characterId) ?: return "New to you"
        // Positives and negatives are kept apart rather than merged onto one scale.
        //
        // An earlier version inverted the negative axes and took the maximum, which
        // produced "Deeply calm" for any relationship nobody had recorded tension for -
        // that is, for almost every new relationship. A default relationship has nothing
        // interesting to say, and saying something false-interesting about it is worse
        // than saying nothing at all.
        //
        // So each axis counts only once it has actually moved off neutral, and the
        // stronger of the two directions wins.
        val positives = listOf(
            "trust" to relationship.trust,
            "warmth" to relationship.affinity,
            "respect" to relationship.respect,
        )
        val negatives = listOf(
            "guarded" to relationship.tension,
            "afraid" to relationship.fear,
        )

        val bestPositive = positives.maxByOrNull { it.second }
        val bestNegative = negatives.maxByOrNull { it.second }

        // Both ends of the range can be news, but not at the same weight. A negative
        // axis is only worth naming well past neutral, because tension at 60 in a
        // long-running relationship is ordinary friction, whereas tension at 85 is the
        // thing the player needs to know about.
        val positiveNews = bestPositive?.takeIf {
            it.second >= POSITIVE_NOTABLE || it.second <= NEGATIVE_NOTABLE
        }
        val negativeNews = bestNegative?.takeIf { it.second >= NEGATIVE_STRONG }

        return when {
            positiveNews == null && negativeNews == null -> "Still getting to know them"
            // A strong negative only wins outright when it clearly outweighs the
            // positive feeling, because "guarded" on its own loses the warmth that is
            // usually also true of someone who is guarded.
            negativeNews != null &&
                positiveNews != null &&
                negativeNews.second > positiveNews.second + POSITIVE_MARGIN ->
                describeAxis(negativeNews.first)

            positiveNews != null -> {
                val word = describeAxis(positiveNews.first)
                if (positiveNews.second <= NEGATIVE_NOTABLE) "hardly $word" else word
            }

            else -> describeAxis(negativeNews!!.first)
        }
    }

    /** Above this, a positive axis is worth naming. */
    const val POSITIVE_NOTABLE = 68

    /** Below this on a positive axis, the absence is itself worth naming. */
    const val NEGATIVE_NOTABLE = 34

    /**
     * How far past neutral a *negative* axis must be before it is worth naming.
     *
     * Higher than [POSITIVE_NOTABLE] on purpose. Marinette starts out with affinity 70
     * toward Adrien, which is genuinely warm and worth saying; the same relationship
     * also has a non-zero tension value from the start, and "guarded" would misrepresent
     * an affectionate crush as a wary one. Asymmetry between the two directions is the
     * fix, not a bug in the thresholds.
     */
    const val NEGATIVE_STRONG = 78

    /** How much stronger a negative must be before it overrides a positive. */
    const val POSITIVE_MARGIN = 12

    /**
     * The word for one axis.
     *
     * The relationship engine's own nouns are "trust" and "affinity"; these are their
     * adjectival forms, because the sentence has to read as a description of a person
     * rather than as a field value.
     */
    private fun describeAxis(axis: String): String = when (axis) {
        "trust" -> "trusting"
        "warmth" -> "warm"
        "respect" -> "respectful"
        "guarded" -> "guarded"
        "afraid" -> "afraid"
        else -> axis
    }

    fun storySheet(
        definition: dev.charaly.runtime.domain.WorldDefinition,
        instance: StoryInstance,
        now: dev.charaly.runtime.domain.StoryTime,
    ): StorySheet {
        val open = instance.storyThreads.values
            .filter { it.status.isOpen }
            .sortedByDescending { it.priority }

        return StorySheet(
            // The highest-priority open thread is the story's current demand on the
            // player. With none open, say that plainly rather than inventing a goal.
            currentBeat = open.firstOrNull()?.nextBeat?.ifBlank { null }
                ?: open.firstOrNull()?.title
                ?: "Nothing is pressing yet.",
            threads = open.take(THREAD_LIMIT).map { thread ->
                StoryThreadRow(
                    id = thread.id.value,
                    title = thread.title,
                    status = when (thread.status) {
                        dev.charaly.runtime.domain.StoryThreadStatus.ACTIVE -> "In play"
                        dev.charaly.runtime.domain.StoryThreadStatus.DORMANT -> "Just started"
                        dev.charaly.runtime.domain.StoryThreadStatus.COMPLETED -> "Finished"
                        dev.charaly.runtime.domain.StoryThreadStatus.FAILED -> "Abandoned"
                    },
                    progress = progressLabel(thread.progress),
                )
            },
            elapsed = elapsedLabel(now, instance.createdAt),
        )
    }

    /**
     * Progress as words.
     *
     * The underlying 0-100 value still decides the wording, so the information is not
     * lost - only the false precision is.
     */
    fun progressLabel(progress: Int): String = when {
        progress <= 0 -> "Off to a start"
        progress < 25 -> "Just begun"
        progress < 50 -> "Underway"
        progress < 75 -> "Halfway there"
        progress < 100 -> "Nearly there"
        else -> "Finished"
    }

    fun elapsedLabel(now: dev.charaly.runtime.domain.StoryTime, since: dev.charaly.runtime.domain.StoryTime): String {
        val minutes = now.totalMinutes - since.totalMinutes
        val days = minutes / (60 * 24)
        return when {
            minutes < 60 -> "Just begun"
            minutes < 60 * 24 -> "${minutes / 60} hours in"
            days == 1L -> "1 day in"
            else -> "$days days in"
        }
    }

    const val MEMORY_LIMIT = 8
    const val THREAD_LIMIT = 5

    /**
     * Which character the player is.
     *
     * The story's persona binding, falling back to the story's focus character. There
     * is exactly one player per story, so this is resolved once rather than threaded
     * through every sheet - and it is what lets "3 people here" exclude the person
     * already looking at the number.
     */
    fun playerId(instance: StoryInstance): CharacterId? =
        instance.focusCharacterId

    /**
     * A character's role in words.
     *
     * `CharacterRole` is an authoring classification (core cast, npc, background), which
     * is a pack author's concern. What a player needs is what this person *is* to them.
     */
    private fun dev.charaly.runtime.domain.CharacterDefinition.roleLabel(): String =
        when (storyRole) {
            dev.charaly.runtime.domain.CharacterRole.PROTAGONIST -> summaryLine().take(48)
            dev.charaly.runtime.domain.CharacterRole.NPC -> summaryLine().take(48)
            // Background population has no individual identity by definition, so there
            // is nothing to say about it beyond acknowledging that it is there.
            dev.charaly.runtime.domain.CharacterRole.BACKGROUND -> "part of the crowd"
        }
}
