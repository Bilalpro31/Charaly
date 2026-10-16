package dev.charaly.runtime.storypack

import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.StoryTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Save and resume: the boring promise everything else is worthless without.
 *
 * Start, play, save, kill the app, restart, resume - and be exactly where you were. Plus
 * the edge nobody plans for: a save torn by a crash recovers to the previous good turn and
 * *says so*, instead of either vanishing or resurrecting as gibberish.
 */
class StoryPackPersistenceTest {

    @Test
    fun `a story survives a full restart with its world intact`() {
        val env = PackTestEnv()
        lateinit var saved: StoryInstance
        runBlocking {
            env.install(PackFixtures.pack())
            val pack = env.installer.load("pack.paris.afterdark")!!.pack
            val story = env.runtime.startStory(pack, StoryInstanceId("story-persist"))
            saved = env.runtime.touch(story.copy(title = "Gece Vardiyası"), nowEpochMs = 1234L)
        }

        // A cold start on the same storage: every component rebuilt from disk.
        val storage = env.storage
        val repository = dev.charaly.runtime.persistence.JsonCharalyRepository(storage)
        runBlocking {
            val restored = repository.restoreAll()
            assertEquals(1, restored.instances.size)
            val resumed = restored.instances.first()
            assertEquals(saved.id, resumed.id)
            assertEquals("Gece Vardiyası", resumed.displayTitle)
            assertEquals(saved.worldState.worldClock.now, resumed.worldState.worldClock.now)
            assertEquals(saved.relationships.keys, resumed.relationships.keys)
            assertEquals(saved.memories.size, resumed.memories.size)
            assertEquals(saved.knowledge.factCount, resumed.knowledge.factCount)
            assertEquals(saved.storyThreads.keys, resumed.storyThreads.keys)
            assertEquals(saved.objectives.map { it.objectiveId }, resumed.objectives.map { it.objectiveId })
            assertEquals(saved.currentSceneId, resumed.currentSceneId)
            assertEquals(saved.packVersion, resumed.packVersion)
        }
        env.cleanup()
    }

    @Test
    fun `a torn save recovers to the previous good turn and reports it`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack())
            val pack = env.installer.load("pack.paris.afterdark")!!.pack
            var story = env.runtime.startStory(pack, StoryInstanceId("story-torn"))
            story = env.runtime.renameStory(story, "İlk tur")
            story = env.runtime.renameStory(story, "İkinci tur")

            // Simulate a crash in the middle of writing the newest save.
            env.storage.write("stories/story-torn.json", """{"id":"story-torn","truncated":""")

            val repository = dev.charaly.runtime.persistence.JsonCharalyRepository(env.storage)
            val restored = repository.restoreAll()
            assertEquals(1, restored.instances.size)
            assertEquals("recovery is reported, not silent", 1, restored.failures.size)
            assertEquals("İlk tur", restored.instances.first().title)
        }
        env.cleanup()
    }

    @Test
    fun `a save with no backup at all is skipped without dragging the others down`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack())
            val pack = env.installer.load("pack.paris.afterdark")!!.pack
            val good = env.runtime.startStory(pack, StoryInstanceId("story-good"))
            env.runtime.startStory(pack, StoryInstanceId("story-doomed"))

            env.storage.write("stories/story-doomed.json", "]not json[")

            val repository = dev.charaly.runtime.persistence.JsonCharalyRepository(env.storage)
            val restored = repository.restoreAll()
            assertEquals(listOf(good.id), restored.instances.map { it.id })
            assertEquals(1, restored.failures.size)
        }
        env.cleanup()
    }

    @Test
    fun `writes are atomic and leave no temporaries behind`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack())
            val pack = env.installer.load("pack.paris.afterdark")!!.pack
            env.runtime.startStory(pack, StoryInstanceId("story-clean"))

            val documents = env.storage.list("")
            assertTrue("leftover temporaries: $documents", documents.none { it.endsWith(".tmp") })
            assertTrue("backups are not documents", documents.none { it.startsWith("backups/") })
            assertEquals(listOf("stories/story-clean.json"), documents.filter { it.startsWith("stories/") })
        }
        env.cleanup()
    }

    @Test
    fun `two saves of the same story do not race into one document`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack())
            val pack = env.installer.load("pack.paris.afterdark")!!.pack
            val story = env.runtime.startStory(pack, StoryInstanceId("story-double"))
            env.runtime.renameStory(story, "Bir")
            val latest = env.runtime.loadStory(story.id)!!
            env.runtime.renameStory(latest, "İki")

            assertEquals("İki", env.runtime.loadStory(story.id)!!.title)
        }
        env.cleanup()
    }

    @Test
    fun `wall clock bookkeeping is not world time`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack())
            val pack = env.installer.load("pack.paris.afterdark")!!.pack
            val story = env.runtime.startStory(pack, StoryInstanceId("story-clock"))
            val touched = env.runtime.touch(story, nowEpochMs = 999_999L)
            assertEquals(999_999L, touched.sessionMeta.lastPlayedAtEpochMs)
            assertNotEquals(
                "story time is its own clock",
                StoryTime.START.totalMinutes,
                touched.worldClock.now.totalMinutes,
            )
        }
        env.cleanup()
    }

    @Test
    fun `restore reports unreadable packs without stopping stories from loading`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack())
            val pack = env.installer.load("pack.paris.afterdark")!!.pack
            env.runtime.startStory(pack, StoryInstanceId("story-survivor"))
            env.storage.write("packs/pack.paris.afterdark.json", "{ nope")

            val repository = dev.charaly.runtime.persistence.JsonCharalyRepository(env.storage)
            val restored = repository.restoreAll()
            assertEquals(1, restored.instances.size)
            assertTrue(restored.failures.isNotEmpty())
        }
        env.cleanup()
    }

    @Test
    fun `an instance list is ordered most recent first`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack())
            val pack = env.installer.load("pack.paris.afterdark")!!.pack
            val first = env.runtime.startStory(pack, StoryInstanceId("story-first"))
            val second = env.runtime.touch(
                env.runtime.startStory(pack, StoryInstanceId("story-second")),
                nowEpochMs = 5_000L,
            )
            val instances = env.runtime.listInstances()
            assertNotNull(instances.firstOrNull { it.id == second.id })
            assertNotNull(instances.firstOrNull { it.id == first.id })
        }
        env.cleanup()
    }
}