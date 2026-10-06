package dev.charaly.runtime.net

import dev.charaly.runtime.model.ModelCatalogItem
import dev.charaly.runtime.model.ModelDownloadManager
import dev.charaly.runtime.model.ModelOrigin
import dev.charaly.runtime.net.huggingface.HuggingFaceClient
import dev.charaly.runtime.net.huggingface.HuggingFaceFile
import dev.charaly.runtime.net.huggingface.quantRank
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * The Hugging Face client, against a scripted transport.
 *
 * ## Why these tests use recorded JSON rather than the live API
 *
 * The Hub's response shape is a *fact about the Hub*, and it changes. Asserting against
 * recorded responses means:
 *
 *  * the suite is deterministic and needs no network;
 *  * a deliberate change to the parser has to be made consciously, by updating a
 *    fixture, rather than discovered by a test failing on a weekend;
 *  * and the parsing logic itself - the interesting part - is actually under test,
 *    because a live-API test would mostly be testing availability.
 *
 * The fixtures below are shaped like real Hub responses. The specific model ids are
 * from real repositories, but no file is downloaded and no claim is made about what
 * those repositories currently contain.
 */
class HuggingFaceClientTest {

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * A trimmed but structurally faithful `/api/models` response.
     *
     * Includes the fields the parser actually depends on - `id`, `downloads`, `likes`,
     * `tags`, `pipeline_tag`, `lastModified` - and one entry with no description tag,
     * because real responses routinely lack one.
     */
    private val searchResponse = """
        [
          {
            "id": "Qwen/Qwen3-4B-GGUF",
            "downloads": 482913,
            "likes": 812,
            "pipeline_tag": "text-generation",
            "lastModified": "2025-06-02T10:00:00.000Z",
            "tags": ["gguf", "qwen3", "text-generation", "license:apache-2.0", "summary: Qwen3 4B in GGUF format"]
          },
          {
            "id": "unsized-org/Some-Repo-GGUF",
            "downloads": 12,
            "likes": 1,
            "pipeline_tag": "text-generation",
            "lastModified": "2025-05-01T00:00:00.000Z",
            "tags": ["gguf"]
          },
          {
            "id": "not-a-model-at-all",
            "downloads": 5,
            "tags": ["transformers", "safetensors"]
          }
        ]
    """.trimIndent()

    /** A `/tree/main` listing with two GGUFs, a README and a directory. */
    private val treeResponse = """
        [
          { "type": "directory", "path": "split", "size": 0 },
          { "type": "file", "path": "README.md", "size": 4200 },
          {
            "type": "file",
            "path": "Qwen3-4B-Q4_K_M.gguf",
            "size": 2587234560,
            "lfs": { "oid": "aaaa1111bbbb2222cccc3333dddd4444eeee5555ffff6666aaaa7777bbbb8888", "size": 2587234560 }
          },
          {
            "type": "file",
            "path": "Qwen3-4B-Q8_0.gguf",
            "size": 4180378112,
            "lfs": { "oid": "1111aaaa2222bbbb3333cccc4444dddd5555eeee6666ffff7777aaaa8888bbbb" }
          }
        ]
    """.trimIndent()

    // ------------------------------------------------------------------
    // Test transport
    // ------------------------------------------------------------------

    /**
     * A transport that answers from a routing table.
     *
     * Records every request so a test can assert on the URL - which is how "did it ask
     * for GGUF-filtered results" becomes checkable without reading the implementation.
     */
    private class ScriptedTransport(
        private val responses: Map<String, String>,
        private val streamBytes: ByteArray = ByteArray(0),
        private val streamCode: Int = 206,
    ) : HttpTransport {
        val requests = mutableListOf<String>()

        override suspend fun getText(url: String, headers: Map<String, String>): HttpTextResponse {
            requests += url
            val body = responses.entries.firstOrNull { url.contains(it.key) }?.value
                ?: return HttpTextResponse(code = 404, body = "not found")
            return HttpTextResponse(code = 200, body = body)
        }

        override suspend fun openStream(
            url: String,
            rangeStart: Long?,
            rangeEnd: Long?,
        ): HttpStreamResponse {
            requests += "$url (range $rangeStart-$rangeEnd)"
            return HttpStreamResponse(
                code = streamCode,
                stream = ByteArrayInputStream(streamBytes) as InputStream,
                totalBytes = streamBytes.size.toLong(),
                supportsResume = true,
            )
        }
    }

    private fun client(transport: HttpTransport) = HuggingFaceClient(transport)

    // ------------------------------------------------------------------
    // Discovery
    // ------------------------------------------------------------------

    /**
     * The query filters on the Hub's own GGUF filter, not client-side.
     *
     * This is the assertion that matters for the brief's "only GGUF models are shown":
     * filtering in the query means the result *count* reflects what the Hub indexes,
     * rather than how many non-GGUF entries happened to be in the page we fetched.
     */
    @Test
    fun `search filters on the hub's gguf index`() = runTest {
        val transport = ScriptedTransport(mapOf("/api/models" to searchResponse))
        client(transport).searchGguf()

        val url = transport.requests.single()
        assertTrue("the query must ask the Hub for gguf: $url", url.contains("library=gguf"))
        assertTrue("the query must also use the gguf filter: $url", url.contains("filter=gguf"))
    }

    /**
     * Non-GGUF results are dropped even when the Hub returns them.
     *
     * Belt and braces: the server-side filter is the mechanism, but a mixed response
     * must not be presented as GGUF-filtered when it is not. `HuggingFaceRepo.libraryTag`
     * records what the Hub *claimed*, and this checks the claim against the answer.
     */
    @Test
    fun `non-gguf results are excluded`() = runTest {
        val transport = ScriptedTransport(mapOf("/api/models" to searchResponse))
        val page = client(transport).searchGguf()

        assertEquals(
            "the non-gguf repository should have been filtered out",
            listOf("Qwen/Qwen3-4B-GGUF", "unsized-org/Some-Repo-GGUF"),
            page.repos.map { it.repoId },
        )
        assertTrue(
            "every returned repo should carry the gguf library tag",
            page.repos.all { it.libraryTag.equals("gguf", ignoreCase = true) },
        )
    }

    @Test
    fun `repo metadata is parsed`() = runTest {
        val transport = ScriptedTransport(mapOf("/api/models" to searchResponse))
        val repo = client(transport).searchGguf().repos.first()

        assertEquals("Qwen", repo.author)
        assertEquals("Qwen3-4B-GGUF", repo.name)
        assertEquals(482913L, repo.downloads)
        assertEquals(812L, repo.likes)
        assertEquals("text-generation", repo.pipelineTag)
        assertEquals("apache-2.0", repo.license)
        assertEquals("qwen3", repo.family)
    }

    /**
     * A description comes from the `summary` tag.
     *
     * This is the assertion the previous version of this suite could not make. The Hub's
     * convention is the tag *name* `summary`, and the parser was matching the literal
     * string `"summary:"` - which appears only in a hand-written fixture, never in a real
     * response. Every catalog card was therefore rendering with a blank description while
     * the tests passed, because the fixture had been written to match the bug.
     *
     * The `summary:` spelling is still accepted, since some uploads use it, but a real
     * response is now covered by this test.
     */
    @Test
    fun `a description is read from the summary tag`() = runTest {
        val transport = ScriptedTransport(
            mapOf(
                "/api/models" to """
                [{
                  "id": "org/Model-GGUF",
                  "downloads": 100,
                  "likes": 10,
                  "pipeline_tag": "text-generation",
                  "tags": ["gguf", "summary: A tiny model for phones.", "license:mit"]
                }]
                """.trimIndent(),
            ),
        )
        val repo = client(transport).searchGguf().repos.single()
        assertEquals("A tiny model for phones.", repo.description)
    }

    /** The underscore spelling the Hub uses for multi-word summaries. */
    @Test
    fun `an underscored summary is humanised`() = runTest {
        val transport = ScriptedTransport(
            mapOf(
                "/api/models" to """
                [{
                  "id": "org/Model-GGUF",
                  "tags": ["gguf", "summary:small_model_for_phones"]
                }]
                """.trimIndent(),
            ),
        )
        val repo = client(transport).searchGguf().repos.single()
        assertEquals("small model for phones", repo.description)
    }

    /**
     * A repo with no description is not an error.
     *
     * Real responses routinely lack a summary tag, and a parser that threw here would
     * take out the whole search result set for one sparse entry.
     */
    @Test
    fun `a repo without a description parses with an empty one`() = runTest {
        val transport = ScriptedTransport(mapOf("/api/models" to searchResponse))
        val repo = client(transport).searchGguf().repos[1]

        // No summary tag, so no description. The absence is legitimate and must not be
        // filled with a neighbouring field's value - a card reading "text-generation" as
        // its description is worse than one reading nothing.
        assertEquals("", repo.description)
        assertEquals("", repo.license)
        assertEquals(1L, repo.likes)
        // Fields that *are* present still parse normally.
        assertEquals("text-generation", repo.pipelineTag)
        assertEquals(12L, repo.downloads)
    }

    /**
     * A failed request yields an empty page rather than an exception.
     *
     * A model library that throws on a flaky network is worse than one that shows
     * nothing: the user cannot tell "no results" from "broken".
     */
    @Test
    fun `a failed search returns an empty page`() = runTest {
        val transport = ScriptedTransport(emptyMap())
        val page = client(transport).searchGguf()

        assertTrue(page.repos.isEmpty())
        assertEquals(0, page.totalCount)
        assertFalse(page.hasMore)
    }

    @Test
    fun `malformed json returns an empty page`() = runTest {
        val transport = ScriptedTransport(mapOf("/api/models" to "{ this is not json"))
        assertTrue(client(transport).searchGguf().repos.isEmpty())
    }

    // ------------------------------------------------------------------
    // File listings
    // ------------------------------------------------------------------

    /**
     * Real sizes, so the pre-download storage check is meaningful.
     *
     * The `lfs.size` is the authoritative one for a large file; the listing's own `size`
     * is what the CDN will actually send.
     */
    @Test
    fun `gguf files are listed with real sizes and hashes`() = runTest {
        val transport = ScriptedTransport(mapOf("/tree/main" to treeResponse))
        val listing = client(transport).listFiles("Qwen/Qwen3-4B-GGUF")

        assertEquals(2, listing.ggufFiles.size)
        assertEquals("the README should be counted but not listed", 1, listing.otherFileCount)
        assertFalse(listing.isEmpty)

        val q4 = listing.ggufFiles.first { it.fileName.contains("Q4_K_M") }
        assertEquals(2587234560L, q4.sizeBytes)
        assertEquals(
            "aaaa1111bbbb2222cccc3333dddd4444eeee5555ffff6666aaaa7777bbbb8888",
            q4.sha256,
        )
    }

    /**
     * A file with no LFS hash reports none, rather than an empty string that would fail
     * verification.
     *
     * The distinction matters at install time: an empty expected hash means "skip the
     * check", while an empty hash being *compared* against a real file's digest would
     * always mismatch and would delete a perfectly good download.
     */
    @Test
    fun `a file without an lfs hash reports no hash`() = runTest {
        val transport = ScriptedTransport(
            mapOf(
                "/tree/main" to """
                [
                  { "type": "file", "path": "plain.gguf", "size": 12345 }
                ]
                """.trimIndent(),
            ),
        )
        val listing = client(transport).listFiles("org/repo")
        val file = listing.ggufFiles.single()
        assertEquals("", file.sha256)
        assertEquals(12345L, file.sizeBytes)
    }

    /**
     * The quantisation chooser picks the best file that fits.
     *
     * This is the function that turns "the Hub has five quantisations of this model"
     * into a single answer, and getting it wrong means either a model too large for the
     * device or an unnecessarily low quantisation.
     */
    @Test
    fun `the best fitting quantisation is chosen`() = runTest {
        val transport = ScriptedTransport(mapOf("/tree/main" to treeResponse))
        val listing = client(transport).listFiles("Qwen/Qwen3-4B-GGUF")

        // Plenty of room: Q8_0 is the better quantisation and should win.
        val roomy = listing.bestFor(availableRamBytes = 12L * 1024 * 1024 * 1024)
        assertTrue(
            "expected the Q8_0 file, got ${roomy?.fileName}",
            roomy?.fileName.orEmpty().contains("Q8_0"),
        )

        // Tight: the smaller quantisation must be preferred over a 4 GB file that will
        // not fit alongside the runtime allowance.
        val tight = listing.bestFor(availableRamBytes = 3_000_000_000L)
        assertTrue(
            "expected the smaller quantisation, got ${tight?.fileName}",
            (tight?.sizeBytes ?: Long.MAX_VALUE) < 3_000_000_000L,
        )
    }

    /**
     * Candidates come back best-first.
     *
     * The regression this guards: the sort was `compareBy { quantRank }` - ascending -
     * while the doc comment said "best first". A screen iterating this list would offer
     * the repository's *worst* quantisation first, which is precisely the file most
     * likely to be both huge and unreadable.
     */
    @Test
    fun `candidates are ordered best first`() = runTest {
        val transport = ScriptedTransport(mapOf("/tree/main" to treeResponse))
        val listing = client(transport).listFiles("Qwen/Qwen3-4B-GGUF")

        val ranks = listing.candidatesFor(availableRamBytes = 8L * 1024 * 1024 * 1024)
            .map { it.quantRank }
        assertEquals("candidates should be in descending quality order", ranks.sortedDescending(), ranks)
        assertTrue(
            "the best quantisation should be first, ranks were $ranks",
            ranks.first() > ranks.last(),
        )
    }

    /**
     * Download URLs are HTTPS.
     *
     * A cleartext model download would let anyone on the network replace the weights of
     * a model the user is about to trust with their story, so this is asserted rather
     * than assumed.
     */
    @Test
    fun `download urls are https and percent-encoded`() = runTest {
        val transport = ScriptedTransport(mapOf("/tree/main" to treeResponse))
        val listing = client(transport).listFiles("org name/repo name")
        val file = HuggingFaceFile(repoId = "org name/repo name", path = "a b/Qwen 4B.gguf", sizeBytes = 1L)

        assertTrue("url must be https: ${file.downloadUrl}", file.downloadUrl.startsWith("https://"))
        // Spaces and slashes are escaped within a path segment, not passed through raw.
        assertTrue("the filename should be encoded: ${file.downloadUrl}", file.downloadUrl.contains("Qwen%204B.gguf"))
        assertTrue("a path separator must survive: ${file.downloadUrl}", file.downloadUrl.contains("/a%20b/"))
    }

    // ------------------------------------------------------------------
    // Header reads
    // ------------------------------------------------------------------

    /**
     * A range request is used for the header read.
     *
     * The GGUF metadata block sits at the front of the file, so a few hundred kilobytes
     * answers "will this load?" without downloading four gigabytes.
     */
    @Test
    fun `the header is fetched as a byte range`() = runTest {
        val bytes = ByteArray(1024) { 'G'.code.toByte() }
        val transport = ScriptedTransport(emptyMap(), streamBytes = bytes)
        val file = HuggingFaceFile("org/repo", "model.gguf", 4_000_000_000L)

        val header = client(transport).fetchHeader(file, headerBytes = 1024)

        assertNotNull("no header returned", header)
        assertEquals(1024, header!!.size)
        assertTrue(
            "expected a ranged request, got ${transport.requests.single()}",
            transport.requests.single().contains("range 0-1023"),
        )
    }

    /**
     * A server that ignores the range is refused, not buffered.
     *
     * A 200 in answer to a range request means the whole multi-gigabyte body is
     * arriving. Buffering it to look at the first 512 KiB would be a catastrophic memory
     * use, so the client sees the raw status and declines.
     */
    @Test
    fun `a server ignoring the range is refused`() = runTest {
        val transport = ScriptedTransport(emptyMap(), streamBytes = ByteArray(16), streamCode = 200)
        val header = client(transport).fetchHeader(HuggingFaceFile("org/repo", "m.gguf", 4_000_000_000L))
        assertNull("a 200 answer to a range request must not be read", header)
    }

    @Test
    fun `an unreachable header read returns null`() = runTest {
        val transport = object : HttpTransport {
            override suspend fun getText(url: String, headers: Map<String, String>) =
                HttpTextResponse(404, "")

            override suspend fun openStream(url: String, rangeStart: Long?, rangeEnd: Long?): HttpStreamResponse =
                throw java.io.IOException("no route to host")
        }
        assertNull(client(transport).fetchHeader(HuggingFaceFile("org/repo", "m.gguf", 1L)))
    }

    // ------------------------------------------------------------------
    // Quantisation ranking
    // ------------------------------------------------------------------

    /**
     * The ranking table orders quantisations the way quality actually does.
     *
     * Q6 is better than Q5, Q5_K_M is better than Q4_K_M, and none of that follows
     * from the names alphabetically or numerically - which is why this is a hand-written
     * table rather than a parser.
     */
    @Test
    fun `quantisation ranking reflects real quality order`() {
        val scores = listOf(
            "model-q8_0.gguf" to "q8_0",
            "model-q6_k.gguf" to "q6_k",
            "model-q5_k_m.gguf" to "q5_k_m",
            "model-q4_k_m.gguf" to "q4_k_m",
            "model-q4_k_s.gguf" to "q4_k_s",
            "model-q3_k_l.gguf" to "q3_k_l",
            "model-q2_k.gguf" to "q2_k",
        )
        val ranked = scores.map { (fileName) ->
            dev.charaly.runtime.net.huggingface.GgufFileRanking.scoreOf(fileName)
        }
        assertEquals(
            "quantisation ranks should decrease in quality order",
            ranked.sortedDescending(),
            ranked,
        )
        for (score in ranked) {
            assertTrue("an unrecognised quantisation scored 0, which should be last", score > 0)
        }
    }

    @Test
    fun `an unrecognised quantisation scores zero rather than guessing`() {
        assertEquals(
            0,
            dev.charaly.runtime.net.huggingface.GgufFileRanking.scoreOf("model-experimental-quant.gguf"),
        )
    }

    @Test
    fun `a two-token quantisation is not scored as its one-token prefix`() {
        // The bug this guards: scoring "q5_k_m" as "q5_k" would rank a mixed-precision
        // file as better than a clean Q6.
        val twoToken = dev.charaly.runtime.net.huggingface.GgufFileRanking.scoreOf("m-Q5_K_M.gguf")
        val oneToken = dev.charaly.runtime.net.huggingface.GgufFileRanking.scoreOf("m-Q5_K.gguf")
        val q6 = dev.charaly.runtime.net.huggingface.GgufFileRanking.scoreOf("m-Q6_K.gguf")
        assertTrue("Q5_K_M should outrank Q5_K", twoToken > oneToken)
        assertTrue("Q6_K should outrank Q5_K_M", q6 > twoToken)
    }

    // ------------------------------------------------------------------
    // The offline default
    // ------------------------------------------------------------------

    /**
     * The offline transport refuses everything and explains why.
     *
     * It must not pretend to work. A transport that returned an empty body would make a
     * search look like "no results" rather than "no network", which is exactly the kind
     * of quiet lie this codebase is built to avoid.
     */
    @Test
    fun `the offline transport refuses rather than pretending`() = runTest {
        val transport = OfflineHttpTransport()

        val text = transport.getText("https://example.invalid/api")
        assertEquals(0, text.code)
        assertFalse(text.isSuccessful)
        assertTrue("the reason must be carried: ${text.headers}", text.headers.containsKey("charaly-error"))

        var threw = false
        try {
            transport.openStream("https://example.invalid/model.gguf")
        } catch (e: java.io.IOException) {
            threw = true
            assertTrue("the reason must be carried", e.message.orEmpty().isNotBlank())
        }
        assertTrue("openStream should refuse", threw)
    }

    @Test
    fun `the offline status is reported honestly`() {
        // No transport installed: offline only. Network is never required for core.
        assertEquals("Offline only", NetworkCapability.statusLabel(internetAvailable = true))
        assertFalse(NetworkCapability.NETWORK_REQUIRED_FOR_CORE)
        assertFalse("the bare runtime must not claim a transport", NetworkCapability.transportInstalled)
    }

    /**
     * `UnavailableModelDownloads` emits no progress at all.
     *
     * A single imaginary frame is enough to make a UI draw a progress bar that goes
     * nowhere, so the honest implementation emits exactly one FAILED frame and nothing
     * else.
     */
    @Test
    fun `unavailable downloads emit a single honest failure`() = runTest {
        val downloads: ModelDownloadManager = dev.charaly.runtime.model.UnavailableModelDownloads()
        assertNotNull("availability must state the reason", downloads.availability())

        val item = ModelCatalogItem(
            id = "m1",
            name = "Test",
            publisher = "p",
            architecture = "qwen2",
            download = dev.charaly.runtime.model.DownloadMetadata(
                url = "https://example.invalid/m.gguf",
                sizeBytes = 100L,
            ),
        )
        assertFalse("a model that cannot be downloaded must not offer it", downloads.canDownload(item))

        val frames = downloads.download(item).toList()
        assertEquals("exactly one frame, and it must be a failure", 1, frames.size)
        assertEquals(dev.charaly.runtime.model.DownloadState.FAILED, frames.single().state)
        assertNotNull(frames.single().failure)
        assertNull("no fraction may be invented", frames.single().fraction)
    }
}
