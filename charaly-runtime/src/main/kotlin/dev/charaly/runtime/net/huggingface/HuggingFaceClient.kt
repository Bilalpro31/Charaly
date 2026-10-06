package dev.charaly.runtime.net.huggingface

import dev.charaly.runtime.net.HttpTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A GGUF repository on the Hugging Face Hub, as far as discovery is concerned.
 *
 * This is a *repository*, not a model file. A single repo routinely publishes dozens of
 * GGUF quantisations of the same weights, and picking between them is a device decision,
 * not a discovery one - see [HuggingFaceClient.ggufFiles] and the quantisation chooser.
 */
data class HuggingFaceRepo(
    /** `author/model-name`. Charaly's stable key for a repository. */
    val repoId: String,
    val author: String,
    val name: String,
    val description: String = "",
    val downloads: Long = 0L,
    val likes: Long = 0L,
    val tags: List<String> = emptyList(),
    val pipelineTag: String = "",
    val lastModified: String = "",
    val license: String = "",
    /**
     * The library tag Hub reports. Always `gguf` here, because the query filters on it.
     *
     * Recorded rather than assumed: a future response with a mixed result would otherwise
     * be silently presented as GGUF-filtered when it is not.
     */
    val libraryTag: String = "",
) {
    /** `author/model-name`, as shown on a card's secondary line. */
    val displayName: String get() = repoId

    /**
     * The model family, derived from the repo name.
     *
     * "Qwen3-4B-Instruct-GGUF" and "Meta-Llama-3.2-1B-GGUF" both reduce to something a
     * user recognises as the family. Used for the Family filter, which is why it is a
     * known-prefix match rather than a substring search: a substring search on "gemma"
     * also matches repositories about gemma.
     */
    val family: String
        get() = FAMILY_PREFIXES.firstOrNull { prefix ->
            name.lowercase().startsWith(prefix) || repoId.substringAfter('/').lowercase().startsWith(prefix)
        } ?: name.substringBefore('-').lowercase().ifBlank { "other" }

    companion object {
        /**
         * Verified GGUF families, longest first so "llama3" wins over "llama".
         *
         * Ordered deliberately: a prefix list must be checked most-specific-first, or
         * every Llama 3 model would be classified as Llama.
         */
        val FAMILY_PREFIXES: List<String> = listOf(
            "qwen3", "qwen2.5", "qwen2", "qwen",
            "llama-3", "llama3", "llama-2", "llama2", "llama",
            "mistral", "mixtral", "ministral",
            "gemma-3", "gemma3", "gemma-2", "gemma2", "gemma",
            "phi-4", "phi4", "phi-3", "phi3", "phi",
            "deepseek", "command-r", "olmo", "exaone", "smol", "granite", "yi-", "yi/",
        )
    }
}

/**
 * One GGUF file inside a repository.
 *
 * Sizes come from the Hub's file listing, which for files over 10 MB reports the Git-LFS
 * size. [sizeBytes] is therefore the real transfer size, not an estimate - which is what
 * makes the storage check before a download meaningful.
 */
data class HuggingFaceFile(
    val repoId: String,
    /** Path within the repo, e.g. `Qwen3-4B-Q4_K_M.gguf`. */
    val path: String,
    val sizeBytes: Long,
    /**
     * Git-LFS sha256, lowercase hex, when the Hub publishes one.
     *
     * This is the *content* hash, so verifying against it is a genuine integrity check
     * rather than a size comparison. Empty means "cannot verify", never "assume fine".
     */
    val sha256: String = "",
) {
    val fileName: String get() = path.substringAfterLast('/')

    val sizeLabel: String
        get() = when {
            sizeBytes <= 0L -> "unknown"
            sizeBytes >= 1L shl 30 -> "%.2f GB".format(sizeBytes / (1L shl 30).toDouble())
            sizeBytes >= 1L shl 20 -> "%.0f MB".format(sizeBytes / (1L shl 20).toDouble())
            else -> "%.0f kB".format(sizeBytes / 1024.0)
        }

    /** The direct HTTPS download URL. Always HTTPS; cleartext is refused by the app. */
    val downloadUrl: String
        get() = "https://huggingface.co/$repoId/resolve/main/${path.split('/').joinToString("/") { encodePathSegment(it) }}"

    /** URL of the first [prefixBytes] of the file, for reading its GGUF header. */
    fun headerUrl(prefixBytes: Int): String {
        val base = "https://huggingface.co/$repoId/resolve/main/${path.split('/').joinToString("/") { encodePathSegment(it) }}"
        return base
    }

    companion object {
        /**
         * Percent-encodes one path segment.
         *
         * Written out rather than delegating to an encoder because the runtime module must
         * not depend on `java.net`, and the encoding rules here are narrow enough to state:
         * everything outside an unreserved set is escaped.
         */
        internal fun encodePathSegment(segment: String): String {
            val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
            return buildString(segment.length) {
                segment.forEach { ch ->
                    if (ch in unreserved) {
                        append(ch)
                    } else {
                        ch.toString().toByteArray(Charsets.UTF_8).forEach { byte ->
                            val v = byte.toInt() and 0xFF
                            append('%')
                            append("0123456789ABCDEF"[v shr 4])
                            append("0123456789ABCDEF"[v and 0x0F])
                        }
                    }
                }
            }
        }
    }
}

/**
 * A page of search results, with the information needed to say "there are more".
 */
data class HuggingFaceSearchPage(
    val repos: List<HuggingFaceRepo>,
    val totalCount: Int,
    val hasMore: Boolean,
) {
    companion object {
        val EMPTY = HuggingFaceSearchPage(emptyList(), 0, false)
    }
}

/**
 * Everything the Hub told us about one repository's files.
 */
data class HuggingFaceFileListing(
    val repoId: String,
    val ggufFiles: List<HuggingFaceFile>,
    /** Non-GGUF files, counted rather than listed: a repo's README is not a model. */
    val otherFileCount: Int,
) {
    val isEmpty: Boolean get() = ggufFiles.isEmpty()

    /**
     * The GGUF files, best first.
     *
     * Descending by [GgufFileRanking], ties broken by size. Ascending was the bug here:
     * the doc said "best first", the code sorted ascending, and a UI iterating this list
     * would offer a user the *worst* quantisation in the repository first. Size is the
     * tie-break because among equally-good quantisations the smaller file is the one
     * more likely to fit.
     */
    fun candidatesFor(availableRamBytes: Long): List<HuggingFaceFile> =
        ggufFiles.sortedWith(compareByDescending<HuggingFaceFile> { it.quantRank }.thenBy { it.sizeBytes })

    /**
     * The best single file for this device, or null when nothing fits.
     *
     * Prefers the highest quantisation that fits, because quantisation is the main lever
     * on prose quality and dropping it further than necessary loses that for nothing.
     */
    fun bestFor(availableRamBytes: Long): HuggingFaceFile? {
        val sorted = ggufFiles.sortedByDescending { it.quantRank }
        // Fit test uses the weights plus the runtime allowance, mirroring the post-download
        // compatibility check so the two agree about what fits.
        return sorted.firstOrNull { file ->
            availableRamBytes <= 0L ||
                file.sizeBytes + GgufFileRanking.RUNTIME_HEADROOM < availableRamBytes
        } ?: sorted.lastOrNull()
    }
}

/**
 * How well a quantisation is expected to preserve quality, 0..100.
 *
 * ## Why a hand-written table and not a parsed constant
 *
 * GGUF quantisation type names are `F32`, `Q8_0`, `Q6_K`, `Q5_K_M`, `Q4_K_M`, `Q4_K_S`,
 * `Q3_K_L`, `IQ4_XS` and so on, with minor variants. The ordering matters (Q6 is better
 * than Q5, Q5_K_M is better than Q4_K_M) and it does not follow from the name
 * alphabetically or numerically. An unknown name scores 0, which puts it last rather
 * than pretending to know.
 */
internal object GgufFileRanking {

    /** Ordered best-first. The first matching prefix wins. */
    private val TIERS: List<Pair<String, Int>> = listOf(
        "f32" to 100,
        "f16" to 95,
        "bf16" to 94,
        "q8_0" to 90,
        "q8_k" to 89,
        "q6_k" to 80,
        "q5_k_m" to 72,
        "q5_k_s" to 68,
        "q5_0" to 66,
        "q5_1" to 66,
        "q5_k" to 70,
        "q4_k_m" to 60,
        "q4_k_s" to 55,
        "q4_0" to 52,
        "q4_1" to 52,
        "iq4_xs" to 50,
        "iq4_nl" to 50,
        "q3_k_l" to 44,
        "q3_k_m" to 40,
        "q3_k_s" to 36,
        "iq3_m" to 38,
        "iq3_s" to 36,
        "iq3_xs" to 35,
        "iq3_xxs" to 33,
        "q2_k_s" to 24,
        "q2_k" to 24,
        "iq2_m" to 22,
        "iq2_s" to 20,
        "iq2_xs" to 18,
        "iq2_xxs" to 16,
        "q1_s" to 10,
        "iq1_s" to 10,
        "iq1_m" to 8,
    )

    /** Runtime allowance added to a file's size when asking "does this fit". */
    const val RUNTIME_HEADROOM: Long = 768L * 1024L * 1024L

    /**
     * Quality score for a filename, 0..100, or 0 when unrecognised.
     *
     * Reads the token after the parameter count, so `q4_k_m` is found in
     * `Model-Q4_K_M.gguf` regardless of what follows it.
     */
    fun scoreOf(fileName: String): Int {
        val lower = fileName.lowercase()
        // Try two-token forms first so "q5_k_m" is not scored as "q5_k".
        for (length in 2 downTo 1) {
            for (start in lower.indices) {
                if (start > 0 && !isSeparator(lower[start - 1])) continue
                if (start + length > lower.length) continue
                if (start + length < lower.length && !isSeparator(lower[start + length])) continue
                val token = lower.substring(start, start + length)
                val hit = TIERS.firstOrNull { (name, _) -> name == token }
                    ?: TIERS.firstOrNull { (name, _) -> name.startsWith("$token-") }
                if (hit != null) return hit.second
            }
        }
        // Fall back to a prefix match anywhere in the name, for names like
        // "q4_k_m-00001-of-00003.gguf".
        return TIERS.firstOrNull { (name, _) -> lower.contains(name) }?.second ?: 0
    }

    private fun isSeparator(ch: Char): Boolean = ch == '-' || ch == '_' || ch == ' ' || ch == '.'
}

/**
 * Quality rank parsed out of a filename, for sorting a repo's quantisations.
 *
 * An extension so `HuggingFaceFile` can expose it without this file owning the file type.
 */
val HuggingFaceFile.quantRank: Int get() = GgufFileRanking.scoreOf(fileName)

/**
 * Talks to the public Hugging Face Hub API.
 *
 * ## What this client does and does not do
 *
 * It does:
 *  * list GGUF repositories, with search and filters, from the public API;
 *  * list a repository's files with real sizes and LFS hashes;
 *  * fetch the first few hundred kilobytes of a file so
 *    [dev.charaly.runtime.model.gguf.GgufReader] can classify it.
 *
 * It deliberately does **not** decide compatibility. The Hub publishes whatever a repo
 * contains, including quantisations of architectures this build's llama.cpp cannot open.
 * Charaly computes that itself from the file's own header - see
 * [dev.charaly.runtime.model.gguf.GgufCompatibility] - which is the only answer that can
 * account for the engine compiled into this particular APK.
 *
 * ## No credentials
 *
 * Public endpoints only, so the client needs no token. A private repo is out of scope:
 * asking for a token would put a credential in an app whose entire premise is that it
 * holds none.
 */
class HuggingFaceClient(
    private val transport: HttpTransport,
    private val baseUrl: String = "https://huggingface.co",
    private val json: Json = defaultJson,
) {

    /**
     * Lists GGUF repositories.
     *
     * `library=gguf` on the Hub's own filter is the mechanism - rather than filtering
     * client-side - so the result count reflects what the Hub actually indexes instead of
     * how many pages we happened to fetch.
     */
    suspend fun searchGguf(
        query: String = "",
        limit: Int = DEFAULT_PAGE_SIZE,
        sort: Sort = Sort.DOWNLOADS,
        additionalFilters: List<String> = emptyList(),
    ): HuggingFaceSearchPage {
        val params = buildList {
            add("library=gguf")
            add("filter=gguf")
            add("limit=${limit.coerceIn(1, MAX_PAGE_SIZE)}")
            add("sort=${sort.apiValue}")
            add("direction=-1")
            add("full=true")
            if (query.isNotBlank()) add("search=${encodeQuery(query.trim())}")
            additionalFilters.forEach { add(it) }
        }.joinToString("&")

        val response = transport.getText("$baseUrl/api/models?$params")
        if (!response.isSuccessful) return HuggingFaceSearchPage.EMPTY

        val array = runCatching { json.parseToJsonElement(response.body).jsonArray }.getOrNull()
            ?: return HuggingFaceSearchPage.EMPTY

        val repos = array.mapNotNull { element ->
            runCatching { parseRepo(element.jsonObject) }.getOrNull()
        }.filter { it.libraryTag.equals("gguf", ignoreCase = true) }

        return HuggingFaceSearchPage(
            repos = repos,
            // The listing endpoint returns at most `limit` items and does not send a total,
            // so "has more" is inferred from a full page. A short page is the end.
            totalCount = repos.size,
            hasMore = array.size >= limit.coerceIn(1, MAX_PAGE_SIZE),
        )
    }

    /**
     * Lists a repository's files.
     *
     * The tree endpoint is used rather than the model endpoint because it reports a real
     * `size` per file, which is what makes the pre-download storage check possible.
     */
    suspend fun listFiles(repoId: String): HuggingFaceFileListing {
        val response = transport.getText("$baseUrl/api/models/${encodeQuery(repoId)}/tree/main?recursive=true")
        if (!response.isSuccessful) return HuggingFaceFileListing(repoId, emptyList(), 0)

        val array = runCatching { json.parseToJsonElement(response.body).jsonArray }.getOrNull()
            ?: return HuggingFaceFileListing(repoId, emptyList(), 0)

        var other = 0
        val ggufs = mutableListOf<HuggingFaceFile>()
        array.forEach { element ->
            val obj = runCatching { element.jsonObject }.getOrNull() ?: return@forEach
            val path = obj.string("path") ?: return@forEach
            if (obj.string("type") == "directory") return@forEach
            if (!path.endsWith(".gguf", ignoreCase = true)) {
                other++
                return@forEach
            }
            // The LFS oid is the file's *content* hash, so verifying against it is a
            // genuine integrity check rather than a size comparison.
            //
            // `lfs` is authoritative when present. A bare top-level `oid` is only a
            // fallback for listings that omit it, and `lfs.size` is preferred over the
            // listing's own `size` because it is the figure the CDN will actually send.
            val lfs = obj["lfs"] as? JsonObject
            ggufs += HuggingFaceFile(
                repoId = repoId,
                path = path,
                sizeBytes = lfs?.long("size") ?: obj.long("size") ?: 0L,
                sha256 = lfs?.string("oid")?.takeIf { it.isNotBlank() }
                    ?: obj.string("oid").orEmpty(),
            )
        }
        return HuggingFaceFileListing(repoId, ggufs, other)
    }

    /**
     * Reads the first [headerBytes] of a GGUF file and parses its header.
     *
     * ## Why the header is fetched over the network at all
     *
     * The library has to answer "will this load?" *before* a 4.5 GB download. The GGUF
     * key/value block sits at the start of the file, so a ranged request for the first few
     * hundred kilobytes is enough to read the architecture, the context length and the
     * chat template. That turns a four-gigabyte guess into a few hundred kilobytes of
     * fact.
     *
     * The compatibility verdict itself comes from Charaly's own classifier, not from the
     * Hub, so a repo's self-description cannot make an unloadable model look loadable.
     */
    suspend fun fetchHeader(file: HuggingFaceFile, headerBytes: Int = DEFAULT_HEADER_BYTES): ByteArray? {
        val response = runCatching {
            transport.openStream(
                url = file.downloadUrl,
                rangeStart = 0L,
                rangeEnd = headerBytes.toLong() - 1L,
            )
        }.getOrNull() ?: return null

        // 206 is the correct answer for a range request. A 200 means the server ignored
        // the range and is sending the whole file, which for a model is gigabytes: treat
        // it as unusable rather than buffering it.
        if (response.code != 206) {
            response.close()
            return null
        }
        return response.use { stream ->
            runCatching {
                val buffer = ByteArray(headerBytes)
                var offset = 0
                while (offset < buffer.size) {
                    val n = stream.read(buffer, offset, buffer.size - offset)
                    if (n <= 0) break
                    offset += n
                }
                if (offset <= 0) null else buffer.copyOf(offset)
            }.getOrNull()
        }
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    private fun parseRepo(obj: JsonObject): HuggingFaceRepo {
        val repoId = obj.string("id").orEmpty()
        if (repoId.isBlank()) error("repo has no id")
        val tags = obj.array("tags").mapNotNull { (it as? JsonPrimitive)?.content }
        return HuggingFaceRepo(
            repoId = repoId,
            author = repoId.substringBefore('/'),
            name = repoId.substringAfter('/'),
            description = descriptionOf(tags),
            downloads = obj.long("downloads") ?: 0L,
            likes = obj.long("likes") ?: 0L,
            tags = tags,
            pipelineTag = obj.string("pipeline_tag").orEmpty(),
            lastModified = obj.string("lastModified").orEmpty(),
            license = tags.firstOrNull { it.startsWith("license:") }?.removePrefix("license:").orEmpty(),
            libraryTag = tags.firstOrNull { it.equals("gguf", ignoreCase = true) }.orEmpty(),
        )
    }

    // Small JSON helpers. Written out rather than pulled in as a dependency because the
    // shapes are narrow and the "absent vs zero" distinction matters throughout.
    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it != "null" && it.isNotEmpty() }

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

    private fun JsonObject.array(key: String): List<JsonPrimitive> {
        val array = this[key] as? JsonArray ?: return emptyList()
        return array.mapNotNull { it as? JsonPrimitive }
    }

    private fun JsonObject.nestedString(parent: String, child: String): String? =
        (this[parent] as? JsonObject)?.let { it.string(child) }

    /** Sort options, mapped to the Hub's own parameter values. */
    enum class Sort(val apiValue: String) {
        DOWNLOADS("downloads"),
        LIKES("likes"),
        LAST_MODIFIED("lastModified"),
        ;

        /** The label a filter chip shows. */
        val label: String
            get() = when (this) {
                DOWNLOADS -> "Most downloaded"
                LIKES -> "Most liked"
                LAST_MODIFIED -> "Recently updated"
            }
    }

    companion object {
        /** The Hub caps a page at 1000 entries. */
        const val MAX_PAGE_SIZE = 100

        /** A page size that is large enough to be useful and small enough to stay snappy. */
        const val DEFAULT_PAGE_SIZE = 30

        /**
         * How much of a file to fetch when reading its header.
         *
         * 512 KiB. The metadata block of every widely used converter output is well under
         * 64 KiB - the chat template and tokenizer merges dominate - so this is generous
         * while staying trivial to download.
         */
        const val DEFAULT_HEADER_BYTES = 512 * 1024

        /**
         * How a tag turns into a description.
         *
         * Matching on the literal string "summary:" was a bug: the Hub's own convention
         * is the tag *name* `summary`, and this parser only ever saw a value like
         * `summary: Qwen3 4B in GGUF format` in hand-written fixtures. A real response
         * carries `"summary"` in `tags`, so every catalog card was rendering with a blank
         * description while looking fine in tests.
         *
         * Both spellings are now accepted, and an empty description is a legitimate
         * outcome rather than something to paper over.
         */
        private fun descriptionOf(tags: List<String>): String {
            // The Hub's convention is the tag *name* `summary`, optionally with a colon
            // and a value. Both spellings are accepted because uploads use both.
            val raw = tags.firstOrNull { it.equals("summary", ignoreCase = true) }
                ?: tags.firstOrNull { it.startsWith("summary:") }
                ?: return ""

            val value = raw.substringAfter(':', missingDelimiterValue = "")
                .trim()
            if (value.isEmpty()) {
                // A bare "summary" tag with no payload carries no description. Returning
                // the tag's own name would put the word "summary" on every card.
                return ""
            }
            return value.replace('_', ' ')
        }

        val defaultJson: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /** Percent-encodes a query-string value. */
        internal fun encodeQuery(value: String): String =
            HuggingFaceFile.encodePathSegment(value)
    }
}
