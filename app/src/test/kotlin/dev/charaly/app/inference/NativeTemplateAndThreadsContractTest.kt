package dev.charaly.app.inference

import dev.charaly.runtime.model.gguf.GgufReadResult
import dev.charaly.runtime.model.gguf.GgufReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * PHASE 10 — the two native contracts the language and latency work depend on.
 *
 * ## 1. The model's own chat template
 *
 * `llama_chat_apply_template(nullptr, ...)` does NOT mean "use the model's template". In the
 * vendored llama.cpp it substitutes the literal `"chatml"` and resolves it by NAME, so the
 * family-detection heuristics never run and `tokenizer.chat_template` - which the GGUF
 * carries and which the Kotlin side reads and reports - was never used to render a prompt.
 * Every templated model was formatted as ChatML, right or wrong.
 *
 * A Qwen model cannot tell the difference, which is exactly why this is asserted by
 * *absence of the fallback diagnostic* rather than by comparing text: the rendered output
 * is nearly identical either way, and a test that compared it would be asserting nothing.
 *
 * ## 2. The two thread pools
 *
 * Prompt evaluation and token decoding run on different llama.cpp thread pools with
 * different scaling behaviour, and before Phase 10 the prompt one was pinned at
 * `GGML_DEFAULT_N_THREADS` = 4 with no Kotlin-side way to reach it. Time to first token is
 * dominated by the prompt pool, so this is the contract that decides how long a player
 * waits for a word.
 *
 * Both tests skip without a native library and a real GGUF - a green tick must never stand
 * in for a run that did not happen.
 */
class NativeTemplateAndThreadsContractTest {

    private val modelFile: File? = System.getProperty("charalyModelFile")
        ?.takeIf { it.isNotBlank() }
        ?.let { File(it) }
        ?.takeIf { it.isFile }

    @Test
    fun `the model's own chat template is the one that renders the prompt`() {
        assumeTrue("no libcharaly_llama.so offered", LlamaNative.isAvailable())
        assumeTrue("no real GGUF offered (-PcharalyModelFile)", modelFile != null)
        val gguf = modelFile!!
        gguf.inputStream().use { stream ->
            assumeTrue(
                "unreadable GGUF header",
                (GgufReader.readPrefix(stream.readNBytes(8 * 1024 * 1024)) is GgufReadResult.Success),
            )
        }

        val handle = LlamaNative.loadModel(
            path = gguf.absolutePath,
            contextSize = 2048,
            threads = 0,
            threadsBatch = 0,
            gpuLayers = 0,
            progressCallback = { _, _ -> },
        )
        assumeTrue("the model could not be loaded", handle != 0L)
        try {
            val rendered = LlamaNative.applyChatTemplate(
                handle,
                arrayOf("system", "You are Marinette. Reply in Turkish.", "user", "Merhaba, nasılsın?"),
            )
            assertTrue("the template rendered nothing", rendered.isNotBlank())
            assertTrue(
                "a ChatML-family template must open the system turn with <|im_start|>system: " +
                    rendered.take(40),
                rendered.startsWith("<|im_start|>system"),
            )
            assertTrue(
                "the assistant's turn must be open, ready for the model to continue",
                rendered.trimEnd().endsWith("<|im_start|>assistant"),
            )
            assertTrue(
                "the system content must survive the template",
                rendered.contains("You are Marinette. Reply in Turkish."),
            )
            assertTrue(
                "the user turn must keep its own role",
                rendered.contains("<|im_start|>user\nMerhaba, nasılsın?"),
            )

            // The decisive assertion: the built-in ChatML fallback was NOT taken. When it is,
            // nativeDiagnostic says so, and that sentence is the difference between "the
            // model's own template" and "a hardcoded one that happens to match".
            val diagnostic = LlamaNative.lastError()
            assertFalse(
                "the model's own template was not applied, so every templated model is being " +
                    "formatted as ChatML: $diagnostic",
                diagnostic.lowercase(Locale.ROOT).contains("chat template"),
            )
        } finally {
            LlamaNative.freeModel(handle)
        }
    }

    @Test
    fun `the prompt thread pool is reported separately from the decode pool`() {
        assumeTrue("no libcharaly_llama.so offered", LlamaNative.isAvailable())
        assumeTrue("no real GGUF offered (-PcharalyModelFile)", modelFile != null)
        val gguf = modelFile!!

        val handle = LlamaNative.loadModel(
            path = gguf.absolutePath,
            contextSize = 2048,
            threads = 4,
            threadsBatch = 6,
            gpuLayers = 0,
            progressCallback = { _, _ -> },
        )
        assumeTrue("the model could not be loaded", handle != 0L)
        try {
            // One generation, so `lastGenerateStats` describes THIS configuration rather
            // than whatever the last caller left behind.
            val text = LlamaNative.generate(
                handle, "Merhaba, nasılsın?", 8, 0.8f, 0.93f, 40, 0.05f, 1.1f, 1L,
                arrayOf<String>(), { },
            )
            assertTrue("the generation produced nothing", text.isNotBlank())

            val stats = NativeReturnParser.parseKeyValuesCatching("threads") {
                LlamaNative.lastGenerateStats()
            }.let { it as? NativeReturnParser.KeyValues.Ok }?.values.orEmpty()

            assertEquals(
                "the decode pool must be reported as asked for",
                4,
                stats["threads"]?.toIntOrNull(),
            )
            assertEquals(
                "the prompt pool must be reported as asked for. Before Phase 10 this key " +
                    "did not exist and the pool was pinned at llama.cpp's default of 4.",
                6,
                stats["threads_batch"]?.toIntOrNull(),
            )
        } finally {
            LlamaNative.freeModel(handle)
        }
    }

    @Test
    fun `the engine's batch thread configuration is plumbed through to the native call`() {
        // No model needed: this is about the Kotlin contract, and a test that needs a 2.4 GB
        // file to prove that a constructor argument is stored is a test that cannot run.
        val engine = LocalLlamaInferenceEngine(
            LocalLlamaInferenceEngine.EngineConfig(threads = 4, threadsBatch = 6),
        )
        assertEquals(4, engine.configThreads())
        assertEquals(
            "the prompt pool must be readable, because it is the number that decides TTFT",
            6,
            engine.configThreadsBatch(),
        )
        assertEquals(
            "an unconfigured engine reports 0, which the native side reads as " +
                "'the device's core count, capped' - never as a silent 4",
            0,
            LocalLlamaInferenceEngine().configThreadsBatch(),
        )
    }

    @Test
    fun `an explicit prompt thread count survives a load`() {
        assumeTrue("no libcharaly_llama.so offered", LlamaNative.isAvailable())
        assumeTrue("no real GGUF offered (-PcharalyModelFile)", modelFile != null)
        val gguf = modelFile!!

        val engine = LocalLlamaInferenceEngine(
            LocalLlamaInferenceEngine.EngineConfig(contextSize = 2048, threadsBatch = 5),
        )
        val outcome = kotlinx.coroutines.runBlocking {
            engine.loadModel(
                dev.charaly.runtime.inference.ModelLoadRequest(
                    path = gguf.absolutePath,
                    displayName = metadataName(gguf),
                    installedModelId = "threads-contract",
                ),
            )
        }
        assertTrue("the model could not be loaded: $outcome", outcome is dev.charaly.runtime.inference.LoadOutcome.Loaded)
        try {
            val text = kotlinx.coroutines.runBlocking {
                engine.generate(
                    dev.charaly.runtime.inference.InferenceRequest(
                        systemPrompt = "You are Marinette.",
                        messages = listOf(
                            dev.charaly.runtime.inference.ChatMessage.user("Merhaba, nasılsın?"),
                        ),
                        params = dev.charaly.runtime.inference.GenerationParams(maxTokens = 8),
                    ),
                )
            }
            assertTrue("the generation produced nothing", text.text.isNotBlank())

            val stats = engine.lastTimings()
            assertTrue("no timings were reported", stats != null)
            assertEquals(
                "the batch pool must be the one the caller asked for",
                5,
                stats!!.threadsBatch,
            )
            assertTrue(
                "the batch pool must be more than llama.cpp's default of 4 for this to be " +
                    "a meaningful assertion",
                stats.threadsBatch > 4,
            )
        } finally {
            kotlinx.coroutines.runBlocking { engine.unloadModel() }
        }
    }

    private fun metadataName(gguf: File): String = try {
        gguf.inputStream().use { stream ->
            (GgufReader.readPrefix(stream.readNBytes(4 * 1024 * 1024)) as? GgufReadResult.Success)
                ?.metadata?.name ?: gguf.nameWithoutExtension
        }
    } catch (error: Throwable) {
        gguf.nameWithoutExtension
    }
}
