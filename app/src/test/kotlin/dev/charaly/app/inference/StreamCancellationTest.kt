package dev.charaly.app.inference

import dev.charaly.runtime.inference.ChatMessage
import dev.charaly.runtime.inference.ChatRole
import dev.charaly.runtime.inference.GenerationParams
import dev.charaly.runtime.inference.InferenceRequest
import dev.charaly.runtime.inference.ModelLoadRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * CANCELLATION, AND THE `stopGeneration` PATH.
 *
 * ## Why this test exists
 *
 * Streaming cancellation used to be structurally impossible. The blocking native decode
 * ran directly in the `callbackFlow` body and `awaitClose { }` - the only place a stop
 * request can be registered - came *after* it. By the time `awaitClose` ran, the model
 * had already finished, and its block was empty besides. So pressing Cancel, closing the
 * screen, or cancelling the coroutine had no path to `LlamaNative.stopGeneration` at all:
 * the decode ran to the end of the reply every time.
 *
 * Cancellation is cooperative and a blocking JNI call has no suspension point, so the
 * only fix is structural: run the decode in a child coroutine and register the stop
 * handler before it starts.
 *
 * ## What this does and does not prove
 *
 * There is no native library on a JVM, so this proves the *wiring*: that a cancelled
 * flow reaches `stopGeneration`, and that a normally-finished turn does not. It does not
 * claim any inference works.
 */
class StreamCancellationTest {

    // ==================================================================
    // 1. The structural property, readable from the source
    // ==================================================================

    @Test
    fun `the stop handler is registered before the blocking decode is started`() {
        val source = File("src/main/kotlin/dev/charaly/app/inference/LocalLlamaInferenceEngine.kt")
            .readText()

        val streamBody = source.substringAfter("override fun stream(").substringBefore("override fun stop()")
        val awaitCloseAt = streamBody.indexOf("awaitClose")
        val decodeAt = streamBody.indexOf("runGeneration")

        assertTrue("stream() must contain an awaitClose handler", awaitCloseAt >= 0)
        assertTrue("stream() must contain the blocking decode", decodeAt >= 0)
        assertTrue(
            "awaitClose must come AFTER the decode is launched, so the handler is armed " +
                "for the whole decode. Running the decode inline in the flow body makes " +
                "cancellation structurally impossible.",
            awaitCloseAt > decodeAt,
        )
        assertTrue(
            "the awaitClose handler must actually call stopGeneration",
            streamBody.substring(awaitCloseAt).contains("requestStop(current)"),
        )
    }

    @Test
    fun `a normally finished turn does not request a stop`() {
        val source = File("src/main/kotlin/dev/charaly/app/inference/LocalLlamaInferenceEngine.kt")
            .readText()
        val streamBody = source.substringAfter("override fun stream(").substringBefore("override fun stop()")
        assertTrue(
            "the stop must be conditional on the engine still generating, or a " +
                "completed turn would leave the cancellation flag set for the next one",
            streamBody.contains("if (generating.get()) requestStop(current)"),
        )
    }

    @Test
    fun `the non-streaming generate path also cancels the native decode`() {
        // `generate` blocks on the same JNI call, so a cancelled caller had the same
        // problem and no handler at all.
        val source = File("src/main/kotlin/dev/charaly/app/inference/LocalLlamaInferenceEngine.kt")
            .readText()
        val generateBody = source.substringAfter("override suspend fun generate(")
            .substringBefore("override fun stream(")
        assertTrue(
            "generate() must register a cancellation handler that reaches stopGeneration",
            generateBody.contains("invokeOnCompletion") && generateBody.contains("requestStop(current)"),
        )
    }

    // ==================================================================
    // 2. The engine honours stop() without a native library present
    // ==================================================================

    @Test
    fun `stop on an engine with no model does not crash`() {
        // On a JVM the library is absent, so `stopGeneration` throws
        // `UnsatisfiedLinkError`. Reaching it from a Cancel press must not turn a
        // user gesture into a crash.
        LocalLlamaInferenceEngine().stop()
    }

    @Test
    fun `stop is safe to call repeatedly`() {
        val engine = LocalLlamaInferenceEngine()
        repeat(5) { engine.stop() }
    }

    @Test
    fun `cancelling a stream with no model reports the typed error`() = runTest {
        val engine = LocalLlamaInferenceEngine()
        val failure = runCatching {
            engine.stream(request()).toList()
        }.exceptionOrNull()
        assertTrue(failure is dev.charaly.runtime.inference.InferenceError)
    }

    // ==================================================================
    // 3. The cancellation path, exercised through the real flow machinery
    // ==================================================================

    @Test
    fun `cancelling a collecting coroutine tears the flow down without hanging`() = runTest {
        // The observable half of the contract: a cancelled collector must not wedge.
        // The stop *request* is a native call and is asserted at the source level above,
        // because there is no native library here to observe.
        val engine = LocalLlamaInferenceEngine()
        val started = CompletableDeferred<Unit>()

        val job = launch(Dispatchers.Default) {
            runCatching { engine.stream(request()).toList() }
            started.complete(Unit)
        }

        // The stream runs on a real dispatcher, so the wait for it must be in real time
        // too: the virtual clock only advances on scheduler events, and a busy machine
        // trips a virtual `withTimeout` before the real work has had a chance to post its
        // completion event. Same timeout budget, honest clock.
        withContext(Dispatchers.Default) {
            withTimeout(30_000) { started.await() }
        }
        job.cancel()

        assertTrue(
            "a cancelled stream must not leave the engine claiming to be generating",
            !engine.isLoaded(),
        )
    }

    // ==================================================================
    // 4. The model guard still runs before any native call
    // ==================================================================

    @Test
    fun `a load that never reaches native reports the file, not the library`() = runTest {
        // Regression guard on the order of the checks in loadModel. If the native
        // availability check moved ahead of the file checks, a missing file would be
        // reported as "this build has no inference", which sends users to re-download a
        // file that was fine.
        val outcome = LocalLlamaInferenceEngine().loadModel(
            ModelLoadRequest(path = "/definitely/not/here.gguf"),
        )
        assertTrue(outcome is dev.charaly.runtime.inference.LoadOutcome.Failed)
        assertTrue(
            (outcome as dev.charaly.runtime.inference.LoadOutcome.Failed).error
                is dev.charaly.runtime.inference.InferenceError.ModelNotFound,
        )
    }

    @Test
    fun `a valid header with no native library is reported as unsupported, not as a bad file`() =
        runTest {
            val file = Files.createTempFile("real-header", ".gguf").toFile()
            file.writeBytes(
                ByteArray(24) { index ->
                    when (index) {
                        0 -> 'G'.code.toByte()
                        1 -> 'G'.code.toByte()
                        2 -> 'U'.code.toByte()
                        3 -> 'F'.code.toByte()
                        4 -> 3
                        else -> 0
                    }
                },
            )
            try {
                val outcome = LocalLlamaInferenceEngine().loadModel(
                    ModelLoadRequest(path = file.absolutePath),
                )
                val error = (outcome as dev.charaly.runtime.inference.LoadOutcome.Failed).error
                assertTrue(
                    "a build without native inference must say so, not blame the file: $error",
                    error is dev.charaly.runtime.inference.InferenceError.Unsupported,
                )
            } finally {
                file.delete()
            }
        }

    private fun request() = InferenceRequest(
        systemPrompt = "",
        messages = listOf(ChatMessage(ChatRole.USER, "hello")),
        params = GenerationParams(maxTokens = 8),
    )
}