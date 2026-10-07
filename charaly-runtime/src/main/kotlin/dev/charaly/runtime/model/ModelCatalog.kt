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
 * Recorded rather than assumed: the pipeline checks the source against its own capabilities
 * before offering the action, so a `LOCAL_FILE` entry and an `HTTP` entry behave correctly
 * without the UI having to know which is which. When a transport is unavailable the library
 * reports that honestly instead of showing a progress bar that goes nowhere.
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
                // Real Q4_K_M artifact size, measured on the Hub 2026-10-07.
                sizeBytes = 1_282_439_584L,
                quantization = "Q4_K_M",
                architecture = "Qwen3",
                contextLength = 4096,
                recommendedRamBytes = 2_400_000_000L,
                roleplaySuitability = 3,
                license = "Apache 2.0",
                tags = listOf("small", "fast", "chat"),
                isRecommended = true,
                upstreamId = "bartowski/Qwen_Qwen3-1.7B-GGUF",
                ggufArtifacts = listOf(
                    "Qwen_Qwen3-1.7B-Q4_K_M.gguf",
                    "Qwen_Qwen3-1.7B-Q5_K_M.gguf",
                    "Qwen_Qwen3-1.7B-Q6_K.gguf",
                    "Qwen_Qwen3-1.7B-Q8_0.gguf",
                ),
                download = DownloadMetadata(
                    url = "https://huggingface.co/bartowski/Qwen_Qwen3-1.7B-GGUF/resolve/main/Qwen_Qwen3-1.7B-Q4_K_M.gguf",
                    fileName = "Qwen_Qwen3-1.7B-Q4_K_M.gguf",
                    sizeBytes = 1_282_439_584L,
                    sha256 = "72c5c3cb38fa32d5256e2fe30d03e7a64c6c79e668ad84057e3bd66e250b24fb",
                ),
            ),
            ModelCatalogItem(
                id = "qwen3-4b-q4",
                name = "Qwen 3 4B Instruct",
                publisher = "Qwen",
                description = "The sweet spot on a modern phone: noticeably better voice and memory than 1.7B.",
                parameterCount = 4_000_000_000L,
                // Real Q4_K_M artifact size, measured on the Hub 2026-10-07.
                sizeBytes = 2_497_280_960L,
                quantization = "Q4_K_M",
                architecture = "Qwen3",
                contextLength = 8192,
                recommendedRamBytes = 6_500_000_000L,
                roleplaySuitability = 5,
                license = "Apache 2.0",
                tags = listOf("roleplay", "recommended"),
                isRecommended = true,
                upstreamId = "bartowski/Qwen_Qwen3-4B-GGUF",
                ggufArtifacts = listOf(
                    "Qwen_Qwen3-4B-Q4_K_M.gguf",
                    "Qwen_Qwen3-4B-Q5_K_M.gguf",
                    "Qwen_Qwen3-4B-Q6_K.gguf",
                    "Qwen_Qwen3-4B-Q8_0.gguf",
                ),
                download = DownloadMetadata(
                    url = "https://huggingface.co/bartowski/Qwen_Qwen3-4B-GGUF/resolve/main/Qwen_Qwen3-4B-Q4_K_M.gguf",
                    fileName = "Qwen_Qwen3-4B-Q4_K_M.gguf",
                    sizeBytes = 2_497_280_960L,
                    sha256 = "fbe1d5edd4ce802ae3ae7c7e4ab7d09789d697fdac1fc7929f8df4ca3c41bae3",
                ),
            ),
            ModelCatalogItem(
                id = "qwen3-8b-q4",
                name = "Qwen 3 8B Instruct",
                publisher = "Qwen",
                description = "Strongest prose of the Qwen line, but heavy. Needs a recent device with plenty of free memory.",
                parameterCount = 8_000_000_000L,
                // Real Q4_K_M artifact size, measured on the Hub 2026-10-07.
                sizeBytes = 5_027_784_224L,
                quantization = "Q4_K_M",
                architecture = "Qwen3",
                contextLength = 8192,
                recommendedRamBytes = 9_500_000_000L,
                roleplaySuitability = 5,
                license = "Apache 2.0",
                tags = listOf("roleplay", "large", "slow"),
                upstreamId = "bartowski/Qwen_Qwen3-8B-GGUF",
                ggufArtifacts = listOf(
                    "Qwen_Qwen3-8B-Q4_K_M.gguf",
                    "Qwen_Qwen3-8B-Q5_K_M.gguf",
                    "Qwen_Qwen3-8B-Q6_K.gguf",
                    "Qwen_Qwen3-8B-Q8_0.gguf",
                ),
                download = DownloadMetadata(
                    url = "https://huggingface.co/bartowski/Qwen_Qwen3-8B-GGUF/resolve/main/Qwen_Qwen3-8B-Q4_K_M.gguf",
                    fileName = "Qwen_Qwen3-8B-Q4_K_M.gguf",
                    sizeBytes = 5_027_784_224L,
                    sha256 = "54fffa050078e984116639c83dfb64b5aa6d4cd474e018b076777c632bbccccd",
                ),
            ),
            ModelCatalogItem(
                id = "llama-3.2-3b-q4",
                name = "Llama 3.2 3B Instruct",
                publisher = "Meta",
                description = "A reliable, widely supported general model. Comfortable on mid-range hardware.",
                parameterCount = 3_200_000_000L,
                // Real Q4_K_M artifact size, measured on the Hub 2026-10-07.
                sizeBytes = 2_019_377_696L,
                quantization = "Q4_K_M",
                architecture = "Llama",
                contextLength = 8192,
                recommendedRamBytes = 5_000_000_000L,
                roleplaySuitability = 4,
                license = "Llama 3.2 Community License",
                tags = listOf("chat", "general"),
                isRecommended = true,
                upstreamId = "bartowski/Llama-3.2-3B-Instruct-GGUF",
                ggufArtifacts = listOf(
                    "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
                    "Llama-3.2-3B-Instruct-Q5_K_M.gguf",
                    "Llama-3.2-3B-Instruct-Q6_K.gguf",
                    "Llama-3.2-3B-Instruct-Q8_0.gguf",
                ),
                download = DownloadMetadata(
                    url = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
                    fileName = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
                    sizeBytes = 2_019_377_696L,
                    sha256 = "6c1a2b41161032677be168d354123594c0e6e67d2b9227c84f296ad037c728ff",
                ),
            ),
            ModelCatalogItem(
                id = "llama-3.2-1b-q4",
                name = "Llama 3.2 1B Instruct",
                publisher = "Meta",
                description = "The smallest current Llama. Loads on nearly any phone and answers immediately.",
                parameterCount = 1_200_000_000L,
                sizeBytes = 807_694_464L,
                quantization = "Q4_K_M",
                architecture = "Llama",
                contextLength = 8192,
                recommendedRamBytes = 2_000_000_000L,
                roleplaySuitability = 2,
                license = "Llama 3.2 Community License",
                tags = listOf("small", "fast", "general"),
                isRecommended = true,
                upstreamId = "bartowski/Llama-3.2-1B-Instruct-GGUF",
                ggufArtifacts = listOf(
                    "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
                    "Llama-3.2-1B-Instruct-Q5_K_M.gguf",
                    "Llama-3.2-1B-Instruct-Q6_K.gguf",
                    "Llama-3.2-1B-Instruct-Q8_0.gguf",
                ),
                download = DownloadMetadata(
                    url = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
                    fileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
                    sizeBytes = 807_694_464L,
                    sha256 = "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83",
                ),
            ),
            ModelCatalogItem(
                id = "gemma-2-2b-q4",
                name = "Gemma 2 2B",
                publisher = "Google",
                description = "Small, quick to load, and surprisingly good at staying in character for its size.",
                parameterCount = 2_600_000_000L,
                // Real Q4_K_M artifact size, measured on the Hub 2026-10-07.
                sizeBytes = 1_708_582_752L,
                quantization = "Q4_K_M",
                architecture = "Gemma2",
                contextLength = 8192,
                recommendedRamBytes = 4_200_000_000L,
                roleplaySuitability = 3,
                license = "Gemma Terms of Use",
                tags = listOf("small", "fast"),
                upstreamId = "bartowski/gemma-2-2b-it-GGUF",
                ggufArtifacts = listOf(
                    "gemma-2-2b-it-Q4_K_M.gguf",
                    "gemma-2-2b-it-Q5_K_M.gguf",
                    "gemma-2-2b-it-Q6_K.gguf",
                    "gemma-2-2b-it-Q8_0.gguf",
                ),
                download = DownloadMetadata(
                    url = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
                    fileName = "gemma-2-2b-it-Q4_K_M.gguf",
                    sizeBytes = 1_708_582_752L,
                    sha256 = "e0aee85060f168f0f2d8473d7ea41ce2f3230c1bc1374847505ea599288a7787",
                ),
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
                upstreamId = "bartowski/Mistral-Nemo-Instruct-2407-GGUF",
                ggufArtifacts = listOf("Mistral-Nemo-Instruct-2407-Q4_K_M.gguf"),
                download = DownloadMetadata(
                    url = "https://huggingface.co/bartowski/Mistral-Nemo-Instruct-2407-GGUF/resolve/main/Mistral-Nemo-Instruct-2407-Q4_K_M.gguf",
                    fileName = "Mistral-Nemo-Instruct-2407-Q4_K_M.gguf",
                    sizeBytes = 7_477_208_192L,
                    sha256 = "7c1a10d202d8788dbe5628dc962254d10654c853cae6aaeca0618f05490d4a46",
                ),
            ),
            // ---- Gemma 4 -----------------------------------------------------
            //
            // These are real, published Google models: the E2B and E4B sizes are the
            // ones Google designates for on-device / edge deployment, and the ids below
            // are the official ones.
            //
            // What is NOT verified is that the llama.cpp compiled into this app can load a
            // GGUF of them. The bundled engine is pinned at commit `5143fa895` (2025-09-05) -
            // `git -C app/src/main/cpp/llama.cpp log -1` is the command that establishes this,
            // and it is unchanged by the work in this release. That commit's
            // `src/llama-arch.cpp` registers gemma, gemma2, gemma3, gemma3n and
            // gemma-embedding, and **no gemma4**. A GGUF whose `general.architecture` is
            // "gemma4" therefore cannot be loaded by this build.
            //
            // Upstream has since added `gemma4` (and gemma4-assistant), so these entries are
            // a *stale* refusal rather than a permanent one - but a stale refusal that is
            // honest is strictly better than an optimistic label. Claiming "supported" would
            // offer a multi-gigabyte download that fails at load time, which is worse than
            // saying nothing can be downloaded today. They resolve to
            // EngineSupport.ENGINE_UPDATE_REQUIRED, the library shows that, and
            // `ModelEngineCompatibilityTest` fails the build if the vendored engine's
            // architecture table and [EngineCapabilities.ARCHITECTURES] ever disagree - so
            // the next engine bump turns these live on its own, with no edit here.
            //
            // Sizes and downloads are now real: unsloth publishes the official Gemma 4
            // GGUFs, and the artifact sizes/SHA-256 below are measured on the Hub repo
            // tree (2026-10-07). The engine verdict is still ENGINE_UPDATE_REQUIRED.
            ModelCatalogItem(
                id = "gemma-4-e2b-it",
                name = "Gemma 4 E2B Instruct",
                publisher = "Google",
                description = "Google's on-device Gemma 4 in the smaller E2B size - the one " +
                    "aimed at phones and tablets with limited memory. Best starting point " +
                    "for smaller devices.",
                parameterCount = 2_000_000_000L,
                // Real Q4_K_M artifact size, measured on the Hub 2026-10-07.
                sizeBytes = 3_106_738_272L,
                quantization = "Q4_K_M",
                architecture = "gemma4",
                contextLength = 8192,
                recommendedRamBytes = 4_000_000_000L,
                roleplaySuitability = 4,
                license = "Gemma Terms of Use",
                licenseUrl = "https://ai.google.dev/gemma/terms",
                tags = listOf("gemma", "edge", "mobile", "small", "recommended"),
                upstreamId = "unsloth/gemma-4-E2B-it-GGUF",
                ggufArtifacts = listOf(
                    "gemma-4-E2B-it-Q4_K_M.gguf",
                    "gemma-4-E2B-it-Q5_K_M.gguf",
                    "gemma-4-E2B-it-Q8_0.gguf",
                ),
                download = DownloadMetadata(
                    url = "https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_K_M.gguf",
                    fileName = "gemma-4-E2B-it-Q4_K_M.gguf",
                    sizeBytes = 3_106_738_272L,
                    sha256 = "740185b21d22ceb83a11c3aa62ad5842ef32c70f6096d756bbee85a1e4ec34b8",
                ),
                confidence = MetadataConfidence.VERIFIED,
            ),
            ModelCatalogItem(
                id = "gemma-4-e4b-it",
                name = "Gemma 4 E4B Instruct",
                publisher = "Google",
                description = "Google's on-device Gemma 4 in the larger E4B size. Google's " +
                    "recommended balance for tablets and recent phones - noticeably better " +
                    "at holding a long conversation without drifting.",
                parameterCount = 4_000_000_000L,
                // Real Q4_K_M artifact size, measured on the Hub 2026-10-07.
                sizeBytes = 4_977_171_584L,
                quantization = "Q4_K_M",
                architecture = "gemma4",
                contextLength = 8192,
                recommendedRamBytes = 7_000_000_000L,
                roleplaySuitability = 5,
                license = "Gemma Terms of Use",
                licenseUrl = "https://ai.google.dev/gemma/terms",
                tags = listOf("gemma", "edge", "mobile", "roleplay", "recommended"),
                upstreamId = "unsloth/gemma-4-E4B-it-GGUF",
                ggufArtifacts = listOf(
                    "gemma-4-E4B-it-Q4_K_M.gguf",
                    "gemma-4-E4B-it-Q5_K_M.gguf",
                    "gemma-4-E4B-it-Q8_0.gguf",
                ),
                download = DownloadMetadata(
                    url = "https://huggingface.co/unsloth/gemma-4-E4B-it-GGUF/resolve/main/gemma-4-E4B-it-Q4_K_M.gguf",
                    fileName = "gemma-4-E4B-it-Q4_K_M.gguf",
                    sizeBytes = 4_977_171_584L,
                    sha256 = "85a896a047553e842f25297ee5b031d64ff30147d9c4af17b1e4b394cd1fab87",
                ),
                confidence = MetadataConfidence.VERIFIED,
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