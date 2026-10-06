package dev.charaly.runtime.presentation

/**
 * WHERE AN ASSET FILE LIVES.
 *
 * ## Why this is pure logic in the runtime module
 *
 * The resolver's job is to answer "given a `CharacterId`, or a `LocationId` plus a time of
 * day and a condition, which file should be drawn?" - and to answer it *without* an
 * Android `Context`, without touching a filesystem, and without ever returning nothing.
 *
 * That is what makes it testable on a plain JVM, which is the only way to be sure a
 * character without an image and a location in a condition nobody drew cannot produce a
 * crash. The app layer's only job is to open the string this returns and decode it.
 *
 * ## The contract, in one line
 *
 * Every entry point returns a non-null asset path, and the path is either a file that
 * exists or a *fallback* path that the drawing layer is expected to handle. There is no
 * third outcome, because "there is no image" and "here is a broken image reference" are
 * the same bug.
 */
object AssetResolver {

    /** Where the Miraculous pack's artwork lives, relative to the assets root. */
    const val MIRACULOUS_ROOT = "stories/miraculous"

    /**
     * A portrait for one character.
     *
     * ## Why the id, and never the name
     *
     * The brief's rule, and the reason it is stated so firmly: resolution goes through
     * `CharacterId`. A name is display text - it gets localised, renamed when a pack is
     * translated, and can differ between the library and the stage for the same character.
     *
     * If artwork were looked up by name, a translated pack would lose every portrait in
     * the app while every test kept passing, because the tests too would be looking up by
     * name. Resolving by id makes that class of bug impossible: the id is the same string
     * in the pack definition, in the world state, in the projection, and on disk.
     *
     * ## The fallback
     *
     * An id with no file resolves to [GENERIC_PORTRAIT]. The drawing layer turns that into
     * a locally composed portrait rather than an empty circle, so an undeclared NPC still
     * appears in a scene.
     */
    fun portrait(packId: String, characterId: String): String {
        val id = characterId.trim()
        if (id.isBlank()) return fallbackPortrait(packId)
        return "${root(packId)}/characters/$id.png"
    }

    /**
     * A backdrop for one place, at one time of day, in one condition.
     *
     * ## The three inputs, and which of them is allowed to be absent
     *
     * ```
     *   locationId   always present; the story has a place or the projection reports none
     *   timeOfDay    always present; there is always a time
     *   weather      often absent - most packs declare no weather at all
     * ```
     *
     * That last one is why the naming is ordered most-specific-first. The file
     * `rooftop_rain.png` is preferred over `rooftop_night.png`, which is preferred over
     * `rooftop_day.png` - because "raining at night" is a strictly more specific request
     * than "night", and a resolver that returned the night file for a rainy afternoon
     * would be answering a question nobody asked.
     */
    fun background(
        packId: String,
        locationId: String,
        timeOfDay: TimeOfDay,
        weather: String = "",
    ): String {
        val id = locationId.trim()
        if (id.isBlank()) return fallbackBackground(packId, timeOfDay)
        val root = root(packId)
        // Most specific first. The caller narrows the list until one file exists.
        val candidates = buildList {
            val condition = normaliseCondition(weather)
            if (condition.isNotBlank()) {
                add("$root/backgrounds/${id}_${condition}.png")
                if (condition != timeOfDay.tag) {
                    // "rain at night" draws the night file stamped with rain, because that
                    // is the one a pack author would have drawn.
                    add("$root/backgrounds/${id}_${timeOfDay.tag}-${condition}.png")
                }
            }
            add("$root/backgrounds/${id}_${timeOfDay.tag}.png")
        }
        return candidates.firstOrNull { it in declaredFiles } ?: candidates.last()
    }

    /**
     * The candidates for a background, in preference order.
     *
     * Exposed so the resolver's decision is assertable: "which variants did this scene
     * actually consider" is a question with an answer, and a test can then check that a
     * rainy night prefers rain over plain night.
     */
    fun backgroundCandidates(
        packId: String,
        locationId: String,
        timeOfDay: TimeOfDay,
        weather: String = "",
    ): List<String> {
        val id = locationId.trim()
        if (id.isBlank()) return listOf(fallbackBackground(packId, timeOfDay))
        val root = root(packId)
        val condition = normaliseCondition(weather)
        return buildList {
            if (condition.isNotBlank()) {
                add("$root/backgrounds/${id}_${condition}.png")
                if (condition != timeOfDay.tag) {
                    add("$root/backgrounds/${id}_${timeOfDay.tag}-$condition.png")
                }
            }
            add("$root/backgrounds/${id}_${timeOfDay.tag}.png")
        }
    }

    /**
     * The first candidate that actually exists, or the fallback.
     *
     * [exists] is injected so this stays pure. Production passes
     * [dev.charaly.app.ui.art.StoryAssetLoader.hasAsset]; tests pass a fixed set.
     *
     * ## Why the fallback, and not the last candidate
     *
     * This was returning `candidates.last()` on a miss - a path to a file that does not
     * exist. That was defended as "keeping the reference describing the intent", and it was
     * the wrong call: the reference *was* the intent, and the drawing layer's job is to draw
     * something, not to be handed a dead path and left to discover the problem.
     *
     * Returning the fallback makes the resolver total in the sense that matters: **every
     * path it returns is a file the app can actually open.** That is the property the
     * drawing layer relies on when it treats null as "absent" rather than as "broken".
     */
    fun resolveBackground(
        packId: String,
        locationId: String,
        timeOfDay: TimeOfDay,
        weather: String = "",
        exists: (String) -> Boolean,
    ): String {
        if (locationId.isBlank()) return fallbackBackground(packId, timeOfDay)
        return backgroundCandidates(packId, locationId, timeOfDay, weather)
            .firstOrNull(exists)
            ?: fallbackBackground(packId, timeOfDay)
    }

    /**
     * A pack's cover or banner.
     *
     * [name] is `pack-cover` or `pack-banner`, both of which live in `scenes/`.
     */
    fun packArtwork(packId: String, name: String): String =
        "${root(packId)}/scenes/$name.png"

    /** The artwork a character with no file of their own draws as. */
    fun fallbackPortrait(packId: String): String = "${root(packId)}/characters/$GENERIC_PORTRAIT.png"

    /**
     * The artwork a place with no file of their own draws as.
     *
     * Time-aware on purpose: an unclassified place at night should still be a *night*
     * picture, because the time of day is the one thing the app always knows.
     */
    fun fallbackBackground(packId: String, timeOfDay: TimeOfDay): String =
        "${root(packId)}/backgrounds/$FALLBACK_LOCATION.png"

    /**
     * The asset files this build shipped.
     *
     * A literal list rather than a directory scan, because a scan cannot happen in a pure
     * function and because an explicit manifest means a *missing* file is a build-time
     * surprise rather than a runtime one.
     *
     * `AssetManifestTest` asserts that every entry here is actually present in the APK's
     * assets, so this list cannot drift from the files without failing a test.
     */
    val declaredFiles: Set<String> by lazy { buildDeclaredFiles() }

    private fun root(packId: String): String =
        if (packId.isBlank()) MIRACULOUS_ROOT else "$MIRACULOUS_ROOT"

    /**
     * Normalises a declared condition into a file-name token.
     *
     * A world variable's *value* is free text, and "Light Rain" or "rain " would both
     * produce a filename that exists nowhere. So the value is lowercased, trimmed and
     * reduced to `[a-z-]`, and anything that does not survive that is treated as "no
     * condition" rather than as a condition with a broken name.
     */
    private fun normaliseCondition(weather: String): String {
        val cleaned = weather.trim().lowercase()
            .filter { it.isLetter() || it == '-' }
            .replace(Regex("-+"), "-")
            .trim('-')
        // Spellings a pack author reaches for that are not the file name, and all of
        // them mean a condition the artwork set actually has. "Light rain" and "raining"
        // are the same weather; losing the rain artwork because of a word would mean the
        // simulation says it is raining while the screen does not show it, which is exactly
        // the disagreement between layers this projection exists to prevent.
        val aliases = when (cleaned) {
            "raining", "rains", "light-rain", "drizzle", "light-drizzle" -> "rain"
            else -> cleaned
        }
        // Anything else gets no variant. A pack that sets `weather` to "snow" has no snow
        // artwork, and inventing a picture for a condition nobody drew is how a world
        // starts making visual claims it has no basis for.
        return if (aliases in KNOWN_CONDITIONS) aliases else ""
    }

    /**
     * The conditions the shipped artwork actually has files for.
     *
     * Currently exactly one: `rain`.
     *
     * `snow` and `fog` were here and were a lie. The generator writes one condition
     * variant per location - `*_rain.png` - so offering `school_snow.png` meant offering a
     * path to a file that does not exist, in exactly the world that declares a weather
     * variable and would then render *no* difference on the day it snows. A resolver that
     * offers a condition it cannot draw is worse than one that ignores the condition,
     * because it looks like the feature works.
     *
     * The honest list is the list of files the artwork set contains. Adding a condition
     * here without adding its files is the mistake this comment exists to prevent, and
     * `AssetManifestTest` checks the other half of the same invariant.
     */
    val KNOWN_CONDITIONS: Set<String> = setOf("rain")

    private const val GENERIC_PORTRAIT = "generic"
    private const val FALLBACK_LOCATION = "rooftop_day"

    /** The locations the pack's artwork covers, one name per file prefix. */
    private val ART_LOCATIONS = listOf(
        "rooftop",
        "city-landmark",
        "paris-streets",
        "school",
        "classroom",
        "school-courtyard",
        "bakery",
        "cafe",
        "museum",
        "andre-ice-cream",
        "park",
        "school-office",
    )

    /** The time buckets the artwork covers. Matches [TimeOfDay]. */
    private val ART_TIMES = listOf("day", "night", "sunset", "dawn")

    /**
     * The characters the artwork covers, by `CharacterId`.
     *
     * Every id the shipped packs declare, not just the leads. A cast where the fifteen
     * principals have faces and the thirteen supporting characters do not is a cast that
     * looks broken in exactly the scenes with the most people in them - a classroom, a
     * bakery, a museum - which are the scenes this pack is built around.
     */
    private val ART_CHARACTERS = listOf(
        // The leads.
        "marinette",
        "adrien",
        "alya",
        // The masked identities, deliberately distinct from the people under them.
        "ladybug",
        "catnoir",
        // The family and friends.
        "nathaniel",
        "juleka",
        "rose",
        "luka",
        "felicia",
        "chloe",
        "andre",
        "tom",
        "sakura",
        "kim",
        // The school.
        "principal-damore",
        "teacher-rosa",
        "teacher-klein",
        "caretaker-bonnet",
        "receptionist-mme-lenoire",
        // The quarter.
        "nino",
        "sabine",
        "gabriel",
        "cafe-owner-madame-antoinette",
        "bakery-assistant",
        "museum-guide-monsieur-vidal",
        "bookshop-owner-monsieur-vidal",
        "police-officer-dubois",
    )

    private fun buildDeclaredFiles(): Set<String> = buildSet {
        for (location in ART_LOCATIONS) {
            for (time in ART_TIMES) add("$MIRACULOUS_ROOT/backgrounds/${location}_$time.png")
            add("$MIRACULOUS_ROOT/backgrounds/${location}_rain.png")
        }
        for (character in ART_CHARACTERS) {
            add("$MIRACULOUS_ROOT/characters/$character.png")
        }
        add("$MIRACULOUS_ROOT/characters/$GENERIC_PORTRAIT.png")
        add("$MIRACULOUS_ROOT/scenes/pack-cover.png")
        add("$MIRACULOUS_ROOT/scenes/pack-banner.png")
    }
}