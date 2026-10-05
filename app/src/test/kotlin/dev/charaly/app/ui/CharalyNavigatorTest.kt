package dev.charaly.app.ui

import dev.charaly.app.ui.nav.CharalyNavigator
import dev.charaly.app.ui.nav.Route
import dev.charaly.app.ui.nav.decodeRoute
import dev.charaly.app.ui.nav.tabFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Navigation is a hand-rolled back stack rather than a library, so it carries its own
 * responsibility: restoring the user to the story they were reading.
 *
 * These are pure JVM tests on purpose - navigation bugs are cheap to find here and
 * expensive to find on a device.
 */
class CharalyNavigatorTest {

    @Test
    fun `starts at home and cannot pop`() {
        val navigator = CharalyNavigator()
        assertEquals(Route.Home, navigator.current)
        assertFalse(navigator.canGoBack)
        assertFalse(navigator.pop())
    }

    @Test
    fun `pushing and popping a story returns to where it came from`() {
        val navigator = CharalyNavigator()
        navigator.navigateTo(Route.Library)
        navigator.navigateTo(Route.PackDetail("pack-miraculous-shadows-of-paris"))
        navigator.navigateTo(Route.NewStory("pack-miraculous-shadows-of-paris"))

        assertEquals(4, navigator.backStack.size)
        assertTrue(navigator.pop())
        assertEquals(Route.PackDetail("pack-miraculous-shadows-of-paris"), navigator.current)
        assertTrue(navigator.pop())
        assertEquals(Route.Library, navigator.current)
    }

    @Test
    fun `selecting a tab clears whatever was stacked on it`() {
        val navigator = CharalyNavigator()
        navigator.navigateTo(Route.Story("story-1"))
        navigator.navigateTo(Route.ModelDetail("local-qwen"))

        navigator.selectTab(Route.Tab.SESSIONS)

        assertEquals(1, navigator.backStack.size)
        assertEquals(Route.Sessions, navigator.current)
        assertFalse(navigator.canGoBack)
    }

    @Test
    fun `replacing the stack leaves a finished flow with no history`() {
        val navigator = CharalyNavigator()
        navigator.navigateTo(Route.Library)
        navigator.navigateTo(Route.NewStory("pack-neon-district-afterlight"))

        // "Enter Story" should not leave the wizard behind the story.
        navigator.replaceAll(Route.Story("story-99"))

        assertEquals(Route.Story("story-99"), navigator.current)
        assertEquals(1, navigator.backStack.size)
        assertFalse(navigator.pop())
    }

    @Test
    fun `popping to root keeps the user on the same tab`() {
        val navigator = CharalyNavigator()
        navigator.selectTab(Route.Tab.PACKS)
        navigator.navigateTo(Route.PackDetail("pack-the-last-kingdom"))
        navigator.navigateTo(Route.CharacterDetail("pack-the-last-kingdom", "elara"))

        navigator.popToRoot()

        assertEquals(Route.Library, navigator.current)
    }

    @Test
    fun `navigating to the current route is a no-op`() {
        val navigator = CharalyNavigator()
        navigator.navigateTo(Route.Sessions)
        navigator.navigateTo(Route.Sessions)
        assertEquals(2, navigator.backStack.size)
    }

    @Test
    fun `a whole back stack encodes into routes and decodes back`() {
        val navigator = CharalyNavigator()
        navigator.navigateTo(Route.Library)
        navigator.navigateTo(Route.PackDetail("pack-neon-district-afterlight"))
        navigator.navigateTo(Route.NewStory("pack-neon-district-afterlight"))
        navigator.navigateTo(Route.Story("story-99"))

        // This is exactly what the Saver writes and reads back.
        val encoded = navigator.backStack.map { it.encode() }
        val decoded = encoded.map { decodeRoute(it) }

        assertEquals(navigator.backStack.toList(), decoded)
        assertEquals(Route.Story("story-99"), decoded.last())
    }

    @Test
    fun `every route round-trips through its encoding`() {
        val routes = listOf(
            Route.Home,
            Route.Library,
            Route.Models,
            Route.Sessions,
            Route.Settings,
            Route.Developer,
            Route.CreatePack,
            Route.PackDetail("pack-x"),
            Route.NewStory("pack-x"),
            Route.Story("story-x"),
            Route.SessionDetail("story-x"),
            Route.ModelDetail("local-x"),
            Route.CharacterDetail("pack-x", "alice"),
            Route.LocationDetail("pack-x", "library"),
            Route.EditCharacter("pack-x", "alice"),
            Route.EditLocation("pack-x", "library"),
            Route.EditEvent("pack-x", "event-x"),
        )
        routes.forEach { route ->
            assertEquals(route, decodeRoute(route.encode()))
        }
    }

    @Test
    fun `an unknown encoded route falls back instead of crashing`() {
        assertNull(decodeRoute("nonsense"))
        assertNull(decodeRoute(null))
        assertNull(decodeRoute("pack/"))
    }

    @Test
    fun `routes report the tab they belong to`() {
        assertEquals(Route.Tab.HOME, Route.Home.tabFor())
        assertEquals(Route.Tab.PACKS, Route.Library.tabFor())
        assertEquals(Route.Tab.PACKS, Route.PackDetail("pack-x").tabFor())
        assertEquals(Route.Tab.MODELS, Route.ModelDetail("local-x").tabFor())
        assertEquals(Route.Tab.SESSIONS, Route.SessionDetail("story-x").tabFor())
        // A story is reached from a Story Pack, so that is the tab it belongs to.
        assertEquals(Route.Tab.PACKS, Route.Story("story-x").tabFor())
    }
}