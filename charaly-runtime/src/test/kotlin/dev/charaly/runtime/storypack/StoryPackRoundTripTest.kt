package dev.charaly.runtime.storypack

import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.pack.LastKingdomPack
import dev.charaly.runtime.pack.MiraculousPack
import dev.charaly.runtime.pack.NeonDistrictPack
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Export, import, and the round trip between them.
 *
 * The load-bearing claim: a pack that leaves one Charaly installation and enters another is
 * *the same pack* afterwards - same content, same identity, same digest. Not approximately.
 * A distribution format that loses a sentence on the way is a distribution format nobody
 * can build on.
 */
class StoryPackRoundTripTest {

    @Test
    fun `export produces a portable archive with a manifest of its own`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack())
            val bytes = env.installer.export("pack.paris.afterdark")
            assertNotNull(bytes)

            val decoded = StoryPackDecoder.decode(bytes!!)
            assertEquals("pack.paris.afterdark", decoded.manifest?.packId)
            assertEquals("1.0.0", decoded.manifest?.packVersion)
            assertEquals(emptyList<ValidationIssue>(), decoded.issues.filter { it.severity == Severity.CRITICAL })
        }
        env.cleanup()
    }

    @Test
    fun `an exported pack re-imports into an identical story pack`() {
        val source = PackTestEnv()
        val target = PackTestEnv()
        runBlocking {
            source.install(PackFixtures.pack(PackFixtures.pack().id.value))
            val exported = source.installer.export("pack.paris.afterdark")!!
            val result = target.installer.import(exported)

            assertTrue("import said: ${(result as? StoryPackInstaller.ImportResult.Rejected)?.report?.debugLines()}", result is StoryPackInstaller.ImportResult.Installed)
            val original = source.installer.load("pack.paris.afterdark")!!.pack
            val arrived = target.installer.load("pack.paris.afterdark")!!.pack
            assertEquals(original, arrived)
            assertEquals(original.semanticVersion, arrived.semanticVersion)
        }
        source.cleanup()
        target.cleanup()
    }

    @Test
    fun `the digest survives a round trip between devices`() {
        val source = PackTestEnv()
        val target = PackTestEnv()
        runBlocking {
            source.install(PackFixtures.pack())
            val exported = source.installer.export("pack.paris.afterdark")!!
            target.installer.import(exported)

            val first = source.registry.get("pack.paris.afterdark")!!
            val second = target.registry.get("pack.paris.afterdark")!!
            assertEquals("pack digest is content, not device", first.checksum, second.checksum)
        }
        source.cleanup()
        target.cleanup()
    }

    @Test
    fun `the full demo packs survive export and import`() {
        listOf(MiraculousPack.pack, NeonDistrictPack.pack, LastKingdomPack.pack).forEach { pack ->
            val env = PackTestEnv()
            runBlocking {
                val exported = env.installer.exportPack(pack)
                val decoded = StoryPackDecoder.decode(exported)
                assertTrue(
                    "${pack.title}: ${decoded.issues.filter { it.isBlocking }.map { it.message }}",
                    decoded.installable,
                )
                assertEquals(pack, decoded.pack)
            }
            env.cleanup()
        }
    }

    @Test
    fun `a renamed archive imports to the same identity`() {
        val env = PackTestEnv()
        runBlocking {
            val bytes = PackFixtures.sampleArchive(PackFixtures.pack(id = "miraculous.paris.afterdark"))
            // The bytes never knew their name; nor does the installer.
            val result = env.installer.import(bytes)
            val record = (result as StoryPackInstaller.ImportResult.Installed).record
            assertEquals("miraculous.paris.afterdark", record.packId)
            assertEquals("1.0.0", record.packVersion)
            assertNotNull("assets resolve inside the install", env.store.assetRoot(record.packId, record.packVersion).root)
        }
        env.cleanup()
    }

    @Test
    fun `import refuses a corrupt pack and writes nothing`() {
        val env = PackTestEnv()
        runBlocking {
            val garbage = ByteArray(512) { it.toByte() }
            val result = env.installer.import(garbage)
            assertTrue(result is StoryPackInstaller.ImportResult.Rejected)
            assertEquals(emptyList<Any>(), env.registry.list().map { it.packId })
        }
        env.cleanup()
    }

    @Test
    fun `import refuses a pack with a critical finding`() {
        val env = PackTestEnv()
        runBlocking {
            val broken = PackFixtures.pack().copy(
                initialStoryThreads = emptyList(), // leaves the thread refs dangling
            )
            val result = env.installer.import(PackFixtures.sampleArchive(broken))
            assertTrue(result is StoryPackInstaller.ImportResult.Rejected)
            val report = (result as StoryPackInstaller.ImportResult.Rejected).report
            assertTrue(report.errors.isNotEmpty())
            assertEquals("kayıtlı olmamalı", emptyList<Any>(), env.registry.list())
        }
        env.cleanup()
    }

    @Test
    fun `inspect is a dry run that writes nothing`() {
        val env = PackTestEnv()
        runBlocking {
            val candidate = env.installer.inspect(PackFixtures.sampleArchive())
            assertTrue(candidate.isInstallable)
            assertTrue(candidate.report.census != null)
            assertEquals(emptyList<Any>(), env.registry.list())
            assertEquals(emptyList<Any>(), env.store.listEditions("pack.paris.afterdark"))
        }
        env.cleanup()
    }

    @Test
    fun `warnings do not block installation`() {
        val env = PackTestEnv()
        runBlocking {
            // A translation table with one unknown key: a warning by design.
            val localization = mapOf("tr" to mapOf("character.nobody.name" to "Kim"))
            val result = env.installer.import(PackFixtures.sampleArchive(PackFixtures.pack(), localization))
            val installed = result as? StoryPackInstaller.ImportResult.Installed
            assertTrue("warnings must not block: ${installed?.report?.debugLines()}", installed != null)
            assertEquals(Severity.WARNING, installed!!.report.severity)
        }
        env.cleanup()
    }

    @Test
    fun `exported archives carry no device paths`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack())
            val bytes = env.installer.export("pack.paris.afterdark")!!
            val asText = String(bytes, Charsets.ISO_8859_1)
            org.junit.Assert.assertFalse("no absolute paths in an export", asText.contains("/data/user"))
            org.junit.Assert.assertFalse(asText.contains("/home/"))
            val decoded = StoryPackDecoder.decode(bytes)
            assertTrue(decoded.contents!!.paths.all { StoryPackFormat.isSafeRelativePath(it) })
        }
        env.cleanup()
    }

    @Test
    fun `export of an unknown pack is null rather than an empty archive`() {
        val env = PackTestEnv()
        runBlocking { assertNull(env.installer.export("pack.not.installed")) }
        env.cleanup()
    }

    @Test
    fun `two exports of the same install are byte identical`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack())
            val a = env.installer.export("pack.paris.afterdark")!!
            val b = env.installer.export("pack.paris.afterdark")!!
            assertEquals(a.toList(), b.toList())
        }
        env.cleanup()
    }

    @Test
    fun `an import into a second device does not disturb the first`() {
        val source = PackTestEnv()
        val target = PackTestEnv()
        runBlocking {
            source.install(PackFixtures.pack())
            val exported = source.installer.export("pack.paris.afterdark")!!
            target.installer.import(exported)

            assertNotEquals(source.root.absolutePath, target.root.absolutePath)
            assertEquals(1, source.registry.list().size)
            assertEquals(1, target.registry.list().size)
        }
        source.cleanup()
        target.cleanup()
    }
}