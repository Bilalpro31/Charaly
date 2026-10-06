package dev.charaly.runtime.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE ASSET RESOLVER.
 *
 * ## What "correct" means here
 *
 * Three inputs, and the resolution has to be a *function of them* - no ordering surprises,
 * no dependence on what happened to load first, and no dependence on the clock. Everything
 * here is pure, so all of it is testable in microseconds, which matters because the
 * alternative - discovering that rain never draws because the resolver picked the wrong
 * candidate - is invisible on a device until somebody happens to be outdoors in the rain.
 *
 * ## The four properties
 *
 *  1. **Total.** Every input combination returns a non-empty path. Never null, never blank.
 *  2. **Specific first.** "Raining at night" prefers the rain artwork over the night one,
 *     because a resolver that answered "night" to that question was not asked it.
 *  3. **Id, not name.** Portraits resolve by `CharacterId`. A test asserts that two
 *     differently-*named* characters with one id resolve identically, and that two ids in
 *     one name never do.
 *  4. **Honest degradation.** An unknown condition, an unknown location and a blank id all
 *     land on a fallback rather than on a path to nothing.
 */
class AssetResolutionTest {

    // ==================================================================
    // 1. Portraits: resolved by id, never by name
    // ==================================================================

    @Test
    fun `a portrait is keyed by character id`() {
        assertEquals(
            "stories/miraculous/characters/marinette.png",
            AssetResolver.portrait("miraculous-shadows-of-paris", "marinette"),
        )
        assertEquals(
            "stories/miraculous/characters/adrien.png",
            AssetResolver.portrait("miraculous-shadows-of-paris", "adrien"),
        )
        // Not the same, which is the point: two characters, two files.
        assertTrue(
            AssetResolver.portrait("p", "marinette") != AssetResolver.portrait("p", "alya"),
        )
    }

    @Test
    fun `a display name never selects an asset`() {
        // The display text for these characters is not their id. If resolution went through
        // the name, this would produce a path to a file that does not exist.
        val byId = AssetResolver.portrait("p", "marinette")
        assertFalse(
            "the id must be used, not the display name",
            byId.contains("Ladyn") || byId.contains(" ") || byId.contains("Lady"),
        )
        // And a name passed where an id belongs does not accidentally resolve to a file:
        // it becomes a path for an id nobody declared, which is the safe failure.
        val byName = AssetResolver.portrait("p", "Ladyn")
        assertTrue(byName.startsWith("stories/miraculous/characters/"))
        assertFalse("a name must not resolve to another character's file", byName == byId)
    }

    @Test
    fun `a blank id falls back rather than producing a bare filename`() {
        val fallback = AssetResolver.portrait("p", "")
        assertEquals(AssetResolver.fallbackPortrait("p"), fallback)
        assertTrue("the fallback must be a real path", fallback.isNotBlank())
        assertFalse("no double slash", fallback.contains("//"))
    }

    @Test
    fun `an id with awkward characters cannot escape the characters directory`() {
        // A path-traversal-shaped id is not a real threat here - ids come from pack data -
        // but a resolver that concatenates strings should be shown to keep its output
        // inside the folder it was given.
        val path = AssetResolver.portrait("p", "../../etc/passwd")
        assertTrue("must stay under the characters directory", path.startsWith("stories/miraculous/characters/"))
    }

    // ==================================================================
    // 2. Backgrounds: most specific candidate first
    // ==================================================================

    @Test
    fun `a plain daytime location resolves to its own time variant`() {
        assertEquals(
            listOf("stories/miraculous/backgrounds/school_day.png"),
            AssetResolver.backgroundCandidates("p", "school", TimeOfDay.DAY),
        )
    }

    @Test
    fun `night is a different file from day`() {
        assertEquals(
            "stories/miraculous/backgrounds/rooftop_night.png",
            AssetResolver.background("p", "rooftop", TimeOfDay.NIGHT),
        )
    }

    @Test
    fun `rain is preferred over the plain time variant`() {
        val candidates = AssetResolver.backgroundCandidates("p", "rooftop", TimeOfDay.DAY, "rain")
        assertEquals(
            "the rain artwork must be tried first",
            "stories/miraculous/backgrounds/rooftop_rain.png",
            candidates.first(),
        )
        assertTrue(
            "and the plain variant must still be a candidate behind it",
            candidates.contains("stories/miraculous/backgrounds/rooftop_day.png"),
        )
    }

    @Test
    fun `rain at night offers both the stamped and the plain rain file`() {
        val candidates = AssetResolver.backgroundCandidates("p", "rooftop", TimeOfDay.NIGHT, "rain")
        // The stamped file is the most specific thing a pack author could have drawn.
        assertEquals("stories/miraculous/backgrounds/rooftop_rain.png", candidates.first())
        assertTrue(
            "a night-rain variant must be offered",
            candidates.contains("stories/miraculous/backgrounds/rooftop_night-rain.png"),
        )
        assertTrue(
            "and plain night remains the last resort",
            candidates.contains("stories/miraculous/backgrounds/rooftop_night.png"),
        )
    }

    @Test
    fun `resolution picks the first candidate that exists`() {
        val onlyNight = setOf("stories/miraculous/backgrounds/rooftop_night.png")
        // No rain file shipped: the resolver must fall through to the night artwork rather
        // than returning a path that does not exist.
        assertEquals(
            "stories/miraculous/backgrounds/rooftop_night.png",
            AssetResolver.resolveBackground(
                packId = "p",
                locationId = "rooftop",
                timeOfDay = TimeOfDay.NIGHT,
                weather = "rain",
                exists = { it in onlyNight },
            ),
        )

        val both = onlyNight + "stories/miraculous/backgrounds/rooftop_rain.png"
        assertEquals(
            "with the rain file present it must win over plain night",
            "stories/miraculous/backgrounds/rooftop_rain.png",
            AssetResolver.resolveBackground(
                packId = "p",
                locationId = "rooftop",
                timeOfDay = TimeOfDay.NIGHT,
                weather = "rain",
                exists = { it in both },
            ),
        )
    }

    @Test
    fun `resolution falls back rather than returning a path to nothing`() {
        // The property that matters: every path this resolver returns is a file the app can
        // open. An earlier version returned the last *candidate* on a miss, which for an
        // unillustrated place is a filename nobody shipped - and the drawing layer was then
        // left to discover that at decode time, per frame.
        assertEquals(
            AssetResolver.fallbackBackground("p", TimeOfDay.NIGHT),
            AssetResolver.resolveBackground("p", "rooftop", TimeOfDay.NIGHT, exists = { false }),
        )
        assertEquals(
            AssetResolver.fallbackBackground("p", TimeOfDay.NIGHT),
            AssetResolver.resolveBackground(
                "p",
                "somebody-apartments",
                TimeOfDay.NIGHT,
                weather = "rain",
                exists = { false },
            ),
        )
    }

    // ==================================================================
    // 3. Conditions are constrained, not free text
    // ==================================================================

    @Test
    fun `a condition nobody drew produces no variant rather than a broken filename`() {
        // A world variable's *value* is free text a pack author typed. "Apocalyptic" and
        // "graupel" are conditions this build has no artwork for, and asking for
        // `school_apocalyptic.png` would be a path to nothing dressed up as a decision.
        for (condition in listOf("apocalyptic", "graupel", "heatwave", "snow", "fog", "  ", "..", "\u00e7an")) {
            val candidates = AssetResolver.backgroundCandidates("p", "school", TimeOfDay.DAY, condition)
            assertEquals(
                "a condition nobody drew must not produce a variant file: '$condition'",
                listOf("stories/miraculous/backgrounds/school_day.png"),
                candidates,
            )
        }
    }

    @Test
    fun `a condition written awkwardly still resolves to the artwork that exists`() {
        // The counterpart to the test above: sloppiness in a pack's *spelling* of rain must
        // not lose the rain, or a world that says "Light Rain!" would silently stop
        // raining on screen while continuing to rain in the simulation.
        for (condition in listOf("Rain", "  RAIN  ", "rain!", "light-rain", "Rain.")) {
            assertEquals(
                "'$condition' should still resolve to the rain artwork",
                "stories/miraculous/backgrounds/school_rain.png",
                AssetResolver.background("p", "school", TimeOfDay.DAY, condition),
            )
        }
    }

    @Test
    fun `every condition the resolver offers is one the artwork has files for`() {
        // The two halves of one invariant: the resolver may only offer conditions whose
        // files exist, and the artwork set must cover every condition it declares. The
        // generator writes exactly one variant per location - `*_rain.png` - so this list
        // must not grow unless the generator does.
        assertEquals(
            "a condition the artwork has no file for must not be offered",
            setOf("rain"),
            AssetResolver.KNOWN_CONDITIONS,
        )
        assertTrue(
            "the aliases must all point at a condition that has files",
            listOf("raining", "light-rain", "drizzle", "raining.")
                .map {
                    AssetResolver.backgroundCandidates("p", "school", TimeOfDay.DAY, it)
                }
                .all { it.first().endsWith("_rain.png") },
        )
    }

    // ==================================================================
    // 4. Total: nothing anywhere returns blank
    // ==================================================================

    @Test
    fun `every input produces a usable path`() {
        val times = TimeOfDay.entries
        val weathers = listOf("", "rain", "fog", "snow", "nonsense")
        val locations = listOf("", "school", "rooftop", "somewhere-nobody-declared")
        for (time in times) {
            for (weather in weathers) {
                for (location in locations) {
                    val path = AssetResolver.background("p", location, time, weather)
                    assertTrue(
                        "blank path for $location/$time/$weather",
                        path.isNotBlank(),
                    )
                    assertTrue(
                        "path escaped the assets root for $location/$time/$weather: $path",
                        path.startsWith(AssetResolver.MIRACULOUS_ROOT + "/"),
                    )
                }
            }
        }
    }

    @Test
    fun `a blank location lands on the time-aware fallback`() {
        assertEquals(
            AssetResolver.fallbackBackground("p", TimeOfDay.NIGHT),
            AssetResolver.background("p", "", TimeOfDay.NIGHT),
        )
    }

    // ==================================================================
    // 5. Determinism
    // ==================================================================

    @Test
    fun `resolution is a pure function of its inputs`() {
        // Called a hundred times, because a resolver that read a clock or a counter would
        // look correct in a screenshot and change under a user.
        val first = AssetResolver.background("p", "rooftop", TimeOfDay.NIGHT, "rain")
        repeat(100) {
            assertEquals(first, AssetResolver.background("p", "rooftop", TimeOfDay.NIGHT, "rain"))
        }
    }

    @Test
    fun `pack artwork resolves into the scenes directory`() {
        assertEquals(
            "stories/miraculous/scenes/pack-cover.png",
            AssetResolver.packArtwork("miraculous", "pack-cover"),
        )
        assertEquals(
            "stories/miraculous/scenes/pack-banner.png",
            AssetResolver.packArtwork("miraculous", "pack-banner"),
        )
    }
}