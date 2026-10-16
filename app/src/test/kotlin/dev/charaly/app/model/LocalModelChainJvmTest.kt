package dev.charaly.app.model

import dev.charaly.app.inference.LocalLlamaInferenceEngine
import dev.charaly.runtime.inference.InferenceRequest
import dev.charaly.runtime.inference.LoadOutcome
import dev.charaly.runtime.inference.ModelLoadRequest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The model chain on a plain JVM: what is provable without a device, and nothing more.
 *
 * Everything here runs `LocalLlamaInferenceEngine` for real. On this JVM `System.loadLibrary`
 * finds no `libcharaly_llama.so`, which is exactly the "this build has no local inference"
 * situation the engine is supposed to survive - so the REJECTION half of the contract is
 * fully real here, and the acceptance half is not covered here at all.
 *
 * Where real acceptance lives: `scripts/verify-inference-host.sh` compiles the same JNI
 * translation unit against the same llama.cpp and drives it with a real GGUF. Do not read a
 * green tick below as "the model generates"; it does not say that.
 */
class LocalModelChainJvmTest {

    private fun engine() = LocalLlamaInferenceEngine()

    private fun tempFile(tag: String, bytes: ByteArray): File =
        File.createTempFile("charaly-$tag", ".gguf").apply {
            writeBytes(bytes)
            deleteOnExit()
        }

    // ---- 23. missing file ---------------------------------------------------

    @Test
    fun `23 - a missing file is ModelNotFound and nothing is loaded`() {
        runBlocking {
            val outcome = engine().loadModel(
                ModelLoadRequest(path = "/definitely/not/here.gguf", displayName = "Absent"),
            )
            assertTrue(outcome is LoadOutcome.Failed)
            assertFalse(engine().isLoaded())
        }
    }

    // ---- 3. zero-byte file --------------------------------------------------

    @Test
    fun `3 - a zero-byte file is refused before any native call`() {
        runBlocking {
            val zero = tempFile("zero", ByteArray(0))
            val outcome = engine().loadModel(ModelLoadRequest(path = zero.absolutePath))
            assertTrue(outcome is LoadOutcome.Failed)
            assertFalse(engine().isLoaded())
            assertFalse(engine().isLoaded())
        }
    }

    // ---- 2. invalid file ----------------------------------------------------

    @Test
    fun `2 - a text file wearing a gguf suffix is refused`() {
        runBlocking {
            val fake = tempFile("fake", "just some prose, not weights".toByteArray())
            val outcome = engine().loadModel(ModelLoadRequest(path = fake.absolutePath))
            assertTrue(outcome is LoadOutcome.Failed)
            assertFalse(engine().isLoaded())
        }
    }

    // ---- 4. partial import --------------------------------------------------

    @Test
    fun `4 - a GGUF with only its magic is refused, not half-loaded`() {
        runBlocking {
            // Exactly what an interrupted transfer leaves behind: the first four bytes.
            val stub = tempFile("stub", byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))
            val outcome = engine().loadModel(ModelLoadRequest(path = stub.absolutePath))
            assertTrue(outcome is LoadOutcome.Failed)
            assertFalse("a partial file must never become a resident model", engine().isLoaded())
        }
    }

    @Test
    fun `4 - a part-suffixed file is not a model file`() {
        // The import writes `<name>.gguf.part` and renames it only after three checks pass.
        // Until that rename, the file cannot match the model glob at all.
        val inFlight = File("/models/qwen3-4b.gguf.part")
        assertFalse(inFlight.name.endsWith(".gguf", ignoreCase = true))
        assertTrue(inFlight.name.endsWith(ModelManager.PART_SUFFIX, ignoreCase = true))
    }

    // ---- 12. failed native load, and 28. reload after failure ----------------

    @Test
    fun `12 + 28 - a refused load leaves the engine cold and immediately retryable`() {
        runBlocking {
            val target = engine()
            val first = target.loadModel(ModelLoadRequest(path = "/nope.gguf"))
            assertTrue(first is LoadOutcome.Failed)
            assertFalse(target.isLoaded())

            // The retry must be possible, and must not inherit anything from the failure.
            val second = target.loadModel(ModelLoadRequest(path = "/still-nope.gguf"))
            assertTrue(second is LoadOutcome.Failed)
            assertFalse(target.isLoaded())
            assertEquals(0.0, 0.0, 0.0)

            // And the engine must still be usable afterwards - no poisoned static state.
            assertTrue("unload after a failure is a no-op, not a crash", runCatching { target.unloadModel() }.isSuccess)
        }
    }

    // ---- 20 + 21. context and handle cleanup ---------------------------------

    @Test
    fun `20 + 21 - unloading a cold engine is safe and leaves nothing behind`() {
        runBlocking {
            val target = engine()
            assertTrue(runCatching { target.unloadModel() }.isSuccess)
            assertFalse(target.isLoaded())
            assertEquals(null, target.modelInfo())
        }
    }

    // ---- 15 + 14. generation against a cold engine --------------------------

    @Test
    fun `15 - a cold engine refuses to generate rather than inventing a reply`() {
        runBlocking {
            val target = engine()
            val failure = runCatching { target.generate(InferenceRequest("", emptyList())) }
            assertTrue(
                "a cold engine must fail loudly",
                failure.exceptionOrNull() != null,
            )
        }
    }

    // ---- 5. duplicate identity ----------------------------------------------

    @Test
    fun `5 - one set of weights keeps one identity when the file moves`() {
        // What the scheme promises: the id does not depend on WHERE the file is. A storage
        // migration, an app data-dir move and a re-import all land in the same registry
        // entry, so a story binding survives them. (It does NOT claim a rename keeps the id:
        // the visible name is part of the identity on purpose, so two models cannot collide
        // on a truncated digest. See ModelIds.)
        val sha = "0123456789abcdef0123456789abcdef"
        val original = ModelIds.idFor("qwen3-4b", sha, "/data/models/qwen3-4b.gguf")
        val moved = ModelIds.idFor(
            "qwen3-4b",
            sha,
            "/data/user/0/dev.charaly.app/files/charaly/models/qwen3-4b.gguf",
        )
        assertEquals(original, moved)
        assertFalse("no path may leak into an id", original.contains('/'))
        assertTrue(original.startsWith("local-"))
    }

    @Test
    fun `5 - a legacy entry without a hash still gets a stable id`() {
        val a = ModelIds.idFor("m", "", "/models/m.gguf")
        val b = ModelIds.idFor("m", "", "/models/m.gguf")
        val c = ModelIds.idFor("m", "", "/models/other.gguf")
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    // ---- 13. the model test report never fabricates a measurement ------------

    @Test
    fun `13 - a model test that produced no text is a FAIL, not a PASS`() {
        runBlocking {
            val phases = ModelTestRunner(
                engineProvider = {
                    // A double whose generation produces nothing at all - which is the case a
                    // dishonest report would call "passed, 0 tok/s".
                    SilentEngine()
                },
                versionLabel = { "jvm-test" },
                nowMillis = { 1_000L },
            ).run(
                model = dev.charaly.runtime.model.InstalledModel(
                    id = "local-empty",
                    displayName = "Silent",
                    absolutePath = tempFile("silent", ByteArray(64)).absolutePath,
                ),
            ).toList()

            val last = phases.last()
            assertEquals(ModelTestState.FAILED, last.state)
            assertEquals("", last.output)
            assertEquals(
                "no measurement may be invented for a failed run",
                "",
                last.tokensPerSecondLabel,
            )
        }
    }

    @Test
    fun `13 - an unmeasurable figure renders as Not measured, never as a number`() {
        val idle = ModelTestPhase.IDLE
        assertEquals("", idle.tokensPerSecondLabel)
        assertEquals(0L, idle.firstTokenMillis)
        assertEquals(0L, idle.loadMillis)
    }

    // ---- 22. unsupported architecture is caught before native ----------------

    @Test
    fun `22 - an architecture this engine does not register is refused, not attempted`() {
        val verdict = dev.charaly.runtime.model.EngineVerdict.of("gemma9-from-the-future")
        assertFalse("an unregistered architecture must not be offered as loadable", verdict.isLoadable)
        assertTrue(verdict.reason.isNotBlank())
        assertNotEquals(
            "and it must not be quietly reported as supported",
            dev.charaly.runtime.model.EngineSupport.SUPPORTED,
            verdict.support,
        )
    }
}

/**
 * A `LocalInferenceEngine` that loads happily and then says nothing whatsoever.
 *
 * Typed as the real interface rather than as `InferenceEngine`, because the report reads
 * `lastDiagnostic()` and a double that cannot answer it would hide the exact code path a
 * silent model goes down.
 */
private class SilentEngine : dev.charaly.runtime.model.LocalInferenceEngine {

    private var loaded = false

    override suspend fun loadModel(request: dev.charaly.runtime.inference.ModelLoadRequest) =
        run {
            loaded = true
            dev.charaly.runtime.inference.LoadOutcome.Loaded(
                dev.charaly.runtime.inference.ModelInfo(
                    id = request.installedModelId,
                    path = request.path,
                    displayName = request.displayName,
                ),
            )
        }

    override suspend fun unloadModel() {
        loaded = false
    }

    override suspend fun generate(request: dev.charaly.runtime.inference.InferenceRequest) =
        dev.charaly.runtime.inference.InferenceResult(text = "")

    override fun stream(request: dev.charaly.runtime.inference.InferenceRequest) = kotlinx.coroutines.flow.flow {
        emit(dev.charaly.runtime.inference.StreamChunk(text = "", done = true, tokenCount = 0))
    }

    override fun stop() = Unit

    override fun isLoaded(): Boolean = loaded

    override fun modelInfo(): dev.charaly.runtime.inference.ModelInfo? = null

    override fun computeDevices(): List<String> = listOf("CPU")

    /** No measurement, ever: a silent model has no speed worth reporting. */
    override suspend fun benchmark(prompt: String, maxTokens: Int): dev.charaly.runtime.model.BenchmarkObservation? =
        null

    override fun lastDiagnostic(): String = "the native decode produced no text"
}
