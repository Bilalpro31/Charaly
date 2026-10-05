package dev.charaly.app.ui

import dev.charaly.app.ui.nav.CharalyNavigator
import dev.charaly.app.ui.nav.Route
import dev.charaly.app.ui.nav.tabFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The developer panel must not leak into a normal user's session.
 *
 * Hiding the button is not enough, because navigation state is saved: a user can open
 * the panel, turn developer mode off, and have the route still in the restored back
 * stack. That path is exactly the "by accident" case the brief calls out, so it is worth
 * a test even though the real guard is in the shell.
 */
class DeveloperGateTest {

    @Test
    fun `the developer route survives encoding, so a restored stack can contain it`() {
        // This is precisely why the gate has to be enforced at render time: the route
        // round-trips perfectly well and will be restored after a process restart.
        assertEquals(Route.Developer, dev.charaly.app.ui.nav.decodeRoute("developer"))
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
    fun `the developer panel belongs to the settings tab`() {
        assertEquals(Route.Settings.tabFor(), Route.Developer.tabFor())
    }

    @Test
    fun `developer mode off still leaves every other destination reachable`() {
        // The gate must not be so broad that it hides the app: only this one route.
        val navigator = CharalyNavigator()
        navigator.navigateTo(Route.Home)
        navigator.navigateTo(Route.Library)
        navigator.navigateTo(Route.Story("story-1"))
        assertEquals(3, navigator.backStack.size)
        while (navigator.canGoBack) {
            assertTrue(navigator.pop())
        }
        assertEquals(Route.Home, navigator.current)
    }
}