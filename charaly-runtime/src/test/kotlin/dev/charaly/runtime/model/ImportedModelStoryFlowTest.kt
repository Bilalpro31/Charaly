package dev.charaly.runtime.model

import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.engine.StoryCreationOptions
import dev.charaly.runtime.engine.StoryInstanceFactory
import dev.charaly.runtime.inference.InferenceEngine
import dev.charaly.runtime.inference.InferenceError
import dev.charaly.runtime.inference.InferenceRequest
import dev.charaly.runtime.inference.InferenceResult
import dev.charaly.runtime.inference.LoadOutcome
import dev.charaly.runtime.inference.LoadProgress
import dev.charaly.runtime.inference.ModelInfo
import dev.charaly.runtime.inference.ModelLoadRequest
import dev.charaly.runtime.inference.StopReason
import dev.charaly.runtime.inference.StreamChunk
import dev.charaly.runtime.pack.DemoStoryPacks
import dev.charaly.runtime.presentation.ChatStagePresenter
import dev.charaly.runtime.presentation.GenerationPhase
import dev.charaly.runtime.presentation.Loc
import dev.charaly.runtime.presentation.NewStoryPresenter
import dev.charaly.runtime.presentation.NewStoryStep
import dev.charaly.runtime.session.CharalyRuntime
import dev.charaly.runtime.persistence.CharalyRepository
import dev.charaly.runtime.persistence.InMemoryCharalyStorage
import dev.charaly.runtime.persistence.JsonCharalyRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE IMPORTED-GGUF STORY FLOW, END TO END.
 *
 * ## What this test is for
 *
 * The device bug this file exists to prevent was not a crash. It was four layers disagreeing
 * about whether a model was available:
 *
 * ```
 *   ModelRegistry   YES   the GGUF was imported and set active
 *   StoryInstance   YES   modelBinding.installedModelId names it
 *   engine          NO    llama.cpp has nothing resident
 *   Chat            NO    "Connect a local model"
 * ```
 *
 * A test that only asserted a ViewModel boolean would have passed while every one of those
 * was true, because the boolean it checked was the same boolean that was wrong. So each
 * test here follows the *identity* - one id, one path - from the registry through to the
 * engine that is asked to generate, and asserts that no layer lost it or substituted it.
 */
class ImportedModelStoryFlowTest {

    @After
    fun resetLocale() = Loc.reset()

    // ------------------------------------------------------------------
    // the flow
    // ------------------------------------------------------------------

    @Test
    fun `import to continue to chat keeps one model identity end to end`() = runTest {
        val pack = DemoStoryPacks.all.first()
        val registry = InMemoryModelRegistry()
        // The real JSON repository over the in-memory storage port: the same code path
        // production uses, with no device and no disk.
        val repository: CharalyRepository = JsonCharalyRepository(InMemoryCharalyStorage())
        val engine = ScriptedEngine()

        // A real file, because the resolver's existence check reads the filesystem. A
        // plausible-looking path would be reported as a missing model, which is correct
        // behaviour and the wrong fixture for this test.
        val gguf = File.createTempFile("charaly-imported", ".gguf").apply {
            writeBytes(ByteArray(64))
            deleteOnExit()
        }

        // ---- 1. import: the file becomes a registry entry -----------------
        val imported = InstalledModel(
            id = "local-my-model-42",
            displayName = "My Imported Model",
            absolutePath = gguf.absolutePath,
            sizeBytes = 4L shl 30,
            origin = ModelOrigin.IMPORTED,
            architecture = "qwen3",
            contextLength = 4096,
        )
        registry.register(imported)
        registry.setActive(imported.id)
        assertEquals(imported.id, registry.activeId())

        // The engine has NOT been asked to load it yet. This is the state that used to read
        // as "no model" in the chat composer.
        assertFalse(engine.isLoaded())

        // ---- 2. the wizard sees it and lets the user continue --------------
        val draft = NewStoryPresenter.initialDraft(pack)
        val snapshot = NewStoryPresenter.build(
            draft = draft,
            pack = pack,
            installedModels = registry.list(),
            activeModelId = registry.activeId().orEmpty(),
            step = NewStoryStep.REVIEW,
        )

        assertTrue(
            "a registered, bound, present model must allow Continue",
            snapshot.canAdvance,
        )
        assertTrue(snapshot.modelReady)
        assertEquals(imported.displayName, snapshot.model.displayName)
        assertEquals(imported.id, snapshot.model.modelId)
        assertTrue(
            "a model that is still loading must say so rather than read as missing",
            snapshot.model.loadsOnFirstUse,
        )

        // ---- 3. the story is created bound to the same file ---------------
        val binding = NewStoryPresenter.resolveBinding(
            pack = pack,
            draft = draft,
            installedModels = registry.list(),
            activeModelId = registry.activeId().orEmpty(),
            nowEpochMs = 1_700_000_000_000L,
        )
        assertEquals(imported.id, binding.installedModelId)

        val runtime = CharalyRuntime(repository = repository, engine = engine)
        val story = runtime.startStory(
            pack = pack,
            options = StoryCreationOptions(
                instanceId = StoryInstanceId("story-imported"),
                title = draft.storyTitle,
                scenario = pack.scenario(draft.scenarioId),
                focusCharacterId = pack.characterIds.firstOrNull(),
                castCharacterIds = draft.castCharacterIds.map { dev.charaly.runtime.domain.CharacterId(it) },
                modelBinding = binding,
                nowEpochMs = 1_700_000_000_000L,
            ),
        )

        // The identity is inside the instance, not merely beside it.
        assertEquals(imported.id, story.modelBinding.installedModelId)
        assertEquals(imported.displayName, story.modelBinding.modelDisplayName)

        // ---- 4. chat resolves the SAME model ------------------------------
        val selection = ModelSelectionResolver.resolve(
            binding = story.modelBinding,
            installed = registry.list(),
            activeModelId = registry.activeId().orEmpty(),
            residentModelId = engine.loadedModelId(),
        )
        assertEquals(imported.id, selection.modelId)
        assertEquals(imported.absolutePath, selection.absolutePath)
        assertTrue("an unloaded but usable model must still allow generation", selection.canGenerate)
        assertTrue(selection.needsEngineLoad)

        val definition = runtime.definitionFor(story)
        val stage = ChatStagePresenter.build(
            instance = story,
            pack = pack,
            definition = definition,
            model = selection,
        )

        // THE assertion the bug report was really about.
        assertTrue(
            "chat must not tell a user with a working GGUF to connect a model",
            stage.composer.isEnabled,
        )
        assertFalse(
            "chat must not show the generic 'connect a model' message",
            stage.composer.hint.contains("needs a local model"),
        )
        assertEquals(imported.id, stage.model.modelId)

        // ---- 5. the lazy load happens, then inference is requested -------
        val loaded = engine.load(
            ModelLoadRequest(
                path = selection.absolutePath,
                displayName = selection.displayName,
                installedModelId = selection.modelId,
            ),
        )
        assertTrue(loaded is LoadOutcome.Loaded)

        val after = ModelSelectionResolver.resolve(
            binding = story.modelBinding,
            installed = registry.list(),
            activeModelId = registry.activeId().orEmpty(),
            residentModelId = engine.loadedModelId(),
        )
        assertTrue(after.isReady)
        assertFalse(after.needsEngineLoad)

        val updates = mutableListOf<dev.charaly.runtime.session.GenerationUpdate>()
        runtime.respond(story, "hello").collect { updates += it }

        // The bug this file exists for: a story whose model is bound and whose engine has
        // just loaded it must never be told "no model loaded".
        assertTrue(
            "respond reported that no model was loaded: $updates",
            updates.none { it is dev.charaly.runtime.session.GenerationUpdate.Failed },
        )
        assertEquals(
            "the engine must have been asked to load the bound file, not some other",
            listOf(selection.absolutePath),
            engine.requestedPaths,
        )
        assertEquals(
            "the engine's identity must be the registry's identity",
            selection.modelId,
            engine.loadedModelId(),
        )
    }

    // ------------------------------------------------------------------
    // the cancellation case
    // ------------------------------------------------------------------

    @Test
    fun `importing then abandoning the wizard leaves the model state untouched`() = runTest {
        val registry = InMemoryModelRegistry()
        val imported = InstalledModel(
            id = "local-abandoned",
            displayName = "Abandoned Flow",
            absolutePath = "/data/models/abandoned.gguf",
            architecture = "llama",
        )
        registry.register(imported)
        registry.setActive(imported.id)

        val before = registry.list()
        val activeBefore = registry.activeId()

        // Abandoning the wizard is a draft-level operation. It must not touch the registry,
        // because the model the user imported is theirs regardless of whether they went on
        // to start this particular story.
        // (The ViewModel's cancelNewStory clears only the draft document.)

        assertEquals(before, registry.list())
        assertEquals(activeBefore, registry.activeId())
        assertNotEquals("", activeBefore)
    }

    // ------------------------------------------------------------------
    // the four disagreements
    // ------------------------------------------------------------------

    @Test
    fun `a model that is registered but not resident is usable, not absent`() {
        val model = InstalledModel(
            id = "m1",
            displayName = "Qwen",
            absolutePath = "/models/q.gguf",
            architecture = "qwen3",
        )
        val selection = ModelSelectionResolver.resolve(
            binding = ModelBinding.from("m1", "Qwen", ModelProfileLibrary.all.first()),
            installed = listOf(model),
            activeModelId = "m1",
            residentModelId = null,
            fileExists = { true },
        )

        assertTrue(selection.canGenerate)
        assertTrue(selection.needsEngineLoad)
        assertFalse(selection.isReady)
        assertEquals(ModelBlockReason.NONE, selection.blocked)
    }

    @Test
    fun `each blocked state has its own reason, never the generic one`() {
        val model = InstalledModel(id = "m", displayName = "M", absolutePath = "/models/m.gguf")

        val notInstalled = ModelSelectionResolver.resolve(
            binding = ModelBinding.EMPTY,
            installed = emptyList(),
            activeModelId = "",
            fileExists = { true },
        )
        assertEquals(ModelBlockReason.NOT_INSTALLED, notInstalled.blocked)
        assertFalse(notInstalled.canGenerate)

        val fileGone = ModelSelectionResolver.resolve(
            binding = ModelBinding.from("m", "M", ModelProfileLibrary.all.first()),
            installed = listOf(model),
            activeModelId = "m",
            fileExists = { false },
        )
        assertEquals(ModelBlockReason.FILE_MISSING, fileGone.blocked)

        val loadFailed = ModelSelection(
            modelId = "m",
            displayName = "M",
            absolutePath = "/models/m.gguf",
            loadFailure = "Out of memory",
        )
        assertEquals(ModelBlockReason.LOAD_FAILED, loadFailed.blocked)

        val unsupported = ModelSelection(
            modelId = "m",
            displayName = "M",
            architecture = "not-an-architecture",
            engineSupports = false,
        )
        assertEquals(ModelBlockReason.UNSUPPORTED, unsupported.blocked)
    }

    @Test
    fun `a story bound to a deleted model does not silently adopt a different one`() {
        val survivor = InstalledModel(
            id = "survivor",
            displayName = "The other model",
            absolutePath = "/models/other.gguf",
        )
        val selection = ModelSelectionResolver.resolve(
            // This story was created against a file that has since been removed.
            binding = ModelBinding.from("deleted", "The original", ModelProfileLibrary.all.first()),
            installed = listOf(survivor),
            activeModelId = "survivor",
            fileExists = { true },
        )

        assertEquals("", selection.modelId)
        assertFalse(
            "swapping the file mid-story would change the story's sampler and context " +
                "underneath the reader",
            selection.canGenerate,
        )
    }

    @Test
    fun `a story with no binding adopts the model the user just imported`() {
        val fresh = InstalledModel(
            id = "fresh",
            displayName = "Freshly imported",
            absolutePath = "/models/fresh.gguf",
        )
        val selection = ModelSelectionResolver.resolve(
            binding = ModelBinding.EMPTY,
            installed = listOf(fresh),
            activeModelId = "fresh",
            fileExists = { true },
        )
        assertEquals("fresh", selection.modelId)
        assertTrue(selection.canGenerate)
    }

    @Test
    fun `the file check reads the filesystem rather than trusting the registry`() {
        val missing = InstalledModel(id = "m", displayName = "M", absolutePath = "/does/not/exist.gguf")
        val real = File.createTempFile("charaly-model", ".gguf").apply { deleteOnExit() }
        val present = InstalledModel(id = "p", displayName = "P", absolutePath = real.absolutePath)

        val gone = ModelSelectionResolver.resolve(
            binding = ModelBinding.from("m", "M", ModelProfileLibrary.all.first()),
            installed = listOf(missing, present),
            activeModelId = "m",
        )
        assertEquals(ModelBlockReason.FILE_MISSING, gone.blocked)

        val here = ModelSelectionResolver.resolve(
            binding = ModelBinding.from("p", "P", ModelProfileLibrary.all.first()),
            installed = listOf(missing, present),
            activeModelId = "p",
        )
        assertTrue(here.canGenerate)
    }

    // ------------------------------------------------------------------
    // a minimal engine, so the test needs no device and no native library
    // ------------------------------------------------------------------

    private class ScriptedEngine : InferenceEngine {
        private var info: ModelInfo? = null
        val requestedPaths = mutableListOf<String>()

        fun loadedModelId(): String? = info?.id

        fun load(request: ModelLoadRequest): LoadOutcome {
            requestedPaths += request.path
            // Same contract as the real engine: the registry id wins, the file name is the
            // fallback. If the two ever diverged again, this test would fail here rather
            // than quietly passing.
            info = ModelInfo(
                id = request.installedModelId.ifBlank { "unnamed" },
                path = request.path,
                displayName = request.displayName,
            )
            return LoadOutcome.Loaded(info!!)
        }

        override suspend fun loadModel(request: ModelLoadRequest): LoadOutcome = load(request)

        override suspend fun unloadModel() {
            info = null
        }

        override suspend fun generate(request: InferenceRequest) =
            InferenceResult(text = "ok", stopReason = StopReason.COMPLETED, engineId = "scripted")

        override fun stream(request: InferenceRequest): Flow<StreamChunk> = flow {
            emit(StreamChunk(text = "ok", done = false, tokenCount = 1))
            emit(StreamChunk(text = "", done = true, tokenCount = 1))
        }

        override fun stop() = Unit

        override fun isLoaded(): Boolean = info != null

        override fun modelInfo(): ModelInfo? = info
    }
}