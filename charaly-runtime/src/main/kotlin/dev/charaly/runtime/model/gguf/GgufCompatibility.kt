package dev.charaly.runtime.model.gguf

import dev.charaly.runtime.model.EngineCapabilities
import dev.charaly.runtime.model.EngineSupport
import dev.charaly.runtime.model.EngineVerdict
import dev.charaly.runtime.model.MetadataConfidence

/**
 * What Charaly can actually do with one GGUF file, on one device.
 *
 * ## The three verdicts, and why they are three
 *
 * ```
 *   CHARALY_READY        the bundled engine loads it, it has a context length, and it
 *                        fits in this device's memory. Tap "use" and it works.
 *   MANUAL_IMPORT_ONLY   the file itself is fine, but *this build* cannot run it.
 *                        Importing it is still useful - a newer Charaly, or another
 *                        engine, can use it - so the option is offered rather than hidden.
 *   UNSUPPORTED          the file is not something Charaly can ever load: no GGUF, a
 *                        corrupt header, or an architecture with no plausible mapping.
 * ```
 *
 * Collapsing the middle case into the first is how a library ends up promising a
 * "Download" that produces an error three screens later. Collapsing it into the last is
 * how it hides a model that would work fine after an engine update.
 */
enum class CharalyCompatibility {

    /** Usable now, on this device. */
    CHARALY_READY,

    /** A valid model file, but not by this build. Import is still offered. */
    MANUAL_IMPORT_ONLY,

    /** Not a model Charaly can load, by any route. */
    UNSUPPORTED,
    ;

    val isUsableNow: Boolean get() = this == CHARALY_READY

    /** Whether importing this file is a meaningful action at all. */
    val isImportable: Boolean get() = this != UNSUPPORTED
}

/**
 * A classified verdict for one artifact.
 *
 * Every field is either observed from the file or derived from
 * [EngineCapabilities] / the device's declared limits. There is no field that says
 * "probably fine".
 */
data class CompatibilityVerdict(
    val compatibility: CharalyCompatibility,
    val architecture: String,
    val engineSupport: EngineSupport,
    /** The user-facing reason. Empty only when [compatibility] is CHARALY_READY. */
    val reason: String,
    /** What the engine verdict says, independent of device fit. */
    val engineReason: String = "",
    /** RAM needed for weights plus a usable context, from the file size. */
    val estimatedRamBytes: Long = 0L,
    /** Whether the file declares a context length. */
    val hasContextLength: Boolean = false,
    val hasChatTemplate: Boolean = false,
    val confidence: MetadataConfidence = MetadataConfidence.VERIFIED,
) {
    val isReady: Boolean get() = compatibility == CharalyCompatibility.CHARALY_READY

    /** One line, for a card footer. */
    fun label(): String = when (compatibility) {
        CharalyCompatibility.CHARALY_READY -> "Ready for Charaly"
        CharalyCompatibility.MANUAL_IMPORT_ONLY -> "Manual import only"
        CharalyCompatibility.UNSUPPORTED -> "Not supported"
    }
}

/**
 * Turns a read GGUF header into a [CompatibilityVerdict].
 *
 * ## The ordering of the checks is the point
 *
 * Support is decided in this order, and the order is deliberate:
 *
 *  1. **Does the engine register this architecture?** If not, nothing else matters -
 *     a beautiful metadata block on a file llama.cpp cannot open is still unusable.
 *  2. **Does the file declare a context length?** Without one Charaly cannot budget a
 *     prompt, and guessing a default would silently truncate long scenes.
 *  3. **Does it fit in this device's memory?** A 20 GB model on a 6 GB phone is
 *     loadable-in-principle and unloadable-in-practice.
 *
 * Reading them in that order means the reason a user sees is the *first* real blocker,
 * not whichever check happened to be written last.
 */
object GgufCompatibility {

    /**
     * RAM overhead beyond the file size, for the KV cache and runtime buffers.
     *
     * A 20% allowance on the weights plus a flat 512 MiB. This is a heuristic, and it
     * is labelled one: it is used to warn, and to sort recommendations, never to refuse
     * a model outright. The real limit is enforced by the loader failing at load time,
     * which is the only measurement that cannot lie.
     */
    const val KV_CACHE_ALLOWANCE = 0.20
    const val RUNTIME_OVERHEAD_BYTES = 512L * 1024L * 1024L

    /** Context tokens to assume when budgeting KV cache, if the file does not say. */
    const val ASSUMED_CONTEXT_TOKENS = 4096

    /** Classifies a successfully-read header. */
    fun classify(
        metadata: GgufMetadata,
        fileSizeBytes: Long = 0L,
        availableRamBytes: Long = 0L,
        requireChatTemplate: Boolean = false,
    ): CompatibilityVerdict {
        val architecture = metadata.architecture
        val engineVerdict: EngineVerdict = EngineVerdict.of(architecture)
        val contextLength = metadata.contextLength
        val size = fileSizeBytes.takeIf { it > 0L } ?: 0L
        val ram = estimateRam(size, contextLength, metadata)
        val hasTemplate = metadata.hasChatTemplate

        // --- 1. engine support ------------------------------------------------
        if (!engineVerdict.isLoadable) {
            val verdict = when (engineVerdict.support) {
                EngineSupport.ENGINE_UPDATE_REQUIRED -> CharalyCompatibility.MANUAL_IMPORT_ONLY
                else -> CharalyCompatibility.UNSUPPORTED
            }
            return CompatibilityVerdict(
                compatibility = verdict,
                architecture = architecture,
                engineSupport = engineVerdict.support,
                reason = engineVerdict.reason,
                engineReason = engineVerdict.reason,
                estimatedRamBytes = ram,
                hasContextLength = contextLength > 0,
                hasChatTemplate = hasTemplate,
            )
        }

        // --- 2. context length ------------------------------------------------
        //
        // A file with no declared context is still importable: llama.cpp substitutes a
        // default. It is not *ready*, because Charaly has to cap the prompt at something
        // and picking a number the file never stated would be a guess.
        if (contextLength <= 0) {
            return CompatibilityVerdict(
                compatibility = CharalyCompatibility.MANUAL_IMPORT_ONLY,
                architecture = architecture,
                engineSupport = EngineSupport.SUPPORTED,
                reason = "This file does not declare a context length, so Charaly cannot " +
                    "size the story prompt. A model published with one is needed for full use.",
                engineReason = engineVerdict.reason,
                estimatedRamBytes = ram,
                hasContextLength = false,
                hasChatTemplate = hasTemplate,
            )
        }

        // --- 3. device fit ----------------------------------------------------
        //
        // Warn-only. The loader is the real authority on whether a model fits; refusing
        // here would block a model that turns out to fit perfectly well on a device that
        // reports a conservative free-memory figure.
        val tooLargeForDevice = availableRamBytes > 0L && ram > availableRamBytes

        val ready = !tooLargeForDevice && (!requireChatTemplate || hasTemplate)

        val reason = when {
            tooLargeForDevice -> "This model needs about " +
                "${formatBytesShort(ram)} of free memory and this device reports " +
                "${formatBytesShort(availableRamBytes)}. It may fail to load."
            requireChatTemplate && !hasTemplate ->
                "This model has no chat template, so Charaly cannot format the story prompt."
            else -> ""
        }

        return CompatibilityVerdict(
            compatibility = if (ready) CharalyCompatibility.CHARALY_READY else CharalyCompatibility.MANUAL_IMPORT_ONLY,
            architecture = architecture,
            engineSupport = EngineSupport.SUPPORTED,
            reason = reason,
            engineReason = engineVerdict.reason,
            estimatedRamBytes = ram,
            hasContextLength = true,
            hasChatTemplate = hasTemplate,
        )
    }

    /** Classifies a read result, mapping a header failure straight to UNSUPPORTED. */
    fun classify(
        read: GgufReadResult,
        fileSizeBytes: Long = 0L,
        availableRamBytes: Long = 0L,
    ): CompatibilityVerdict = when (read) {
        is GgufReadResult.Success -> classify(read.metadata, fileSizeBytes, availableRamBytes)
        is GgufReadResult.Failure -> CompatibilityVerdict(
            compatibility = CharalyCompatibility.UNSUPPORTED,
            architecture = "",
            engineSupport = EngineSupport.UNKNOWN_ARCHITECTURE,
            reason = read.reason,
            engineReason = read.reason,
            estimatedRamBytes = 0L,
            hasContextLength = false,
            hasChatTemplate = false,
        )
    }

    /**
     * RAM a model needs: the weights, plus a KV cache for [contextLength] tokens.
     *
     * The KV cache estimate assumes 2 bytes per element across 2 (K and V) matrices per
     * layer, times the layer count. It is a rough figure on purpose - the loader's own
     * allocation is the truth - but it is in the right order of magnitude, which is all a
     * pre-download warning needs.
     */
    fun estimateRam(weightsBytes: Long, contextLength: Int, metadata: GgufMetadata? = null): Long {
        if (weightsBytes <= 0L) return 0L
        val kv = if (metadata != null) estimateKvCacheBytes(metadata, contextLength) else 0L
        return (weightsBytes * (1.0 + KV_CACHE_ALLOWANCE)).toLong() + kv + RUNTIME_OVERHEAD_BYTES
    }

    /**
     * KV cache size for a context, when the layer count is known.
     *
     * Returns 0 when the architecture metadata is unavailable, in which case the caller
     * falls back to the weights-only allowance.
     */
    fun estimateKvCacheBytes(metadata: GgufMetadata, contextLength: Int): Long {
        val layers = metadata.blockCount
        val embd = metadata.embeddingLength
        if (layers <= 0 || embd <= 0 || contextLength <= 0) return 0L
        // 2 (K,V) x 2 bytes x layers x embd x context, halved for GQA's narrower cache,
        // which is the common case and the reason this is an estimate rather than a
        // constant.
        val kvHeadsDivisor = 2L
        return (2L * 2L * layers * embd * contextLength / kvHeadsDivisor)
    }

    private fun formatBytesShort(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
        else -> "${bytes / 1024} kB"
    }
}
