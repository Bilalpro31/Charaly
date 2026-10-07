package dev.charaly.app.model

import dev.charaly.app.inference.LocalLlamaInferenceEngine
import dev.charaly.runtime.inference.InferenceError
import dev.charaly.runtime.inference.LoadOutcome
import dev.charaly.runtime.inference.ModelLoadRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/**
 * The import path must never reach the inference engine, and the engine must
 * refuse an unsupported architecture before touching llama.cpp.
 */
class ModelInfrastructureHardeningTest {

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

    private fun minimalGguf(architecture: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("GGUF".toByteArray())
        le32(out, 3)
        le64(out, 0L)
        le64(out, 1L)
        str(out, "general.architecture")
        le32(out, 8)
        str(out, architecture)
        out.write(ByteArray(32) { 0x11 })
        return out.toByteArray()
    }

    @Test
    fun `gemma4 is refused before native code, with a real reason`() = runTest {
        val file = Files.createTempFile("gemma4", ".gguf").toFile()
        file.writeBytes(minimalGguf("gemma4"))
        try {
            val outcome = LocalLlamaInferenceEngine().loadModel(ModelLoadRequest(path = file.absolutePath))
            assertTrue(outcome is LoadOutcome.Failed)
            val error = (outcome as LoadOutcome.Failed).error
            assertTrue("expected Unsupported, got $error", error is InferenceError.Unsupported)
            assertTrue(error.message!!.contains("newer llama.cpp"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a corrupt gguf is refused before native code`() = runTest {
        val file = Files.createTempFile("corrupt", ".gguf").toFile()
        val bytes = minimalGguf("llama")
        // Valid magic, then a truncated header (longer than the 24-byte floor so
        // it reaches the parser, not the size check).
        file.writeBytes(bytes.copyOfRange(0, 40))
        try {
            val outcome = LocalLlamaInferenceEngine().loadModel(ModelLoadRequest(path = file.absolutePath))
            assertTrue(outcome is LoadOutcome.Failed)
            assertTrue((outcome as LoadOutcome.Failed).error is InferenceError.InvalidModel)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a supported architecture passes the pre-flight and only fails on missing native`() = runTest {
        val file = Files.createTempFile("llama", ".gguf").toFile()
        file.writeBytes(minimalGguf("llama"))
        try {
            val outcome = LocalLlamaInferenceEngine().loadModel(ModelLoadRequest(path = file.absolutePath))
            assertTrue(outcome is LoadOutcome.Failed)
            val error = (outcome as LoadOutcome.Failed).error
            assertTrue("expected native-unavailable Unsupported, got $error", error is InferenceError.Unsupported)
            assertTrue(error.message!!.contains("native llama.cpp is not available"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `the import chain never names the inference engine`() {
        val viewModel = File("src/main/kotlin/dev/charaly/app/ui/CharalyViewModel.kt").readText()
        val importStart = viewModel.indexOf("fun importModel(uri: android.net.Uri)")
        val importEnd = viewModel.indexOf("fun readAndRecordHeader")
        val body = viewModel.substring(importStart, importEnd)
        assertTrue(
            "importModel must not call loadModel - READY is the end of import",
            !body.contains("loadModel("),
        )
        val manager = File("src/main/kotlin/dev/charaly/app/model/ModelManager.kt").readText()
        assertTrue("ModelManager must not reference the native surface", !manager.contains("LlamaNative"))
        assertTrue("ModelManager must not reference the engine", !manager.contains("InferenceEngine"))
    }

    @Test
    fun `a valid but engine-unsupported gguf is importable and native load stays blocked`() = runTest {
        val file = Files.createTempFile("gemma4", ".gguf").toFile()
        file.writeBytes(minimalGguf("gemma4"))
        try {
            // 1. The file is a VALID GGUF...
            val read = dev.charaly.runtime.model.gguf.GgufReader.read(file.inputStream())
            assertTrue("the header must parse", read is dev.charaly.runtime.model.gguf.GgufReadResult.Success)

            // 2. ...and it is importable (NOT scored UNSUPPORTED / never deleted).
            val verdict = dev.charaly.runtime.model.gguf.GgufCompatibility.classify(read, file.length())
            assertTrue(verdict.compatibility.isImportable)
            assertTrue(verdict.compatibility != dev.charaly.runtime.model.gguf.CharalyCompatibility.UNSUPPORTED)

            // 3. ...and the native engine still refuses to load it.
            val outcome = LocalLlamaInferenceEngine().loadModel(ModelLoadRequest(path = file.absolutePath))
            assertTrue(outcome is LoadOutcome.Failed)
            assertTrue((outcome as LoadOutcome.Failed).error is InferenceError.Unsupported)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `the import path must not reject or delete unsupported-but-valid files`() {
        val manager = File("src/main/kotlin/dev/charaly/app/model/ModelManager.kt").readText()
        assertTrue(
            "import must not delete a valid-but-unsupported model",
            !manager.contains("error(REASON_UNSUPPORTED)"),
        )
    }

    @Test
    fun `model ids come from content, not from the path`() {
        val sha = "deadbeef".repeat(8)
        val a = ModelIds.idFor("Qwen3-4B", sha, "/models/qwen.gguf")
        val b = ModelIds.idFor("Qwen3-4B", sha, "/other/dir/renamed.gguf")
        assertEquals(a, b)
        // Legacy fallback stays deterministic for the same path.
        val legacy1 = ModelIds.idFor("Qwen3-4B", "", "/models/qwen.gguf")
        val legacy2 = ModelIds.idFor("Qwen3-4B", "", "/models/qwen.gguf")
        assertEquals(legacy1, legacy2)
    }
}
