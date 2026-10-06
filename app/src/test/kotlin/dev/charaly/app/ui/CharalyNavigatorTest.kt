package dev.charaly.app.ui

import dev.charaly.app.ui.nav.CharalyNavigator
import dev.charaly.app.ui.nav.Route
import dev.charaly.app.ui.nav.decodeRoute
import dev.charaly.app.ui.nav.destinationOrNull
import dev.charaly.runtime.presentation.CharalyDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NAVIGATION, for the four-destination app.
 *
 * ## What changed and why it is asserted
 *
 * The previous route table had seven tab roots (HOME, LIBRARY, PACKS, MODELS, SESSIONS, ...)
 * and eleven routes that existed only to reach a detail screen. That is the shape of a
 * settings app: every noun in the domain became a destination, so the bar grew past what a
 * thumb can cover.
 *
 * The new table is four primary destinations and a small set of secondary routes, and this
 * test file pins the two properties that make that work:
 *
 * 1. **Every route round-trips through its encoding.** A saved back stack is written on
 *    every navigation and read after a process death, which is exactly when a user is most
 *    likely to be mid-story. `decodeRoute(encode()) == route` is what makes that safe.
 *
 * 2. **Only the four destinations own a bar.** Everything else returns null from
 *    [destinationOrNull], which is what hides the navigation. The stage and the showcase
 *    are the two screens that own the whole display, and a bar on either would compete with
 *    the fiction.
 *
 * All of it is pure JVM: navigation bugs are cheap to find here and expensive to find on a
 * device.
 */
class CharalyNavigatorTest {

    @Test
    fun `starts at home and cannot pop`() {
        val navigator = CharalyNavigator()
        assertEquals(Route.Home, navigator.current)
        assertFalse(navigator.canGoBack)
        assertFalse("popping the root must leave the app, not crash", navigator.pop())
    }

    @Test
    fun `pushing and popping a story returns to where it came from`() {
        val navigator = CharalyNavigator()
        navigator.selectTab(Route.Tab.LIBRARY)
        navigator.navigateTo(Route.StoryRecord("story-1"))
        navigator.navigateTo(Route.Stage("story-1"))

        assertEquals(3, navigator.backStack.size)
        assertTrue(navigator.pop())
        assertEquals(Route.StoryRecord("story-1"), navigator.current)
        assertTrue(navigator.pop())
        assertEquals(Route.Library, navigator.current)
    }

    @Test
    fun `selecting a destination clears whatever was stacked on it`() {
        val navigator = CharalyNavigator()
        navigator.navigateTo(Route.Worlds)
        navigator.navigateTo(Route.Showcase("pack-neon-district-afterlight"))
        navigator.navigateTo(Route.ModelDetail("local-qwen"))

        navigator.selectTab(Route.Tab.LIBRARY)

        assertEquals(
            "switching destination must clear the previous stack, or a user tapping " +
                "Library from a model detail gets the world's page with a new title",
            1,
            navigator.backStack.size,
        )
        assertEquals(Route.Library, navigator.current)
        assertFalse(navigator.canGoBack)
    }

    @Test
    fun `entering a world leaves the role chooser behind it`() {
        val navigator = CharalyNavigator()
        navigator.selectTab(Route.Tab.WORLDS)
        navigator.navigateTo(Route.Showcase("pack-the-last-kingdom"))
        navigator.navigateTo(Route.EnterWorld("pack-the-last-kingdom"))

        // "Back" from inside a story means "leave the story", not "return to the form I
        // filled in" - so the stack is replaced rather than pushed.
        navigator.replaceAll(Route.Stage("story-99"))

        assertEquals(Route.Stage("story-99"), navigator.current)
        assertEquals(1, navigator.backStack.size)
        assertFalse(navigator.pop())
    }

    @Test
    fun `popping to root keeps the user on the same destination`() {
        val navigator = CharalyNavigator()
        navigator.selectTab(Route.Tab.WORLDS)
        navigator.navigateTo(Route.Showcase("pack-the-last-kingdom"))
        navigator.navigateTo(Route.EnterWorld("pack-the-last-kingdom"))

        navigator.popToRoot()

        assertEquals(
            "popping must land on the destination's own root, not on Home",
            Route.Worlds,
            navigator.current,
        )
    }

    @Test
    fun `popping to root from a route with no destination lands on home`() {
        // Settings and the model hub are full-screen. There is no bar to return to, so the
        // honest answer is Home rather than an unreachable bar.
        val navigator = CharalyNavigator()
        navigator.navigateTo(Route.Settings)
        navigator.navigateTo(Route.Authoring)

        navigator.popToRoot()

        assertEquals(Route.Home, navigator.current)
    }

    @Test
    fun `navigating to the current route is a no-op`() {
        val navigator = CharalyNavigator()
        navigator.navigateTo(Route.Library)
        navigator.navigateTo(Route.Library)
        assertEquals(2, navigator.backStack.size)
    }

    @Test
    fun `selecting the destination already shown at the root does nothing`() {
        val navigator = CharalyNavigator()
        navigator.selectTab(Route.Tab.HOME)
        navigator.selectTab(Route.Tab.HOME)
        assertEquals(1, navigator.backStack.size)
        assertEquals(Route.Home, navigator.current)
    }

    @Test
    fun `a whole back stack encodes into routes and decodes back`() {
        val navigator = CharalyNavigator()
        navigator.selectTab(Route.Tab.WORLDS)
        navigator.navigateTo(Route.Showcase("pack-neon-district-afterlight"))
        navigator.navigateTo(Route.EnterWorld("pack-neon-district-afterlight"))
        navigator.replaceAll(Route.Stage("story-99"))

        // This is exactly what the Saver writes and reads back.
        val encoded = navigator.backStack.map { it.encode() }
        val decoded = encoded.map { decodeRoute(it) }

        assertEquals(navigator.backStack.toList(), decoded)
        assertEquals(Route.Stage("story-99"), decoded.last())
    }

    @Test
    fun `every route round-trips through its encoding`() {
        val routes = listOf(
            Route.Home,
            Route.Worlds,
            Route.Chat,
            Route.Library,
            Route.Models,
            Route.Settings,
            Route.Developer,
            Route.Authoring,
            Route.Showcase("pack-x"),
            Route.EnterWorld("pack-x"),
            Route.Stage("story-x"),
            Route.ModelDetail("local-x"),
            Route.StoryRecord("story-x"),
        )
        routes.forEach { route ->
            assertEquals(route, decodeRoute(route.encode()))
        }
    }

    @Test
    fun `an unknown encoded route falls back instead of crashing`() {
        // The saved stack can name a screen a *later* build removed. Throwing here would
        // crash on launch for every user who had ever opened it.
        assertNull(decodeRoute("nonsense"))
        assertNull(decodeRoute(null))
        assertNull(decodeRoute(""))
        assertNull(decodeRoute("showcase/"))
        assertNull(decodeRoute("stage/"))
        assertNull(decodeRoute("record/"))
        assertNull(decodeRoute("model/"))
        // An id with extra segments is refused rather than silently truncated, so a route
        // cannot point at a different story than the one that was saved.
        assertNull(decodeRoute("stage/story-1/extra"))
    }

    @Test
    fun `exactly four destinations own a navigation bar`() {
        val withBar = listOf(
            Route.Home,
            Route.Worlds,
            Route.Showcase("pack-x"),
            Route.EnterWorld("pack-x"),
            Route.Chat,
            Route.Library,
            Route.StoryRecord("story-x"),
        ).mapNotNull { it.destinationOrNull() }

        assertEquals(
            "the bar must offer the four primary destinations and nothing else",
            CharalyDestination.PRIMARY.toSet(),
            withBar.toSet(),
        )
        assertEquals(4, CharalyDestination.PRIMARY.size)
    }

    @Test
    fun `the stage is the one primary screen that hides the bar`() {
        // The stage owns the bottom edge because the composer is pinned there, so a floating
        // pill above it is exactly the layout mistake this app replaced. Entering a story
        // *is* leaving the navigation; the stage's own back affordance brings it back.
        assertNull(Route.Stage("story-x").destinationOrNull())
    }

    @Test
    fun `secondary routes hide the navigation entirely`() {
        // Settings and the model hub are tools. A bar on them would give a settings screen
        // the same visual weight as the thing the user came for.
        for (route in listOf(
            Route.Models,
            Route.ModelDetail("local-x"),
            Route.Settings,
            Route.Developer,
            Route.Authoring,
        )) {
            assertNull("${route.encode()} must not draw a navigation bar", route.destinationOrNull())
        }
    }

    @Test
    fun `the showcase reports its parent destination so returning highlights it`() {
        assertEquals(CharalyDestination.WORLDS, Route.Showcase("pack-x").destinationOrNull())
        assertEquals(CharalyDestination.LIBRARY, Route.StoryRecord("story-x").destinationOrNull())
    }

    @Test
    fun `selecting by destination reaches the same place as selecting by tab`() {
        val navigator = CharalyNavigator()
        navigator.navigateTo(Route.Stage("story-1"))

        navigator.select(CharalyDestination.LIBRARY)

        assertEquals(Route.Library, navigator.current)
        assertEquals(1, navigator.backStack.size)
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
    fun `a route naming something deleted still pops cleanly`() {
        // A pack removed by the authoring tool while its showcase sat on the stack, a story
        // removed on the library, a model deleted mid-session. None of these may trap the
        // user: the route decodes, renders an empty state, and back still works.
        val navigator = CharalyNavigator()
        navigator.navigateTo(Route.Showcase("pack-that-was-deleted"))
        assertTrue(navigator.pop())
        assertEquals(Route.Home, navigator.current)

        navigator.navigateTo(Route.Stage("story-that-was-deleted"))
        assertTrue(navigator.pop())

        navigator.navigateTo(Route.ModelDetail("model-that-was-deleted"))
        assertTrue(navigator.pop())
        assertEquals(Route.Home, navigator.current)
    }
}
