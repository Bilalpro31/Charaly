package dev.charaly.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Model Library had two composables that existed but were never placed in the screen.
 *
 * `LibraryTitle` (the "Models" heading, the working indicator and the Offline dot) and
 * `ModelSearchField` (the filter over the on-device and reference sections) were both
 * declared, both correct, and both dead. The screen rendered its sections with no heading
 * at all, `isOnline` was an unused parameter so "Offline" was unreachable, and
 * `onQueryChange` never fired so the library could not be filtered without opening the
 * Hub search first.
 *
 * That class of bug - **declared but never placed** - is what this file exists to catch, and
 * it is the failure mode of the redesign too. The hub now has a header, a hero, a transfer
 * surface, an installed section, a search field, a filter row and an import action, all of
 * which are composables someone could have written and forgotten to call.
 *
 * There is no Robolectric in this build - the app layer is compiled and unit tested on the
 * JVM with `isReturnDefaultValues = true` - so a composable cannot be rendered here. What
 * *can* be asserted, and what actually regressed, is that the screen body places the
 * composables instead of merely declaring them.
 */
class ModelHubScreenWiringTest {

    private val screenSource: String by lazy { source("ui/screens/ModelHubScreen.kt") }

    /** The body of `ModelHubScreen` - the parameter list ends at the first `) {`. */
    private val screenBody: String by lazy {
        val start = screenSource.indexOf("fun ModelHubScreen(")
        if (start < 0) error("ModelHubScreen is not declared in this file")
        val bodyStart = screenSource.indexOf("\n) {", start)
        if (bodyStart < 0) error("could not find the end of ModelHubScreen's parameter list")
        screenSource.substring(bodyStart)
    }

    @Test
    fun `the hub screen places its heading, not just a title`() {
        assertTrue(
            "ModelHubScreen never places a section header, so the screen opens with no title",
            // The heading text is a localisation key now; the screen still places its own heading,
            // which is the property being asserted.
            screenBody.contains("Loc.t(\"models.title\")"),
        )
        assertTrue(
            "the status line the brief asks for is never rendered",
            screenBody.contains("ModelStagePresenter.statusLine("),
        )
    }

    @Test
    fun `the connectivity state reaches the status line`() {
        // The Offline pill is the only thing that distinguishes "no Hub results" from "no
        // connection", and it can only appear if `isOnline` is actually threaded in.
        assertTrue(
            "statusLine is not given isOnline, so Offline is unreachable",
            screenBody.contains("ModelStagePresenter.statusLine(isOnline, hasInstalledModels)"),
        )
    }

    @Test
    fun `the hub screen places its search field, not just declares it`() {
        assertTrue(
            "ModelHubScreen never places a search field, so EXPLORE cannot be filtered",
            screenBody.contains("CharalySearchField("),
        )
        assertTrue(
            "the search field is not given the library's own query callback",
            screenBody.contains("onValueChange = onQueryChange"),
        )
    }

    @Test
    fun `the hub screen places its filter row`() {
        assertTrue(
            "ModelHubScreen never places a filter row, so the filters are unreachable",
            screenBody.contains("CharalyPillRow("),
        )
        assertTrue(
            "the filter row does not receive the selected filter",
            screenBody.contains("setOf(filter.label)"),
        )
    }

    @Test
    fun `the current model is a hero rather than a row in the list`() {
        assertTrue(
            "the hero is declared but never placed, so 'which model is speaking' has no home",
            screenBody.contains("CurrentModelHero("),
        )
    }

    @Test
    fun `the transfer surface is placed and receives the real transfer controls`() {
        assertTrue(
            "the download surface is declared but never placed",
            screenBody.contains("DownloadSurface("),
        )
        for (control in listOf("onPause = onPause", "onResume = onResume", "onCancel = onCancel")) {
            assertTrue("the transfer surface is missing $control", screenBody.contains(control))
        }
    }

    @Test
    fun `download is offered on the cards that can actually be downloaded`() {
        // The central rule of the hub: a control that says DOWNLOAD downloads. Asserted
        // on the card's own action branch rather than on a helper, because a helper could
        // be correct while the card ignored it.
        assertTrue(
            "the card has no download branch, so 'Download' is unreachable",
            screenBody.contains("actionIsDownload"),
        )
        assertTrue(
            "the download action does not reach the real pipeline",
            screenBody.contains("Icons.Filled.Download"),
        )
    }

    @Test
    fun `the hub screen places its browse status, so offline has a home`() {
        assertTrue(
            "BrowseStatus is declared but never placed, so Offline and search failure have " +
                "nowhere to render",
            screenBody.contains("BrowseStatus("),
        )
    }

    @Test
    fun `every action on the hub screen is wired to a callback`() {
        val offenders = mutableListOf<String>()
        Regex("""onClick\s*=\s*\{\s*\}""").findAll(screenSource).forEach {
            offenders += it.value
        }
        assertTrue("controls with an empty click handler: $offenders", offenders.isEmpty())
    }

    @Test
    fun `the hub screen does not read the removed library screen`() {
        // The old screen is gone; a reference here would mean a resurrected import rather
        // than a comment.
        assertFalse(
            "ModelHubScreen still references the deleted library screen",
            screenSource.contains("ModelLibraryScreen"),
        )
    }

    private fun source(relativePath: String): String {
        val candidates = listOf(
            "src/main/kotlin/dev/charaly/app/$relativePath",
            "../app/src/main/kotlin/dev/charaly/app/$relativePath",
        )
        val file = candidates.map(::File).firstOrNull(File::isFile)
            ?: error(
                "$relativePath not found from ${File(".").absolutePath}; candidates were $candidates"
            )
        return file.readText()
    }
}
