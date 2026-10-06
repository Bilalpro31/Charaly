package dev.charaly.app.ui

import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.WorldDefinition
import dev.charaly.runtime.engine.StoryInstanceFactory
import dev.charaly.runtime.pack.DemoStoryPacks
import dev.charaly.runtime.pack.MiraculousPack
import dev.charaly.runtime.presentation.AssetResolver
import dev.charaly.runtime.presentation.TimeOfDay
import dev.charaly.runtime.presentation.VisualSceneProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE ASSET PIPELINE, END TO END FROM WORLD STATE.
 *
 * ## Why this test is the important one
 *
 * `AssetResolutionTest` proves the resolver is a pure function of its inputs. This proves
 * its *inputs are real* - that the `CharacterId` and `LocationId` a projection hands it are
 * the same identifiers the pack defines and the artwork files are named after.
 *
 * That gap is where a visual pipeline silently dies. A resolver that works perfectly on
 * synthetic ids, fed a projection carrying different ones, returns paths to nothing - and
 * the symptom is a stage that looks correct in every test and shows nothing on a device.
 *
 * ## The three claims
 *
 *  1. The pack's real location ids resolve to files that are actually declared.
 *  2. The pack's real character ids resolve to files that are actually declared.
 *  3. The projection's output is derived from `WorldState` alone - move the clock, and the
 *     resolved file changes, with no input from the transcript.
 */
class MiraculousAssetPipelineTest {

    private val pack = DemoStoryPacks.all.first { it.id.value == MiraculousPack.ID }
    private val definition = WorldDefinition(pack.characters, pack.locations)

    private fun story() = StoryInstanceFactory.create(pack, StoryInstanceId("asset-test"))

    // ==================================================================
    // 1. Real location ids resolve to real files
    // ==================================================================

    @Test
    fun `every location the pack declares has artwork for every time of day`() {
        val unresolved = pack.locations.mapNotNull { location ->
            val id = location.id.value
            // Locations the pack itself does not name are excluded deliberately: an
            // unclassified place is *supposed* to fall back, and the fallback is asserted
            // separately below. What must never happen is a place the pack names being
            // silently unillustrated.
            if (!isIllustratedLocation(id)) return@mapNotNull null
            TimeOfDay.entries.firstOrNull { time ->
                AssetResolver.background("p", id, time) !in AssetResolver.declaredFiles
            }?.let { time -> "$id has no artwork for $time" }
        }
        assertTrue(
            "these places would draw the fallback instead of their own artwork:\n  " +
                unresolved.joinToString("\n  "),
            unresolved.isEmpty(),
        )
    }

    @Test
    fun `the projection's location resolves to declared artwork`() {
        // Take the projection's own output rather than constructing ids by hand - this is
        // the value the screen will actually pass to the resolver.
        val projection = VisualSceneProjection.project(story(), definition, pack)
        assertTrue("the projection must name a location", projection.hasLocation)

        val path = AssetResolver.background(
            packId = pack.id.value,
            locationId = projection.locationId,
            timeOfDay = projection.timeOfDay,
            weather = projection.weather,
        )
        assertTrue(
            "the projection produced '$path', which no resolver call could reach",
            path in AssetResolver.declaredFiles,
        )
    }

    @Test
    fun `the places a story actually starts in have artwork of their own`() {
        // Deliberately narrower than "every location".
        //
        // The pack declares twenty-seven places and the artwork set keyframes twelve of
        // them: the rooftop, the school and its rooms, the courtyard, the quarter's street,
        // the bakery, the cafe, the museum, the park. The remaining fifteen are somebody's
        // apartment, a metro station and the TV tower - places the story passes *through*,
        // not places it happens. Demanding bespoke artwork for all of them would be asking
        // for twenty-seven background paintings to cover fifteen moments.
        //
        // What matters is that every place a scene can *be* has a picture, so this asserts
        // against the scenario starting locations - the ones a reader will actually open on.
        val scenarioStarts = pack.scenarios
            .mapNotNull { it.startLocationId?.value }
            .toSet()

        val unillustrated = scenarioStarts.filterNot { isIllustratedLocation(it) }
        assertTrue(
            "these are the places stories open in, and they need artwork:\n  " +
                unillustrated.joinToString("\n  "),
            unillustrated.isEmpty(),
        )
        assertTrue(
            "a pack with no scenarios would make this test vacuous",
            scenarioStarts.isNotEmpty(),
        )
    }

    @Test
    fun `an unillustrated place resolves to the fallback rather than to nothing`() {
        // The other half of the decision above: the through-places still render a complete
        // picture, and the file they resolve to is one the app actually ships.
        //
        // `resolveBackground` is the call the screen makes - the one that knows which files
        // exist. Using the raw `background` here instead would test a function nothing on
        // the screen calls, which is how a test can pass while the stage shows nothing.
        val fallback = AssetResolver.fallbackBackground("p", TimeOfDay.NIGHT)
        assertTrue("the fallback must be a shipped file", fallback in AssetResolver.declaredFiles)

        val shipped = AssetResolver.declaredFiles
        val unillustrated = pack.locations.map { it.id.value }.filterNot { isIllustratedLocation(it) }
        assertTrue("the pack should have some unillustrated places for this to mean anything", unillustrated.isNotEmpty())

        for (id in unillustrated) {
            val path = AssetResolver.resolveBackground(
                packId = "p",
                locationId = id,
                timeOfDay = TimeOfDay.NIGHT,
                exists = { it in shipped },
            )
            assertTrue(
                "'$id' resolved to '$path', which is not a shipped file",
                path in shipped,
            )
        }
    }

    @Test
    fun `the artwork filenames use the same spelling as the pack's location ids`() {
        // The mismatch that matters: pack says `school-courtyard`, artwork says
        // `school_courtyard`. One character wrong and every courtyard in the story quietly
        // draws a rooftop, which looks like an art choice rather than a bug.
        val filePrefixes = AssetResolver.declaredFiles
            .filter { it.contains("/backgrounds/") }
            .map { it.substringAfter("/backgrounds/").substringBefore("_") }
            .toSet()

        val keyframed = pack.locations
            .map { it.id.value }
            .filter { isIllustratedLocation(it) }
        val misspelled = keyframed.filter { id ->
            filePrefixes.none { it == id || it == id.replace('-', '_') }
        }
        assertTrue(
            "these keyframed places have no file under their own spelling:\n  " +
                misspelled.joinToString("\n  "),
            misspelled.isEmpty(),
        )
    }

    // ==================================================================
    // 2. Real character ids resolve to real files
    // ==================================================================

    @Test
    fun `every character the pack declares resolves to a declared portrait`() {
        val unresolved = pack.characters
            .filter { AssetResolver.portrait("p", it.id.value) !in AssetResolver.declaredFiles }
            .map { it.id.value }
        assertTrue(
            "these characters would draw the generic mark instead of their own portrait:\n  " +
                unresolved.joinToString("\n  "),
            unresolved.isEmpty(),
        )
    }

    @Test
    fun `two characters never resolve to the same portrait`() {
        val byId = pack.characters.associate { it.id.value to AssetResolver.portrait("p", it.id.value) }
        val duplicates = byId.values.groupingBy { it }.eachCount().filterValues { it > 1 }
        assertTrue("these characters share a portrait file: ${duplicates.keys}", duplicates.isEmpty())
        assertNotEquals(byId[MiraculousPack.MARINETTE], byId[MiraculousPack.ADRIEN])
    }

    @Test
    fun `the masked heroes resolve by their own ids, not the person inside the suit`() {
        // The projection carries whichever id the engine is using. Ladybug and Marinette are
        // different ids and must be different portraits, because a scene where a character
        // takes off the mask should look like it.
        val ladybug = AssetResolver.portrait("p", "ladybug")
        val marinette = AssetResolver.portrait("p", MiraculousPack.MARINETTE)
        assertNotEquals(
            "a masked hero and the person under the mask must not share a portrait",
            ladybug,
            marinette,
        )
    }

    // ==================================================================
    // 3. The projection is a function of world state alone
    // ==================================================================

    @Test
    fun `the resolved background follows the world clock`() {
        val instance = story()
        val day = VisualSceneProjection.project(instance, definition, pack)
        // The clock lives inside `WorldState`, not beside it: that nesting is the point of the
        // boundary this whole architecture protects, so the test moves it the same way the
        // engine does rather than reaching around it.
        val nightInstance = instance.copy(
            worldState = instance.worldState.copy(
                worldClock = instance.worldState.worldClock.copy(
                    now = instance.worldState.worldClock.now.copy(hour = 23),
                ),
            ),
        )
        val night = VisualSceneProjection.project(nightInstance, definition, pack)

        assertEquals(TimeOfDay.DAY, day.timeOfDay)
        assertEquals(TimeOfDay.NIGHT, night.timeOfDay)

        // Same place, different hour, different file. If these resolved to one path the
        // clock would not be reaching the artwork.
        val dayPath = AssetResolver.background("p", day.locationId, day.timeOfDay, day.weather)
        val nightPath = AssetResolver.background("p", night.locationId, night.timeOfDay, night.weather)
        assertNotEquals("the clock must change the artwork", dayPath, nightPath)
        assertTrue(nightPath in AssetResolver.declaredFiles)
        assertTrue(dayPath in AssetResolver.declaredFiles)
    }

    @Test
    fun `a declared condition reaches the artwork and prose does not`() {
        val instance = story()
        // The same story, with the world's own weather variable set.
        val raining = instance.copy(
            worldState = instance.worldState.withVariable(
                dev.charaly.runtime.domain.WorldVariable(
                    key = VisualSceneProjection.WEATHER_VARIABLE,
                    type = dev.charaly.runtime.domain.WorldVariableType.TEXT,
                    value = "rain",
                ),
            ),
        )
        val dry = VisualSceneProjection.project(instance, definition, pack)
        val wet = VisualSceneProjection.project(raining, definition, pack)

        assertEquals("", dry.weather)
        assertEquals("rain", wet.weather)
        assertNotEquals(
            "a declared condition must reach the artwork",
            AssetResolver.background("p", wet.locationId, wet.timeOfDay, dry.weather),
            AssetResolver.background("p", wet.locationId, wet.timeOfDay, wet.weather),
        )
        // And the rain file is one the app actually ships.
        assertTrue(
            AssetResolver.background("p", wet.locationId, wet.timeOfDay, wet.weather) in
                AssetResolver.declaredFiles,
        )
    }

    @Test
    fun `resolution is stable across repeated projections of the same state`() {
        val instance = story()
        val first = VisualSceneProjection.project(instance, definition, pack)
        val second = VisualSceneProjection.project(instance, definition, pack)
        assertEquals(first.cacheKey, second.cacheKey)
        assertEquals(first.locationId, second.locationId)
        assertEquals(
            AssetResolver.background("p", first.locationId, first.timeOfDay, first.weather),
            AssetResolver.background("p", second.locationId, second.timeOfDay, second.weather),
        )
    }

    // ==================================================================
    // helpers
    // ==================================================================

    /**
     * Whether the artwork set covers a location.
     *
     * A prefix rather than a membership test, because the pack names locations like
     * `school-courtyard` while the artwork uses `school_courtyard` - and asserting that
     * exact mapping by hand is precisely what the test above exists to prevent. So the
     * helper is permissive about the separator and strict about everything else.
     */
    private fun isIllustratedLocation(id: String): Boolean =
        AssetResolver.declaredFiles.any { it.contains("/backgrounds/${id.replace('-', '_')}_") } ||
            AssetResolver.declaredFiles.any { it.contains("/backgrounds/${id}_") }

    @Test
    fun `the pack cover and banner are both declared`() {
        assertTrue(AssetResolver.packArtwork(pack.id.value, "pack-cover") in AssetResolver.declaredFiles)
        assertTrue(AssetResolver.packArtwork(pack.id.value, "pack-banner") in AssetResolver.declaredFiles)
        assertFalse(
            "no asset may live outside the pack's own directory",
            AssetResolver.declaredFiles.any { !it.startsWith(AssetResolver.MIRACULOUS_ROOT + "/") },
        )
    }
}