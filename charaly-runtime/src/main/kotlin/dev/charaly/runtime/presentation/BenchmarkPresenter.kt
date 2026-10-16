package dev.charaly.runtime.presentation

import dev.charaly.runtime.model.BenchmarkFailure
import dev.charaly.runtime.model.BenchmarkRecord
import dev.charaly.runtime.model.ModelBenchmark

/**
 * What the model library says about speed, and how sure it is.
 *
 * ## The three states, and why there is no fourth
 *
 * ```
 *   MEASURED   "Fast on this device"  + the tok/s and the conditions it was taken under
 *   UNMEASURED "Not measured"        + an offer to measure it
 *   RUNNING    the real phase, from the real loader
 * ```
 *
 * There is no fourth state because there is no honest way to produce one. A speed cannot
 * be derived from a model's size, a device's RAM class or a parameter count - those
 * inputs have no relationship to tokens per second that survives contact with a real
 * phone, and a number derived from them is a fabrication that looks like a measurement.
 * So the library shows either a measured figure or the words "Not measured".
 *
 * Every string here is either a measured number rendered with its unit, or a sentence
 * about what to do next. No bare tok/s figure appears without the phrase that says where
 * it came from.
 */
enum class SpeedState {
    /** A real measurement exists for this model on this device. */
    MEASURED,

    /** No measurement. The library says so rather than guessing. */
    UNMEASURED,
}

/**
 * The performance line for one model.
 *
 * A card carries this instead of a speed string, so a screen cannot accidentally render a
 * tier name without the evidence behind it: [label] and [evidence] are produced together
 * by one function.
 */
data class SpeedVerdict(
    val state: SpeedState,
    /**
     * "Fast on this device", or "Not measured".
     *
     * Empty only while a benchmark is in flight, where the phase label takes over.
     */
    val label: String,
    /**
     * "18.7 tok/s, measured on 4 threads · CPU".
     *
     * Empty when there is no measurement, because an evidence line without evidence is
     * worse than no line.
     */
    val evidence: String = "",
    /** "4 threads · CPU" - the conditions, always shown next to the number. */
    val conditions: String = "",
    /** First-token latency, when measured. "1.4s to the first word" is what a reader feels. */
    val latencyLabel: String = "",
    /** Peak resident memory, when measured. */
    val memoryLabel: String = "",
    /** True while a benchmark is running for this model. */
    val isMeasuring: Boolean = false,
    /** The live phase label while measuring: "Loading model…", "Generating…". */
    val phaseLabel: String = "",
) {
    /** Whether a "Measure" control should be offered. */
    val canBenchmark: Boolean get() = state == SpeedState.UNMEASURED && !isMeasuring

    /**
     * Whether the number on screen is a measurement.
     *
     * Read by tests rather than trusted: a screen that renders [evidence] while this is
     * false has invented a figure.
     */
    val hasMeasurement: Boolean get() = state == SpeedState.MEASURED && evidence.isNotBlank()
}

/** The benchmark state of the whole library, for the banner above the list. */
data class BenchmarkSummary(
    val measuredCount: Int,
    val unmeasuredCount: Int,
    /** True when a benchmark is running for any model. */
    val isRunning: Boolean,
    /** "Measuring Gemma 3 4B…" - which model, so two concurrent runs are never ambiguous. */
    val runningLabel: String = "",
    /** The failure sentence for a run that did not complete. Empty when nothing failed. */
    val failureMessage: String = "",
)

object BenchmarkPresenter {

    /**
     * Builds one model's speed line.
     *
     * [record] is null when this device has never measured this model. That is the normal
     * state, and it renders as words rather than as a number.
     */
    fun verdict(
        record: BenchmarkRecord?,
        isMeasuring: Boolean = false,
        phaseLabel: String = "",
    ): SpeedVerdict = when {
        isMeasuring -> SpeedVerdict(
            state = SpeedState.UNMEASURED,
            label = "",
            isMeasuring = true,
            phaseLabel = phaseLabel,
        )

        // Not measured is a *state*, not an absence: the label says so explicitly, and a
        // "Measure" control is offered because measuring is cheap next to being wrong.
        record == null -> SpeedVerdict(
            state = SpeedState.UNMEASURED,
            label = "Ölçülmedi",
        )

        else -> measured(record)
    }

    /**
     * The banner above the library list.
     *
     * [installedIds] rather than a count, because a measurement for a model the user has
     * since deleted must not be counted as covering a model that is present. Passing ids
     * makes the intersection explicit and impossible to get wrong.
     */
    fun summary(
        installedIds: List<String>,
        records: List<BenchmarkRecord>,
        measuringModelId: String = "",
        measuringName: String = "",
        phaseLabel: String = "",
        failureMessage: String = "",
    ): BenchmarkSummary {
        val present = installedIds.toSet()
        val measuredCount = records.count { it.benchmark.modelId in present }
        return BenchmarkSummary(
            measuredCount = measuredCount,
            unmeasuredCount = (present.size - measuredCount).coerceAtLeast(0),
            isRunning = measuringModelId.isNotBlank(),
            runningLabel = if (measuringModelId.isBlank()) {
                ""
            } else {
                listOfNotNull(measuringName.takeIf { it.isNotBlank() }, phaseLabel.takeIf { it.isNotBlank() })
                    .joinToString(" ")
                    .ifBlank { "Ölçülüyor…" }
            },
            failureMessage = failureMessage,
        )
    }

    /** The sentence for a run that produced no measurement. */
    fun failureMessage(failure: BenchmarkFailure): String = failure.message

    /**
     * The sentence for a failure that arrived as a throwable.
     *
     * Only the typed [dev.charaly.runtime.model.BenchmarkFailedException] is mapped. Any
     * other exception becomes the generic "could not complete" - never
     * `Throwable.message`, which for a native failure reads like
     * `InferenceError: llama_decode failed` and is exactly the kind of engine vocabulary
     * this codebase refuses to put in front of a player.
     */
    fun messageFor(error: Throwable): String = when (error) {
        is dev.charaly.runtime.model.BenchmarkFailedException -> error.reason.message
        else -> BenchmarkFailure.CouldNotComplete.message
    }

    // -------------------------------------------------------------------- private

    private fun measured(record: BenchmarkRecord): SpeedVerdict {
        val benchmark = record.benchmark
        val conditions = conditionsLabel(record)
        return SpeedVerdict(
            state = SpeedState.MEASURED,
            label = "Bu cihazda ${dev.charaly.runtime.model.PerformanceTier.of(benchmark.tokensPerSecond).label}",
            // The evidence line always names the rate *and* where the number came from.
            // A bare "18.7 tok/s" on a model detail screen is indistinguishable from a
            // guess, and that ambiguity is what this whole feature exists to remove.
            evidence = "%.1f tok/s, %s ölçüldü".format(
                benchmark.tokensPerSecond,
                if (conditions.isBlank()) "bu cihazda" else conditions,
            ),
            conditions = conditions,
            latencyLabel = if (benchmark.firstTokenMillis > 0L) {
                "ilk kelimeye %.1f sn".format(benchmark.firstTokenMillis / 1000.0)
            } else {
                ""
            },
            memoryLabel = if (benchmark.peakMemoryBytes > 0L) {
                "en yüksek ${dev.charaly.runtime.model.formatBytes(benchmark.peakMemoryBytes)} bellek"
            } else {
                ""
            },
        )
    }

    /**
     * "4 threads · CPU" - the conditions a tok/s figure is meaningless without.
     *
     * Shows an accelerator only when the engine's own device registry named one. A build
     * compiled CPU-only must say CPU, and it must not be able to say "GPU" on the
     * strength of a hardcoded string.
     */
    private fun conditionsLabel(record: BenchmarkRecord): String {
        val threads = if (record.threads > 0) "${record.threads} iş parçacığı" else ""
        val accelerator = record.devices
            .firstOrNull { it.startsWith("GPU:") || it.startsWith("ACCELERATOR:") }
            ?.substringAfter(':')
            ?.takeIf { it.isNotBlank() }
            ?: if (record.gpuLayers > 0) "GPU" else "CPU"
        val offload = if (record.gpuLayers > 0) " · ${record.gpuLayers} katman devredildi" else ""
        return listOf(threads, "$accelerator$offload").filter { it.isNotBlank() }.joinToString(" · ")
    }
}

