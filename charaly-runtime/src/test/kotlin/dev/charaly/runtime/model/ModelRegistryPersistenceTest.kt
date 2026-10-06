package dev.charaly.runtime.model

import dev.charaly.runtime.persistence.InMemoryCharalyStorage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * After a process death the registry - the authoritative "what do I have" - must
 * come back exactly as it was. Native residency does not; that is the point.
 */
class ModelRegistryPersistenceTest {

    private fun model(id: String, path: String = "/models/$id.gguf") = InstalledModel(
        id = id,
        displayName = id,
        absolutePath = path,
        sizeBytes = 1024L,
        architecture = "llama",
        sha256 = "ab$id",
        installedAtEpochMs = 1L,
    )

    @Test
    fun `registered models survive a registry reload`() = runTest {
        val storage = InMemoryCharalyStorage()
        val first = JsonModelRegistry(storage)
        first.register(model("a"))
        first.register(model("b"))
        first.setActive("b")

        val second = JsonModelRegistry(storage)
        assertEquals(listOf("a", "b"), second.list().map { it.id })
        assertEquals("b", second.activeId())
    }

    @Test
    fun `a removed model stays removed across reload`() = runTest {
        val storage = InMemoryCharalyStorage()
        val first = JsonModelRegistry(storage)
        first.register(model("a"))
        first.register(model("b"))
        first.remove("a")

        val second = JsonModelRegistry(storage)
        assertNull(second.get("a"))
        assertEquals(1, second.list().size)
    }

    @Test
    fun `a corrupt registry document falls back to empty rather than crashing`() = runTest {
        val storage = InMemoryCharalyStorage()
        storage.write("models/registry.json", "{ not json")
        val registry = JsonModelRegistry(storage)
        assertTrue(registry.list().isEmpty())
    }

    @Test
    fun `a missing file on disk is a registry concern, not a crash`() = runTest {
        val storage = InMemoryCharalyStorage()
        val registry = JsonModelRegistry(storage)
        registry.register(model("ghost", path = "/models/gone.gguf"))
        // The resolver blocks via ModelSelection, the registry keeps the record.
        assertEquals("ghost", registry.get("ghost")?.id)
    }
}
