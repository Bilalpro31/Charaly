package dev.charaly.app.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE JNI RETURN CONTRACT, TESTED.
 *
 * ## What this file is
 *
 * The JNI surface used to declare `modelMetadata`, `benchmark` and
 * `availableBackends` as returning `Map`/`List` while `charaly_jni.cpp` returned
 * `jobjectArray`. Nothing caught it: Kotlin compiles happily against a declaration
 * that does not exist in C++, because there is no header to disagree with. It only
 * failed on a real device, as a `ClassCastException` at the first call.
 *
 * So the contract is asserted here at the two levels that can be checked without a
 * device: that the Kotlin declaration asks for a `String[]` (which is what
 * `jobjectArray` actually is), and that the parser handles every shape the array can
 * arrive in.
 *
 * ## What this file is not
 *
 * It does not claim any inference works. Nothing here produces a token, and no mock
 * stands in for llama.cpp. A test that "proved" streaming worked by faking a
 * generator would prove only that the fake was called.
 */
class NativeReturnParserTest {

    // ==================================================================
    // 1. The declaration matches what native returns
    // ==================================================================

    @Test
    fun `the three collection returns are String arrays, not Map or List`() {
        val source = sourceOf("LlamaNative.kt")

        // These three used to be Map<String,String> / Map<String,String> / List<String>.
        // Native returns jobjectArray for all of them, so the declaration has to ask for
        // an array or the JVM casts a String[] to a Map and throws on device.
        assertTrue(
            "modelMetadata must return Array<String> to match jobjectArray",
            source.contains("external fun modelMetadata(handle: Long): Array<String>"),
        )
        assertTrue(
            "benchmark must return Array<String> to match jobjectArray",
            source.contains("): Array<String>") &&
                source.contains("external fun benchmark("),
        )
        assertTrue(
            "availableBackends must return Array<String> to match jobjectArray",
            source.contains("external fun availableBackends(): Array<String>"),
        )

        // And no external declaration may promise a Kotlin collection that native
        // cannot build without extra JNI calls.
        val collectionReturns = Regex("""external fun \w+\([^)]*\)\s*:\s*(Map|List|Set)<""")
            .findAll(source)
            .map { it.value }
            .toList()
        assertTrue(
            "no external declaration may return a Kotlin collection: native cannot " +
                "produce one for free. Found: $collectionReturns",
            collectionReturns.isEmpty(),
        )
    }

    @Test
    fun `generate takes stop sequences as an array because native reads a jobjectArray`() {
        val source = sourceOf("LlamaNative.kt")
        assertTrue(
            "stopSequences must be Array<String>; native indexes it with " +
                "GetObjectArrayElement, which throws on a java.util.List",
            source.contains("stopSequences: Array<String>"),
        )
    }

    // ==================================================================
    // 2. Key/value parsing
    // ==================================================================

    @Test
    fun `valid key value pairs become a map`() {
        val parsed = NativeReturnParser.parseKeyValues(
            arrayOf("general.name", "Test Model", "parameters", "7000000000"),
        )
        assertEquals(
            mapOf("general.name" to "Test Model", "parameters" to "7000000000"),
            parsed.valuesOrEmpty,
        )
        assertTrue(parsed is NativeReturnParser.KeyValues.Ok)
    }

    @Test
    fun `an empty array is a legitimate empty result, not a failure`() {
        // The distinction the whole file exists for: a model with no metadata is not a
        // broken call, and reporting it as one would train users to ignore the field.
        val parsed = NativeReturnParser.parseKeyValues(arrayOf())
        assertTrue("an empty array must parse as Ok", parsed is NativeReturnParser.KeyValues.Ok)
        assertTrue(parsed.valuesOrEmpty.isEmpty())
    }

    @Test
    fun `an odd element count is reported as malformed, not silently truncated`() {
        val parsed = NativeReturnParser.parseKeyValues(arrayOf("tokens", "16", "dangling"))
        assertTrue(
            "a key with no value means the contract was broken",
            parsed is NativeReturnParser.KeyValues.Malformed,
        )
        assertTrue((parsed as NativeReturnParser.KeyValues.Malformed).detail.contains("3"))
        // Nothing is invented from a broken payload.
        assertTrue(parsed.valuesOrEmpty.isEmpty())
    }

    @Test
    fun `an empty key is malformed rather than a usable map entry`() {
        val parsed = NativeReturnParser.parseKeyValues(arrayOf("", "orphan", "k", "v"))
        assertTrue(parsed is NativeReturnParser.KeyValues.Malformed)
        assertTrue(parsed.valuesOrEmpty.isEmpty())
    }

    @Test
    fun `a later duplicate key wins, as a native HashMap would`() {
        val parsed = NativeReturnParser.parseKeyValues(arrayOf("k", "first", "k", "second"))
        assertEquals(mapOf("k" to "second"), parsed.valuesOrEmpty)
    }

    @Test
    fun `empty values are preserved rather than dropped`() {
        // An empty string is a real answer from GGUF metadata. Dropping the entry would
        // make "present but blank" indistinguishable from "absent".
        val parsed = NativeReturnParser.parseKeyValues(arrayOf("general.name", "", "k", "v"))
        assertEquals(mapOf("general.name" to "", "k" to "v"), parsed.valuesOrEmpty)
    }

    // ==================================================================
    // 3. A native call that throws is a failure, never an empty result
    // ==================================================================

    @Test
    fun `an UnsatisfiedLinkError becomes Failed rather than an empty map`() {
        // This is the exact bug: runCatching{...}.getOrDefault(emptyMap()) turned a
        // missing native library into "this model has no metadata".
        val parsed = NativeReturnParser.parseKeyValuesCatching("model metadata") {
            throw UnsatisfiedLinkError("no libcharaly_llama.so for arm64-v8a")
        }
        assertTrue(
            "a missing native library must stay a failure",
            parsed is NativeReturnParser.KeyValues.Failed,
        )
        assertTrue((parsed as NativeReturnParser.KeyValues.Failed).reason.isNotBlank())
        assertTrue(parsed.valuesOrEmpty.isEmpty())
    }

    @Test
    fun `a ClassCastException is a failure too, not an empty map`() {
        val parsed = NativeReturnParser.parseKeyValuesCatching("benchmark") {
            throw ClassCastException("java.lang.String[] cannot be cast to java.util.Map")
        }
        assertTrue(parsed is NativeReturnParser.KeyValues.Failed)
    }

    @Test
    fun `an Error that is not an Exception is still caught`() {
        // UnsatisfiedLinkError is an Error. A boundary that only catches Exception lets
        // it escape to the UI thread, which is how a stripped .so becomes a crash.
        val parsed = NativeReturnParser.parseKeyValuesCatching("metadata") {
            throw StackOverflowError("deep recursion")
        }
        assertTrue(parsed is NativeReturnParser.KeyValues.Failed)
    }

    @Test
    fun `a successful call parses normally through the catching wrapper`() {
        val parsed = NativeReturnParser.parseKeyValuesCatching("metadata") {
            arrayOf("a", "1", "b", "2")
        }
        assertEquals(mapOf("a" to "1", "b" to "2"), parsed.valuesOrEmpty)
    }

    // ==================================================================
    // 4. Backend parsing
    // ==================================================================

    @Test
    fun `a CPU-only build reports only what ggml reported`() {
        val devices = NativeReturnParser.parseDevices(arrayOf("CPU:CPU"))
        assertEquals(listOf("CPU:CPU"), devices)
        assertFalse(
            "a CPU-only build must not gain a GPU entry",
            devices.any { it.startsWith("GPU") || it.startsWith("Vulkan") || it.startsWith("CUDA") },
        )
    }

    @Test
    fun `CPU and GPU both survive when ggml reports both`() {
        val devices = NativeReturnParser.parseDevices(
            arrayOf("CPU:CPU", "GPU:Vulkan (Adreno 740)", "ACCELERATOR:OpenCL"),
        )
        assertEquals(
            listOf("CPU:CPU", "GPU:Vulkan (Adreno 740)", "ACCELERATOR:OpenCL"),
            devices,
        )
    }

    @Test
    fun `an empty native result stays empty and is never backfilled with a CPU claim`() {
        // The UI renders "unknown" for an empty list. If the parser invented a CPU entry
        // here, a build whose registry could not be read would claim a device it did not
        // verify - which is the fake-availability failure the brief forbids.
        val devices = NativeReturnParser.parseDevices(arrayOf())
        assertTrue(devices.isEmpty())
    }

    @Test
    fun `blank and unnamed devices are dropped rather than shown`() {
        val devices = NativeReturnParser.parseDevices(
            arrayOf("", "   ", "GPU:unknown", "CPU:CPU"),
        )
        assertEquals(
            listOf("CPU:CPU"),
            devices,
        )
    }

    @Test
    fun `duplicate device entries are collapsed`() {
        val devices = NativeReturnParser.parseDevices(arrayOf("CPU:CPU", "CPU:CPU"))
        assertEquals(listOf("CPU:CPU"), devices)
    }

    @Test
    fun `a failing device call is a failure result, not an empty success`() {
        val result = NativeReturnParser.parseDevicesCatching("devices") {
            throw UnsatisfiedLinkError("no library")
        }
        assertTrue("the caller must be able to tell this failed", result.isFailure)
    }

    private fun sourceOf(fileName: String): String =
        File("src/main/kotlin/dev/charaly/app/inference/$fileName").readText()
}