package dev.charaly.runtime.model

import kotlinx.coroutines.flow.Flow

/**
 * Why a download cannot happen, in words a user can act on.
 *
 * Charaly's core build declares no `android.permission.INTERNET`. That is a
 * deliberate, machine-checkable privacy guarantee, and it means an HTTP download
 * cannot run here. This type exists so the UI can say exactly that - instead of
 * offering a Download button that either does nothing or fails three screens later.
 */
sealed interface DownloadUnavailable {

    /** Shown verbatim in the model library. */
    val message: String

    /** The honest alternative the user actually has. */
    val alternative: String

    /**
     * The core build is offline by design.
     *
     * Charaly's manifest declares no INTERNET permission, so the OS makes opening a
     * socket impossible. This is not a limitation to apologise for: it is the reason a
     * conversation, a memory or a model cannot leave the device.
     */
    data object OfflineCoreBuild : DownloadUnavailable {
        override val message: String =
            "Charaly does not use the internet, so it cannot download models."
        override val alternative: String =
            "Import a .gguf file you already have. It goes through exactly the same " +
                "registry and the same model profiles as a downloaded model would."
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
) {
    /**
     * Fraction complete in 0..1, or null when the total size is genuinely unknown.
     *
     * Returning null rather than 0 is the difference between "not started" and "we do
     * not know how big this is"; the UI renders them differently.
     */
    val fraction: Float?
        get() = when {
            state != DownloadState.DOWNLOADING -> null
            totalBytes <= 0L -> null
            else -> ((bytesDownloaded.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0)).toFloat()
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
    ;

    val isActive: Boolean get() = this == DOWNLOADING || this == VERIFYING || this == REGISTERING

    /** Whether the user can meaningfully press Pause right now. */
    val isPausable: Boolean get() = this == DOWNLOADING

    val isCancellable: Boolean get() = this == DOWNLOADING || this == PAUSED
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
        DownloadState.CATALOG -> to == DownloadState.DOWNLOADING || to == DownloadState.FAILED
        DownloadState.DOWNLOADING ->
            to == DownloadState.PAUSED || to == DownloadState.DOWNLOADED || to == DownloadState.FAILED
        DownloadState.PAUSED -> to == DownloadState.DOWNLOADING || to == DownloadState.FAILED
        DownloadState.DOWNLOADED -> to == DownloadState.VERIFYING || to == DownloadState.FAILED
        DownloadState.VERIFYING -> to == DownloadState.REGISTERING || to == DownloadState.FAILED
        DownloadState.REGISTERING -> to == DownloadState.READY || to == DownloadState.FAILED
        DownloadState.READY -> false // nothing follows a successful install
        // A failed download is retryable, which is the only way out.
        DownloadState.FAILED -> to == DownloadState.DOWNLOADING
    }

    /** Terminal states: no further automatic work will happen. */
    fun isTerminal(state: DownloadState): Boolean =
        state == DownloadState.READY || state == DownloadState.FAILED
}