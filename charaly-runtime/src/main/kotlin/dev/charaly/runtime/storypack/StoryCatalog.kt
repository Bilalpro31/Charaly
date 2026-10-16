package dev.charaly.runtime.storypack

import dev.charaly.runtime.net.HttpTransport
import kotlinx.serialization.Serializable

/**
 * The Story Catalog: what is *available*, as opposed to what is installed.
 *
 * ## Catalog and pack are different things
 *
 * A catalog entry is metadata - a listing in a shop window. A pack is the goods. Confusing
 * them is how software ends up "installed" because a download *started*: the catalog says
 * a pack exists, the downloader fetches bytes, the verifier checks them against what the
 * catalog claimed, and only the installer makes anything true. Four separate steps, and
 * the last one is the only one that writes.
 *
 * ## Offline is the default
 *
 * The catalog may need the network. Playing a story never does. Nothing in the story
 * runtime can reach this file, which is the structural form of that promise.
 */
@Serializable
data class StoryCatalogItem(
    /** Catalog entry id. Not the pack id: one pack can have several listings over time. */
    val id: String,
    val packId: String,
    val packVersion: String,
    val title: String,
    val description: String = "",
    val premise: String = "",
    val author: String = "",
    val publisher: String = "",
    val language: String = "tr",
    val license: String = "",
    val tags: List<String> = emptyList(),
    val cover: String = "",
    val sizeBytes: Long = 0L,
    /** Digest of the `.charaly-pack` bytes. What a download is checked against. */
    val sha256: String = "",
    val minEngineVersion: String = "",
    val isRecommended: Boolean = false,
    val download: StoryPackDownloadMetadata? = null,
) {
    val sizeLabel: String
        get() = if (sizeBytes > 0) formatBytes(sizeBytes) else "boyut belirtilmemiş"

    /** Whether this build can fetch it at all. */
    val isDownloadable: Boolean get() = download != null

    companion object {
        fun formatBytes(bytes: Long): String = when {
            bytes >= 1_000_000_000 -> "%.1f GB".format(java.util.Locale.ROOT, bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> "%.1f MB".format(java.util.Locale.ROOT, bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.0f KB".format(java.util.Locale.ROOT, bytes / 1_000.0)
            else -> "$bytes B"
        }
    }
}

/** How to fetch a pack archive. */
@Serializable
data class StoryPackDownloadMetadata(
    val source: StoryPackDownloadSource = StoryPackDownloadSource.HTTP,
    val url: String = "",
    val fileName: String = "",
    val sizeBytes: Long = 0L,
    val sha256: String = "",
    val mirrors: List<String> = emptyList(),
)

@Serializable
enum class StoryPackDownloadSource {
    /** Over HTTPS, byte ranges supported for resume. */
    HTTP,

    /**
     * Already on the device - the packs Charaly ships with.
     *
     * Kept as a *download source* rather than a shortcut around installation, so built-in
     * packs are verified and installed by exactly the pipeline a downloaded pack goes
     * through. A shortcut here is how the pipeline stops being the pipeline.
     */
    BUILTIN,
}

/** Where a catalog's listings came from. Returned, never thrown - offline is not an error. */
sealed interface StoryCatalogSource {
    data class Remote(val manifestVersion: String, val itemCount: Int) : StoryCatalogSource
    data class Fallback(val reason: String) : StoryCatalogSource
    data object NotLoaded : StoryCatalogSource
}

/** The listing itself. */
interface StoryCatalog {
    val items: List<StoryCatalogItem>
    fun byId(id: String): StoryCatalogItem? = items.firstOrNull { it.id == id }
    fun forPack(packId: String): StoryCatalogItem? =
        items.filter { it.packId == packId }
            .sortedWith { a, b -> SemVer.compare(a.packVersion, b.packVersion) }
            .lastOrNull()
}

/** A catalog that is simply a list: the built-in fallback and every test double. */
class BuiltInStoryCatalog(
    override val items: List<StoryCatalogItem> = emptyList(),
) : StoryCatalog {
    companion object {
        val EMPTY = BuiltInStoryCatalog()
    }
}

/**
 * The catalog document: one JSON file describing many packs.
 *
 * Parsing is strict about the envelope and forgiving about the entries, exactly as the
 * model catalog is: one malformed listing must not blank the shop window for everybody
 * else, but a document that is not a catalog at all must not be mistaken for one.
 */
@Serializable
data class StoryCatalogManifest(
    val version: String = "1",
    val generatedAt: String = "",
    val items: List<StoryCatalogItem> = emptyList(),
) {
    val ids: List<String>
        get() = items.map { it.id }.distinct()

    companion object {
        fun parse(raw: String): StoryCatalogManifest? {
            val envelope = runCatching {
                StoryPackFormat.manifestJson.decodeFromString(Envelope.serializer(), raw)
            }.getOrNull() ?: return null
            if (envelope.items.isEmpty()) return null
            val items = envelope.items.mapNotNull { element ->
                runCatching {
                    StoryPackFormat.manifestJson.decodeFromJsonElement(StoryCatalogItem.serializer(), element)
                }.getOrNull()
            }
            if (items.isEmpty()) return null
            return StoryCatalogManifest(
                version = envelope.version,
                generatedAt = envelope.generatedAt,
                items = items.distinctBy { it.id },
            )
        }

        @Serializable
        private data class Envelope(
            val version: String = "1",
            val generatedAt: String = "",
            val items: List<kotlinx.serialization.json.JsonElement> = emptyList(),
        )
    }
}

/**
 * A catalog fetched over the network, layered over a built-in one.
 *
 * ## The one rule
 *
 * *Never fail the catalog screen.* Offline, 404, a truncated body, an HTML error page
 * dressed as JSON: all of them degrade to the built-in listings with a footnote saying so.
 * Discovery is a convenience; it is not allowed to become a dependency.
 */
class RemoteStoryCatalog(
    private val transport: HttpTransport,
    private val manifestUrl: String,
    private val fallback: StoryCatalog = BuiltInStoryCatalog.EMPTY,
) : StoryCatalog {

    @Volatile
    private var remote: List<StoryCatalogItem> = emptyList()

    @Volatile
    private var source: StoryCatalogSource = StoryCatalogSource.NotLoaded

    val catalogSource: StoryCatalogSource get() = source

    override val items: List<StoryCatalogItem>
        get() = merge(remote, fallback.items)

    /** Fetches the manifest. Never throws; a failure simply leaves the fallback in place. */
    suspend fun refresh(): StoryCatalogSource {
        val response = runCatching { transport.getText(manifestUrl) }.getOrNull()
            ?: return useFallback("katalog sunucusuna ulaşılamadı")
        if (response.code !in 200..299) {
            return useFallback("katalog sunucusu ${response.code} yanıtı verdi")
        }
        val manifest = StoryCatalogManifest.parse(response.body)
            ?: return useFallback("katalog belgesi okunamadı")
        remote = manifest.items
        source = StoryCatalogSource.Remote(manifest.version, manifest.items.size)
        return source
    }

    fun useFallback(reason: String): StoryCatalogSource {
        remote = emptyList()
        source = StoryCatalogSource.Fallback(reason)
        return source
    }

    /** Remote listings win on a shared id; built-ins fill the gaps. */
    private fun merge(
        remoteItems: List<StoryCatalogItem>,
        builtIn: List<StoryCatalogItem>,
    ): List<StoryCatalogItem> {
        val byId = LinkedHashMap<String, StoryCatalogItem>()
        builtIn.forEach { byId[it.id] = it }
        remoteItems.forEach { byId[it.id] = it }
        return byId.values.sortedBy { it.title.lowercase() }
    }

    companion object {
        /** The built-in listings expressed as the same document a server would send. */
        fun builtinManifest(fallback: StoryCatalog): StoryCatalogManifest =
            StoryCatalogManifest(version = "builtin", generatedAt = "", items = fallback.items)

        fun builtinManifestJson(fallback: StoryCatalog): String =
            StoryPackFormat.manifestJson.encodeToString(
                StoryCatalogManifest.serializer(),
                builtinManifest(fallback),
            )
    }
}

/** Footnote under a catalog that is showing its fallback listings. */
fun storyCatalogSourceNote(source: StoryCatalogSource): String = when (source) {
    is StoryCatalogSource.Remote -> "Çevrimiçi katalog · ${source.itemCount} paket"
    is StoryCatalogSource.Fallback -> "Çevrimdışı katalog · ${source.reason}"
    StoryCatalogSource.NotLoaded -> ""
}