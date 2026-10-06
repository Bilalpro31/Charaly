package dev.charaly.runtime.model

import dev.charaly.runtime.model.gguf.GgufMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gemma 4 must be reported from what the pinned engine can load, not from what the
 * model name or the GGUF header happens to say.
 */
class GemmaSupportTest {

    @Test
    fun `the pinned llama build has no gemma4 architecture`() {
        // Mirrors app/src/main/cpp/llama.cpp/src/llama-arch.cpp at the pinned commit:
        // gemma, gemma2, gemma3, gemma3n, gemma-embedding exist; "gemma4" does not.
        assertFalse(EngineCapabilities.supports("gemma4"))
        assertFalse(EngineCapabilities.supports("Gemma4"))
        assertFalse(EngineCapabilities.supports("gemma-4"))
    }

    @Test
    fun `gemma 4 reports ENGINE_UPDATE_REQUIRED rather than supported`() {
        val verdict = EngineVerdict.of("gemma4")
        assertEquals(EngineSupport.ENGINE_UPDATE_REQUIRED, verdict.support)
        assertFalse(verdict.isLoadable)
    }

    @Test
    fun `the gemma arches the pinned build does have are supported`() {
        for (arch in listOf("gemma", "gemma2", "gemma3", "gemma3n", "gemma-embedding")) {
            assertTrue("$arch should be supported", EngineCapabilities.supports(arch))
        }
    }

    @Test
    fun `a gemma-4 repository name resolves to the honest verdict`() {
        val inferred = EngineVerdict.inferArchitecture("Gemma-4-9B-Instruct-Q4_K_M.gguf")
        assertEquals("gemma4", inferred)
        assertEquals(EngineSupport.ENGINE_UPDATE_REQUIRED, EngineVerdict.of(inferred).support)
    }

    @Test
    fun `file type labels match the LLAMA_FTYPE enum of the pinned build`() {
        assertEquals("F32", GgufMetadata.FILE_TYPE_LABELS[0])
        assertEquals("F16", GgufMetadata.FILE_TYPE_LABELS[1])
        assertEquals("Q4_0", GgufMetadata.FILE_TYPE_LABELS[2])
        assertEquals("Q8_0", GgufMetadata.FILE_TYPE_LABELS[7])
        assertEquals("Q4_K_S", GgufMetadata.FILE_TYPE_LABELS[14])
        assertEquals("Q4_K_M", GgufMetadata.FILE_TYPE_LABELS[15])
        assertEquals("Q5_K_M", GgufMetadata.FILE_TYPE_LABELS[17])
        assertEquals("Q6_K", GgufMetadata.FILE_TYPE_LABELS[18])
        assertEquals("BF16", GgufMetadata.FILE_TYPE_LABELS[32])
    }
}
