package dev.charaly.runtime.model

import dev.charaly.runtime.presentation.ModelLibraryPresenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the model library is allowed to promise.
 *
 * The catalog contains models this build cannot load. The presenter's job is to make
 * that visible in the UI model rather than letting a dead "Download" button imply
 * otherwise - a user who is told to import a file that still will not load has been
 * actively misled.
 */
class ModelLibraryPresenterTest {

    private val catalog = BuiltInModelCatalog().items

    private fun build(downloadsAvailable: Boolean = false) = ModelLibraryPresenter.build(
        installedModels = emptyList(),
        catalog = catalog,
        activeModelId = "",
        loadedModelId = null,
        downloadsAvailable = downloadsAvailable,
        availableRamBytes = 8_000_000_000L,
    )

    @Test
    fun `gemma 4 is never offered as installable`() {
        val snapshot = build()
        val installable = (snapshot.recommended + snapshot.others).map { it.id }
        assertFalse(
            "Gemma 4 must not appear in the installable catalog",
            installable.any { it.startsWith("gemma-4") },
        )
    }

    @Test
    fun `gemma 4 is still shown, in its own honest section`() {
        val snapshot = build()
        val awaiting = snapshot.awaitingEngineUpdate.map { it.id }
        assertEquals(
            BuiltInModelCatalog.GEMMA_4_IDS.sorted(),
            awaiting.sorted(),
        )
        snapshot.awaitingEngineUpdate.forEach { card ->
            assertTrue(
                "the reason must name the engine, not blame the network",
                card.reason.contains("llama.cpp", ignoreCase = true),
            )
            assertTrue("the upstream id must be shown", card.upstreamId.isNotBlank())
        }
    }

    @Test
    fun `a model needing an engine update is never given a download action`() {
        val snapshot = build(downloadsAvailable = true)
        // Even with downloads enabled, an unloadable model offers nothing.
        (snapshot.recommended + snapshot.others).forEach { card ->
            assertTrue(
                "${card.id} is offered a download it cannot honour",
                !card.needsEngineUpdate,
            )
        }
    }

    @Test
    fun `every offered download is genuinely enabled by the build`() {
        val withDownloads = build(downloadsAvailable = true)
        val withoutDownloads = build(downloadsAvailable = false)

        assertTrue("the build says it can download", withDownloads.canDownload)
        assertFalse("this build cannot download", withoutDownloads.canDownload)
        withoutDownloads.recommended.forEach { card ->
            assertFalse("${card.id} offers a download in an offline build", card.actions.canDownload)
        }
    }

    @Test
    fun `a disabled action always carries a reason`() {
        val snapshot = build()
        (snapshot.recommended + snapshot.others).forEach { card ->
            if (!card.actions.canDownload) {
                assertTrue(
                    "${card.id} has a disabled download with no explanation",
                    card.actions.disabledReason.isNotBlank(),
                )
            }
        }
    }

    @Test
    fun `the download note explains the offline build rather than blaming the model`() {
        val snapshot = build()
        assertTrue(snapshot.downloadNote.contains("network", ignoreCase = true))
    }

    @Test
    fun `models too large for the device are not recommended`() {
        val small = ModelLibraryPresenter.build(
            installedModels = emptyList(),
            catalog = catalog,
            activeModelId = "",
            loadedModelId = null,
            downloadsAvailable = true,
            availableRamBytes = 2_500_000_000L,
        )
        small.recommended.forEach { card ->
            assertTrue(
                "${card.id} is recommended but needs more than the device has",
                card.fitsDevice,
            )
        }
    }

    @Test
    fun `an installed gemma 4 file is labelled rather than silently offered`() {
        // Someone could import a Gemma 4 GGUF by hand. The file being present does not
        // mean this engine can read it, and the card must say so.
        val card = ModelLibraryPresenter.modelCard(
            model = InstalledModel(
                id = "imported-gemma4",
                displayName = "gemma-4-E2B-it-Q4_K_M.gguf",
                absolutePath = "/data/gemma4.gguf",
                architecture = "gemma4",
            ),
            isActive = false,
            isLoaded = false,
            availableRamBytes = 8_000_000_000L,
            downloadsAvailable = false,
        )
        assertEquals("Needs engine update", card.engineSupportLabel)
        assertTrue(card.needsEngineUpdate)
        assertTrue(card.engineVerdictReason.contains("llama.cpp", ignoreCase = true))
        assertFalse(
            "an unloadable file must not be offered as usable",
            card.actions.canUse,
        )
    }

    @Test
    fun `a supported installed model carries no engine warning`() {
        val card = ModelLibraryPresenter.modelCard(
            model = InstalledModel(
                id = "local-qwen",
                displayName = "Qwen 3 4B",
                absolutePath = "/data/qwen.gguf",
                architecture = "qwen3",
            ),
            isActive = true,
            isLoaded = true,
            availableRamBytes = 8_000_000_000L,
            downloadsAvailable = false,
        )
        assertEquals("", card.engineSupportLabel)
        assertFalse(card.needsEngineUpdate)
        assertTrue(card.actions.canUse)
    }
}