package dev.charaly.runtime.model

import kotlinx.serialization.Serializable

/**
 * How much to trust a catalog entry's numbers.
 *
 * A model library that prints a confident file size it has not verified is lying in a
 * very small way, and those lies compound: the user reserves the wrong amount of
 * space, then discovers at install time that the download will not fit.
 */
@Serializable
enum class MetadataConfidence {
    /** Sizes and requirements have been checked against a real artifact. */
    VERIFIED,

    /** Derived from parameter count and a known-good quant; close, not exact. */
    APPROXIMATE,

    /** Not published yet. The UI says so rather than inventing a number. */
    UNPUBLISHED,
    ;

    val isTrustworthy: Boolean get() = this == VERIFIED
}

/**
 * A model in the *catalog*: something the user could install.
 *
 * This is metadata only. Charaly ships no model binaries, and the catalog never
 * implies that a file exists on the device: that is what
 * [dev.charaly.runtime.model.InstalledModel] is for.
 */
@Serializable
data class ModelCatalogItem(
    val id: String,
    val name: String,
    val publisher: String,
    val description: String = "",
    val parameterCount: Long = 0L,
    val sizeBytes: Long = 0L,
    val quantization: String = "",
    val architecture: String = "",
    val contextLength: Int = 4096,
    /** Rough total RAM needed to hold the model plus a usable context. */
    val recommendedRamBytes: Long = 0L,
    /** 1..5, how well this model holds a long roleplay without drifting. */
    val roleplaySuitability: Int = 3,
    val license: String = "",
    val licenseUrl: String = "",
    val tags: List<String> = emptyList(),
    val download: DownloadMetadata? = null,
    val isRecommended: Boolean = false,
    /**
     * The upstream publication identifier, e.g. "google/gemma-4-E2B-it".
     *
     * Recorded so the catalog entry can be traced back to its source, and so it is
     * obvious that this is a *publication* fact and not a statement about whether the
     * bundled engine can load it. That question is answered by [engineVerdict].
     */
    val upstreamId: String = "",
    /** GGUF artifacts that exist for this model, if any are known. */
    val ggufArtifacts: List<String> = emptyList(),
    /** How much to trust [sizeBytes] and [recommendedRamBytes]. */
    val confidence: MetadataConfidence = MetadataConfidence.APPROXIMATE,
) {
    init {
        require(id.isNotBlank()) { "ModelCatalogItem needs an id" }
        require(name.isNotBlank()) { "Catalog item $id needs a name" }
        require(roleplaySuitability in 1..5) { "roleplaySuitability must be 1..5" }
    }

    val sizeLabel: String get() = formatBytes(sizeBytes)

    /**
     * The size, or an honest admission that it is not known.
     *
     * "unknown" is a legitimate answer here; a fabricated number is not.
     */
    val sizeDisplay: String
        get() = if (sizeBytes <= 0L) "size not published" else formatBytes(sizeBytes)

    val ramLabel: String
        get() = when {
            recommendedRamBytes <= 0L -> "unknown"
            // Anything not verified against a real artifact is an estimate, and is
            // labelled as one rather than presented as a hard requirement.
            confidence.isTrustworthy -> "${formatBytes(recommendedRamBytes)} free"
            else -> "about ${formatBytes(recommendedRamBytes)} free"
        }

    val parameterLabel: String
        get() = when {
            parameterCount >= 1_000_000_000 -> "%.1fB".format(parameterCount / 1_000_000_000.0)
            parameterCount >= 1_000_000 -> "%dM".format(parameterCount / 1_000_000)
            parameterCount > 0 -> parameterCount.toString()
            else -> "unknown size"
        }

    /**
     * Whether the engine compiled into this app can actually load this model.
     *
     * Derived from [EngineCapabilities] rather than asserted per entry, so the library
     * cannot advertise something the engine will refuse three screens later.
     */
    val engineVerdict: EngineVerdict get() = EngineVerdict.of(architecture)

    val isEngineLoadable: Boolean get() = engineVerdict.isLoadable

    /**
     * Whether this entry should be shown with a "needs an engine update" treatment.
     *
     * Kept visible rather than filtered out: hiding a model the user has heard of is
     * worse than showing it honestly, as long as the reason is clear.
     */
    val needsEngineUpdate: Boolean
        get() = engineVerdict.support == EngineSupport.ENGINE_UPDATE_REQUIRED

    fun isSuitableForDevice(availableRamBytes: Long): Boolean =
        availableRamBytes <= 0L || recommendedRamBytes in 1..availableRamBytes

    /** Small models first: the library must lead with what actually runs. */
    fun weight(): Int = when {
        !isEngineLoadable -> 3
        roleplaySuitability >= 4 -> 0
        roleplaySuitability >= 3 -> 1
        else -> 2
    }
}

/**
 * Where a download would come from.
 *
 * Charaly declares no INTERNET permission, so `source` is metadata that the
 * download pipeline validates against the device's capabilities before offering
 * the action. When downloads are unavailable, the UI shows an honest disabled
 * state instead of a fake progress bar.
 */
@Serializable
data class DownloadMetadata(
    val source: DownloadSource = DownloadSource.HTTP,
    val url: String = "",
    val fileName: String = "",
    val sizeBytes: Long = 0L,
    /** sha256 of the file, lowercase hex. Empty means "cannot be verified". */
    val sha256: String = "",
    val mirrors: List<String> = emptyList(),
    val notes: String = "",
)

@Serializable
enum class DownloadSource {
    /** Direct HTTPS. Requires a network capability the app may not have. */
    HTTP,

    /** User-supplied file through the system file picker. Always available. */
    LOCAL_FILE,
}

/**
 * The catalog port.
 *
 * Adding a source later means implementing this interface, not touching the UI.
 */
interface ModelCatalog {
    val items: List<ModelCatalogItem>

    fun byId(id: String): ModelCatalogItem? = items.firstOrNull { it.id == id }

    /**
     * Models worth suggesting *on this device*.
     *
     * Filtered by what the engine can actually load and by available memory, because a
     * recommendation the user cannot act on is noise. Never promises performance - it
     * only avoids obviously unsuitable sizes.
     */
    fun recommendedFor(availableRamBytes: Long): List<ModelCatalogItem> =
        items
            .filter { it.isEngineLoadable }
            .filter { it.isRecommended }
            .filter { it.isSuitableForDevice(availableRamBytes) }
            .sortedWith(compareBy({ it.weight() }, { it.recommendedRamBytes }, { it.name }))

    fun recommended(): List<ModelCatalogItem> = items.filter { it.isRecommended }.sortedBy { it.weight() }

    fun sortedForLibrary(): List<ModelCatalogItem> =
        items.sortedWith(compareBy({ it.weight() }, { it.sizeBytes }, { it.name }))

    /**
     * Entries the engine cannot load yet, so the library can show them honestly rather
     * than hiding a model the user has heard of.
     */
    fun awaitingEngineUpdate(): List<ModelCatalogItem> = items.filter { it.needsEngineUpdate }
}

/**
 * The catalog Charaly ships with.
 *
 * These are *descriptions of models users can install themselves*, with the
 * honest label "not installed" until a real file exists. No binaries are bundled
 * and no fake download progress exists anywhere in the app.
 */
class BuiltInModelCatalog(
    override val items: List<ModelCatalogItem> = DEFAULT,
) : ModelCatalog {

    companion object {
        val DEFAULT: List<ModelCatalogItem> = listOf(
            ModelCatalogItem(
                id = "qwen3-1.7b-q4",
                name = "Qwen 3 1.7B Instruct",
                publisher = "Qwen",
                description = "The smallest model that still holds a conversation. A good first install on a modest phone.",
                parameterCount = 1_700_000_000L,
                sizeBytes = 1_120_000_000L,
                quantization = "Q4_K_M",
                architecture = "Qwen3",
                contextLength = 4096,
                recommendedRamBytes = 2_400_000_000L,
                roleplaySuitability = 3,
                license = "Apache 2.0",
                tags = listOf("small", "fast", "chat"),
                isRecommended = true,
            ),
            ModelCatalogItem(
                id = "qwen3-4b-q4",
                name = "Qwen 3 4B Instruct",
                publisher = "Qwen",
                description = "The sweet spot on a modern phone: noticeably better voice and memory than 1.7B.",
                parameterCount = 4_000_000_000L,
                sizeBytes = 2_600_000_000L,
                quantization = "Q4_K_M",
                architecture = "Qwen3",
                contextLength = 8192,
                recommendedRamBytes = 6_500_000_000L,
                roleplaySuitability = 5,
                license = "Apache 2.0",
                tags = listOf("roleplay", "recommended"),
                isRecommended = true,
            ),
            ModelCatalogItem(
                id = "qwen3-8b-q4",
                name = "Qwen 3 8B Instruct",
                publisher = "Qwen",
                description = "Strongest prose of the Qwen line, but heavy. Needs a recent device with plenty of free memory.",
                parameterCount = 8_000_000_000L,
                sizeBytes = 5_200_000_000L,
                quantization = "Q4_K_M",
                architecture = "Qwen3",
                contextLength = 8192,
                recommendedRamBytes = 9_500_000_000L,
                roleplaySuitability = 5,
                license = "Apache 2.0",
                tags = listOf("roleplay", "large", "slow"),
            ),
            ModelCatalogItem(
                id = "llama-3.2-3b-q4",
                name = "Llama 3.2 3B Instruct",
                publisher = "Meta",
                description = "A reliable, widely supported general model. Comfortable on mid-range hardware.",
                parameterCount = 3_200_000_000L,
                sizeBytes = 2_000_000_000L,
                quantization = "Q4_K_M",
                architecture = "Llama",
                contextLength = 8192,
                recommendedRamBytes = 5_000_000_000L,
                roleplaySuitability = 4,
                license = "Llama 3.2 Community License",
                tags = listOf("chat", "general"),
                isRecommended = true,
            ),
            ModelCatalogItem(
                id = "gemma-2-2b-q4",
                name = "Gemma 2 2B",
                publisher = "Google",
                description = "Small, quick to load, and surprisingly good at staying in character for its size.",
                parameterCount = 2_600_000_000L,
                sizeBytes = 1_700_000_000L,
                quantization = "Q4_K_M",
                architecture = "Gemma2",
                contextLength = 8192,
                recommendedRamBytes = 4_200_000_000L,
                roleplaySuitability = 3,
                license = "Gemma Terms of Use",
                tags = listOf("small", "fast"),
            ),
            ModelCatalogItem(
                id = "mistral-nemo-12b-q4",
                name = "Mistral Nemo 12B",
                publisher = "Mistral AI",
                description = "Very good long-form writing and instruction following. Effectively desktop-class memory.",
                parameterCount = 12_000_000_000L,
                sizeBytes = 7_100_000_000L,
                quantization = "Q4_K_M",
                architecture = "Llama",
                contextLength = 8192,
                recommendedRamBytes = 16_000_000_000L,
                roleplaySuitability = 5,
                license = "Apache 2.0",
                tags = listOf("large", "writer"),
                confidence = MetadataConfidence.APPROXIMATE,
            ),
            // ---- Gemma 4 -----------------------------------------------------
            //
            // These are real, published Google models: the E2B and E4B sizes are the
            // ones Google designates for on-device / edge deployment, and the ids below
            // are the official ones.
            //
            // What is NOT verified is that the llama.cpp compiled into this app can load
            // a GGUF of them. The bundled engine (commit 5143fa895, 2025-09-05) registers
            // gemma, gemma2, gemma3, gemma3n and gemma-embedding - and no gemma4. A GGUF
            // whose general.architecture is "gemma4" cannot be loaded by it, so these
            // entries deliberately resolve to EngineSupport.ENGINE_UPDATE_REQUIRED and the
            // library shows that instead of offering a download that would fail later.
            //
            // Sizes are left unstated rather than guessed, because no verified GGUF
            // artifact has been checked here.
            ModelCatalogItem(
                id = "gemma-4-e2b-it",
                name = "Gemma 4 E2B Instruct",
                publisher = "Google",
                description = "Google's on-device Gemma 4 in the smaller E2B size - the one " +
                    "aimed at phones and tablets with limited memory. Best starting point " +
                    "for smaller devices.",
                parameterCount = 2_000_000_000L,
                sizeBytes = 0L,
                quantization = "",
                architecture = "gemma4",
                contextLength = 8192,
                recommendedRamBytes = 4_000_000_000L,
                roleplaySuitability = 4,
                license = "Gemma Terms of Use",
                licenseUrl = "https://ai.google.dev/gemma/terms",
                tags = listOf("gemma", "edge", "mobile", "small", "recommended"),
                upstreamId = "google/gemma-4-E2B-it",
                confidence = MetadataConfidence.UNPUBLISHED,
            ),
            ModelCatalogItem(
                id = "gemma-4-e4b-it",
                name = "Gemma 4 E4B Instruct",
                publisher = "Google",
                description = "Google's on-device Gemma 4 in the larger E4B size. Google's " +
                    "recommended balance for tablets and recent phones - noticeably better " +
                    "at holding a long conversation without drifting.",
                parameterCount = 4_000_000_000L,
                sizeBytes = 0L,
                quantization = "",
                architecture = "gemma4",
                contextLength = 8192,
                recommendedRamBytes = 7_000_000_000L,
                roleplaySuitability = 5,
                license = "Gemma Terms of Use",
                licenseUrl = "https://ai.google.dev/gemma/terms",
                tags = listOf("gemma", "edge", "mobile", "roleplay", "recommended"),
                upstreamId = "google/gemma-4-E4B-it",
                confidence = MetadataConfidence.UNPUBLISHED,
            ),
        )

        /** The Gemma 4 entries, so tests and the UI agree on what "Gemma 4" means. */
        val GEMMA_4_IDS: List<String> = listOf("gemma-4-e2b-it", "gemma-4-e4b-it")
    }
}

/**
 * Byte formatting shared by the library and the model detail screen.
 *
 * The sub-kilobyte case exists because a download that has just started reports a few
 * hundred bytes, and "0 kB" there would look like a frozen progress bar rather than a
 * transfer in progress.
 */
fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "unknown"
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f kB".format(bytes / 1024.0)
    else -> "$bytes B"
}