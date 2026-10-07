package dev.charaly.runtime.model

import dev.charaly.runtime.net.HttpStreamResponse
import dev.charaly.runtime.net.HttpTextResponse
import dev.charaly.runtime.net.HttpTransport
import dev.charaly.runtime.net.StorageProbe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * The download state machine, and the pipeline that implements it.
 *
 * ## The invariant everything else serves
 *
 * **Nothing partial is ever presented as installed.**
 *
 * Bytes land in `<name>.part`. A registry entry is written only after verification
 * *and* an atomic rename. A crash at any point leaves a `.part` file that is not a model,
 * so the library can never offer something that will fail on first use. That property is
 * what the whole state machine exists to make structural rather than aspirational.
 *
 * ## Why these use a scripted transport
 *
 * A download is the one place where a fake would be most tempting and most dangerous: a
 * fake progress bar is trivially easy and teaches the user to trust a number that is not
 * real. So the transport here emits *actual bytes* through the *actual* pipeline, and the
 * assertions are about real byte counts, real resume offsets and a real sha256 over a
 * real file. Nothing in this suite is simulated except the socket.
 */
class DownloadStateMachineTest {

    // ------------------------------------------------------------------
    // The pure state machine
    // ------------------------------------------------------------------

    /**
     * The happy path is the only route to READY, and it goes through verification.
     *
     * Every other entry into READY is a bug that would show a corrupt model to a user as
     * a working one.
     */
    @Test
    fun `ready is reachable only through verification and registering`() {
        val routesToReady = DownloadState.entries.filter { candidate ->
            candidate != DownloadState.READY && DownloadStateMachine.canTransition(candidate, DownloadState.READY)
        }
        assertEquals(
            "these states could reach READY without verification: $routesToReady",
            listOf(DownloadState.REGISTERING),
            routesToReady,
        )
    }

    @Test
    fun `registering can still refuse the file`() {
        // The last chance to reject. An UNSUPPORTED verdict discovered *after* the bytes
        // were transferred must not be able to reach READY - the user has spent their
        // data, and the least we owe them is an accurate failure.
        assertTrue(
            DownloadStateMachine.canTransition(DownloadState.REGISTERING, DownloadState.UNSUPPORTED)
        )
    }

    @Test
    fun `downloading cannot skip straight to ready`() {
        assertFalse(DownloadStateMachine.canTransition(DownloadState.DOWNLOADING, DownloadState.READY))
        assertFalse(DownloadStateMachine.canTransition(DownloadState.DOWNLOADING, DownloadState.VERIFYING))
        // It also cannot be "un-verified" into an install.
        assertFalse(DownloadStateMachine.canTransition(DownloadState.PAUSED, DownloadState.REGISTERING))
    }

    @Test
    fun `ready and unsupported are final`() {
        assertFalse("nothing follows a successful install", DownloadStateMachine.canTransition(DownloadState.READY, DownloadState.DOWNLOADING))
        assertFalse("refusing twice is not a transition", DownloadStateMachine.canTransition(DownloadState.UNSUPPORTED, DownloadState.FAILED))
        assertTrue(DownloadStateMachine.isTerminal(DownloadState.READY))
        assertTrue(DownloadStateMachine.isTerminal(DownloadState.UNSUPPORTED))
    }

    /**
     * Retry is offered only where it could work.
     *
     * A Retry button on an UNSUPPORTED or READY download is worse than none: retrying
     * cannot fix either, so it teaches the user that the app does not know its own state.
     */
    @Test
    fun `retry is offered only where it could plausibly succeed`() {
        assertTrue(DownloadState.FAILED.isRetryable)
        assertTrue(DownloadState.CANCELLED.isRetryable)
        assertTrue(DownloadState.PAUSED.isRetryable)

        assertFalse("an unsupported model cannot be fixed by retrying", DownloadState.UNSUPPORTED.isRetryable)
        assertFalse("a finished download cannot be retried", DownloadState.READY.isRetryable)
    }

    @Test
    fun `only an in-flight download is pausable and cancellable`() {
        assertTrue(DownloadState.DOWNLOADING.isPausable)
        assertFalse("pausing a queued download would be a no-op with no explanation", DownloadState.QUEUED.isPausable)
        assertFalse("pausing a failed download is meaningless", DownloadState.FAILED.isPausable)

        assertTrue(DownloadState.DOWNLOADING.isCancellable)
        assertTrue(DownloadState.PAUSED.isCancellable)
        assertTrue(DownloadState.QUEUED.isCancellable)
        assertFalse("a finished download cannot be cancelled", DownloadState.READY.isCancellable)
    }

    @Test
    fun `activity and settled are mutually exclusive`() {
        for (state in DownloadState.entries) {
            if (state.isActive) {
                assertFalse("$state is active and also settled", state.isSettled)
            }
        }
        assertTrue(DownloadState.DOWNLOADING.isActive)
        assertTrue(DownloadState.VERIFYING.isActive)
        assertFalse(DownloadState.PAUSED.isActive)
    }

    // ------------------------------------------------------------------
    // Progress honesty
    // ------------------------------------------------------------------

    /**
     * A paused download reports no fraction.
     *
     * A bar that keeps filling while paused is a lie, and `fraction` returning null is
     * what lets the UI render "paused" rather than a stalled percentage.
     */
    @Test
    fun `a paused download reports no fraction`() {
        val paused = DownloadProgress(
            catalogId = "m",
            state = DownloadState.PAUSED,
            bytesDownloaded = 500L,
            totalBytes = 1000L,
        )
        assertNull(paused.fraction)
    }

    /**
     * An unknown total reports no fraction.
     *
     * The difference between "not started" and "we do not know how big this is" matters,
     * so this returns null rather than 0.
     */
    @Test
    fun `an unknown total reports no fraction`() {
        val unknown = DownloadProgress(
            catalogId = "m",
            state = DownloadState.DOWNLOADING,
            bytesDownloaded = 500L,
            totalBytes = 0L,
        )
        assertNull(unknown.fraction)
        assertEquals("500 B", unknown.percentLabel)
    }

    @Test
    fun `a known total reports a real fraction and a real label`() {
        val halfway = DownloadProgress(
            catalogId = "m",
            state = DownloadState.DOWNLOADING,
            bytesDownloaded = 512L,
            totalBytes = 1024L,
        )
        assertEquals(0.5f, halfway.fraction!!, 0.001f)
        assertEquals("512 B of 1 kB", halfway.transferredLabel)
    }

    /**
     * Only a DOWNLOADING frame has a fraction.
     *
     * A VERIFYING frame at 100% is true but useless, and rendering it as a full bar
     * would suggest the work is done when it is not.
     */
    @Test
    fun `only downloading frames carry a fraction`() {
        for (state in DownloadState.entries.filter { it != DownloadState.DOWNLOADING }) {
            val frame = DownloadProgress(
                catalogId = "m",
                state = state,
                bytesDownloaded = 100L,
                totalBytes = 100L,
            )
            assertNull("$state reported a fraction", frame.fraction)
        }
    }

    // ------------------------------------------------------------------
    // The real pipeline
    // ------------------------------------------------------------------

    /**
     * A registry that records what was registered, with no persistence.
     *
     * Implements the whole [ModelRegistry] interface rather than a subset, so the
     * download pipeline runs against the same surface it will meet in the app. The
     * unimplemented halves throw rather than returning defaults: a silent default here
     * would let a test pass against behaviour the real registry does not have.
     */
    private class RecordingRegistry : ModelRegistry {
        val registered = mutableListOf<InstalledModel>()
        private val profiles = mutableMapOf<String, MutableList<ModelProfile>>()
        private var active: String? = null

        override suspend fun list(): List<InstalledModel> = registered.toList()

        override suspend fun get(id: String): InstalledModel? = registered.firstOrNull { it.id == id }

        override suspend fun register(model: InstalledModel): InstalledModel {
            registered.removeAll { it.id == model.id }
            registered += model
            return model
        }

        override suspend fun update(model: InstalledModel): InstalledModel = register(model)

        override suspend fun remove(id: String) {
            registered.removeAll { it.id == id }
            profiles.remove(id)
            if (active == id) active = null
        }

        override suspend fun setActive(id: String?) {
            active = id
        }

        override suspend fun activeId(): String? = active

        override suspend fun profilesFor(modelId: String): List<ModelProfile> =
            profiles[modelId].orEmpty().toList()

        override suspend fun saveProfile(modelId: String, profile: ModelProfile): List<ModelProfile> {
            val forModel = profiles.getOrPut(modelId) { mutableListOf() }
            forModel.removeAll { it.id == profile.id }
            forModel += profile
            return forModel.toList()
        }

        override suspend fun removeProfile(modelId: String, profileId: String): List<ModelProfile> {
            val forModel = profiles[modelId] ?: return emptyList()
            forModel.removeAll { it.id == profileId }
            return forModel.toList()
        }
    }

    /**
     * A transport serving real bytes from memory, with optional range support.
     *
     * `honourRange = false` reproduces a server that ignores the request and sends the
     * whole body - the case where resuming would corrupt the file, and which the pipeline
     * has to detect.
     */
    private class ByteTransport(
        private val payload: ByteArray,
        private val honourRange: Boolean = true,
    ) : HttpTransport {
        var openedWithRange: Long? = null
        var openCount = 0

        override suspend fun getText(url: String, headers: Map<String, String>) =
            HttpTextResponse(404, "")

        override suspend fun openStream(url: String, rangeStart: Long?, rangeEnd: Long?): HttpStreamResponse {
            openCount++
            if (rangeStart != null) openedWithRange = rangeStart
            return if (rangeStart != null && honourRange) {
                HttpStreamResponse(
                    code = 206,
                    stream = ByteArrayInputStream(payload.copyOfRange(rangeStart.toInt(), payload.size)) as InputStream,
                    totalBytes = payload.size.toLong(),
                    supportsResume = true,
                )
            } else {
                HttpStreamResponse(
                    code = 200,
                    stream = ByteArrayInputStream(payload) as InputStream,
                    totalBytes = payload.size.toLong(),
                    supportsResume = honourRange,
                )
            }
        }
    }

    private fun catalogItem(
        id: String = "test-model",
        url: String = "https://example.invalid/test-model.gguf",
        sizeBytes: Long,
        sha256: String = "",
        architecture: String = "qwen2",
    ) = ModelCatalogItem(
        id = id,
        name = "Test Model",
        publisher = "test",
        architecture = architecture,
        sizeBytes = sizeBytes,
        download = DownloadMetadata(
            url = url,
            fileName = "test-model.gguf",
            sizeBytes = sizeBytes,
            sha256 = sha256,
        ),
    )

    private fun pipeline(
        transport: HttpTransport,
        registry: ModelRegistry,
        directory: File,
        availableBytes: Long = Long.MAX_VALUE,
    ) = HuggingFaceModelDownloads(
        transport = transport,
        storage = object : StorageProbe {
            override fun availableBytes(): Long = availableBytes
        },
        registry = registry,
        modelsDirectory = directory,
        storageSafetyMarginBytes = 0L,
        nowMs = { 0L },
    )

    private fun temporaryDirectory(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "charaly-test-$name-${System.nanoTime()}").apply {
            mkdirs()
        }

    /**
     * Builds a byte-for-byte minimal but genuinely valid GGUF header.
     *
     * ## Why the payload has to be a real GGUF
     *
     * The pipeline's install step *reads the file's own header* and refuses anything it
     * cannot classify - which is the entire point of "never present a partial or unusable
     * file as installed". A payload of arbitrary bytes therefore fails, correctly, at
     * install time with `UNSUPPORTED`, and the happy-path assertions would be testing the
     * failure branch.
     *
     * So this writes the real format:
     *
     * ```
     *   "GGUF"  u32 version = 3
     *   u64 tensor_count = 0
     *   u64 kv_count = 4
     *     "general.architecture"  string "qwen2"
     *     "general.file_type"     u32 15        (Q4_K_M)
     *     "qwen2.context_length"  u32 8192
     *     "qwen2.block_count"     u32 32
     * ```
     *
     * `qwen2` is a real registered architecture in the bundled engine, and a declared
     * context length is what promotes the verdict from MANUAL_IMPORT_ONLY to
     * CHARALY_READY. Both `block_count` and the context are included because the
     * classifier's checks are ordered - architecture, then context, then device fit - and
     * a fixture missing the context would reach READY with the wrong verdict text.
     *
     * Padded to the requested size with bytes after the header, which the reader ignores
     * because it stops at the metadata block.
     */
    private fun validGgufBytes(totalSize: Int, architecture: String = "qwen2"): ByteArray {
        val out = java.io.ByteArrayOutputStream()

        // Declared before use: Kotlin local functions are not hoisted.
        //
        // Little-endian, which is GGUF's byte order. Writing big-endian here produced
        // version 50331648 instead of 3 - a useful reminder that a test fixture has to
        // match the real format exactly, or it is testing nothing.
        fun writeU32(value: Long) {
            out.write(value.toInt())
            out.write((value ushr 8).toInt())
            out.write((value ushr 16).toInt())
            out.write((value ushr 24).toInt())
        }

        /**
         * Little-endian, low half first.
         *
         * Two bytes wrong here read as 7308890738324930560 rather than 2, which is the
         * kind of fixture bug that produces a confusing "no real model claims a billion
         * metadata entries" failure three layers away from the cause.
         */
        fun writeU64(value: Long) {
            writeU32(value and 0xFFFFFFFFL)
            writeU32((value ushr 32) and 0xFFFFFFFFL)
        }

        fun writeString(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            writeU64(bytes.size.toLong())
            out.write(bytes)
        }

        fun writeUint32Pair(key: String, value: Long) {
            writeString(key)
            writeU32(4L) // value type UINT32
            writeU32(value)
        }

        out.write("GGUF".toByteArray(Charsets.US_ASCII))
        writeU32(3L)            // version 3
        writeU64(0L)            // tensor count
        writeU64(4L)            // kv count

        writeString("general.architecture")
        writeU32(8L)            // value type STRING
        writeString(architecture)

        writeUint32Pair("general.file_type", 15L)   // Q4_K_M
        writeUint32Pair("qwen2.context_length", 8192L)
        writeUint32Pair("qwen2.block_count", 32L)

        val header = out.toByteArray()
        require(header.size <= totalSize) { "the requested size is too small for a GGUF header" }
        // Pad after the header. The reader stops at the end of the metadata block, so
        // this is inert - which is the point: it makes the file the right *size* without
        // pretending to contain weights.
        return header + ByteArray(totalSize - header.size)
    }

    /**
     * A download that completes is registered, moved out of `.part`, and verified.
     *
     * The three outcomes asserted here are the whole promise: real bytes on disk, no
     * temporary file left behind, and a registry entry that only exists because the
     * transfer finished.
     */
    @Test
    fun `a completed download is verified and registered`() = runTest {
        val dir = temporaryDirectory("complete")
        val payload = validGgufBytes(4096)
        val registry = RecordingRegistry()
        val downloads = pipeline(ByteTransport(payload), registry, dir)

        val item = catalogItem(sizeBytes = payload.size.toLong())
        val frames = downloads.download(item).toList()
        val final = frames.last()

        assertEquals(
            "the final frame should be READY but was ${final.state}: " +
                "${final.message} (${final.failure})",
            DownloadState.READY,
            final.state,
        )
        assertEquals("Ready for Charaly", final.message)
        assertEquals(1, registry.registered.size)
        assertEquals(payload.size.toLong(), registry.registered.single().sizeBytes)

        // The file is in place, and no .part survives a successful install.
        val installed = File(dir, "test-model.gguf")
        assertTrue("the model file was not moved into place", installed.exists())
        assertEquals(payload.size.toLong(), installed.length())
        assertFalse("a temporary file survived a successful install", File(dir, "test-model.gguf.part").exists())

        dir.deleteRecursively()
    }

    /**
     * A checksum mismatch discards the file rather than quarantining it.
     *
     * A corrupt GGUF can never become loadable, and leaving it behind consumes the exact
     * storage the retry needs. The user is told precisely what happened.
     */
    @Test
    fun `a checksum mismatch discards the file`() = runTest {
        val dir = temporaryDirectory("checksum")
        val payload = validGgufBytes(2048)
        val registry = RecordingRegistry()
        val downloads = pipeline(ByteTransport(payload), registry, dir)

        val wrongHash = "0".repeat(64)
        val frames = downloads.download(catalogItem(sizeBytes = payload.size.toLong(), sha256 = wrongHash)).toList()
        val final = frames.last()

        assertEquals(DownloadState.FAILED, final.state)
        assertNotNull("the failure must be explained", final.failure)
        assertTrue(final.failure is DownloadUnavailable.ChecksumMismatch)
        assertEquals(
            "a failed verification must not register a model",
            0,
            registry.registered.size,
        )
        assertFalse("a rejected file was left on disk", File(dir, "test-model.gguf.part").exists())
        assertFalse("a rejected file was moved into place", File(dir, "test-model.gguf").exists())

        dir.deleteRecursively()
    }

    /**
     * A matching checksum verifies.
     *
     * Without this, the mismatch test above would pass for the wrong reason - a pipeline
     * that never checked anything.
     */
    @Test
    fun `a matching checksum verifies`() = runTest {
        val dir = temporaryDirectory("hash-ok")
        val payload = validGgufBytes(1024)
        val registry = RecordingRegistry()
        val downloads = pipeline(ByteTransport(payload), registry, dir)

        val hash = MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { "%02x".format(it) }

        val frames = downloads.download(catalogItem(sizeBytes = payload.size.toLong(), sha256 = hash)).toList()
        assertEquals(DownloadState.READY, frames.last().state)
        assertTrue("a verified file should be marked verified", registry.registered.single().verified)

        dir.deleteRecursively()
    }

    /**
     * A pause resumes from the byte on disk, not from a remembered one.
     *
     * This is what makes a pause survive a process death: the offset is read back from
     * the file, so there is nothing in memory to lose.
     */
    @Test
    fun `a resume starts from the byte already on disk`() = runTest {
        val dir = temporaryDirectory("resume")
        val payload = validGgufBytes(8192)

        // Simulate an interrupted attempt: 3000 bytes already written.
        File(dir, "test-model.gguf.part").writeBytes(payload.copyOfRange(0, 3000))

        val registry = RecordingRegistry()
        val transport = ByteTransport(payload)
        val downloads = pipeline(transport, registry, dir)

        val frames = downloads.download(catalogItem(sizeBytes = payload.size.toLong())).toList()

        assertEquals(DownloadState.READY, frames.last().state)
        assertEquals(
            "the transfer should have resumed at byte 3000, not 0",
            3000L,
            transport.openedWithRange,
        )
        // And the result is the whole file, not a doubled-up partial.
        assertEquals(payload.size.toLong(), File(dir, "test-model.gguf").length())

        dir.deleteRecursively()
    }

    /**
     * A server that ignores the range is refused rather than corrupting the file.
     *
     * Appending a whole body to an existing partial would produce a file that is neither
     * the old bytes nor the new ones. The only safe moves are to restart or to stop, and
     * this is the stop.
     */
    @Test
    fun `a server ignoring the range is refused`() = runTest {
        val dir = temporaryDirectory("no-range")
        val payload = validGgufBytes(8192)
        File(dir, "test-model.gguf.part").writeBytes(payload.copyOfRange(0, 3000))

        val registry = RecordingRegistry()
        val downloads = pipeline(ByteTransport(payload, honourRange = false), registry, dir)

        val final = downloads.download(catalogItem(sizeBytes = payload.size.toLong())).toList().last()

        assertEquals(DownloadState.FAILED, final.state)
        assertTrue(
            "the failure should name the reason: ${final.failure}",
            final.failure is DownloadUnavailable.ResumeUnsupported,
        )
        assertEquals("a refused resume must not register anything", 0, registry.registered.size)

        dir.deleteRecursively()
    }

    /**
     * Not enough room is refused before a single byte moves.
     *
     * A 4.5 GB download into 3 GB of free space that fails *after* the user has waited is
     * a poor experience; failing in a second is a courtesy.
     */
    @Test
    fun `insufficient storage is refused before opening the transfer`() = runTest {
        val dir = temporaryDirectory("storage")
        val transport = ByteTransport(ByteArray(1000))
        val downloads = pipeline(transport, RecordingRegistry(), dir, availableBytes = 100L)

        val final = downloads.download(catalogItem(sizeBytes = 10_000_000L)).toList().last()

        assertEquals(DownloadState.FAILED, final.state)
        assertEquals(DownloadUnavailable.InsufficientStorage, final.failure)
        assertEquals(
            "the transport must not be opened at all",
            0,
            transport.openCount,
        )

        dir.deleteRecursively()
    }

    /**
     * A model the engine cannot load still downloads, and is labelled.
     *
     * The transfer is *not* gated on engine support: a valid GGUF for an
     * architecture this build lacks is a real file the user may want for a
     * newer engine, another engine, or to keep. The engine verdict is
     * recorded on the installed entry so the library shows ENGINE UNSUPPORTED
     * rather than promising a load that will fail.
     *
     * What is still refused is a file that is not a valid GGUF at all - that
     * is caught at install time from the bytes, never from the name.
     */
    @Test
    fun `an unloadable model downloads and records the engine verdict`() = runTest {
        val dir = temporaryDirectory("unsupported")
        // The fixture's own header must declare the unsupported
        // architecture: the install step classifies from the file,
        // never from the catalog entry's name field.
        val payload = validGgufBytes(
            totalSize = 1024,
            architecture = "definitely-not-an-architecture",
        )
        val transport = ByteTransport(payload)
        val registry = RecordingRegistry()
        val downloads = pipeline(transport, registry, dir)

        val unsupported = catalogItem(
            sizeBytes = payload.size.toLong(),
            architecture = "definitely-not-an-architecture",
        )
        val final = downloads.download(unsupported).toList().last()
        assertEquals(
            "a valid GGUF must still reach READY: ${final.state} ${final.message}",
            DownloadState.READY,
            final.state,
        )
        assertEquals("the transfer must happen, not be pre-refused", 1, transport.openCount)
        assertEquals("the model must be registered", 1, registry.registered.size)
        assertEquals(
            "the engine verdict must be recorded, not hidden",
            EngineSupport.UNKNOWN_ARCHITECTURE,
            registry.registered.single().compatibility.engineSupport,
        )

        dir.deleteRecursively()
    }

    /**
     * A model with no download source fails immediately, with a reason.
     *
     * No transfer, no invented progress - the same honest refusal the offline build uses.
     */
    @Test
    fun `a model with no source fails immediately`() = runTest {
        val dir = temporaryDirectory("no-source")
        val downloads = pipeline(ByteTransport(ByteArray(10)), RecordingRegistry(), dir)

        val item = catalogItem(sizeBytes = 10L).copy(download = null)
        val frames = downloads.download(item).toList()

        assertEquals(1, frames.size)
        assertEquals(DownloadState.FAILED, frames.single().state)

        dir.deleteRecursively()
    }

    /**
     * Downloads are available in this build.
     *
     * The core wires a real HTTPS transport, so `availability()` must be null. A
     * non-null answer here would mean the UI is either offering a dead button or hiding a
     * working one.
     */
    @Test
    fun `the pipeline reports downloads as available`() {
        val dir = temporaryDirectory("available")
        val downloads = pipeline(ByteTransport(ByteArray(10)), RecordingRegistry(), dir)
        assertNull("this build has a transport, so downloads are available", downloads.availability())
        dir.deleteRecursively()
    }
}
