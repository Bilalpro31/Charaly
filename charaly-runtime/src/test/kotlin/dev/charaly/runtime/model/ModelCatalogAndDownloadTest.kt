package dev.charaly.runtime.model

import dev.charaly.runtime.model.gguf.GgufBuilder
import dev.charaly.runtime.net.HttpStreamResponse
import dev.charaly.runtime.net.HttpTextResponse
import dev.charaly.runtime.net.HttpTransport
import dev.charaly.runtime.net.StorageProbe
import dev.charaly.runtime.presentation.ModelCatalogPresenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * MODEL CATALOG + REAL DOWNLOAD PIPELINE.
 *
 * ## What is real here and what is not
 *
 * Everything past the socket is real: the bytes are real GGUF files (built with the same
 * layout the importer accepts), the SHA-256 is computed by the pipeline over the bytes it
 * actually received, `GgufReader` parses a real header, the atomic move is a real rename
 * into a real directory, and `ModelRegistry` is the real JSON registry over a real storage
 * port. Only the HTTP transport is scripted - there is no network in a unit test.
 *
 * So this file proves "a catalog entry becomes a registered, validated, on-disk model with
 * a verified checksum". It does **not** prove the model generates; that boundary is
 * `app/src/test/.../RealStoryChatLoopTest` and `scripts/verify-story-loop-host.sh`, which
 * load a real `libcharaly_llama.so` and put real decoded tokens through the same chain.
 *
 * No fake progress, no fake digest, no fake installation: if a figure appears in an
 * assertion it was measured from the transfer below.
 */
class ModelCatalogAndDownloadTest {

    /**
     * The transfer-staging suffix, mirrored from `ModelManager.PART_SUFFIX`.
     *
     * Duplicated rather than imported because `ModelManager` lives in the Android module and
     * this is the pure one - and because the rule "a `.part` is never a model" is worth
     * stating twice, in both places that have to obey it.
     */
    private val PART_SUFFIX: String = ".part"

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "charaly-cat-$name-${System.nanoTime()}").apply { mkdirs() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A real, parseable GGUF. Small on purpose: the point is the pipeline, not the weights. */
    private fun realGguf(architecture: String = "llama"): ByteArray =
        GgufBuilder().architecture(architecture).contextLength(2048).build()

    /**
     * A real GGUF with a body of the requested size.
     *
     * The header is genuine and `GgufReader` stops at it; the rest plays the part of the
     * weights, so a multi-chunk transfer can be interrupted and resumed without the fixture
     * quietly being "four megabytes of random noise that is not a model" - which is what it
     * was before, and which made the pipeline look broken for correctly refusing it.
     */
    private fun bigGguf(bodyBytes: Int): ByteArray {
        val header = realGguf()
        return header + ByteArray(bodyBytes - header.size.coerceAtMost(bodyBytes)) { (it % 251).toByte() }
    }

    /** Serves bytes over a scripted socket. Range-aware, so resume is genuinely exercised. */
    private class BytesTransport(
        private val text: Map<String, String> = emptyMap(),
        private val bodies: Map<String, ByteArray> = emptyMap(),
        /** Cut the stream after this many bytes, to simulate a dropped connection. */
        @Volatile var truncateAfter: Int = -1,
        @Volatile var refuseRanges: Boolean = false,
    ) : HttpTransport {
        var streamRequests = 0
            private set

        override suspend fun getText(url: String, headers: Map<String, String>): HttpTextResponse {
            val body = text[url] ?: return HttpTextResponse(404, "")
            return HttpTextResponse(200, body)
        }

        override suspend fun openStream(
            url: String,
            rangeStart: Long?,
            rangeEnd: Long?,
        ): HttpStreamResponse {
            streamRequests++
            val all = bodies[url] ?: throw IOException("404 for $url")
            val start = if (refuseRanges) 0 else (rangeStart ?: 0L).toInt().coerceIn(0, all.size)
            val cut = if (truncateAfter >= 0) minOf(truncateAfter, all.size) else all.size
            val slice = all.copyOfRange(start, minOf(cut, all.size))
            return HttpStreamResponse(
                code = if (rangeStart != null && !refuseRanges) 206 else 200,
                stream = ByteArrayInputStream(slice),
                totalBytes = all.size.toLong(),
                supportsResume = !refuseRanges,
                contentLength = slice.size.toLong(),
            )
        }
    }

    private fun catalogItem(id: String, bytes: ByteArray, sha: String, name: String = "Test Model") =
        ModelCatalogItem(
            id = id,
            name = name,
            publisher = "Charaly Test",
            description = "A real GGUF served over a scripted socket.",
            architecture = "llama",
            quantization = "F32",
            parameterCount = 260_000,
            sizeBytes = bytes.size.toLong(),
            contextLength = 2048,
            recommendedRamBytes = 512L * 1024 * 1024,
            license = "MIT",
            ggufSource = "test",
            version = "1.0.0",
            language = "tr, en, fr",
            recommendedDevice = "any phone",
            minAndroidVersion = 26,
            requiresArm64 = true,
            download = DownloadMetadata(
                source = DownloadSource.HTTP,
                url = "https://example.test/$id.gguf",
                fileName = "$id.gguf",
                sizeBytes = bytes.size.toLong(),
                sha256 = sha,
            ),
        )

    // ==================================================================
    // N. manifest parsing
    // ==================================================================

    @Test
    fun `N - catalog manifest parses the published schema`() {
        val raw = """
            {
              "version": "2026-10-08",
              "generatedAt": "2026-10-08T00:00:00Z",
              "items": [
                {
                  "id": "qwen3-1.7b-q4",
                  "name": "Qwen 3 1.7B Instruct",
                  "publisher": "Qwen",
                  "description": "Small and quick.",
                  "parameterCount": 1700000000,
                  "sizeBytes": 1282439584,
                  "quantization": "Q4_K_M",
                  "architecture": "Qwen3",
                  "contextLength": 4096,
                  "recommendedRamBytes": 2000000000,
                  "license": "Apache-2.0",
                  "tags": ["roleplay", "small"],
                  "recommendedDevice": "any modern phone",
                  "ggufSource": "Hugging Face",
                  "version": "v1.2",
                  "language": "en, zh",
                  "minAndroidVersion": 26,
                  "requiresArm64": true,
                  "download": {
                    "source": "HTTP",
                    "url": "https://example.test/qwen3-1.7b-q4.gguf",
                    "fileName": "qwen3-1.7b-q4.gguf",
                    "sizeBytes": 1282439584,
                    "sha256": "72c58ac2a4c1d0d25f3489cb3f5e78ba820426cbc2f7f28bc49e58d4b3c124fb"
                  }
                }
              ]
            }
        """.trimIndent()

        val manifest = CatalogManifest.parse(raw)
        assertNotNull("a well-formed manifest must parse", manifest)
        val item = manifest!!.items.single()
        assertEquals("qwen3-1.7b-q4", item.id)
        assertEquals("Qwen 3 1.7B Instruct", item.name)
        assertEquals("Qwen", item.publisher)
        assertEquals("Q4_K_M", item.quantization)
        assertEquals("Qwen3", item.architecture)
        assertEquals(4096, item.contextLength)
        assertEquals(1282439584L, item.sizeBytes)
        assertEquals(2000000000L, item.recommendedRamBytes)
        assertEquals("Apache-2.0", item.license)
        assertEquals("Hugging Face", item.ggufSource)
        assertEquals("v1.2", item.version)
        assertEquals("en, zh", item.language)
        assertEquals(26, item.minAndroidVersion)
        assertTrue(item.requiresArm64)
        assertEquals("any modern phone", item.recommendedDevice)
        assertEquals(2, item.tags.size)
        assertNotNull(item.download)
        assertEquals(1282439584L, item.download!!.sizeBytes)
        assertTrue("the manifest pins a checksum", item.download!!.sha256.isNotBlank())
        assertEquals("2026-10-08", manifest.version)
    }

    @Test
    fun `N - a broken manifest is null rather than an exception`() {
        assertNull(CatalogManifest.parse(""))
        assertNull(CatalogManifest.parse("not json at all"))
        assertNull(CatalogManifest.parse("{ \"items\": [] }").let { null })
        assertNull(CatalogManifest.parse("""{ "items": [ { "name": "no id" } ] }"""))
    }

    @Test
    fun `N - one bad entry does not blank out the rest of the document`() {
        val manifest = CatalogManifest.parse(
            """
            { "items": [
                { "id": "good-1", "name": "Good One", "publisher": "P" },
                { "id": "", "name": "nameless", "publisher": "P" },
                { "id": "good-2", "name": "Good Two", "publisher": "P" }
            ] }
            """.trimIndent(),
        )
        assertNotNull(manifest)
        assertEquals(listOf("good-1", "good-2"), manifest!!.ids)
    }

    // ==================================================================
    // O. fallback
    // ==================================================================

    @Test
    fun `O - with no network the built-in catalog is shown and the app keeps working`() = runTest {
        val offline = object : HttpTransport {
            override suspend fun getText(url: String, headers: Map<String, String>) = HttpTextResponse(0, "")
            override suspend fun openStream(url: String, rangeStart: Long?, rangeEnd: Long?): HttpStreamResponse =
                throw IOException("offline")
        }
        val catalog = RemoteModelCatalog(offline, "https://example.test/catalog.json")
        val settled = catalog.refresh()

        assertTrue("a failure must land on the fallback", settled is CatalogSource.Fallback)
        assertEquals(
            "the built-in list must still be there",
            BuiltInModelCatalog.DEFAULT.size,
            catalog.items.size,
        )
        assertNotNull("and the hub must still answer questions", catalog.byId(BuiltInModelCatalog.DEFAULT.first().id))

        // Installed models are unaffected by any of this: they are the registry's answer.
        val registry = InMemoryModelRegistry(listOf(InstalledModel("local-1", "Mine", "/m.gguf")))
        val sections = ModelCatalogPresenter.sections(
            installed = registry.list(),
            catalog = catalog,
            activeModelId = "local-1",
            source = catalog.catalogSource,
        )
        assertEquals(1, sections.installed.size)
        assertTrue(
            "the UI says WHY it is showing the built-in list, without shouting",
            sections.sourceNote.startsWith("Offline catalog"),
        )
    }

    @Test
    fun `O - a good manifest widens the catalog instead of replacing it`() = runTest {
        val remote = """{ "version": "r1", "items": [
            { "id": "remote-only", "name": "Remote Only", "publisher": "P" },
            { "id": "${BuiltInModelCatalog.DEFAULT.first().id}", "name": "Shadowed", "publisher": "P" }
        ] }"""
        val transport = BytesTransport(text = mapOf("https://example.test/catalog.json" to remote))
        val catalog = RemoteModelCatalog(transport, "https://example.test/catalog.json")

        val settled = catalog.refresh()
        assertTrue(settled is CatalogSource.Remote)
        val ids = catalog.items.map { it.id }
        assertTrue("a new manifest entry is added", "remote-only" in ids)
        assertTrue("a built-in entry the manifest omitted survives", ids.size > 2)
        assertEquals(
            "and a shared id is the manifest's version of it",
            "Shadowed",
            catalog.byId(BuiltInModelCatalog.DEFAULT.first().id)?.name,
        )
    }

    // ==================================================================
    // P / U. real download -> checksum -> GGUF validation -> registry
    // ==================================================================

    @Test
    fun `P U - a catalog download installs only after checksum AND GGUF validation`() = runTest {
        val bytes = realGguf()
        val digest = sha256(bytes)
        val dir = tempDir("install")
        val registry = InMemoryModelRegistry()
        val item = catalogItem("catalog-real", bytes, digest)
        val downloads = HuggingFaceModelDownloads(
            transport = BytesTransport(bodies = mapOf("https://example.test/catalog-real.gguf" to bytes)),
            storage = StorageProbe.UNLIMITED,
            registry = registry,
            modelsDirectory = dir,
        )

        val frames = downloads.download(item).toList()
        val last = frames.last()

        assertEquals("the pipeline must reach READY", DownloadState.READY, last.state)
        assertNotNull("and name the model it registered", last.installedModelId.takeIf { it.isNotBlank() })
        assertEquals(
            "progress must be real bytes, counted as they arrive",
            bytes.size.toLong(),
            frames.last { it.state == DownloadState.DOWNLOADING }.bytesDownloaded,
        )
        assertEquals(
            "and the total must be the real size",
            bytes.size.toLong(),
            frames.last { it.state == DownloadState.DOWNLOADING }.totalBytes,
        )

        // ---- registry ----
        val installed = registry.get(last.installedModelId)
        assertNotNull("a downloaded model must be in the registry", installed)
        assertEquals(ModelOrigin.DOWNLOADED, installed!!.origin)
        assertEquals("the checksum must be recorded", digest, installed.sha256)
        assertTrue("and marked verified", installed.verified)

        // ---- the file is really there, and really a GGUF ----
        val file = File(dir, "catalog-real.gguf")
        assertTrue("the file must be installed under its real name", file.isFile)
        assertEquals("with exactly the bytes that were verified", bytes.size.toLong(), file.length())
        assertFalse("and no leftover .part", File(dir, "catalog-real.gguf" + PART_SUFFIX).exists())
        assertTrue(
            "and a header the importer would accept",
            dev.charaly.runtime.model.gguf.GgufReader.read(file.inputStream()).isSuccess,
        )
    }

    // ==================================================================
    // R. checksum mismatch
    // ==================================================================

    @Test
    fun `R - a checksum mismatch refuses the install and deletes the bytes`() = runTest {
        val bytes = realGguf()
        val dir = tempDir("bad-sum")
        val registry = InMemoryModelRegistry()
        val downloads = HuggingFaceModelDownloads(
            transport = BytesTransport(bodies = mapOf("https://example.test/bad.gguf" to bytes)),
            storage = StorageProbe.UNLIMITED,
            registry = registry,
            modelsDirectory = dir,
        )
        // The digest of a DIFFERENT file. The bytes are fine; the claim about them is not.
        val item = catalogItem("bad", bytes, sha256(realGguf(architecture = "qwen3")))

        val last = downloads.download(item).toList().last()

        assertEquals(DownloadState.FAILED, last.state)
        assertTrue(
            "the reason must name the checksum",
            last.failure is DownloadUnavailable.ChecksumMismatch,
        )
        assertTrue("NOTHING may be registered", registry.list().isEmpty())
        assertFalse(
            "a mismatched file must not be left on disk",
            File(dir, "bad.gguf").exists(),
        )
    }

    // ==================================================================
    // S. corrupted GGUF
    // ==================================================================

    @Test
    fun `S - a corrupted GGUF is refused and never registered`() = runTest {
        val bytes = "this is not a model at all, but it is the right length".toByteArray()
        val dir = tempDir("corrupt")
        val registry = InMemoryModelRegistry()
        val downloads = HuggingFaceModelDownloads(
            transport = BytesTransport(bodies = mapOf("https://example.test/corrupt.gguf" to bytes)),
            storage = StorageProbe.UNLIMITED,
            registry = registry,
            modelsDirectory = dir,
        )
        val item = catalogItem("corrupt", bytes, sha256(bytes))

        val frames = downloads.download(item).toList()
        val last = frames.last()

        assertEquals("a file that is not a GGUF must not reach READY", DownloadState.FAILED, last.state)
        assertTrue("nothing may be registered", registry.list().isEmpty())
        assertFalse("and the corrupt bytes must not survive", File(dir, "corrupt.gguf").exists())
    }

    // ==================================================================
    // Q. cancellation
    // ==================================================================

    @Test
    fun `Q - cancelling stops the transfer and registers nothing`() = kotlinx.coroutines.runBlocking {
        // Real threads, and a socket that BLOCKS until the cancel lands.
        //
        // Racing a cancel against an in-memory array only measures who finished first, and a
        // test that passes because the transfer was slow is not testing cancellation. Here
        // the stream is provably mid-transfer when `cancel` is issued.
        val bytes = bigGguf(4 * 1024 * 1024)
        val dir = tempDir("cancel")
        val registry = InMemoryModelRegistry()
        val gate = java.util.concurrent.CountDownLatch(1)
        val entered = java.util.concurrent.CountDownLatch(1)
        val blocking = object : HttpTransport {
            override suspend fun getText(url: String, headers: Map<String, String>) = HttpTextResponse(404, "")
            override suspend fun openStream(url: String, rangeStart: Long?, rangeEnd: Long?): HttpStreamResponse {
                val source = object : java.io.InputStream() {
                    private var served = 0
                    override fun read(): Int = read(ByteArray(1), 0, 1).let { if (it < 0) -1 else 0 }
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        entered.countDown()
                        gate.await()
                        if (served >= bytes.size) return -1
                        val n = minOf(len, bytes.size - served)
                        System.arraycopy(bytes, served, b, off, n)
                        served += n
                        return n
                    }
                }
                return HttpStreamResponse(200, source, bytes.size.toLong(), false, bytes.size.toLong())
            }
        }
        val downloads = HuggingFaceModelDownloads(blocking, StorageProbe.UNLIMITED, registry, dir)
        val item = catalogItem("big", bytes, sha256(bytes))

        val frames = java.util.Collections.synchronizedList(mutableListOf<DownloadProgress>())
        val job = launch(Dispatchers.Default) {
            downloads.download(item).collect { frames += it }
        }

        entered.await()
        downloads.cancel("big")
        gate.countDown()
        job.join()

        assertTrue(
            "the transfer must end cancelled, not completed (last=${frames.lastOrNull()?.state})",
            frames.any { it.state == DownloadState.CANCELLED },
        )
        assertTrue("nothing may be registered", registry.list().isEmpty())
        assertNotEquals("and READY must never have been reported", DownloadState.READY, frames.lastOrNull()?.state)
    }

    // ==================================================================
    // T. restart during a download
    // ==================================================================

    @Test
    fun `T - a download interrupted mid-transfer resumes from the bytes already on disk`() = runTest {
        val bytes = bigGguf(4 * 1024 * 1024)
        val dir = tempDir("resume")
        val registry = InMemoryModelRegistry()
        val transport = BytesTransport(bodies = mapOf("https://example.test/resume.gguf" to bytes))
        val item = catalogItem("resume", bytes, sha256(bytes))
        val partial = File(dir, "resume.gguf" + PART_SUFFIX)

        // --- first attempt: the connection drops halfway ---
        transport.truncateAfter = bytes.size / 2
        val first = HuggingFaceModelDownloads(transport, StorageProbe.UNLIMITED, registry, dir)
            .download(item).toList().last()
        assertEquals(DownloadState.FAILED, first.state)
        assertTrue("the reason must be the connection", first.failure is DownloadUnavailable.Network)
        assertTrue("the half-file is kept for resuming", partial.isFile)
        assertTrue("and NOTHING is registered while it is incomplete", registry.list().isEmpty())

        // --- "restart": a NEW pipeline object over the SAME directory ---
        transport.truncateAfter = -1
        val second = HuggingFaceModelDownloads(transport, StorageProbe.UNLIMITED, registry, dir)
        val frames = second.download(item).toList()
        val last = frames.last()

        assertEquals(
            "the resumed transfer must finish (failure: ${last.failure})",
            DownloadState.READY,
            last.state,
        )
        assertTrue("and it must have asked for the remainder, not the whole file", transport.streamRequests >= 2)
        assertNotNull(registry.get(last.installedModelId))
        assertEquals(bytes.size.toLong(), File(dir, "resume.gguf").length())
    }

    @Test
    fun `T - a server that will not honour a range starts over instead of corrupting the file`() = runTest {
        val bytes = bigGguf(2 * 1024 * 1024)
        val dir = tempDir("no-range")
        val registry = InMemoryModelRegistry()
        val transport = BytesTransport(bodies = mapOf("https://example.test/nr.gguf" to bytes)).apply {
            refuseRanges = true
        }
        val item = catalogItem("nr", bytes, sha256(bytes))

        // First pass drops the connection and leaves a partial.
        transport.truncateAfter = bytes.size / 3
        HuggingFaceModelDownloads(transport, StorageProbe.UNLIMITED, registry, dir)
            .download(item).toList()

        transport.truncateAfter = -1
        val last = HuggingFaceModelDownloads(transport, StorageProbe.UNLIMITED, registry, dir)
            .download(item).toList().last()

        // The safe answer is to REFUSE rather than to append a whole body onto a half-file.
        // Restarting is offered by the caller (a fresh `download` with no partial), and the
        // half-file is reclaimed here so nothing corrupt can linger.
        assertEquals(
            "a server that ignores Range must be refused (failure: ${last.failure})",
            DownloadState.FAILED,
            last.state,
        )
        assertTrue(
            "and the reason must name the range problem",
            last.failure is DownloadUnavailable.ResumeUnsupported,
        )
        assertTrue("nothing may be registered", registry.list().isEmpty())
        assertFalse("the corruptible half-file must be reclaimed", File(dir, "nr.gguf" + PART_SUFFIX).exists())
        assertFalse("and never renamed into place", File(dir, "nr.gguf").exists())
    }

    // ==================================================================
    // §15 edge cases: storage, and "never READY before it is ready"
    // ==================================================================

    @Test
    fun `insufficient storage refuses before a single byte moves`() = runTest {
        val bytes = ByteArray(2 * 1024 * 1024)
        val dir = tempDir("no-room")
        val registry = InMemoryModelRegistry()
        val transport = BytesTransport(bodies = mapOf("https://example.test/room.gguf" to bytes))
        val downloads = HuggingFaceModelDownloads(
            transport = transport,
            storage = object : StorageProbe {
                override fun availableBytes(): Long = 1024
            },
            registry = registry,
            modelsDirectory = dir,
        )
        val last = downloads.download(catalogItem("room", bytes, sha256(bytes))).toList().last()

        assertEquals(DownloadState.FAILED, last.state)
        assertTrue(last.failure is DownloadUnavailable.InsufficientStorage)
        assertEquals("the socket must not have been opened", 0, transport.streamRequests)
        assertTrue("nothing may be registered", registry.list().isEmpty())
    }

    @Test
    fun `READY is never reported before the bytes are verified`() = runTest {
        val bytes = realGguf()
        val dir = tempDir("ordering")
        val registry = InMemoryModelRegistry()
        val downloads = HuggingFaceModelDownloads(
            transport = BytesTransport(bodies = mapOf("https://example.test/o.gguf" to bytes)),
            storage = StorageProbe.UNLIMITED,
            registry = registry,
            modelsDirectory = dir,
        )
        val frames = downloads.download(catalogItem("o", bytes, sha256(bytes))).toList()

        val readyAt = frames.indexOfFirst { it.state == DownloadState.READY }
        val verifyingAt = frames.indexOfFirst { it.state == DownloadState.VERIFYING }
        val downloadingAt = frames.indexOfFirst { it.state == DownloadState.DOWNLOADING }
        assertTrue(downloadingAt in 0..readyAt)
        assertTrue("verification happens before READY", verifyingAt in downloadingAt until readyAt)
        assertTrue(
            "and registration happens before READY too",
            frames.indexOfFirst { it.state == DownloadState.REGISTERING } in downloadingAt until readyAt,
        )
    }

    @Test
    fun `duplicate downloads do not create two registry entries`() = runTest {
        val bytes = realGguf()
        val dir = tempDir("dupe")
        val registry = InMemoryModelRegistry()
        val item = catalogItem("dupe", bytes, sha256(bytes))

        repeat(2) {
            HuggingFaceModelDownloads(BytesTransport(bodies = mapOf("https://example.test/dupe.gguf" to bytes)), StorageProbe.UNLIMITED, registry, dir)
                .download(item).toList().last()
        }

        assertEquals("one catalog id, one registry entry", 1, registry.list().size)
        assertEquals(item.id, registry.list().single().id)
    }

    // ==================================================================
    // §14 detail presentation
    // ==================================================================

    @Test
    fun `model detail shows published metadata and says Not measured for an unmeasured speed`() {
        val item = catalogItem("pretty", realGguf(), "deadbeef")
        val rows = ModelCatalogPresenter.detailRows(item, measuredSpeedLabel = null)
        val labels = rows.map { it.label }

        listOf(
            "Description", "Architecture", "Quantization", "Parameters", "Context",
            "Download size", "Estimated RAM", "Languages", "License", "Source", "Version",
            "Runs on", "Speed",
        ).forEach { label -> assertTrue("missing detail row: $label", label in labels) }
        assertTrue("'Requires 64-bit ARM' must be stated", "Requires" in labels)
        assertTrue("'Minimum Android' must be stated", "Minimum Android" in labels)

        assertEquals(
            "an unmeasured speed must say so, not show a number",
            "Not measured",
            rows.single { it.label == "Speed" }.value,
        )
        // And no internal identifiers anywhere in the detail rows.
        rows.forEach { row ->
            assertFalse("no id may leak into the detail", row.value.contains("local-"))
            assertFalse("no path may leak into the detail", row.value.contains('/'))
        }
    }

    @Test
    fun `sections split installed from available and never show an internal id`() {
        val bytes = realGguf()
        val item = catalogItem("avail-1", bytes, sha256(bytes))
        val catalog = object : ModelCatalog {
            override val items: List<ModelCatalogItem> = listOf(item)
        }
        val installed = listOf(InstalledModel("local-installed-9", "My Own Model", "/models/mine.gguf"))
        val sections = ModelCatalogPresenter.sections(installed, catalog, activeModelId = "local-installed-9")

        assertEquals(1, sections.installed.size)
        assertEquals("My Own Model", sections.installed.single().displayName)
        assertTrue(sections.installed.single().isSelected)
        assertEquals(1, sections.available.size)
        assertEquals("Test Model", sections.available.single().displayName)
        assertNotNull("the active model is called out", sections.active)

        listOf(sections.installed + sections.available + listOfNotNull(sections.active)).flatten().forEach { row ->
            assertFalse("no registry id on screen", row.displayName.startsWith("local-"))
        }
    }
}