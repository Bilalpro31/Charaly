package dev.charaly.runtime.storypack

import dev.charaly.runtime.net.HttpTransport
import dev.charaly.runtime.net.StorageProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Downloading a story pack from the catalog.
 *
 * ```
 * DOWNLOAD -> VERIFY -> INSTALL -> REGISTER
 * ```
 *
 * Four states, four different questions: did we get the bytes (`DOWNLOAD`), are they the
 * bytes the catalog promised (`VERIFY`), are they a valid Story Pack (`INSTALL`), and is
 * it ours now (`REGISTER`). Collapsing them is how "downloaded" comes to mean "installed"
 * in the UI while meaning "sitting in a temp folder" on disk.
 *
 * ## What can go wrong, and what happens
 *
 * | Fault | Behaviour |
 * |---|---|
 * | no network | `Failed(Network)`, nothing written |
 * | not enough space | refused up front via [StorageProbe], nothing written |
 * | interrupted | partial kept at `<temp>/<id>.part`, resumable with a range request |
 * | cancelled | partial discarded, `Cancelled` |
 * | checksum mismatch | partial **deleted**, `ChecksumMismatch` |
 * | valid bytes, invalid pack | nothing installed, the validation report travels back |
 * | crash mid-install | staging swept on next start; never a half-installed pack |
 *
 * The invariant this all protects: **a pack that is not finished is not installed**, and
 * the registry is the only witness to the difference.
 */
enum class PackDownloadState {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    DOWNLOADED,
    VERIFYING,
    INSTALLING,
    REGISTERING,
    READY,
    FAILED,
    CANCELLED,
    ;

    val isActive: Boolean
        get() = this == QUEUED || this == DOWNLOADING || this == VERIFYING ||
            this == INSTALLING || this == REGISTERING

    val isRetryable: Boolean get() = this == FAILED || this == CANCELLED || this == PAUSED

    val isSettled: Boolean get() = this == READY || this == FAILED || this == CANCELLED
}

/** Why a download did not finish. Every case is a sentence a person can act on. */
sealed class PackDownloadFailure(val message: String, val alternative: String = "") {
    class Network(detail: String = "") :
        PackDownloadFailure(
            message = if (detail.isBlank()) "Bağlantı kurulamadı." else "Bağlantı kesildi: $detail",
            alternative = "Bağlantı düzeldiğinde kaldığı yerden devam edebilirsiniz.",
        )

    class InsufficientStorage(requiredBytes: Long) :
        PackDownloadFailure(
            message = "Bu paket için yeterli boş yer yok " +
                "(gerekli: ${StoryCatalogItem.formatBytes(requiredBytes)}).",
            alternative = "Biraz yer açıp tekrar deneyin.",
        )

    class ChecksumMismatch :
        PackDownloadFailure(
            message = "İndirilen dosya bozulmuş görünüyordu; kaydedilmedi.",
            alternative = "Tekrar deneyin; dosya en baştan indirilecek.",
        )

    class InvalidPack(report: ValidationReport?) :
        PackDownloadFailure(
            message = report?.headline() ?: "İndirilen dosya geçerli bir hikâye paketi değil.",
            alternative = "Paketi gönderene bildirin.",
        )

    class Interrupted(detail: String = "") :
        PackDownloadFailure(
            message = if (detail.isBlank()) "İndirme yarım kaldı." else "İndirme yarım kaldı: $detail",
            alternative = "Devam ettirerek kaldığı yerden sürebilirsiniz.",
        )

    class Cancelled :
        PackDownloadFailure(
            message = "İndirme iptal edildi.",
            alternative = "İstediğinizde yeniden başlayabilirsiniz.",
        )

    class Other(detail: String) :
        PackDownloadFailure(message = detail.ifBlank { "Beklenmeyen bir sorun oluştu." })
}

/** One frame of a download's life. */
data class PackDownloadProgress(
    val itemId: String,
    val state: PackDownloadState,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val message: String = "",
    val failure: PackDownloadFailure? = null,
    val speedBytesPerSecond: Long = 0L,
    val installedPackId: String = "",
    val report: ValidationReport? = null,
) {
    /**
     * Progress, or null when it genuinely cannot be known.
     *
     * Null rather than zero: a bar that says 0% for a byte count we do not have is lying,
     * and a lying progress bar is worse than none.
     */
    val fraction: Float?
        get() = if (totalBytes > 0 && state == PackDownloadState.DOWNLOADING) {
            (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            null
        }

    val transferredLabel: String
        get() = when {
            totalBytes > 0 ->
                "${StoryCatalogItem.formatBytes(bytesDownloaded)} / ${StoryCatalogItem.formatBytes(totalBytes)}"
            bytesDownloaded > 0 -> StoryCatalogItem.formatBytes(bytesDownloaded)
            else -> ""
        }
}

/**
 * The downloader.
 *
 * One file per download at `<temp>/<itemId>.part`, appended on resume via a range request.
 * The partial is the only durable state a download has, and it is deliberately *outside*
 * the install tree: a `.part` can never be mistaken for a pack.
 */
class StoryPackDownloadManager(
    private val transport: HttpTransport,
    private val installer: StoryPackInstaller,
    private val temporaryDirectory: File,
    private val storage: StorageProbe = StorageProbe.UNLIMITED,
    /** Bytes already reserved by something else in flight (a model download, say). */
    private val reservedBytes: Long = 0L,
    private val builtinSource: (StoryCatalogItem) -> ByteArray? = { null },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    private val cancelled = mutableSetOf<String>()
    private val paused = mutableSetOf<String>()
    private val lastProgress = linkedMapOf<String, PackDownloadProgress>()

    /** The most recent frame, so a reopened screen is never blank. */
    fun snapshot(itemId: String): PackDownloadProgress? = lastProgress[itemId]

    fun cancel(itemId: String) {
        synchronized(cancelled) { cancelled += itemId }
    }

    fun pause(itemId: String) {
        synchronized(paused) { paused += itemId }
    }

    fun resumeAllowed(itemId: String) {
        synchronized(paused) { paused -= itemId }
        synchronized(cancelled) { cancelled -= itemId }
    }

    /**
     * Runs a download to completion as a stream of frames.
     *
     * The happy path is four states; every other path ends in `FAILED` or `CANCELLED` with
     * a reason, and never leaves the registry claiming something it should not.
     */
    fun download(item: StoryCatalogItem): Flow<PackDownloadProgress> = flow {
        suspend fun publish(frame: PackDownloadProgress) {
            lastProgress[item.id] = frame
            emit(frame)
        }

        fun isCancelled(): Boolean = synchronized(cancelled) { item.id in cancelled }
        fun isPaused(): Boolean = synchronized(paused) { item.id in paused }

        publish(PackDownloadProgress(item.id, PackDownloadState.QUEUED, message = "Sırada"))

        val metadata = item.download
        if (metadata == null) {
            publish(
                PackDownloadProgress(
                    item.id,
                    PackDownloadState.FAILED,
                    message = "Bu paketin indirme kaynağı yok.",
                    failure = PackDownloadFailure.Other("indirme kaynağı tanımsız"),
                ),
            )
            return@flow
        }

        val needed = if (item.sizeBytes > 0) item.sizeBytes else metadata.sizeBytes
        val available = runCatching { storage.availableBytes() }.getOrDefault(Long.MAX_VALUE)
        if (needed > 0 && available != Long.MAX_VALUE && available - reservedBytes < needed) {
            publish(
                PackDownloadProgress(
                    item.id,
                    PackDownloadState.FAILED,
                    totalBytes = needed,
                    message = "Yer yok.",
                    failure = PackDownloadFailure.InsufficientStorage(needed),
                ),
            )
            return@flow
        }

        temporaryDirectory.mkdirs()
        val partial = File(temporaryDirectory, "${sanitize(item.id)}$PART_SUFFIX")

        val bytes: ByteArray = when (metadata.source) {
            StoryPackDownloadSource.BUILTIN -> {
                val built = builtinSource(item)
                if (built == null) {
                    publish(
                        PackDownloadProgress(
                            item.id,
                            PackDownloadState.FAILED,
                            message = "Paket cihazda bulunamadı.",
                            failure = PackDownloadFailure.Other("builtin source missing"),
                        ),
                    )
                    return@flow
                }
                partial.delete()
                publish(
                    PackDownloadProgress(
                        item.id,
                        PackDownloadState.DOWNLOADING,
                        bytesDownloaded = built.size.toLong(),
                        totalBytes = built.size.toLong(),
                        message = "Hazırlanıyor",
                    ),
                )
                built
            }
            StoryPackDownloadSource.HTTP -> try {
                fetchOverHttp(
                    item = item,
                    metadata = metadata,
                    partial = partial,
                    publish = ::publish,
                    isCancelled = ::isCancelled,
                    isPaused = ::isPaused,
                )
            } catch (stopped: DownloadStopped) {
                return@flow
            }
        }

        publish(
            PackDownloadProgress(
                item.id,
                PackDownloadState.DOWNLOADED,
                bytesDownloaded = bytes.size.toLong(),
                totalBytes = bytes.size.toLong(),
                message = "İndirildi",
            ),
        )

        // ---- VERIFY ------------------------------------------------------
        publish(
            PackDownloadProgress(
                item.id,
                PackDownloadState.VERIFYING,
                bytesDownloaded = bytes.size.toLong(),
                totalBytes = bytes.size.toLong(),
                message = "Denetleniyor",
            ),
        )
        val expected = item.sha256.ifBlank { metadata.sha256 }
        if (expected.isNotBlank() && Sha256.of(bytes) != expected) {
            // The bytes are not what was promised. Keeping them would poison the next
            // resume; the only honest move is to throw them away and say so.
            partial.delete()
            publish(
                PackDownloadProgress(
                    item.id,
                    PackDownloadState.FAILED,
                    bytesDownloaded = bytes.size.toLong(),
                    totalBytes = bytes.size.toLong(),
                    message = "Dosya bozulmuş.",
                    failure = PackDownloadFailure.ChecksumMismatch(),
                ),
            )
            return@flow
        }

        // ---- INSTALL -----------------------------------------------------
        publish(
            PackDownloadProgress(
                item.id,
                PackDownloadState.INSTALLING,
                bytesDownloaded = bytes.size.toLong(),
                totalBytes = bytes.size.toLong(),
                message = "Kuruluyor",
            ),
        )
        val result = try {
            installer.import(bytes, source = InstalledPackRecord.SOURCE_CATALOG)
        } catch (cause: Exception) {
            partial.delete()
            publish(
                PackDownloadProgress(
                    item.id,
                    PackDownloadState.FAILED,
                    message = "Kurulum tamamlanamadı.",
                    failure = PackDownloadFailure.Other(cause.message.orEmpty()),
                ),
            )
            return@flow
        }

        when (result) {
            is StoryPackInstaller.ImportResult.Rejected -> {
                partial.delete()
                publish(
                    PackDownloadProgress(
                        item.id,
                        PackDownloadState.FAILED,
                        bytesDownloaded = bytes.size.toLong(),
                        totalBytes = bytes.size.toLong(),
                        message = result.report.headline(),
                        failure = PackDownloadFailure.InvalidPack(result.report),
                        report = result.report,
                    ),
                )
            }
            is StoryPackInstaller.ImportResult.Installed -> {
                // ---- REGISTER: the installer wrote the registry; this is the ack ----
                partial.delete()
                publish(
                    PackDownloadProgress(
                        item.id,
                        PackDownloadState.READY,
                        bytesDownloaded = bytes.size.toLong(),
                        totalBytes = bytes.size.toLong(),
                        message = "Kuruldu",
                        installedPackId = result.record.packId,
                        report = result.report,
                    ),
                )
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Streams one HTTP download into the partial file and returns the complete bytes.
     *
     * Aborting (cancelled, paused, connection lost) is signalled by publishing the frame
     * and throwing [DownloadStopped], so no nested lambda has to unwind anything.
     */
    private suspend fun fetchOverHttp(
        item: StoryCatalogItem,
        metadata: StoryPackDownloadMetadata,
        partial: File,
        publish: suspend (PackDownloadProgress) -> Unit,
        isCancelled: () -> Boolean,
        isPaused: () -> Boolean,
    ): ByteArray {
        val resumeFrom = if (partial.isFile) partial.length() else 0L
        val response = try {
            if (resumeFrom > 0) {
                transport.openStream(metadata.url, rangeStart = resumeFrom)
            } else {
                transport.openStream(metadata.url)
            }
        } catch (cause: Exception) {
            publish(
                PackDownloadProgress(
                    item.id,
                    PackDownloadState.FAILED,
                    bytesDownloaded = resumeFrom,
                    message = "Bağlantı kurulamadı.",
                    failure = PackDownloadFailure.Network(cause.message.orEmpty()),
                ),
            )
            throw DownloadStopped
        }

        if (response.code !in 200..299) {
            response.close()
            val failure = if (response.code == 0) {
                PackDownloadFailure.Network(response.headers["charaly-error"].orEmpty())
            } else {
                PackDownloadFailure.Interrupted("sunucu ${response.code} yanıtı verdi")
            }
            publish(
                PackDownloadProgress(
                    item.id,
                    PackDownloadState.FAILED,
                    bytesDownloaded = resumeFrom,
                    message = failure.message,
                    failure = failure,
                ),
            )
            throw DownloadStopped
        }

        // A server that ignores our range answers 200 with the whole body: start over
        // rather than splice two different byte sequences together.
        val appending = response.code == 206 && resumeFrom > 0
        if (!appending && partial.exists()) partial.delete()
        var written = if (appending) resumeFrom else 0L
        val total = when {
            response.totalBytes > 0 -> response.totalBytes
            item.sizeBytes > 0 -> item.sizeBytes
            else -> 0L
        }
        val startedAt = nowMs()

        try {
            response.stream.use { input ->
                val out = if (appending) {
                    java.io.FileOutputStream(partial, true)
                } else {
                    java.io.FileOutputStream(partial)
                }
                out.use { target ->
                    val buffer = ByteArray(DOWNLOAD_CHUNK)
                    while (true) {
                        if (isCancelled()) {
                            target.flush()
                            partial.delete()
                            publish(
                                PackDownloadProgress(
                                    item.id,
                                    PackDownloadState.CANCELLED,
                                    bytesDownloaded = written,
                                    totalBytes = total,
                                    message = "İptal edildi.",
                                    failure = PackDownloadFailure.Cancelled(),
                                ),
                            )
                            throw DownloadStopped
                        }
                        if (isPaused()) {
                            target.flush()
                            publish(
                                PackDownloadProgress(
                                    item.id,
                                    PackDownloadState.PAUSED,
                                    bytesDownloaded = written,
                                    totalBytes = total,
                                    message = "Duraklatıldı.",
                                ),
                            )
                            throw DownloadStopped
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        target.write(buffer, 0, read)
                        written += read
                        val elapsedMs = (nowMs() - startedAt).coerceAtLeast(1L)
                        publish(
                            PackDownloadProgress(
                                item.id,
                                PackDownloadState.DOWNLOADING,
                                bytesDownloaded = written,
                                totalBytes = total,
                                speedBytesPerSecond = written * 1000L / elapsedMs,
                                message = "İndiriliyor",
                            ),
                        )
                    }
                    target.flush()
                }
            }
        } catch (stopped: DownloadStopped) {
            runCatching { response.close() }
            throw stopped
        } catch (cause: Exception) {
            publish(
                PackDownloadProgress(
                    item.id,
                    PackDownloadState.FAILED,
                    bytesDownloaded = written,
                    totalBytes = total,
                    message = "İndirme yarım kaldı.",
                    failure = PackDownloadFailure.Interrupted(cause.message.orEmpty()),
                ),
            )
            throw DownloadStopped
        } finally {
            runCatching { response.close() }
        }

        return partial.readBytes()
    }

    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "pack" }

    /** Internal signal: the last frame has been published; unwind and stop. */
    private object DownloadStopped : Exception("download stopped")

    companion object {
        const val PART_SUFFIX = ".part"
        private const val DOWNLOAD_CHUNK = 64 * 1024
    }
}

/** Entry point for callers that want one blocking await (tests, CLI tools). */
fun <T> Flow<T>.collectLastBlocking(): T? = runBlocking {
    var last: T? = null
    collect { last = it }
    last
}