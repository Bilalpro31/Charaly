package dev.charaly.app.ui

import dev.charaly.app.model.HuggingFaceServices
import dev.charaly.runtime.model.DownloadProgress
import dev.charaly.runtime.model.DownloadState
import dev.charaly.runtime.model.UnavailableModelDownloads
import dev.charaly.runtime.net.HttpStreamResponse
import dev.charaly.runtime.net.HttpTextResponse
import dev.charaly.runtime.net.HttpTransport
import dev.charaly.runtime.net.NetworkCapability
import dev.charaly.runtime.net.huggingface.HuggingFaceClient
import dev.charaly.runtime.presentation.HubSearchState
import kotlinx.coroutines.flow.toList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Model Hub's wiring: does pressing the controls reach the real network layer?
 *
 * ## Why these read the app's sources rather than calling its composables
 *
 * Compose cannot be rendered or clicked from a JVM unit test - this module has no
 * Robolectric and no device. So the checks that matter here are structural: that the
 * screen's Download control invokes a real callback rather than an empty lambda, and that
 * the view model hands that callback a pipeline wired to an HTTPS transport rather than
 * the offline refusal.
 *
 * Both of those are the difference between "the Model Library has a download button" and
 * "the Model Library downloads models", and the second claim is the one the product makes.
 * A source-level assertion is weaker than a rendered one, and this file says so rather than
 * pretending otherwise - but it is a real check, and the pipeline's own behaviour is
 * verified against real bytes in `DownloadStateMachineTest`.
 */
class ModelHubWiringTest {

    private fun appSource(relativePath: String): File =
        File("src/main/kotlin/dev/charaly/app/$relativePath")

    private fun runtimeSource(relativePath: String): File =
        File("../charaly-runtime/src/main/kotlin/dev/charaly/runtime/$relativePath")

    // ------------------------------------------------------------------
    // The screen is wired, not decorative
    // ------------------------------------------------------------------

    /**
     * No control in the model library is a dead lambda.
     *
     * The bug this exists to prevent is the one that shipped: a `DOWNLOAD` button whose
     * `onClick` was `{}`. It looks finished, it is reachable, it is correctly labelled, and
     * it does nothing - which is worse than not offering it at all, because it teaches the
     * user that the rest of the library's controls cannot be trusted either.
     *
     * An empty `onClick` is banned outright in the Hub screen for the same reason.
     */
    @Test
    fun `no control anywhere in the ui has an empty click handler`() {
        // Every screen, not just the model library. The bug this class was written for -
        // a DOWNLOAD button with `onClick = {}` - was not confined to one screen, and
        // checking only the screens I happened to touch would let the next one ship the
        // same defect. An empty handler is banned outright across the UI layer.
        val screens = File("src/main/kotlin/dev/charaly/app/ui/screens")
            .walkTopDown()
            .filter { it.extension == "kt" }
            .toList()
        assertTrue("no screens were found to check", screens.isNotEmpty())

        for (file in screens) {
            val text = file.readText()
            val match = Regex("""onClick\s*=\s*\{\s*\}""").find(text)
            assertTrue(
                "${file.name} has a control whose onClick does nothing" +
                    (match?.let { " at character ${it.range.first}" } ?: ""),
                match == null,
            )
        }
    }

    /**
     * The library screen renders real Hub results, not only the bundled list.
     *
     * The failure this guards is a screen that still looks complete while filtering a
     * hardcoded array: the search field works, it just never leaves the device.
     */
    @Test
    fun `the hub screen renders hub results`() {
        // One browse list, not an "installed" section and a "reference" section. Two
        // visually different sections was how the previous screen made installed models
        // feel like a settings page and the Hub like a shop.
        val source = appSource("ui/screens/ModelHubScreen.kt").readText()
        assertTrue(
            "the hub no longer renders anything from the Hub",
            source.contains("discoverable"),
        )
        assertTrue(
            "the hub no longer has a search field",
            source.contains("CharalySearchField("),
        )
        assertTrue(
            "the hub no longer offers a filter row",
            source.contains("CharalyPillRow("),
        )
        // And a Hub repository opens its files inline rather than through another route,
        // because choosing a quantisation is part of browsing rather than a separate
        // destination.
        assertTrue(
            "the hub no longer renders a repository's files inline",
            source.contains("RepoDetailSurface("),
        )
        assertTrue(
            "the hub no longer offers a per-file download",
            source.contains("onDownload("),
        )
    }

    /**
     * A download progress surface exists and is driven by the real state.
     *
     * Not a spinner: the surface takes the pipeline's own [dev.charaly.app.ui.ModelDownloads.UiState],
     * whose fields are measured byte counts.
     */
    @Test
    fun `download progress is rendered from measured state`() {
        val source = appSource("ui/screens/ModelHubScreen.kt").readText()
        assertTrue("there is no download progress surface", source.contains("fun DownloadSurface"))
        // The bar must be able to be indeterminate, which is the honest response to an
        // unknown total. A screen that always draws a percentage is one that invented one.
        assertTrue(
            "the progress bar cannot express an unknown total",
            source.contains("fraction == null"),
        )
    }

    /**
     * The app's view model performs Hub search rather than filtering locally.
     *
     * The decisive assertion is that it calls the Hub client. Everything else - the debounce,
     * the filters, the empty states - is decoration around whether a request is made.
     */
    @Test
    fun `the view model queries the hub rather than filtering locally`() {
        val source = appSource("ui/CharalyViewModel.kt").readText()
        assertTrue(
            "the view model no longer calls the Hub's search endpoint",
            source.contains("client.searchGguf("),
        )
        assertTrue(
            "the view model no longer lists a repository's files",
            source.contains("listFiles("),
        )
        // And it starts real downloads through the port rather than writing a file itself.
        assertTrue(
            "the view model no longer starts a download through the pipeline",
            source.contains("downloads.download("),
        )
    }

    /**
     * A completed download activates the model.
     *
     * A model that downloads, verifies and registers successfully but then needs a second
     * tap to become usable is a broken install from the user's point of view: the flow
     * says DOWNLOAD then INSTALLING then READY, and USE MODEL is what follows.
     */
    @Test
    fun `a finished download activates the model`() {
        val source = appSource("ui/CharalyViewModel.kt").readText()
        val readyBranch = source.substringAfter("DownloadState.READY ->").substringBefore("DownloadState.FAILED")
        assertTrue(
            "a READY download does not refresh the registry",
            readyBranch.contains("registry.list()"),
        )
        assertTrue(
            "a READY download does not make the model selectable",
            readyBranch.contains("selectModel("),
        )
    }

    /**
     * Pause, resume and cancel all reach the real pipeline.
     *
     * Each maps to a distinct call because they do distinct things: pause leaves the partial
     * file for a ranged request to continue from, cancel stops it, and resume re-issues the
     * *same resolved item* - which is the only object carrying the URL and checksum.
     */
    @Test
    fun `transfer controls reach the pipeline`() {
        val source = appSource("ui/CharalyViewModel.kt").readText()
        assertTrue("pause does nothing", source.contains("fun pauseDownload() = downloads.pause("))
        assertTrue("cancel does nothing", source.contains("downloads.cancel("))
        assertTrue(
            "resume does not re-issue the download",
            source.contains("fun resumeDownload()"),
        )
        // The item must be retained, or resume has nothing real to re-issue.
        assertTrue(
            "the resolved download item is not retained for resume",
            source.contains("pendingDownloadItem"),
        )
    }

    // ------------------------------------------------------------------
    // Production wiring
    // ------------------------------------------------------------------

    /**
     * The composition root supplies the real Hub services.
     *
     * A view model defaulting to the offline refusal would compile, run, and offer a
     * library with no discovery at all - so this asserts the production path explicitly.
     */
    @Test
    fun `the composition root wires the real hub`() {
        val source = appSource("CharalyApplication.kt").readText()
        assertTrue(
            "the application no longer constructs HuggingFaceServices",
            source.contains("HuggingFaceServices(this, modelRegistry)"),
        )
        val factory = appSource("ui/CharalyViewModel.kt").readText()
            .substringAfter("fun factory(application: CharalyApplication)")
        assertTrue(
            "the production factory no longer passes the Hub services",
            factory.contains("hub = application.huggingFace"),
        )
        assertTrue(
            "the production factory no longer passes the download pipeline",
            factory.contains("downloads = application.huggingFace.downloads"),
        )
    }

    /**
     * The download pipeline's availability is what the library reports.
     *
     * Not a hardcoded boolean: derived from the injected manager, so a build that genuinely
     * cannot download says so and a build that can offers the button.
     */
    @Test
    fun `download availability comes from the pipeline`() {
        val source = appSource("ui/CharalyViewModel.kt").readText()
        assertTrue(
            "the library reports availability from a constant rather than the pipeline",
            source.contains("downloads.availability() == null"),
        )
    }

    /**
     * The manifest permits the one network feature, and only that one.
     *
     * INTERNET is required for a model download and ACCESS_NETWORK_STATE for the offline
     * indicator. Anything else - a server permission, a boot receiver - would contradict
     * the product's local-first claim.
     */
    @Test
    fun `the manifest requests only the network permissions it uses`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue("INTERNET is required for model download", manifest.contains("android.permission.INTERNET"))
        assertTrue(
            "ACCESS_NETWORK_STATE is required for the offline indicator",
            manifest.contains("android.permission.ACCESS_NETWORK_STATE"),
        )
        for (forbidden in listOf(
            "READ_EXTERNAL_STORAGE",
            "WRITE_EXTERNAL_STORAGE",
            "MANAGE_EXTERNAL_STORAGE",
            "QUERY_ALL_PACKAGES",
            "RECEIVE_BOOT_COMPLETED",
        )) {
            assertFalse("the manifest requests $forbidden, which the product does not use", manifest.contains(forbidden))
        }
        // And cleartext stays off, so a model cannot be swapped in transit.
        assertTrue("cleartext traffic is still permitted", manifest.contains("usesCleartextTraffic=\"false\""))
    }

    /**
     * The storage transport is HTTPS-only.
     *
     * A cleartext download would let anyone on the network replace the weights of a model
     * the user is about to trust with their story, so this is a security property rather
     * than a preference.
     */
    @Test
    fun `the http transport refuses cleartext`() {
        val source = appSource("net/AndroidHttpTransport.kt").readText()
        assertTrue(
            "the transport no longer rejects non-HTTPS urls",
            source.contains("requireHttps"),
        )
    }

    // ------------------------------------------------------------------
    // The offline contract
    // ------------------------------------------------------------------

    /**
     * The offline state names what still works.
     *
     * This is the load-bearing string of the whole offline design. A library that reads as
     * broken in airplane mode makes the user think the app is broken; one that says
     * "installed models still work" makes the same state obviously fine.
     */
    @Test
    fun `offline copy says installed models still work`() {
        val source = File(
            "../charaly-runtime/src/main/kotlin/dev/charaly/runtime/presentation/ModelHub.kt",
        ).readText()
        assertTrue(
            "the offline state no longer reassures about installed models",
            source.contains("Installed models still work"),
        )
    }

    /**
     * An offline view model still offers its installed models.
     *
     * Verified by construction: the offline refusal is reached through a *search* action,
     * which cannot touch the installed list, so there is no path by which failing discovery
     * empties the models a user already has.
     */
    @Test
    fun `an offline search cannot clear installed models`() {
        val source = appSource("ui/CharalyViewModel.kt").readText()
        val searchBody = source.substringAfter("fun searchHub(query: String)").substringBefore("fun openHubRepo")
        assertFalse(
            "the offline search path clears installed models",
            searchBody.contains("installedModels ="),
        )
        // And it must point at the alternative rather than declaring defeat. A failure
        // message with no next step is the thing the brief rules out.
        assertTrue(
            "the offline path does not say what still works",
            searchBody.contains("HubSearchState.offline()"),
        )
    }

    // ------------------------------------------------------------------
    // Progress honesty
    // ------------------------------------------------------------------

    /**
     * A projected progress frame never invents a number.
     *
     * Checked by running the real projection: an unknown total must produce a null fraction
     * rather than a zero, because "not started" and "we do not know the size" are different
     * states that look identical on a bar.
     */
    @Test
    fun `progress projection reports no fraction for an unknown total`() {
        val frame = DownloadProgress(
            catalogId = "m",
            state = DownloadState.DOWNLOADING,
            bytesDownloaded = 900L,
            totalBytes = 0L,
        )
        val projected = frame.toUiState()

        assertNull("an unknown total must not produce a fraction", projected.fraction)
        assertEquals("900 B", projected.transferred)
        // Not measurable is an empty string, not "0 B/s".
        assertEquals("", projected.speed)
        assertEquals("", projected.eta)
        assertTrue("a running download must be pausable", projected.isPausable)
    }

    /**
     * A measured frame reports exactly what was measured.
     */
    @Test
    fun `progress projection reports measured values`() {
        val frame = DownloadProgress(
            catalogId = "m",
            state = DownloadState.DOWNLOADING,
            bytesDownloaded = 512L,
            totalBytes = 1024L,
            speedBytesPerSecond = 1_048_576.0,
            bytesPerSecondLabel = "1.0 MB/s",
            etaLabel = "0s left",
        )
        val projected = frame.toUiState()

        assertEquals(0.5f, projected.fraction!!, 0.001f)
        assertEquals("512 B of 1 kB", projected.transferred)
        assertEquals("1.0 MB/s", projected.speed)
        assertEquals("0s left", projected.eta)
    }

    /**
     * The offline fallback manager refuses immediately rather than faking progress.
     *
     * The pipeline is not wired in a JVM test, and the honest behaviour of the fallback is
     * to emit a single FAILED frame explaining itself. A frame that reported progress would
     * make the test environment's limitation invisible in the UI.
     */
    @Test
    fun `the offline fallback reports one honest failure frame`() = kotlinx.coroutines.test.runTest {
        val manager = UnavailableModelDownloads()
        val item = dev.charaly.runtime.model.ModelCatalogItem(id = "m", name = "M", publisher = "p")

        val frames = manager.download(item).toList()

        assertEquals("the fallback must not simulate a transfer", 1, frames.size)
        assertEquals(DownloadState.FAILED, frames.single().state)
        assertNotNull("the refusal must carry a reason", frames.single().failure)
        assertTrue("the refusal must explain itself", frames.single().message.isNotBlank())

        // And it says so up front rather than only when pressed. The library reads this to
        // decide whether to offer a Download control at all, so a fallback that reported
        // availability *and then* refused would produce a dead button - the exact bug the
        // real pipeline replaced.
        assertNotNull(
            "an offline build must report that it cannot download",
            manager.availability(),
        )
        assertTrue("downloads must be reported unavailable", !manager.canDownload(item))
    }

    /**
     * A transport that serves nothing still yields a real, explained refusal.
     *
     * This is the shape every network failure takes: a code of 0 and a reason, never an
     * exception escaping into the UI and never a silent empty result.
     */
    @Test
    fun `a dead transport produces an explained failure not an exception`() =
        kotlinx.coroutines.test.runTest {
            val dead = object : HttpTransport {
                override suspend fun getText(url: String, headers: Map<String, String>): HttpTextResponse =
                    HttpTextResponse(code = 0, body = "", headers = mapOf("charaly-error" to "offline"))

                override suspend fun openStream(url: String, rangeStart: Long?, rangeEnd: Long?): HttpStreamResponse =
                    throw java.io.IOException("offline")
            }
            val client = HuggingFaceClient(dead)

            // The client answers with an empty page rather than throwing, so a screen shows
            // its own empty state instead of an error dialog for a network that is merely
            // not there.
            val page = client.searchGguf("qwen")
            assertEquals(0, page.repos.size)

            assertFalse("a dead socket must not look reachable", isReachable(dead))
        }

    private suspend fun isReachable(transport: HttpTransport): Boolean =
        transport.getText("https://example.invalid").isSuccessful

    // ------------------------------------------------------------------
    // Identity honesty
    // ------------------------------------------------------------------

    /**
     * No performance claim without a measurement.
     *
     * The brief is explicit that "Fast on this device" may only appear after a benchmark
     * has actually run. Asserted on the source because the label is a string in a composable
     * that cannot be rendered here.
     */
    @Test
    fun `the hub makes no unmeasured performance claim`() {
        val source = File(
            "../charaly-runtime/src/main/kotlin/dev/charaly/runtime/presentation/ModelHub.kt",
        ).readText()
        for (claim in listOf("Fast on this device", "Runs fast", "Blazing", "Instant")) {
            assertFalse("the Hub claims \"$claim\" without a benchmark", source.contains(claim))
        }
    }

    /**
     * The download pipeline is the only path to a model file.
     *
     * Asserted by checking that the runtime's own install path exists and is the one the Hub
     * joins to. The join is [dev.charaly.runtime.presentation.ModelHubPresenter.toCatalogItem],
     * which is what makes a Hub repository installable at all.
     */
    @Test
    fun `a hub file becomes an installable catalog item`() {
        val source = File(
            "../charaly-runtime/src/main/kotlin/dev/charaly/runtime/presentation/ModelHub.kt",
        ).readText()
        assertTrue(
            "there is no join from a Hub file to the download pipeline",
            source.contains("fun toCatalogItem("),
        )
        assertTrue(
            "the join does not attach download metadata",
            source.contains("DownloadMetadata("),
        )
        assertTrue(
            "the join does not set a download url",
            source.contains("url = file.downloadUrl"),
        )
    }

    /**
     * The Hub's offline flag defaults to the pessimistic reading.
     *
     * A device with a captive portal has connectivity and no Hub. Reporting "online" there
     * produces a search that fails after a timeout instead of immediately, so the default is
     * the fast, correct-by-default answer.
     */
    @Test
    fun `the network capability defaults to pessimistic`() {
        assertTrue(
            "with no connectivity argument the Hub is assumed unreachable",
            NetworkCapability.offline(),
        )
        assertFalse(
            "with connectivity the Hub is assumed reachable",
            NetworkCapability.offline(connectivityAvailable = true),
        )
        // The offline indicator's own wording, with a transport present - which is the
        // production case. Asserted because it is user-visible copy that has changed
        // before: "Offline only" would imply the whole app is dead, and "Offline Ready" is
        // the claim we want to make, since everything installed keeps working.
        //
        // `transportInstalled` is a global the app sets at startup, so it is set and
        // restored here rather than left to whichever test happened to run first.
        val previous = NetworkCapability.transportInstalled
        try {
            NetworkCapability.transportInstalled = true
            assertEquals("Offline Ready", NetworkCapability.statusLabel(internetAvailable = false))
            assertEquals("Ready", NetworkCapability.statusLabel(internetAvailable = true))

            // With no transport at all, the wording escalates - and this is the state the
            // bare JVM runtime reports, which is why the two must be distinguishable.
            NetworkCapability.transportInstalled = false
            assertEquals("Offline only", NetworkCapability.statusLabel(internetAvailable = true))
        } finally {
            NetworkCapability.transportInstalled = previous
        }
    }

    /**
     * The Hub's search state is a sealed hierarchy, not a pair of booleans.
     *
     * "Loading" and "loading with no results yet" need different rendering, and "failed" and
     * "offline" need different copy and different actions. A single `isLoading` flag forces
     * every screen to guess, and the offline case is the one where guessing wrong tells a
     * user their library is broken.
     */
    @Test
    fun `hub search distinguishes its states`() {
        val states: List<HubSearchState> = listOf(
            HubSearchState.Idle,
            HubSearchState.Searching("qwen"),
            HubSearchState.Loaded("qwen", 30, hasMore = true),
            HubSearchState.offline(),
        )
        // Distinct types, so a `when` over them is exhaustive and a new state cannot be
        // silently unhandled on one screen and handled on another.
        assertEquals(4, states.distinct().size)
        assertTrue(states.last().let { it is HubSearchState.Failed && it.offline })
    }
}

