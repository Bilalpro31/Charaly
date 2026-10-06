package dev.charaly.runtime.model

import kotlinx.coroutines.flow.Flow

/**
 * Every reason a download cannot happen, in words a user can act on.
 *
 * This type exists so the UI can say exactly what is wrong - instead of offering a Download
 * button that either does nothing or fails three screens later.
 *
 * ## Why [OfflineCoreBuild] is not the normal case any more
 *
 * It used to describe the whole product: the core build declared no INTERNET permission, so
 * an HTTP download was impossible by construction. This build declares INTERNET for exactly
 * one feature and wires a real HTTPS transport, so that refusal now means *no transport was
 * installed* - the bare `charaly-runtime` library, or a build whose transport failed to
 * load.
 *
 * The distinction matters because the two need opposite messages. "Charaly cannot reach the
 * internet" is an explanation; "no transport is installed" is a bug report, and telling a
 * user to import a file instead would be useless advice for a device that is perfectly
 * online.
 */
sealed interface DownloadUnavailable {

    /** Shown verbatim in the model library. */
    val message: String

    /** The honest alternative the user actually has. */
    val alternative: String

    /**
     * No network transport is installed in this build.
     *
     * Not a privacy statement and not a network condition - it is the absence of the code
     * that would perform the transfer, which is why the message says so plainly. Saying
     * "you are offline" here would send a user with working Wi-Fi to check their router.
     */
    data object OfflineCoreBuild : DownloadUnavailable {
        override val message: String =
            "This build of Charaly has no way to reach the model library."
        override val alternative: String =
            "Import a .gguf file you already have. It goes through exactly the same " +
                "registry, the same verification and the same model profiles as a " +
                "downloaded model would."
    }

    /** The file could not be fetched (network, server, DNS, TLS). */
    data class Network(val detail: String) : DownloadUnavailable {
        override val message: String = "The download could not be reached: $detail"
        override val alternative: String = "Try again, or import a .gguf from your device."
    }

    /** Not enough room for the file. */
    data object InsufficientStorage : DownloadUnavailable {
        override val message: String = "There is not enough free space on this device."
        override val alternative: String = "Free some space, or import a smaller .gguf."
    }

    /** The bytes arrived but did not match the expected sha256. */
    data class ChecksumMismatch(val expected: String, val actual: String) : DownloadUnavailable {
        override val message: String =
            "The downloaded file did not match its checksum, so it was discarded rather " +
                "than installed."
        override val alternative: String = "Try the download again."
    }

    /** The server does not support resuming, so a pause cannot be honoured. */
    data object ResumeUnsupported : DownloadUnavailable {
        override val message: String = "This file cannot be resumed after pausing."
        override val alternative: String = "Start the download again from the beginning."
    }

    /** The user cancelled. */
    data object Cancelled : DownloadUnavailable {
        override val message: String = "Download cancelled."
        override val alternative: String = ""
    }

    /** Something else, with a reason the caller already phrased. */
    data class Other(val detail: String) : DownloadUnavailable {
        override val message: String = detail
        override val alternative: String = ""
    }
}

/**
 * One step of one download.
 *
 * Progress is reported from real bytes on disk. There is no timer, no interpolation
 * and no synthetic percentage anywhere in this file - a fake progress bar is worse
 * than no button, because it teaches the user to trust a number that is not real.
 */
data class DownloadProgress(
    val catalogId: String,
    val state: DownloadState,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    /** The file being written, always a temporary name until verification passes. */
    val temporaryPath: String = "",
    val message: String = "",
    val failure: DownloadUnavailable? = null,
    /**
     * Measured transfer rate, bytes per second.
     *
     * Exponentially smoothed from real byte counts and real elapsed time - see
     * [HuggingFaceModelDownloads]. 0 means "not yet measurable", and the UI renders that
     * as an absent speed rather than as 0 B/s, because the two look identical on a bar
     * and mean different things.
     */
    val speedBytesPerSecond: Double = 0.0,
    val bytesPerSecondLabel: String = "",
    val etaSeconds: Long? = null,
    val etaLabel: String = "",
    /** Set on the READY frame: the model this download became. */
    val installedModelId: String = "",
    /** The compatibility verdict at install time, e.g. "Ready for Charaly". */
    val compatibilityLabel: String = "",
) {
    /**
     * Fraction complete in 0..1, or null when the total size is genuinely unknown.
     *
     * Returning null rather than 0 is the difference between "not started" and "we do
     * not know how big this is"; the UI renders them differently.
     *
     * Deliberately null for every state except DOWNLOADING: a paused download does not
     * report a fraction, because a bar that fills while paused is a lie.
     */
    val fraction: Float?
        get() = when {
            state != DownloadState.DOWNLOADING -> null
            totalBytes <= 0L -> null
            else -> ((bytesDownloaded.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0)).toFloat()
        }

    /** "1.2 GB of 4.5 GB", using the real numbers. */
    val transferredLabel: String
        get() = if (totalBytes > 0L) {
            "${formatBytes(bytesDownloaded)} of ${formatBytes(totalBytes)}"
        } else {
            formatBytes(bytesDownloaded)
        }

    val percentLabel: String
        get() = fraction?.let { "${(it * 100).toInt()}%" } ?: formatBytes(bytesDownloaded)
}

/**
 * The install pipeline's states, in the order they are reached.
 *
 * ```
 *  CATALOG -> DOWNLOADING <-> PAUSED
 *                  |            |
 *                  v            v
 *             DOWNLOADED -> VERIFYING -> REGISTERING -> READY
 *                  |            |            |
 *                  +------------+------------+--> FAILED
 * ```
 *
 * A file only becomes [REGISTERING] after its checksum has passed, and only becomes
 * [READY] after the atomic move into the models directory has completed. There is no
 * state that means "probably fine".
 */
enum class DownloadState {
    /** Only in the catalog. No bytes have moved. */
    CATALOG,

    /**
     * Found on the Hub, sized, and known to load - but not started.
     *
     * Separated from [CATALOG] so a model the user has chosen but not yet fetched is
     * visibly "queued" rather than looking like something already on the device.
     */
    DISCOVERED,

    /** Accepted and waiting for a transfer slot. */
    QUEUED,

    DOWNLOADING,

    PAUSED,

    /** All bytes are on disk in a temporary file; nothing has been verified. */
    DOWNLOADED,

    /** Computing sha256 over the temporary file. */
    VERIFYING,

    /** Checksum passed; moving into place and writing the registry entry. */
    REGISTERING,

    /** Installed and registered. */
    READY,

    FAILED,

    /** The user stopped it. A partial file may remain, and can be resumed or deleted. */
    CANCELLED,

    /**
     * Refused before any transfer, because the engine cannot load it.
     *
     * A terminal state rather than a failure: nothing went wrong with the network or the
     * disk, the model simply is not one this build can run. Keeping it distinct from
     * [FAILED] is what stops the library from offering "Retry" for something retrying
     * cannot fix.
     */
    UNSUPPORTED,
    ;

    val isActive: Boolean
        get() = this == DOWNLOADING || this == VERIFYING || this == REGISTERING

    /** Whether the user can meaningfully press Pause right now. */
    val isPausable: Boolean get() = this == DOWNLOADING

    val isCancellable: Boolean get() = this == DOWNLOADING || this == PAUSED || this == QUEUED

    /**
     * Whether retrying could plausibly succeed.
     *
     * [UNSUPPORTED] and [READY] are excluded on purpose: the same download will be refused
     * identically forever, and a Retry button that can never work is worse than none.
     */
    val isRetryable: Boolean
        get() = this == FAILED || this == CANCELLED || this == PAUSED

    /**
     * Whether no further work will happen without the user asking.
     *
     * Drives the "nothing is happening" banner: a download in a terminal state with no
     * error is either done or waiting for a decision, and the UI must not imply progress.
     */
    val isSettled: Boolean
        get() = this == READY || this == UNSUPPORTED || this == FAILED || this == CANCELLED ||
            this == PAUSED || this == CATALOG || this == DISCOVERED || this == QUEUED
}

/** What happened to one download attempt. */
sealed interface DownloadOutcome {
    data class Installed(val model: InstalledModel) : DownloadOutcome
    data class Failed(val reason: DownloadUnavailable) : DownloadOutcome
}

/**
 * The download pipeline, as a port.
 *
 * The offline core build wires [UnavailableModelDownloads]; a network-enabled variant
 * wires an implementation backed by a real HTTP transfer. The UI depends only on this
 * interface, so adding network access later is a build-variant change rather than a
 * product rewrite - and core inference never becomes network-dependent either way.
 */
interface ModelDownloadManager {

    /** Whether downloads can run at all in this build, and why not if they cannot. */
    fun availability(): DownloadUnavailable?

    /** Whether a download can start for this item right now. */
    fun canDownload(item: ModelCatalogItem): Boolean

    /** Starts (or resumes) a download, emitting real progress until it finishes. */
    fun download(item: ModelCatalogItem): Flow<DownloadProgress>

    fun pause(catalogId: String)

    fun cancel(catalogId: String)
}

/**
 * The implementation the offline core build uses.
 *
 * It is a real implementation of the interface, not a stub that pretends: every entry
 * point is honest, [availability] reports the precise architectural reason, and
 * [download] fails immediately with that same reason instead of emitting a single
 * frame of imaginary progress.
 */
class UnavailableModelDownloads(
    private val reason: DownloadUnavailable = DownloadUnavailable.OfflineCoreBuild,
) : ModelDownloadManager {

    override fun availability(): DownloadUnavailable = reason

    override fun canDownload(item: ModelCatalogItem): Boolean = false

    override fun download(item: ModelCatalogItem): Flow<DownloadProgress> = kotlinx.coroutines.flow.flow {
        emit(
            DownloadProgress(
                catalogId = item.id,
                state = DownloadState.FAILED,
                message = reason.message,
                failure = reason,
            ),
        )
    }

    override fun pause(catalogId: String) = Unit

    override fun cancel(catalogId: String) = Unit
}

/**
 * The state machine, as pure logic.
 *
 * Kept separate from any I/O so the transitions can be tested exhaustively on the JVM -
 * which is the only way to be sure a failed or cancelled download can never leave a
 * half-written file presented as an installed model.
 */
object DownloadStateMachine {

    /**
     * Whether moving from [from] to [to] is legal.
     *
     * The important rejections are the ones that stop a corrupt or partial file from
     * ever being treated as an installation.
     */
    fun canTransition(from: DownloadState, to: DownloadState): Boolean = when (from) {
        DownloadState.CATALOG ->
            to == DownloadState.DISCOVERED || to == DownloadState.DOWNLOADING ||
                to == DownloadState.UNSUPPORTED || to == DownloadState.FAILED

        // Discovered -> Queued is the user's "start this" action; Discovered -> Downloading
        // is the same action when a slot is free.
        DownloadState.DISCOVERED ->
            to == DownloadState.QUEUED || to == DownloadState.DOWNLOADING ||
                to == DownloadState.UNSUPPORTED || to == DownloadState.FAILED

        DownloadState.QUEUED ->
            to == DownloadState.DOWNLOADING || to == DownloadState.CANCELLED ||
                to == DownloadState.UNSUPPORTED || to == DownloadState.FAILED

        DownloadState.DOWNLOADING ->
            to == DownloadState.PAUSED || to == DownloadState.DOWNLOADED ||
                to == DownloadState.CANCELLED || to == DownloadState.FAILED

        DownloadState.PAUSED ->
            to == DownloadState.DOWNLOADING || to == DownloadState.CANCELLED ||
                to == DownloadState.FAILED

        DownloadState.DOWNLOADED -> to == DownloadState.VERIFYING || to == DownloadState.FAILED
        DownloadState.VERIFYING -> to == DownloadState.REGISTERING || to == DownloadState.FAILED

        // Installation is the last chance to refuse the file. An UNSUPPORTED verdict
        // discovered here - after bytes were transferred - still must not reach READY.
        DownloadState.REGISTERING ->
            to == DownloadState.READY || to == DownloadState.UNSUPPORTED || to == DownloadState.FAILED

        DownloadState.READY -> false // nothing follows a successful install
        DownloadState.UNSUPPORTED -> false // refusing twice is not a transition

        // Failed, cancelled and paused are the only retryable states, which is the only
        // way out of them.
        DownloadState.FAILED, DownloadState.CANCELLED, DownloadState.PAUSED ->
            to == DownloadState.QUEUED || to == DownloadState.DOWNLOADING || to == DownloadState.FAILED
    }

    /**
     * Terminal states: no further automatic work will happen, and none can start without
     * the user asking.
     */
    fun isTerminal(state: DownloadState): Boolean =
        state == DownloadState.READY ||
            state == DownloadState.UNSUPPORTED ||
            state == DownloadState.FAILED ||
            state == DownloadState.CANCELLED ||
            state == DownloadState.PAUSED
}