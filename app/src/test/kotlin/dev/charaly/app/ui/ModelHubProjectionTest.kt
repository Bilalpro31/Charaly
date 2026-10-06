package dev.charaly.app.ui

import dev.charaly.runtime.model.DownloadProgress
import dev.charaly.runtime.model.DownloadState
import dev.charaly.runtime.model.DownloadUnavailable
import dev.charaly.runtime.presentation.ModelFilter
import dev.charaly.runtime.presentation.ModelStagePresenter
import dev.charaly.runtime.presentation.TransferStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE MODEL HUB'S HONESTY.
 *
 * ## The one rule
 *
 * **A control that says DOWNLOAD downloads, and a number on screen was measured.**
 *
 * Everything below is that rule from different angles. The hub is the screen where a user
 * is most likely to be misled, because a model library is where invented numbers hide: a
 * plausible speed, a "recommended" badge with nothing behind it, a progress bar for a
 * transfer whose size is not published.
 *
 * ## Why these are JVM tests
 *
 * The download pipeline lives in `charaly-runtime` and emits real progress frames. The
 * projection from those frames to what a screen draws is a pure function, so it can be
 * driven with real byte counts here, in milliseconds, with no device and no network. If a
 * stage were to start interpolating a percentage, or a card were to gain a "Fast" label,
 * these tests would fail the same day rather than in a user's hands.
 */
class ModelHubProjectionTest {

    private fun frame(
        state: DownloadState,
        downloaded: Long = 0L,
        total: Long = 0L,
        message: String = "",
        path: String = "/data/charaly/tmp/Qwen3-4B.gguf.part",
        speedLabel: String = "",
        etaLabel: String = "",
        failure: DownloadUnavailable? = null,
    ) = DownloadProgress(
        catalogId = "Qwen/Qwen3-4B-GGUF",
        state = state,
        bytesDownloaded = downloaded,
        totalBytes = total,
        temporaryPath = path,
        message = message,
        failure = failure,
        bytesPerSecondLabel = speedLabel,
        etaLabel = etaLabel,
    )

    // ------------------------------------------------------------------
    // Every pipeline stage has a word
    // ------------------------------------------------------------------

    @Test
    fun `every download state projects to a labelled stage`() {
        // Total by construction: a state the screen cannot describe becomes a state the
        // screen renders as "something is happening", which is the bug.
        DownloadState.entries.forEach { state ->
            val stage = ModelStagePresenter.stageOf(state)
            assertTrue("$state has no label", stage.label.isNotBlank())
        }
        assertEquals(DownloadState.entries.size, DownloadState.entries.distinct().size)
    }

    @Test
    fun `the pipeline's real stages are the ones the brief names`() {
        // DOWNLOADING -> VERIFYING -> INSTALLING -> READY is the sequence a resumable,
        // checksum-verifying pipeline actually runs. Verifying and installing being real
        // stages rather than animations is the point: they are work.
        assertEquals(TransferStage.DOWNLOADING, ModelStagePresenter.stageOf(DownloadState.DOWNLOADING))
        assertEquals(TransferStage.VERIFYING, ModelStagePresenter.stageOf(DownloadState.VERIFYING))
        assertEquals(TransferStage.INSTALLING, ModelStagePresenter.stageOf(DownloadState.REGISTERING))
        assertEquals(TransferStage.READY, ModelStagePresenter.stageOf(DownloadState.READY))
    }

    @Test
    fun `ready to fetch covers every pre-transfer state`() {
        // Found, sized and known to load: nothing has moved, and the screen must not draw a
        // bar for a transfer that has not begun.
        for (state in listOf(DownloadState.CATALOG, DownloadState.DISCOVERED, DownloadState.QUEUED)) {
            assertEquals(state.name, TransferStage.READY_TO_FETCH, ModelStagePresenter.stageOf(state))
        }
    }

    @Test
    fun `only running stages report as running`() {
        assertTrue(TransferStage.DOWNLOADING.isRunning)
        assertTrue(TransferStage.VERIFYING.isRunning)
        assertTrue(TransferStage.INSTALLING.isRunning)
        for (stage in listOf(TransferStage.PAUSED, TransferStage.READY, TransferStage.FAILED)) {
            assertFalse("$stage is not running work", stage.isRunning)
            assertTrue("$stage is a resting state", stage.isTerminal)
        }
    }

    // ------------------------------------------------------------------
    // Progress reports measured values only
    // ------------------------------------------------------------------

    @Test
    fun `an unknown total produces no fraction rather than a zero`() {
        // "Not started" and "we do not know how big this is" are different states that look
        // identical on a bar, so the second must not be drawn as the first.
        val projected = ModelStagePresenter.transfer(frame(DownloadState.DOWNLOADING, downloaded = 900L))
        assertNull("an unknown total must not produce a fraction", projected.fraction)
        assertTrue("an unknown total must render as indeterminate", projected.isIndeterminate)
    }

    @Test
    fun `a measured download reports exactly what was measured`() {
        val projected = ModelStagePresenter.transfer(
            frame(
                state = DownloadState.DOWNLOADING,
                downloaded = 512L,
                total = 1024L,
                speedLabel = "1.0 MB/s",
                etaLabel = "4 min left",
            ),
        )
        assertEquals(0.5f, projected.fraction!!, 0.001f)
        assertEquals("512 B of 1 kB", projected.transferred)
        assertEquals("1.0 MB/s", projected.speed)
        assertEquals("4 min left", projected.remaining)
        assertTrue("a running download must be pausable", projected.canPause)
        assertTrue("a running download must be cancellable", projected.canCancel)
        assertFalse("a running download cannot be resumed", projected.canResume)
    }

    @Test
    fun `an unmeasured speed is absent rather than zero`() {
        // "0 B/s" is a measurement of nothing and reads as a stalled transfer.
        val projected = ModelStagePresenter.transfer(frame(DownloadState.DOWNLOADING, downloaded = 10L, total = 100L))
        assertEquals("", projected.speed)
        assertEquals("", projected.remaining)
    }

    @Test
    fun `a paused download reports no fraction`() {
        // A bar that fills while a download is paused is a lie.
        val projected = ModelStagePresenter.transfer(
            frame(DownloadState.PAUSED, downloaded = 500L, total = 1000L),
        )
        assertEquals(TransferStage.PAUSED, projected.stage)
        assertNull(projected.fraction)
        assertTrue("a paused download must be resumable", projected.canResume)
        assertFalse("a paused download is not running", projected.isRunning)
    }

    @Test
    fun `a verification stage reports no progress of its own`() {
        // Verification is real work with no bytes attached; the bar must not sit at 100%
        // implying a second transfer.
        val projected = ModelStagePresenter.transfer(
            frame(DownloadState.VERIFYING, downloaded = 1024L, total = 1024L, message = "Checking the download"),
        )
        assertEquals(TransferStage.VERIFYING, projected.stage)
        assertNull(projected.fraction)
        assertEquals("Checking the download", projected.stageLabel)
        assertTrue(projected.isRunning)
    }

    @Test
    fun `the file name is temporary until verification passes`() {
        val projected = ModelStagePresenter.transfer(frame(DownloadState.DOWNLOADING))
        assertEquals("Qwen3-4B.gguf.part", projected.fileName)
    }

    // ------------------------------------------------------------------
    // Failure says a sentence, not a code
    // ------------------------------------------------------------------

    @Test
    fun `a failure names the problem and offers something to do`() {
        val projected = ModelStagePresenter.transfer(
            frame(DownloadState.FAILED, message = "HTTP 403"),
        )
        assertEquals(TransferStage.FAILED, projected.stage)
        assertTrue("a failure must say something", projected.failureLabel.isNotBlank())
        assertTrue("the technical half is collapsed by default", projected.technicalDetail.isNotBlank())
        assertTrue("a failed download must be retryable", projected.canRetry)
        // Cancel is deliberately absent: a failed transfer is already stopped, and offering
        // "Cancel" on it would describe an action that has nothing to do.
        assertFalse("a failed download has nothing left to cancel", projected.canCancel)
        assertFalse("a failed download is not running", projected.isRunning)
        assertTrue(projected.hasFailure)
    }

    @Test
    fun `a sentence is never repeated beneath itself`() {
        // "Couldn't finish" over "Couldn't finish" reads as the app not knowing what it
        // said, so the technical half is only populated when the message really is
        // technical.
        val friendly = ModelStagePresenter.transfer(
            frame(DownloadState.FAILED, message = "That model could not be fetched."),
        )
        assertEquals("", friendly.technicalDetail)

        val technical = ModelStagePresenter.transfer(
            frame(DownloadState.FAILED, message = "HTTP 503 while fetching sha256"),
        )
        assertTrue(technical.technicalDetail.isNotBlank())
    }

    @Test
    fun `an unsupported model says so rather than offering a download`() {
        val projected = ModelStagePresenter.transfer(frame(DownloadState.UNSUPPORTED))
        assertEquals(TransferStage.UNSUPPORTED, projected.stage)
        assertTrue(
            "an unsupported model must explain itself: '${projected.failureLabel}'",
            projected.failureLabel.isNotBlank(),
        )
        assertFalse(projected.canRetry)
    }

    @Test
    fun `an unavailable pipeline reports its own reason`() {
        // Each refusal carries a reason rather than a bare code, so the screen has something
        // honest to show and a test can tell the refusals apart. At least one of the two
        // halves must be populated: silence is the only unacceptable outcome.
        val reasons = listOf(
            DownloadUnavailable.OfflineCoreBuild,
            DownloadUnavailable.Network("no route to host"),
            DownloadUnavailable.InsufficientStorage,
            DownloadUnavailable.ChecksumMismatch("abc", "def"),
            DownloadUnavailable.ResumeUnsupported,
            DownloadUnavailable.Cancelled,
            DownloadUnavailable.Other("the file moved"),
        )
        for (reason in reasons) {
            val projected = ModelStagePresenter.transfer(
                frame(DownloadState.FAILED, message = "", failure = reason),
            )
            assertTrue(
                "${reason::class.simpleName} must produce a sentence, not silence",
                projected.failureLabel.isNotBlank() || projected.technicalDetail.isNotBlank(),
            )
            assertTrue("${reason::class.simpleName} must be reported as a failure", projected.hasFailure)
        }
    }

    // ------------------------------------------------------------------
    // The hero
    // ------------------------------------------------------------------

    @Test
    fun `with nothing installed the hero says so and asks for a model`() {
        val hero = ModelStagePresenter.hero(
            snapshot = dev.charaly.runtime.presentation.ModelLibraryPresenter.build(
                installedModels = emptyList(),
                catalog = emptyList(),
                activeModelId = "",
                loadedModelId = null,
            ),
            engineLabel = "llama.cpp",
        )
        assertEquals("", hero.id)
        assertFalse("a hero with no model must not link anywhere", hero.isLinked)
        assertEquals("NOT INSTALLED", hero.stateLabel)
        assertTrue("an empty hero must still ask for something", hero.problemLabel.isNotBlank())
        assertTrue(hero.needsAttention)
    }

    @Test
    fun `the hero reports only a measured performance`() {
        // A rate only exists once something has actually generated tokens. Asserted with
        // both arguments because the whole property is that the presenter *passes it
        // through* rather than deriving one.
        val snapshot = dev.charaly.runtime.presentation.ModelLibraryPresenter.build(
            installedModels = listOf(anInstalledModel()),
            catalog = emptyList(),
            activeModelId = "local-1",
            loadedModelId = "local-1",
        )
        assertEquals(
            "",
            ModelStagePresenter.hero(snapshot, "llama.cpp").measuredPerformance,
        )
        assertEquals(
            "31 tok/s",
            ModelStagePresenter.hero(snapshot, "llama.cpp", measuredPerformance = "31 tok/s")
                .measuredPerformance,
        )
    }

    @Test
    fun `the hero names the model and links to it when one is installed`() {
        val snapshot = dev.charaly.runtime.presentation.ModelLibraryPresenter.build(
            installedModels = listOf(anInstalledModel()),
            catalog = emptyList(),
            activeModelId = "local-1",
            loadedModelId = "local-1",
        )
        val hero = ModelStagePresenter.hero(snapshot, "llama.cpp")
        assertEquals("local-1", hero.id)
        assertTrue("an installed hero must link to its detail page", hero.isLinked)
        assertEquals("READY", hero.stateLabel)
        assertTrue(hero.isLoaded)
        assertFalse(hero.needsAttention)
        assertEquals("Qwen3 4B", hero.modelName)
    }

    // ------------------------------------------------------------------
    // The status line and storage
    // ------------------------------------------------------------------

    @Test
    fun `the status line reads honestly in all four states`() {
        assertEquals("Local · Offline ready", ModelStagePresenter.statusLine(isOnline = false, hasInstalledModel = true))
        assertEquals("Local · No model yet", ModelStagePresenter.statusLine(isOnline = false, hasInstalledModel = false))
        assertEquals("Local · Online", ModelStagePresenter.statusLine(isOnline = true, hasInstalledModel = true))
        assertEquals("Local · Ready to install", ModelStagePresenter.statusLine(isOnline = true, hasInstalledModel = false))
    }

    @Test
    fun `offline says what still works`() {
        // The local-first claim is at stake here: a library that reads as broken in airplane
        // mode tells the user the *app* is broken.
        assertTrue(
            "offline copy must say installed models still work",
            ModelStagePresenter.OFFLINE_BODY.contains("Installed models"),
        )
        assertTrue(
            "offline copy must say worlds and stories still work",
            ModelStagePresenter.OFFLINE_BODY.contains("world"),
        )
        assertTrue(ModelStagePresenter.OFFLINE_TITLE.isNotBlank())
        assertTrue(ModelStagePresenter.OFFLINE_ACTION.isNotBlank())
    }

    @Test
    fun `storage is blank rather than zero when there is nothing installed`() {
        assertEquals("", ModelStagePresenter.storageLabel(0L))
        assertEquals("", ModelStagePresenter.storageLabel(-1L))
        assertTrue(ModelStagePresenter.storageLabel(1_500_000L).isNotBlank())
    }

    // ------------------------------------------------------------------
    // Filters
    // ------------------------------------------------------------------

    @Test
    fun `no filter promises a speed nobody measured`() {
        // "Fast" needs a benchmark, and a filter that promised speed on a device that had
        // never run the model would be a lie with a dropdown attached.
        val labels = ModelFilter.entries.map { it.label }
        for (claim in listOf("Fast", "Quick", "Blazing", "Instant", "Fastest")) {
            assertFalse("a filter claims \"$claim\"", labels.any { it.contains(claim, ignoreCase = true) })
        }
        assertEquals(5, labels.size)
    }

    @Test
    fun `the filters are the ones the brief asks for`() {
        assertEquals(
            listOf("All", "Compatible", "Fits this device", "Small", "Installed"),
            ModelFilter.entries.map { it.label },
        )
    }

    private fun anInstalledModel() = dev.charaly.runtime.model.InstalledModel(
        id = "local-1",
        displayName = "Qwen3 4B",
        absolutePath = "/data/charaly/models/qwen3-4b.gguf",
        sizeBytes = 2_600_000_000L,
        architecture = "qwen3",
        quantization = "Q4_K_M",
        contextLength = 40960,
        parameterCount = 4_020_000_000L,
        origin = dev.charaly.runtime.model.ModelOrigin.DOWNLOADED,
        verified = true,
        installedAtEpochMs = 1L,
    )
}
