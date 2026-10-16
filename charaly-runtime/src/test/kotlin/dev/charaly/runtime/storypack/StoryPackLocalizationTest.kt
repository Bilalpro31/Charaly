package dev.charaly.runtime.storypack

import dev.charaly.runtime.pack.MiraculousPack
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Localization: tr, en, and an honest fallback chain.
 *
 * The rule this file defends: **a key never renders as a key**. A half-translated pack
 * shows the author's own words for the gaps and translated words everywhere else, and the
 * device's language wins wherever a translation exists.
 */
class StoryPackLocalizationTest {

    private fun table(): PackLocalizer.Table =
        PackLocalizer.forPack(PackFixtures.pack(), PackFixtures.localization(), preferred = "tr")

    @Test
    fun `the device language wins when the pack has it`() {
        val localizer = table()
        assertEquals("Paris Gecesi", localizer.title(PackFixtures.pack()))
        assertEquals("Paris'in üzerinde bir gece.", localizer.premise(PackFixtures.pack()))
    }

    @Test
    fun `a language with no translation falls back to the pack language`() {
        val localizer = PackLocalizer.forPack(PackFixtures.pack(), PackFixtures.localization(), preferred = "de")
        assertEquals("Paris Gecesi", localizer.title(PackFixtures.pack()))
    }

    @Test
    fun `english is the last resort before the authored text`() {
        val tables = mapOf(
            "en" to mapOf(PackLocalizer.META_TITLE to "Paris After Dark"),
        )
        val localizer = PackLocalizer.forPack(PackFixtures.pack(), tables, preferred = "fr")
        assertEquals("Paris After Dark", localizer.title(PackFixtures.pack()))
    }

    @Test
    fun `a missing key renders the authored text, never the key`() {
        val localizer = PackLocalizer.forPack(PackFixtures.pack(), emptyMap(), preferred = "tr")
        val title = localizer.title(PackFixtures.pack())
        assertEquals("Paris After Dark", title)
        assertFalse(title.contains("meta."))
    }

    @Test
    fun `locale spellings collapse to one language`() {
        val tables = PackFixtures.localization()
        val localizer = PackLocalizer.forPack(PackFixtures.pack(), tables, preferred = "tr-TR")
        assertEquals("Paris Gecesi", localizer.title(PackFixtures.pack()))
    }

    @Test
    fun `a complete translation covers exactly the expected keys`() {
        val pack = PackFixtures.pack()
        val expected = PackLocalizer.expectedKeys(pack)
        assertTrue(expected.contains(PackLocalizer.META_TITLE))
        assertTrue(expected.contains("character.ch-marina.name"))
        assertTrue(expected.contains("choice.scn-rooftop-scene.choice-help.label"))
        assertTrue(expected.none { it.isBlank() })
    }

    @Test
    fun `key grammar is checked against the pack it addresses`() {
        val pack = PackFixtures.pack()
        assertTrue(PackLocalizer.isValidKey("character.ch-marina.name", pack))
        assertTrue(PackLocalizer.isValidKey("choice.scn-rooftop-scene.choice-help.label", pack))
        assertFalse("a typo addresses nothing", PackLocalizer.isValidKey("chracter.ch-marina.name", pack))
        assertFalse(PackLocalizer.isValidKey("character.ch-ghost.name", pack))
        assertFalse(PackLocalizer.isValidKey("justoneword", pack))
    }

    @Test
    fun `validation reports a language with no table at all`() {
        val issues = StoryPackValidator.validateLocalization(PackFixtures.pack(), mapOf("tr" to emptyMap()))
        assertTrue(issues.any { it.message.contains("ENGLISH") || it.message.contains("EN") })
    }

    @Test
    fun `a translation rides inside the pack and survives a round trip`() {
        val env = PackTestEnv()
        val other = PackTestEnv()
        runBlocking {
            env.installer.import(PackFixtures.sampleArchive(PackFixtures.pack(), PackFixtures.localization()))
            val exported = env.installer.export("pack.paris.afterdark")!!
            other.installer.import(exported)

            val arrived = other.installer.load("pack.paris.afterdark")!!
            assertEquals(setOf("tr", "en"), arrived.localization.keys)
            val localizer = PackLocalizer.forPack(arrived.pack, arrived.localization, preferred = "en")
            assertEquals("Paris After Dark", localizer.title(arrived.pack))
        }
        env.cleanup()
        other.cleanup()
    }

    @Test
    fun `pack metadata can be localized without touching the content`() {
        val pack = MiraculousPack.pack
        val tables = mapOf("tr" to mapOf(PackLocalizer.META_PREMISE to "Paris'te bir gece."))
        val localizer = PackLocalizer.forPack(pack, tables, preferred = "tr")
        assertEquals("Paris'te bir gece.", localizer.premise(pack))
        assertEquals("the description stays as authored", pack.description, localizer.description(pack))
    }

    @Test
    fun `the localization file path is part of the format`() {
        assertEquals("localization/tr.json", PackManifestBuilder.localizationPath("TR"))
        assertEquals("localization/en.json", PackManifestBuilder.localizationPath("en"))
    }
}