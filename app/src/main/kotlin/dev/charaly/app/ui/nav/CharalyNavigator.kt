package dev.charaly.app.ui.nav

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.charaly.app.ui.design.Charaly
import dev.charaly.app.ui.theme.motionDuration
import dev.charaly.runtime.presentation.CharalyDestination

/**
 * NAVIGATION.
 *
 * ## Four destinations, and what is deliberately absent
 *
 * ```
 *   HOME      the world lobby
 *   WORLDS    discovery
 *   CHAT      the stage
 *   LIBRARY   what you have made
 * ```
 *
 * Settings is not one of them. In a consumer app a settings destination occupies the same
 * visual weight as the thing the user came for, and this user came for a world. Settings is
 * a secondary route from Home.
 *
 * Models is also secondary, and deliberately so: it is a real screen with real downloads,
 * but it is a *tool* for making the other four work, not a place to spend an evening.
 *
 * ## Routes are data, not strings
 *
 * A route carries its id as a typed field, so a screen cannot navigate to a world or a
 * story that does not exist, and a saved back stack round-trips exactly. `decodeRoute`
 * returns null for an unrecognised string rather than throwing, which means a saved stack
 * written by a *different* build of the app restores to Home instead of crashing on launch.
 */
@Stable
sealed interface Route {

    /** The four primary destinations. */
    enum class Tab(val destination: CharalyDestination) {
        HOME(CharalyDestination.HOME),
        WORLDS(CharalyDestination.WORLDS),
        CHAT(CharalyDestination.CHAT),
        LIBRARY(CharalyDestination.LIBRARY),
    }

    // ---- the four primaries ---------------------------------------------

    data object Home : Route

    /** Discovery. The world's own name, not "Story Packs". */
    data object Worlds : Route

    /** The stage for the currently open story. */
    data object Chat : Route

    /** Every story on this device. */
    data object Library : Route

    // ---- worlds ----------------------------------------------------------

    /**
     * A world's showcase.
     *
     * The storefront: artwork, a premise, two or three hooks, and one invitation. It cannot
     * render a roster even by accident, because [dev.charaly.runtime.presentation.PackShowcase]
     * has no field for one.
     */
    data class Showcase(val packId: String) : Route

    /** Choosing a role and an opening, on the way into a world. */
    data class EnterWorld(val packId: String) : Route

    // ---- the stage -------------------------------------------------------

    /** A specific story. Carries a streaming state through process death. */
    data class Stage(val instanceId: String) : Route

    // ---- secondary -------------------------------------------------------

    data object Models : Route
    data class ModelDetail(val modelId: String) : Route
    data object Settings : Route
    data object Developer : Route
    data object Authoring : Route

    /**
     * The character card import flow: pick, read, preview, confirm.
     *
     * Its own route because the preview needs a full screen, not a dialog. A card can carry
     * a description, a personality, a scenario, a greeting, example dialogue and a dozen
     * lore entries, and a user deciding whether to keep a character is entitled to see the
     * lot before anything is written to their library.
     */
    data object CharacterImport : Route

    /** What Charaly remembers about one installed model. */
    data class StoryRecord(val instanceId: String) : Route

    fun encode(): String = when (this) {
        Home -> "home"
        Worlds -> "worlds"
        Chat -> "chat"
        Library -> "library"
        Models -> "models"
        Settings -> "settings"
        Developer -> "developer"
        Authoring -> "authoring"
        CharacterImport -> "characters/import"
        is Showcase -> "showcase/$packId"
        is EnterWorld -> "enter/$packId"
        is Stage -> "stage/$instanceId"
        is ModelDetail -> "model/$modelId"
        is StoryRecord -> "record/$instanceId"
    }
}

/**
 * Decoding is total: an unrecognised string yields null rather than throwing.
 *
 * Null means "this build no longer has that route", and the honest response is to land on
 * Home. The alternative - throwing - would crash on launch for every user whose saved stack
 * mentioned a screen a later build removed, which is the worst possible failure and the most
 * likely one.
 */
fun decodeRoute(value: String?): Route? {
    if (value == null || value.isBlank()) return null
    return when {
        value == "home" -> Route.Home
        value == "worlds" -> Route.Worlds
        value == "chat" -> Route.Chat
        value == "library" -> Route.Library
        value == "models" -> Route.Models
        value == "settings" -> Route.Settings
        value == "developer" -> Route.Developer
        value == "authoring" -> Route.Authoring
        value == "characters/import" -> Route.CharacterImport
        value.startsWith("showcase/") -> value.id("showcase/")?.let { Route.Showcase(it) }
        value.startsWith("enter/") -> value.id("enter/")?.let { Route.EnterWorld(it) }
        value.startsWith("stage/") -> value.id("stage/")?.let { Route.Stage(it) }
        value.startsWith("model/") -> value.id("model/")?.let { Route.ModelDetail(it) }
        value.startsWith("record/") -> value.id("record/")?.let { Route.StoryRecord(it) }
        else -> null
    }
}

/** The id after a prefix, or null when the suffix is empty or has extra segments. */
private fun String.id(prefix: String): String? =
    substringAfter(prefix)
        .takeIf { it.isNotBlank() && '/' !in it }

/**
 * Which primary destination a route belongs to, or null when it is full-screen.
 *
 * A null answer is what *hides* the navigation, so it is a real design decision rather than
 * an absence.
 *
 * ## The stage is the one primary screen that hides it
 *
 * `[Stage]` returns null, and that is the load-bearing exception in this table. The stage
 * owns the bottom edge because the composer is pinned there, and a floating nav pill above
 * the composer is precisely the layout mistake this app replaced: two navigation surfaces
 * competing for the same attention. So entering a story *is* leaving the navigation, and
 * the stage's own back affordance is how you come back - see `Route.Stage.backTarget`.
 *
 * `Chat` still returns `CHAT`, because the CHAT route is also what the user lands on when
 * no story is open, and a destination with no navigation cannot be switched away from.
 * `CharalyApp` therefore resolves Chat's destination from the *stage*, not from the route
 * alone, so the pill disappears the moment a story is actually on screen.
 */
fun Route.destinationOrNull(): CharalyDestination? = when (this) {
    Route.Home -> CharalyDestination.HOME
    Route.Worlds, is Route.Showcase, is Route.EnterWorld -> CharalyDestination.WORLDS
    Route.Chat -> CharalyDestination.CHAT
    is Route.Stage -> null
    Route.Library, is Route.StoryRecord -> CharalyDestination.LIBRARY
    // The import flow is full-screen like Settings and Authoring: it is a tool the user
    // steps into rather than a tab, and it hides the navigation for the same reason they do.
    Route.Models, is Route.ModelDetail, Route.Settings, Route.Developer, Route.Authoring,
    Route.CharacterImport -> null
}

private fun rootOf(tab: Route.Tab): Route = when (tab) {
    Route.Tab.HOME -> Route.Home
    Route.Tab.WORLDS -> Route.Worlds
    Route.Tab.CHAT -> Route.Chat
    Route.Tab.LIBRARY -> Route.Library
}

/**
 * The navigation state holder.
 *
 * A hand-rolled back stack rather than a navigation library, because the app has one
 * activity and a dozen destinations, and because a saveable back stack is the property that
 * matters: a screen must survive process death, which is what makes "leave the story, come
 * back" feel solid.
 *
 * Every mutation is a function, so the whole thing is testable without a device - which is
 * where the interesting bugs are.
 */
@Stable
class CharalyNavigator(initial: Route = Route.Home) {

    val backStack = mutableStateListOf(initial)

    /** The route currently on screen. */
    var current: Route by mutableStateOf(initial)
        private set

    /** Whether a back gesture should pop rather than leave the app. */
    val canGoBack: Boolean get() = backStack.size > 1

    /** Pushes a screen. Navigating to the current route is a no-op. */
    fun navigateTo(route: Route) {
        if (route == current) return
        backStack.add(route)
        current = route
    }

    /**
     * Switches destination, clearing anything stacked on the previous one.
     *
     * Clearing rather than preserving is the right behaviour for a four-item navigation:
     * a user tapping "Worlds" from deep inside a model detail screen wants the worlds, not
     * the world's detail with a fresh title.
     */
    fun selectTab(tab: Route.Tab) {
        val root = rootOf(tab)
        if (current == root && backStack.size == 1) return
        backStack.clear()
        backStack.add(root)
        current = root
    }

    /** Selects by destination, which is what the navigation bar actually holds. */
    fun select(destination: CharalyDestination) {
        Route.Tab.entries.firstOrNull { it.destination == destination }?.let(::selectTab)
    }

    /** Pops one level. False means the activity should finish. */
    fun pop(): Boolean {
        if (!canGoBack) return false
        backStack.removeAt(backStack.lastIndex)
        current = backStack.last()
        return true
    }

    /** Pops back to the destination's root. */
    fun popToRoot(): Boolean {
        if (backStack.size <= 1) return false
        val root = Route.Tab.entries
            .firstOrNull { it.destination == current.destinationOrNull() }
            ?.let(::rootOf)
            ?: Route.Home
        backStack.clear()
        backStack.add(root)
        current = root
        return true
    }

    /**
     * Leaves a full-screen destination that owns the whole display.
     *
     * The stage is opened with [replaceAll], so its back stack is one deep and a plain
     * [pop] would fall through to the activity and close the app - which is exactly what
     * should *not* happen when a user presses back inside a story they are reading.
     *
     * So: pop when there is history to pop, and otherwise go somewhere sensible rather than
     * nowhere. Home, because the stage is where a story begins and ends, and the lobby is
     * the one screen guaranteed to be about it.
     *
     * Returns false only when there is genuinely nothing to do, which is the signal for the
     * caller to let the activity finish.
     */
    fun leaveImmersive(fallback: Route = Route.Home): Boolean {
        if (canGoBack) return pop()
        if (current == fallback) return false
        replaceAll(fallback)
        return true
    }

    /**
     * Replaces the whole stack with one route.
     *
     * Used when a flow finishes: entering a world should not leave the role chooser behind
     * it when the user presses back, because "back" from inside a story means "leave the
     * story", not "return to the form I filled in".
     */
    fun replaceAll(route: Route) {
        backStack.clear()
        backStack.add(route)
        current = route
    }

    /** Clears back to a root without changing destination. */
    fun resetTo(route: Route) = replaceAll(route)

    companion object {
        /** Persists the whole stack, so a restored app lands where the user left. */
        val Saver: Saver<CharalyNavigator, List<String>> = Saver(
            save = { navigator -> navigator.backStack.map { route -> route.encode() } },
            restore = { saved ->
                CharalyNavigator(decodeRoute(saved.firstOrNull()) ?: Route.Home).apply {
                    saved.drop(1).forEach { value ->
                        decodeRoute(value)?.let { backStack.add(it) }
                    }
                    current = backStack.last()
                }
            },
        )
    }
}

/** Remembers a navigator across recreation, keeping the whole stack. */
@Composable
fun rememberNavigator(initial: Route = Route.Home): CharalyNavigator =
    rememberSaveable(saver = CharalyNavigator.Saver) { CharalyNavigator(initial) }

/**
 * Renders [content] for the current route.
 *
 * ## The transition is a cross-fade with a small scale, and nothing else
 *
 * The previous build slid horizontally by a ninth of the screen width. That reads as a
 * *page*, which is correct for a document reader and wrong for a world: entering Paris is
 * not turning a page.
 *
 * So: a cross-fade plus a 2% scale, in [Charaly.timing.standard]. Story entry uses
 * [cinematic] instead, because entering a world is the one moment in the product that earns
 * a longer budget.
 *
 * Because these are the standard transition primitives, the system's "Remove animations"
 * accessibility setting disables them for free - and the explicit `motionDuration` calls
 * make the in-app switch work as well.
 */
@Composable
fun RouteHost(
    navigator: CharalyNavigator,
    modifier: Modifier = Modifier,
    cinematic: Boolean = false,
    content: @Composable (Route) -> Unit,
) {
    val enterMs = motionDuration(
        if (cinematic) Charaly.timing.cinematic else Charaly.timing.standard,
    )
    val exitMs = motionDuration(if (cinematic) Charaly.timing.quick else Charaly.timing.standard)

    AnimatedContent(
        targetState = navigator.current,
        modifier = modifier,
        transitionSpec = {
            (fadeIn(tween(enterMs)) + scaleIn(tween(enterMs), initialScale = 0.98f))
                .togetherWith(fadeOut(tween(exitMs)))
        },
        label = "screen",
    ) { route ->
        content(route)
    }
}