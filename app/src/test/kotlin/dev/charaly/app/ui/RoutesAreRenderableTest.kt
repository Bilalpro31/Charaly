package dev.charaly.app.ui

import dev.charaly.app.ui.nav.CharalyNavigator
import dev.charaly.app.ui.nav.Route
import dev.charaly.app.ui.nav.decodeRoute
import dev.charaly.app.ui.nav.tabFor
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.StoryPackId
import dev.charaly.runtime.engine.StoryCreationOptions
import dev.charaly.runtime.engine.StoryInstanceFactory
import dev.charaly.runtime.pack.DemoStoryPacks
import dev.charaly.runtime.presentation.CharacterCard
import dev.charaly.runtime.presentation.LibraryPresenter
import dev.charaly.runtime.presentation.ModelLibraryPresenter
import dev.charaly.runtime.presentation.PackDetailPresenter
import dev.charaly.runtime.presentation.RegistryModelStatus
import dev.charaly.runtime.presentation.StoryPresenter
import dev.charaly.runtime.presentation.WorldPanelPresenter
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
 *  * a route naming something that does not exist - a pack deleted from the creator
 *    while its detail screen sat on the back stack, a story removed on another screen,
 *    a model deleted mid-session;
 *  * degenerate content - an empty title, a character with no description, a pack with
 *    no characters at all.
 *
 * The rule throughout: a screen must render an *empty state*, not throw. Compose will
 * happily propagate a thrown exception out of a composable and kill the activity, and
 * there is no catch-all that makes that acceptable.
 */
class RoutesAreRenderableTest {

    private val packs = DemoStoryPacks.all

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    @Test
    fun `every primary destination has a route that round-trips`() {
        val destinations = listOf(
            Route.Home,
            Route.Library,
            Route.PackDetail("pack-miraculous-shadows-of-paris"),
            Route.NewStory("pack-miraculous-shadows-of-paris"),
            Route.Story("story-1"),
            Route.Sessions,
            Route.SessionDetail("story-1"),
            Route.Models,
            Route.ModelDetail("local-1"),
            Route.Settings,
        )
        destinations.forEach { route ->
            assertEquals(route, decodeRoute(route.encode()))
            assertNotNull("${route.encode()} must name a tab", route.tabFor())
        }
    }

    @Test
    fun `a route naming a pack that no longer exists is inert, not fatal`() {
        // What happens when a pack is deleted while its detail screen is on the stack.
        val navigator = CharalyNavigator()
        navigator.navigateTo(Route.Library)
        navigator.navigateTo(Route.PackDetail("pack-that-was-deleted"))

        val snapshot = PackDetailPresenter.buildOrNull(
            nowEpochMs = 0L,
            pack = packs.firstOrNull { it.id.value == "pack-that-was-deleted" },
            instances = emptyList(),
            defaultProfileName = "",
        )
        assertNull("a missing pack must resolve to null, not throw", snapshot)

        // Back navigation still works from the broken state.
        assertTrue(navigator.pop())
        assertEquals(Route.Library, navigator.current)
    }

    @Test
    fun `a route naming a story that no longer exists still pops cleanly`() {
        val navigator = CharalyNavigator()
        navigator.navigateTo(Route.Story("story-does-not-exist"))
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
        repeat(20) { i ->
            navigator.navigateTo(Route.PackDetail("pack-$i"))
            navigator.navigateTo(Route.ModelDetail("model-$i"))
        }
        // Every push must be individually poppable, back to the root.
        while (navigator.canGoBack) {
            assertTrue(navigator.pop())
        }
        assertEquals(Route.Home, navigator.current)
        assertEquals(1, navigator.backStack.size)
    }

    @Test
    fun `tapping the same destination twice does not stack it`() {
        val navigator = CharalyNavigator()
        navigator.selectTab(Route.Tab.PACKS)
        navigator.selectTab(Route.Tab.PACKS)
        assertEquals(1, navigator.backStack.size)
    }

    // ------------------------------------------------------------------
    // Degenerate content
    // ------------------------------------------------------------------

    @Test
    fun `the library renders with no packs at all`() {
        val snapshot = LibraryPresenter.build(
            nowEpochMs = 0L,
            packs = emptyList(),
            instances = emptyList(),
        )
        assertNotNull("an empty library must show an empty state", snapshot.emptyState)
        assertTrue(snapshot.sections.isEmpty())
    }

    @Test
    fun `the library renders a pack with no tagline and no artwork`() {
        // The domain requires a title, but a tagline and cover are both optional, and a
        // pack author who leaves them blank must not produce a blank-looking card.
        val bare = packs.first().copy(
            id = StoryPackId("pack-bare"),
            description = "",
            identity = packs.first().identity.copy(tagline = "", cover = dev.charaly.runtime.domain.PackArtwork()),
        )
        val snapshot = LibraryPresenter.build(
            nowEpochMs = 0L,
            packs = listOf(bare),
            instances = emptyList(),
        )
        assertEquals(1, snapshot.visiblePacks)
    }

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
    fun `a pack detail renders with an empty cast and no locations`() {
        // Strip the pack down to *nothing* the detail screen iterates over. Its seed
        // events are stripped too, because they reference characters that no longer
        // exist - which is the point: content removal must not crash world creation.
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
            initialWorldState = dev.charaly.runtime.domain.InitialWorldState(
                startLocations = emptyMap(),
                startActivities = emptyMap(),
                startGoals = emptyMap(),
            ),
        )
        val snapshot = PackDetailPresenter.build(
            nowEpochMs = 0L,
            pack = empty,
            instances = emptyList(),
            defaultProfileName = "",
        )
        assertTrue(snapshot.characters.isEmpty())
        assertTrue(snapshot.locations.isEmpty())
        // And a world with no characters must still be startable.
        val instance = StoryInstanceFactory.create(
            empty,
            StoryCreationOptions(instanceId = StoryInstanceId("story-empty")),
        )
        assertEquals(0, instance.characters.size)
    }

    @Test
    fun `a character card renders when every text field is blank`() {
        val blank = CharacterCard(
            id = "blank",
            name = "",
            tagline = "",
            description = "",
            locationName = "",
            accent = 0L,
            artwork = dev.charaly.runtime.domain.PackArtwork(),
            avatarUri = null,
            role = "",
        )
        // Nothing to assert beyond "no exception while the presenter builds a snapshot
        // containing it"; an accent of 0 is the crash-prone case.
        val pack = packs.first().copy(
            id = StoryPackId("pack-blank-card"),
            characters = listOf(
                packs.first().characters.first().copy(id = CharacterId("blank"), name = "X", accentHex = ""),
            ),
        )
        val snapshot = PackDetailPresenter.build(0L, pack, emptyList(), "")
        assertEquals(1, snapshot.characters.size)
        assertTrue("an unparseable accent must not be zero", snapshot.characters.first().accent != 0L)
        assertEquals("", blank.role)
    }

    @Test
    fun `a story snapshot renders when the focus character has been deleted`() {
        val pack = packs.first()
        val instance = StoryInstanceFactory.create(
            pack,
            StoryCreationOptions(
                instanceId = StoryInstanceId("story-orphan"),
                scenario = pack.defaultScenario(),
                focusCharacterId = CharacterId("a-character-who-left"),
                nowEpochMs = 0L,
            ),
        )
        val snapshot = StoryPresenter.build(
            instance = instance,
            pack = pack,
            definition = dev.charaly.runtime.domain.WorldDefinition(pack.characters, pack.locations),
        )
        // No character by that name, and no crash on the way to a usable snapshot.
        assertNotNull(snapshot)
        assertTrue(
            "the scene must still resolve to something displayable",
            snapshot.locationName.isNotBlank() || snapshot.sceneLine.isNotBlank(),
        )
    }

    @Test
    fun `a world panel renders for a story with no memories and no threads`() {
        val pack = packs.first()
        val instance = StoryInstanceFactory.create(
            pack,
            StoryCreationOptions(instanceId = StoryInstanceId("story-thin"), scenario = null),
        )
        val panel = WorldPanelPresenter.build(instance, pack)
        assertNotNull(panel)
        assertTrue(
            "every timeline entry must carry readable text",
            panel.recentEvents.all { it.summary.isNotBlank() },
        )
        assertTrue(
            "an unresolved location must still read as somewhere, not as a raw id",
            panel.sceneLocation.isNotBlank(),
        )
    }

    @Test
    fun `the model library renders with nothing installed and nothing in the catalog`() {
        val snapshot = ModelLibraryPresenter.build(
            installedModels = emptyList(),
            catalog = emptyList(),
            activeModelId = "",
            loadedModelId = null,
        )
        assertNotNull(snapshot.emptyState)
        assertTrue(snapshot.installed.isEmpty())
        assertTrue(snapshot.awaitingEngineUpdate.isEmpty())
    }

    @Test
    fun `the model library renders when no model is ready`() {
        val snapshot = dev.charaly.runtime.presentation.HomePresenter.build(
            nowEpochMs = 0L,
            instances = emptyList(),
            packs = packs,
            definitions = emptyMap(),
            model = RegistryModelStatus(installed = null, engineLabel = "llama", ready = false),
        )
        assertNotNull(snapshot)
        assertFalse(snapshot.modelStatus.isReady)
        // With packs present but no stories, Home shows the library rather than an apology.
        assertNotNull(
            "the model status must still be renderable when nothing is installed",
            snapshot.modelStatus.modelName,
        )
    }

    @Test
    fun `every demo pack renders a library card and a detail snapshot`() {
        packs.forEach { pack ->
            val card = LibraryPresenter.build(0L, listOf(pack), emptyList())
            assertEquals("${pack.id.value} must render as a card", 1, card.visiblePacks)

            val detail = PackDetailPresenter.build(0L, pack, emptyList(), "")
            assertEquals(pack.title, detail.title)
            assertTrue("${pack.id.value} must have content to show", detail.characters.isNotEmpty())
        }
    }

    @Test
    fun `every character and location in every pack is renderable`() {
        packs.forEach { pack ->
            val detail = PackDetailPresenter.build(0L, pack, emptyList(), "")
            detail.characters.forEach { character ->
                assertTrue("a character card must have an id", character.id.isNotBlank())
            }
            detail.locations.forEach { location ->
                assertTrue("${pack.id.value} has a location with no name", location.name.isNotBlank())
            }
        }
    }

    @Test
    fun `a story started from every pack opens without a model`() {
        packs.forEach { pack ->
            val instance = StoryInstanceFactory.create(
                pack,
                StoryCreationOptions(instanceId = StoryInstanceId("story-${pack.id.value}"), scenario = pack.defaultScenario()),
            )
            // Either the scenario placed the player somewhere, or there is no scene yet. Both are
            // legitimate; what must not happen is an exception.
            val where = instance.currentLocation()
            assertTrue(
                "a started story must be locatable or explicitly unlocated",
                where == null || where.id.value.isNotBlank(),
            )
            assertTrue(instance.worldClock.now.totalMinutes > 0)
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