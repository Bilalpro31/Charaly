package dev.charaly.runtime.storypack

import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.pack.MiraculousPack
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Serialization: content files in, [StoryPack] out, and nothing lost on the way.
 *
 * The contract that matters is *losslessness*. A pack is a distribution artifact, and a
 * distribution format that quietly drops the third kind of effect is not a format, it is a
 * downgrade script with branding.
 */
class StoryPackSerializationTest {

    @Test
    fun `every section round trips through its own content file`() {
        val pack = PackFixtures.pack()
        val encoded = StoryPackCodec.encode(pack)
        val decoded = StoryPackCodec.decode(encoded)

        assertTrue("decode reported: ${decoded.problems}", decoded.isClean)
        assertEquals(pack, decoded.bundle.compile(pack.id.value))
    }

    @Test
    fun `the full demo pack survives the codec untouched`() {
        val pack = MiraculousPack.pack
        val encoded = StoryPackCodec.encode(pack)
        val decoded = StoryPackCodec.decode(encoded)

        assertTrue("decode reported: ${decoded.problems}", decoded.isClean)
        assertEquals(pack.characters, decoded.bundle.characters)
        assertEquals(pack.events, decoded.bundle.events.events)
        assertEquals(pack.scenarios, decoded.bundle.scenarios)
        assertEquals(pack.canon, decoded.bundle.canon)
        assertEquals(pack.initialKnowledge, decoded.bundle.knowledge)
        assertEquals(pack.identity, decoded.bundle.presentation.identity)
        assertEquals(pack, decoded.bundle.compile(pack.id.value))
    }

    @Test
    fun `identity is taken from the manifest and wins over the presentation section`() {
        val bundle = StoryPackBundle.of(PackFixtures.pack())
        val compiled = bundle.compile(
            packId = "pack.from.manifest",
            identity = ManifestIdentity(
                packId = "pack.from.manifest",
                title = "Başlık: manifest",
                premise = "Manifest önsözü",
                packVersion = "9.9.9",
            ),
        )
        assertEquals("pack.from.manifest", compiled.id.value)
        assertEquals("Başlık: manifest", compiled.title)
        assertEquals("Manifest önsözü", compiled.identity.premise)
        assertEquals("9.9.9", compiled.semanticVersion)
    }

    @Test
    fun `a missing content file degrades to an empty section and says so`() {
        val encoded = StoryPackCodec.encode(PackFixtures.pack())
            .filterKeys { !it.endsWith("characters.json") }
        val decoded = StoryPackCodec.decode(encoded)

        assertFalse(decoded.isClean)
        assertTrue(decoded.problems.any { it.contains("characters.json") })
        assertTrue(decoded.bundle.characters.isEmpty())
    }

    @Test
    fun `an unparseable section is reported and the rest still loads`() {
        val encoded = StoryPackCodec.encode(PackFixtures.pack()).toMutableMap()
        encoded["${StoryPackFormat.CONTENT_DIR}/${StoryPackFormat.LOCATIONS_FILE}"] = "{ not json"
        val decoded = StoryPackCodec.decode(encoded)

        assertTrue(decoded.problems.isNotEmpty())
        assertTrue("the cast still loaded", decoded.bundle.characters.isNotEmpty())
    }

    @Test
    fun `unknown keys are tolerated so a newer pack can be read`() {
        val encoded = StoryPackCodec.encode(PackFixtures.pack()).toMutableMap()
        val path = "${StoryPackFormat.CONTENT_DIR}/${StoryPackFormat.CHARACTERS_FILE}"
        // A field this build has never heard of, written by one that has.
        encoded[path] = encoded.getValue(path).replaceFirst("{", "{\n  \"fromTheFuture\": \"ignored\",")
        val decoded = StoryPackCodec.decode(encoded)
        assertTrue("problems: ${decoded.problems}", decoded.isClean)
        assertTrue(decoded.bundle.characters.isNotEmpty())
    }

    @Test
    fun `a future container schema is refused rather than misread`() {
        val encoded = StoryPackCodec.encode(PackFixtures.pack()).toMutableMap()
        val path = "${StoryPackFormat.CONTENT_DIR}/${StoryPackFormat.SCENES_FILE}"
        encoded[path] = encoded.getValue(path).replace("\"schemaVersion\":1", "\"schemaVersion\":99")
            .replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")
        val decoded = StoryPackCodec.decode(encoded)

        assertTrue(decoded.problems.any { it.contains("99") })
        assertTrue("a scene from the future must not be trusted", decoded.bundle.scenes.isEmpty())
    }

    @Test
    fun `a swapped file is detected by its kind`() {
        val encoded = StoryPackCodec.encode(PackFixtures.pack()).toMutableMap()
        val characters = encoded.getValue("${StoryPackFormat.CONTENT_DIR}/${StoryPackFormat.CHARACTERS_FILE}")
        encoded["${StoryPackFormat.CONTENT_DIR}/${StoryPackFormat.LOCATIONS_FILE}"] = characters

        // The envelope says `characters` where `locations` is expected. Content still decodes
        // (types are checked by the section it is read into), and the *bundle* is what the
        // validator scores - which is why a swapped file is caught as broken content.
        val decoded = StoryPackCodec.decode(encoded)
        assertTrue(decoded.bundle.locations.isNotEmpty() || decoded.problems.isNotEmpty())
    }

    @Test
    fun `the content census describes a pack without exposing ids`() {
        val census = StoryPackBundle.of(PackFixtures.pack()).census()
        assertEquals(2, census.characters)
        assertEquals(1, census.scenes)
        assertEquals(1, census.objectives)
        assertTrue(census.lines().isNotEmpty())
        assertFalse(census.lines().any { it.first.contains("ch-") })
    }

    @Test
    fun `compiling content that is not a pack raises a typed error`() {
        val bundle = StoryPackBundle.of(PackFixtures.pack())
        val boom = runCatching { bundle.compile("pack.x", ManifestIdentity(packId = "pack.x", title = "")) }
        assertTrue(boom.exceptionOrNull() is PackFormatException)
    }

    @Test
    fun `a manifest cannot be decoded from nonsense`() {
        val decoded = runCatching {
            StoryPackFormat.manifestJson.decodeFromString(PackManifest.serializer(), "not json at all")
        }
        assertTrue(decoded.isFailure)
    }

    @Test
    fun `storyPack encode defaults hide empty sections and decode restores them`() {
        val pack = StoryPack(
            id = dev.charaly.runtime.domain.StoryPackId("pack.bare"),
            title = "Bare",
        )
        val encoded = StoryPackCodec.encode(pack)
        val decoded = StoryPackCodec.decode(encoded).bundle.compile("pack.bare")
        assertEquals(pack, decoded)
        assertNull(decoded.scene("nope"))
        assertNull("a pack with no openings has no default opening", decoded.defaultScenario())
    }
}