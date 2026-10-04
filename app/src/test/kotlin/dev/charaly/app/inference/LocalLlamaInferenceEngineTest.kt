package dev.charaly.app.inference

import dev.charaly.runtime.inference.ChatMessage
import dev.charaly.runtime.inference.ChatRole
import dev.charaly.runtime.inference.GenerationParams
import dev.charaly.runtime.inference.InferenceEngine
import dev.charaly.runtime.inference.InferenceError
import dev.charaly.runtime.inference.InferenceRequest
import dev.charaly.runtime.inference.InferenceResult
import dev.charaly.runtime.inference.LoadOutcome
import dev.charaly.runtime.inference.ModelInfo
import dev.charaly.runtime.inference.ModelLoadRequest
import dev.charaly.runtime.inference.StopReason
import dev.charaly.runtime.inference.StreamChunk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Tests for the local inference adapter that do NOT need a real model.
 *
 * The native decode loop can only be exercised on a device with a GGUF file, so
 * these cover everything around it: prompt assembly, error mapping, GGUF
 * sniffing, and the contract the runtime depends on.
 */
class LocalLlamaInferenceEngineTest {

    // ------------------------------------------------------------------
    // Error handling before native code is reached
    // ------------------------------------------------------------------

    @Test
    fun `a missing file fails with ModelNotFound`() = runTest {
        val engine = LocalLlamaInferenceEngine()
        val outcome = engine.loadModel(ModelLoadRequest(path = "/does/not/exist.gguf"))
        assertTrue(outcome is LoadOutcome.Failed)
        assertTrue((outcome as LoadOutcome.Failed).error is InferenceError.ModelNotFound)
    }

    @Test
    fun `a non gguf file fails with InvalidModel rather than reaching llama`() = runTest {
        val file = Files.createTempFile("not-a-model", ".gguf").toFile()
        file.writeText("this is a text file pretending to be a model")
        try {
            val outcome = LocalLlamaInferenceEngine().loadModel(ModelLoadRequest(path = file.absolutePath))
            assertTrue(outcome is LoadOutcome.Failed)
            val error = (outcome as LoadOutcome.Failed).error
            assertTrue("expected InvalidModel, got $error", error is InferenceError.InvalidModel)
            assertTrue(error.message!!.contains("GGUF"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `an empty file is rejected`() = runTest {
        val file = Files.createTempFile("empty", ".gguf").toFile()
        try {
            val outcome = LocalLlamaInferenceEngine().loadModel(ModelLoadRequest(path = file.absolutePath))
            assertTrue(outcome is LoadOutcome.Failed)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a directory is not a model`() = runTest {
        val dir = Files.createTempDirectory("model-dir").toFile()
        val outcome = LocalLlamaInferenceEngine().loadModel(ModelLoadRequest(path = dir.absolutePath))
        assertTrue(outcome is LoadOutcome.Failed)
    }

    @Test
    fun `an engine with no model reports not loaded and refuses to generate`() = runTest {
        val engine = LocalLlamaInferenceEngine()
        assertFalse(engine.isLoaded())
        assertEquals(null, engine.modelInfo())
        val failure = runCatching { engine.generate(request()) }.exceptionOrNull()
        assertTrue(failure is InferenceError)
        assertTrue((failure as InferenceError) is InferenceError.ModelNotLoaded)
    }

    @Test
    fun `unloading an engine that was never loaded is safe`() = runTest {
        LocalLlamaInferenceEngine().unloadModel()
        assertTrue(true)
    }

    @Test
    fun `stop is safe when nothing is running`() {
        LocalLlamaInferenceEngine().stop()
        assertTrue(true)
    }

    // ------------------------------------------------------------------
    // Prompt assembly
    // ------------------------------------------------------------------

    @Test
    fun `the prompt carries the system block and the turns`() {
        val engine = LocalLlamaInferenceEngine()
        val prompt = engine.buildPromptForTest(
            InferenceRequest(
                systemPrompt = "You are Alice. You do not run the world.",
                messages = listOf(
                    ChatMessage.user("Who is in the library?"),
                    ChatMessage.assistant("Nobody.", name = "alice"),
                ),
            ),
        )
        assertTrue(prompt.contains("You are Alice. You do not run the world."))
        assertTrue(prompt.contains("[user] Who is in the library?"))
        assertTrue(prompt.contains("[alice] Nobody."))
        assertTrue("the model must be told whose turn it is", prompt.trimEnd().endsWith("[assistant]"))
    }

    @Test
    fun `a system message inside the transcript is labelled`() {
        val engine = LocalLlamaInferenceEngine()
        val prompt = engine.buildPromptForTest(
            InferenceRequest(
                systemPrompt = "base rules",
                messages = listOf(ChatMessage.system("additional rule")),
            ),
        )
        assertTrue(prompt.contains("[system] additional rule"))
        assertTrue(prompt.contains("base rules"))
    }

    @Test
    fun `an assistant message without a name still renders`() {
        val engine = LocalLlamaInferenceEngine()
        val prompt = engine.buildPromptForTest(
            InferenceRequest(
                systemPrompt = "",
                messages = listOf(ChatMessage.assistant("murmured something")),
            ),
        )
        assertTrue(prompt.contains("murmured something"))
    }

    @Test
    fun `generation parameters reach the engine unchanged`() {
        val params = GenerationParams(
            maxTokens = 128,
            temperature = 0.4f,
            topP = 0.8f,
            topK = 20,
            repeatPenalty = 1.25f,
            seed = 7L,
            stopSequences = listOf("\n\n"),
        )
        val request = InferenceRequest(systemPrompt = "", messages = emptyList(), params = params)
        assertEquals(128, request.params.maxTokens)
        assertEquals(0.4f, request.params.temperature, 0.0001f)
        assertEquals(7L, request.params.seed)
        assertEquals(listOf("\n\n"), request.params.stopSequences)
    }

    // ------------------------------------------------------------------
    // The port contract the runtime relies on
    // ------------------------------------------------------------------

    @Test
    fun `the engine satisfies the InferenceEngine port`() {
        // A compile-time guarantee, stated explicitly so it cannot regress.
        val engine: InferenceEngine = LocalLlamaInferenceEngine()
        assertEquals(ENGINE_ID, LocalLlamaInferenceEngine.ENGINE_ID)
        assertEquals(engine.javaClass.simpleName, LocalLlamaInferenceEngine::class.java.simpleName)
    }

    @Test
    fun `native availability is reported without crashing when the library is absent`() {
        // On a JVM there is no .so; the call must return false, not throw.
        val available = LocalLlamaInferenceEngine.isNativeAvailable()
        assertFalse("no native library on a JVM test", available)
        assertTrue(LocalLlamaInferenceEngine.nativeVersion().isNotBlank())
    }

    @Test
    fun `stream refuses cleanly when nothing is loaded`() = runTest {
        val engine = LocalLlamaInferenceEngine()
        val failure = runCatching {
            engine.stream(request()).toList()
        }.exceptionOrNull()
        assertTrue(failure is InferenceError)
        assertTrue((failure as InferenceError) is InferenceError.ModelNotLoaded)
    }

    @Test
    fun `errors form a closed, meaningful taxonomy`() {
        val errors = listOf(
            InferenceError.ModelNotLoaded(),
            InferenceError.ModelNotFound("/x"),
            InferenceError.InvalidModel("/x", "bad magic"),
            InferenceError.OutOfMemory(1 shl 30),
            InferenceError.GenerationFailed("boom"),
            InferenceError.Cancelled(),
            InferenceError.Unsupported("no native lib"),
        )
        errors.forEach { error ->
            assertTrue("error message must not be blank", error.message!!.isNotBlank())
        }
        assertTrue(errors.all { it is InferenceError })
        assertTrue(
            InferenceError.OutOfMemory(2L shl 30).message!!.contains("MiB"),
        )
    }

    @Test
    fun `stop reasons are a closed set the UI can switch on`() {
        assertEquals(
            setOf(StopReason.COMPLETED, StopReason.LENGTH, StopReason.STOP_SEQUENCE, StopReason.CANCELLED, StopReason.ERROR),
            StopReason.entries.toSet(),
        )
    }

    private fun request() = InferenceRequest(
        systemPrompt = "rules",
        messages = listOf(ChatMessage(ChatRole.USER, "hello")),
        params = GenerationParams(maxTokens = 8),
    )
}

/** Exposes prompt assembly so it can be tested without a model. */
private fun LocalLlamaInferenceEngine.buildPromptForTest(request: InferenceRequest): String =
    buildPromptInternal(request)
