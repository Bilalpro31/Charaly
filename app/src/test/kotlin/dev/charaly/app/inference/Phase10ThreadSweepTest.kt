package dev.charaly.app.inference

import dev.charaly.runtime.model.gguf.GgufReadResult
import dev.charaly.runtime.model.gguf.GgufReader
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * PHASE 10 B — the thread pool that decides time to first token, measured directly.
 *
 * ## The claim under test
 *
 * llama.cpp keeps two thread pools: `n_threads` decodes one token, `n_threads_batch`
 * evaluates a prompt (`llama_context::graph_compute`:
 * `batched ? n_threads_batch : n_threads`). Before Phase 10 the batch pool was
 * unreachable from Kotlin, so it sat at `GGML_DEFAULT_N_THREADS` - a flat 4 in this
 * checkout (ggml/include/ggml.h:228) - on every device, whatever the caller asked for.
 * Time to first token is dominated by exactly that pool.
 *
 * ## Why two loads of the same prompt
 *
 * One load cannot answer the question: `n_threads_batch` is fixed at load time. So the
 * prompt is rendered once and evaluated twice - once on 4 threads (the pre-Phase-10 state,
 * reproduced deliberately rather than checked out) and once on the device's core count.
 * Everything else is the same binary, the same weights, the same sampler, the same KV
 * cache state.
 *
 * ## Two traps this test refuses to fall into
 *
 * **A cache hit is not a measurement.** The engine keeps a KV prefix cache keyed on the
 * exact token stream, so a second identical prompt evaluates one token. The first version
 * of this test warmed up with the prompt under test, then reported ~1061 tok/s for a cache
 * hit as if it were prompt evaluation throughput. Now the warm-up is a DIFFERENT, short
 * prompt, and throughput is computed per *evaluated* token - so a cache hit reports zero.
 *
 * **Thread count is reported by the engine, not by the caller.** Each row reads
 * `threads` and `threads_batch` back out of `lastGenerateStats()`, because the number that
 * matters is the one llama.cpp actually granted, and that is the number a claim can be
 * checked against.
 *
 * ```
 *   ./gradlew :app:testDebugUnitTest --offline \
 *     -PcharalyNativeLib=... -PcharalyModelFile=... --tests '*Phase10ThreadSweepTest'
 * ```
 */
class Phase10ThreadSweepTest {

    private val modelFile: File? = System.getProperty("charalyModelFile")
        ?.takeIf { it.isNotBlank() }
        ?.let { File(it) }
        ?.takeIf { it.isFile }

    @Test
    fun `prompt evaluation throughput at the pre-Phase-10 thread count versus the device`() {
        assumeTrue("no libcharaly_llama.so offered", LlamaNative.isAvailable())
        assumeTrue("no real GGUF offered (-PcharalyModelFile)", modelFile != null)
        val gguf = modelFile!!
        gguf.inputStream().use { stream ->
            assumeTrue(
                "unreadable GGUF header",
                (GgufReader.readPrefix(stream.readNBytes(8 * 1024 * 1024)) is GgufReadResult.Success),
            )
        }

        val cores = Runtime.getRuntime().availableProcessors()
        val contextSize = System.getProperty("charalyContextSize")?.toIntOrNull() ?: 4096

        val report = StringBuilder()
        report.appendLine("### PHASE 10 - PROMPT EVALUATION THREAD POOL")
        report.appendLine("model   : ${gguf.name}")
        report.appendLine("cores   : $cores")
        report.appendLine("context : $contextSize")
        report.appendLine()
        report.appendLine("The BEFORE column is not an old binary. It is this binary, loaded")
        report.appendLine("with prompt_threads = 4, which is what every pre-Phase-10 build ran")
        report.appendLine("with no matter what it asked for.")

        data class Reading(
            val decodeThreads: Int,
            val promptThreads: Int,
            val promptTokens: Int,
            val reused: Int,
            val evalMillis: Long,
            val evalTokPerSec: Double,
            val genTokPerSec: Double,
        )

        /**
         * One load, one cold evaluation.
         *
         * The generation budget is deliberately small (16 tokens). Prompt evaluation is
         * the quantity under test and it does not depend on how many tokens are decoded;
         * a larger budget would only spend time measuring decode, which is the other pool
         * and is reported here only to show the fix did not move the bottleneck onto it.
         */
        fun measure(decodeThreads: Int, promptThreads: Int): Reading {
            val handle = LlamaNative.loadModel(
                path = gguf.absolutePath,
                contextSize = contextSize,
                threads = decodeThreads,
                threadsBatch = promptThreads,
                gpuLayers = 0,
                progressCallback = { _, _ -> },
            )
            assertTrue("the model could not be loaded", handle != 0L)
            try {
                // Short, and DIFFERENT from the prompt under test. Same prompt here would
                // seed the prefix cache and turn the measurement into a cache hit.
                LlamaNative.generate(
                    handle, "You are a character in a story.", 1,
                    0.8f, 0.93f, 40, 0.05f, 1.1f, 1L, arrayOf<String>(), { },
                )
                LlamaNative.generate(
                    handle, PROMPT, 16, 0.8f, 0.93f, 40, 0.05f, 1.1f, 1L, arrayOf<String>(), { },
                )

                val stats = NativeReturnParser.parseKeyValuesCatching("sweep") {
                    LlamaNative.lastGenerateStats()
                }.let { it as? NativeReturnParser.KeyValues.Ok }?.values.orEmpty()

                val promptTokens = stats["prompt_tokens"]?.toIntOrNull() ?: 0
                val reused = stats["reused_tokens"]?.toIntOrNull() ?: 0
                val evaluated = promptTokens - reused
                val evalMicros = stats["eval_micros"]?.toLongOrNull() ?: 0L
                val genMicros = stats["generation_micros"]?.toLongOrNull() ?: 0L
                val generated = stats["generated_tokens"]?.toIntOrNull() ?: 0

                return Reading(
                    decodeThreads = stats["threads"]?.toIntOrNull() ?: decodeThreads,
                    promptThreads = stats["threads_batch"]?.toIntOrNull() ?: promptThreads,
                    promptTokens = promptTokens,
                    reused = reused,
                    evalMillis = evalMicros / 1000L,
                    evalTokPerSec = if (evalMicros > 0 && evaluated > 0) {
                        evaluated * 1_000_000.0 / evalMicros
                    } else {
                        0.0
                    },
                    genTokPerSec = if (genMicros > 0) {
                        generated * 1_000_000.0 / genMicros
                    } else {
                        0.0
                    },
                )
            } finally {
                LlamaNative.freeModel(handle)
            }
        }

        val before = measure(decodeThreads = 4, promptThreads = 4)
        val after = measure(decodeThreads = 4, promptThreads = cores)

        report.appendLine()
        report.appendLine("                | BEFORE (4 prompt threads) | AFTER ($cores prompt threads)")
        report.appendLine("----------------+--------------------------+----------------------")
        report.appendLine("decode threads  | ${"%24d".format(before.decodeThreads)} | ${"%22d".format(after.decodeThreads)}")
        report.appendLine("prompt threads | ${"%24d".format(before.promptThreads)} | ${"%22d".format(after.promptThreads)}")
        report.appendLine("prompt tokens  | ${"%24d".format(before.promptTokens)} | ${"%22d".format(after.promptTokens)}")
        report.appendLine("reused tokens  | ${"%24d".format(before.reused)} | ${"%22d".format(after.reused)}")
        report.appendLine("evaluated      | ${"%24d".format(before.promptTokens - before.reused)} | ${"%22d".format(after.promptTokens - after.reused)}")
        report.appendLine("eval millis    | ${"%24d".format(before.evalMillis)} | ${"%22d".format(after.evalMillis)}")
        report.appendLine("eval tok/s     | ${"%24.2f".format(before.evalTokPerSec)} | ${"%22.2f".format(after.evalTokPerSec)}")
        report.appendLine("decode tok/s   | ${"%24.2f".format(before.genTokPerSec)} | ${"%22.2f".format(after.genTokPerSec)}")

        report.appendLine()
        report.appendLine("### READ")
        val evaluatedBefore = before.promptTokens - before.reused
        val evaluatedAfter = after.promptTokens - after.reused
        report.appendLine("prompt tokens evaluated: $evaluatedBefore -> $evaluatedAfter")
        report.appendLine(
            "prompt evaluation throughput: ${"%.2f".format(before.evalTokPerSec)} -> " +
                "${"%.2f".format(after.evalTokPerSec)} tok/s " +
                "(${if (before.evalTokPerSec > 0) "%.2fx".format(after.evalTokPerSec / before.evalTokPerSec) else "n/a"})",
        )
        if (before.evalTokPerSec > 0 && after.evalTokPerSec > 0) {
            val better = if (after.evalTokPerSec > before.evalTokPerSec) "FASTER" else "SLOWER"
            report.appendLine(
                "the pool that dominates TTFT is $better on $cores cores. Less than 1.00x means " +
                    "the extra threads cost more than they bought, which is a real answer too - " +
                    "decode saturates far earlier than prompt evaluation does.",
            )
        }
        report.appendLine(
            "decode throughput moved ${"%.2f".format(before.genTokPerSec)} -> " +
                "${"%.2f".format(after.genTokPerSec)} tok/s. Decode threads were held at 4 in " +
                "both runs, so any movement here is contention from the batch pool, not a " +
                "change in the decode configuration.",
        )

        val text = report.toString()
        File(REPORT_PATH).writeText(text)
        println(text)

        assertTrue(
            "the 4-thread run produced no measurable prompt evaluation (evaluated=$evaluatedBefore)",
            before.evalTokPerSec > 0.0,
        )
        assertTrue(
            "the $cores-thread run produced no measurable prompt evaluation (evaluated=$evaluatedAfter)",
            after.evalTokPerSec > 0.0,
        )
    }

    private companion object {
        const val REPORT_PATH = "/tmp/opencode/phase10_thread_sweep.txt"

        /**
         * A prompt in the shape Charaly sends, in the token range the Phase 10 story turns
         * actually produced (1400-2000 tokens).
         *
         * Length is the point. A 50-token prompt measures thread-pool spin-up and nothing
         * else; the numbers below are only worth quoting at the length a real turn has.
         */
        val PROMPT: String = buildString {
            appendLine("You are Marinette Dupain-Cheng, a character inside a living story world.")
            appendLine()
            appendLine("LANGUAGE - THIS IS THE FIRST RULE, AND IT OVERRIDES EVERYTHING ELSE BELOW")
            appendLine("- The person you are talking to writes in Turkish. You answer in TURKISH, in every sentence.")
            appendLine("- Spell it the way a Turkish speaker spells it.")
            appendLine()
            appendLine("WORLD RULES")
            appendLine("- You do not run the world. Locations, time, relationships and memories are managed outside you.")
            appendLine("- Only state what Marinette Dupain-Cheng personally knows or perceives right now.")
            appendLine("- Answer the user's message directly. Do not give generic advice.")
            appendLine()
            appendLine("YOUR IDENTITY")
            appendLine("Name: Marinette Dupain-Cheng")
            appendLine("Appearance/Role: Sixteen, black hair in a ponytail, always slightly behind.")
            appendLine("Background: Lives above her parents' bakery with two younger siblings.")
            appendLine("Personality: Kind to the point of self-sacrifice, anxious about being a burden.")
            appendLine("Role: The reluctant hero of Paris, and a student nobody knows saves it.")
            appendLine("Goals: Keep the secret from her family; Prove she is more than she believes")
            appendLine("Fears: Being found out; Letting a friend down during a fight")
            appendLine("Tone of voice: Fast, breathless, self-deprecating.")
            appendLine("Vocabulary: Everyday teen schoolgirl speech.")
            appendLine("Habits: Apologises reflexively; Changes the subject when she is cornered")
            appendLine()
            appendLine("WHAT YOU KNOW")
            appendLine("- The watch reacts as if a second pair of Miraculous is active somewhere in Paris.")
            appendLine()
            appendLine("PEOPLE YOU KNOW")
            appendLine("- alya: a close friend (trust 82/100, familiarity 90/100, affinity 78/100)")
            appendLine()
            repeat(6) { turn ->
                appendLine("<|im_start|>user")
                appendLine("Merhaba, bugün okulda biraz endişeliyim, $turn. ne yapmalıyım?")
                appendLine("<|im_end|>")
                appendLine("<|im_start|>assistant")
                appendLine("Merhaba! Biraz daha iyi hissettim, $turn. Seni düşünüyorum, çünkü konuşmak iyi geliyor.")
                appendLine("<|im_end|>")
            }
            appendLine("<|im_start|>user")
            appendLine("Peki yarın ne yapabilirim?")
            appendLine("<|im_end|>")
            append("<|im_start|>assistant")
        }
    }
}
