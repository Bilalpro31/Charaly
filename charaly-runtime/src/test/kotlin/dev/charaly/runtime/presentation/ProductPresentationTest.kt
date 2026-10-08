package dev.charaly.runtime.presentation

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.MemoryId
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.StoryThreadStatus
import dev.charaly.runtime.domain.WorldDefinition
import dev.charaly.runtime.domain.memory.Memory
import dev.charaly.runtime.domain.memory.MemoryTier
import dev.charaly.runtime.domain.memory.MemoryVisibility
import dev.charaly.runtime.engine.StoryInstanceFactory
import dev.charaly.runtime.pack.DemoStoryPacks
import dev.charaly.runtime.pack.MiraculousPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The product's presentation rules, asserted rather than reviewed.
 *
 * ## Why this test file is long and fussy
 *
 * The brief's central claim about the product is that a normal user never sees engine
 * internals: no ids, no scores, no event types, no context budgets. That is a property
 * about *strings*, and strings are exactly the thing no compiler checks and no reviewer
 * reliably catches.
 *
 * So each rule below is enforced by looking at what a screen would actually draw, not
 * at the type signature. `PersonRow` has no id-scoring field today; if someone adds one
 * next year, the `standing is words` test will fail the same day.
 */
class ProductPresentationTest {

    private val pack = MiraculousPack.pack
    private val definition = WorldDefinition(pack.characters, pack.locations)
    private val marinette = CharacterId(MiraculousPack.MARINETTE)
    private val adrien = CharacterId(MiraculousPack.ADRIEN)

    private fun story(id: String = "story-present") =
        StoryInstanceFactory.create(pack, StoryInstanceId(id))

    // ------------------------------------------------------------------
    // The showcase: a pack is a premise, not a database dump
    // ------------------------------------------------------------------

    /**
     * A pack detail screen is types, so it structurally cannot leak a roster.
     *
     * This is the real assertion. [PackShowcase] has no field for characters, locations,
     * events or lore, which means no future change to the screen can render them without
     * first adding a field here.
     */
    @Test
    fun `a showcase has nowhere to put engine data`() {
        val showcase = PackShowcaseBuilder.build(pack)
        val fieldNames = showcase::class.java.declaredFields.map { it.name }.toSet()

        for (forbidden in listOf(
            "characters", "locations", "events", "lore", "factions", "threads",
            "personas", "scenarios", "canonLines", "eventTypes",
        )) {
            assertFalse(
                "PackShowcase exposes '$forbidden'; a pack detail screen must not have a slot for it",
                fieldNames.contains(forbidden),
            )
        }
    }

    @Test
    fun `the showcase reads as a story invitation`() {
        val showcase = PackShowcaseBuilder.build(pack)
        assertEquals("Miraculous: Shadows of Paris", showcase.title)
        assertTrue("the premise should be a sentence", showcase.premise.length > 40)
        assertTrue("there should be a short invitation", showcase.invitation.isNotBlank())
        assertTrue("there should be atmosphere", showcase.atmosphere.isNotBlank())
        // Three hooks at most: a mood, not a backlog.
        assertTrue("hooks should be 1..3", showcase.hooks.size in 1..3)
    }

    // ------------------------------------------------------------------
    // The four contextual entries
    // ------------------------------------------------------------------

    @Test
    fun `a story offers exactly four contextual entries`() {
        val instance = story()
        val context = StoryContextPresenter.build(definition, instance)
        assertEquals(
            listOf(
                ContextEntryId.WORLD,
                ContextEntryId.MEMORY,
                ContextEntryId.PEOPLE,
                ContextEntryId.STORY,
            ),
            context.entries.map { it.id },
        )
    }

    /**
     * Presence reads as a sentence.
     *
     * "3 people here" is the form the brief asks for, and it has to exclude the player -
     * a player is not a fact about the room.
     */
    @Test
    fun `presence is phrased as a sentence and excludes the player`() {
        assertEquals("Sadece siz", StoryContextPresenter.presenceLabel(0))
        assertEquals("1 kişi burada", StoryContextPresenter.presenceLabel(1))
        assertEquals("3 kişi burada", StoryContextPresenter.presenceLabel(3))
        assertEquals("11 kişi burada", StoryContextPresenter.presenceLabel(11))
    }

    @Test
    fun `time of day is a phrase, not a number`() {
        assertEquals("Gece yarısı", StoryContextPresenter.timeOfDayLabel(2))
        assertEquals("Sabah", StoryContextPresenter.timeOfDayLabel(9))
        assertEquals("Öğle", StoryContextPresenter.timeOfDayLabel(12))
        assertEquals("Öğleden sonra", StoryContextPresenter.timeOfDayLabel(15))
        assertEquals("Akşam", StoryContextPresenter.timeOfDayLabel(19))
        assertEquals("Gece", StoryContextPresenter.timeOfDayLabel(23))
    }

    /**
     * A relationship is described, not scored.
     *
     * The axes are genuinely useful to the engine and to the prompt; showing
     * "trust 62, familiarity 41" to a player gives them a number with no reference
     * frame. So the label reads the relationship's salient axis and names it in words.
     */
    /**
     * A relationship is described, never scored.
     *
     * The decisive part is the last assertion: Marinette and Adrien start with a real
     * relationship whose axes are all at neutral. An earlier version of this label took
     * the maximum of an inverted negative axis, which made every untouched relationship
     * read "Deeply calm" - a false and oddly flattering thing to tell someone about a
     * person they have just met.
     */
    @Test
    fun `standing is words and never numbers`() {
        val instance = story("story-standing")
        val label = StoryContextPresenter.standingLabel(instance, adrien)
        assertFalse("standing label contains a digit: $label", label.any { it.isDigit() })
        assertTrue("standing label is blank", label.isNotBlank())

        // Marinette's starting relationship with Adrien has trust 70, familiarity 75
        // and affinity 74 - the pack author wrote an affectionate crush. The label may
        // therefore say "warm", but it must not say "guarded": tension on that
        // relationship is a non-zero default, not something the story established, and
        // calling it guarded would misdescribe someone the pack wrote as fond.
        assertEquals("warm", label)
        assertFalse("a warm relationship was labelled guarded: $label", label.contains("guarded"))
        assertFalse("a warm relationship was labelled afraid: $label", label.contains("afraid"))
    }

    /**
     * A relationship the pack wrote says so; one with nothing in it does not.
     *
     * The regression this guards is the first half. An earlier implementation inverted
     * the negative axes and took the maximum, which produced "Deeply calm" for every
     * untouched relationship - a flattering falsehood for anyone the player had just met.
     *
     * Note the second half is checked by *building* a neutral relationship, not by
     * picking a character. Every character the Miraculous pack ships has authored
     * relationship values, because that is what a living cast means, so no shipped
     * character can serve as a "nothing to say" case.
     */
    @Test
    fun `a neutral relationship says nothing interesting`() {
        val base = story("story-standing-neutral")
        val someone = CharacterId("stranger-with-no-relationship")

        // With no relationship record at all, the honest answer is "new".
        assertEquals("New to you", StoryContextPresenter.standingLabel(base, someone))

        // With a record whose every axis sits at neutral, nothing is worth naming. This
        // is the case that used to read "Deeply calm".
        val withNeutral = base.copy(
            worldState = base.worldState.copy(
                relationships = base.worldState.relationships + mapOf(
                    dev.charaly.runtime.domain.RelationshipKey(
                        CharacterId(MiraculousPack.MARINETTE),
                        CharacterId("someone-neutral"),
                    ) to dev.charaly.runtime.domain.Relationship(
                        sourceId = CharacterId(MiraculousPack.MARINETTE),
                        targetId = CharacterId("someone-neutral"),
                    ),
                ),
            ),
        )
        val label = StoryContextPresenter.standingLabel(withNeutral, CharacterId("someone-neutral"))
        assertEquals("Still getting to know them", label)
        assertFalse("a neutral relationship was overstated: $label", label.contains("Deeply"))
    }

    @Test
    fun `an unknown character reads as new rather than as nothing`() {
        val instance = story("story-standing-new")
        val stranger = CharacterId("nobody-at-all")
        // No relationship exists, so the honest answer is "new", not an empty string -
        // a blank row in a People sheet looks like a bug.
        val label = StoryContextPresenter.standingLabel(instance, stranger)
        assertEquals("New to you", label)
    }

    @Test
    fun `progress is words and never a percentage`() {
        assertEquals("Başlangıçta", StoryContextPresenter.progressLabel(0))
        assertEquals("Yeni başladı", StoryContextPresenter.progressLabel(10))
        assertEquals("Devam ediyor", StoryContextPresenter.progressLabel(40))
        assertEquals("Yarı yolda", StoryContextPresenter.progressLabel(60))
        assertEquals("Bitmek üzere", StoryContextPresenter.progressLabel(90))
        assertEquals("Bitti", StoryContextPresenter.progressLabel(100))
        // The decisive property: no output contains a digit.
        for (progress in 0..100) {
            assertFalse(
                "progressLabel($progress) contains a digit",
                StoryContextPresenter.progressLabel(progress).any { it.isDigit() },
            )
        }
    }

    /**
     * Thread status is a phrase, never an enum name.
     *
     * `StoryThreadStatus.ACTIVE` leaking into a screen as "ACTIVE" is exactly the kind
     * of leak this suite exists to prevent, and it is a one-line change away.
     */
    @Test
    fun `thread status is never an enum name`() {
        val instance = story("story-thread-status")
        val sheet = StoryContextPresenter.storySheet(definition, instance, instance.worldClock.now)
        for (row in sheet.threads) {
            for (name in StoryThreadStatus.entries.map { it.name }) {
                assertFalse(
                    "thread row '${row.status}' exposes the enum name $name",
                    row.status.contains(name),
                )
            }
            assertFalse("thread status contains a digit", row.status.any { it.isDigit() })
        }
    }

    @Test
    fun `elapsed time reads as a phrase`() {
        val now = dev.charaly.runtime.domain.StoryTime.of(day = 3, hour = 14, minute = 0)
        assertEquals("Yeni başladı", StoryContextPresenter.elapsedLabel(now, now))
        assertEquals(
            "5 saat oldu",
            StoryContextPresenter.elapsedLabel(now, dev.charaly.runtime.domain.StoryTime.of(day = 3, hour = 9, minute = 0)),
        )
        assertEquals(
            "1 gün oldu",
            StoryContextPresenter.elapsedLabel(now, dev.charaly.runtime.domain.StoryTime.of(day = 2, hour = 14, minute = 0)),
        )
        assertEquals(
            "2 gün oldu",
            StoryContextPresenter.elapsedLabel(now, dev.charaly.runtime.domain.StoryTime.of(day = 1, hour = 14, minute = 0)),
        )
    }

    /**
     * The memory sheet shows the person in focus, not everyone.
     *
     * Showing every character's memory would leak what others know and be useless at the
     * same time.
     */
    @Test
    fun `the memory sheet is scoped to one character`() {
        val base = story("story-memory-scope")
        val focused = base.copy(focusCharacterId = marinette)
        val cards = StoryContextPresenter.memorySheet(definition, focused, focused.worldClock.now)

        // Only Marinette's memories can appear, because only her view was queried.
        assertTrue(
            "the memory sheet showed another character's memory: " +
                cards.map { it.ownerName },
            cards.all { it.ownerName == definition.character(marinette)?.name },
        )
    }

    /**
     * Low-importance and scratch memories never reach a memory card.
     *
     * The gate is duplicated from `StoryFeed.NOTABLE_IMPORTANCE` on purpose: both are
     * reads of the same rule, and if they ever disagree the user sees an inconsistent
     * pair of screens.
     */
    @Test
    fun `small talk and scratch memories are excluded`() {
        val base = story("story-memory-gate")
        val focus = base.copy(focusCharacterId = marinette)
        val polluted = focus.copy(
            memories = focus.memories.addAll(
                listOf(
                    Memory(
                        id = MemoryId("greeting"),
                        characterId = marinette,
                        content = "hi",
                        importance = 1,
                    ),
                    Memory(
                        id = MemoryId("scene-scratch"),
                        characterId = marinette,
                        content = "You are in the courtyard.",
                        importance = 4,
                        tier = MemoryTier.SCENE,
                    ),
                    Memory(
                        id = MemoryId("real"),
                        characterId = marinette,
                        content = "You promised to be at the school festival.",
                        importance = 4,
                    ),
                ),
            ),
        )

        val cards = StoryContextPresenter.memorySheet(definition, polluted, polluted.worldClock.now)
        val texts = cards.map { it.text }
        assertFalse("a greeting reached the memory sheet", texts.any { it == "hi" })
        assertFalse("a scene-tier memory reached the memory sheet", texts.contains("You are in the courtyard."))
        assertTrue("the real memory was dropped", texts.any { it.contains("school festival") })
    }

    /**
     * The contextual projections carry no engine vocabulary in their text.
     *
     * Applies the same forbidden-word sweep the showcase test uses, to every string a
     * sheet would render.
     */
    @Test
    fun `no contextual sheet exposes engine vocabulary`() {
        val base = story("story-vocab")
        val instance = base.copy(focusCharacterId = marinette)
        val context = StoryContextPresenter.build(definition, instance)

        val rendered = buildString {
            context.entries.forEach { append(it.label).append(' ').append(it.summary).append(' ') }
            append(context.world.locationName).append(' ')
            append(context.world.locationDescription).append(' ')
            append(context.world.timeOfDay).append(' ')
            append(context.world.presenceLabel).append(' ')
            context.memory.forEach { append(it.text).append(' ').append(it.timeLabel).append(' ') }
            context.people.forEach { append(it.name).append(' ').append(it.standing).append(' ') }
            append(context.story.currentBeat).append(' ')
            context.story.threads.forEach { append(it.title).append(' ').append(it.status).append(' ') }
        }

        for (forbidden in listOf(
            "CharacterId", "LocationId", "MemoryId", "ThreadId", "FactId",
            "EventId", "charaly:action", "MemoryTier", "EPISODIC", "SEMANTIC",
            "PROTAGONIST", "NPC", "NPCs", "context", "tokens", "budget",
        )) {
            assertFalse(
                "player-facing text contains '$forbidden'",
                rendered.contains(forbidden),
            )
        }
    }

    // ------------------------------------------------------------------
    // The story feed
    // ------------------------------------------------------------------

    /**
     * A notable memory produces one line, and a greeting does not.
     *
     * The feed is the mechanism by which invisible engine work becomes visible, and its
     * cost is attention. Every line has to earn it.
     */
    @Test
    fun `only notable memories become feed lines`() {
        val base = story("story-feed-gate")
        val instance = base.copy(focusCharacterId = marinette)
        val polluted = instance.copy(
            memories = instance.memories.addAll(
                listOf(
                    Memory(id = MemoryId("small"), characterId = marinette, content = "hi", importance = 1),
                    Memory(id = MemoryId("big"), characterId = marinette, content = "The akuma came from the school.", importance = 5),
                ),
            ),
        )

        val lines = StoryFeed.build(polluted)
        assertFalse("a greeting became a feed line", lines.any { it.text == "hi" })
        assertTrue("the notable memory produced no line", lines.any { it.text.contains("akuma") })
    }

    @Test
    fun `the feed is capped`() {
        val base = story("story-feed-cap")
        val instance = base.copy(focusCharacterId = marinette)
        // More notable memories than the cap allows.
        val many = (1..12).fold(instance) { acc, index ->
            acc.copy(
                memories = acc.memories.add(
                    Memory(
                        id = MemoryId("m$index"),
                        characterId = marinette,
                        content = "Distinct memory number $index",
                        importance = 5,
                        // Distinct subjects, so deduplication does not collapse them.
                        relatedCharacterIds = listOf(CharacterId("who-$index")),
                    ),
                ),
            )
        }
        assertTrue(StoryFeed.build(many).size <= StoryFeed.MAX_VISIBLE)
    }

    /**
     * A line never exposes an id or a score.
     */
    @Test
    fun `feed lines are story language`() {
        val base = story("story-feed-lang")
        val instance = base.copy(focusCharacterId = marinette)
        val withMemory = instance.copy(
            memories = instance.memories.add(
                Memory(id = MemoryId("secret-1"), characterId = marinette, content = "Gabriel built the akuma.", importance = 5),
            ),
        )

        val lines = StoryFeed.build(withMemory)
        assertTrue("the feed produced nothing to check", lines.isNotEmpty())
        for (line in lines) {
            for (forbidden in listOf("memory-", "evt-", "MemoryId", "EPISODIC", "importance")) {
                assertFalse(
                    "feed line '${line.text}' exposes '$forbidden'",
                    line.text.contains(forbidden),
                )
            }
            assertTrue("every line has text", line.text.isNotBlank())
        }
    }

    /**
     * The debug trace is present on every line, so developer mode has something to show.
     *
     * A debug view that renders nothing once enabled is worse than one that was never
     * offered, so the fields are populated unconditionally.
     */
    @Test
    fun `every feed line carries a debug trace`() {
        val base = story("story-feed-debug")
        val instance = base.copy(focusCharacterId = marinette)
        val withMemory = instance.copy(
            memories = instance.memories.add(
                Memory(id = MemoryId("d1"), characterId = marinette, content = "Something happened.", importance = 5),
            ),
        )
        for (line in StoryFeed.build(withMemory)) {
            assertTrue("line has no debug source", line.debug.source.isNotBlank())
            assertTrue("line is not marked debug-visible", line.isDebugVisible)
        }
    }

    @Test
    fun `rate limiting refuses a line too soon after the last one`() {
        val first = StoryFeedLine(
            id = "a",
            kind = FeedKind.REMEMBERED,
            text = "Something happened.",
            eventMinutes = 0L,
        )
        // 5 story minutes later: refused.
        assertFalse(
            StoryFeed.rateLimit(first, first.copy(id = "b", eventMinutes = 5L)),
        )
        // 20 minutes later: accepted.
        assertTrue(
            StoryFeed.rateLimit(first, first.copy(id = "c", eventMinutes = 20L)),
        )
        // Nothing shown yet: always accepted.
        assertTrue(
            StoryFeed.rateLimit(null, first.copy(id = "d", eventMinutes = 0L)),
        )
    }

    @Test
    fun `relative labels are phrases`() {
        val now = dev.charaly.runtime.domain.StoryTime.of(day = 3, hour = 20, minute = 0)
        fun label(minutesAgo: Long) = StoryFeed.relativeLabel(
            dev.charaly.runtime.domain.StoryTime.of(day = 3, hour = 20, minute = 0)
                .minusMinutes(minutesAgo),
            now,
        )
        assertEquals("Şimdi", label(0))
        assertEquals("Şimdi", label(30))
        assertEquals("3 saat önce", label(180))
        assertEquals("Bugün daha erken", label(600))
        assertEquals("Dün gece", label(60L * 20))
        assertEquals("Dün", label(60L * 30))
        assertEquals("2 gün önce", label(60L * 24 * 2))
    }

    // ------------------------------------------------------------------
    // Resume intelligence on the Continue card
    // ------------------------------------------------------------------

    /**
     * Home's Continue card says where you were.
     *
     * A title and a timestamp is not enough to decide whether to step back in; the
     * location, the time of day and the current beat are.
     */
    @Test
    fun `the continue card carries resume intelligence`() {
        val instance = story("story-resume")
        val card = HomePresenter.build(
            nowEpochMs = 1_700_000_000_000L,
            instances = listOf(instance),
            packs = listOf(pack),
            definitions = mapOf(pack.id.value to definition),
            model = TestModelStatus,
        ).continueCard

        assertTrue("no continue card", card != null)
        requireNotNull(card)
        assertTrue("the card has no story title", card.storyTitle.isNotBlank())
        assertTrue("the card has no day label", card.sceneDayLabel.isNotBlank())
        // Presence is always phrased, even when the player is alone.
        assertTrue("the card has no presence label", card.presenceLabel.isNotBlank())
        assertTrue(
            "the card's presence label is a phrase",
            card.presenceLabel == "Sadece siz" || card.presenceLabel.endsWith("burada"),
        )
    }

    @Test
    fun `a hero banner is available for the continue card`() {
        val instance = story("story-banner")
        val card = HomePresenter.build(
            nowEpochMs = 1_700_000_000_000L,
            instances = listOf(instance),
            packs = listOf(pack),
            definitions = mapOf(pack.id.value to definition),
            model = TestModelStatus,
        ).continueCard
        requireNotNull(card)
        // Keyed on the pack, not the story, so every playthrough of a pack shares the
        // same hero composition rather than looking like a different world.
        assertTrue("the card has no banner artwork", card.bannerArtwork != null)
    }

    /** The model status stand-in the presenters take, so a test needs no real model. */
    private object TestModelStatus : ModelStatusLike {
        override fun displayName(): String = ""
        override fun stateLabel(): String = "No model"
        override fun detailLabel(): String = "Import a GGUF to generate."
        override fun isReady(): Boolean = false
    }
}
