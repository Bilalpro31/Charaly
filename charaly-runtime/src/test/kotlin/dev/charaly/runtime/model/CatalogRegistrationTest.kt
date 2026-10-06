package dev.charaly.runtime.model

import dev.charaly.runtime.persistence.InMemoryCharalyStorage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE ONE REGISTRY, REACHED TWO WAYS.
 *
 * ## What these tests protect
 *
 * The brief's rule is absolute and easy to state: an imported GGUF and a downloaded GGUF
 * are the same kind of thing, and the chat must not care which arrived. That rule is easy
 * to state and easy to break, because the two paths are written in two different layers:
 *
 * ```
 *   SAF import  -> app/ModelManager.toInstalled()  -> id = "local-<slug>-<hash>"
 *   Hub download -> HuggingFaceModelDownloads      -> id = catalogId
 * ```
 *
 * Both call `registry.register`, both produce an `InstalledModel`, and neither can see the
 * other. So the invariants below are asserted against the *registry's* view - what a caller
 * can actually observe - rather than against either implementation in isolation.
 *
 * ## What "the same id" has to mean
 *
 * Not literally the same string: a downloaded model's identity legitimately includes the
 * repository it came from, and an imported file has no catalog entry. What must hold is
 * that the id is stable across restarts, that it is the id the selection layer resolves,
 * and that one physical file never appears twice.
 */
class CatalogRegistrationTest {

    // ==================================================================
    // 1. Identity survives a registry round-trip
    // ==================================================================

    @Test
    fun `a downloaded model's id is preserved through persistence`() = runTest {
        // A real JsonModelRegistry over real storage, then a *second* registry instance
        // reading the same document. That second instance is a process restart, which is
        // exactly the claim being made - a fake in-memory registry cannot demonstrate it.
        val storage = InMemoryCharalyStorage()
        val downloaded = InstalledModel(
            // What HuggingFaceModelDownloads actually registers: the catalog id.
            id = "qwen3-1.7b-q4",
            displayName = "Qwen 3 1.7B Instruct",
            absolutePath = "/data/charaly/models/qwen3-1.7b-q4-q4_k_m.gguf",
            sizeBytes = 1_120_000_000L,
            origin = ModelOrigin.DOWNLOADED,
            catalogId = "qwen3-1.7b-q4",
        )
        JsonModelRegistry(storage).register(downloaded)

        val afterRestart = JsonModelRegistry(storage)

        val resolved = afterRestart.list().single()
        assertEquals("the id a story binds to must survive a restart", "qwen3-1.7b-q4", resolved.id)
        assertEquals(
            "the file the loader opens must survive a restart",
            downloaded.absolutePath,
            resolved.absolutePath,
        )
        assertEquals(ModelOrigin.DOWNLOADED, resolved.origin)
        assertEquals(
            "the active model survives too",
            "qwen3-1.7b-q4",
            afterRestart.activeId(),
        )
    }

    @Test
    fun `the active model is the one the selection layer resolves`() = runTest {
        val registry = InMemoryModelRegistry()
        val imported = InstalledModel(
            id = "local-qwen3-1-7b-4242",
            displayName = "qwen3-1.7b",
            absolutePath = "/data/charaly/models/qwen3-1.7b.gguf",
            sizeBytes = 1_120_000_000L,
            origin = ModelOrigin.IMPORTED,
        )
        val downloaded = InstalledModel(
            id = "gemma-2-2b-q4",
            displayName = "Gemma 2 2B",
            absolutePath = "/data/charaly/models/gemma-2-2b-q4_k_m.gguf",
            sizeBytes = 1_700_000_000L,
            origin = ModelOrigin.DOWNLOADED,
            catalogId = "gemma-2-2b-q4",
        )
        registry.register(imported)
        registry.register(downloaded)
        registry.setActive(downloaded.id)

        // The exact call the view model makes when it builds the one authoritative answer.
        val selection = ModelSelectionResolver.resolve(
            binding = ModelBindingResolver.resolve(
                packDefaultProfileId = "",
                installedModel = registry.list().first { it.id == registry.activeId() },
            ),
            installed = registry.list(),
            activeModelId = registry.activeId().orEmpty(),
            fileExists = { true },
        )

        assertEquals("gemma-2-2b-q4", selection.modelId)
        assertEquals(downloaded.absolutePath, selection.absolutePath)
        assertTrue("a bound, present, present-in-registry model can generate", selection.canGenerate)
        assertNotNull("the id must be one the registry knows", registry.get(selection.modelId))
    }

    // ==================================================================
    // 2. One file, one entry
    // ==================================================================

    @Test
    fun `registering the same file twice does not create a second entry`() = runTest {
        val registry = InMemoryModelRegistry()
        val model = InstalledModel(
            id = "local-model-99",
            displayName = "model",
            absolutePath = "/data/charaly/models/model.gguf",
            sizeBytes = 4096L,
            origin = ModelOrigin.IMPORTED,
        )
        registry.register(model)
        registry.register(model.copy(sizeBytes = 4096L, verified = true))

        assertEquals("one file is one model", 1, registry.list().size)
        assertEquals("local-model-99", registry.list().single().id)
    }

    @Test
    fun `a re-registered download keeps its identity and updates its facts`() = runTest {
        val registry = InMemoryModelRegistry()
        val before = InstalledModel(
            id = "qwen3-4b-q4",
            displayName = "Qwen 3 4B Instruct",
            absolutePath = "/data/charaly/models/qwen3-4b-q4_k_m.gguf",
            sizeBytes = 2_600_000_000L,
            origin = ModelOrigin.DOWNLOADED,
            verified = false,
        )
        registry.register(before)

        // Re-downloading the same artifact: same id, header now read, now verified.
        registry.register(
            before.copy(
                architecture = "Qwen3",
                quantization = "Q4_K_M",
                contextLength = 8192,
                verified = true,
            ),
        )

        val after = registry.list().single()
        assertEquals(1, registry.list().size)
        assertEquals("qwen3-4b-q4", after.id)
        assertEquals("Qwen3", after.architecture)
        assertTrue("a re-read header marks it verified", after.verified)
    }

    // ==================================================================
    // 3. Deleting the active model must not strand the engine
    // ==================================================================

    @Test
    fun `removing the active model leaves a usable active model behind`() = runTest {
        val registry = InMemoryModelRegistry()
        val first = InstalledModel(
            id = "a",
            displayName = "a",
            absolutePath = "/m/a.gguf",
            sizeBytes = 1L,
        )
        val second = InstalledModel(
            id = "b",
            displayName = "b",
            absolutePath = "/m/b.gguf",
            sizeBytes = 1L,
        )
        registry.register(first)
        registry.register(second)
        assertEquals("the first registration activates", "a", registry.activeId())

        registry.remove("a")
        assertEquals("removing the active model must promote another, not blank the field", "b", registry.activeId())
        assertNotNull(registry.get(registry.activeId().orEmpty()))
    }

    @Test
    fun `removing the last model leaves an honest empty selection`() = runTest {
        val registry = InMemoryModelRegistry()
        registry.register(
            InstalledModel(id = "only", displayName = "only", absolutePath = "/m/only.gguf", sizeBytes = 1L),
        )
        registry.remove("only")

        // Blank, not a dangling id: `ModelSelectionResolver` turns a blank into
        // ModelSelection.NONE, which is what the chat renders as "no model installed"
        // rather than as a file that cannot be opened.
        assertEquals("", registry.activeId().orEmpty())
        val selection = ModelSelectionResolver.resolve(
            binding = ModelBindingResolver.resolve(packDefaultProfileId = "", installedModel = null),
            installed = registry.list(),
            activeModelId = registry.activeId().orEmpty(),
            fileExists = { true },
        )
        assertEquals(dev.charaly.runtime.model.ModelBlockReason.NOT_INSTALLED, selection.blocked)
    }

    }