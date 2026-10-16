package dev.charaly.runtime.presentation

import dev.charaly.runtime.model.EngineCapabilities
import dev.charaly.runtime.model.EngineSupport
import dev.charaly.runtime.model.EngineVerdict
import dev.charaly.runtime.model.gguf.CharalyCompatibility
import dev.charaly.runtime.model.gguf.GgufReader
import dev.charaly.runtime.net.huggingface.HuggingFaceFile
import dev.charaly.runtime.net.huggingface.HuggingFaceFileListing
import dev.charaly.runtime.net.huggingface.HuggingFaceRepo
import dev.charaly.runtime.net.huggingface.HuggingFaceSearchPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The Model Hub projection: discovery, file choice, and the honest install path.
 *
 * ## Why this is tested against real GGUF bytes
 *
 * The central claim the library makes is "this file will run on your device", and it is
 * derived from a file's own header. A test that fabricated a [dev.charaly.runtime.model.gguf.GgufMetadata]
 * object would be testing the classifier against itself - it would pass while the real
 * reader was broken, which is exactly the bug class that produced a catalog of blank
 * descriptions and an ascending "best first" file sort in the previous release.
 *
 * So these build genuine GGUF v3 headers, byte for byte, and let [GgufReader] parse them.
 * The bytes stop at the metadata block because that is all a GGUF header is; the padding
 * stands in for tensor data the reader never looks at.
 */
class ModelHubPresenterTest {

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * Writes a minimal but genuinely valid GGUF v3 header.
     *
     * Little-endian throughout, which is GGUF's byte order. The value-type codes matter:
     * 8 is STRING, 4 is UINT32, and declaring the wrong one produces a parse failure that
     * looks exactly like a corrupt file - a fixture bug that would make a passing test
     * meaningless.
     */
    private fun ggufBytes(
        architecture: String,
        contextLength: Long,
        blockCount: Long = 32L,
        embeddingLength: Long = 2048L,
        fileType: Long = 15L,
        chatTemplate: String = "{{ bos }}{% for message in messages %}{{ '<|' + message['role'] + '|>\n' + message['content'] + '\n' }}{% endfor %}{% if add_generation_prompt %}{{ '<|assistant|>\n' }}{% endif %}",
    ): ByteArray {
        val out = ByteArrayOutputStream()

        fun u32(value: Long) {
            out.write(value.toInt())
            out.write((value ushr 8).toInt())
            out.write((value ushr 16).toInt())
            out.write((value ushr 24).toInt())
        }

        fun u64(value: Long) {
            u32(value and 0xFFFFFFFFL)
            u32((value ushr 32) and 0xFFFFFFFFL)
        }

        fun string(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            u64(bytes.size.toLong())
            out.write(bytes)
        }

        fun u32Pair(key: String, value: Long) {
            string(key)
            u32(4L) // UINT32
            u32(value)
        }

        fun stringPair(key: String, value: String) {
            string(key)
            u32(8L) // STRING
            string(value)
        }

        out.write("GGUF".toByteArray(Charsets.US_ASCII))
        u32(3L) // version 3
        u64(0L) // tensor count
        // Six key/value pairs follow: architecture, file_type, context_length,
        // block_count, embedding_length, chat_template. Declaring five would make the
        // reader stop one pair early and silently drop the chat template - which is
        // exactly the kind of fixture bug that makes a passing assertion meaningless.
        u64(6L)

        stringPair("general.architecture", architecture)
        u32Pair("general.file_type", fileType)
        u32Pair("$architecture.context_length", contextLength)
        u32Pair("$architecture.block_count", blockCount)
        u32Pair("$architecture.embedding_length", embeddingLength)
        stringPair("tokenizer.chat_template", chatTemplate)

        return out.toByteArray()
    }

    private fun repo(
        repoId: String = "unsloth/Qwen3-4B-GGUF",
        description: String = "Qwen3 4B in GGUF format",
        license: String = "apache-2.0",
        tags: List<String> = listOf("gguf", "text-generation", "license:apache-2.0", "summary"),
    ) = HuggingFaceRepo(
        repoId = repoId,
        author = repoId.substringBefore('/'),
        name = repoId.substringAfter('/'),
        description = description,
        downloads = 1_250_000L,
        likes = 840L,
        tags = tags,
        license = license,
        libraryTag = "gguf",
    )

    private fun file(
        path: String,
        sizeBytes: Long,
        sha256: String = "a".repeat(64),
    ) = HuggingFaceFile(repoId = "unsloth/Qwen3-4B-GGUF", path = path, sizeBytes = sizeBytes, sha256 = sha256)

    // ------------------------------------------------------------------
    // Discovery
    // ------------------------------------------------------------------

    /**
     * A repository card shows only facts the Hub published about the repository.
     *
     * The absence assertions are the point. A repository publishes many files at many
     * sizes and contexts, so a card carrying a single size or context would be picking
     * one arbitrarily - and the type has no field for either, which makes that impossible
     * rather than merely discouraged.
     */
    @Test
    fun `a repository card carries no size or context`() {
        val card = ModelHubPresenter.repoCard(repo())

        assertEquals("unsloth/Qwen3-4B-GGUF", card.repoId)
        assertEquals("unsloth", card.author)
        assertEquals("Qwen3-4B-GGUF", card.title)
        assertEquals("Qwen3 4B in GGUF format", card.description)
        assertEquals("apache-2.0", card.license)
        // Tally formatted, never rounded into a fake "size".
        assertTrue("expected a download tally in ${card.metaLine}", card.downloadCount.contains("indirme"))
    }

    /**
     * A page is projected, and truncation is reported rather than hidden.
     *
     * A silently truncated list reads as "these are all of them", which for a Hub with
     * tens of thousands of GGUF repositories is a lie the user cannot detect.
     */
    @Test
    fun `truncated results are counted rather than hidden`() {
        val many = (1..10).map { repo(repoId = "author/model-$it") }
        val snapshot = ModelHubPresenter.browse(
            page = HuggingFaceSearchPage(repos = many, totalCount = 10, hasMore = true),
            query = "qwen",
            filter = HubFilter.MOST_DOWNLOADED,
            limit = 4,
        )

        assertEquals(4, snapshot.repos.size)
        assertEquals(6, snapshot.hiddenCount)
        assertTrue(snapshot.searchState is HubSearchState.Loaded)
        assertTrue("more pages must be acknowledged", (snapshot.searchState as HubSearchState.Loaded).hasMore)
    }

    /**
     * An empty result names what was searched.
     *
     * "No results" without the term leaves a user retyping what they just typed. This is
     * the smallest possible piece of context that makes the empty state actionable.
     */
    @Test
    fun `an empty result names the search term`() {
        val state = ModelHubPresenter.searchStateFor(query = "qwen", isOnline = true, resultCount = 0)
        assertTrue(state is HubSearchState.Failed)
        assertTrue(
            "the empty state must name the query: $state",
            (state as HubSearchState.Failed).message.contains("qwen"),
        )
    }

    /**
     * Offline says what still works.
     *
     * The library is one screen, so a failure to reach the Hub must not read as "the app
     * is broken" - installed models and every existing story are untouched. That sentence
     * is the entire content of this state.
     */
    @Test
    fun `offline reassures about installed models`() {
        val state = ModelHubPresenter.searchStateFor(query = "", isOnline = false, resultCount = 0)
        assertTrue(state is HubSearchState.Failed)
        val failed = state as HubSearchState.Failed
        assertTrue("offline must be flagged, not just described", failed.offline)
        assertTrue(
            "offline must say what still works: ${failed.actionLabel}",
            failed.actionLabel.contains("still work"),
        )
    }

    // ------------------------------------------------------------------
    // File classification
    // ------------------------------------------------------------------

    /**
     * A valid GGUF header yields "ready for Charaly".
     *
     * This is the load-bearing claim of the whole library: a 4.5 GB download is only worth
     * offering because the file can be classified from 512 KiB of its own header.
     */
    @Test
    fun `a valid qwen3 header classifies as ready`() {
        val header = ggufBytes(architecture = "qwen3", contextLength = 32_768L)
        val verdict = ModelHubPresenter.classify(file("m-Q4_K_M.gguf", 4_500_000_000L), header, availableRamBytes = 0L)

        assertEquals(CharalyCompatibility.CHARALY_READY, verdict.compatibility)
        assertEquals("Ready for Charaly", verdict.label)
        assertEquals("qwen3", verdict.architecture)
        assertEquals(32_768, verdict.contextLength)
        assertTrue(verdict.hasChatTemplate)
        assertTrue("a ready file must be downloadable", verdict.canDownload)
    }

    /**
     * An architecture this engine does not register is refused, by name.
     *
     * The vendored llama.cpp registers `gemma3` but not `gemma4`, so a Gemma 4 GGUF is a
     * real model that this build genuinely cannot open. Saying so *before* the download is
     * the whole reason the header is read first.
     */
    @Test
    fun `an architecture the engine lacks is refused before downloading`() {
        val header = ggufBytes(architecture = "gemma4", contextLength = 131_072L)
        val verdict = ModelHubPresenter.classify(file("gemma-4-E2B-it-Q4_K_M.gguf", 1_800_000_000L), header, 0L)

        assertEquals(CharalyCompatibility.MANUAL_IMPORT_ONLY, verdict.compatibility)
        assertFalse("an unloadable model must not offer a download", verdict.canDownload.not())
        assertTrue(
            "the refusal must name the remedy: ${verdict.reason}",
            verdict.reason.contains("engine", ignoreCase = true),
        )
    }

    /**
     * A file whose header could not be read is offered, but not promised.
     *
     * The middle case is deliberately distinct from both "unsupported" and "ready":
     * spending the user's data is their decision, but Charaly must not claim the model
     * will work when it has not read the file.
     */
    @Test
    fun `an unread header is offered without a promise`() {
        val verdict = ModelHubPresenter.classify(file("m.gguf", 1_000L), header = null, availableRamBytes = 0L)

        assertEquals(CharalyCompatibility.MANUAL_IMPORT_ONLY, verdict.compatibility)
        assertEquals("Henüz kontrol edilmedi", verdict.label)
        assertTrue("downloading is still allowed", verdict.canDownload)
        assertTrue(
            "the uncertainty must be stated: ${verdict.reason}",
            verdict.reason.contains("garanti edemez", ignoreCase = true),
        )
    }

    /**
     * Bytes that are not a GGUF are refused outright.
     *
     * This is the case where there is nothing to import: a truncated download, an HTML
     * error page saved as a model, or a file of the wrong format. The verdict is
     * UNSUPPORTED, which makes the Download control unavailable rather than merely
     * discouraged.
     */
    @Test
    fun `non-gguf bytes are refused`() {
        val verdict = ModelHubPresenter.classify(
            file("not-a-model.gguf", 4_096L),
            "this is not a gguf header at all".toByteArray(),
            availableRamBytes = 0L,
        )

        assertEquals(CharalyCompatibility.UNSUPPORTED, verdict.compatibility)
        assertFalse("an unreadable format must not offer a download", verdict.canDownload)
    }

    /**
     * A model too large for the device is flagged, not hidden.
     *
     * Warn-only rather than refused, because the platform's free-memory figure is
     * conservative and the loader is the only authority that cannot lie about what fits.
     */
    @Test
    fun `a model larger than free memory is flagged rather than refused`() {
        val header = ggufBytes(architecture = "qwen3", contextLength = 32_768L)
        // 40 GB of weights against 2 GB free.
        val verdict = ModelHubPresenter.classify(
            file("huge-Q4_K_M.gguf", 40L * 1024 * 1024 * 1024),
            header,
            availableRamBytes = 2L * 1024 * 1024 * 1024,
        )

        assertFalse("an oversized file must not claim readiness", verdict.isUsableNow)
        assertTrue("the reason must mention memory: ${verdict.reason}", verdict.reason.contains("memory"))
        assertTrue("the user may still try it", verdict.canDownload)
    }

    /**
     * Quantisation is read longest-token-first.
     *
     * `Q4_K_M` reported as `Q4` would misdescribe the file a user is about to spend 4 GB
     * on, and would make the "best fit" highlight choose the wrong variant.
     */
    @Test
    fun `quantisation is read from the full token`() {
        assertEquals("Q4_K_M", ModelHubPresenter.quantizationOf("Qwen3-4B-Q4_K_M.gguf"))
        assertEquals("IQ4_XS", ModelHubPresenter.quantizationOf("model-IQ4_XS.gguf"))
        assertEquals("Q8_0", ModelHubPresenter.quantizationOf("model-q8_0.gguf"))
        // And no token means no guess.
        assertEquals("", ModelHubPresenter.quantizationOf("model-fp16-instruct.gguf"))
    }

    // ------------------------------------------------------------------
    // Repository detail
    // ------------------------------------------------------------------

    /**
     * The recommended file is the best one this device can actually run.
     *
     * The subtle part is that "fits" has to mean the *same thing* as the per-file verdict.
     * A 2.5 GB Q4_K_M with a 32k context needs roughly 7.8 GB of RAM once the KV cache is
     * counted, so a "Best fit" badge computed from file size alone would recommend a file
     * whose own row says "manual import only" - a screen contradicting itself one row
     * apart. Deriving the recommendation from the verdicts rather than from a separate
     * size heuristic is what makes that divergence impossible to write.
     *
     * The memory figures are worth spelling out, because they are the whole test. At a 32k
     * context with 32 layers and a 2048-wide embedding, the KV cache alone is about 4.3 GB,
     * so a 9 GB Q8_0 needs roughly 15.6 GB in total and a 2.5 GB Q4_K_M roughly 7.8 GB.
     * 12 GB of free memory therefore separates them - and would not, if the cache were
     * ignored, which is exactly the bug the verdict derives this from.
     */
    @Test
    fun `the recommended file is the best one this device can run`() {
        val listing = HuggingFaceFileListing(
            repoId = "unsloth/Qwen3-4B-GGUF",
            ggufFiles = listOf(
                file("Qwen3-4B-Q8_0.gguf", 9_000_000_000L),
                file("Qwen3-4B-Q4_K_M.gguf", 2_500_000_000L),
                file("Qwen3-4B-Q2_K.gguf", 1_400_000_000L),
            ),
            otherFileCount = 3,
        )
        val headers = mapOf(
            "Qwen3-4B-Q8_0.gguf" to ggufBytes("qwen3", 32_768L),
            "Qwen3-4B-Q4_K_M.gguf" to ggufBytes("qwen3", 32_768L),
            "Qwen3-4B-Q2_K.gguf" to ggufBytes("qwen3", 32_768L),
        )

        val detail = ModelHubPresenter.detail(
            repo = repo(),
            listing = listing,
            headers = headers,
            // 12 GB free. Chosen to sit between the two files' real requirements (~15.6 GB
            // and ~7.8 GB), which is what makes "the biggest file does not fit" a fact
            // rather than an assumption - the KV cache is the reason, and a test that
            // ignored it would pass for the wrong reason.
            availableRamBytes = 12L * 1024 * 1024 * 1024,
        )

        val recommended = detail.recommended
        assertNotNull(recommended)
        assertEquals("Qwen3-4B-Q4_K_M.gguf", recommended!!.fileName)
        // The property that makes the badge trustworthy: whatever it points at must itself
        // be reported as ready.
        assertTrue(
            "the recommended file must be one this device can run: ${recommended.verdict.label}",
            recommended.verdict.isUsableNow,
        )
        assertEquals(3, detail.files.size)
        assertEquals(1, detail.files.count { it.isRecommended })
        // Best quantisation first, which is what the user is being steered towards.
        assertEquals("Q8_0", detail.files.first().quantization)
    }

    /**
     * A device with too little memory still gets a recommendation, and it is the smallest.
     *
     * Falling back to "nothing" would leave a screen with a file list and no guidance,
     * which is worse than pointing at the least hopeless option. The fallback is never
     * dressed up as ready: its own verdict still says it will not run here, so the badge
     * cannot be read as a promise.
     */
    @Test
    fun `when nothing fits the smallest file is recommended`() {
        val listing = HuggingFaceFileListing(
            repoId = "unsloth/Qwen3-4B-GGUF",
            ggufFiles = listOf(
                file("Qwen3-4B-Q8_0.gguf", 9_000_000_000L),
                file("Qwen3-4B-Q2_K.gguf", 1_400_000_000L),
            ),
            otherFileCount = 0,
        )
        val detail = ModelHubPresenter.detail(
            repo = repo(),
            listing = listing,
            headers = listing.ggufFiles.associate { it.path to ggufBytes("qwen3", 32_768L) },
            // 2 GB free: not enough for either.
            availableRamBytes = 2L * 1024 * 1024 * 1024,
        )

        assertEquals("Qwen3-4B-Q2_K.gguf", detail.recommended?.fileName)
        assertEquals("nothing fits, so nothing may claim to be ready", 0, detail.usableCount)
        assertFalse(detail.recommended!!.verdict.isUsableNow)
    }

    /**
     * A file already on this device is marked, and offers no second download.
     *
     * Downloading the same weights twice is how a phone runs out of storage, so the
     * control changes to a disabled "Installed" rather than remaining tappable.
     */
    @Test
    fun `an installed file is marked and not offered again`() {
        val listing = HuggingFaceFileListing(
            repoId = "unsloth/Qwen3-4B-GGUF",
            ggufFiles = listOf(file("Qwen3-4B-Q4_K_M.gguf", 2_500_000_000L)),
            otherFileCount = 1,
        )
        val detail = ModelHubPresenter.detail(
            repo = repo(),
            listing = listing,
            headers = mapOf("Qwen3-4B-Q4_K_M.gguf" to ggufBytes("qwen3", 32_768L)),
            installedFileNames = setOf("Qwen3-4B-Q4_K_M.gguf"),
        )

        assertTrue("the file must be recognised as installed", detail.files.single().isInstalled)
        assertEquals("Installed", detail.files.single().actionLabel)
    }

    /**
     * A repository with no GGUF says so, rather than showing an empty chooser.
     */
    @Test
    fun `a repository with no gguf files says so`() {
        val detail = ModelHubPresenter.detail(
            repo = repo(),
            listing = HuggingFaceFileListing("unsloth/Qwen3-4B-GGUF", ggufFiles = emptyList(), otherFileCount = 9),
        )
        assertTrue(detail.isEmpty)
        assertNull("nothing to recommend", detail.recommended)
    }

    /**
     * Non-GGUF files are counted, not listed.
     *
     * A repository's README, config and tokenizer JSON are not models, and rendering them
     * as choices would put four non-downloads above the one real file.
     */
    @Test
    fun `non-gguf files are counted not offered`() {
        val detail = ModelHubPresenter.detail(
            repo = repo(),
            listing = HuggingFaceFileListing(
                "unsloth/Qwen3-4B-GGUF",
                ggufFiles = listOf(file("model-Q4_K_M.gguf", 2_500_000_000L)),
                otherFileCount = 7,
            ),
        )
        assertEquals(1, detail.files.size)
        assertEquals(7, detail.otherFileCount)
    }

    // ------------------------------------------------------------------
    // The join to installation
    // ------------------------------------------------------------------

    /**
     * A discovered file becomes a downloadable catalog item.
     *
     * This is the join that makes a Hub repository installable at all: the download
     * pipeline takes a `ModelCatalogItem`, and a repository is not one. Every field the
     * pipeline needs - URL, size, checksum - comes from the Hub listing, so the transfer
     * is verifiable rather than optimistic.
     */
    @Test
    fun `a discovered file becomes a downloadable catalog item`() {
        val source = file("Qwen3-4B-Q4_K_M.gguf", 2_500_000_000L, sha256 = "b".repeat(64))
        val verdict = ModelHubPresenter.classify(source, ggufBytes("qwen3", 32_768L), 0L)

        val item = ModelHubPresenter.toCatalogItem(repo(), source, verdict)

        assertTrue(item.download != null)
        assertTrue("the URL must be HTTPS", item.download!!.url.startsWith("https://"))
        assertTrue(item.download!!.url.contains("Qwen3-4B-Q4_K_M.gguf"))
        assertEquals(2_500_000_000L, item.download!!.sizeBytes)
        assertEquals("b".repeat(64), item.download!!.sha256)
        assertEquals("qwen3", item.architecture)
        assertEquals(32_768, item.contextLength)
        assertEquals("Q4_K_M", item.quantization)
        assertEquals("unsloth/Qwen3-4B-GGUF", item.upstreamId)
        // The architecture was observed, so the engine can load it and the pipeline will
        // not refuse the transfer.
        assertTrue("the item must be loadable", item.isEngineLoadable)
    }

    /**
     * A file the engine cannot load produces an item the pipeline refuses.
     *
     * The refusal has to survive the join: `isEngineLoadable` is what
     * `HuggingFaceModelDownloads.download` checks before opening a socket, so it is the
     * last line of defence between a user's data and an architecture with no loader.
     */
    @Test
    fun `an unloadable architecture produces a refused catalog item`() {
        val source = file("gemma-4-E2B-it-Q4_K_M.gguf", 1_800_000_000L)
        val verdict = ModelHubPresenter.classify(source, ggufBytes("gemma4", 131_072L), 0L)
        val item = ModelHubPresenter.toCatalogItem(repo(), source, verdict)

        assertFalse("the pipeline must refuse this item", item.isEngineLoadable)
        assertTrue(
            "the reason must survive the join: ${item.engineVerdict.reason}",
            item.engineVerdict.reason.contains("newer", ignoreCase = true),
        )
    }

    /**
     * With no header read, the architecture falls back to the file's name - but only to a
     * registered one.
     *
     * `qwen3` resolves and is loadable, which is the right answer for a listing before any
     * header has been fetched. A name matching nothing resolves to empty, which routes to
     * "Charaly does not know what kind of model this is" rather than to a confident wrong
     * architecture.
     */
    @Test
    fun `an unclassified file infers only a registered architecture`() {
        val qwen = ModelHubPresenter.classify(file("Qwen3-4B-Q4_K_M.gguf", 1L), null, 0L)
        val qwenItem = ModelHubPresenter.toCatalogItem(repo(), file("Qwen3-4B-Q4_K_M.gguf", 1L), qwen)
        assertEquals("qwen3", qwenItem.architecture)

        val mystery = ModelHubPresenter.classify(file("some-unknown-thing.gguf", 1L), null, 0L)
        val mysteryItem = ModelHubPresenter.toCatalogItem(repo(), file("some-unknown-thing.gguf", 1L), mystery)
        assertEquals("", mysteryItem.architecture)
        assertFalse(
            "an unrecognised name must not look loadable",
            mysteryItem.isEngineLoadable,
        )
    }

    /**
     * A name hint never invents an architecture the engine lacks.
     *
     * This is the property that makes name inference safe at all: every value it can
     * return must be a member of the vendored engine's table. A hint pointing at an
     * unregistered architecture would produce a confident, wrong "supported" verdict,
     * which is the exact failure [EngineCapabilities] exists to prevent.
     */
    @Test
    fun `a name hint is either registered or deliberately-known-unsupported`() {
        // Names known to name an architecture this engine lacks. `gemma4` is the only one,
        // and it is *supposed* to appear here: inferring it is what lets the library say
        // "needs a newer engine" instead of resolving Gemma 4 to an older architecture and
        // claiming support it does not have.
        val deliberatelyUnsupported = setOf("gemma4")

        for (name in listOf(
            "Qwen3-4B", "qwen2.5-7b", "Meta-Llama-3.2-1B", "llama4-scout",
            "gemma-3-4b", "gemma2-9b", "gemma-4-e2b", "mistral-7b", "mixtral-8x7b",
            "phi4-mini", "DeepSeek-R1", "command-r-plus", "granite-3",
            "smol-v2", "olmo2-7b", "jamba-1.5", "glm4-9b", "yi-9b", "unknown-thing",
        )) {
            val inferred = EngineVerdict.inferArchitecture(name)
            if (inferred.isBlank()) continue
            val accountedFor = EngineCapabilities.supports(inferred) || inferred in deliberatelyUnsupported
            assertTrue(
                "\"$name\" inferred as \"$inferred\", which is neither registered by this " +
                    "engine nor a name we know is genuinely unsupported",
                accountedFor,
            )
        }
    }

    /**
     * Separators do not change the answer.
     *
     * Publishers spell the same family three ways, so a table that only matched one
     * spelling would silently fall through to the parent family - and "gemma_3" resolving
     * to "gemma" is a wrong architecture, not merely a less specific one.
     */
    @Test
    fun `name inference ignores separator spelling`() {
        val canonical = EngineVerdict.inferArchitecture("gemma-3-4b-it")
        assertEquals(canonical, EngineVerdict.inferArchitecture("Gemma3_4B_IT"))
        assertEquals(canonical, EngineVerdict.inferArchitecture("Gemma-3-4B"))
        assertEquals("qwen3", EngineVerdict.inferArchitecture("Qwen3.4B"))
    }

    /**
     * The specific case that makes the property above matter.
     *
     * `gemma-4` must resolve to `gemma4` - unregistered - and therefore to "needs a newer
     * engine". Resolving it to `gemma` would resolve to a *registered* architecture and
     * produce "supported", and the user would spend four gigabytes finding out otherwise.
     */
    @Test
    fun `gemma 4 resolves to the architecture it actually is`() {
        assertEquals("gemma4", EngineVerdict.inferArchitecture("gemma-4-E2B-it"))
        assertEquals(
            EngineSupport.ENGINE_UPDATE_REQUIRED,
            EngineVerdict.of(EngineVerdict.inferArchitecture("gemma-4-E2B-it")).support,
        )
        // While gemma 3 is genuinely supported by the vendored build.
        assertEquals("gemma3", EngineVerdict.inferArchitecture("gemma-3-4b-it"))
        assertEquals(
            EngineSupport.SUPPORTED,
            EngineVerdict.of(EngineVerdict.inferArchitecture("gemma-3-4b-it")).support,
        )
    }

    // ------------------------------------------------------------------
    // Truthfulness of the numbers
    // ------------------------------------------------------------------

    /**
     * Nothing on a card is estimated when the Hub did not publish it.
     *
     * A file size the Hub did not report must read "unknown" rather than a plausible
     * figure, because a user who reserves the wrong amount of space discovers the problem
     * after the download rather than before it.
     */
    @Test
    fun `an unpublished size reads as unknown`() {
        val option = HubFileOption(
            path = "model.gguf",
            fileName = "model.gguf",
            sizeLabel = "unknown",
            sizeBytes = 0L,
            quantization = "",
            verdict = HubFileVerdict(
                compatibility = CharalyCompatibility.MANUAL_IMPORT_ONLY,
                label = "Henüz kontrol edilmedi",
                reason = "",
                architecture = "",
                contextLength = 0,
                estimatedRamBytes = 0L,
                hasChatTemplate = false,
            ),
            hasChecksum = false,
            isInstalled = false,
            isRecommended = false,
        )
        assertEquals("unknown", option.sizeLabel)
        // And the meta line falls back rather than rendering an empty row.
        assertTrue("a sparse file still needs a line", option.metaLine.isNotBlank())
    }

    /**
     * A verified file says so; an unverifiable one does not claim to.
     *
     * The Hub publishes a Git-LFS hash for files over 10 MB, so most real models are
     * verifiable - but a file without one must not borrow the claim from its neighbour.
     */
    @Test
    fun `only a published hash is reported as a checksum`() {
        val listing = HuggingFaceFileListing(
            repoId = "unsloth/Qwen3-4B-GGUF",
            ggufFiles = listOf(
                file("with-hash.gguf", 2_000_000L, sha256 = "c".repeat(64)),
                file("without-hash.gguf", 2_000_000L, sha256 = ""),
            ),
            otherFileCount = 0,
        )
        val detail = ModelHubPresenter.detail(repo = repo(), listing = listing)

        assertTrue(detail.files.first { it.fileName == "with-hash.gguf" }.hasChecksum)
        assertFalse(detail.files.first { it.fileName == "without-hash.gguf" }.hasChecksum)
    }

    /**
     * The snapshot's online flag is carried, not inferred by the screen.
     *
     * A screen that computed its own notion of "offline" would disagree with the state that
     * actually produced the failure.
     */
    @Test
    fun `the online flag reaches the snapshot`() {
        val offline = ModelHubPresenter.browse(
            page = HuggingFaceSearchPage.EMPTY,
            query = "qwen",
            filter = HubFilter.MOST_DOWNLOADED,
            isOnline = false,
        )
        assertFalse(offline.isOnline)
        assertTrue((offline.searchState as HubSearchState.Failed).offline)

        val online = ModelHubPresenter.browse(
            page = HuggingFaceSearchPage(listOf(repo()), 1, false),
            query = "qwen",
            filter = HubFilter.MOST_DOWNLOADED,
            isOnline = true,
        )
        assertTrue(online.isOnline)
        assertNotNull(online.repos.single())
    }
}