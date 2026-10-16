package dev.charaly.runtime.storypack

import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.StoryPackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Manifest semantics: identity, inventory, digests and the version language.
 *
 * The rule under all of it: **identity is not the filename**. Renaming an archive must not
 * move a pack's identity by a millimetre, and the manifest is the only place identity is
 * allowed to come from.
 */
class StoryPackManifestTest {

    @Test
    fun `a manifest carries identity, requirements and an inventory`() {
        val pack = PackFixtures.pack(id = "miraculous.paris.afterdark", version = "1.2.0")
        val content = StoryPackCodec.encode(pack).mapValues { it.value.toByteArray(Charsets.UTF_8) }
        val manifest = PackManifestBuilder.build(pack, content, PackFixtures.localization())

        assertEquals("miraculous.paris.afterdark", manifest.packId)
        assertEquals("1.2.0", manifest.packVersion)
        assertEquals("Paris After Dark", manifest.title)
        assertEquals("Charaly", manifest.author)
        assertEquals("Charaly Packs", manifest.publisher)
        assertEquals("CC BY-NC 4.0", manifest.license)
        assertEquals("tr", manifest.language)
        assertEquals("scn-rooftop", manifest.entryPoint)
        assertEquals("0.8.0", manifest.minimumEngineVersion)
        assertTrue("identity must travel with the pack", manifest.tags.contains("aksiyon"))
        assertTrue("the inventory covers every content file", manifest.contentFiles().size >= 10)
        assertTrue("translations are inventoried", manifest.localizationFiles().size == 2)
        assertTrue(manifest.sizeBytes > 0)
        assertTrue("a pack digest is 64 hex characters", manifest.sha256.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `pack identity is not the filename`() {
        val bytes = PackFixtures.sampleArchive(PackFixtures.pack(id = "miraculous.paris.afterdark"))
        val renamed = StoryPackArchive.read(bytes)
        val manifest = StoryPackFormat.manifestJson.decodeFromString(
            PackManifest.serializer(),
            renamed.manifest.orEmpty(),
        )
        // The archive bytes never carried a name; identity came from inside.
        assertEquals("miraculous.paris.afterdark", manifest.packId)
    }

    @Test
    fun `a pack id may not be smuggled as a path`() {
        val boom = runCatching {
            PackManifest(packId = "../escape", packVersion = "1.0.0", title = "X")
        }
        assertTrue("path-shaped pack ids must be refused", boom.isFailure)
    }

    @Test
    fun `the pack digest is stable across archive re-encoding`() {
        val pack = PackFixtures.pack()
        val content = StoryPackCodec.encode(pack).mapValues { it.value.toByteArray(Charsets.UTF_8) }
        val first = PackManifestBuilder.build(pack, content)
        val second = PackManifestBuilder.build(pack, content.entries.reversed().associate { it.key to it.value })

        assertEquals("digests are order independent", first.sha256, second.sha256)
        assertEquals(first.files.map { it.path }, second.files.map { it.path }.sorted())
    }

    @Test
    fun `the digest changes when content changes`() {
        val pack = PackFixtures.pack()
        val changed = pack.copy(
            characters = pack.characters.map { it.copy(name = it.name + " II") },
        )
        val contentA = StoryPackCodec.encode(pack).mapValues { it.value.toByteArray(Charsets.UTF_8) }
        val contentB = StoryPackCodec.encode(changed).mapValues { it.value.toByteArray(Charsets.UTF_8) }
        assertNotEquals(
            PackManifestBuilder.build(pack, contentA).sha256,
            PackManifestBuilder.build(changed, contentB).sha256,
        )
    }

    @Test
    fun `a required file is required and a translation is not`() {
        val manifest = PackManifestBuilder.build(PackFixtures.pack(), emptyMap(), PackFixtures.localization())
        assertTrue(
            PackManifestBuilder.requiredFor("${StoryPackFormat.CONTENT_DIR}/characters.json"),
        )
        assertFalse(
            PackManifestBuilder.requiredFor(PackManifestBuilder.localizationPath("en")),
        )
        assertTrue(manifest.files.none { it.path == StoryPackFormat.MANIFEST_PATH })
    }

    @Test
    fun `semver ordering puts releases above their own pre-releases`() {
        assertTrue(SemVer.isNewer("1.2.0", "1.1.9"))
        assertTrue(SemVer.isNewer("2.0.0-rc.1", "1.9.9"))
        assertTrue(SemVer.isNewer("1.2.0", "1.2.0-rc.1"))
        assertFalse(SemVer.isNewer("1.2.0-rc.1", "1.2.0"))
        assertEquals(0, SemVer.compare("1.2.3", "1.2.3"))
    }

    @Test
    fun `garbage versions lose to real ones rather than winning`() {
        assertFalse(SemVer.isValid("banana"))
        assertFalse(SemVer.isValid(""))
        assertTrue(SemVer.isValid("1"))
        assertTrue(SemVer.isValid("1.2"))
        assertTrue("a nonsense version sorts below anything real", SemVer.compare("banana", "0.0.1") < 0)
    }

    @Test
    fun `an engine window is enforced in both directions`() {
        val pack = PackFixtures.pack()
        val content = StoryPackCodec.encode(pack).mapValues { it.value.toByteArray(Charsets.UTF_8) }
        val manifest = PackManifestBuilder.build(pack, content)
        assertTrue(manifest.allowsEngine("0.8.0"))
        assertTrue(manifest.allowsEngine("9.9.9"))

        val gated = manifest.copy(minimumEngineVersion = "1.0.0", maximumEngineVersion = "1.4.0")
        assertFalse(gated.allowsEngine("0.8.0"))
        assertTrue(gated.allowsEngine("1.2.0"))
        assertFalse(gated.allowsEngine("2.0.0"))
    }

    @Test
    fun `a cover reference stays portable`() {
        val pack = PackFixtures.pack()
        assertEquals("", PackManifestBuilder.coverRefOf(pack))
        assertTrue(PackAssetReferences.isPortable(PackManifestBuilder.coverRefOf(pack)))
    }

    @Test
    fun `the archive file name is derived from identity, never the reverse`() {
        val name = StoryPackFormat.fileNameFor("miraculous.paris.afterdark", "1.2.0")
        assertEquals("miraculous.paris.afterdark-1.2.0.charaly-pack", name)
        assertFalse(name.contains(" "))
    }

    @Test
    fun `a pack id without an identity is not a pack`() {
        val boom = runCatching { PackManifest(packId = "", packVersion = "1.0.0", title = "X") }
        assertTrue(boom.isFailure)
        val blankTitle = runCatching { PackManifest(packId = "p", packVersion = "1.0.0", title = " ") }
        assertTrue(blankTitle.isFailure)
    }

    @Test
    fun `edition label comes from the semantic version`() {
        val pack: StoryPack = PackFixtures.pack(version = "2.3.1")
        assertEquals("2.3.1", pack.edition())
        assertEquals("2.3.1", StoryPack(id = StoryPackId("p"), title = "T", packVersion = "2.3.1").edition())
    }
}