package dev.charaly.runtime.storypack

import dev.charaly.runtime.domain.StoryInstanceId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Versioning, and the promise that makes it worth having: **a story plays the edition it
 * started with.**
 *
 * Silent save corruption is the outcome this whole file exists to prevent. When the pack
 * moves on and the old edition is still installed, the story carries on untouched. When the
 * old edition is gone, the story says so in plain Turkish and stops - it does not quietly
 * adopt the new plot and hope nobody notices.
 */
class StoryPackVersionTest {

    @Test
    fun `a story pins the pack edition it was opened from`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack(version = "1.1.0"))
            val pack = env.installer.load("pack.paris.afterdark", "1.1.0")!!.pack
            val story = env.runtime.startStory(pack, StoryInstanceId("story-pinned"))
            assertEquals("1.1.0", story.packVersion)
            assertEquals("pack.paris.afterdark", story.storyPackId.value)
        }
        env.cleanup()
    }

    @Test
    fun `installing a newer edition leaves the old one on disk and playable`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack(version = "1.1.0"))
            val old = env.installer.load("pack.paris.afterdark", "1.1.0")!!.pack
            val story = env.runtime.startStory(old, StoryInstanceId("story-old"))

            env.install(PackFixtures.pack(version = "1.2.0").copy(description = "Yeni baskı"))

            val record = env.registry.get("pack.paris.afterdark")!!
            assertEquals("1.2.0", record.packVersion)
            assertTrue("old editions are retained", record.retainedVersions.contains("1.1.0"))
            assertTrue(env.store.hasEdition("pack.paris.afterdark", "1.1.0"))

            val compatibility = env.installer.compatibilityFor(story)
            assertEquals(PackCompatibility.COMPATIBLE, compatibility.status)
            assertTrue(compatibility.isPlayable)
        }
        env.cleanup()
    }

    @Test
    fun `a story reads its own edition's content, not the installed one`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack(version = "1.0.0"))
            val old = env.installer.load("pack.paris.afterdark", "1.0.0")!!.pack
            val story = env.runtime.startStory(old, StoryInstanceId("story-edition"))

            env.install(
                PackFixtures.pack(version = "2.0.0").copy(
                    title = "Paris After Dark II",
                    description = "Bambaşka bir gece",
                ),
            )

            // The library shows the new edition...
            assertEquals("Paris After Dark II", env.registry.get("pack.paris.afterdark")!!.title)
            // ...and the running story still sees the one it began with.
            val served = env.runtime.packFor(story)!!
            assertEquals("Paris After Dark", served.title)
            assertEquals("1.0.0", served.semanticVersion)
        }
        env.cleanup()
    }

    @Test
    fun `losing the pinned edition demands migration instead of migrating`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack(version = "1.0.0"))
            val story = env.runtime.startStory(
                env.installer.load("pack.paris.afterdark")!!.pack,
                StoryInstanceId("story-orphan"),
            )

            // The edition the story needs is taken away behind its back.
            env.store.deleteEdition("pack.paris.afterdark", "1.0.0")
            env.install(PackFixtures.pack(version = "2.0.0"))

            val compatibility = env.installer.compatibilityFor(story)
            assertEquals(PackCompatibility.MIGRATION_REQUIRED, compatibility.status)
            assertFalse("no silent migration", compatibility.isPlayable)
            assertTrue(compatibility.message.contains("1.0.0"))
            assertTrue(compatibility.message.contains("2.0.0"))
        }
        env.cleanup()
    }

    @Test
    fun `a save whose pack vanished says so and keeps its state`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack())
            val story = env.runtime.startStory(
                env.installer.load("pack.paris.afterdark")!!.pack,
                StoryInstanceId("story-nopack"),
            )
            env.installer.delete("pack.paris.afterdark", force = true)

            val reloaded = env.runtime.loadStory(story.id)!!
            assertEquals("save data is intact", story.worldState.worldClock.now, reloaded.worldState.worldClock.now)
            val compatibility = env.installer.compatibilityFor(reloaded)
            assertEquals(PackCompatibility.PACK_MISSING, compatibility.status)
            assertTrue(compatibility.message.contains("yüklü değil"))
        }
        env.cleanup()
    }

    @Test
    fun `a save from before pinning follows the installed edition`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack())
            val legacy = env.runtime
                .startStory(env.installer.load("pack.paris.afterdark")!!.pack, StoryInstanceId("story-legacy"))
                .copy(packVersion = "")

            val compatibility = env.installer.compatibilityFor(legacy)
            assertEquals(PackCompatibility.UNPINNED_LEGACY, compatibility.status)
            assertTrue(compatibility.isPlayable)
        }
        env.cleanup()
    }

    @Test
    fun `reinstalling the same edition replaces it without losing it`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack())
            val again = env.install(PackFixtures.pack())
            assertTrue(again is StoryPackInstaller.ImportResult.Installed)
            val record = env.registry.get("pack.paris.afterdark")!!
            assertEquals("1.0.0", record.packVersion)
            assertEquals("reinstall keeps one copy", listOf("1.0.0"), env.store.listEditions("pack.paris.afterdark"))
        }
        env.cleanup()
    }

    @Test
    fun `ten editions can coexist on one device`() {
        val env = PackTestEnv()
        runBlocking {
            (1..10).forEach { minor ->
                env.install(PackFixtures.pack(version = "1.$minor.0"))
            }
            val record = env.registry.get("pack.paris.afterdark")!!
            assertEquals("1.10.0", record.packVersion)
            assertEquals(10, env.store.listEditions("pack.paris.afterdark").size)
            assertEquals(9, record.retainedVersions.size)

            // A story from the very first edition is still compatible, because it is still
            // here. This is what retention buys.
            val old = env.installer.editionFor("pack.paris.afterdark", "1.1.0")
            assertEquals("1.1.0", old?.semanticVersion)
        }
        env.cleanup()
    }

    @Test
    fun `the compatibility report is verdict, message and permission in one`() {
        listOf(
            PackCompatibilityReport.evaluate("p", "1.0.0", "1.0.0") to PackCompatibility.COMPATIBLE,
            PackCompatibilityReport.evaluate("p", "1.0.0", "1.1.0") to PackCompatibility.MIGRATION_REQUIRED,
            PackCompatibilityReport.evaluate("p", "1.0.0", null) to PackCompatibility.PACK_MISSING,
            PackCompatibilityReport.evaluate("p", "", "1.0.0") to PackCompatibility.UNPINNED_LEGACY,
        ).forEach { (report, expected) ->
            assertEquals(expected, report.status)
            assertTrue(report.message.isNotBlank())
            assertEquals(expected == PackCompatibility.COMPATIBLE || expected == PackCompatibility.UNPINNED_LEGACY, report.isPlayable)
        }
    }
}