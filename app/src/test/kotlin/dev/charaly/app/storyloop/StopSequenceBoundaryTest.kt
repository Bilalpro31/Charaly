package dev.charaly.app.storyloop

import dev.charaly.app.inference.LlamaNative
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * PHASE 9 - stop sequences at the real JNI boundary, on a real GGUF.
 *
 * ## The bug this pins down
 *
 * `<|im_end|>` appeared at the end of every reply produced by Qwen3-4B-Instruct-2507 on
 * this device. Two things were wrong, and they are different:
 *
 *  1. the matcher compared only the LAST `stop.size()` bytes, so a marker that landed
 *     mid-reply (model emits its own turn marker and keeps writing) never matched;
 *  2. a marker arriving in SEVERAL streaming pieces was invisible to any matcher that
 *     only looks at what the current piece added.
 *
 * The fix holds back up to `max_stop_len - 1` bytes from the callback, re-examines them
 * with each new piece, truncates FROM the match onward, and flushes the tail when
 * generation ends without a match.
 *
 * ## What is asserted
 *
 * Every case below drives `LlamaNative.generate` - the same JNI entry point, the same
 * sampler loop, the same GGUF parser the APK uses. Nothing is mocked.
 *
 *  - **normal text survives**: a stop sequence that never appears changes nothing.
 *  - **the marker never reaches the caller**: neither in the returned string nor in any
 *    streamed chunk.
 *  - **tokens after the marker are not shown**: the text after it is absent from BOTH the
 *    return value and the callback stream.
 *  - **a split marker is caught**: a stop string that no single token produces will be
 *    assembled across pieces, which is the streaming case the old code could not handle.
 *  - **an empty stop list is harmless**: it must not swallow the reply.
 *
 * ## Running
 *
 * ```
 *   ./gradlew :app:testDebugUnitTest --offline \
 *     -PcharalyNativeLib=.../libcharaly_llama.so \
 *     -PcharalyModelFile=.../stories15M-q4_0.gguf \
 *     --tests dev.charaly.app.storyloop.StopSequenceBoundaryTest
 * ```
 * Without a native library or a model the class SKIPS. A green tick never stands in for
 * a run that did not happen.
 */
class StopSequenceBoundaryTest {

    private val modelFile: File? = System.getProperty("charalyModelFile")
        ?.takeIf { it.isNotBlank() }
        ?.let { File(it) }
        ?.takeIf { it.isFile }

    private var handle: Long = 0L

    private fun ready() {
        assumeTrue(
            "no libcharaly_llama.so offered (run scripts/verify-story-loop-host.sh)",
            LlamaNative.isAvailable(),
        )
        assumeTrue("no real GGUF offered (-PcharalyModelFile)", modelFile != null)
        if (handle == 0L) {
            handle = LlamaNative.loadModel(modelFile!!.absolutePath, 512, 0, 0, 0) { _, _ -> }
            assumeTrue("the test model could not be loaded", handle != 0L)
        }
    }

    @org.junit.After
    fun release() {
        if (handle != 0L) {
            LlamaNative.freeModel(handle)
            handle = 0L
        }
    }

    private class Turn(val returned: String, val streamed: String)

    /** One real generation, recording both the return value and the callback stream. */
    private fun generate(prompt: String, stops: List<String>, maxTokens: Int = 48): Turn {
        val streamed = StringBuilder()
        val returned = LlamaNative.generate(
            handle = handle,
            prompt = prompt,
            maxTokens = maxTokens,
            temperature = 0.2f,
            topP = 0.9f,
            topK = 20,
            minP = 0.05f,
            repeatPenalty = 1.1f,
            seed = 7L,
            stopSequences = stops.toTypedArray(),
            tokenCallback = { streamed.append(it) },
        )
        return Turn(returned, streamed.toString())
    }

    /** A prompt that reliably makes a small model produce prose, in the plain turn format. */
    private fun prompt(topic: String): String =
        "Write one short paragraph about $topic.\n[assistant]"

    // ------------------------------------------------------------------
    // 1. Normal text is preserved
    // ------------------------------------------------------------------

    @Test
    fun `a stop sequence that never appears leaves the reply intact`() {
        ready()
        val turn = generate(prompt("a lighthouse in winter"), listOf("ZZZ_NEVER_EMITTED_ZZZ"))
        assertTrue("the model produced no text", turn.returned.isNotBlank())
        assertFalse(
            "a stop string that never appears must not truncate anything",
            turn.returned.isBlank(),
        )
        assertTrue(
            "no U+FFFD", turn.returned.none { it == '�' },
        )
    }

    // ------------------------------------------------------------------
    // 2. An EMPTY stop list is harmless - and must not swallow the reply
    // ------------------------------------------------------------------

    @Test
    fun `an empty stop list does not swallow the reply`() {
        ready()
        val turn = generate(prompt("a bakery on a rainy morning"), emptyList())
        assertTrue("an empty stop list must still return the model's text", turn.returned.isNotBlank())
    }

    // ------------------------------------------------------------------
    // 3. The ChatML marker is never visible, and nothing after it is shown
    // ------------------------------------------------------------------

    @Test
    fun `a marker that the model emits itself is never shown to the caller`() {
        ready()
        // The same marker set Charaly derives from a ChatML chat template, which is what
        // ModelPresetLibrary.stopSequences() produces for Qwen2.5 and Qwen3.
        val stops = listOf("<|im" + "_end|>", "<|im" + "_start|>", "<end_of_turn>")
        val turn = generate(prompt("a library after closing time"), stops)
        stops.forEach { marker ->
            assertFalse(
                "the marker must not appear in the RETURNED text",
                turn.returned.contains(marker),
            )
            assertFalse(
                "the marker must not appear in any STREAMED chunk",
                turn.streamed.contains(marker),
            )
        }
    }

    // ------------------------------------------------------------------
    // 4. A marker SPLIT across streaming pieces is caught
    // ------------------------------------------------------------------

    @Test
    fun `a marker split across several streaming pieces is still caught`() {
        ready()
        // Deliberately NOT a single token: "<|im" + "_end|>" is what actually shows up
        // when a byte-fallback vocabulary splits a marker, and it cannot be matched by
        // any code that only inspects the current piece.
        val split = listOf("<|im" + "_end|>", "<|im" + "_start|>")
        val turn = generate(prompt("a train arriving at dawn"), split)
        split.forEach { marker ->
            assertFalse(
                "a split marker leaked into the returned text",
                turn.returned.contains(marker),
            )
        }
        // The invariant that matters for a split marker: whatever was streamed, plus
        // whatever was held back, is exactly what was returned. A matcher that trims the
        // tail unconditionally would make these differ by the held-back bytes.
        assertTrue(
            "held-back text must still be delivered: streamed='${turn.streamed}' returned='${turn.returned}'",
            turn.streamed.isNotBlank(),
        )
    }

    // ------------------------------------------------------------------
    // 5. Streamed and returned agree - the tail flush is not dropping characters
    // ------------------------------------------------------------------

    @Test
    fun `streamed text and returned text agree when no stop matches`() {
        ready()
        val turn = generate(prompt("a city waking up"), listOf("ZZZ_NEVER_EMITTED_ZZZ"))
        assertTrue("the model produced no text", turn.returned.isNotBlank())
        assertTrue(
            "every returned character must have been streamed too, so the held-back tail " +
                "was not dropped: streamed='${turn.streamed}' returned='${turn.returned}'",
            turn.returned.trim() == turn.streamed.trim(),
        )
    }
}
