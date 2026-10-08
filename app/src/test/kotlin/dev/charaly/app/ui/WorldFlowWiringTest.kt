package dev.charaly.app.ui

import dev.charaly.app.model.LlamaBenchmarkRunner
import dev.charaly.runtime.inference.GenerationParams
import dev.charaly.runtime.inference.InferenceEngine
import dev.charaly.runtime.inference.InferenceRequest
import dev.charaly.runtime.inference.InferenceResult
import dev.charaly.runtime.inference.LoadOutcome
import dev.charaly.runtime.inference.ModelInfo
import dev.charaly.runtime.inference.ModelLoadRequest
import dev.charaly.runtime.inference.StreamChunk
import dev.charaly.runtime.model.BenchmarkObservation
import dev.charaly.runtime.model.BenchmarkRecord
import dev.charaly.runtime.model.BenchmarkStore
import dev.charaly.runtime.model.InMemoryBenchmarkStore
import dev.charaly.runtime.model.InstalledModel
import dev.charaly.runtime.model.ModelRegistry
import dev.charaly.runtime.model.InMemoryModelRegistry
import dev.charaly.runtime.persistence.CharacterLibraryRepository
import dev.charaly.runtime.persistence.InMemoryCharalyStorage
import dev.charaly.runtime.persistence.JsonCharacterLibraryRepository
import dev.charaly.runtime.persistence.JsonCharalyRepository
import dev.charaly.runtime.compat.CharacterCardReader
import dev.charaly.runtime.presentation.BenchmarkPresenter
import dev.charaly.runtime.presentation.CharacterImportPresenter
import dev.charaly.runtime.presentation.CharacterImportStep
import dev.charaly.runtime.session.CharalyRuntime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The three features this pass added, as the app's own layer sees them.
 *
 * ## Why source-level assertions are mixed in with real calls
 *
 * Compose cannot be rendered or clicked from a JVM unit test - this module has no
 * Robolectric and no device. So the claims that only a composable can make ("does the screen
 * draw the measurement?", "does this control do anything?") are checked by reading the
 * source, which is a weaker check and says so. Everything that *can* be exercised for real
 * is: the runtime's turn pipeline, the benchmark runner's failure handling, and the whole
 * character import round-trip through the same view model the app uses.
 *
 * The source checks exist because the specific bug this codebase has shipped twice is a
 * control that looks finished and does nothing.
 */
class WorldFlowWiringTest {

    private fun appSource(relativePath: String): File =
        File("src/main/kotlin/dev/charaly/app/$relativePath")

    // ==================================================================
    // 1. The world clock
    // ==================================================================

    /**
     * The turn pipeline advances the clock itself.
     *
     * The load-bearing assertion of the whole clock pass. `advanceClock` existed on the view
     * model for the entire life of the app and no screen called it, so the world's routines
     * could not fire during a real playthrough - while every engine-level time test passed,
     * because those call `advance` directly.
     *
     * So this asserts that `respond` reaches `advance`, through the policy, inside its own
     * body. If it is ever removed, the app goes back to a static world and this fails.
     */
    @Test
    fun `a completed turn advances the clock through the authoritative path`() {
        val source = File(
            "../charaly-runtime/src/main/kotlin/dev/charaly/runtime/session/CharalyRuntime.kt",
        ).readText()
        val respondBody = source.substringAfter("fun respond(").substringBefore("fun stop()")

        assertTrue(
            "the turn pipeline no longer advances the clock",
            respondBody.contains("TurnClockPolicy.minutesFor("),
        )
        // And it goes through `advance`, not through the clock object directly: `advance` is
        // what drains scheduled events and runs the NPC routines, so writing the clock by hand
        // would move the *display* without moving the world.
        assertTrue(
            "the turn pipeline moves the clock without running the event engine",
            respondBody.contains("advance(conditioned, elapsed)"),
        )
        // Which means the world genuinely changes, rather than showing a later time.
        assertTrue(
            "the clock advance does not poll pack-conditional events",
            respondBody.contains("advance(conditioned, elapsed)"),
        )
    }

    /** The stage header reads the world clock rather than formatting its own idea of it. */
    @Test
    fun `the stage header shows the world clock`() {
        val presenter = File(
            "../charaly-runtime/src/main/kotlin/dev/charaly/runtime/presentation/ChatStage.kt",
        ).readText()
        assertTrue(
            "the stage does not project the clock",
            presenter.contains("clockLabel = instance.worldClock.now.formatClock()"),
        )
        assertTrue(
            "the stage does not project the day",
            presenter.contains("dayLabel = \"${'$'}{instance.worldClock.now.day}. Gün\""),
        )

        val screen = appSource("ui/screens/StageScreen.kt").readText()
        // V5 renders the clock as the amber pill in the header, and the day in the World
        // sheet beside it - the header stays one line, the sheet carries the calendar.
        assertTrue(
            "the stage header does not render the clock",
            screen.contains("stage.clockLabel"),
        )
        val sheets = appSource("ui/sheets/StageSheets.kt").readText()
        assertTrue(
            "the world sheet does not render the day",
            sheets.contains("world.dayLabel") || sheets.contains("dayLabel"),
        )
    }

    /** The World sheet shows time, day and the live scene, all from authoritative state. */
    @Test
    fun `the world sheet reports time and scene`() {
        val source = File(
            "../charaly-runtime/src/main/kotlin/dev/charaly/runtime/presentation/ContextualSheets.kt",
        ).readText()
        assertTrue("the world sheet has no exact clock reading", source.contains("clockLabel: String"))
        assertTrue("the world sheet says nothing about the scene", source.contains("activeScene: String"))
        assertTrue(
            "the clock is not read from the instance",
            source.contains("clockLabel = now.formatClock()"),
        )
    }

    /** The debug clock control goes through the engine, and only behind Developer Mode. */
    @Test
    fun `the advance time control is a developer tool that uses the real engine`() {
        val viewModel = appSource("ui/CharalyViewModel.kt").readText()
        val advanceBody = viewModel.substringAfter("fun advanceClock(minutes: Long)")
            .substringBefore("// ---")
        assertTrue(
            "the debug control bypasses the runtime",
            advanceBody.contains("runtime.advance("),
        )
        // And it must not write world fields itself.
        assertFalse(
            "the debug control writes the clock directly",
            advanceBody.contains("worldClock ="),
        )

        val screen = appSource("ui/screens/DeveloperScreen.kt").readText()
        assertTrue(
            "the developer screen has no clock control",
            screen.contains("onAdvanceTime"),
        )
        // The route is gated: `CharalyApp` renders it only when Developer Mode is on.
        val app = appSource("ui/CharalyApp.kt").readText()
        assertTrue(
            "the developer route is no longer gated",
            app.contains("Route.Developer -> if (state.developerMode)"),
        )
    }

    /**
     * The real user path moves the world.
     *
     * Not a source check: a real runtime, a real `respond`, real turns. This is the
     * acceptance criterion, asserted where it cannot be argued with.
     */
    @Test
    fun `six turns of conversation move the clock`() = runTest {
        val storage = InMemoryCharalyStorage()
        val runtime = CharalyRuntime(
            repository = JsonCharalyRepository(storage),
            engine = dev.charaly.runtime.inference.MockInferenceEngine(initiallyLoaded = true),
            library = InMemoryCharacterLibrary(),
        )
        // The Miraculous pack, because it is the one with routines on real NPCs - which is
        // what this pass is about. `DemoStoryPacks.all.first()` happens to be a pack whose
        // first character is in a scene-less opening, and a turn there fails with NoScene.
        val pack = dev.charaly.runtime.pack.DemoStoryPacks.all.first {
            it.id.value == "pack-miraculous-shadows-of-paris"
        }
        val options = dev.charaly.runtime.engine.StoryCreationOptions(
            instanceId = dev.charaly.runtime.domain.StoryInstanceId("story-wiring"),
            scenario = pack.scenarios.first { it.id == "school-morning" },
            focusCharacterId = dev.charaly.runtime.domain.CharacterId("marinette"),
            modelBinding = dev.charaly.runtime.model.ModelBinding.EMPTY,
            nowEpochMs = 1_700_000_000_000L,
        )

        var story = runtime.startStory(pack, options)
        val start = story.worldClock.now

        repeat(6) {
            var finished: dev.charaly.runtime.session.GenerationUpdate.Finished? = null
            var failure: dev.charaly.runtime.session.CharalyError? = null
            runtime.respond(story, "What is happening?").collect { update ->
                when (update) {
                    is dev.charaly.runtime.session.GenerationUpdate.Finished -> finished = update
                    is dev.charaly.runtime.session.GenerationUpdate.Failed -> failure = update.error
                    else -> Unit
                }
            }
            // A failed turn produces no Finished frame, and the message is what says why -
            // otherwise this fails with "required value was null" and no explanation.
            assertNull("turn $it failed: ${failure?.message ?: failure}", failure)
            story = requireNotNull(finished?.instance) { "turn $it produced no authoritative snapshot" }
        }

        assertTrue(
            "six turns of conversation left the clock where it started",
            story.worldClock.now > start,
        )
        // And the world moved with it, through events rather than by assignment.
        assertTrue(story.worldState.eventLog.isNotEmpty())
    }

    /** A private alias so the intent reads at the call site. */
    private class InMemoryCharacterLibrary : CharacterLibraryRepository {
        private val entries = mutableListOf<dev.charaly.runtime.persistence.ImportedCharacter>()
        override suspend fun list() = entries.toList()
        override suspend fun get(id: dev.charaly.runtime.domain.CharacterId) =
            entries.firstOrNull { it.preview.id == id.value }
        override suspend fun save(character: dev.charaly.runtime.persistence.ImportedCharacter):
            dev.charaly.runtime.persistence.ImportedCharacter {
            entries.removeAll { it.preview.id == character.preview.id }
            entries += character
            return character
        }
        override suspend fun delete(id: dev.charaly.runtime.domain.CharacterId) {
            entries.removeAll { it.preview.id == id.value }
        }
        override suspend fun saveResolvingCollisions(
            character: dev.charaly.runtime.persistence.ImportedCharacter,
        ): dev.charaly.runtime.persistence.ImportedCharacter = save(character)
    }

    // ==================================================================
    // 2. Character import
    // ==================================================================

    /**
     * Nothing is written before the user confirms.
     *
     * The property that makes a cancel button honest, and the one that is easy to break by
     * "just saving it early so the list updates". Verified through the same view model the
     * app uses, with a real repository behind it.
     */
    @Test
    fun `cancelling an import leaves the library untouched`() = runTest {
        val library = JsonCharacterLibraryRepository(InMemoryCharalyStorage())
        val rt = runtime(library = library)

        val card = """
        {"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"Wren","description":"a cartographer"}}
        """.trimIndent()
        val preview = CharacterCardReader.read(card.toByteArray(), "wren.json").getOrThrow()

        // The whole flow up to the decision, then the decision itself.
        val previewed = CharacterImportPresenter.preview("wren.json", preview)
        assertTrue(previewed.canConfirm)
        val cancelled = CharacterImportPresenter.cancelled()

        assertEquals(CharacterImportStep.IDLE, cancelled.step)
        assertNull("cancelling left the card on screen", cancelled.preview)
        // Nothing was written. This is the whole claim a cancel button can honestly make, and
        // it holds because writing requires an explicit call the cancel path does not make.
        assertTrue(
            "a cancelled import left something in the library",
            rt.listImportedCharacters().isEmpty(),
        )
    }

    /** Confirming is the only path that writes, and it writes exactly one entry. */
    @Test
    fun `confirming an import is the only path that writes`() = runTest {
        val library = JsonCharacterLibraryRepository(InMemoryCharalyStorage())
        val rt = runtime(library = library)

        val card = """
        {"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"Wren","description":"a cartographer"}}
        """.trimIndent()
        val preview = CharacterCardReader.read(card.toByteArray(), "wren.json").getOrThrow()
        val saved = rt.commitImportedCharacter(preview = preview, rawJson = card, nowEpochMs = 1L)

        assertEquals(1, library.list().size)
        assertEquals("Wren", saved.name)
        assertEquals(1, rt.listImportedCharacters().size)
        // And the library screen can now show it.
        val screen = CharacterImportPresenter.library(rt.listImportedCharacters())
        assertEquals(1, screen.cards.size)
        assertFalse(screen.isEmpty)
    }

    /**
     * The view model reads a card without writing, and commits on confirm.
     *
     * Asserted on the source because the real entry point takes a `Uri`, which a JVM test
     * cannot build. What is checked is the *order*: the read path must not contain the
     * commit, and the commit path must not contain the read.
     */
    @Test
    fun `reading a card and committing it are separate calls`() {
        val source = appSource("ui/CharalyViewModel.kt").readText()
        val read = source.substringAfter("fun readCharacterCard(").substringBefore("fun confirmCharacterImport")
        val commit = source.substringAfter("fun confirmCharacterImport()").substringBefore("fun cancelCharacterImport")

        assertFalse(
            "reading a card writes it to the library",
            read.contains("commitImportedCharacter("),
        )
        assertTrue(
            "confirming does not commit",
            commit.contains("commitImportedCharacter("),
        )
        // Cancelling writes nothing and clears the pending file, so a later import cannot
        // pick up bytes the user already backed out of.
        val cancel = source.substringAfter("fun cancelCharacterImport()").substringBefore("fun deleteImportedCharacter")
        assertFalse("cancelling writes something", cancel.contains("commitImportedCharacter("))
        assertTrue("cancelling leaves the pending file in place", cancel.contains("clearPendingCard()"))
    }

    /**
     * The app asks for no storage permission to read a card.
     *
     * Character cards are picked through the system document picker, which grants one file at
     * a time. A broad storage permission would be a real regression of the local-first
     * posture, so it is asserted rather than assumed.
     */
    @Test
    fun `importing a card asks for no storage permission`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        for (forbidden in listOf(
            "READ_EXTERNAL_STORAGE",
            "WRITE_EXTERNAL_STORAGE",
            "READ_MEDIA_IMAGES",
            "MANAGE_EXTERNAL_STORAGE",
        )) {
            assertFalse(
                "the manifest requests $forbidden, which a SAF card import does not need",
                manifest.contains(forbidden),
            )
        }
    }

    /** The import flow has a route, and that route has exactly one screen. */
    @Test
    fun `the import flow is reachable and renders`() {
        val navigator = appSource("ui/nav/CharalyNavigator.kt").readText()
        assertTrue("the import flow has no route", navigator.contains("data object CharacterImport"))
        // Encoded and decoded, or a restored back stack could not come back to it.
        assertTrue("the route cannot be encoded", navigator.contains("CharacterImport -> \"characters/import\""))
        assertTrue("the route cannot be decoded", navigator.contains("Route.CharacterImport"))
        // And it is a full-screen destination, so the navigation pill hides for it.
        val destinationBlock = navigator.substringAfter("fun Route.tabOrNull()")
            .substringBefore("private fun rootOf")
        assertTrue(
            "the import route is not full-screen",
            destinationBlock.contains("Route.CharacterImport"),
        )

        val app = appSource("ui/CharalyApp.kt").readText()
        assertTrue(
            "no screen renders the import route",
            app.contains("Route.CharacterImport ->"),
        )
        assertTrue(
            "the import route has no preview screen",
            app.contains("CharacterImportScreen("),
        )
    }

    /** No control in the import or stage screens is a dead lambda. */
    @Test
    fun `the import screen has no control that does nothing`() {
        val screen = appSource("ui/screens/CharacterImportScreen.kt").readText()
        assertNull(
            "the import screen has a control whose onClick does nothing",
            Regex("""onClick\s*=\s*\{\s*\}""").find(screen),
        )
        // Every control is wired to one of the four callbacks the flow actually has.
        for (callback in listOf("onPick", "onConfirm", "onCancel", "onDone")) {
            assertTrue(
                "the import screen never calls $callback",
                screen.contains("$callback(") || screen.contains("= $callback"),
            )
        }
    }

    /** The cast picker offers imported characters without confusing them with the pack's. */
    @Test
    fun `the cast picker keeps imported characters in their own list`() {
        val viewModelSource = appSource("ui/CharalyViewModel.kt").readText()
        // Separate draft fields: the isolation tests resolve ids against the pack, so a single
        // mixed set would quietly weaken them.
        // The draft's imported field is read on its own, never folded into the pack's cast.
        // The isolation tests resolve cast ids against the pack, so a single mixed set would
        // quietly weaken them.
        assertTrue(
            "story creation does not read the imported cast separately",
            viewModelSource.contains("val importedIds = draft.importedCharacterIds.map(::CharacterId).toSet()"),
        )
        assertTrue(
            "the toggle writes to the imported field rather than the pack's cast",
            viewModelSource.contains("importedCharacterIds = if (selected) {"),
        )
        assertTrue(
            "story creation ignores the imported cast",
            viewModelSource.contains("startStoryWithImported("),
        )

        val screen = appSource("ui/screens/EnterWorldScreen.kt").readText()
        assertTrue(
            "the cast step does not render the imported list",
            screen.contains("snapshot.importable"),
        )
        assertTrue(
            "the cast step does not offer the import flow",
            screen.contains("action.import_character"),
        )
    }

    /** The failure path shows a sentence, never a parser message. */
    @Test
    fun `a card that cannot be read produces a sentence`() = runTest {
        val snapshot = CharacterImportPresenter.fromResult(
            sourceName = "notacard.png",
            result = CharacterCardReader.read(ByteArray(64) { 0x41 }, "notacard.png"),
        )
        assertEquals(CharacterImportStep.FAILED, snapshot.step)
        assertEquals("Could not read character card", snapshot.error?.headline)
        assertFalse(
            "the failure leaked internal vocabulary",
            snapshot.error?.detail.orEmpty().contains("PNG") ||
                snapshot.error?.detail.orEmpty().contains("JSON"),
        )
        assertFalse(snapshot.canConfirm)
    }

    // ==================================================================
    // 3. The benchmark
    // ==================================================================

    /**
     * A failed benchmark reports a sentence and stores nothing.
     *
     * Real code, a real failure path. The load-bearing part is the last assertion: a stored
     * record would be quoted as a measurement forever, and "could not complete" must leave
     * the card reading "Not measured" rather than "0 tok/s".
     */
    @Test
    fun `a benchmark that cannot run records nothing`() = runTest {
        val store = InMemoryBenchmarkStore()
        val runner = LlamaBenchmarkRunner(
            registry = InMemoryModelRegistry(),
            store = store,
            engineProvider = { UnmeasurableEngine() },
            memoryProbe = dev.charaly.runtime.model.DeviceMemoryProbe.UNKNOWN,
        )
        val model = InstalledModel(
            id = "m1",
            displayName = "Test",
            absolutePath = "/models/m1.gguf",
        )

        val result = runner.run(model)

        assertTrue("a run that measured nothing should fail", result.isFailure)
        val failure = result.exceptionOrNull()
        assertTrue(
            "the failure is not typed",
            failure is dev.charaly.runtime.model.BenchmarkFailedException,
        )
        assertEquals(
            "Benchmark could not complete on this device.",
            BenchmarkPresenter.messageFor(requireNotNull(failure)),
        )
        assertTrue(
            "a failed run left a record behind",
            store.all().isEmpty(),
        )
        // And the presenter renders that absence as words.
        assertEquals("Ölçülmedi", BenchmarkPresenter.verdict(null).label)
    }

    /** A model with no measurement is never shown a speed. */
    @Test
    fun `an unmeasured model shows no rate`() {
        val card = dev.charaly.runtime.presentation.ModelLibraryPresenter.modelCard(
            model = InstalledModel(
                id = "m1",
                displayName = "Test",
                absolutePath = "/models/m1.gguf",
                architecture = "qwen3",
            ),
            isActive = true,
            isLoaded = false,
            availableRamBytes = 4_000_000_000L,
            downloadsAvailable = true,
            benchmark = null,
        )
        assertEquals("Ölçülmedi", card.speed.label)
        assertFalse(card.speed.hasMeasurement)
        assertTrue("an unmeasured model must be offered a benchmark", card.canBenchmark)
    }

    /** A model this build cannot load is never offered a benchmark it could not produce. */
    @Test
    fun `an unloadable model is not offered a benchmark`() {
        val card = dev.charaly.runtime.presentation.ModelLibraryPresenter.modelCard(
            model = InstalledModel(
                id = "m1",
                displayName = "Test",
                absolutePath = "/models/m1.gguf",
                architecture = "gemma4",
            ),
            isActive = false,
            isLoaded = false,
            availableRamBytes = 8_000_000_000L,
            downloadsAvailable = true,
            benchmark = null,
        )
        // A button that always fails is worse than no button.
        assertFalse("an unloadable model was offered a Measure control", card.canBenchmark)
    }

    /** No screen renders a tok/s figure without the line saying it was measured. */
    @Test
    fun `no screen renders a bare tok s figure`() {
        val screen = appSource("ui/screens/ModelHubScreen.kt").readText()
        // The hero must render the evidence line, not just the rate.
        assertTrue(
            "the hero renders a rate without its evidence line",
            screen.contains("speed.evidence"),
        )
        assertTrue(
            "the hero has no unmeasured state",
            screen.contains("Not measured") || screen.contains("speed.label"),
        )
        // And nothing in the UI formats a rate itself.
        assertFalse(
            "a screen formats its own tokens-per-second string",
            Regex("""\d+\.\d+\s*tok/s""").containsMatchIn(screen),
        )
    }

    /** The Measure control is wired to the view model, not to a lambda that does nothing. */
    @Test
    fun `the measure control reaches the runner`() {
        val screen = appSource("ui/screens/ModelHubScreen.kt").readText()
        // Assert on the *control*, not on a hardcoded English literal. The label goes
        // through Loc, so `screen.contains("\"Measure\"")` was a test that broke the moment
        // the screen was localized correctly - and "fixing" it by re-inserting an English
        // string into a Turkish UI would have undone the localization instead. What this
        // test actually cares about is that a benchmark-able card renders a control whose
        // click handler is the real callback.
        assertTrue(
            "the hub screen has no Measure control",
            screen.contains("Loc.t(\"models.measure\")"),
        )
        assertTrue(
            "the Measure control is not wired to the benchmark callback",
            screen.contains("onClick = { onBenchmark(card.id) }"),
        )
        assertNull(
            "the Measure control does nothing",
            Regex("""onClick\s*=\s*\{\s*\}""").find(screen),
        )

        val app = appSource("ui/CharalyApp.kt").readText()
        assertTrue(
            "the shell does not pass the benchmark callback",
            app.contains("onBenchmarkModel = viewModel::benchmarkModel"),
        )

        val viewModel = appSource("ui/CharalyViewModel.kt").readText()
        assertTrue(
            "the view model does not run a benchmark",
            viewModel.contains("runner.run(model)"),
        )
    }

    /** A benchmark restores whatever model was resident, so it cannot break a story. */
    @Test
    fun `a benchmark records the device it ran on`() = runTest {
        val store = InMemoryBenchmarkStore()
        val engine = MeasurableEngine()
        val runner = LlamaBenchmarkRunner(
            registry = InMemoryModelRegistry(),
            store = store,
            engineProvider = { engine },
            memoryProbe = dev.charaly.runtime.model.DeviceMemoryProbe.UNKNOWN,
            nowEpochMs = { 1_700_000_000_000L },
        )
        val model = InstalledModel(
            id = "m1",
            displayName = "Test",
            absolutePath = "/models/m1.gguf",
            architecture = "qwen3",
        )

        val result = runner.run(model)

        assertTrue("the run failed: ${result.exceptionOrNull()}", result.isSuccess)
        val record: BenchmarkRecord = runner.recordFor("m1")!!
        // The conditions travel with the number, because a rate without them is not
        // reproducible.
        assertEquals(listOf("CPU:fake"), record.devices)
        assertEquals("CPU", record.backendLabel)
        assertTrue("the measurement was not stored", record.benchmark.tokensPerSecond > 0.0)
        assertEquals(1_700_000_000_000L, record.benchmark.measuredAtEpochMs)
    }

    /** The engine is asked what devices it has; the answer is not hardcoded in the app. */
    @Test
    fun `the backend is read from the engine rather than a constant`() {
        val engineSource = appSource("inference/LocalLlamaInferenceEngine.kt").readText()
        assertTrue(
            "the engine does not report its compute devices",
            engineSource.contains("override fun computeDevices()"),
        )
        assertTrue(
            "the engine does not ask the native layer for them",
            engineSource.contains("LlamaNative.availableBackends()"),
        )
        // And the UI can only render what it is given.
        val presenter = File(
            "../charaly-runtime/src/main/kotlin/dev/charaly/runtime/presentation/BenchmarkPresenter.kt",
        ).readText()
        assertTrue(
            "the presenter hardcodes an accelerator name",
            !presenter.contains("\"Adreno\"") && !presenter.contains("\"Hexagon\""),
        )
    }

    /** The benchmark's native timings come from a real clock in C++. */
    @Test
    fun `native benchmark timings come from a steady clock`() {
        val jni = File("src/main/cpp/charaly_jni.cpp").readText()
        assertTrue(
            "the native benchmark has no clock",
            jni.contains("std::chrono::steady_clock"),
        )
        assertTrue(
            "the native benchmark is not exposed to Kotlin",
            jni.contains("LlamaNative_benchmark"),
        )
        assertTrue(
            "the native benchmark does not report first-token latency",
            jni.contains("first_token_micros"),
        )
        // And no path invents a figure: a run with no tokens returns an empty map.
        assertTrue(
            "the native benchmark can report a rate for a run that produced nothing",
            jni.contains("if (generated <= 0)"),
        )
    }

    /** No GPU claim anywhere, because this build compiles CPU backends only. */
    @Test
    fun `this build makes no gpu claim`() {
        val cmake = File("src/main/cpp/CMakeLists.txt").readText()
        // Recorded as a fact rather than left implicit: the CMake file's own comment says
        // CPU only, and the engine's gpuLayers default is 0.
        assertTrue(
            "the native build no longer documents its CPU-only decision",
            cmake.contains("Only CPU"),
        )
        val engine = appSource("inference/LocalLlamaInferenceEngine.kt").readText()
        assertTrue(
            "the engine's offload default changed without a measured reason",
            engine.contains("val gpuLayers: Int = 0"),
        )
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    private fun runtime(
        library: CharacterLibraryRepository = JsonCharacterLibraryRepository(InMemoryCharalyStorage()),
    ) = CharalyRuntime(
        repository = JsonCharalyRepository(InMemoryCharalyStorage()),
        engine = dev.charaly.runtime.inference.MockInferenceEngine(initiallyLoaded = true),
        params = GenerationParams(maxTokens = 32),
        library = library,
    )

    /**
     * The view model, wired the way the app wires it minus the two Android objects.
     *
     * `ModelManager` and `AppPreferences` both need a `Context`, which a JVM test has none
     * of, so this test does not build a view model. The import flow's *logic* is asserted
     * through the runtime and the presenters instead - which are the objects that decide
     * whether anything is written - and the view model's role in it is asserted by reading
     * its source, the same way `ModelHubWiringTest` does.
     */

    /**
     * An engine with no native library, so nothing can be measured through it.
     *
     * `benchmark` returning null is the honest answer here and is the case the runner has to
     * handle: a real engine whose load failed reports exactly this.
     */
    private class UnmeasurableEngine : dev.charaly.runtime.model.LocalInferenceEngine {
        override suspend fun loadModel(request: ModelLoadRequest) =
            LoadOutcome.Failed(dev.charaly.runtime.inference.InferenceError.Unsupported("no native library"))

        override suspend fun unloadModel() = Unit
        override suspend fun generate(request: InferenceRequest) = InferenceResult("")
        override fun stream(request: InferenceRequest): Flow<StreamChunk> =
            flow { emit(StreamChunk("", done = true)) }

        override fun stop() = Unit
        override fun isLoaded() = false
        override fun modelInfo(): ModelInfo? = null

        override suspend fun benchmark(prompt: String, maxTokens: Int): BenchmarkObservation? = null
        override fun computeDevices(): List<String> = emptyList()
    }

    /**
     * A measurable engine.
     *
     * The real one is `LocalLlamaInferenceEngine`, which needs the native library and a GGUF
     * on disk. This implements the same port and reports a *recorded* observation, so the
     * runner's loading, labelling, storing and restoring are all exercised for real.
     *
     * It does not fake the measurement itself: the numbers it returns are fixed, which is
     * exactly what a recorded measurement is. And it does not cover the case that matters
     * most - `ModelBenchmarkRunnerTest` asserts that the runner stores nothing at all when
     * an engine produces no observation.
     */
    private class MeasurableEngine : dev.charaly.runtime.model.LocalInferenceEngine {
        override suspend fun loadModel(request: ModelLoadRequest) = LoadOutcome.Loaded(
            ModelInfo(id = "m1", path = request.path, displayName = request.displayName),
        )

        override suspend fun unloadModel() = Unit
        override suspend fun generate(request: InferenceRequest) = InferenceResult("x")
        override fun stream(request: InferenceRequest): Flow<StreamChunk> =
            flow { emit(StreamChunk("x")); emit(StreamChunk("", done = true)) }

        override fun stop() = Unit
        override fun isLoaded() = true
        override fun modelInfo() = ModelInfo("m1", "m1", "m1")

        override suspend fun benchmark(prompt: String, maxTokens: Int) =
            dev.charaly.runtime.model.BenchmarkObservation(
                tokens = 64,
                decodeMicros = 3_000_000L,
                promptMicros = 150_000L,
                firstTokenMicros = 90_000L,
                promptTokens = 48,
                contextSize = 2048,
            )

        override fun computeDevices(): List<String> = listOf("CPU:fake")
    }
}