package dev.charaly.app.storyloop

import dev.charaly.app.inference.LlamaNative
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * PHASE 8 isolation test: can THIS model write coherent Turkish at all, with NO Charaly
 * prompt around it?
 *
 * This is the control for every quality question. If the bare model cannot hold a coherent
 * Turkish sentence here, then no prompt in ContextBuilder can be credited with the fix, and
 * the honest report is "model capacity", not "prompt bug".
 *
 * Four fixed prompts, pure llama.cpp chat template, one turn each, no runtime involvement.
 */
class BareTurkishCapabilityTest {

    private val modelFile: File? = System.getProperty("charalyModelFile")
        ?.takeIf { it.isNotBlank() }
        ?.let { File(it) }
        ?.takeIf { it.isFile }

    @Test
    fun `bare model Turkish without any Charaly prompt`() {
        assumeTrue("no native library", LlamaNative.isAvailable())
        assumeTrue("no model", modelFile != null)
        val gguf = modelFile!!

        val handle = LlamaNative.loadModel(
            path = gguf.absolutePath,
            contextSize = 2048,
            threads = 0,
            threadsBatch = 0,
            gpuLayers = 0,
            progressCallback = { _, _ -> },
        )
        assumeTrue("model failed to load", handle != 0L)

        val out = StringBuilder()
        out.appendLine("MODEL: ${gguf.name}")
        out.appendLine("No Charaly prompt. Pure llama.cpp chat template + one Turkish user turn.")

        // The four brief scenarios, asked plainly, with nothing else in the context.
        // Tunable so the isolation measurement can be kept short: each turn costs a full
        // prompt evaluation plus 120 generated tokens, which on a host container is minutes.
        val turnLimit = System.getProperty("charalyTurns")?.toIntOrNull() ?: 4
        val bare = listOf(
            "Merhaba! Bugün okulda biraz endişeliyim. Sen nasılsın?",
            "Bugün arkadaşlarımla konuşmaya çekindim. Sence ne yapmalıyım?",
            "Sen olsaydın bu durumda ne yapardın?",
            "Neden böyle düşünüyorsun?",
        ).take(turnLimit)

        bare.forEachIndexed { index, prompt ->
            val flat = arrayOf("user", prompt)
            val templated = LlamaNative.applyChatTemplate(handle, flat)
            val reply = LlamaNative.generate(
                handle = handle,
                prompt = templated,
                maxTokens = 120,
                temperature = 0.7f,
                topP = 0.95f,
                topK = 40,
                minP = 0.05f,
                repeatPenalty = 1.1f,
                seed = -1L,
                stopSequences = arrayOf(),
                tokenCallback = { },
            )
            out.appendLine()
            out.appendLine("=== BARE TURN ${index + 1} ===")
            out.appendLine("USER: $prompt")
            out.appendLine("REPLY: $reply")
            out.appendLine("DIACRITICS=${reply.count { it.isLetter() && it.code in 0x00C0..0x024F }} LEN=${reply.length}")
        }

        LlamaNative.freeModel(handle)
        val text = out.toString()
        File("/tmp/opencode/phase8_bare_turkish.txt").writeText(text)
        println(text)
    }
}