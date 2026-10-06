package dev.charaly.app.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE BENCHMARK AND METADATA CONTRACTS.
 *
 * ## The array-bounds bug
 *
 * The native helper that turned `std::vector<pair<string,string>>` into a `jobjectArray`
 * allocated the array with `entries.size()` elements and then wrote each pair at index
 * `i * 2` and `i * 2 + 1`. A benchmark returns seven keys, so it wrote index 12 into a
 * seven-element array - an out-of-bounds write inside the JVM's heap, from the one code
 * path a user reaches by tapping "Measure".
 *
 * That is a memory-safety bug, not a cosmetic one, so it is asserted structurally here:
 * the allocation and the writes are compared against each other in the source.
 */
class BenchmarkAndMetadataContractTest {

    private val native = File("src/main/cpp/charaly_jni.cpp").readText()

    /** The native source with all runs of whitespace collapsed, so assertions can be written as plain text. */
    private val flatNative = native.replace(Regex("\\s+"), " ")

    // ==================================================================
    // 1. Array bounds
    // ==================================================================

    @Test
    fun `the native array is allocated with room for two elements per pair`() {
        val helper = flatNative.substringAfter("jobjectArray string_pairs_to_java(")
            .substringBefore("jobjectArray empty_string_array(")

        assertTrue(
            "the array must be sized 2 * entries: one slot for the key and one for the " +
                "value. Sized with entries.size() it is half the length the writes need.",
            helper.contains("NewObjectArray( static_cast<jsize>(entries.size() * 2)"),
        )
    }

    @Test
    fun `every write into the benchmark array is inside the allocated bounds`() {
        val helper = flatNative.substringAfter("jobjectArray string_pairs_to_java(")
            .substringBefore("jobjectArray empty_string_array(")

        assertEquals(
            "expected exactly two writes per pair",
            2,
            Regex("SetObjectArrayElement").findAll(helper).count(),
        )
        assertTrue(
            "the key is written at 2i",
            helper.contains("SetObjectArrayElement(out, static_cast<jsize>(i * 2), key)"),
        )
        assertTrue(
            "the value is written at 2i+1",
            helper.contains("SetObjectArrayElement(out, static_cast<jsize>(i * 2 + 1), value)"),
        )
        assertTrue(
            "the loop must be bounded by entries.size(), so the largest index written is " +
                "2n-1 against an allocation of 2n",
            helper.contains("for (size_t i = 0; i < entries.size(); ++i)"),
        )
    }

    @Test
    fun `the benchmark returns every field the Kotlin side reads`() {
        // Each of these is read back by LocalLlamaInferenceEngine.benchmark. A field the
        // native side stopped emitting becomes a silent null there.
        listOf(
            "tokens",
            "prompt_tokens",
            "decode_micros",
            "prompt_micros",
            "first_token_micros",
            "context_size",
        ).forEach { key ->
            // `first_token_micros` is written across three lines, so the flattened source
            // has a space between the paren and the quote. Match on the key alone.
            assertTrue(
                "benchmark must emit '$key'; LocalLlamaInferenceEngine reads it",
                flatNative.contains("\"$key\","),
            )
        }
    }

    @Test
    fun `a benchmark that produced no tokens returns an empty array, not zeroes`() {
        // Zero tokens means no measurement. Emitting tokens=0 would let a speed be stored
        // and later quoted, which is the failure this feature exists to prevent.
        val benchmark = flatNative.substringAfter("LlamaNative_benchmark(")
            .substringBefore("// Backend reporting")

        assertTrue(
            "a zero-token run must return before any figure is added",
            Regex("""if \(generated <= 0\) \{[\s\S]{0,400}?return string_pairs_to_java\(env, out\);""")
                .containsMatchIn(benchmark),
        )
    }

    // ==================================================================
    // 2. Metadata
    // ==================================================================

    @Test
    fun `metadata is enumerated from the real GGUF header, not just one key`() {
        // The old implementation read only `general.name`, while the Kotlin side reads
        // `parameters` and `quantization`. Those keys never existed, so every model in the
        // app reported a parameter count of 0 and no quantisation - silently, forever.
        assertTrue(
            "metadata must enumerate every GGUF key",
            native.contains("llama_model_meta_count") &&
                native.contains("llama_model_meta_key_by_index") &&
                native.contains("llama_model_meta_val_str_by_index"),
        )
        assertTrue(
            "parameterCount must come from the model's real header",
            native.contains("llama_model_n_params"),
        )
    }

    @Test
    fun `metadata carries the keys LocalLlamaInferenceEngine reads`() {
        listOf("parameters", "description").forEach { key ->
            assertTrue(
                "native must emit '$key'",
                flatNative.contains("out.emplace_back(\"$key\","),
            )
        }
        // The quantization is the real GGUF file type, enumerated from the header, and
        // mapped to a label on the Kotlin side. `llama_model_desc()` is not a quantization
        // and must never be reported as one.
        val engine = File("src/main/kotlin/dev/charaly/app/inference/LocalLlamaInferenceEngine.kt").readText()
        assertTrue(
            "quantLevel must come from general.file_type",
            engine.contains("metadata[\"general.file_type\"]"),
        )
        assertFalse(
            "desc must not be emitted as quantization",
            native.contains("emplace_back(\"quantization\""),
        )
    }

    @Test
    fun `an unloaded handle returns an empty metadata array rather than failing`() {
        val metadata = flatNative.substringAfter("LlamaNative_modelMetadata(")
            .substringBefore("Java_dev_charaly_app_inference_LlamaNative_contextSize")
        assertTrue(
            "no model must mean an empty array, which the parser reports as Ok(empty)",
            Regex("""handle == 0 \|\| g_model\.metadata\.empty\(\)\) \{ return empty_string_array""")
                .containsMatchIn(metadata),
        )
    }

    // ==================================================================
    // 3. Backend detection must not invent a device
    // ==================================================================

    @Test
    fun `backends are read from the ggml device registry`() {
        val backends = native.substringAfter("LlamaNative_availableBackends(")
        assertTrue(
            "the device list must come from ggml_backend_dev_count",
            backends.contains("ggml_backend_dev_count"),
        )
        assertTrue(
            "device kinds must come from ggml_backend_dev_type",
            backends.contains("ggml_backend_dev_type"),
        )
    }

    @Test
    fun `an empty registry is not backfilled with an invented CPU device`() {
        val backends = native.substringAfter("LlamaNative_availableBackends(")
        assertFalse(
            "ggml registering nothing must be reported as nothing. Inventing a " +
                "\"CPU:builtin\" entry is a device claim ggml never made.",
            backends.contains("CPU:builtin"),
        )
    }

    @Test
    fun `the backend list is not gated behind a model being loaded`() {
        // The diagnostics screen asks what this build can do before any model exists.
        assertTrue(
            "availableBackends must not require a resident model",
            !native.substringAfter("LlamaNative_availableBackends(").contains("g_model.ctx == nullptr"),
        )
    }
}