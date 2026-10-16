package dev.charaly.runtime.storypack

import dev.charaly.runtime.domain.PackAudioAsset
import dev.charaly.runtime.domain.VisualAsset
import dev.charaly.runtime.domain.VisualAssetType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Assets: portable, relative, and inside the pack.
 *
 * Two laws and one courtesy. The laws: a reference is relative to the pack root and can
 * reach nothing else, and a required file that is absent stops the install. The courtesy: a
 * missing optional file is a styling fallback, never a broken frame.
 */
class StoryPackAssetTest {

    @Test
    fun `an asset reference is a relative path or nothing`() {
        assertTrue(PackAssetReferences.isPortable("assets/backgrounds/paris_rooftop.webp"))
        assertTrue(PackAssetReferences.isPortable(""))
        assertTrue(PackAssetReferences.isPortable("generated:paris"))
        assertFalse(PackAssetReferences.isPortable("/data/user/0/files/x.png"))
        assertFalse(PackAssetReferences.isPortable("../../etc/passwd"))
        assertFalse(PackAssetReferences.isPortable("file:///sdcard/x.png"))
    }

    @Test
    fun `device paths are recognised for what they are`() {
        assertTrue(PackAssetReferences.looksLikeDevicePath("/storage/emulated/0/DCIM/img.png"))
        assertTrue(PackAssetReferences.looksLikeDevicePath("C:\\Users\\me\\img.png"))
        assertTrue(PackAssetReferences.looksLikeDevicePath("content://media/external/images/1"))
        assertFalse(PackAssetReferences.looksLikeDevicePath("assets/cover/paris.webp"))
    }

    @Test
    fun `the asset root refuses to resolve outside itself`() {
        val env = PackTestEnv()
        val root = PackAssetRoot(File(env.root, "installed/pack/1.0.0"))
        root.root.mkdirs()

        assertNull(root.resolve("../../secrets.json"))
        assertNull(root.resolve("/etc/passwd"))
        assertNull(root.resolve("assets/../../escape.png"))
        assertNull(root.resolve("assets\\..\\..\\escape.png"))
        assertNull(root.resolve("C:/Windows/system.ini"))
        assertNotNull(root.resolve("assets/cover/paris.webp"))

        File(root.root, "assets").mkdirs()
        File(root.root, "assets/cover").mkdirs()
        File(root.root, "assets/cover/paris.webp").writeBytes(byteArrayOf(1, 2, 3))

        assertTrue(root.exists("assets/cover/paris.webp"))
        assertEquals(3, root.bytes("assets/cover/paris.webp")?.size)
        assertEquals(listOf("assets/cover/paris.webp"), root.listAssets())
        assertFalse(root.exists("assets/cover/nope.webp"))
        env.cleanup()
    }

    @Test
    fun `a required missing asset blocks installation`() {
        val env = PackTestEnv()
        runBlocking {
            val pack = PackFixtures.pack().copy(
                visualAssets = listOf(
                    VisualAsset(
                        assetId = "cover",
                        type = VisualAssetType.PACK_COVER,
                        ref = "assets/cover/paris.webp",
                    ),
                ),
            )
            val result = env.installer.import(PackFixtures.sampleArchive(pack))
            assertTrue(result is StoryPackInstaller.ImportResult.Rejected)
            val report = (result as StoryPackInstaller.ImportResult.Rejected).report
            assertTrue(report.errors.any { it.code == IssueCode.INVALID_ASSET })
        }
        env.cleanup()
    }

    @Test
    fun `a required asset that is present installs and resolves after installation`() {
        val env = PackTestEnv()
        runBlocking {
            val pack = PackFixtures.pack().copy(
                visualAssets = listOf(
                    VisualAsset(
                        assetId = "cover",
                        type = VisualAssetType.PACK_COVER,
                        ref = "assets/cover/paris.webp",
                    ),
                ),
            )
            val content = StoryPackCodec.encode(pack)
            val files = LinkedHashMap<String, ByteArray>()
            content.forEach { (path, text) -> files[path] = text.toByteArray(Charsets.UTF_8) }
            files["assets/cover/paris.webp"] = byteArrayOf(1, 2, 3, 4)
            val manifest = PackManifestBuilder.build(pack, files)
            val all = LinkedHashMap<String, ByteArray>()
            all[StoryPackFormat.MANIFEST_PATH] = StoryPackFormat.manifestJson
                .encodeToString(PackManifest.serializer(), manifest)
                .toByteArray(Charsets.UTF_8)
            all.putAll(files)

            val result = env.installer.import(StoryPackArchive.write(all))
            assertTrue(result is StoryPackInstaller.ImportResult.Installed)

            val assets = env.store.assetRoot("pack.paris.afterdark", "1.0.0")
            assertTrue(assets.exists("assets/cover/paris.webp"))
            assertEquals(4, assets.bytes("assets/cover/paris.webp")?.size)
        }
        env.cleanup()
    }

    @Test
    fun `an optional missing asset warns and falls back`() {
        val env = PackTestEnv()
        runBlocking {
            val pack = PackFixtures.pack().copy(
                visualAssets = listOf(
                    VisualAsset(
                        assetId = "thumb",
                        type = VisualAssetType.CHARACTER_THUMBNAIL,
                        generatedSeed = "marina",
                        thumbnailRef = "assets/portraits/marina_small.webp",
                    ),
                ),
            )
            val result = env.installer.import(PackFixtures.sampleArchive(pack))
            val installed = result as? StoryPackInstaller.ImportResult.Installed
            assertNotNull(installed)
            assertEquals(Severity.WARNING, installed!!.report.severity)
            // And the visual chain still resolves to *something* to draw.
            val resolved = dev.charaly.runtime.domain.VisualAssetChain.resolve(
                type = VisualAssetType.CHARACTER_THUMBNAIL,
                declared = pack.visualAssets.first(),
                seed = "ch-marina",
            )
            assertTrue(resolved.tier.name.isNotBlank())
        }
        env.cleanup()
    }

    @Test
    fun `a device path in an asset reference is a critical refusal`() {
        val env = PackTestEnv()
        runBlocking {
            val pack = PackFixtures.pack().copy(
                visualAssets = listOf(
                    VisualAsset(
                        assetId = "loot",
                        type = VisualAssetType.BACKGROUND_IMAGE,
                        ref = "/data/user/0/dev.charaly.app/files/charaly/models/x.png",
                    ),
                ),
            )
            val result = env.installer.import(PackFixtures.sampleArchive(pack))
            assertTrue(result is StoryPackInstaller.ImportResult.Rejected)
        }
        env.cleanup()
    }

    @Test
    fun `audio assets are inventoried and required audio blocks when absent`() {
        val pack = PackFixtures.pack().copy(
            audioAssets = listOf(
                PackAudioAsset(id = "rain", path = "assets/audio/rooftop_rain.ogg", loop = true, required = true),
            ),
        )
        val contents = StoryPackArchive.read(PackFixtures.sampleArchive(pack))
        val issues = StoryPackValidator.validateAssets(pack, contents) { false }
        assertTrue(issues.any { it.code == IssueCode.INVALID_ASSET && it.isBlocking })

        val optional = pack.copy(
            audioAssets = listOf(
                PackAudioAsset(id = "rain", path = "assets/audio/rooftop_rain.ogg", loop = true, required = false),
            ),
        )
        val softIssues = StoryPackValidator.validateAssets(optional, contents) { false }
        assertTrue(softIssues.any { it.severity == Severity.WARNING })
        assertFalse(softIssues.any { it.isBlocking })
    }

    @Test
    fun `an undeclared file in the archive is a warning`() {
        val contents = StoryPackArchive.Contents(
            manifest = null,
            textFiles = emptyMap(),
            assets = mapOf("assets/random.bin" to ByteArray(4)),
            entryNames = listOf("assets/random.bin"),
            warnings = emptyList(),
        )
        val issues = StoryPackValidator.validateAssets(PackFixtures.pack(), contents) { true }
        assertTrue(issues.any { it.code == IssueCode.UNKNOWN_FILE && it.severity == Severity.WARNING })
    }

    @Test
    fun `generated artwork travels without a file`() {
        val pack = PackFixtures.pack().copy(
            visualAssets = listOf(
                VisualAsset.generated(assetId = "scene", type = VisualAssetType.SCENE_IMAGE, seed = "rooftop"),
            ),
        )
        val contents = StoryPackArchive.read(PackFixtures.sampleArchive(pack))
        assertTrue(StoryPackValidator.validateAssets(pack, contents) { false }.none { it.isBlocking })
        assertTrue(pack.visualAssets.first().source.isRedistributable)
    }

    @Test
    fun `export carries the assets that were installed`() {
        val env = PackTestEnv()
        runBlocking {
            val pack = PackFixtures.pack().copy(
                audioAssets = listOf(
                    PackAudioAsset(id = "rain", path = "assets/audio/rooftop_rain.ogg", required = false),
                ),
            )
            val content = StoryPackCodec.encode(pack)
            val files = LinkedHashMap<String, ByteArray>()
            content.forEach { (path, text) -> files[path] = text.toByteArray(Charsets.UTF_8) }
            files["assets/audio/rooftop_rain.ogg"] = byteArrayOf(7, 7, 7)
            val manifest = PackManifestBuilder.build(pack, files)
            val all = LinkedHashMap<String, ByteArray>()
            all[StoryPackFormat.MANIFEST_PATH] = StoryPackFormat.manifestJson
                .encodeToString(PackManifest.serializer(), manifest)
                .toByteArray(Charsets.UTF_8)
            all.putAll(files)
            env.installer.import(StoryPackArchive.write(all))

            val exported = env.installer.export("pack.paris.afterdark")!!
            val decoded = StoryPackArchive.read(exported)
            assertEquals(3, decoded.assets["assets/audio/rooftop_rain.ogg"]?.size)
        }
        env.cleanup()
    }
}
