package dev.charaly.runtime.model

import kotlinx.serialization.Serializable

/**
 * What this device can actually do, as measured rather than assumed.
 *
 * ## Why "measured" is the whole point
 *
 * The brief asks for labels like "Fast", "Balanced" and "Quality", and explicitly says
 * they must be backed by real data rather than invented. That constraint rules out the
 * obvious implementation, which is to sort models by parameter count and call the small
 * ones fast - a heuristic that is wrong on exactly the devices where it matters, because
 * a phone with a fast NPU and a phone with none both get the same label.
 *
 * So this type carries three separately-sourced facts:
 *
 * ```
 *   totalRamBytes      the platform's figure
 *   availableRamBytes  what the platform says is currently free
 *   benchmark          MEASURED on this device, or absent
 * ```
 *
 * and every label is a function of them. With no benchmark, the presenter says
 * [RecommendationBasis.DEVICE_CLASS] - "fits your device" - which is a claim the platform
 * figures actually support. Only after a real measurement does it say "fast", and then
 * the number it used is available to show.
 */
data class DeviceCapability(
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    /** Measured on this device. Null until a benchmark has actually run. */
    val benchmark: ModelBenchmark? = null,
) {
    /**
     * Whether the platform will tell us anything.
     *
     * Some devices report -1 for both figures. Rather than guessing, this returns false
     * and every recommendation falls back to "no size claim made".
     */
    val hasMemoryFigures: Boolean
        get() = totalRamBytes > 0L && availableRamBytes > 0L

    /** A coarse class, for filtering the catalog when there is no benchmark. */
    val deviceClass: DeviceClass
        get() = when {
            !hasMemoryFigures -> DeviceClass.UNKNOWN
            totalRamBytes >= LARGE_RAM -> DeviceClass.ROOMY
            totalRamBytes >= MID_RAM -> DeviceClass.NORMAL
            else -> DeviceClass.TIGHT
        }

    /**
     * How confident a performance label is.
     *
     * Exposed to the UI so it can qualify what it says: "Fast on this device" from a
     * measurement is a claim; "fits your device" from a RAM figure is a different kind of
     * claim, and conflating them is how a library ends up promising speed it never tested.
     */
    fun basisFor(model: InstalledModel): RecommendationBasis = when {
        benchmark != null -> RecommendationBasis.MEASURED_ON_THIS_DEVICE
        hasMemoryFigures -> RecommendationBasis.DEVICE_CLASS
        else -> RecommendationBasis.UNKNOWN
    }

    companion object {
        const val TIGHT_RAM = 3L * 1024 * 1024 * 1024
        const val MID_RAM = 6L * 1024 * 1024 * 1024
        const val LARGE_RAM = 10L * 1024 * 1024 * 1024

        /** What the JVM tests and previews use. */
        val UNKNOWN = DeviceCapability(0L, 0L, null)
    }
}

enum class DeviceClass {
    /** Under 3 GB. One small model, and it has to be a small one. */
    TIGHT,

    /** 3-6 GB. The common case for a mid-range phone. */
    NORMAL,

    /** Over 10 GB. Room for a real 7B. */
    ROOMY,

    /** The platform would not say. */
    UNKNOWN,
}

/** Why a recommendation says what it says. Shown to the user so the claim is honest. */
enum class RecommendationBasis {
    /** A real generation-speed measurement taken on this device. */
    MEASURED_ON_THIS_DEVICE,

    /** Inferred from reported RAM. A fit claim, not a speed claim. */
    DEVICE_CLASS,

    /** Nothing is known. The presenter must not make a performance claim at all. */
    UNKNOWN,
}

/**
 * A measured model on a specific device.
 *
 * ## Why this is only ever filled in by actually running something
 *
 * There is no constructor path that accepts guessed numbers: every field is produced by
 * [ModelBenchmarkRunner], which has to load the model and generate tokens. That is why
 * there is no `estimatedTokensPerSecond` field anywhere in this codebase - an estimate
 * would be indistinguishable from a measurement once stored, and the whole value of the
 * label is that it is not an estimate.
 */
@Serializable
data class ModelBenchmark(
    val modelId: String,
    /** Wall-clock load time, milliseconds. */
    val loadMillis: Long,
    /** Median generation speed over the sample, tokens per second. */
    val tokensPerSecond: Double,
    /** Tokens generated in the sample. Zero would mean no measurement. */
    val tokensMeasured: Int,
    /** Prompt evaluation speed, tokens per second. Zero when not measured. */
    val promptTokensPerSecond: Double = 0.0,
    /**
     * Time from pressing send to the first token appearing, in milliseconds.
     *
     * The number a reader actually feels. A model at 15 tok/s can still feel broken if it
     * takes nine seconds to say anything at all, and only this figure separates those two
     * cases. Zero when the engine could not time it.
     */
    val firstTokenMillis: Long = 0L,
    /** Peak resident set size observed during the run, bytes. Zero when not measured. */
    val peakMemoryBytes: Long = 0L,
    /** When this was measured. */
    val measuredAtEpochMs: Long = 0L,
    /** Free memory before the run. */
    val freeMemoryBeforeBytes: Long = 0L,
    /** Free memory after the run, for a leak check. */
    val freeMemoryAfterBytes: Long = 0L,
) {
    init {
        require(tokensMeasured > 0) {
            "a benchmark must have generated at least one token; $tokensMeasured means " +
                "nothing was measured and this should not be recorded"
        }
    }

    /**
     * Whether memory was measurably released after unloading.
     *
     * A model that is not unloaded leaks. This is the only way that shows up as a
     * measurement rather than as a user report three weeks later, so it is recorded
     * here even though nothing in the UI currently reads it.
     */
    fun releasedMemory(): Boolean {
        if (freeMemoryBeforeBytes <= 0L || freeMemoryAfterBytes <= 0L) return false
        return freeMemoryAfterBytes >= freeMemoryBeforeBytes - MEMORY_TOLERANCE_BYTES
    }

    companion object {
        /**
         * 256 MiB of slack.
         *
         * Free-memory readings fluctuate with allocation churn and other processes, so
         * demanding an exact return would fail on a healthy model.
         */
        const val MEMORY_TOLERANCE_BYTES = 256L * 1024L * 1024
    }
}

/**
 * How a model performs on this device, in words.
 *
 * ## The three tiers and where they come from
 *
 * The thresholds are deliberately coarse. The alternative - nine tiers from
 * "blistering" to "usable" - would imply a precision that a two-second sample cannot
 * deliver, and a user who is told a model is "fast" and finds it is not has been told
 * something false however true the number was.
 *
 * Fast means roughly "reads as instant while typing". Below the floor, the honest answer
 * is [TIER_TOO_SLOW] rather than a bad Fast.
 */
enum class PerformanceTier(val label: String) {
    FAST("Hızlı"),
    BALANCED("Dengeli"),
    QUALITY("Kaliteli"),
    TOO_SLOW("Uzun sahneler için çok yavaş"),
    ;

    val isUsable: Boolean get() = this != TOO_SLOW

    companion object {
        /** Tokens/sec at which prose reads as immediate rather than as waiting. */
        const val FAST_TOKENS_PER_SECOND = 20.0

        /** Below this, a multi-turn scene is painful. */
        const val USABLE_TOKENS_PER_SECOND = 6.0

        fun of(tokensPerSecond: Double): PerformanceTier = when {
            tokensPerSecond >= FAST_TOKENS_PER_SECOND -> FAST
            tokensPerSecond >= USABLE_TOKENS_PER_SECOND -> BALANCED
            else -> TOO_SLOW
        }
    }
}

/**
 * The user-facing recommendation for one model on one device.
 *
 * @param tier a measured performance tier, or null when nothing has been measured.
 * @param basis what the tier (or its absence) rests on. Never omitted, because a tier
 *   without a basis is a claim with nothing behind it.
 */
@Serializable
data class ModelRecommendation(
    val modelId: String,
    val tier: PerformanceTier?,
    val basis: RecommendationBasis,
    /** "Fast on this device", or "Fits your device". Never blank. */
    val label: String,
    /** The measurement, when there is one: "18.4 tok/s on this device". */
    val evidence: String = "",
    /** Set when the model does not fit in memory, regardless of speed. */
    val memoryWarning: String = "",
) {
    /**
     * Whether a performance claim is being made at all.
     *
     * The UI checks this rather than checking [tier], because a null tier with a
     * DEVICE_CLASS basis still has something useful to say - it just is not about speed.
     */
    val claimsSpeed: Boolean get() = basis == RecommendationBasis.MEASURED_ON_THIS_DEVICE

    val isRecommended: Boolean
        get() = memoryWarning.isEmpty() && tier?.isUsable == true
}

/**
 * Turns a model and a device into a recommendation.
 *
 * ## The rule this encodes
 *
 * `Fast`, `Balanced` and `Quality` may only be used when a benchmark exists. Without one,
 * the honest label is about *fit*, which the platform's RAM figures do support:
 *
 * ```
 *   measured  -> "Fast on this device"  + the tok/s it was measured at
 *   RAM only  -> "Fits your device"
 *   nothing   -> no claim
 * ```
 *
 * This is the difference between a model library that informs and one that flatters.
 */
object ModelRecommender {

    fun recommend(
        model: InstalledModel,
        device: DeviceCapability,
    ): ModelRecommendation {
        val benchmark = device.benchmark?.takeIf { it.modelId == model.id }
        val memoryWarning = memoryWarning(model, device)

        if (benchmark != null) {
            val tier = PerformanceTier.of(benchmark.tokensPerSecond)
            return ModelRecommendation(
                modelId = model.id,
                tier = tier,
                basis = RecommendationBasis.MEASURED_ON_THIS_DEVICE,
                label = when (tier) {
                    PerformanceTier.TOO_SLOW -> "Uzun sahneler için çok yavaş"
                    else -> "Bu cihazda ${tier.label}"
                },
                evidence = "%.1f tok/s, ölçüldü".format(benchmark.tokensPerSecond),
                memoryWarning = memoryWarning,
            )
        }

        // No measurement. Whatever we say now is about size, not speed, and it is
        // labelled that way.
        return ModelRecommendation(
            modelId = model.id,
            tier = null,
            basis = if (device.hasMemoryFigures) {
                RecommendationBasis.DEVICE_CLASS
            } else {
                RecommendationBasis.UNKNOWN
            },
            label = when {
                memoryWarning.isNotEmpty() -> "Bu cihaz için çok büyük"
                device.hasMemoryFigures -> "Cihazınıza uygun"
                else -> ""
            },
            evidence = "",
            memoryWarning = memoryWarning,
        )
    }

    /**
     * Why a model will not fit, in one sentence.
     *
     * Empty when it fits, or when the platform will not say - in which case no warning
     * is issued, because warning about a size we cannot check is inventing a problem.
     */
    fun memoryWarning(model: InstalledModel, device: DeviceCapability): String {
        if (!device.hasMemoryFigures) return ""
        if (model.sizeBytes <= 0L) return ""
        // The same arithmetic the pre-download check uses, so the library's warning and
        // the downloader's refusal cannot disagree about what fits.
        val needed = dev.charaly.runtime.model.gguf.GgufCompatibility.estimateRam(
            weightsBytes = model.sizeBytes,
            contextLength = model.contextLength,
        )
        if (needed <= device.availableRamBytes) return ""
        return "Yaklaşık ${formatBytes(needed)} boş alan gerekiyor; bu cihaz " +
            "${formatBytes(device.availableRamBytes)} bildiriyor."
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
        else -> "${bytes / 1024} kB"
    }
}
