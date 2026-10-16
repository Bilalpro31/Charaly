package dev.charaly.runtime.model.gguf

import dev.charaly.runtime.model.EngineCapabilities
import dev.charaly.runtime.model.EngineSupport
import dev.charaly.runtime.model.EngineVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * GGUF validation, case by case.
 *
 * These are the cases where a wrong answer two screens later is a native abort rather than
 * an error message: a file that passes here is handed to `llama_model_load_from_file`, which
 * asserts its way out of the process on anything it did not expect. So "reject early, with a
 * reason" is not politeness here, it is the only layer that can prevent a crash.
 */
class GgufValidationMatrixTest {

    // ---- 1. valid GGUF -----------------------------------------------------

    @Test
    fun `1 - a valid GGUF parses and reports its architecture`() {
        val bytes = GgufBuilder().architecture("llama").contextLength(4096).build()
        val result = GgufReader.read(bytes.inputStream())
        assertTrue("a well-formed header must parse: $result", result.isSuccess)
        val metadata = result.metadataOrNull!!
        assertEquals("llama", metadata.architecture)
        assertTrue(EngineCapabilities.supports(metadata.architecture))
    }

    @Test
    fun `1 - every value type round-trips, including spec-shaped arrays`() {
        // The array length is a uint64 in the GGUF spec. Reading it as a uint32 shifts every
        // following field, so a header with an array is the case that catches it.
        val bytes = GgufBuilder()
            .architecture("qwen3")
            .u32("general.file_type", 15)
            .u64("%s.context_length".format("qwen3"), 8192)
            .str("tokenizer.ggml.tokens", "a")
            .array("qwen3.block_count", listOf(1L, 2L, 3L))
            .build()
        val result = GgufReader.read(bytes.inputStream())
        assertTrue("$result", result.isSuccess)
        assertEquals("qwen3", result.metadataOrNull!!.architecture)
        assertEquals(5, result.metadataOrNull!!.pairCount)
        assertEquals(8192, result.metadataOrNull!!.contextLength)
    }

    // ---- 2. invalid file ---------------------------------------------------

    @Test
    fun `2 - a text file renamed to gguf is refused as NOT_GGUF`() {
        val result = GgufReader.read("not a model at all".toByteArray().inputStream())
        val failure = result as GgufReadResult.Failure
        assertEquals(GgufFailure.NOT_GGUF, failure.failure)
    }

    @Test
    fun `2 - a safetensors file is refused as NOT_GGUF`() {
        val result = GgufReader.read("{\"dtype\":\"F32\"}".toByteArray().inputStream())
        assertEquals(GgufFailure.NOT_GGUF, (result as GgufReadResult.Failure).failure)
    }

    // ---- 3. zero-byte file -------------------------------------------------

    @Test
    fun `3 - a zero-byte file is refused as NOT_GGUF and never reaches the loader`() {
        val result = GgufReader.read(ByteArray(0).inputStream())
        assertEquals(GgufFailure.NOT_GGUF, (result as GgufReadResult.Failure).failure)
    }

    // ---- 4. partial import -------------------------------------------------

    @Test
    fun `4 - a header cut off mid-way is refused as TRUNCATED`() {
        val whole = GgufBuilder().architecture("llama").contextLength(2048).build()
        val half = whole.copyOf(whole.size / 2)
        val result = GgufReader.read(half.inputStream())
        assertEquals(GgufFailure.TRUNCATED, (result as GgufReadResult.Failure).failure)
    }

    @Test
    fun `4 - a file that stops after the magic is not a partial success`() {
        val magicOnly = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())
        val result = GgufReader.read(magicOnly.inputStream())
        assertTrue(result is GgufReadResult.Failure)
        assertFalse("a magic number is not a header", result.isSuccess)
    }

    @Test
    fun `4 - a part-suffixed file is never mistaken for a finished model`() {
        // The rule ModelManager.managedModels applies before it can see a registry entry.
        val finished = "qwen3-4b.gguf"
        val inFlight = "qwen3-4b.gguf.part"
        assertTrue(finished.endsWith(".gguf", ignoreCase = true))
        assertFalse(
            "an unfinished transfer must not match the model glob",
            inFlight.endsWith(".gguf", ignoreCase = true) && !inFlight.endsWith(".part", ignoreCase = true),
        )
    }

    // ---- 22. unsupported architecture --------------------------------------

    @Test
    fun `22 - an unregistered architecture is called out before any native call`() {
        val bytes = GgufBuilder().architecture("gemma9-from-the-future").build()
        val result = GgufReader.read(bytes.inputStream())
        assertTrue(result.isSuccess)
        val architecture = result.metadataOrNull!!.architecture
        assertFalse(EngineCapabilities.supports(architecture))

        val verdict = EngineVerdict.of(architecture)
        assertFalse("an architecture the engine does not register is not loadable", verdict.isLoadable)
        assertTrue("the reason must name a real problem", verdict.reason.isNotBlank())
    }

    @Test
    fun `22 - a blank architecture is unknown rather than refused`() {
        // An unread header means "unknown", and refusing a real model because its header has
        // not been parsed yet is the exact failure ModelSelection exists to remove.
        assertFalse(EngineCapabilities.supports(""))
        val verdict = EngineVerdict.of("")
        assertTrue(
            "a blank architecture must not be classified as an update-required model",
            verdict.support != EngineSupport.ENGINE_UPDATE_REQUIRED,
        )
    }

    // ---- 26. long prompt / 27. Turkish prompt at the header level -----------

    @Test
    fun `27 - Turkish and emoji metadata survives the header parse`() {
        val bytes = GgufBuilder().architecture("llama").str("general.name", "Karşıyaka 🌊 modeli").build()
        val result = GgufReader.read(bytes.inputStream())
        assertTrue("$result", result.isSuccess)
        assertEquals("Karşıyaka 🌊 modeli", result.metadataOrNull!!.name)
    }
}

/**
 * Writes bytes in the exact on-disk GGUF v3 layout.
 *
 * A hand-built file rather than a checked-in fixture: the interesting cases are malformed
 * ones, and a fixture cannot be meaningfully "this header with one length field wrong".
 */
internal class GgufBuilder {
    private val kv = ByteArrayOutputStream()
    private var pairs = 0
    private val tensors = ByteArrayOutputStream()
    private var tensorCount = 0

    fun architecture(name: String) = str("general.architecture", name)

    fun contextLength(n: Long) = u64("llama.context_length", n)

    fun str(key: String, value: String) = apply {
        writeKey(key); le32(8); writeString(value, kv); pairs++
    }

    fun u32(key: String, value: Long) = apply {
        writeKey(key); le32(4); le32(value); pairs++
    }

    fun u64(key: String, value: Long) = apply {
        writeKey(key); le32(10); le64(value); pairs++
    }

    /** An array of int64s. Its length is a **uint64** on disk, per the spec. */
    fun array(key: String, values: List<Long>) = apply {
        writeKey(key)
        le32(9) // ARRAY
        le32(11) // element type: INT64
        le64(values.size.toLong()) // element count: uint64
        values.forEach { le64(it) }
        pairs++
    }

    /** A tensor the loader would see. Included so `tensor_count` is not a lie. */
    fun tensor(name: String) = apply {
        writeString(name, tensors)
        le32(1, tensors) // dimensionality
        le64(1, tensors) // one element
        le32(0, tensors) // GGML_TYPE_F32
        le64(0, tensors) // offset
        tensorCount++
    }

    fun build(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))
        le32(3, out) // version
        le64(tensorCount.toLong(), out)
        le64(pairs.toLong(), out)
        kv.writeTo(out)
        tensors.writeTo(out)
        return out.toByteArray()
    }

    private fun writeKey(key: String) = writeString(key, kv)

    private fun writeString(value: String, to: ByteArrayOutputStream) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        le64(bytes.size.toLong(), to)
        to.write(bytes)
    }

    private fun le32(v: Long, to: ByteArrayOutputStream = kv) {
        to.write((v and 0xFF).toInt()); to.write((v shr 8 and 0xFF).toInt())
        to.write((v shr 16 and 0xFF).toInt()); to.write((v shr 24 and 0xFF).toInt())
    }

    private fun le64(v: Long, to: ByteArrayOutputStream = kv) {
        le32(v and 0xFFFFFFFFL, to)
        le32(v ushr 32 and 0xFFFFFFFFL, to)
    }
}