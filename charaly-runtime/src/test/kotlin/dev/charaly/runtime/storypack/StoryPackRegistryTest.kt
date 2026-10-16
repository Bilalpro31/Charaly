package dev.charaly.runtime.storypack

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry: what claims to be installed, and what that claim survives.
 *
 * The registry is written *last*, so the states a crash can leave behind are "nothing" and
 * "everything", never "half". These tests pin that ordering down by observing what a
 * restarted installation sees.
 */
class StoryPackRegistryTest {

    @Test
    fun `installation records what was installed`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack())
            val record = env.registry.get("pack.paris.afterdark")!!

            assertEquals("1.0.0", record.packVersion)
            assertEquals("Paris After Dark", record.title)
            assertEquals("Charaly", record.author)
            assertEquals(InstalledPackRecord.SOURCE_BUILTIN, record.source)
            assertTrue(record.enabled)
            assertTrue(record.checksum.isNotBlank())
            assertTrue(record.installedAtEpochMs > 0)
            assertEquals(2, record.characterCount)
            assertEquals(2, record.locationCount)
        }
        env.cleanup()
    }

    @Test
    fun `the registry survives a restart`() {
        val env = PackTestEnv()
        runBlocking { env.install(PackFixtures.pack()) }

        // Cold start on the same tree.
        val restarted = PackTestEnv()
        val sameTreeRegistry = JsonStoryPackRegistry(env.storage)
        runBlocking {
            sameTreeRegistry.reload()
            assertEquals(1, sameTreeRegistry.list().size)
            assertEquals("pack.paris.afterdark", sameTreeRegistry.list().first().packId)
        }
        env.cleanup()
        restarted.cleanup()
    }

    @Test
    fun `a corrupt registry document is empty, not fatal`() {
        val env = PackTestEnv()
        runBlocking {
            env.storage.write(JsonStoryPackRegistry.DOCUMENT, "{ definitely not json")
            val registry = JsonStoryPackRegistry(env.storage)
            registry.reload()
            assertEquals(emptyList<Any>(), registry.list())
        }
        env.cleanup()
    }

    @Test
    fun `removing a record forgets the pack without touching saves`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack())
            val story = env.runtime.startStory(
                env.installer.load("pack.paris.afterdark")!!.pack,
                dev.charaly.runtime.domain.StoryInstanceId("story-keep"),
            )
            env.registry.remove("pack.paris.afterdark")

            assertNull(env.registry.get("pack.paris.afterdark"))
            assertNotNull("the save is untouched", env.runtime.loadStory(story.id))
        }
        env.cleanup()
    }

    @Test
    fun `records are listed in a stable order`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack())
            env.install(PackFixtures.pack(id = "pack.zeta", version = "1.0.0").copy(title = "Zeta"))
            val titles = env.registry.list().map { it.title.lowercase() }
            assertEquals(titles.sorted(), titles)
        }
        env.cleanup()
    }

    @Test
    fun `enabling and disabling is a recorded state, not a deletion`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack())
            env.installer.setEnabled("pack.paris.afterdark", false)
            val record = env.registry.get("pack.paris.afterdark")!!
            assertFalse(record.enabled)
            assertEquals("1.0.0", record.packVersion)
            assertTrue(env.store.hasEdition("pack.paris.afterdark", "1.0.0"))
        }
        env.cleanup()
    }

    @Test
    fun `in-memory registry behaves like the durable one`() {
        val registry = InMemoryStoryPackRegistry()
        runBlocking {
            val record = InstalledPackRecord(packId = "p", packVersion = "1.0.0", title = "P")
            registry.put(record)
            assertEquals(record, registry.get("p"))
            registry.remove("p")
            assertNull(registry.get("p"))
        }
    }

    @Test
    fun `a record knows every edition it carries`() {
        val record = InstalledPackRecord(
            packId = "p",
            packVersion = "1.2.0",
            title = "P",
            retainedVersions = listOf("1.1.0", "1.0.0"),
        )
        assertTrue(record.carriesEdition("1.0.0"))
        assertTrue(record.carriesEdition("1.2.0"))
        assertFalse(record.carriesEdition("2.0.0"))
    }

    @Test
    fun `the registry document path is internal and stable`() {
        assertEquals("registry/story-packs.json", JsonStoryPackRegistry.DOCUMENT)
    }
}