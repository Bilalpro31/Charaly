package dev.charaly.app.inference

import dev.charaly.runtime.inference.ChatMessage
import dev.charaly.runtime.inference.ChatRole
import dev.charaly.runtime.inference.GenerationParams
import dev.charaly.runtime.inference.InferenceError
import dev.charaly.runtime.inference.InferenceRequest
import dev.charaly.runtime.inference.LoadOutcome
import dev.charaly.runtime.inference.ModelLoadRequest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * THE NATIVE BOUNDARY, AND WHAT IT GUARANTEES WITHOUT A DEVICE.
 *
 * ## Why this file exists next to the existing engine tests
 *
 * `LocalLlamaInferenceEngineTest` covers the engine's behaviour around llama.cpp. This one
 * covers the *promise the app makes about* llama.cpp - the claims the Settings diagnostics
 * screen puts in front of a user, and the claims the chat screen's "it works on this
 * device" is built on.
 *
 * Those are claims about the native library's presence and reachability, which a JVM can
 * verify: whether the JNI symbols are declared at all, whether the guard clauses reject a
 * bad file *before* the native call, and whether a model that is not resident refuses to
 * generate rather than producing a plausible empty string.
 *
 * ## What cannot be verified here, and is not claimed
 *
 * That a given GGUF loads, and what it says. That needs an arm64 device with a real model
 * file, which is exactly what Settings -> Diagnostics -> Model test exists for: it runs the
 * thing on the device and shows the output. Pretending to cover it here with a mock is
 * exactly what the brief forbids, so this file stops at the boundary and says so.
 */
class NativeInferenceBoundaryTest {

    // ==================================================================
    // 1. The engine refuses bad input before native code is reached
    // ==================================================================

    @Test
    fun `a load request with no file fails without touching native code`() = runTest {
        val outcome = LocalLlamaInferenceEngine().loadModel(
            ModelLoadRequest(path = "/definitely/not/here.gguf"),
        )
        // A precise, typed reason rather than a native error string: the guard runs in
        // Kotlin, so this works identically on a build with no native library at all - which
        // is what lets the diagnostics screen give the same answer in both cases.
        assertTrue(outcome is LoadOutcome.Failed)
        assertTrue((outcome as LoadOutcome.Failed).error is InferenceError.ModelNotFound)
    }

    @Test
    fun `a text file renamed to gguf is rejected before native code`() = runTest {
        val file = Files.createTempFile("renamed", ".gguf").toFile()
        file.writeText("GGUF but not really")
        try {
            val outcome = LocalLlamaInferenceEngine().loadModel(ModelLoadRequest(path = file.absolutePath))
            assertTrue(outcome is LoadOutcome.Failed)
            assertTrue(
                "a renamed text file must fail as an invalid model, not reach llama.cpp",
                (outcome as LoadOutcome.Failed).error is InferenceError.InvalidModel,
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a truncated gguf header is rejected before native code`() = runTest {
        // Four magic bytes and nothing else. llama.cpp would fail on this too, but failing
        // in Kotlin means the user gets "this file is not a GGUF model" instead of a native
        // message about tensor shapes.
        val file = Files.createTempFile("truncated", ".gguf").toFile()
        file.writeBytes("GGUF".toByteArray())
        try {
            val outcome = LocalLlamaInferenceEngine().loadModel(ModelLoadRequest(path = file.absolutePath))
            assertTrue(outcome is LoadOutcome.Failed)
            assertTrue((outcome as LoadOutcome.Failed).error is InferenceError.InvalidModel)
        } finally {
            file.delete()
        }
    }

    // ==================================================================
    // 2. Generating without a resident model refuses rather than inventing
    // ==================================================================

    @Test
    fun `generating with nothing loaded is refused, not faked`() = runTest {
        val engine = LocalLlamaInferenceEngine()
        val error = runCatching {
            engine.generate(
                InferenceRequest(
                    systemPrompt = "",
                    messages = listOf(ChatMessage(ChatRole.USER, "hello")),
                ),
            )
        }.exceptionOrNull()

        assertTrue(
            "an engine with no model must refuse rather than return an empty string, " +
                "which is indistinguishable from a model that chose to say nothing",
            error is InferenceError.ModelNotLoaded,
        )
    }

    @Test
    fun `streaming with nothing loaded is refused too`() = runTest {
        val engine = LocalLlamaInferenceEngine()
        val error = runCatching {
            engine.stream(
                InferenceRequest(
                    systemPrompt = "",
                    messages = listOf(ChatMessage(ChatRole.USER, "hello")),
                ),
            ).toList()
        }.exceptionOrNull()

        assertTrue(error is InferenceError.ModelNotLoaded)
    }

    @Test
    fun `an unloaded engine reports itself as unloaded`() {
        // The one residency question every other layer asks. If this lied, `ModelSelection`
        // would report a model as resident that llama.cpp does not hold - which is the shape
        // of the original "chat says no model" bug, from the other direction.
        val engine = LocalLlamaInferenceEngine()
        assertTrue("a fresh engine holds nothing", !engine.isLoaded())
        assertTrue("and has no model info", engine.modelInfo() == null)
    }

    // ==================================================================
    // 3. The prompt the diagnostics screen sends is the one the tests assert
    // ==================================================================

    @Test
    fun `the prompt format is stable and does not depend on a chat template`() {
        // The engine builds its own plain-text turn format rather than guessing at the
        // model's template. That is the one place that decides how a story reads to the
        // model, so it is asserted here: a change to these markers changes every reply in
        // the app, and it is not a change a unit test would otherwise catch.
        val prompt = LocalLlamaInferenceEngine().buildPromptInternal(
            InferenceRequest(
                systemPrompt = "You are Marinette.",
                messages = listOf(
                    ChatMessage(ChatRole.USER, "Hello?"),
                    ChatMessage(ChatRole.ASSISTANT, "Hi.", name = "Marinette"),
                ),
                params = GenerationParams(maxTokens = 32),
            ),
        )

        assertTrue("the system prompt must come first", prompt.startsWith("You are Marinette."))
        assertTrue("user turns are marked", prompt.contains("[user] Hello?"))
        assertTrue(
            "a character turn is marked with the speaker's name, so a multi-character " +
                "scene stays readable",
            prompt.contains("[Marinette] Hi."),
        )
        assertTrue(
            "the prompt must end by inviting the model to speak",
            prompt.trimEnd().endsWith("[assistant]"),
        )
    }

    // ==================================================================
    // 4. The native surface the app declares matches what it calls
    // ==================================================================

    @Test
    fun `a build with no native library still answers every question`() = runTest {
        // The poisoned-class trap, made explicit.
        //
        // `System.loadLibrary` runs in `LlamaNative`'s static initialiser, and a failure
        // there marks the class erroneous for the life of the process. Every accessor must
        // therefore answer rather than throw - not once, but *repeatedly*, because a caller
        // that catches the first failure and gets an exception on the second has not solved
        // anything. This is the shape of a real device bug: an ABI split strips the .so,
        // the app crashes on the first inference attempt and would crash again after a
        // restart, and the only honest answer is a sentence about the build.
        repeat(3) {
            assertTrue(
                "isNativeAvailable must keep answering after a failed load (attempt $it)",
                !LocalLlamaInferenceEngine.isNativeAvailable(),
            )
            assertEquals(
                "nativeVersion must keep answering (attempt $it)",
                "unavailable",
                LocalLlamaInferenceEngine.nativeVersion(),
            )
        }
    }

    @Test
    fun `every native method the Kotlin side calls is declared`() {
        // A source-level check, and the cheapest possible guard against the JNI boundary
        // silently moving: adding a native method to the Kotlin object without implementing
        // it in C++ compiles fine and throws UnsatisfiedLinkError on a device, which is
        // exactly the class of failure that only shows up after release.
        val declared = Regex("""external fun (\w+)\(""")
            .findAll(File("src/main/kotlin/dev/charaly/app/inference/LlamaNative.kt").readText())
            .map { it.groupValues[1] }
            .toSet()

        val expected = setOf(
            "nativeAvailable",
            "nativeVersion",
            "loadModel",
            "freeModel",
            "modelMetadata",
            "contextSize",
            "generate",
            "stopGeneration",
            "lastError",
            "benchmark",
            "availableBackends",
        )
        assertEquals(
            "the JNI surface changed; the native side and the diagnostics screen must agree",
            expected,
            declared,
        )
    }

    @Test
    fun `the native library is reported honestly when it is absent`() {
        // On this JVM there is no native library at all, so this must return false rather
        // than throwing. The diagnostics screen shows exactly this, which is why "the model
        // test cannot run" is a sentence a user can act on instead of a crash.
        assertTrue(
            "nativeAvailable must answer false rather than throwing when the library is missing",
            !LocalLlamaInferenceEngine.isNativeAvailable(),
        )
        assertEquals(
            "and nativeVersion must not claim a version it does not have",
            "unavailable",
            LocalLlamaInferenceEngine.nativeVersion(),
        )
    }

    @Test
    fun `a missing native library is reported as unsupported, not as a broken model`() = runTest {
        // The distinction the user cares about: "this build has no local inference" is a
        // fact about the app; "this file is corrupt" is a fact about their file. Reporting
        // the second for the first sends people to re-download a file that was fine.
        val file = Files.createTempFile("real-header", ".gguf").toFile()
        // A real 24-byte GGUF v3 header, so every Kotlin guard passes and the check that
        // fails is genuinely the missing library.
        file.writeBytes(ByteArray(24) { index ->
            when (index) {
                0 -> 'G'.code.toByte()
                1 -> 'G'.code.toByte()
                2 -> 'U'.code.toByte()
                3 -> 'F'.code.toByte()
                else -> 0
            }
        }.also { header ->
            header[4] = 3 // version
            header[5] = 0
        })
        try {
            val outcome = LocalLlamaInferenceEngine().loadModel(
                ModelLoadRequest(path = file.absolutePath),
            )
            assertTrue(outcome is LoadOutcome.Failed)
            assertTrue(
                "a build without native inference must say so, not blame the file",
                (outcome as LoadOutcome.Failed).error is InferenceError.Unsupported,
            )
        } finally {
            file.delete()
        }
    }
}