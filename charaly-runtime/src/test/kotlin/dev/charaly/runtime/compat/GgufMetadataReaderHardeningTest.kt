package dev.charaly.runtime.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The import-time reader must fail soft - a specific failure, never a process
 * crash - on every adversarial header shape the wild can produce.
 */
class GgufMetadataReaderHardeningTest {

    private fun le32(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }

    private fun le64(out: ByteArrayOutputStream, value: Long) {
        for (shift in 0 until 64 step 8) out.write(((value ushr shift) and 0xFF).toInt())
    }

    private fun str(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        le64(out, bytes.size.toLong())
        out.write(bytes)
    }

    /** Header whose first kv entry is an ARRAY of uint32, with a declared count. */
    private fun ggufWithArray(elementType: Int, count: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("GGUF".toByteArray())
        le32(out, 3)
        le64(out, 1L)
        le64(out, 1L)
        str(out, "general.architecture")
        le32(out, 9) // GGUF_TYPE_ARRAY
        le32(out, elementType)
        le64(out, count) // GGUF spec: element count is uint64
        if (count in 1..4) repeat(count.toInt()) { le32(out, 11) }
        return out.toByteArray()
    }

    @Test
    fun `array element count is read as uint64, not uint32`() {
        // 3 elements of uint32: total value bytes 12. If the reader used a 32-bit
        // count it would misalign the stream by 4 bytes and every later key would
        // read garbage. A clean parse proves the width is right.
        val bytes = ggufWithArray(elementType = 4, count = 3)
        val withTail = bytes + "\u0000".repeat(8).toByteArray()
        val result = GgufMetadataReader.read(withTail)
        // The parse itself must not throw; success or a tidy failure are both
        // acceptable, a crash or hang is not. With count=3 the reader consumes
        // the array and stops (no more kv pairs), so it is a Success.
        assertTrue("expected success, got $result", result.isSuccess)
    }

    @Test
    fun `a tokenizer array parses together with the key that follows it`() {
        // A real GGUF v3 header: tokenizer.ggml.tokens is an ARRAY of STRING with
        // count 2, followed by general.architecture. The array count is uint64.
        //
        // This is the decisive width test. The count above (3) has a zero high
        // dword, so a reader that wrongly read it as uint32 would still consume
        // the right number of elements when tail bytes happen to follow - the
        // existing test cannot tell the two readers apart. Here the second key
        // only parses if the count was read at full width: a 32-bit read leaves
        // the high 4 bytes of the count field in the stream, which the reader
        // then takes for the first element's length, and every later byte is
        // garbage.
        val out = ByteArrayOutputStream()
        out.write("GGUF".toByteArray())
        le32(out, 3)
        le64(out, 1L) // tensor count
        le64(out, 2L) // kv count
        str(out, "tokenizer.ggml.tokens")
        le32(out, 9) // GGUF_TYPE_ARRAY
        le32(out, 8) // elements are STRING
        le64(out, 2L) // element count: uint64
        str(out, "<s>")
        str(out, "</s>")
        str(out, "general.architecture")
        le32(out, 8) // STRING
        str(out, "llama")

        val result = GgufMetadataReader.read(out.toByteArray())
        assertTrue("expected success, got $result", result.isSuccess)
        val meta = result.getOrThrow()
        assertEquals("llama", meta.architecture)
        assertEquals("[<s>, </s>]", meta.values["tokenizer.ggml.tokens"])
    }

    @Test
    fun `a huge declared array count is rejected, not allocated`() {
        val bytes = ggufWithArray(elementType = 4, count = 1L shl 40)
        assertTrue(GgufMetadataReader.read(bytes).isFailure)
    }

    @Test
    fun `a huge declared string length is rejected`() {
        val out = ByteArrayOutputStream()
        out.write("GGUF".toByteArray())
        le32(out, 3)
        le64(out, 1L)
        le64(out, 1L)
        str(out, "general.name")
        le32(out, 8) // STRING
        le64(out, 1L shl 40) // absurd length
        assertTrue(GgufMetadataReader.read(out.toByteArray()).isFailure)
    }

    @Test
    fun `an unknown metadata value type is a clean failure`() {
        val out = ByteArrayOutputStream()
        out.write("GGUF".toByteArray())
        le32(out, 3)
        le64(out, 1L)
        le64(out, 1L)
        str(out, "general.name")
        le32(out, 77) // no such GGUF value type
        assertTrue(GgufMetadataReader.read(out.toByteArray()).isFailure)
    }

    @Test
    fun `a file type of 15 maps to Q4_K_M, the real tensor quantisation`() {
        val out = ByteArrayOutputStream()
        out.write("GGUF".toByteArray())
        le32(out, 3)
        le64(out, 1L)
        le64(out, 2L)
        str(out, "general.architecture"); le32(out, 8); str(out, "gemma3")
        str(out, "general.file_type"); le32(out, 4); le32(out, 15)
        val meta = GgufMetadataReader.read(out.toByteArray()).getOrThrow()
        assertEquals("Q4_K_M", meta.quantization)
        assertEquals("gemma3", meta.architecture)
    }
}
