package dev.charaly.app.ui.nav

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import dev.charaly.app.ui.theme.motionDuration

/**
 * Charaly's navigation.
 *
 * A hand-rolled back stack rather than a navigation library, because the app has
 * one activity, one bottom bar and a handful of destinations. The important
 * property is that it is *saveable*: a screen survives process death, which is what
 * makes "leave the story, come back" feel solid.
 *
 * Routes are data, not strings, so a screen can never navigate to a pack or story
 * that does not exist.
 */
@Stable
sealed interface Route {

    /** The four bottom-navigation destinations. */
    enum class Tab(val label: String) {
        HOME("Home"),
        PACKS("Story Packs"),
        MODELS("Models"),
        SESSIONS("Sessions"),
    }

    data object Home : Route
    data object Library : Route
    data object Models : Route
    data object Sessions : Route

    data class PackDetail(val packId: String) : Route
    data class NewStory(val packId: String) : Route

    /** The immersive story screen. Survives recreation with its streaming state. */
    data class Story(val instanceId: String) : Route

    data class SessionDetail(val instanceId: String) : Route
    data class ModelDetail(val modelId: String) : Route

    data object Settings : Route
    data object Developer : Route
    data object CreatePack : Route

    /**
     * The story's world, as places and people.
     *
     * Reached from the story screen's menu. It is the screen that makes Charaly a world
     * rather than a chat: you look around and walk somewhere, instead of picking a
     * character to talk to.
     */
    data class World(val instanceId: String) : Route

    data class CharacterDetail(val packId: String, val characterId: String) : Route
    data class LocationDetail(val packId: String, val locationId: String) : Route
    data class EditCharacter(val packId: String, val characterId: String) : Route
    data class EditLocation(val packId: String, val locationId: String) : Route
    data class EditEvent(val packId: String, val eventId: String) : Route

    fun encode(): String = when (this) {
        Home -> "home"
        Library -> "library"
        Models -> "models"
        Sessions -> "sessions"
        Settings -> "settings"
        Developer -> "developer"
        CreatePack -> "create-pack"
        is World -> "world/$instanceId"
        is PackDetail -> "pack/$packId"
        is NewStory -> "new-story/$packId"
        is Story -> "story/$instanceId"
        is SessionDetail -> "session/$instanceId"
        is ModelDetail -> "model/$modelId"
        is CharacterDetail -> "character/$packId/$characterId"
        is LocationDetail -> "location/$packId/$locationId"
        is EditCharacter -> "edit-character/$packId/$characterId"
        is EditLocation -> "edit-location/$packId/$locationId"
        is EditEvent -> "edit-event/$packId/$eventId"
    }
}

/** Decoding is total: an unknown string falls back to Home rather than crashing. */
fun decodeRoute(value: String?): Route? = when {
    value == null -> null
    value == "home" -> Route.Home
    value == "library" -> Route.Library
    value == "models" -> Route.Models
    value == "sessions" -> Route.Sessions
    value == "settings" -> Route.Settings
    value == "developer" -> Route.Developer
    value == "create-pack" -> Route.CreatePack
    value.startsWith("world/") -> value.substringAfter("world/").takeIf { it.isNotBlank() }
        ?.let { Route.World(it) }
    value.startsWith("pack/") -> value.substringAfter("pack/").takeIf { it.isNotBlank() }
        ?.let { Route.PackDetail(it) }
    value.startsWith("new-story/") -> value.substringAfter("new-story/").takeIf { it.isNotBlank() }
        ?.let { Route.NewStory(it) }
    value.startsWith("story/") -> value.substringAfter("story/").takeIf { it.isNotBlank() }
        ?.let { Route.Story(it) }
    value.startsWith("session/") -> value.substringAfter("session/").takeIf { it.isNotBlank() }
        ?.let { Route.SessionDetail(it) }
    value.startsWith("model/") -> value.substringAfter("model/").takeIf { it.isNotBlank() }
        ?.let { Route.ModelDetail(it) }
    value.startsWith("character/") -> value.substringAfter("character/")
        .split("/", limit = 2).takeIf { it.size == 2 }?.let { Route.CharacterDetail(it[0], it[1]) }
    value.startsWith("location/") -> value.substringAfter("location/")
        .split("/", limit = 2).takeIf { it.size == 2 }?.let { Route.LocationDetail(it[0], it[1]) }
    value.startsWith("edit-character/") -> value.substringAfter("edit-character/")
        .split("/", limit = 2).takeIf { it.size == 2 }?.let { Route.EditCharacter(it[0], it[1]) }
    value.startsWith("edit-location/") -> value.substringAfter("edit-location/")
        .split("/", limit = 2).takeIf { it.size == 2 }?.let { Route.EditLocation(it[0], it[1]) }
    value.startsWith("edit-event/") -> value.substringAfter("edit-event/")
        .split("/", limit = 2).takeIf { it.size == 2 }?.let { Route.EditEvent(it[0], it[1]) }
    else -> null
}

/** Which bottom-navigation tab a route belongs to, for the bar's selected state. */
fun Route.tabFor(): Route.Tab = when (this) {
    Route.Home -> Route.Tab.HOME
    Route.Library, is Route.PackDetail, is Route.NewStory, is Route.Story, is Route.CreatePack,
    is Route.World, is Route.CharacterDetail, is Route.LocationDetail, is Route.EditCharacter,
    is Route.EditLocation, is Route.EditEvent, Route.Settings, Route.Developer,
    -> Route.Tab.PACKS
    Route.Models, is Route.ModelDetail -> Route.Tab.MODELS
    Route.Sessions, is Route.SessionDetail -> Route.Tab.SESSIONS
}

private fun rootOf(tab: Route.Tab): Route = when (tab) {
    Route.Tab.HOME -> Route.Home
    Route.Tab.PACKS -> Route.Library
    Route.Tab.MODELS -> Route.Models
    Route.Tab.SESSIONS -> Route.Sessions
}

/**
 * The navigation state holder.
 *
 * Deliberately tiny: a stack of routes plus a `tab` for the bar. Every mutation is
 * a function, so the whole thing is testable without a device.
 */
@Stable
class CharalyNavigator(initial: Route = Route.Home) {

    val backStack = mutableStateListOf(initial)

    /** The currently visible route. */
    var current: Route by mutableStateOf(initial)
        private set

    /** True when a back gesture / system back should pop. */
    val canGoBack: Boolean get() = backStack.size > 1

    /** Pushes a screen. */
    fun navigateTo(route: Route) {
        if (route == current) return
        backStack.add(route)
        current = route
    }

    /** Switches the root tab, clearing anything stacked on top of it. */
    fun selectTab(tab: Route.Tab) {
        backStack.clear()
        backStack.add(rootOf(tab))
        current = backStack.last()
    }

    /**
     * Pops one level. Returns false when there is nothing to pop, which is the
     * signal for the activity to finish instead.
     */
    fun pop(): Boolean {
        if (!canGoBack) return false
        backStack.removeAt(backStack.lastIndex)
        current = backStack.last()
        return true
    }

    /** Pops back to the tab root of the current route. */
    fun popToRoot(): Boolean {
        if (backStack.size <= 1) return false
        val root = rootOf(current.tabFor())
        backStack.clear()
        backStack.add(root)
        current = root
        return true
    }

    /**
     * Replaces the whole stack with one route.
     *
     * Used when a flow finishes: "Enter Story" should not leave the wizard behind
     * it when the user presses back.
     */
    fun replaceAll(route: Route) {
        backStack.clear()
        backStack.add(route)
        current = route
    }

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
 * Pushes slide forward and pop back, with a short cross-fade. 220ms is the whole
 * budget: a story app should feel like it is keeping up with you, not animating
 * at you. Because it uses the standard transition primitives, the system's
 * "remove animations" accessibility setting disables it for free.
 */
@Composable
fun RouteHost(
    navigator: CharalyNavigator,
    modifier: Modifier = Modifier,
    content: @Composable (Route) -> Unit,
) {
    val direction = remember { mutableStateOf(1) }
    val rendered = remember { mutableStateOf(navigator.current) }
    val previous = rendered.value
    if (previous != navigator.current) {
        val previousIndex = navigator.backStack.indexOf(previous)
        val currentIndex = navigator.backStack.indexOf(navigator.current)
        direction.value = if (previousIndex < 0 || currentIndex < 0) 1 else {
            if (currentIndex > previousIndex) 1 else -1
        }
        rendered.value = navigator.current
    }
    val forward = direction.value

    // Durations come from the app's motion policy, not from literals here. With "Reduce
    // motion" on (or the system's "Remove animations") these become 0: the screen still
    // changes, it just does not slide. Hard-coded tweens would ignore both settings.
    val enterMs = motionDuration(220)
    val exitMs = motionDuration(200)

    AnimatedContent(
        targetState = navigator.current,
        modifier = modifier,
        transitionSpec = {
            val slide = if (forward > 0) 1 else -1
            (slideInHorizontally(tween(enterMs)) { width -> slide * width / 9 } + fadeIn(tween(enterMs)))
                .togetherWith(
                    slideOutHorizontally(tween(exitMs)) { width -> -slide * width / 11 } + fadeOut(tween(exitMs)),
                )
        },
        label = "screen",
    ) { route ->
        content(route)
    }
}

private typealias SizeTransform = androidx.compose.animation.SizeTransform