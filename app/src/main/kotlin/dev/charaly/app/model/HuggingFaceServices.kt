package dev.charaly.app.model

import android.content.Context
import dev.charaly.app.net.AndroidHttpTransport
import dev.charaly.app.net.AndroidStorageProbe
import dev.charaly.runtime.model.HuggingFaceModelDownloads
import dev.charaly.runtime.model.JsonModelRegistry
import dev.charaly.runtime.model.ModelCatalogItem
import dev.charaly.runtime.model.ModelDownloadManager
import dev.charaly.runtime.net.HttpTransport
import dev.charaly.runtime.net.NetworkCapability
import dev.charaly.runtime.net.StorageProbe
import dev.charaly.runtime.net.huggingface.HuggingFaceClient
import dev.charaly.runtime.net.huggingface.HuggingFaceFile
import dev.charaly.runtime.net.huggingface.HuggingFaceFileListing
import dev.charaly.runtime.net.huggingface.HuggingFaceRepo
import dev.charaly.runtime.net.huggingface.HuggingFaceSearchPage
import dev.charaly.runtime.model.gguf.CharalyCompatibility
import dev.charaly.runtime.model.gguf.GgufCompatibility
import dev.charaly.runtime.model.gguf.GgufReader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import java.io.File

/**
 * The app module's wiring for Hugging Face discovery and model download.
 *
 * ## What this connects, and to what
 *
 * ```
 *   HuggingFaceClient          discovery: repos, files, sizes, LFS hashes
 *          |                   (small JSON over HTTPS)
 *          v
 *   HuggingFaceModelDownloads  the real transfer, install and register pipeline
 *          |
 *          v
 *   ModelRegistry              one list of installed models
 * ```
 *
 * All three are pure Kotlin/JVM in `charaly-runtime` and fully unit tested there. The
 * only thing this class adds is the Android [HttpTransport] and the real
 * [StorageProbe] - i.e. exactly the two things a JVM module cannot provide.
 *
 * ## The offline guarantee
 *
 * Discovery and download are the only network features in the product. Everything that
 * makes up a *story* - world state, memory, inference, simulation - never touches this
 * object, so an app with no connectivity is a fully working app. That is why
 * `NetworkCapability.NETWORK_REQUIRED_FOR_CORE` is a constant `false` rather than
 * something computed.
 */
class HuggingFaceServices(
    context: Context,
    private val registry: JsonModelRegistry,
    transport: HttpTransport = AndroidHttpTransport(),
    /** Free-space source. Defaults to the models directory itself. */
    storage: StorageProbe? = null,
) {

    /**
     * The same directory [ModelManager] imports into.
     *
     * Shared deliberately: the registry reconciliation on launch scans exactly this
     * directory and deletes entries whose files are not in it, so a download written
     * anywhere else would have its registry entry removed on the next app start.
     */
    private val modelsDirectory: File = CharalyPaths.models(context).apply { mkdirs() }

    /** Discovery: search the Hub's GGUF-filtered index. */
    val client: HuggingFaceClient = HuggingFaceClient(transport)

    /** The real download pipeline. Not a stub, and not optional. */
    val downloads: HuggingFaceModelDownloads = HuggingFaceModelDownloads(
        transport = transport,
        storage = storage ?: AndroidStorageProbe(modelsDirectory),
        registry = registry,
        modelsDirectory = modelsDirectory,
    )

    init {
        // The Settings screen's offline indicator reads this, and so does the model
        // library, which is the only screen that offers a network action.
        NetworkCapability.transportInstalled = true
    }

    /** Convenience: is the device currently able to reach the Hub? */
    fun searchGguf(
        query: String = "",
        limit: Int = HuggingFaceClient.DEFAULT_PAGE_SIZE,
        sort: HuggingFaceClient.Sort = HuggingFaceClient.Sort.DOWNLOADS,
    ): Flow<HuggingFaceSearchPage> = flow { emit(client.searchGguf(query, limit, sort)) }

    /** A repository's GGUF files, with real sizes. */
    suspend fun listFiles(repoId: String): HuggingFaceFileListing = client.listFiles(repoId)

    /**
     * Classifies a GGUF *before* downloading it, from its header alone.
     *
     * ## Why this is worth 512 KiB of download
     *
     * A GGUF's metadata block sits at the front of the file, so a ranged request for
     * the first few hundred kilobytes yields the architecture, the context length and
     * the chat template. That turns a four-gigabyte guess into a few hundred kilobytes
     * of fact, and it is what lets the library say "Ready for Charaly" before asking the
     * user to spend their data.
     *
     * ## The verdict is Charaly's, not the Hub's
     *
     * The Hub publishes whatever a repository contains, including quantisations of
     * architectures this build's llama.cpp cannot open. So the compatibility decision is
     * made by [GgufCompatibility] against [dev.charaly.runtime.model.EngineCapabilities],
     * which mirrors the vendored engine's own architecture table. A repository's
     * self-description cannot make an unloadable model look loadable.
     */
    suspend fun classifyBeforeDownload(
        file: HuggingFaceFile,
        availableRamBytes: Long,
    ): PreDownloadVerdict {
        val header = client.fetchHeader(file)
        if (header == null) {
            return PreDownloadVerdict(
                compatibility = CharalyCompatibility.MANUAL_IMPORT_ONLY,
                label = "Manual import only",
                reason = "Charaly could not read this file's header, so it cannot promise " +
                    "the model will load. Downloading it is still allowed - you can try it.",
                architecture = "",
                contextLength = 0,
                chatTemplate = false,
                sizeBytes = file.sizeBytes,
            )
        }
        val read = GgufReader.read(header)
        val verdict = GgufCompatibility.classify(read, file.sizeBytes, availableRamBytes)
        val metadata = (read as? dev.charaly.runtime.model.gguf.GgufReadResult.Success)?.metadata
        return PreDownloadVerdict(
            compatibility = verdict.compatibility,
            label = verdict.label(),
            reason = verdict.reason,
            architecture = verdict.architecture,
            contextLength = metadata?.contextLength ?: 0,
            chatTemplate = metadata?.hasChatTemplate == true,
            sizeBytes = file.sizeBytes,
        )
    }
}

/**
 * What Charaly can say about a Hub file before committing to a download.
 *
 * [compatibility] is a three-way verdict on purpose. "Unknown" and "known but needs an
 * engine update" are very different situations, and collapsing them is how a library
 * ends up offering a Download that fails an hour later.
 */
data class PreDownloadVerdict(
    val compatibility: CharalyCompatibility,
    val label: String,
    val reason: String,
    val architecture: String,
    val contextLength: Int,
    val chatTemplate: Boolean,
    val sizeBytes: Long,
) {
    val isReadyNow: Boolean get() = compatibility == CharalyCompatibility.CHARALY_READY

    /** "7B · Q4_K_M · 4.5 GB" style secondary line. Never a fabricated number. */
    fun secondaryLine(): String = listOfNotNull(
        architecture.takeIf { it.isNotBlank() },
        contextLength.takeIf { it > 0 }?.let { "${it} ctx" },
        sizeLabel(),
    ).joinToString(" · ")

    private fun sizeLabel(): String? = when {
        sizeBytes <= 0L -> null
        sizeBytes >= 1L shl 30 -> "%.1f GB".format(sizeBytes / (1L shl 30).toDouble())
        sizeBytes >= 1L shl 20 -> "%.0f MB".format(sizeBytes / (1L shl 20).toDouble())
        else -> "%.0f kB".format(sizeBytes / 1024.0)
    }
}
