package dev.charaly.app.ui

import dev.charaly.app.ui.nav.CharalyNavigator
import dev.charaly.app.ui.nav.Route
import dev.charaly.app.ui.nav.decodeRoute
import dev.charaly.app.ui.nav.tabFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The world route is the one that makes Charaly a world rather than a chat app.
 *
 * It has to behave like any other destination: round-trip through the saved back stack,
 * report a tab, and never strand the user when something it names has been deleted.
 */
class WorldNavigationTest {

    @Test
    fun `the world route round-trips through its encoding`() {
        val route = Route.World("story-1")
        assertEquals("world/story-1", route.encode())
        assertEquals(route, decodeRoute(route.encode()))
    }

    @Test
    fun `the world screen belongs to the same tab as the story`() {
        assertEquals(Route.Story("story-1").tabFor(), Route.World("story-1").tabFor())
        assertEquals(Route.Tab.PACKS, Route.World("story-1").tabFor())
    }

    @Test
    fun `an empty world id is not a route`() {
        assertEquals(null, decodeRoute("world/"))
    }

    @Test
    fun `a malformed world route falls back instead of crashing`() {
        assertEquals(null, decodeRoute("world"))
        assertEquals(null, decodeRoute("nonsense/world/story-1"))
    }

    @Test
    fun `going from a story to the world and back returns to the story`() {
        val navigator = CharalyNavigator()
        navigator.navigateTo(Route.Library)
        navigator.navigateTo(Route.PackDetail("pack-miraculous-shadows-of-paris"))
        navigator.replaceAll(Route.Story("story-1"))

        navigator.navigateTo(Route.World("story-1"))
        assertEquals(Route.World("story-1"), navigator.current)

        assertTrue(navigator.pop())
        assertEquals(Route.Story("story-1"), navigator.current)
    }

    @Test
    fun `walking into a place and coming back does not grow the stack without bound`() {
        val navigator = CharalyNavigator()
        navigator.replaceAll(Route.Story("story-1"))
        val depth = navigator.backStack.size

        repeat(10) {
            navigator.navigateTo(Route.World("story-1"))
            navigator.pop()
        }
        assertEquals(depth, navigator.backStack.size)
        assertEquals(Route.Story("story-1"), navigator.current)
    }

    @Test
    fun `the world survives process recreation with its whole stack`() {
        val navigator = CharalyNavigator()
        navigator.selectTab(Route.Tab.PACKS)
        navigator.navigateTo(Route.Story("story-1"))
        navigator.navigateTo(Route.World("story-1"))

        // Exactly what the Saver writes and reads back.
        val encoded = navigator.backStack.map { it.encode() }
        val decoded = encoded.map { decodeRoute(it) }

        assertEquals(navigator.backStack.toList(), decoded)
        assertEquals(Route.World("story-1"), decoded.last())
    }

    @Test
    fun `every wizard step has somewhere to go, and the first does not fake a Back`() {
        // The "dead Back button" bug: the footer used to receive a no-op lambda on the
        // first step, so a permanently dead button appeared. The contract is that the
        // first step has no previous step at all.
        val first = dev.charaly.runtime.presentation.NewStoryStep.entries.first()
        assertEquals(
            "the first wizard step must have no previous step, or Back is a dead button",
            null,
            first.previous,
        )
        dev.charaly.runtime.presentation.NewStoryStep.entries.forEach { step ->
            if (step != first) {
                assertTrue("${step.name} should be able to go back", step.previous != null)
            }
        }
    }

    @Test
    fun `the world is never shown as a bottom-navigation destination`() {
        // The world is immersive: no bottom bar while you are inside it, so it must not
        // be one of the four tab roots the shell can land on. It still reports a tab so
        // that returning from it highlights the right destination.
        val tabRoots: List<Route> = listOf(
            Route.Home,
            Route.Library,
            Route.Models,
            Route.Sessions,
        )
        val world = Route.World("story-1")
        assertFalse("the world must not be a tab root", tabRoots.any { it == world })
        assertEquals(
            "popping the world must land on a tab root",
            Route.Story("story-1").tabFor(),
            world.tabFor(),
        )
    }

    @Test
    fun `a whole back stack mixing world and story survives recreation`() {
        val navigator = CharalyNavigator()
        navigator.navigateTo(Route.Story("story-1"))
        navigator.navigateTo(Route.World("story-1"))
        navigator.navigateTo(Route.Story("story-1"))
        navigator.navigateTo(Route.World("story-1"))
        navigator.navigateTo(Route.Settings)

        val decoded = navigator.backStack.map { decodeRoute(it.encode()) }
        assertEquals(navigator.backStack.toList(), decoded)
        assertEquals(Route.Settings, navigator.current)
    }
}