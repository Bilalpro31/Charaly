package dev.charaly.app.ui

import dev.charaly.app.ui.nav.CharalyNavigator
import dev.charaly.app.ui.nav.Route
import dev.charaly.app.ui.nav.decodeRoute
import dev.charaly.app.ui.nav.tabOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE DEVELOPER PANEL MUST NOT LEAK INTO A NORMAL SESSION.
 *
 * Hiding the button is not enough, because navigation state is saved: a user can open the
 * panel, turn developer mode off, and have the route still in the restored back stack. That
 * path is exactly the "by accident" case the brief calls out, so it is worth a test even
 * though the real guard lives in `CharalyApp`, where the mode is re-checked at render time.
 *
 * These tests pin the two halves of that guard:
 *
 *  * **the route exists and round-trips** - which is *why* the render-time check is
 *    mandatory rather than defensive;
 *  * **the route owns no destination** - so even when it does render, it cannot hand the
 *    user a navigation bar that leads somewhere they are not allowed to go.
 */
class DeveloperGateTest {

    @Test
    fun `the developer route survives encoding, so a restored stack can contain it`() {
        // This is precisely why the gate has to be enforced at render time: the route
        // round-trips perfectly well and will be restored after a process restart.
        assertEquals(Route.Developer, decodeRoute("developer"))
        assertEquals(Route.Authoring, decodeRoute("authoring"))
    }

    @Test
    fun `a stack that contains the developer panel still pops cleanly`() {
        val navigator = CharalyNavigator()
        navigator.navigateTo(Route.Settings)
        navigator.navigateTo(Route.Developer)
        assertEquals(Route.Developer, navigator.current)
        assertTrue(navigator.pop())
        assertEquals(Route.Settings, navigator.current)
    }

    @Test
    fun `the developer panel and the authoring tool own no destination`() {
        // They are reached from Settings, so drawing a bar on them would let a user leave
        // an engine inspector by tapping "Worlds" and lose their place in the diagnostics.
        assertNull(Route.Developer.tabOrNull())
        assertNull(Route.Authoring.tabOrNull())
    }

    @Test
    fun `the gate is narrow - every other destination stays reachable`() {
        // The check must not be so broad that it hides the app: only these two routes.
        val navigator = CharalyNavigator()
        navigator.navigateTo(Route.Sessions)
        navigator.navigateTo(Route.Library)
        navigator.navigateTo(Route.Stage("story-1"))
        navigator.navigateTo(Route.Settings)

        assertEquals(5, navigator.backStack.size)
        while (navigator.canGoBack) {
            assertTrue(navigator.pop())
        }
        assertEquals(Route.Home, navigator.current)
    }
}
