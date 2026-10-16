package dev.charaly.runtime.storypack

import dev.charaly.runtime.domain.StoryInstanceId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Import: what happens between "the user picked a file" and "this pack is in the library".
 *
 * The Android side supplies bytes from the Storage Access Framework; everything after that
 * is here. So the SAF-specific risks - a URI that is not a pack, a name that lies, a
 * permission that evaporates mid-copy - arrive as "these bytes", and are answered by the
 * same inspection and the same atomic install.
 */
class StoryPackImportTest {

    @Test
    fun `importing a file is name independent`() {
        val env = PackTestEnv()
        runBlocking {
            // Whatever the picker called it, the pack is what its manifest says.
            val bytes = PackFixtures.sampleArchive(PackFixtures.pack(id = "pack.renamed.often"))
            val result = env.installer.import(bytes)
            val installed = result as StoryPackInstaller.ImportResult.Installed
            assertEquals("pack.renamed.often", installed.record.packId)
        }
        env.cleanup()
    }

    @Test
    fun `the import report is readable and shows no internal ids`() {
        val env = PackTestEnv()
        runBlocking {
            val broken = PackFixtures.pack().copy(locations = emptyList())
            val candidate = env.installer.inspect(PackFixtures.sampleArchive(broken))

            assertFalse(candidate.isInstallable)
            val headline = candidate.report.headline()
            assertTrue(headline.contains("kritik"))
            val playerFacing = candidate.report.errors.map { it.message }
            assertTrue(playerFacing.isNotEmpty())
            assertFalse(
                "no ids at a player",
                playerFacing.any { message -> message.contains("loc-") || message.contains("ch-") },
            )
            assertTrue("ids belong to the developer view", candidate.report.debugLines().any { it.contains("loc-") })
        }
        env.cleanup()
    }

    @Test
    fun `cancelling an import writes nothing at all`() {
        val env = PackTestEnv()
        runBlocking {
            // The user inspects and walks away. Inspection is read-only by contract.
            env.installer.inspect(PackFixtures.sampleArchive())
            env.installer.inspect(PackFixtures.sampleArchive())

            assertEquals(emptyList<Any>(), env.registry.list())
            assertEquals(emptyList<Any>(), env.store.listEditions("pack.paris.afterdark"))
        }
        env.cleanup()
    }

    @Test
    fun `an interrupted import leaves no half pack and can simply be retried`() {
        val env = PackTestEnv()
        runBlocking {
            val bytes = PackFixtures.sampleArchive()
            // First attempt dies inside staging (simulated), second succeeds.
            val staging = env.store.stage("interrupted")
            staging.resolve("manifest.json").writeText("{ half")
            env.installer.sweep()

            val result = env.installer.import(bytes)
            assertTrue(result is StoryPackInstaller.ImportResult.Installed)
            assertEquals(1, env.registry.list().size)
        }
        env.cleanup()
    }

    @Test
    fun `importing a newer edition is an upgrade that keeps the old one`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack(version = "1.0.0"))
            val story = env.runtime.startStory(
                env.installer.load("pack.paris.afterdark", "1.0.0")!!.pack,
                StoryInstanceId("story-1"),
            )

            val candidate = env.installer.inspect(PackFixtures.sampleArchive(PackFixtures.pack(version = "1.1.0")))
            assertTrue("the report says this is an upgrade", candidate.isUpgrade)
            assertEquals("1.0.0", candidate.existingVersion)

            val result = env.installer.import(PackFixtures.sampleArchive(PackFixtures.pack(version = "1.1.0")))
                    as StoryPackInstaller.ImportResult.Installed
            assertEquals("1.0.0", result.upgradedFrom)
            assertTrue(env.installer.compatibilityFor(story).isPlayable)
        }
        env.cleanup()
    }

    @Test
    fun `the import result distinguishes installed from refused`() {
        val env = PackTestEnv()
        runBlocking {
            val good = env.installer.import(PackFixtures.sampleArchive())
            val bad = env.installer.import("this is not an archive".toByteArray())

            assertTrue(good is StoryPackInstaller.ImportResult.Installed)
            assertTrue(bad is StoryPackInstaller.ImportResult.Rejected)
            assertFalse((bad as StoryPackInstaller.ImportResult.Rejected).report.isInstallable)
        }
        env.cleanup()
    }

    @Test
    fun `importing twice is idempotent rather than duplicating`() {
        val env = PackTestEnv()
        runBlocking {
            repeat(3) { env.installer.import(PackFixtures.sampleArchive()) }
            assertEquals(1, env.registry.list().size)
            assertEquals(listOf("1.0.0"), env.store.listEditions("pack.paris.afterdark"))
            assertEquals(1, env.runtime.listPacks().size)
        }
        env.cleanup()
    }

    @Test
    fun `the census in the report describes what is inside without opening it in a browser`() {
        val env = PackTestEnv()
        runBlocking {
            val candidate = env.installer.inspect(PackFixtures.sampleArchive())
            val census = candidate.report.censusLine()
            assertTrue(census.contains("karakter"))
            assertTrue(census.contains("olay"))
            // Two real warnings (no translations, one thread with no ending) and an honest
            // headline that says exactly that.
            assertEquals("2 uyarı ile kurulabilir", candidate.report.headline())
            assertTrue(candidate.report.warnings.isNotEmpty())
            assertEquals(emptyList<ValidationIssue>(), candidate.report.errors)
        }
        env.cleanup()
    }

    @Test
    fun `an archive that lies about its checksum is refused before install`() {
        val env = PackTestEnv()
        runBlocking {
            val bytes = PackFixtures.sampleArchive()
            val contents = StoryPackArchive.read(bytes)
            val manifest = StoryPackFormat.manifestJson
                .decodeFromString(PackManifest.serializer(), contents.manifest.orEmpty())
            val lying = manifest.copy(sha256 = "f".repeat(64))
            val forged = LinkedHashMap<String, ByteArray>()
            forged[StoryPackFormat.MANIFEST_PATH] = StoryPackFormat.manifestJson
                .encodeToString(PackManifest.serializer(), lying)
                .toByteArray()
            contents.textFiles.forEach { (k, v) -> forged[k] = v.toByteArray() }

            val candidate = env.installer.inspect(StoryPackArchive.write(forged))
            assertFalse(candidate.isInstallable)
            assertTrue(candidate.report.errors.any { it.code == IssueCode.CHECKSUM_MISMATCH })
        }
        env.cleanup()
    }
}