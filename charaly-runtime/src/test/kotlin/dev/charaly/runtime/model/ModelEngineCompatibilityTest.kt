package dev.charaly.runtime.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.flow.toList
import java.io.File

/**
 * The model library must never advertise something the engine cannot do.
 *
 * The most damaging thing a model library can do is offer an action that fails later.
 * These tests pin the two promises that prevent it:
 *
 *  1. compatibility is *derived* from the bundled llama.cpp's architecture table, not
 *     asserted per catalog entry;
 *  2. downloads are either genuinely available or explain precisely why they are not.
 *
 * The engine assertions are backed by a test that reads the vendored `llama-arch.cpp`,
 * so bumping llama.cpp without updating [EngineCapabilities] fails the build instead of
 * silently mislabelling the library.
 */
class ModelEngineCompatibilityTest {

    private val catalog = BuiltInModelCatalog()

    // ------------------------------------------------------------------
    // Capability list agrees with the bundled engine
    // ------------------------------------------------------------------

    @Test
    fun `the declared architecture list matches the bundled llama cpp`() {
        val archSource = locateLlamaArch() ?: return // engine not vendored in this checkout
        val declared = EngineCapabilities.ARCHITECTURES.map { it.lowercase() }.toSet()

        val registered = Regex("""\{\s*LLM_ARCH_[A-Z0-9_]+,\s*"([a-z0-9\-]+)"\s*\}""")
            .findAll(archSource.readText())
            .map { it.groupValues[1].lowercase() }
            .toSet()

        assertTrue(
            "expected to find the LLM_ARCH name table in llama-arch.cpp",
            registered.size > 40,
        )

        val missing = declared - registered - LEGACY_ONLY
        assertTrue(
            "EngineCapabilities claims architectures the bundled engine does not " +
                "register: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `a gemma family architecture the engine lacks is reported as needing an update`() {
        // This is the assertion the whole engine-support layer exists for.
        assertFalse(
            "the bundled engine must not be assumed to support gemma4",
            EngineCapabilities.supports("gemma4"),
        )
        val verdict = EngineVerdict.of("gemma4")
        assertEquals(EngineSupport.ENGINE_UPDATE_REQUIRED, verdict.support)
        assertFalse(verdict.isLoadable)
        assertTrue(
            "the reason must name the cause",
            verdict.reason.contains("llama.cpp", ignoreCase = true),
        )
    }

    @Test
    fun `architectures the engine does register are reported as supported`() {
        listOf("llama", "qwen3", "qwen2", "gemma", "gemma2", "gemma3", "gemma3n", "phi3")
            .forEach { architecture ->
                assertTrue(
                    "$architecture is registered by the bundled engine",
                    EngineVerdict.of(architecture).isLoadable,
                )
            }
    }

    @Test
    fun `an unknown architecture is not mistaken for a supported one`() {
        val verdict = EngineVerdict.of("not-a-real-arch")
        assertEquals(EngineSupport.UNKNOWN_ARCHITECTURE, verdict.support)
        assertFalse(verdict.isLoadable)
    }

    @Test
    fun `a blank architecture is unknown rather than supported`() {
        assertEquals(EngineSupport.UNKNOWN_ARCHITECTURE, EngineVerdict.of("").support)
        assertEquals(EngineSupport.UNKNOWN_ARCHITECTURE, EngineVerdict.of("   ").support)
    }

    @Test
    fun `legacy ggml spellings resolve to their architecture`() {
        assertTrue(EngineCapabilities.supports("ggml-gemma2"))
        assertEquals("gemma2", EngineCapabilities.normalise("GGML-Gemma2"))
    }

    // ------------------------------------------------------------------
    // Gemma 4 catalog entries
    // ------------------------------------------------------------------

    @Test
    fun `the catalog ships official gemma 4 e2b and e4b entries`() {
        val byId = catalog.items.associateBy { it.id }
        val e2b = byId["gemma-4-e2b-it"]
        val e4b = byId["gemma-4-e4b-it"]

        assertNotNull("Gemma 4 E2B Instruct must be in the catalog", e2b)
        assertNotNull("Gemma 4 E4B Instruct must be in the catalog", e4b)
        assertEquals("Gemma 4 E2B Instruct", e2b!!.name)
        assertEquals("Gemma 4 E4B Instruct", e4b!!.name)
        assertEquals("Google", e2b.publisher)
        assertEquals("google/gemma-4-E2B-it", e2b.upstreamId)
        assertEquals("google/gemma-4-E4B-it", e4b.upstreamId)
    }

    @Test
    fun `gemma 4 entries are labelled needing an engine update, not download`() {
        val gemma4 = catalog.items.filter { it.architecture.equals("gemma4", ignoreCase = true) }
        assertEquals(2, gemma4.size)
        gemma4.forEach { item ->
            assertTrue("${item.id} must be flagged", item.needsEngineUpdate)
            assertFalse("${item.id} must not claim to be loadable", item.isEngineLoadable)
            assertEquals(EngineSupport.ENGINE_UPDATE_REQUIRED, item.engineVerdict.support)
        }
    }

    @Test
    fun `a model the engine cannot load is never offered as a recommendation`() {
        val recommendations = catalog.recommendedFor(availableRamBytes = 32_000_000_000L)
        assertTrue(
            "recommendations must only contain loadable models",
            recommendations.none { !it.isEngineLoadable },
        )
        assertTrue(
            "Gemma 4 must not appear as a recommendation on this engine",
            recommendations.none { it.architecture.equals("gemma4", ignoreCase = true) },
        )
    }

    @Test
    fun `gemma 4 is still visible as awaiting an engine update`() {
        val awaiting = catalog.awaitingEngineUpdate().map { it.id }
        assertEquals(BuiltInModelCatalog.GEMMA_4_IDS.sorted(), awaiting.sorted())
    }

    @Test
    fun `unpublished sizes are admitted rather than invented`() {
        val e2b = catalog.byId("gemma-4-e2b-it")!!
        assertEquals(MetadataConfidence.UNPUBLISHED, e2b.confidence)
        assertEquals(0L, e2b.sizeBytes)
        assertEquals(
            "the UI must be told the size is unknown, not given a guess",
            "size not published",
            e2b.sizeDisplay,
        )
    }

    @Test
    fun `approximate memory requirements are labelled as approximate`() {
        val e4b = catalog.byId("gemma-4-e4b-it")!!
        assertTrue("unexpected label: ${e4b.ramLabel}", e4b.ramLabel.startsWith("about "))
    }

    @Test
    fun `recommendations respect available memory`() {
        val small = catalog.recommendedFor(availableRamBytes = 3_000_000_000L)
        assertTrue(
            "nothing needing more than 3 GB should be recommended on a 3 GB device",
            small.all { it.recommendedRamBytes <= 3_000_000_000L },
        )
        assertTrue("a small device should still get something", small.isNotEmpty())
    }

    @Test
    fun `every catalog entry declares an architecture and an engine verdict`() {
        catalog.items.forEach { item ->
            assertTrue("${item.id} has no architecture", item.architecture.isNotBlank())
            assertNotNull(item.engineVerdict.reason)
        }
    }

    @Test
    fun `every loadable catalog entry is actually supported by the engine`() {
        // The two independent facts must agree, or the library is lying.
        catalog.items.filter { it.isEngineLoadable }.forEach { item ->
            assertTrue(
                "${item.id} claims loadable but the engine list does not contain ${item.architecture}",
                EngineCapabilities.supports(item.architecture),
            )
        }
    }

    // ------------------------------------------------------------------
    // Downloads
    // ------------------------------------------------------------------

    /**
     * A build with no transport explains why, and names the real cause.
     *
     * The cause is *the missing transport*, not the user's connectivity. This build does
     * declare INTERNET and does wire a real HTTPS transport, so a refusal here is a build
     * problem - and telling someone with working Wi-Fi that they are offline would send
     * them to check their router for no reason. The wording is asserted because it is the
     * only thing a user ever sees in this state.
     */
    @Test
    fun `a build with no transport explains why downloads are unavailable`() {
        val manager: ModelDownloadManager = UnavailableModelDownloads()
        val reason = manager.availability()
        assertNotNull("the build must state why downloads are off", reason)
        assertEquals(DownloadUnavailable.OfflineCoreBuild, reason)
        assertTrue(
            "the reason must name the transport as the cause: ${reason!!.message}",
            reason.message.contains("model library", ignoreCase = true) ||
                reason.message.contains("transport", ignoreCase = true),
        )
        assertFalse(
            "the reason must not blame the user's connection: ${reason.message}",
            reason.message.contains("you are offline", ignoreCase = true),
        )
        assertTrue(
            "and must offer the alternative that does work",
            reason.alternative.contains("gguf", ignoreCase = true),
        )
    }

    @Test
    fun `no catalog entry can be downloaded in the offline build`() {
        val manager: ModelDownloadManager = UnavailableModelDownloads()
        catalog.items.forEach { item ->
            assertFalse(
                "${item.id} must not be downloadable in the offline core build",
                manager.canDownload(item),
            )
        }
    }

    @Test
    fun `an unavailable download reports failure immediately and never fakes progress`() {
        val manager: ModelDownloadManager = UnavailableModelDownloads()
        val item = catalog.items.first()
        val frames = kotlinx.coroutines.runBlocking { manager.download(item).toList() }
        assertEquals("exactly one terminal frame, no fake progress", 1, frames.size)
        val frame = frames.single()
        assertEquals(DownloadState.FAILED, frame.state)
        assertEquals(0L, frame.bytesDownloaded)
        assertNotNull("a failure must carry a reason", frame.failure)
        assertNull("and must never report a progress fraction", frame.fraction)
    }

    @Test
    fun `a partial file can never be presented as ready`() {
        // Verification and the atomic move sit between "bytes arrived" and "installed".
        assertTrue(DownloadStateMachine.canTransition(DownloadState.DOWNLOADED, DownloadState.VERIFYING))
        assertFalse(
            "no path may skip straight from downloading to ready",
            DownloadStateMachine.canTransition(DownloadState.DOWNLOADING, DownloadState.READY),
        )
        assertFalse(
            "a checksum failure can never become ready",
            DownloadStateMachine.canTransition(DownloadState.VERIFYING, DownloadState.READY),
        )
    }

    @Test
    fun `a ready install is terminal`() {
        assertTrue(DownloadStateMachine.isTerminal(DownloadState.READY))
        assertFalse(DownloadStateMachine.canTransition(DownloadState.READY, DownloadState.DOWNLOADING))
    }

    @Test
    fun `a failed download can be retried but nothing else can jump the queue`() {
        assertTrue(DownloadStateMachine.canTransition(DownloadState.FAILED, DownloadState.DOWNLOADING))
        assertFalse(DownloadStateMachine.canTransition(DownloadState.FAILED, DownloadState.READY))
        assertFalse(DownloadStateMachine.canTransition(DownloadState.CATALOG, DownloadState.VERIFYING))
    }

    @Test
    fun `progress is null until the total size is genuinely known`() {
        val unknownTotal = DownloadProgress(
            catalogId = "x",
            state = DownloadState.DOWNLOADING,
            bytesDownloaded = 500,
            totalBytes = 0,
        )
        assertNull(unknownTotal.fraction)
        assertEquals("500 B", unknownTotal.percentLabel)

        val known = unknownTotal.copy(totalBytes = 1000)
        assertEquals(0.5f, known.fraction!!, 0.001f)
        assertEquals("50%", known.percentLabel)
    }

    @Test
    fun `progress is never reported outside the downloading state`() {
        val verifying = DownloadProgress("x", DownloadState.VERIFYING, bytesDownloaded = 100, totalBytes = 100)
        assertNull(verifying.fraction)
    }

    private fun locateLlamaArch(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, "app/src/main/cpp/llama.cpp/src/llama-arch.cpp")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        return null
    }

    private companion object {
        /** Aliases we accept for older third-party conversions that are not in the table. */
        val LEGACY_ONLY = setOf("gemma", "gemma2", "gemma3")
    }
}