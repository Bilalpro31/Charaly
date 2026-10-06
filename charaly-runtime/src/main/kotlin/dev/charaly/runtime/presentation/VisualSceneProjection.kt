package dev.charaly.runtime.presentation

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.Location
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.StoryTime
import dev.charaly.runtime.domain.VisualAsset
import dev.charaly.runtime.domain.VisualAssetType
import dev.charaly.runtime.domain.WorldDefinition
import dev.charaly.runtime.engine.EventEngine

/**
 * WHAT THE SCENE LOOKS LIKE.
 *
 * ## The shape of this projection
 *
 * ```
 *   WorldState + current Scene + Location + WorldVariable(weather) + WorldClock
 *        -> VisualSceneProjection
 *             -> background, atmosphere seed, character portraits
 * ```
 *
 * It is a pure function of state. It cannot change world state, cannot read the clock on the
 * wall, cannot read prose, and cannot ask the model what the weather is doing. Everything it
 * decides, the engine already knows.
 *
 * ## Why this is not in the engine
 *
 * Nothing here is simulation. The engine decides *what time it is* and *what the weather
 * is*; this decides *which drawing to use*. Putting the second in the first would mean a
 * visual preference could be mistaken for a world fact, and a visual projection could end
 * up in a world mutation - which is the boundary this codebase protects everywhere else.
 *
 * ## What it is allowed to vary on, and why
 *
 * Two things, both authoritative:
 *
 *  * **time of day**, from [dev.charaly.runtime.domain.WorldClock];
 *  * **weather**, but only when the pack declared a weather world variable. If no pack has
 *    one, there is no weather and no weather variant - the background is simply the place.
 *
 * Explicitly *not* a source of variation: the model's prose. A character saying "it is
 * raining" does not make it rain, and it does not change the wallpaper. The engine's own
 * rules decide whether rain exists in this world at all.
 */
data class VisualSceneProjection(
    /** The backdrop for the scene being played. Never blank; always drawable. */
    val background: ResolvedVisual,
    /** Stable seed for the locally drawn composition, for cache keys and for fallbacks. */
    val atmosphereSeed: String,
    /** Where the scene is. Empty when the story has no resolvable location yet. */
    val locationId: String = "",
    val locationName: String = "",
    /**
     * The place's own declared tags, read from the pack.
     *
     * Carried rather than looked up in the UI so that "what kind of place is this" is
     * answered once, from pack data, and the drawing is the only thing downstream. A screen
     * cannot guess a location's nature from its name.
     */
    val locationTags: List<String> = emptyList(),
    /** The time bucket the background was chosen for. */
    val timeOfDay: TimeOfDay = TimeOfDay.DAY,
    /** The declared condition, or empty when the world has none. */
    val weather: String = "",
    /**
     * The tags the chosen background was selected by, e.g. "night,rain".
     *
     * Carried so the projection is assertable in a test: "which variants did this scene
     * actually use" is a question with an answer here and nowhere else.
     */
    val appliedVariants: List<String> = emptyList(),
) {
    /** The scene's key for caching. Two states with the same key draw identically. */
    val cacheKey: String
        get() = buildString {
            append(locationId.ifBlank { "nowhere" })
            append('@')
            append(timeOfDay.name.lowercase())
            if (weather.isNotBlank()) append('/').append(weather)
        }

    /** Whether there is a real location behind this scene. */
    val hasLocation: Boolean get() = locationId.isNotBlank()

    companion object {
        /**
         * The world variable a pack sets to declare weather.
         *
         * A named flag rather than a heuristic over every variable, so a world with a
         * variable called "season" does not acquire a rain background.
         */
        const val WEATHER_VARIABLE = "weather"

        fun project(
            instance: StoryInstance,
            definition: WorldDefinition,
            pack: StoryPack? = null,
        ): VisualSceneProjection {
            val locationId = resolveLocation(instance)
            // `LocationId` rejects a blank id, and "nobody is anywhere" is a real state - a
            // story whose characters have not been placed yet, or a pack that defines no
            // places at all. Constructing the id unconditionally made the stage throw here,
            // which is exactly the kind of failure a projection must never cause.
            val location = locationId.takeIf { it.isNotBlank() }
                ?.let { definition.location(LocationId(it)) }
            val timeOfDay = TimeOfDay.of(instance.worldClock.now)
            val weather = weatherOf(instance)
            val tags = buildList {
                add(timeOfDay.tag)
                weather.takeIf { it.isNotBlank() }?.let(::add)
            }
            val seed = "scene-${locationId.ifBlank { instance.id.value }}-${tags.joinToString("-")}"
            return VisualSceneProjection(
                background = sceneBackdrop(locationId, location, tags, seed),
                atmosphereSeed = seed,
                locationId = locationId,
                locationName = location?.name.orEmpty(),
                locationTags = location?.tags.orEmpty(),
                timeOfDay = timeOfDay,
                weather = weather,
                appliedVariants = tags.filter { it.isNotBlank() },
            )
        }

        /**
         * Where the player actually is.
         *
         * The engine's own answer rather than the scene's id, so a background follows the
         * character who moved rather than the scene that was opened first.
         */
        private fun resolveLocation(instance: StoryInstance): String =
            EventEngine.currentPlayerLocation(instance)?.value
                ?: instance.currentLocation()?.id?.value
                ?: ""

        /**
         * The weather the *world* says it is.
         *
         * Read from a declared world variable. Absent means absent: there is no default of
         * "clear", because a default would put a dry daytime background on a world that
         * simply never said anything about weather.
         */
        private fun weatherOf(instance: StoryInstance): String =
            instance.worldState.variables[WEATHER_VARIABLE]?.value?.trim()?.lowercase().orEmpty()

        /**
         * The backdrop, chosen by variant.
         *
         * Order of preference: an asset tagged with the full condition list, then each tag
         * on its own, then any untagged asset, then a generated composition. A pack that
         * declares nothing still renders a real picture rather than a hole.
         */
        private fun sceneBackdrop(
            locationId: String,
            location: Location?,
            tags: List<String>,
            seed: String,
        ): ResolvedVisual {
            val declared = location?.visualAssets.orEmpty()
                .filter { it.type == VisualAssetType.BACKGROUND_IMAGE || it.type == VisualAssetType.SCENE_IMAGE }
            val preferred = variantOrder(tags)
                .firstNotNullOfOrNull { tag -> declared.firstOrNull { it.variant.equals(tag, ignoreCase = true) } }
                ?: declared.firstOrNull { it.variant.isBlank() }
            return VisualResolver.sceneBackdrop(
                locationId = locationId,
                declared = preferred,
                seed = seed,
            )
        }

        /**
         * Which tag wins when several are live.
         *
         * Most specific first: a background tagged "night,rain" beats one tagged "night".
         * The order is a *preference between authored assets*, not a guess about the world -
         * if the pack declared no such asset, the next candidate is simply used.
         */
        private fun variantOrder(tags: List<String>): List<String> {
            val specific = tags.filter { it.isNotBlank() }
            return if (specific.size > 1) {
                listOf(specific.joinToString(",")) + specific
            } else {
                specific
            }
        }
    }
}

/**
 * One character's picture for this scene.
 *
 * Resolved by [CharacterId] - the runtime identity - never by name. Name matching is how
 * "Adrien" and "Adrien Agreste" end up rendering as two different people, and it breaks
 * the moment a pack renames a character or a user imports one whose name collides with
 * another.
 */
fun VisualSceneProjection.portraitOf(
    characterId: CharacterId,
    definition: WorldDefinition,
): ResolvedVisual {
    val character = definition.character(characterId)
    return VisualResolver.character(character ?: return genericPortrait(characterId), portrait = true)
        .copy(key = "portrait:${characterId.value}")
}

/** A portrait for someone the world definition does not describe. Never blank. */
private fun genericPortrait(id: CharacterId): ResolvedVisual = VisualResolver.resolveFor(
    key = "portrait:${id.value}",
    type = VisualAssetType.CHARACTER_PORTRAIT,
    declared = null,
    seed = id.value,
)

/**
 * Time of day, in four buckets.
 *
 * Four rather than twenty-four because a background does not need to know the minute, and
 * because a pack author has four images to provide rather than twenty-four. The boundaries
 * are the ones a story clock would plausibly care about: dawn, daylight, dusk, night.
 */
enum class TimeOfDay(val tag: String) {
    DAWN("dawn"),
    DAY("day"),
    SUNSET("sunset"),
    NIGHT("night"),
    ;

    companion object {
        /** Buckets [time] from the story clock. Deterministic, and never guesses. */
        fun of(time: StoryTime): TimeOfDay = when (time.hour) {
            in 5..7 -> DAWN
            in 8..16 -> DAY
            in 17..19 -> SUNSET
            else -> NIGHT
        }
    }
}

/** The first declared asset matching [variant], or null. Used by pack tooling and tests. */
internal fun List<VisualAsset>.firstWithVariant(variant: String): VisualAsset? =
    firstOrNull { it.variant.equals(variant, ignoreCase = true) }