package dev.charaly.runtime.storypack

import dev.charaly.runtime.domain.StoryInstanceId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Installation is atomic or it is nothing.
 *
 * The outcomes a crash may produce are exactly three: no pack, a pack, or *litter that
 * nothing reports*. There is no fourth outcome in which a pack is half-visible, and no
 * outcome in which a save is damaged by anything in this file - saves are a different tree
 * and this layer never writes to it except through the repository.
 */
class StoryPackInstallAtomicityTest {

    @Test
    fun `a refused install leaves the device exactly as it was`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack())
            val before = env.registry.list()

            val broken = PackFixtures.pack(id = "pack.other").copy(locations = emptyList())
            val result = env.installer.import(PackFixtures.sampleArchive(broken))

            assertTrue(result is StoryPackInstaller.ImportResult.Rejected)
            assertEquals(before, env.registry.list())
            assertEquals(listOf("1.0.0"), env.store.listEditions("pack.paris.afterdark"))
            assertEquals(emptyList<Any>(), env.store.listEditions("pack.other"))
        }
        env.cleanup()
    }

    @Test
    fun `a hostile archive cannot damage an installed pack`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack())
            val before = env.installer.load("pack.paris.afterdark")!!.pack

            val hostile = zipWithTraversal("pack.paris.afterdark")
            val result = runCatching { env.installer.import(hostile) }
            assertTrue(result.isFailure || result.getOrNull() is StoryPackInstaller.ImportResult.Rejected)

            assertEquals(before, env.installer.load("pack.paris.afterdark")!!.pack)
            assertEquals(1, env.registry.list().size)
        }
        env.cleanup()
    }

    private fun zipWithTraversal(name: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry(StoryPackFormat.MANIFEST_PATH))
            zip.write("{}" .toByteArray())
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("../../packs/$name.json"))
            zip.write("{\"id\":\"evil\"}".toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun `an install interrupted before commit leaves only sweeepable staging`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack())

            // What a crash mid-write looks like: a staging directory and no registry entry.
            val staging = env.store.stage("crash-token")
            staging.resolve("manifest.json").writeText("{ half written")
            assertEquals(emptyList<Any>(), env.registry.list().filter { it.packId == "pack.crashed" })

            env.installer.sweep()

            assertFalse("staging must be swept", staging.exists())
            assertEquals(1, env.registry.list().size)
        }
        env.cleanup()
    }

    @Test
    fun `reinstalling over an existing edition keeps exactly one copy`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack())
            env.install(PackFixtures.pack())
            env.install(PackFixtures.pack())

            assertEquals(listOf("1.0.0"), env.store.listEditions("pack.paris.afterdark"))
            assertEquals(1, env.registry.list().size)
        }
        env.cleanup()
    }

    @Test
    fun `updating keeps the old edition and claims only the new one`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack(version = "1.0.0"))
            val result = env.install(PackFixtures.pack(version = "1.1.0"))

            val upgraded = result as StoryPackInstaller.ImportResult.Installed
            assertEquals("1.0.0", upgraded.upgradedFrom)
            assertEquals("1.1.0", upgraded.record.packVersion)
            assertEquals(listOf("1.0.0"), upgraded.record.retainedVersions)
            assertEquals(setOf("1.0.0", "1.1.0"), env.store.listEditions("pack.paris.afterdark").toSet())
        }
        env.cleanup()
    }

    @Test
    fun `deletion removes every edition and the claim, and nothing else`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack(version = "1.0.0"))
            env.install(PackFixtures.pack(version = "1.1.0"))
            env.install(PackFixtures.pack(id = "pack.other").copy(title = "Diğer"))

            val outcome = env.installer.delete("pack.paris.afterdark")
            assertTrue(outcome is StoryPackInstaller.DeleteOutcome.Deleted)

            assertNull(env.registry.get("pack.paris.afterdark"))
            assertEquals(emptyList<Any>(), env.store.listEditions("pack.paris.afterdark"))
            assertEquals("a neighbour pack is untouched", listOf("1.0.0"), env.store.listEditions("pack.other"))
        }
        env.cleanup()
    }

    @Test
    fun `deleting a pack with running stories is refused until the user insists`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack())
            val pack = env.installer.load("pack.paris.afterdark")!!.pack
            val story = env.runtime.startStory(pack, StoryInstanceId("story-live"))
            env.activeStories = mapOf("pack.paris.afterdark" to 1)

            val refused = env.installer.delete("pack.paris.afterdark")
            assertTrue(refused is StoryPackInstaller.DeleteOutcome.Blocked)
            assertNotNull(env.registry.get("pack.paris.afterdark"))

            val forced = env.installer.delete("pack.paris.afterdark", force = true) as
                StoryPackInstaller.DeleteOutcome.Deleted
            assertEquals("the save is kept", 1, forced.keptSaves)
            assertNotNull("still playable as an orphaned save", env.runtime.loadStory(story.id))
        }
        env.cleanup()
    }

    @Test
    fun `deleting something that is not installed is answered honestly`() {
        val env = PackTestEnv()
        runBlocking {
            val outcome = env.installer.delete("pack.nothing")
            assertTrue(outcome is StoryPackInstaller.DeleteOutcome.NotFound)
        }
        env.cleanup()
    }

    @Test
    fun `a failed verify is discarded rather than installed`() {
        val env = PackTestEnv()
        runBlocking {
            val bytes = PackFixtures.sampleArchive()
            val contents = StoryPackArchive.read(bytes)
            // Tamper with one content file after the manifest was minted: the digest no
            // longer matches and the install must refuse.
            val tampered = LinkedHashMap<String, ByteArray>()
            tampered[StoryPackFormat.MANIFEST_PATH] = contents.manifest.orEmpty().toByteArray()
            contents.textFiles.forEach { (k, v) -> tampered[k] = v.toByteArray() }
            val path = "${StoryPackFormat.CONTENT_DIR}/${StoryPackFormat.CHARACTERS_FILE}"
            tampered[path] = tampered.getValue(path).plus("!".toByteArray())

            val result = env.installer.import(StoryPackArchive.write(tampered))
            assertTrue(result is StoryPackInstaller.ImportResult.Rejected)
            assertEquals(emptyList<Any>(), env.registry.list())
        }
        env.cleanup()
    }

    @Test
    fun `the registry is the only witness to installation`() {
        val env = PackTestEnv()
        runBlocking {
            // Content parked on disk with no registry entry is *not* an installed pack.
            val staging = env.store.stage("parked")
            staging.resolve(StoryPackFormat.MANIFEST_PATH).writeText("{}")
            env.store.commit(staging, "pack.parked", "1.0.0")

            assertNull(env.registry.get("pack.parked"))
            assertEquals(emptyList<Any>(), env.registry.list().map { it.packId })
        }
        env.cleanup()
    }
}