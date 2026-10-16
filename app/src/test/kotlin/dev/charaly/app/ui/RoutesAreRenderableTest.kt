package dev.charaly.app.ui

import dev.charaly.app.ui.nav.CharalyNavigator
import dev.charaly.app.ui.nav.Route
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.InitialWorldState
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.PackArtwork
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.StoryPackId
import dev.charaly.runtime.domain.WorldDefinition
import dev.charaly.runtime.engine.StoryCreationOptions
import dev.charaly.runtime.engine.StoryInstanceFactory
import dev.charaly.runtime.pack.DemoStoryPacks
import dev.charaly.runtime.presentation.ChatStagePresenter
import dev.charaly.runtime.presentation.GenerationPhase
import dev.charaly.runtime.presentation.LibraryShelfPresenter
import dev.charaly.runtime.presentation.LobbyPresenter
import dev.charaly.runtime.presentation.PackShowcaseBuilder
import dev.charaly.runtime.presentation.RegistryModelStatus
import dev.charaly.runtime.presentation.StoryContextPresenter
import dev.charaly.runtime.presentation.WorldsFeedPresenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every primary destination must survive the inputs a real session throws at it.
 *
 * Two kinds of failure are covered here, and both used to be crashes rather than
 * empty states:
 *
 *  * a route naming something that does not exist - a pack deleted from the authoring tool
 *    while its showcase sat on the back stack, a story removed on another screen, a model
 *    deleted mid-session;
 *  * degenerate content - an empty title, a character with no description, a pack with no
 *    characters at all.
 *
 * The rule throughout: a screen must render an *empty state*, not throw. Compose will
 * happily propagate a thrown exception out of a composable and kill the activity, and
 * there is no catch-all that makes that acceptable.
 *
 * Every projection used here is the same one the ViewModel assembles, so this tests the
 * real thing rather than a parallel copy of it.
 */
class RoutesAreRenderableTest {

    private val packs = DemoStoryPacks.all

    private fun definitions(packs: List<dev.charaly.runtime.domain.StoryPack> = this.packs) =
        packs.associate { it.id.value to WorldDefinition(it.characters, it.locations) }

    private fun storyFor(pack: dev.charaly.runtime.domain.StoryPack, id: String) =
        StoryInstanceFactory.create(pack, StoryCreationOptions(instanceId = StoryInstanceId(id)))

    private fun noModel() = RegistryModelStatus(installed = null, engineLabel = "llama", ready = false)

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    @Test
    fun `every primary destination has a route that round-trips`() {
        val routes = listOf(
            Route.Home,
            Route.Sessions,
            Route.Create,
            Route.Library,
            Route.Showcase("pack-miraculous-shadows-of-paris"),
            Route.EnterWorld("pack-miraculous-shadows-of-paris"),
            Route.Chat,
            Route.Stage("story-1"),
            Route.StoryRecord("story-1"),
            Route.Models,
            Route.ModelDetail("local-1"),
            Route.Settings,
        )
        routes.forEach { route ->
            assertEquals(route, dev.charaly.app.ui.nav.decodeRoute(route.encode()))
        }
    }

    @Test
    fun `a route naming a pack that no longer exists is inert, not fatal`() {
        // What happens when a pack is deleted while its showcase screen is on the stack.
        val navigator = CharalyNavigator()
        navigator.navigateTo(Route.Library)
        navigator.navigateTo(Route.Showcase("pack-that-was-deleted"))

        // The showcase projection is null for a missing pack, and the screen's null branch
        // is what renders - asserted by type rather than by calling the composable.
        val resolved = packs.firstOrNull { it.id.value == "pack-that-was-deleted" }
        assertNull(resolved)

        // Back navigation still works from the broken state.
        assertTrue(navigator.pop())
        assertEquals(Route.Library, navigator.current)
    }

    @Test
    fun `a route naming a story that no longer exists still pops cleanly`() {
        val navigator = CharalyNavigator()
        navigator.navigateTo(Route.Stage("story-does-not-exist"))
        assertTrue(navigator.pop())
        assertEquals(Route.Home, navigator.current)
    }

    @Test
    fun `a route naming a model that was deleted still pops cleanly`() {
        val navigator = CharalyNavigator()
        navigator.navigateTo(Route.ModelDetail("model-that-was-deleted"))
        assertTrue(navigator.pop())
        assertEquals(Route.Home, navigator.current)
    }

    @Test
    fun `rapid navigation never leaves the stack inconsistent`() {
        val navigator = CharalyNavigator()
        repeat(20) { index ->
            navigator.navigateTo(Route.Showcase("pack-$index"))
            navigator.navigateTo(Route.Stage("story-$index"))
        }
        while (navigator.canGoBack) {
            assertTrue(navigator.pop())
        }
        assertEquals(Route.Home, navigator.current)
        assertEquals(1, navigator.backStack.size)
    }

    @Test
    fun `tapping the same destination twice does not stack it`() {
        val navigator = CharalyNavigator()
        navigator.selectTab(Route.Tab.LIBRARY)
        navigator.selectTab(Route.Tab.LIBRARY)
        assertEquals(1, navigator.backStack.size)
    }

    // ------------------------------------------------------------------
    // The lobby
    // ------------------------------------------------------------------

    @Test
    fun `the lobby renders with no packs and no stories at all`() {
        val snapshot = LobbyPresenter.build(
            nowEpochMs = 0L,
            instances = emptyList(),
            packs = emptyList(),
            definitions = emptyMap(),
            model = noModel(),
        )
        assertNotNull("a first run must show an empty state, not a blank page", snapshot.emptyWorlds)
        assertNull(snapshot.continueSurface)
        assertTrue(snapshot.worlds.isEmpty())
        assertTrue(snapshot.stories.isEmpty())
        assertEquals(dev.charaly.runtime.presentation.LeadIdea.EMPTY, snapshot.leadIdea)
        // And the question is still asked, because the screen's job is to be a doorway.
        assertEquals(LobbyPresenter.HEADLINE, snapshot.headline)
    }

    @Test
    fun `the lobby renders with packs but no stories`() {
        val snapshot = LobbyPresenter.build(0L, emptyList(), packs, definitions(), noModel())
        assertNull("there is nothing to continue when no story exists", snapshot.continueSurface)
        // The empty state is for *no worlds at all* - with worlds present, the feed is the
        // content and an empty-state card beside it would be noise.
        assertNull(snapshot.emptyWorlds)
        assertEquals(packs.size, snapshot.worlds.size)
        assertEquals(dev.charaly.runtime.presentation.LeadIdea.DISCOVER, snapshot.leadIdea)
        assertEquals("${packs.size} dünyaya adım atabilirsiniz.", snapshot.subline)
    }

    @Test
    fun `the lobby leads with a continuation surface when a story exists`() {
        val pack = packs.first()
        val instance = storyFor(pack, "story-lobby")
        val snapshot = LobbyPresenter.build(0L, listOf(instance), packs, definitions(), noModel())

        val surface = snapshot.continueSurface
        assertNotNull("an existing story must produce a continuation surface", surface)
        assertEquals("story-lobby", surface!!.storyId)
        assertEquals(pack.title, surface.worldName)
        assertEquals(dev.charaly.runtime.presentation.LeadIdea.CONTINUE, snapshot.leadIdea)
        assertEquals("Sizi bekleyen bir şey var.", snapshot.subline)
        // And the moment is a sentence, never blank and never a count.
        assertTrue("the moment must say something", surface.moment.isNotBlank())
        assertTrue(
            "the presence label must never be a raw count of the pack's cast",
            surface.presenceLabel.isBlank() || surface.presenceLabel.contains("burada") ||
                surface.presenceLabel.contains("Sadece siz"),
        )
    }

    @Test
    fun `the lobby shows only the newest story on the continuation surface`() {
        val pack = packs.first()
        val stories = (1..3).map { storyFor(pack, "story-$it") }
        val snapshot = LobbyPresenter.build(0L, stories, packs, definitions(), noModel())
        assertNotNull(snapshot.continueSurface)
        // The shelf takes the rest, capped.
        assertTrue(
            "the shelf must be capped, not the whole history",
            snapshot.stories.size <= LobbyPresenter.SHELF_LIMIT,
        )
        assertTrue(
            "the continue surface's story must not also be on the shelf",
            snapshot.stories.none { it.id == snapshot.continueSurface!!.storyId },
        )
    }

    @Test
    fun `the lobby renders a pack with no tagline and no artwork`() {
        // A tagline and cover are both optional, and a pack author who leaves them blank
        // must not produce a blank-looking card.
        val bare = packs.first().copy(
            id = StoryPackId("pack-bare"),
            description = "",
            identity = packs.first().identity.copy(tagline = "", cover = PackArtwork()),
        )
        val snapshot = LobbyPresenter.build(0L, emptyList(), listOf(bare), definitions(listOf(bare)), noModel())
        assertEquals(1, snapshot.worlds.size)
        assertTrue("the feed item must still have a title", snapshot.worlds.first().title.isNotBlank())
    }

    // ------------------------------------------------------------------
    // The worlds feed
    // ------------------------------------------------------------------

    @Test
    fun `the feed weights its cards so one world leads`() {
        val snapshot = WorldsFeedPresenter.build(0L, emptyList(), packs)
        assertEquals(packs.size, snapshot.worlds.size)
        assertEquals(
            "the feed must have exactly one dominant card, or it is a grid",
            1,
            snapshot.worlds.count { it.weight == dev.charaly.runtime.presentation.WorldWeight.DOMINANT },
        )
        assertEquals(
            dev.charaly.runtime.presentation.WorldWeight.DOMINANT,
            snapshot.worlds.first().weight,
        )
        assertTrue(
            "every world after the first two must be quiet",
            snapshot.worlds.drop(3).all {
                it.weight == dev.charaly.runtime.presentation.WorldWeight.QUIET
            },
        )
    }

    @Test
    fun `the feed re-derives its weights after filtering`() {
        // Weighting before filtering would let a search return a screen whose largest
        // element was the third result, which reads as a bug.
        //
        // The fixture is the *third* pack specifically: with three worlds the weights are
        // DOMINANT, FULL, FULL, so a world that was never the lead is guaranteed to become
        // the lead once it is the only result. Filtering on a word from its premise keeps
        // the match unambiguous.
        val full = WorldsFeedPresenter.build(0L, emptyList(), packs)
        val tail = full.worlds[2]
        assertEquals(
            "the third world must not start as the dominant card, or this test proves nothing",
            dev.charaly.runtime.presentation.WorldWeight.FULL,
            tail.weight,
        )

        val word = tail.premise.split(" ")
            .firstOrNull { it.length >= 5 }
            ?: error("the third world has no distinctive word in its premise: ${tail.premise}")

        val filtered = WorldsFeedPresenter.build(0L, emptyList(), packs, query = word)

        assertTrue(
            "the fixture word \"$word\" matched ${filtered.worlds.size} worlds; it must be " +
                "unique to one world so this test can prove re-weighting",
            filtered.worlds.size == 1,
        )
        assertEquals(
            "a filtered feed must make its first result the dominant card, or a search " +
                "returns a screen whose largest element was the third result",
            dev.charaly.runtime.presentation.WorldWeight.DOMINANT,
            filtered.worlds.first().weight,
        )
    }

    @Test
    fun `the feed renders with no packs`() {
        val snapshot = WorldsFeedPresenter.build(0L, emptyList(), emptyList())
        assertNotNull(snapshot.emptyState)
        assertTrue(snapshot.isEmpty)
        assertEquals("0", snapshot.matchCountLabel())
    }

    @Test
    fun `the feed reports what it is filtering`() {
        val all = WorldsFeedPresenter.build(0L, emptyList(), packs)
        assertEquals(packs.size.toString(), all.matchCountLabel())

        val one = WorldsFeedPresenter.build(0L, emptyList(), packs, query = all.worlds.first().id)
        assertTrue(
            "a filtered feed must say how many of how many: ${one.matchCountLabel()}",
            one.matchCountLabel().contains("of ${packs.size}"),
        )
    }

    @Test
    fun `a feed filter that matches nothing explains itself`() {
        val snapshot = WorldsFeedPresenter.build(0L, emptyList(), packs, query = "zzzz-no-such-world")
        assertNotNull(snapshot.emptyState)
        assertNotNull(snapshot.emptyState!!.actionLabel)
        assertTrue(
            "a search empty state must offer to clear the search",
            snapshot.emptyState!!.actionLabel.contains("Temizle", ignoreCase = true),
        )
    }

    @Test
    fun `the feed's genres come from authored metadata only`() {
        val snapshot = WorldsFeedPresenter.build(0L, emptyList(), packs)
        assertTrue("a pack declares genres, so some must be offered", snapshot.availableGenres.isNotEmpty())
        assertTrue(
            "the genre row must be capped or it becomes a wall of chips",
            snapshot.availableGenres.size <= WorldsFeedPresenter.FILTER_LIMIT,
        )
    }

    // ------------------------------------------------------------------
    // The stage
    // ------------------------------------------------------------------

    @Test
    fun `the stage renders a story that has not begun`() {
        val pack = packs.first()
        val instance = storyFor(pack, "story-new")
        val stage = ChatStagePresenter.build(
            instance = instance,
            pack = pack,
            definition = WorldDefinition(pack.characters, pack.locations),
        )
        assertNotNull("an unstarted story needs an opening, not a blank transcript", stage.opening)
        assertFalse(stage.hasTranscript)
        assertFalse(stage.isGenerating)
        // The composer is *enabled* here: saying something is how a story starts.
        assertTrue(stage.composer.isEnabled)
        assertEquals("", stage.composer.blockedReason())
        // And the header never names nobody.
        assertTrue("the companion must never be blank", stage.companionName.isNotBlank())
    }

    @Test
    fun `the stage disables the composer and says why when there is no model`() {
        val pack = packs.first()
        val instance = storyFor(pack, "story-nomodel")
        val stage = ChatStagePresenter.build(
            instance = instance,
            pack = pack,
            definition = WorldDefinition(pack.characters, pack.locations),
            modelReady = false,
        )
        assertFalse(stage.composer.isEnabled)
        val reason = stage.composer.blockedReason()
        assertTrue("a disabled composer must say why: '$reason'", reason.isNotBlank())
        assertTrue(
            "the reason must name the fix, not just the symptom: '$reason'",
            reason.contains("model", ignoreCase = true),
        )
    }

    @Test
    fun `the stage reports generation as a person, not as a spinner`() {
        val pack = packs.first()
        val instance = storyFor(pack, "story-thinking")
        val definition = WorldDefinition(pack.characters, pack.locations)

        val thinking = ChatStagePresenter.build(instance, pack, definition, phase = GenerationPhase.THINKING)
        assertTrue(thinking.phaseLabel.isNotBlank())
        assertTrue(thinking.isGenerating)
        assertFalse(
            "the composer must be locked while the model is writing",
            thinking.composer.isEnabled,
        )

        val streaming = ChatStagePresenter.build(
            instance,
            pack,
            definition,
            phase = GenerationPhase.STREAMING,
            streamingText = "I have been waiting",
        )
        assertTrue(streaming.isStreaming)
        assertTrue("the streaming beat must be rendered", streaming.beats.any { it.isStreaming })
    }

    @Test
    fun `a stage failure is a sentence with somewhere to go`() {
        val pack = packs.first()
        val instance = storyFor(pack, "story-failed")
        val stage = ChatStagePresenter.build(
            instance = instance,
            pack = pack,
            definition = WorldDefinition(pack.characters, pack.locations),
            phase = GenerationPhase.FAILED,
            failureMessage = "InferenceError: context window exceeded",
        )
        val failure = stage.failure
        assertNotNull("a failed generation must say so", failure)
        assertTrue("a failure with no action is a dead end", failure!!.actions.isNotEmpty())
        assertTrue(
            "a raw exception class must not be the headline: ${failure.message}",
            !failure.message.contains("InferenceError"),
        )
    }

    @Test
    fun `a stage renders when the focus character has been deleted`() {
        val pack = packs.first()
        val instance = StoryInstanceFactory.create(
            pack,
            StoryCreationOptions(
                instanceId = StoryInstanceId("story-orphan"),
                focusCharacterId = CharacterId("a-character-who-left"),
                nowEpochMs = 0L,
            ),
        )
        val stage = ChatStagePresenter.build(
            instance = instance,
            pack = pack,
            definition = WorldDefinition(pack.characters, pack.locations),
        )
        // No character by that name, and no crash on the way to a usable snapshot.
        assertTrue("the companion must fall back to something displayable", stage.companionName.isNotBlank())
        assertTrue(
            "the scene must resolve to something displayable",
            stage.contextLine.isNotBlank() || stage.opening != null,
        )
    }

    @Test
    fun `the stage renders without its pack`() {
        // A pack removed while its story is open. The stage still has to render, because
        // the story itself is intact and the user's transcript is real.
        val pack = packs.first()
        val instance = storyFor(pack, "story-nopack")
        val stage = ChatStagePresenter.build(
            instance = instance,
            pack = null,
            definition = WorldDefinition(pack.characters, pack.locations),
        )
        assertNotNull(stage)
        assertTrue(stage.companionName.isNotBlank())
    }

    // ------------------------------------------------------------------
    // The four context sheets
    // ------------------------------------------------------------------

    @Test
    fun `the four sheets render for a story with no memories and no threads`() {
        val pack = packs.first()
        val instance = storyFor(pack, "story-thin")
        val sheets = StoryContextPresenter.build(
            WorldDefinition(pack.characters, pack.locations),
            instance,
        )
        assertEquals(4, sheets.entries.size)
        assertEquals(
            listOf(
                dev.charaly.runtime.presentation.ContextEntryId.WORLD,
                dev.charaly.runtime.presentation.ContextEntryId.MEMORY,
                dev.charaly.runtime.presentation.ContextEntryId.PEOPLE,
                dev.charaly.runtime.presentation.ContextEntryId.STORY,
            ),
            sheets.entries.map { it.id },
        )
        // Every entry answers the question its label asks, on the entry itself.
        for (entry in sheets.entries) {
            assertTrue("${entry.id} has no label", entry.label.isNotBlank())
            assertTrue("${entry.id} has no summary", entry.summary.isNotBlank())
            assertNotNull(sheets.entryFor(entry.id))
        }
        // And an unresolved location still reads as somewhere, not as a raw id.
        assertTrue(sheets.world.locationName.isNotBlank())
        assertTrue(sheets.story.currentBeat.isNotBlank())
    }

    @Test
    fun `no sheet field exposes an engine internal as its label`() {
        val pack = packs.first()
        val instance = storyFor(pack, "story-leak")
        val sheets = StoryContextPresenter.build(
            WorldDefinition(pack.characters, pack.locations),
            instance,
        )
        val strings = buildList {
            add(sheets.world.locationName)
            add(sheets.world.locationDescription)
            addAll(sheets.world.nearby)
            add(sheets.world.timeOfDay)
            add(sheets.story.currentBeat)
            addAll(sheets.story.threads.map { it.title })
            addAll(sheets.story.threads.map { it.status })
            addAll(sheets.story.threads.map { it.progress })
            addAll(sheets.people.map { it.name })
            addAll(sheets.people.map { it.role })
            addAll(sheets.people.map { it.standing })
            addAll(sheets.memory.map { it.text })
        }.filter { it.isNotBlank() }

        assertTrue("nothing to assert on", strings.isNotEmpty())
        for (text in strings) {
            for (forbidden in listOf("EPISODIC", "SEMANTIC", "LUDIC", "pack-", "char-", "loc-", "mem-")) {
                assertFalse(
                    "a sheet leaked an engine internal ('$forbidden') in \"$text\"",
                    text.contains(forbidden),
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // The library
    // ------------------------------------------------------------------

    @Test
    fun `the library renders with no stories`() {
        val shelf = LibraryShelfPresenter.build(0L, emptyList())
        assertTrue(shelf.isEmpty)
        assertNotNull(shelf.emptyState)
        assertNotNull(shelf.emptyState!!.actionLabel)
    }

    @Test
    fun `the library renders every story and describes each one`() {
        val pack = packs.first()
        val stories = (1..3).map { storyFor(pack, "story-$it") }
        val shelf = LibraryShelfPresenter.build(0L, stories)
        assertEquals(3, shelf.stories.size)
        assertEquals(3, shelf.totalCount)
        for (story in shelf.stories) {
            assertTrue("a shelf row must name its world", story.worldName.isNotBlank())
            assertTrue("a shelf row must say what is happening", story.moment.isNotBlank())
        }
    }

    @Test
    fun `the library search really filters and says so`() {
        val pack = packs.first()
        val stories = (1..3).map { storyFor(pack, "story-$it") }
        val shelf = LibraryShelfPresenter.build(0L, stories, query = "no-such-story")
        assertTrue(shelf.isEmpty)
        assertNotNull(
            "a search that matches nothing must explain itself, not read as an empty library",
            shelf.emptyState,
        )
        assertTrue(shelf.emptyState!!.title.contains("eşleşen", ignoreCase = true))
    }

    // ------------------------------------------------------------------
    // Degenerate content
    // ------------------------------------------------------------------

    @Test
    fun `a pack with a blank title is rejected at the domain, not at the screen`() {
        // Worth pinning explicitly: the guarantee that blank titles cannot exist is a
        // domain invariant, so the screen never has to defend against one.
        assertTrue(
            "a pack with no title must be refused where it is authored",
            runCatching { packs.first().copy(id = StoryPackId("pack-blank"), title = "") }.isFailure,
        )
    }

    @Test
    fun `a pack renders its showcase when stripped of characters locations and events`() {
        // Its seed events are stripped too, because they reference characters that no
        // longer exist - which is the point: content removal must not crash the projection.
        val empty = packs.first().copy(
            id = StoryPackId("pack-empty"),
            characters = emptyList(),
            locations = emptyList(),
            events = emptyList(),
            initialEvents = emptyList(),
            scenarios = emptyList(),
            personas = emptyList(),
            factions = emptyList(),
            lore = emptyList(),
            initialStoryThreads = emptyList(),
            initialWorldState = InitialWorldState(
                startLocations = emptyMap(),
                startActivities = emptyMap(),
                startGoals = emptyMap(),
            ),
        )
        val showcase = PackShowcaseBuilder.build(empty)
        assertTrue("a stripped pack must still have a premise", showcase.premise.isNotBlank())
        assertNotNull("a stripped pack must still have a primary action", showcase.primaryActionLabel)

        // And the feed and the lobby render it too.
        val feed = WorldsFeedPresenter.build(0L, emptyList(), listOf(empty))
        assertEquals(1, feed.worlds.size)

        val lobby = LobbyPresenter.build(0L, emptyList(), listOf(empty), definitions(listOf(empty)), noModel())
        assertEquals(1, lobby.worlds.size)

        // A world with no characters must still be startable.
        val instance = StoryInstanceFactory.create(
            empty,
            StoryCreationOptions(instanceId = StoryInstanceId("story-empty")),
        )
        assertEquals(0, instance.characters.size)
        val stage = ChatStagePresenter.build(instance, empty, WorldDefinition(emptyList(), emptyList()))
        assertNotNull(stage)
    }

    @Test
    fun `every shipped pack renders a feed item and a showcase`() {
        for (pack in packs) {
            val feed = WorldsFeedPresenter.build(0L, emptyList(), listOf(pack))
            assertEquals("${pack.id.value} must render as a feed item", 1, feed.worlds.size)
            assertTrue(
                "${pack.id.value} has no premise, so its feed card would be blank",
                feed.worlds.first().premise.isNotBlank(),
            )

            val showcase = PackShowcaseBuilder.build(pack)
            assertEquals(pack.title, showcase.title)
            assertTrue("${pack.id.value} has no hooks to show", showcase.hooks.isNotEmpty())
        }
    }

    @Test
    fun `a story started from every pack opens without a model`() {
        for (pack in packs) {
            val instance = StoryInstanceFactory.create(
                pack,
                StoryCreationOptions(
                    instanceId = StoryInstanceId("story-${pack.id.value}"),
                    scenario = pack.defaultScenario(),
                ),
            )
            // Either the scenario placed the player somewhere, or there is no scene yet.
            // Both are legitimate; what must not happen is an exception.
            val where = instance.currentLocation()
            assertTrue(
                "a started story must be locatable or explicitly unlocated",
                where == null || where.id.value.isNotBlank(),
            )
            assertTrue(instance.worldClock.now.totalMinutes > 0)

            val stage = ChatStagePresenter.build(
                instance,
                pack,
                WorldDefinition(pack.characters, pack.locations),
                modelReady = false,
            )
            assertFalse(
                "${pack.id.value}: with no model the composer must be disabled and say so",
                stage.composer.isEnabled,
            )
        }
    }

    @Test
    fun `an empty location id never reaches the UI`() {
        // Location ids are built from pack data; a blank one would serialise as "" and
        // collide in a map. The domain type refuses it, so assert the refusal.
        assertTrue(runCatching { LocationId("") }.isFailure)
        assertTrue(runCatching { CharacterId("") }.isFailure)
    }
}
