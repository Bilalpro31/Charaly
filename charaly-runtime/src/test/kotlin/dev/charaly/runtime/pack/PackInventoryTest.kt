package dev.charaly.runtime.pack

import dev.charaly.runtime.engine.StoryInstanceFactory
import dev.charaly.runtime.domain.StoryInstanceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackInventoryTest {
    @Test
    fun `the three demo packs are populated`() {
        val packs = DemoStoryPacks.all
        assertEquals(3, packs.size)
        packs.forEach { pack ->
            assertTrue("${pack.title} needs characters", pack.characters.size >= 5)
            assertTrue("${pack.title} needs locations", pack.locations.size >= 6)
            assertTrue("${pack.title} needs events", pack.events.size >= 5)
            assertTrue("${pack.title} needs scenarios", pack.scenarios.size >= 3)
            assertTrue("${pack.title} needs personas", pack.personas.size >= 3)
            assertTrue("${pack.title} needs threads", pack.initialStoryThreads.size >= 5)
            assertTrue("${pack.title} needs lore", pack.lore.isNotEmpty())
            assertTrue("${pack.title} needs a theme", pack.identity.theme.primaryHex.isNotBlank())
        }
    }

    @Test
    fun `demo packs are isolated from each other`() {
        val packs = DemoStoryPacks.all
        val characterIds = packs.map { pack -> pack.characters.map { it.id.value }.toSet() }
        val locationIds = packs.map { pack -> pack.locations.map { it.id.value }.toSet() }
        val threadIds = packs.map { pack -> pack.initialStoryThreads.map { it.id.value }.toSet() }
        for (i in packs.indices) {
            for (j in packs.indices) {
                if (i == j) continue
                assertTrue("character ids overlap", characterIds[i].intersect(characterIds[j]).isEmpty())
                assertTrue("location ids overlap", locationIds[i].intersect(locationIds[j]).isEmpty())
                assertTrue("thread ids overlap", threadIds[i].intersect(threadIds[j]).isEmpty())
            }
        }
    }

    @Test
    fun `every demo pack starts a story`() {
        DemoStoryPacks.all.forEach { pack ->
            val instance = StoryInstanceFactory.create(pack, StoryInstanceId("inventory-${pack.id.value}"))
            assertEquals(pack.characters.size, instance.characters.size)
            assertTrue(instance.conversation.entries.isEmpty())
            assertTrue(instance.chapters.isNotEmpty())
        }
    }
}
