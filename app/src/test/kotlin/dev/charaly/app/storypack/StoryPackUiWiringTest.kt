package dev.charaly.app.storypack

import dev.charaly.app.ui.packstore.IssueRow
import dev.charaly.app.ui.packstore.PackDownloadUi
import dev.charaly.app.ui.packstore.installStateOf
import dev.charaly.app.ui.packstore.packCountsLine
import dev.charaly.app.ui.packstore.packVersionLabel
import dev.charaly.app.ui.packstore.PackInstallState
import dev.charaly.app.ui.packstore.scenarioObjectives
import dev.charaly.app.ui.packstore.toImportReportUi
import dev.charaly.app.ui.packstore.toPackDownloadUi
import dev.charaly.app.ui.packstore.toPackStoreItem
import dev.charaly.app.ui.packstore.toStoryCatalogCard
import dev.charaly.app.ui.nav.Route
import dev.charaly.app.ui.nav.decodeRoute
import dev.charaly.app.ui.nav.tabOrNull
import dev.charaly.runtime.domain.StoryPackId
import dev.charaly.runtime.pack.DemoStoryPacks
import dev.charaly.runtime.persistence.InMemoryCharalyStorage
import dev.charaly.runtime.persistence.JsonCharalyRepository
import dev.charaly.runtime.storypack.InstalledPackRecord
import dev.charaly.runtime.storypack.InMemoryStoryPackRegistry
import dev.charaly.runtime.storypack.IssueCode
import dev.charaly.runtime.storypack.PackDownloadProgress
import dev.charaly.runtime.storypack.PackDownloadState
import dev.charaly.runtime.storypack.PackInstallStore
import dev.charaly.runtime.storypack.StoryCatalogItem
import dev.charaly.runtime.storypack.StoryPackDownloadMetadata
import dev.charaly.runtime.storypack.StoryPackDownloadSource
import dev.charaly.runtime.storypack.StoryPackInstaller
import dev.charaly.runtime.storypack.StoryPackRegistry
import dev.charaly.runtime.storypack.ValidationIssue
import dev.charaly.runtime.storypack.ValidationReport
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE STORY PACK STORE'S APP-LAYER CONTRACTS.
 *
 * ## Why these live together
 *
 * Three claims meet at this feature and each one is invisible to the compiler: that the
 * two new routes are *handled* (a route that round-trips and renders nowhere is a trap
 * door in the back stack), that the projections between the validator and the screen leak
 * none of the validator's engineering vocabulary, and that demo seeding really goes
 * through the install pipeline rather than around it. All three are checkable on the JVM
 * without rendering a composable, so all three are checked rather than assumed.
 *
 * The leak test is the sharpest of them: it plants unmistakable markers in every
 * developer-facing field a pack record and a validation issue carry - digests, paths,
 * retained edition names, `detail`, `subject` - and asserts that no string any screen can
 * render contains one. Structural where it can be (`IssueRow` physically has no third
 * field), behavioural where it must be (the mapping output is scanned).
 */
class StoryPackUiWiringTest {

    // ==================================================================
    // 1. The routes
    // ==================================================================

    /**
     * Both pack routes round-trip, hide the navigation bar, and are handled once.
     *
     * Round-tripping is what makes a saved back stack mean anything after process death;
     * "exactly once" is what keeps two code paths from growing around one destination -
     * the defect the router's own contract test exists to prevent, asserted here for the
     * routes that test predates.
     */
    @Test
    fun `the pack store and import routes round-trip and are handled once`() {
        for (route in listOf<Route>(Route.PackStore, Route.PackImport)) {
            assertEquals(route, decodeRoute(route.encode()))
            assertNull(
                "${route.encode()} must own the whole display: a delete confirmation and a " +
                    "nav bar on the same screen fight over the bottom edge",
                route.tabOrNull(),
            )
        }

        val router = appText("ui/CharalyApp.kt")
            .substringAfter("when (route) {")
            .substringBefore("private fun ChatRoute")
        for (name in listOf("Route.PackStore", "Route.PackImport")) {
            val branches = Regex("""(?:is )?${Regex.escape(name)}\s*->""").findAll(router).count()
            assertEquals("$name must have exactly one branch in the router", 1, branches)
            assertTrue("$name's branch must render a screen", router.contains(name.removePrefix("Route.")))
        }
        // And the two file actions ride on the shell's SAF launchers, as promised.
        val app = appText("ui/CharalyApp.kt")
        assertTrue(
            "no OpenDocument launcher owns .charaly-pack import",
            app.contains("ActivityResultContracts.OpenDocument()"),
        )
        assertTrue(
            "no CreateDocument launcher owns .charaly-pack export",
            app.contains("ActivityResultContracts.CreateDocument(StoryPackFormat.MEDIA_TYPE)"),
        )
    }

    // ==================================================================
    // 2. Nothing leaks
    // ==================================================================

    /**
     * A record's digests, paths and internal names reach no screen.
     *
     * The markers are placed in exactly the fields that exist for machines. Every string
     * the store row can render is gathered and searched: a hit means some future field
     * change routed a digest into a player's eyeline.
     */
    @Test
    fun `pack store projections leak no digests paths or ids`() {
        val record = InstalledPackRecord(
            packId = MARKERS.packId,
            packVersion = "1.2.0",
            title = "Kayıp Saatler",
            author = "Deniz Kaya",
            description = "Bir şehir ve bir söz.",
            premise = "Gece yarısı bir mektup bırakılır.",
            tags = listOf("gizem"),
            coverPath = MARKERS.coverPath,
            checksum = MARKERS.checksum,
            archiveSha256 = MARKERS.archive,
            entryPoint = MARKERS.entryPoint,
            source = MARKERS.source,
            retainedVersions = listOf(MARKERS.retained),
            characterCount = 2,
            locationCount = 3,
            scenarioCount = 1,
        )

        val item = record.toPackStoreItem()
        // The derivation itself is asserted exactly, so a future "helpful" addition of a
        // digest line has to change a test rather than slip past a search.
        assertEquals("Sürüm 1.2.0", item.versionLabel)
        assertEquals("2 karakter · 3 mekân · 1 açılış", item.countsLine)
        assertNoMarkers("PackStoreItem", collectVisible(item))
    }

    /**
     * A validation finding reaches the review as its sentence and its severity, and the
     * review has nowhere to put anything else.
     */
    @Test
    fun `the import review carries sentences and severities only`() {
        val issue = ValidationIssue.critical(
            code = IssueCode.DANGLING_REFERENCE,
            message = "Marinette'in başladığı mekân tarif edilmemiş.",
            detail = MARKERS.detail,
            subject = MARKERS.subject,
        )
        val warning = ValidationIssue.warning(
            code = IssueCode.UNKNOWN_FILE,
            message = "Paket fazladan bir dosya içeriyor.",
            detail = MARKERS.detail,
            subject = MARKERS.subject,
        )
        val report = ValidationReport(issues = listOf(issue, warning))
        val ui = report.toImportReportUi()

        assertEquals("Kritik Hata", ui.errors.single().severityLabel)
        assertEquals("Uyarı", ui.warnings.single().severityLabel)
        assertNoMarkers("PackImportReportUi", collectVisible(ui))
        assertFalse(
            "a report with a CRITICAL finding says it can install; that is the whole " +
                "reason the finding is critical",
            ui.canInstall,
        )

        // Structural half: the row type physically cannot carry a third field, so no
        // future mapping can leak one without changing this test. `$stable` and friends
        // are Compose compiler bookkeeping rather than data, so only real instance fields
        // count.
        assertEquals(
            setOf("message", "severityLabel"),
            IssueRow::class.java.declaredFields
                .filterNot { field ->
                    java.lang.reflect.Modifier.isStatic(field.modifiers) ||
                        field.isSynthetic ||
                        field.name.startsWith('$')
                }
                .map { it.name }
                .toSet(),
        )
    }

    /** A warnings-only report installs, and a clean one says so in its own words. */
    @Test
    fun `warnings install and a clean report reads as ready`() {
        val warningsOnly = ValidationReport(
            issues = listOf(ValidationIssue.warning(IssueCode.UNKNOWN_FILE, "Küçük bir uyarı.")),
        )
        assertTrue(warningsOnly.toImportReportUi().canInstall)

        val clean = ValidationReport(issues = emptyList())
        val ui = clean.toImportReportUi()
        assertTrue(ui.canInstall)
        assertEquals("Kontrol tamamlandı, sorun bulunamadı", ui.headline)
    }

    /**
     * The download card carries the failure's own sentences and nothing else.
     *
     * The contract is *verbatim pass-through*: `PackDownloadFailure` writes `message` and
     * `alternative` for a person ("İndirilen dosya bozulmuş görünüyordu; kaydedilmedi."), and
     * the projection forwards them and adds nothing. What must NOT arrive is the progress
     * frame's *machinery* - the catalog entry id, the installed pack id, and the validation
     * report riding along on a rejected install. Those have no field in the UI model, which
     * is asserted at the type level below; the markers planted in them prove no string gets
     * through regardless.
     */
    @Test
    fun `download state carries the failure's sentences and nothing else`() {
        val report = ValidationReport(
            issues = listOf(
                ValidationIssue.critical(
                    code = IssueCode.CHECKSUM_MISMATCH,
                    message = "Dosya bozulmuş görünüyordu.",
                    detail = MARKERS.detail,
                    subject = MARKERS.subject,
                ),
            ),
        )
        val failure = dev.charaly.runtime.storypack.PackDownloadFailure.ChecksumMismatch()
        val frame = PackDownloadProgress(
            itemId = MARKERS.itemId,
            state = PackDownloadState.FAILED,
            bytesDownloaded = 10,
            totalBytes = 100,
            message = "Dosya bozulmuş.",
            failure = failure,
            installedPackId = MARKERS.packId,
            report = report,
        )
        val ui = frame.toPackDownloadUi()

        // Verbatim: the failure's own two sentences, unedited and unembellished.
        assertEquals(failure.message, ui.failureMessage)
        assertEquals(failure.alternative, ui.failureAlternative)

        // And nothing of the machinery: not as a marker, and not as a field.
        assertNoMarkers("PackDownloadUi", collectVisible(ui))
        val fields = PackDownloadUi::class.java.declaredFields
            .filterNot { field ->
                java.lang.reflect.Modifier.isStatic(field.modifiers) ||
                    field.isSynthetic ||
                    field.name.startsWith('$')
            }
            .map { it.name }
            .toSet()
        for (forbidden in listOf("itemId", "installedPackId", "report", "failure", "bytesDownloaded", "totalBytes")) {
            assertFalse(
                "PackDownloadUi exposes '$forbidden': the pipeline's machinery has reached " +
                    "a user-facing projection",
                forbidden in fields,
            )
        }
    }

    // ==================================================================
    // 3. The progress bar cannot lie
    // ==================================================================

    /**
     * `fraction` is null unless bytes are genuinely moving toward a known total.
     *
     * Every state is exercised with and without a total: a bar that renders a percentage
     * during "Denetleniyor" is reporting progress on something no byte is moving in, and a
     * bar at 0% for an unknown total is a claim the pipeline never made.
     */
    @Test
    fun `a download reports a fraction only while downloading with a known total`() {
        for (state in PackDownloadState.entries) {
            val withTotal = PackDownloadProgress(
                itemId = "x",
                state = state,
                bytesDownloaded = 25,
                totalBytes = 100,
            ).toPackDownloadUi()
            val withoutTotal = PackDownloadProgress(
                itemId = "x",
                state = state,
                bytesDownloaded = 25,
                totalBytes = 0,
            ).toPackDownloadUi()

            if (state == PackDownloadState.DOWNLOADING) {
                assertEquals("$state with a total must be measurable", 0.25f, withTotal.fraction!!, 0.0001f)
            } else {
                assertNull("$state must not report a fraction", withTotal.fraction)
            }
            assertNull("$state without a total must not report a fraction", withoutTotal.fraction)
        }
    }

    /** The affordances follow the state: pause where pausing is real, retry where it is. */
    @Test
    fun `download affordances match the pipeline state`() {
        val downloading = PackDownloadProgress("x", PackDownloadState.DOWNLOADING, totalBytes = 8).toPackDownloadUi()
        assertTrue(downloading.canPause)
        assertTrue(downloading.canCancel)
        assertFalse(downloading.canRetry)

        val paused = PackDownloadProgress("x", PackDownloadState.PAUSED, totalBytes = 8).toPackDownloadUi()
        assertTrue(paused.isPaused)
        assertTrue("a paused transfer must be resumable", paused.canRetry)

        val failed = PackDownloadProgress("x", PackDownloadState.FAILED, totalBytes = 8).toPackDownloadUi()
        assertTrue(failed.isFailed)
        assertTrue(failed.canRetry)

        val ready = PackDownloadProgress("x", PackDownloadState.READY, totalBytes = 8).toPackDownloadUi()
        assertTrue(ready.isReady)
        assertFalse(ready.canCancel)
    }

    // ==================================================================
    // 4. Catalog and installed state
    // ==================================================================

    /** A newer catalog edition is an update; the same or an older one is not a downgrade. */
    @Test
    fun `install state judges upgrades by the version triple`() {
        val installed = listOf(
            InstalledPackRecord(packId = "pack-a", packVersion = "1.0.0", title = "A"),
        )
        val listing = StoryCatalogItem(
            id = "builtin.pack-a",
            packId = "pack-a",
            packVersion = "2.0.0",
            title = "A",
            description = "İki nokta sıfır sürümü.",
            author = "Deniz Kaya",
            sizeBytes = 0,
            download = StoryPackDownloadMetadata(source = StoryPackDownloadSource.BUILTIN),
        )
        assertEquals(PackInstallState.UPDATE_AVAILABLE, installStateOf(listing, installed))
        assertEquals(
            "a republished older edition must not be offered as an update",
            PackInstallState.INSTALLED,
            installStateOf(listing.copy(packVersion = "0.9.0"), installed),
        )
        assertEquals(PackInstallState.INSTALLED, installStateOf(listing.copy(packVersion = "1.0.0"), installed))
        assertEquals(PackInstallState.NOT_INSTALLED, installStateOf(listing.copy(packId = "pack-b"), installed))

        // And the catalog card's own mapping keeps to listing vocabulary: a listing has no
        // inventory to boast about and borrows none from the copy installed here.
        val card = listing.toStoryCatalogCard(PackInstallState.NOT_INSTALLED, download = null)
        assertEquals("builtin.pack-a", card.itemId)
        assertEquals("A", card.title)
        assertEquals("Deniz Kaya", card.author)
        assertEquals("İki nokta sıfır sürümü.", card.premise)
        assertEquals("boyut belirtilmemiş", card.sizeLabel)
    }

    /** The Turkish shells of the derived labels. */
    @Test
    fun `derived pack labels are Turkish and shaped as promised`() {
        assertEquals("Sürüm 1.2.0", packVersionLabel("1.2.0"))
        assertEquals("", packVersionLabel(""))
        assertEquals("3 karakter · 2 açılış", packCountsLine(characters = 3, locations = 0, scenarios = 2))
        assertEquals("", packCountsLine(0, 0, 0))
    }

    /** The review step shows the opening's objectives and never a secret one. */
    @Test
    fun `scenario objectives are named ones and auto-activating ones only`() {
        val base = DemoStoryPacks.all.first()
        val scenario = base.scenarios.first()
        val named = dev.charaly.runtime.domain.PackObjective(id = "obj-named", title = "İsimli görev")
        val auto = dev.charaly.runtime.domain.PackObjective(id = "obj-auto", title = "Otomatik görev")
        val manual = dev.charaly.runtime.domain.PackObjective(
            id = "obj-manual",
            title = "Elle konulmuş görev",
            autoActivate = false,
        )
        val secret = dev.charaly.runtime.domain.PackObjective(
            id = "obj-secret",
            title = "Gizli görev",
            autoActivate = false,
            secret = true,
        )
        val pack = base.copy(
            objectives = listOf(named, auto, manual, secret),
            scenarios = listOf(scenario.copy(objectiveIds = listOf("obj-named"))) + base.scenarios.drop(1),
        )

        val rows = scenarioObjectives(pack, scenario.id).map { it.title }

        assertTrue("the opening's own objective must be offered", "İsimli görev" in rows)
        assertTrue("an auto-activating objective must be offered", "Otomatik görev" in rows)
        assertFalse(
            "an objective the opening never named must not appear",
            "Elle konulmuş görev" in rows,
        )
        assertFalse(
            "a secret objective's existence is a spoiler; it must not reach the review",
            "Gizli görev" in rows,
        )
    }

    // ==================================================================
    // 5. Seeding goes through the pipeline
    // ==================================================================

    /**
     * Every demo pack exports to an archive its own inspector approves.
     *
     * The first half of the round trip: `exportPack` must produce real `.charaly-pack`
     * bytes that decode, validate and health-check as installable. A demo that could not
     * survive its own format would make the seeded first run silently empty, and this is
     * the cheapest place to catch it.
     */
    @Test
    fun `every demo pack exports to an archive that inspects clean`() = runTest {
        val root = java.nio.file.Files.createTempDirectory("charaly-pack-store-").toFile()
        try {
            val installer = installerFor(root)
            for (demo in DemoStoryPacks.all) {
                val bytes = installer.exportPack(demo)
                assertTrue("${demo.title} exported nothing", bytes.isNotEmpty())

                val candidate = installer.inspect(bytes)
                assertFalse(
                    "${demo.title} did not survive inspection: ${candidate.report.debugLines()}",
                    candidate.isRejected,
                )
                assertTrue(
                    "${demo.title} is not installable: ${candidate.report.debugLines()}",
                    candidate.report.isInstallable,
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * An exported demo installs *and registers* through the pipeline, as a builtin.
     *
     * The second half - and the acceptance criterion for the seeding change: the demos are
     * seeded as ordinary archives through `import`, so this must land exactly as a
     * downloaded pack does.
     *
     * ## The skip, and what it stands for
     *
     * `import()` is `charaly-runtime`'s, and it currently refuses some first installs
     * behind a warnings-only report (suspect: it re-reads the staged install via
     * `store.loadEdition(packId, version)`, which points at the edition directory that
     * `commit` has not created yet - so a genuine first install reads as a failed re-read).
     * Until that is fixed upstream this test *skips with the pointer* rather than red-
     * flagging the app layer for a defect it is not allowed to repair - the same convention
     * the native tests use when a device property is absent. The moment `import` honours
     * its contract the assumption passes and every assertion below runs.
     */
    @Test
    fun `an exported demo installs and registers through the pipeline`() = runBlocking {
        val root = java.nio.file.Files.createTempDirectory("charaly-pack-store-").toFile()
        try {
            val repository = JsonCharalyRepository(InMemoryCharalyStorage())
            val registry = InMemoryStoryPackRegistry()
            val installer = installerFor(root, repository, registry)

            for (demo in DemoStoryPacks.all) {
                val bytes = installer.exportPack(demo)
                val result = installer.import(bytes, source = InstalledPackRecord.SOURCE_BUILTIN)

                // A real assertion, not an assumption: a demo that cannot install through
                // the pipeline is a broken pipeline, and skipping would say the opposite.
                assertTrue(
                    "import refused ${demo.title} on a first install: " +
                        (result as? StoryPackInstaller.ImportResult.Rejected)?.report?.debugLines().orEmpty(),
                    result is StoryPackInstaller.ImportResult.Installed,
                )
                assertEquals(
                    "a seeded demo must record where it came from",
                    InstalledPackRecord.SOURCE_BUILTIN,
                    (result as StoryPackInstaller.ImportResult.Installed).record.source,
                )
            }

            val installed = registry.list().map { it.packId }.toSet()
            assertEquals(
                "every demo pack must be registered after seeding",
                DemoStoryPacks.all.map { it.id.value }.toSet(),
                installed,
            )
            // And the content is where the runtime reads it from.
            assertNotNull(repository.getPack(StoryPackId(DemoStoryPacks.all.first().id.value)))
        } finally {
            root.deleteRecursively()
        }
    }

    /** The installer the app's composition root builds, minus its Android halves. */
    private fun installerFor(
        root: File,
        repository: JsonCharalyRepository = JsonCharalyRepository(InMemoryCharalyStorage()),
        registry: StoryPackRegistry = InMemoryStoryPackRegistry(),
    ): StoryPackInstaller = StoryPackInstaller(
        packs = repository,
        registry = registry,
        store = PackInstallStore(root),
        engineVersion = "0.8.0",
        storyCountFor = { 0 },
    )

    /**
     * The view model seeds through `exportPack` -> `import`, not around the pipeline.
     *
     * The behavioural proof is above; this pins the *wiring* - `refresh()` must route the
     * missing demos through the installer with the builtin source, and must not smuggle
     * the old repository write back in beside it. Read as source because the view model's
     * own constructor needs an Android `Context`, which a JVM test does not have.
     */
    @Test
    fun `the view model seeds demos through export and import`() {
        val source = appText("ui/CharalyViewModel.kt")
        val seeding = source
            .substringAfter("val missing = DemoStoryPacks.all")
            .substringBefore("val packs = runCatching")

        assertTrue("refresh() no longer exports a missing demo", seeding.contains("packInstaller.exportPack("))
        assertTrue("refresh() no longer imports the exported demo", seeding.contains("packInstaller.import("))
        assertTrue(
            "a seeded demo must be recorded as builtin",
            seeding.contains("InstalledPackRecord.SOURCE_BUILTIN"),
        )
        assertFalse(
            "the old direct repository write has returned beside the pipeline; demos would " +
                "then be 'installed' without being registered",
            seeding.contains("runtime.createPack("),
        )
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    /** Every planted marker, and where it was planted. */
    private val MARKERS = Markers(
        packId = "PACK-ID-MARKER",
        checksum = "CHECKSUM-MARKER",
        archive = "ARCHIVE-MARKER",
        coverPath = "assets/private/COVER-PATH-MARKER.png",
        entryPoint = "ENTRY-POINT-MARKER",
        source = "SOURCE-MARKER",
        retained = "RETAINED-EDITION-MARKER",
        itemId = "ITEM-ID-MARKER",
        detail = "DETAIL-MARKER",
        subject = "SUBJECT-MARKER",
    )

    private data class Markers(
        val packId: String,
        val checksum: String,
        val archive: String,
        val coverPath: String,
        val entryPoint: String,
        val source: String,
        val retained: String,
        val itemId: String,
        val detail: String,
        val subject: String,
    ) {
        fun all(): List<String> =
            listOf(packId, checksum, archive, coverPath, entryPoint, source, retained, itemId, detail, subject)
    }

    /**
     * Property readers that would defeat the walk.
     *
     * The wiring keys (never rendered), the raw bytes (never rendered), and the reflective
     * identity trio - `toString()` in particular reproduces the whole object, wiring keys
     * and all, and would make every leak test vacuous.
     */
    private val EXCLUDED_GETTERS = setOf(
        "getPackId",
        "getItemId",
        "getBytes",
        "toString",
        "hashCode",
        "getClass",
    )

    /**
     * Every string a screen could render from this projection.
     *
     * Walks the value's own properties recursively and keeps strings and string lists. The
     * two *wiring* keys are excluded from the walk by name: `packId` and `itemId` identify
     * which row an action targets and are never rendered - which is exactly why a marker
     * planted in them is the sharpest detector for one escaping through some *other*
     * field. `toString()` is excluded too: it would reproduce the whole object, wiring keys
     * and all, and make the walk vacuous.
     */
    private fun collectVisible(value: Any): List<String> {
        val out = mutableListOf<String>()
        fun walk(current: Any?) {
            when (current) {
                null -> Unit
                is String -> out += current
                is List<*> -> current.forEach(::walk)
                else -> {
                    // Recurse only through the projection types themselves; primitives,
                    // enums and boxed numbers stop the walk rather than inviting a getter
                    // hunt through the JDK.
                    if (current::class.java.`package`?.name != "dev.charaly.app.ui.packstore") return
                    current::class.java.declaredMethods
                        .filter { method ->
                            method.parameterCount == 0 &&
                                java.lang.reflect.Modifier.isPublic(method.modifiers) &&
                                !method.name.startsWith("component") &&
                                method.name !in EXCLUDED_GETTERS
                        }
                        .forEach { method -> runCatching { walk(method.invoke(current)) } }
                }
            }
        }
        walk(value)
        return out
    }

    private fun assertNoMarkers(subject: String, visible: List<String>) {
        for (text in visible) {
            for (marker in MARKERS.all()) {
                assertFalse(
                    "$subject leaks an internal marker: \"$text\" contains \"$marker\"",
                    text.contains(marker),
                )
            }
        }
    }

    private fun appText(relativePath: String): String = appSource(relativePath).readText()

    /** The app's sources, located the way the other contract tests locate them. */
    private fun appSource(relativePath: String): File {
        val candidates = listOf(
            File("src/main/kotlin/dev/charaly/app/$relativePath"),
            File("../app/src/main/kotlin/dev/charaly/app/$relativePath"),
        )
        return candidates.firstOrNull(File::isFile)
            ?: error("app sources not found from ${File(".").absolutePath}: $relativePath")
    }
}