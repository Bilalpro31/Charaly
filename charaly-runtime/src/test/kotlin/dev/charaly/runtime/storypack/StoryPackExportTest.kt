package dev.charaly.runtime.storypack

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Export: getting a story pack *out* of Charaly and onto a device that is not this one.
 *
 * Export is the mirror of import and is held to the same laws: the file is portable (no
 * device paths), it is complete (content, translations, assets), it is verifiable (its own
 * manifest and digests), and it is reproducible (the same install exports the same bytes).
 */
class StoryPackExportTest {

    @Test
    fun `an export is a pack archive with a manifest at its root`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack())
            val bytes = env.installer.export("pack.paris.afterdark")!!

            val contents = StoryPackArchive.read(bytes)
            assertNotNull(contents.manifest)
            assertTrue(contents.textFiles.keys.any { it.startsWith("${StoryPackFormat.CONTENT_DIR}/") })
            assertEquals(StoryPackFormat.fileNameFor("pack.paris.afterdark", "1.0.0"), "pack.paris.afterdark-1.0.0.charaly-pack")
        }
        env.cleanup()
    }

    @Test
    fun `an export verifies against its own manifest`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack())
            val bytes = env.installer.export("pack.paris.afterdark")!!
            val decoded = StoryPackDecoder.decode(bytes)

            assertEquals("the exported pack validates", emptyList<ValidationIssue>(), decoded.issues.filter {
                it.severity == Severity.CRITICAL
            })
            assertTrue(PackDigest.of(decoded.manifest!!.files).isNotBlank())
        }
        env.cleanup()
    }

    @Test
    fun `exports of one install are reproducible`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack())
            val first = env.installer.export("pack.paris.afterdark")!!
            val second = env.installer.export("pack.paris.afterdark")!!
            assertArrayEquals(first, second)
        }
        env.cleanup()
    }

    @Test
    fun `an old edition can be exported as well as played`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack(version = "1.0.0"))
            env.install(PackFixtures.pack(version = "1.1.0"))

            val old = env.installer.export("pack.paris.afterdark", "1.0.0")!!
            val decoded = StoryPackDecoder.decode(old)
            assertEquals("1.0.0", decoded.manifest!!.packVersion)

            val current = StoryPackDecoder.decode(env.installer.export("pack.paris.afterdark")!!)
            assertEquals("1.1.0", current.manifest!!.packVersion)
        }
        env.cleanup()
    }

    @Test
    fun `exporting a retained edition that is gone is an honest null`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack(version = "1.0.0"))
            env.install(PackFixtures.pack(version = "1.1.0"))
            env.store.deleteEdition("pack.paris.afterdark", "1.0.0")

            assertNull(env.installer.export("pack.paris.afterdark", "1.0.0"))
        }
        env.cleanup()
    }

    @Test
    fun `an authored pack exports without being installed first`() {
        val bytes = PackTestEnv().let { env ->
            val exported = env.installer.exportPack(PackFixtures.pack(), PackFixtures.localization())
            env.cleanup()
            exported
        }
        val decoded = StoryPackDecoder.decode(bytes)
        assertTrue(decoded.installable)
        assertEquals(setOf("tr", "en"), decoded.localization.keys)
        assertEquals("pack.paris.afterdark", decoded.pack!!.id.value)
    }

    @Test
    fun `an export carries translations and assets and nothing else`() {
        val env = PackTestEnv()
        runBlocking {
            val pack = PackFixtures.pack()
            val content = StoryPackCodec.encode(pack)
            val files = LinkedHashMap<String, ByteArray>()
            content.forEach { (path, text) -> files[path] = text.toByteArray() }
            files["assets/audio/rain.ogg"] = ByteArray(16) { 5 }
            val manifest = PackManifestBuilder.build(pack, files, PackFixtures.localization())
            val all = LinkedHashMap<String, ByteArray>()
            all[StoryPackFormat.MANIFEST_PATH] = StoryPackFormat.manifestJson
                .encodeToString(PackManifest.serializer(), manifest)
                .toByteArray()
            all.putAll(files)
            PackFixtures.localization().forEach { (language, table) ->
                all[PackManifestBuilder.localizationPath(language)] =
                    LocalizationCodec.encode(StoryPackFormat.json, table).toByteArray()
            }
            env.installer.import(StoryPackArchive.write(all))

            val exported = StoryPackArchive.read(env.installer.export("pack.paris.afterdark")!!)
            assertEquals(1, exported.assets.size)
            val translations = exported.textFiles.keys.filter { it.startsWith("${StoryPackFormat.LOCALIZATION_DIR}/") }
            assertEquals(2, translations.size)
            assertTrue(exported.paths.none { it.endsWith(".tmp") || it.contains("registry") })
        }
        env.cleanup()
    }

    @Test
    fun `an export of a disabled pack is still allowed - it is content, not playback`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack())
            env.installer.setEnabled("pack.paris.afterdark", false)
            assertNotNull(env.installer.export("pack.paris.afterdark"))
        }
        env.cleanup()
    }

    @Test
    fun `a manifest minted at export time matches what is inside`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack())
            val decoded = StoryPackDecoder.decode(env.installer.export("pack.paris.afterdark")!!)
            val manifest = decoded.manifest!!
            val worldPath = "${StoryPackFormat.CONTENT_DIR}/${StoryPackFormat.WORLD_FILE}"
            val worldBytes = decoded.contents!!.textFiles.getValue(worldPath).toByteArray(Charsets.UTF_8)
            assertEquals(Sha256.of(worldBytes), manifest.files.first { it.path == worldPath }.sha256)
        }
        env.cleanup()
    }
}