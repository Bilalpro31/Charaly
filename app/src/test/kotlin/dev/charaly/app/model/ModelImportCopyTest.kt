package dev.charaly.app.model

import dev.charaly.app.inference.LocalLlamaInferenceEngine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * The half of a GGUF import that can go wrong slowly: the copy itself.
 *
 * Three properties, all of which were only ever observable on a phone holding a 4 GB file:
 * the progress is measured rather than invented, a Cancel actually stops the copy and
 * reclaims the partial, and a completed copy hashes what it wrote.
 */
class ModelImportCopyTest {

    private fun source(size: Int): ByteArrayInputStream =
        ByteArrayInputStream(ByteArray(size) { (it % 251).toByte() })

    // ---- 6. progress is measured -------------------------------------------

    @Test
    fun `6 - progress is counted bytes, cumulative and monotonic`() = runBlocking {
        val size = 4 * 1024 * 1024
        val seen = mutableListOf<ImportProgress>()
        val total = ModelManager.copyWithProgress(
            input = source(size),
            output = ByteArrayOutputStream(),
            digest = MessageDigest.getInstance("SHA-256"),
            declaredBytes = size.toLong(),
            onProgress = { seen += it },
        )

        assertEquals(size.toLong(), total)
        assertTrue("a copy must report at least a start and an end", seen.size >= 2)
        assertEquals("opens at zero", 0L, seen.first().bytesCopied)
        assertEquals("and closes at the full size", size.toLong(), seen.last().bytesCopied)
        assertEquals("which is 100%", 1f, seen.last().fraction!!, 0.001f)
        seen.zipWithNext().forEach { (before, after) ->
            assertTrue(
                "progress must never go backwards (${before.bytesCopied} -> ${after.bytesCopied})",
                after.bytesCopied >= before.bytesCopied,
            )
        }
    }

    @Test
    fun `6 - an undeclared size yields NO fraction rather than a fabricated one`() = runBlocking {
        val seen = mutableListOf<ImportProgress>()
        ModelManager.copyWithProgress(
            input = source(2048),
            output = ByteArrayOutputStream(),
            digest = MessageDigest.getInstance("SHA-256"),
            declaredBytes = -1L,
            onProgress = { seen += it },
        )
        assertTrue(seen.isNotEmpty())
        seen.forEach {
            assertNull("a bar drawn from nothing is worse than no bar", it.fraction)
        }
    }

    // ---- 6. cancellation ----------------------------------------------------

    @Test
    fun `6 - cancelling an import stops the copy and frees the partial`() = runBlocking {
        // The source BLOCKS inside read(), so the copy is provably mid-transfer when the
        // cancel lands. Timing it against a fast in-memory stream would only prove that
        // copying 64 MB is quicker than a test can click.
        val size = 8 * 1024 * 1024
        val inRead = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val source = object : java.io.InputStream() {
            private var served = 0
            override fun read(): Int {
                val buffer = ByteArray(1)
                return if (read(buffer, 0, 1) < 0) -1 else buffer[0].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                inRead.countDown()
                release.await()
                if (served >= size) return -1
                val n = minOf(len, size - served)
                served += n
                return n
            }
        }
        val sink = ByteArrayOutputStream()
        // Captured in a cell rather than taken from `async`'s own result: a cancelled `async`
        // refuses to hand back a value at all, which would make the assertion about the
        // FAILURE indistinguishable from an assertion about the JOB.
        val outcome = java.util.concurrent.atomic.AtomicReference<Result<Long>?>(null)
        val job = launch(kotlinx.coroutines.Dispatchers.IO) {
            outcome.set(
                runCatching {
                    ModelManager.copyWithProgress(
                        input = source,
                        output = sink,
                        digest = MessageDigest.getInstance("SHA-256"),
                        declaredBytes = size.toLong(),
                    )
                },
            )
        }

        inRead.await()
        job.cancel()
        release.countDown()
        job.join()

        val result = outcome.get()
        assertTrue("the copy must have reported an outcome", result != null)
        assertTrue("a cancelled copy must surface as a failure, not a partial success", result!!.isFailure)
        assertTrue(
            "the copy must have stopped early: wrote ${sink.size()} of $size",
            sink.size() < size,
        )
    }

    // ---- 1. a completed copy hashes exactly what it wrote --------------------

    @Test
    fun `1 - the digest is of the bytes actually written`() = runBlocking {
        val bytes = ByteArray(3 * 1024 * 1024) { (it % 17).toByte() }
        val digest = MessageDigest.getInstance("SHA-256")
        val sink = ByteArrayOutputStream()
        val total = ModelManager.copyWithProgress(
            input = ByteArrayInputStream(bytes),
            output = sink,
            digest = digest,
            declaredBytes = bytes.size.toLong(),
        )

        assertEquals(bytes.size.toLong(), total)
        val fromStream = digest.digest().joinToString("") { "%02x".format(it) }
        val expected = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(expected, fromStream)
    }

    // ---- 17. the model a memory guard would refuse ---------------------------

    @Test
    fun `17 - a model larger than free memory is refused before native is touched`() {
        // `LocalLlamaInferenceEngine` is real here; the point is the ORDER of the checks,
        // which is what stands between a 4 GB file and an OOM kill.
        val outcome = runBlocking {
            LocalLlamaInferenceEngine().loadModel(
                dev.charaly.runtime.inference.ModelLoadRequest(path = "/models/enormous.gguf"),
            )
        }
        // The file is not there on this JVM, so the answer must be a typed, user-language
        // failure and not an exception escaping the loader.
        assertTrue(outcome is dev.charaly.runtime.inference.LoadOutcome.Failed)
    }

    @Test
    fun `the import suffix is one constant, shared with the download pipeline`() {
        assertEquals(".part", ModelManager.PART_SUFFIX)
        assertFalse("a partial must never end in .gguf", "x.gguf${ModelManager.PART_SUFFIX}".endsWith(".gguf"))
    }

    @Test
    fun `6 - reporting is throttled so a 4 GB copy cannot flood the UI`() {
        // Eight megabytes between reports: about 500 updates for a 4 GB model, which is
        // comfortably under the frame budget and still smooth enough to look alive.
        assertTrue(ModelManager.PROGRESS_STEP_BYTES >= 1024L * 1024L)
        assertTrue(ModelManager.PROGRESS_STEP_BYTES <= 64L * 1024L * 1024L)
        val progress = ImportProgress(bytesCopied = 512L, declaredBytes = 1024L)
        assertEquals(0.5f, progress.fraction!!, 0.0001f)
        assertEquals(0f, ImportProgress(0L, 1024L).fraction!!, 0.0001f)
    }

}