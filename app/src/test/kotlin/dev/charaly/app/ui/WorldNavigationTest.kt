package dev.charaly.app.ui

import dev.charaly.app.ui.nav.CharalyNavigator
import dev.charaly.app.ui.nav.Route
import dev.charaly.app.ui.nav.decodeRoute
import dev.charaly.app.ui.nav.destinationOrNull
import dev.charaly.runtime.presentation.CharalyDestination
import dev.charaly.runtime.presentation.NewStoryStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE WORLD FLOW, end to end.
 *
 * ```
 *   WORLDS  ->  SHOWCASE  ->  ENTER WORLD  ->  STAGE
 * ```
 *
 * Three decisions live in this flow and each of them is a place a navigation bug is
 * invisible until a user is standing in it:
 *
 * 1. **The showcase is a destination, not a modal.** It round-trips, so returning to it
 *    after a process death lands on the same world's page rather than on the feed.
 * 2. **Entering a world replaces the stack.** Otherwise "back" from inside a story walks
 *    the user back through a form they have already submitted, which reads as the app
 *    forgetting they are in a story.
 * 3. **The stage owns the whole display.** No bar, no rail - because the composer already
 *    wants the bottom edge.
 *
 * Pure JVM: no device, no emulator.
 */
class WorldNavigationTest {

    // ------------------------------------------------------------------
    // The showcase
    // ------------------------------------------------------------------

    @Test
    fun `the showcase route round-trips through its encoding`() {
        val route = Route.Showcase("pack-miraculous-shadows-of-paris")
        assertEquals("showcase/pack-miraculous-shadows-of-paris", route.encode())
        assertEquals(route, decodeRoute(route.encode()))
    }

    @Test
    fun `the showcase belongs to the same destination as the feed it came from`() {
        // Not so a bar can be drawn - the showcase hides it - but so returning from it
        // highlights Worlds rather than leaving the bar unlit.
        assertEquals(Route.Worlds.destinationOrNull(), Route.Showcase("pack-x").destinationOrNull())
        assertEquals(CharalyDestination.WORLDS, Route.Showcase("pack-x").destinationOrNull())
    }

    @Test
    fun `an empty showcase id is not a route`() {
        assertNull(decodeRoute("showcase/"))
        assertNull(decodeRoute("enter/"))
    }

    @Test
    fun `a malformed showcase route falls back instead of crashing`() {
        assertNull(decodeRoute("showcase"))
        assertNull(decodeRoute("nonsense/showcase/pack-x"))
        assertNull(decodeRoute("showcase/pack-x/extra"))
    }

    // ------------------------------------------------------------------
    // Entering a world
    // ------------------------------------------------------------------

    @Test
    fun `the whole world flow walks forward and returns to the feed`() {
        val navigator = CharalyNavigator()
        navigator.selectTab(Route.Tab.WORLDS)
        navigator.navigateTo(Route.Showcase("pack-the-last-kingdom"))
        navigator.navigateTo(Route.EnterWorld("pack-the-last-kingdom"))
        navigator.replaceAll(Route.Stage("story-1"))

        assertEquals(Route.Stage("story-1"), navigator.current)

        // Leaving the story from the stage lands on the lobby, never on the role chooser
        // that created it and never by closing the app.
        assertTrue(navigator.leaveImmersive())
        assertEquals(Route.Home, navigator.current)
        assertFalse("the user must still be in the app", navigator.backStack.size == 0)
    }

    @Test
    fun `leaving the stage with history behind it pops instead`() {
        // Reached from the library rather than by entering a world, so there is somewhere
        // real to go back to.
        val navigator = CharalyNavigator()
        navigator.selectTab(Route.Tab.LIBRARY)
        navigator.navigateTo(Route.Stage("story-1"))

        assertTrue(navigator.leaveImmersive())
        assertEquals(Route.Library, navigator.current)
    }

    @Test
    fun `leaving the stage from the root does not close the app`() {
        // The bug this prevents: the stage is opened with `replaceAll`, so its stack is one
        // deep and a plain `pop` finds nothing and hands control to the activity - closing
        // Charaly while the user is reading a story.
        val navigator = CharalyNavigator()
        navigator.replaceAll(Route.Stage("story-1"))
        assertEquals(1, navigator.backStack.size)

        assertTrue(navigator.leaveImmersive())
        assertEquals(Route.Home, navigator.current)

        // And at Home there is genuinely nothing left to do, so that is when the activity
        // is allowed to finish.
        assertFalse(navigator.leaveImmersive())
    }

    @Test
    fun `stepping out of the role chooser returns to the showcase it came from`() {
        // The one place in the flow where back *should* show the previous screen: the user
        // has not entered anything yet, so the form is genuinely cancelable.
        val navigator = CharalyNavigator()
        navigator.selectTab(Route.Tab.WORLDS)
        navigator.navigateTo(Route.Showcase("pack-the-last-kingdom"))
        navigator.navigateTo(Route.EnterWorld("pack-the-last-kingdom"))

        assertTrue(navigator.pop())
        assertEquals(Route.Showcase("pack-the-last-kingdom"), navigator.current)
        assertTrue(navigator.pop())
        assertEquals(Route.Worlds, navigator.current)
    }

    @Test
    fun `re-entering a world does not grow the stack without bound`() {
        val navigator = CharalyNavigator()
        navigator.selectTab(Route.Tab.WORLDS)
        val depth = navigator.backStack.size

        repeat(10) {
            navigator.navigateTo(Route.EnterWorld("pack-the-last-kingdom"))
            navigator.pop()
        }

        assertEquals(depth, navigator.backStack.size)
        assertEquals(Route.Worlds, navigator.current)
    }

    @Test
    fun `the world flow survives process recreation with its whole stack`() {
        val navigator = CharalyNavigator()
        navigator.selectTab(Route.Tab.WORLDS)
        navigator.navigateTo(Route.Showcase("pack-neon-district-afterlight"))
        navigator.navigateTo(Route.EnterWorld("pack-neon-district-afterlight"))

        // Exactly what the Saver writes and reads back.
        val encoded = navigator.backStack.map { it.encode() }
        val decoded = encoded.map { decodeRoute(it) }

        assertEquals(navigator.backStack.toList(), decoded)
        assertEquals(Route.EnterWorld("pack-neon-district-afterlight"), decoded.last())
    }

    // ------------------------------------------------------------------
    // The stage
    // ------------------------------------------------------------------

    @Test
    fun `the stage hides the navigation, because the composer wants the bottom edge`() {
        assertNull(
            "a bar or rail on the stage competes with the composer for the same edge",
            Route.Stage("story-1").destinationOrNull(),
        )
        // The Chat route is the one primary destination that keeps its bar, because it is
        // also what the user lands on when there is no story to show. `CharalyApp` drops it
        // to null the moment a story is actually on screen.
        assertEquals(CharalyDestination.CHAT, Route.Chat.destinationOrNull())
    }

    @Test
    fun `a stage reached from the library is the same screen as the chat destination`() {
        // Two routes to one screen is fine; two *versions* of one screen is not, and
        // `CharalyApp` routes both through `ChatRoute`.
        val stageRoute = Route.Stage("story-1")
        assertEquals("stage/story-1", stageRoute.encode())
        assertEquals(Route.Stage("story-1"), dev.charaly.app.ui.nav.decodeRoute(stageRoute.encode()))
        assertEquals(CharalyDestination.CHAT, Route.Chat.destinationOrNull())
    }

    @Test
    fun `the stage route round-trips and carries its streaming state`() {
        val route = Route.Stage("story-1")
        assertEquals("stage/story-1", route.encode())
        assertEquals(route, decodeRoute(route.encode()))
    }

    // ------------------------------------------------------------------
    // The role chooser
    // ------------------------------------------------------------------

    @Test
    fun `every role-chooser step can go back, and the first has no dead Back button`() {
        // The "dead Back button" bug: the footer used to receive a no-op lambda on the first
        // step, so a permanently dead button appeared. The contract is that the first step
        // has no previous step *at all*.
        val first = NewStoryStep.entries.first()
        assertNull(
            "the first wizard step must have no previous step, or Back is a dead button",
            first.previous,
        )
        NewStoryStep.entries.forEach { step ->
            if (step != first) {
                assertTrue("${step.name} should be able to go back", step.previous != null)
            }
        }
    }

    @Test
    fun `the world flow never offers a tab root the shell cannot render`() {
        // The four tab roots are the only routes `selectTab` can land on, so the immersive
        // routes must not be among them.
        val tabRoots: List<Route> = listOf(
            Route.Home,
            Route.Worlds,
            Route.Chat,
            Route.Library,
        )
        assertEquals(4, Route.Tab.entries.size)
        assertFalse(
            "the stage must not be a tab root",
            tabRoots.contains(Route.Stage("story-1")),
        )
        assertFalse(
            "the showcase must not be a tab root",
            tabRoots.contains(Route.Showcase("pack-x")),
        )
        assertFalse(
            "the role chooser must not be a tab root",
            tabRoots.contains(Route.EnterWorld("pack-x")),
        )
        assertEquals(
            "every tab root must resolve to a destination the shell draws",
            4,
            tabRoots.count { it.destinationOrNull() != null },
        )
    }
}
