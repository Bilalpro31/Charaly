package dev.charaly.runtime.model.gguf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validation versus compatibility, as two separate layers.
 *
 * The bug these tests were written against: a *valid* GGUF whose architecture
 * the bundled engine lacks was treated as an *invalid* model - the import was
 * rejected and the file deleted. "Valid GGUF" and "engine can load it" are
 * different facts; this suite pins them apart.
 */
class GgufCompatibilityVerdictTest {

    private fun le32(out: java.io.ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }

    private fun le64(out: java.io.ByteArrayOutputStream, value: Long) {
        for (shift in 0 until 64 step 8) out.write(((value ushr shift) and 0xFF).toInt())
    }

    private fun str(out: java.io.ByteArrayOutputStream, value: String) {
        le64(out, value.toByteArray(Charsets.UTF_8).size.toLong())
        out.write(value.toByteArray(Charsets.UTF_8))
    }

    private fun ggufBytes(architecture: String, contextLength: Int = 8192): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write("GGUF".toByteArray())
        le32(out, 3)
        le64(out, 0L)
        le64(out, 2L)
        str(out, "general.architecture")
        le32(out, 8)
        str(out, architecture)
        str(out, "$architecture.context_length")
        le32(out, 4)
        le32(out, contextLength)
        out.write(ByteArray(64) { 0x11 })
        return out.toByteArray()
    }

    @Test
    fun `valid gguf with a supported architecture is ready`() {
        val read = GgufReader.read(ggufBytes("qwen3").inputStream())
        assertTrue(read is GgufReadResult.Success)
        val verdict = GgufCompatibility.classify(read)
        assertEquals(CharalyCompatibility.CHARALY_READY, verdict.compatibility)
        assertTrue(verdict.isReady)
    }

    @Test
    fun `valid gguf with an engine-unsupported architecture stays importable`() {
        // THE regression case: Gemma 4 is a real, valid GGUF that this
        // build's llama.cpp cannot load. It must not be scored UNSUPPORTED,
        // which previously caused the import to delete the file.
        val read = GgufReader.read(ggufBytes("gemma4").inputStream())
        assertTrue(read is GgufReadResult.Success)
        val verdict = GgufCompatibility.classify(read)

        assertEquals(CharalyCompatibility.MANUAL_IMPORT_ONLY, verdict.compatibility)
        assertTrue("the file is valid and must remain importable", verdict.compatibility.isImportable)
        assertFalse(verdict.isReady)
        assertEquals(
            dev.charaly.runtime.model.EngineSupport.ENGINE_UPDATE_REQUIRED,
            verdict.engineSupport,
        )
        assertTrue(verdict.reason.isNotBlank())
    }

    @Test
    fun `valid gguf with an unknown architecture is importable, not invalid`() {
        val read = GgufReader.read(ggufBytes("some-future-arch").inputStream())
        assertTrue(read is GgufReadResult.Success)
        val verdict = GgufCompatibility.classify(read)

        assertEquals(CharalyCompatibility.MANUAL_IMPORT_ONLY, verdict.compatibility)
        assertEquals(
            dev.charaly.runtime.model.EngineSupport.UNKNOWN_ARCHITECTURE,
            verdict.engineSupport,
        )
    }

    @Test
    fun `garbage bytes are invalid and cannot be imported`() {
        val read = GgufReader.read("definitely not a gguf".toByteArray().inputStream())
        assertTrue(read is GgufReadResult.Failure)
        val verdict = GgufCompatibility.classify(read)
        assertEquals(CharalyCompatibility.UNSUPPORTED, verdict.compatibility)
        assertFalse(verdict.compatibility.isImportable)
    }

    @Test
    fun `a truncated header is invalid, not merely unsupported`() {
        val bytes = ggufBytes("llama")
        val read = GgufReader.read(bytes.copyOfRange(0, 40).inputStream())
        assertTrue(read is GgufReadResult.Failure)
        assertEquals(CharalyCompatibility.UNSUPPORTED, GgufCompatibility.classify(read).compatibility)
    }
}
