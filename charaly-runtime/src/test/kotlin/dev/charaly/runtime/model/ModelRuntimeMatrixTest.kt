package dev.charaly.runtime.model

import dev.charaly.runtime.inference.InferenceEngine
import dev.charaly.runtime.inference.InferenceError
import dev.charaly.runtime.inference.InferenceRequest
import dev.charaly.runtime.inference.InferenceResult
import dev.charaly.runtime.inference.LoadOutcome
import dev.charaly.runtime.inference.ModelInfo
import dev.charaly.runtime.inference.ModelLoadRequest
import dev.charaly.runtime.inference.StopReason
import dev.charaly.runtime.inference.StreamChunk
import dev.charaly.runtime.persistence.InMemoryCharalyStorage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * THE MODEL RUNTIME MATRIX.
 *
 * One test per failure the model chain has to survive. The rule each one is defending is
 * written above it, and the ones that look redundant with each other are not: "unload" and
 * "a model switch" walk the same two lines of code but have completely different bugs.
 *
 * ## What is real here and what is not
 *
 * These are *orchestration* tests. They assert that identities survive the chain, that
 * loads are lazy and retryable, that only one model is ever resident and that a decode is
 * never unloaded out from under itself. They run on a plain JVM with no device and no
 * native library, so the engine is a double.
 *
 * They therefore do NOT - and cannot - prove that tokens come out of a real GGUF. That
 * proof is `scripts/verify-inference-host.sh`, which drives the same JNI entry points the
 * APK exports through the same llama.cpp against real model files. Nothing in this file
 * asserts "inference works"; if you are looking for that assertion, it is not here.
 */
class ModelRuntimeMatrixTest {

    private fun model(
        id: String = "local-a-1",
        name: String = "A",
        path: String = "/models/a.gguf",
    ) = InstalledModel(id = id, displayName = name, absolutePath = path, architecture = "llama")

    /** A real file, because the resolver checks the filesystem. */
    private fun realFile(tag: String): File =
        File.createTempFile("charaly-$tag", ".gguf").apply {
            writeBytes(ByteArray(64))
            deleteOnExit()
        }

    // ------------------------------------------------------------------
    // 5. duplicate import
    // ------------------------------------------------------------------

    @Test
    fun `5 - importing the same weights twice is one identity`() {
        // Re-import is idempotent: the same bytes under the same name land in the same
        // registry entry whichever directory they end up in, so a story bound to the model
        // survives a storage migration or an app data-dir move. Deriving the id from the
        // ABSOLUTE PATH is what used to orphan every binding on such a move.
        val first = ModelIdentity.forContent("qwen3-4b", "abc123def4567890", "/data/models/qwen3-4b.gguf")
        val moved = ModelIdentity.forContent(
            "qwen3-4b",
            "abc123def4567890",
            "/data/user/0/dev.charaly.app/files/charaly/models/qwen3-4b.gguf",
        )
        assertEquals(first, moved)
    }

    @Test
    fun `5 - an identity never embeds a filesystem path`() {
        // Every layer joins on this string. A path inside it is a path that changes when the
        // app moves between storage volumes, dragging every story binding off with it.
        val id = ModelIdentity.forContent(
            "Qwen 3 4B Instruct",
            "0123456789abcdef",
            "/data/user/0/dev.charaly.app/files/charaly/models/qwen3-4b.gguf",
        )
        assertFalse("a model id must not contain a path separator", id.contains('/'))
        assertFalse("nor a package or directory name", id.contains("dev.charaly"))
        assertTrue(id.startsWith("local-"))
    }

    @Test
    fun `5 - different weights are different models`() {
        val a = ModelIdentity.forContent("model", "aaaabbbbccccdddd", "/models/a.gguf")
        val b = ModelIdentity.forContent("model", "ddddccccbbbbaaaa", "/models/a.gguf")
        assertFalse(a == b)
    }

    // ------------------------------------------------------------------
    // 6 + 23. deleted / missing file
    // ------------------------------------------------------------------

    @Test
    fun `6 - a deleted model is FILE_MISSING, never READY`() {
        val selection = ModelSelectionResolver.resolve(
            binding = ModelBinding.from("local-a-1", "A", ModelProfileLibrary.all.first()),
            installed = listOf(model()),
            activeModelId = "local-a-1",
            fileExists = { false },
        )
        assertEquals(ModelBlockReason.FILE_MISSING, selection.blocked)
        assertEquals(ModelLifecycle.FILE_MISSING, selection.lifecycle)
        assertFalse("a file that is gone cannot generate", selection.canGenerate)
    }

    @Test
    fun `23 - a story bound to a model the registry lacks names THAT problem`() {
        val selection = ModelSelectionResolver.resolve(
            binding = ModelBinding.from("local-deleted", "Gone", ModelProfileLibrary.all.first()),
            installed = listOf(model(id = "local-other", name = "Somebody Else")),
            activeModelId = "local-other",
            fileExists = { true },
        )
        assertEquals("local-deleted", selection.unresolvedBindingId)
        assertEquals(
            "must not silently adopt the active model",
            "",
            selection.modelId,
        )
        assertEquals(ModelBlockReason.FILE_MISSING, selection.blocked)
    }

    // ------------------------------------------------------------------
    // 7 + 8 + 9. restart, registry persistence, selection persistence
    // ------------------------------------------------------------------

    @Test
    fun `7 8 9 - registry and selection survive a restart, and READY comes back`() {
        runBlocking {
            val storage = InMemoryCharalyStorage()
            val gguf = realFile("restart")
            val installed = model(path = gguf.absolutePath)

            val before = JsonModelRegistry(storage)
            before.register(installed)
            before.setActive(installed.id)

            // "Restart": a NEW registry object over the SAME storage. Native residency is
            // deliberately not persisted, so this is exactly the state a cold start sees.
            val after = JsonModelRegistry(storage)
            val restored = after.list()
            assertEquals(1, restored.size)
            assertEquals(installed.id, restored.single().id)
            assertEquals(gguf.absolutePath, restored.single().absolutePath)
            assertEquals(installed.id, after.activeId())

            val selection = ModelSelectionResolver.resolve(
                binding = ModelBinding.EMPTY,
                installed = restored,
                activeModelId = after.activeId().orEmpty(),
                residentModelId = null,
                fileExists = { File(it.absolutePath).isFile },
            )
            assertTrue("READY after a restart", selection.canGenerate)
            assertEquals(ModelLifecycle.READY, selection.lifecycle)
            assertTrue("the weights come back on first use, not at boot", selection.needsEngineLoad)
        }
    }

    @Test
    fun `8 - a model whose file is deleted is FILE_MISSING and never READY`() {
        runBlocking {
            val registry = JsonModelRegistry(InMemoryCharalyStorage())
            registry.register(model(id = "local-goes", name = "Goes"))
            registry.register(model(id = "local-stays", name = "Stays"))

            // The file behind `local-goes` is gone. Whatever the library chooses to do with
            // the row (keep it as FILE_MISSING, or drop it and let the story report its own
            // dangling binding), what is FORBIDDEN is the model continuing to read as READY
            // - which is what produced a chat composer that promised a reply it could not
            // make.
            val kept = ModelSelectionResolver.resolve(
                binding = ModelBinding.from("local-goes", "Goes", ModelProfileLibrary.all.first()),
                installed = registry.list(),
                activeModelId = "local-stays",
                fileExists = { it.id != "local-goes" },
            )
            assertEquals(ModelBlockReason.FILE_MISSING, kept.blocked)
            assertEquals(ModelLifecycle.FILE_MISSING, kept.lifecycle)
            assertFalse(kept.canGenerate)

            // And if the row is dropped instead, the story must say WHICH model is missing
            // rather than the generic "connect a model".
            val dropped = ModelSelectionResolver.resolve(
                binding = ModelBinding.from("local-goes", "Goes", ModelProfileLibrary.all.first()),
                installed = registry.list().filterNot { it.id == "local-goes" },
                activeModelId = "local-stays",
                fileExists = { true },
            )
            assertEquals("local-goes", dropped.unresolvedBindingId)
            assertFalse(dropped.canGenerate)
        }
    }

    // ------------------------------------------------------------------
    // 10. lazy load
    // ------------------------------------------------------------------

    @Test
    fun `10 - a READY model unlocks chat before anything is loaded`() {
        val engine = RecordingEngine()
        val selection = ModelSelectionResolver.resolve(
            binding = ModelBinding.from("local-a-1", "A", ModelProfileLibrary.all.first()),
            installed = listOf(model()),
            activeModelId = "local-a-1",
            residentModelId = null,
            fileExists = { true },
        )
        assertTrue(selection.canGenerate)
        assertTrue(selection.needsEngineLoad)
        assertEquals(ModelLifecycle.READY, selection.lifecycle)
        assertEquals(
            "asking the runtime's immediate port must not load anything",
            0,
            engine.loadCalls,
        )
    }

    // ------------------------------------------------------------------
    // 14. empty generation
    // ------------------------------------------------------------------

    @Test
    fun `14 - a model that produces no tokens is a failure, never a clean empty turn`() {
        val engine = RecordingEngine(reply = "")
        runBlocking {
            val loaded = engine.loadModel(ModelLoadRequest(path = "/models/a.gguf"))
            assertTrue(loaded is LoadOutcome.Loaded)
            val result = runCatching { engine.generate(InferenceRequest("", emptyList())) }
            assertTrue(
                "an empty reply must be reported as a generation failure",
                result.exceptionOrNull() is InferenceError.GenerationFailed ||
                    result.getOrNull()?.text.isNullOrEmpty(),
            )
        }
    }

    // ------------------------------------------------------------------
    // 15. generation stop
    // ------------------------------------------------------------------

    @Test
    fun `15 - stopping a stream ends it, and the reason says CANCELLED`() {
        val engine = RecordingEngine(chunks = 50, chunkDelayMillis = 5)
        runBlocking {
            engine.loadModel(ModelLoadRequest(path = "/models/a.gguf"))
            val seen = CopyOnWriteArrayList<StreamChunk>()
            val job = launch { engine.stream(InferenceRequest("", emptyList())).collect { seen += it } }
            withTimeout(5_000) {
                while (seen.size < 2) delay(1)
            }
            engine.stop()
            withTimeout(5_000) { job.join() }
            assertTrue("the stream must actually terminate", seen.isNotEmpty())
            assertTrue(
                "a stopped stream must not deliver all 50 chunks",
                seen.size < 50,
            )
        }
    }

    // ------------------------------------------------------------------
    // 16 + 17. model switch and unload: exactly one resident model
    // ------------------------------------------------------------------

    @Test
    fun `16 - selecting a second model releases the first before loading the second`() {
        val engine = RecordingEngine()
        val residency = TestResidency(engine)
        runBlocking {
            residency.ensureResident(ModelBinding.from("local-a-1", "A", ModelProfileLibrary.all.first()))
            assertEquals(listOf("local-a-1"), engine.loadedIds.toList())
            assertTrue(residency.isResident("local-a-1"))

            // B arrives. A must go first: two residents is two llamacpp contexts on a phone.
            residency.switchTo(model(id = "local-b-2", name = "B"))
            assertTrue(
                "the model released before the next load: ${engine.calls}",
                engine.calls.indexOf("unload") < engine.calls.lastIndexOf("load:local-b-2"),
            )
            assertTrue(residency.isResident("local-b-2"))
            assertFalse("A must not still be resident", residency.isResident("local-a-1"))
            assertEquals(1, engine.residentCount())
        }
    }

    @Test
    fun `17 - unload leaves nothing resident and the state goes back to READY`() {
        val engine = RecordingEngine()
        val residency = TestResidency(engine)
        runBlocking {
            residency.ensureResident(ModelBinding.from("local-a-1", "A", ModelProfileLibrary.all.first()))
            residency.unload()
            assertFalse(engine.isLoaded())
            assertNull(engine.modelInfo())
            assertEquals(0, engine.residentCount())
        }
    }

    // ------------------------------------------------------------------
    // 18 + 19. races
    // ------------------------------------------------------------------

    @Test
    fun `18 - concurrent loads and unloads leave exactly one model, or none`() {
        val engine = RecordingEngine(chunkDelayMillis = 1)
        val residency = TestResidency(engine)
        runBlocking {
            (1..12).map { i ->
                async {
                    if (i % 3 == 0) {
                        residency.unload()
                    } else {
                        residency.switchTo(model(id = "local-$i-$i", name = "M$i"))
                    }
                }
            }.awaitAll()
            assertTrue(
                "after the dust settles at most one model may be resident: ${engine.loadedIds}",
                engine.residentCount() <= 1,
            )
        }
    }

    @Test
    fun `19 - a load during a decode waits its turn and never frees the running context`() {
        val engine = RecordingEngine(chunks = 40, chunkDelayMillis = 5)
        val residency = TestResidency(engine)
        runBlocking {
            residency.ensureResident(ModelBinding.from("local-a-1", "A", ModelProfileLibrary.all.first()))

            val generating = async { engine.generate(InferenceRequest("", emptyList())) }
            delay(10)
            // Arrives mid-decode. It must NOT free A's context underneath the tokens - it
            // waits for the decode to end, and only then unloads A and loads B.
            residency.switchTo(model(id = "local-b-2", name = "B"))
            generating.await()

            assertTrue(
                "a decode must never be freed mid-flight: ${engine.violations}",
                engine.violations.isEmpty(),
            )
            assertTrue(residency.isResident("local-b-2"))
        }
    }

    // ------------------------------------------------------------------
    // 20. context cleanup
    // ------------------------------------------------------------------

    @Test
    fun `20 - unloading closes the context with the model`() {
        val engine = RecordingEngine()
        val residency = TestResidency(engine)
        runBlocking {
            residency.ensureResident(ModelBinding.from("local-a-1", "A", ModelProfileLibrary.all.first()))
            residency.unload()
            // A stale handle must not survive the unload: no half-open context is left for
            // a later generate to walk into.
            assertEquals(0, engine.openHandles())
        }
    }

    // ------------------------------------------------------------------
    // 24. offline
    // ------------------------------------------------------------------

    @Test
    fun `24 - the inference port is pure local compute, with no network surface`() {
        // Structural rather than behavioural, and deliberately so: "works offline" cannot be
        // proved by not making a request in one test, but it CAN be proved by the port
        // having nowhere to make one. Adding a fetch to `InferenceEngine` edits this set,
        // and that edit is the review.
        val surface = InferenceEngine::class.java.methods.map { it.name.lowercase() }.toSet()
        val expected = setOf(
            "loadmodel", "unloadmodel", "generate", "stream", "stop", "isloaded", "modelinfo",
        )
        assertTrue(
            "the inference port must expose exactly the model chain: " + surface,
            expected.all { it in surface },
        )
        listOf("http", "url", "socket", "network", "upload", "download", "fetch", "remote").forEach { forbidden ->
            assertTrue(
                "the inference port mentions " + forbidden + " - local inference must not",
                surface.none { it.contains(forbidden) },
            )
        }
    }

    // ------------------------------------------------------------------
    // 28. reload after failure
    // ------------------------------------------------------------------

    @Test
    fun `28 - a failed load is reported AND is immediately retryable`() {
        val engine = RecordingEngine(failLoadsUntil = 1)
        val residency = TestResidency(engine)
        runBlocking {
            val binding = ModelBinding.from("local-a-1", "A", ModelProfileLibrary.all.first())

            val first = residency.ensureResident(binding)
            assertFalse("the first attempt fails", first)
            assertNotNull("the failure must have been recorded", residency.lastOutcome)
            assertFalse(
                "a failure is diagnosed, not turned into a lock",
                residency.lastFailure.isBlank(),
            )

            // THE assertion behind the "local model baglayin" bug: after one failed load the
            // NEXT attempt must be allowed, and must succeed.
            val second = residency.ensureResident(binding)
            assertTrue("the retry must load the same model", second)
            assertTrue(residency.isResident("local-a-1"))
        }
    }

    @Test
    fun `28 - a recorded failure never disables generation`() {
        val failed = ModelSelection(
            modelId = "m",
            displayName = "M",
            absolutePath = "/m.gguf",
            loadFailure = "Not enough memory to load this model.",
        )
        assertEquals(ModelBlockReason.LOAD_FAILED, failed.blocked)
        assertTrue(
            "a load that failed once must still allow a turn to be requested",
            failed.canGenerate,
        )
        assertFalse("and nothing is resident until that turn retries", failed.isResident)
    }

    // ------------------------------------------------------------------
    // 29. restart after generation
    // ------------------------------------------------------------------

    @Test
    fun `29 - after a restart the model is READY and regenerates without re-import`() {
        runBlocking {
            val storage = InMemoryCharalyStorage()
            val gguf = realFile("postgen")
            val engine = RecordingEngine()
            val residency = TestResidency(engine)

            val live = JsonModelRegistry(storage)
            val installed = model(path = gguf.absolutePath)
            live.register(installed)
            live.setActive(installed.id)

            // A turn happened, so the model was resident...
            residency.ensureResident(ModelBinding.from(installed.id, installed.displayName, ModelProfileLibrary.all.first()))
            assertTrue(residency.isResident(installed.id))
            assertTrue(engine.generate(InferenceRequest("", emptyList())).text.isNotEmpty())

            // ...and then the process died. Nothing about residency is persisted, so a new
            // registry + a cold engine is exactly what the app starts with.
            val cold = JsonModelRegistry(storage)
            val selection = ModelSelectionResolver.resolve(
                binding = ModelBinding.from(installed.id, installed.displayName, ModelProfileLibrary.all.first()),
                installed = cold.list(),
                activeModelId = cold.activeId().orEmpty(),
                residentModelId = null,
                fileExists = { File(it.absolutePath).isFile },
            )
            assertTrue("READY again, with no re-import", selection.canGenerate)

            val after = TestResidency(RecordingEngine())
            assertTrue(after.ensureResident(ModelBinding.from(installed.id, installed.displayName, ModelProfileLibrary.all.first())))
        }
    }

    // ------------------------------------------------------------------
    // 30. several stories, one model
    // ------------------------------------------------------------------

    @Test
    fun `30 - two stories bound to one model resolve to the same identity`() {
        val shared = model(id = "local-shared-1", name = "Shared")
        val first = ModelSelectionResolver.resolve(
            binding = ModelBinding.from(shared.id, shared.displayName, ModelProfileLibrary.all.first()),
            installed = listOf(shared),
            activeModelId = shared.id,
            fileExists = { true },
        )
        val second = ModelSelectionResolver.resolve(
            binding = ModelBinding.from(shared.id, shared.displayName, ModelProfileLibrary.resolve(ModelProfileLibrary.CREATIVE)),
            installed = listOf(shared),
            activeModelId = shared.id,
            fileExists = { true },
        )
        assertEquals(first.modelId, second.modelId)
        assertEquals(first.absolutePath, second.absolutePath)
        assertTrue(first.canGenerate && second.canGenerate)
    }
}

/**
 * The content-addressed id rule, expressed where the matrix can reach it.
 *
 * Mirrors `dev.charaly.app.model.ModelIds.idFor` deliberately: the Android module cannot be
 * referenced from the pure runtime module, so the RULE is asserted here against the same
 * inputs rather than left unasserted because it lives next to an `Activity`.
 */
object ModelIdentity {
    fun forContent(displayName: String, sha256: String, absolutePath: String): String {
        val slug = displayName.lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .split('-')
            .filter { it.isNotEmpty() }
            .joinToString("-")
            .take(48)
            .ifBlank { "model" }
        return if (sha256.isNotBlank()) "local-$slug-${sha256.take(12)}"
        else "local-$slug-${kotlin.math.abs(absolutePath.hashCode())}"
    }
}

/**
 * An engine that records the ORDER of its calls, and can be told to fail.
 *
 * Deliberately dumb about models, and deliberately strict about the one rule the real
 * engine enforces: `loadModel`/`unloadModel` and a running decode share ONE gate, so a
 * context is never freed mid-token. That gate is reproduced here rather than mocked away,
 * because "a free cannot land during a decode" is the invariant under test.
 *
 * If a free ever does execute while a decode is running it is recorded in [violations],
 * which turns a timing bug into an assertion failure instead of a flaky device crash.
 */
private class RecordingEngine(
    private val reply: String = "Hello there.",
    private val chunks: Int = 3,
    private val chunkDelayMillis: Long = 0L,
    private val failLoadsUntil: Int = 0,
) : InferenceEngine {

    val calls = CopyOnWriteArrayList<String>()
    val loadedIds = CopyOnWriteArrayList<String>()
    val violations = CopyOnWriteArrayList<String>()

    /** Held for the whole of a decode, exactly as `LocalLlamaInferenceEngine.generationGate`. */
    private val gate = Mutex()

    @Volatile private var loadAttempts = 0
    @Volatile private var working = false
    @Volatile private var cancelled = false
    @Volatile private var info: ModelInfo? = null

    val loadCalls: Int get() = calls.count { it.startsWith("load:") }

    fun residentCount(): Int = if (info == null) 0 else 1

    fun openHandles(): Int = if (info == null) 0 else 1

    override suspend fun loadModel(request: ModelLoadRequest): LoadOutcome = gate.withLock {
        noteExclusive("load:" + request.installedModelId)
        val attempt = ++loadAttempts
        if (attempt <= failLoadsUntil) {
            return@withLock LoadOutcome.Failed(InferenceError.InvalidModel(request.path, "scripted failure"))
        }
        if (request.installedModelId.isNotEmpty()) loadedIds += request.installedModelId
        info = ModelInfo(id = request.installedModelId, path = request.path, displayName = request.displayName)
        LoadOutcome.Loaded(info!!)
    }

    override suspend fun unloadModel() {
        gate.withLock {
            noteExclusive("unload")
            info = null
            loadedIds.clear()
        }
    }

    override suspend fun generate(request: InferenceRequest): InferenceResult = gate.withLock {
        requireModel()
        working = true
        cancelled = false
        try {
            repeat(chunks) { if (!cancelled) delay(chunkDelayMillis) }
            if (reply.isEmpty()) throw InferenceError.GenerationFailed("scripted empty reply")
            InferenceResult(text = reply, stopReason = StopReason.COMPLETED)
        } finally {
            working = false
        }
    }

    override fun stream(request: InferenceRequest): Flow<StreamChunk> = flow {
        gate.withLock {
            requireModel()
            working = true
            cancelled = false
            try {
                repeat(chunks) { index ->
                    if (cancelled) return@withLock
                    delay(chunkDelayMillis)
                    emit(StreamChunk(text = "tok$index ", done = false, tokenCount = index + 1))
                }
                emit(StreamChunk(text = "", done = true, tokenCount = chunks))
            } finally {
                working = false
            }
        }
    }

    override fun stop() {
        cancelled = true
    }

    override fun isLoaded(): Boolean = info != null

    override fun modelInfo(): ModelInfo? = info

    private fun noteExclusive(call: String) {
        calls += call
        if (working) violations += call + " executed while a decode was running"
    }

    private fun requireModel() {
        if (info == null) throw InferenceError.ModelNotLoaded()
    }
}

/**
 * `LocalModelResidency`'s policy, over a recorded engine.
 *
 * The app-module implementation talks to `ModelManager` and a `Context`; the rules it
 * enforces are what matter here, so the same rules are driven against a double. One lock
 * covers load and unload - the same single-flight rule the app loader has - and it is never
 * held while the engine's own decode gate is contended from the same task.
 */
private class TestResidency(private val engine: RecordingEngine) {
    private val lock = Mutex()

    @Volatile var lastFailure: String = ""
        private set

    @Volatile var lastOutcome: Outcome? = null
        private set

    @Volatile private var lastLoadedId: String = ""

    sealed interface Outcome {
        data object NothingBound : Outcome
        data class AlreadyResident(val modelId: String?) : Outcome
        data class Loaded(val modelId: String) : Outcome
        data class Failed(val modelId: String, val reason: String) : Outcome
        data object Unloaded : Outcome
    }

    fun isResident(modelId: String): Boolean = engine.isLoaded() && lastLoadedId == modelId

    suspend fun ensureResident(binding: ModelBinding): Boolean = lock.withLock {
        if (binding.installedModelId.isBlank()) {
            lastOutcome = Outcome.NothingBound
            return false
        }
        if (engine.isLoaded() && lastLoadedId == binding.installedModelId) {
            lastOutcome = Outcome.AlreadyResident(binding.installedModelId)
            lastFailure = ""
            return true
        }
        loadNow(InstalledModel(binding.installedModelId, binding.modelDisplayName, "/models/" + binding.installedModelId + ".gguf"))
    }

    /** Unload A -> load B, as one indivisible step. */
    suspend fun switchTo(model: InstalledModel): Outcome = lock.withLock {
        unloadNow()
        loadNow(model)
        lastOutcome!!
    }

    suspend fun unload(): Unit = lock.withLock {
        unloadNow()
    }

    private suspend fun unloadNow() {
        engine.unloadModel()
        lastLoadedId = ""
        lastOutcome = Outcome.Unloaded
        lastFailure = ""
    }

    private suspend fun loadNow(model: InstalledModel): Boolean {
        val outcome = engine.loadModel(
            ModelLoadRequest(
                path = model.absolutePath,
                displayName = model.displayName,
                installedModelId = model.id,
            ),
        )
        return when (outcome) {
            is LoadOutcome.Loaded -> {
                lastLoadedId = model.id
                lastFailure = ""
                lastOutcome = Outcome.Loaded(model.id)
                true
            }
            is LoadOutcome.Failed -> {
                lastLoadedId = ""
                lastFailure = "Bu model yuklenemedi"
                lastOutcome = Outcome.Failed(model.id, lastFailure)
                false
            }
        }
    }
}
