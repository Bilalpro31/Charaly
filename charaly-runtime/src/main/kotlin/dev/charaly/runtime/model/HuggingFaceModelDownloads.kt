package dev.charaly.runtime.model

import dev.charaly.runtime.net.HttpStreamResponse
import dev.charaly.runtime.net.HttpTransport
import dev.charaly.runtime.net.StorageProbe
import dev.charaly.runtime.model.gguf.CharalyCompatibility
import dev.charaly.runtime.model.gguf.GgufCompatibility
import dev.charaly.runtime.model.gguf.GgufReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * A real, resumable GGUF download.
 *
 * ## What this is not
 *
 * It is not a progress bar with a timer behind it. Every number it emits is derived from
 * bytes actually written to disk, verified by reading the file's length back:
 *
 * ```
 *   discovered -> queued -> downloading <-> paused
 *                                  |
 *                                  v
 *                              verifying     sha256 over the real file, if published
 *                                  |
 *                                  v
 *                              installing    GGUF header parse + compatibility + move
 *                                  |
 *                                  v
 *                                 ready       registered, profile attached
 *
 *   any state -> failed | cancelled | unsupported
 * ```
 *
 * ## The three invariants that make it trustworthy
 *
 * 1. **Nothing partial is ever presented as installed.** Bytes land in `<name>.part`.
 *    The registry entry is written only after verification *and* an atomic rename. A crash
 *    at any point leaves a `.part` file that is not a model.
 * 2. **A file that fails verification is deleted, not quarantined.** A corrupt GGUF cannot
 *    become loadable later, and leaving it consumes the user's storage.
 * 3. **A paused download resumes from the byte it reached**, because the offset is read
 *    back from the file on disk rather than remembered in memory. A process death
 *    therefore costs nothing.
 */
class HuggingFaceModelDownloads(
    private val transport: HttpTransport,
    private val storage: StorageProbe,
    private val registry: ModelRegistry,
    /** Where model files live. Must be app-private storage. */
    private val modelsDirectory: File,
    /** Safety margin added to the required-bytes check. */
    private val storageSafetyMarginBytes: Long = DEFAULT_SAFETY_MARGIN,
    /** Injectable clock so speed/ETA are testable. */
    private val nowMs: () -> Long = System::currentTimeMillis,
) : ModelDownloadManager {

    private val availabilityOverride: DownloadUnavailable? = null

    private val active = mutableMapOf<String, JobHandle>()

    /** Observed progress for every download this process has touched. */
    private val progressBus = MutableSharedFlow<DownloadProgress>(
        replay = 16,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Progress for background display, replayed so a newly-composed screen is not blank. */
    fun progress(): Flow<DownloadProgress> = progressBus.asSharedFlow()

    /** The latest known state of one download, for a screen that re-appears. */
    fun lastProgress(catalogId: String): DownloadProgress? = snapshots[catalogId]

    private val snapshots = mutableMapOf<String, DownloadProgress>()

    // ------------------------------------------------------------------
    // ModelDownloadManager
    // ------------------------------------------------------------------

    /** Always null: this implementation *can* download, provided the file has a URL. */
    override fun availability(): DownloadUnavailable? = availabilityOverride

    override fun canDownload(item: ModelCatalogItem): Boolean {
        val download = item.download ?: return false
        if (download.url.isBlank()) return false
        if (!item.isEngineLoadable) return false
        return hasRoomFor(download.sizeBytes)
    }

    override fun download(item: ModelCatalogItem): Flow<DownloadProgress> = flow {
        val metadata = item.download
        if (metadata == null || metadata.url.isBlank()) {
            emit(fail(item.id, DownloadUnavailable.Other("This model has no download source.")))
            return@flow
        }
        // A model the engine cannot load is never downloaded. Offering it would spend the
        // user's data and storage to produce a file that cannot be opened.
        if (!item.isEngineLoadable) {
            emit(
                fail(
                    item.id,
                    DownloadUnavailable.Other(item.engineVerdict.reason),
                ),
            )
            return@flow
        }
        val outcome = runTransfer(
            onProgress = { emit(it) },
            catalogId = item.id,
            url = metadata.url,
            fileName = metadata.fileName.ifBlank { defaultFileName(item) },
            expectedBytes = metadata.sizeBytes,
            expectedSha256 = metadata.sha256,
            displayName = item.name,
            architectureHint = item.architecture,
            quantizationHint = item.quantization,
            contextHint = item.contextLength,
        )
        emit(outcome)
    }

    override fun pause(catalogId: String) {
        // Cooperative: the handle flips a flag and the read loop notices within one chunk.
        // There is no thread to interrupt, which is what makes this safe on Android.
        active[catalogId]?.pauseRequested = true
    }

    override fun cancel(catalogId: String) {
        val handle = active[catalogId]
        handle?.cancelRequested = true
    }

    // ------------------------------------------------------------------
    // The transfer
    // ------------------------------------------------------------------

    /**
     * Runs the whole pipeline, returning the final progress frame.
     *
     * [onProgress] is invoked for every state change so the caller can surface it. It is
     * a callback rather than a Flow emit because this is a suspend function called from
     * inside a `flow { }` builder, where `emit` is not in scope.
     */
    private suspend fun runTransfer(
        onProgress: suspend (DownloadProgress) -> Unit,
        catalogId: String,
        url: String,
        fileName: String,
        expectedBytes: Long,
        expectedSha256: String,
        displayName: String,
        architectureHint: String,
        quantizationHint: String,
        contextHint: Int,
    ): DownloadProgress {
        val finalFile = File(modelsDirectory, fileName)
        val partialFile = File(modelsDirectory, "$fileName.part")
        modelsDirectory.mkdirs()

        // --- storage check, before a single byte moves --------------------------
        //
        // Resuming needs room for the remainder, not the whole file, so the required
        // amount depends on what is already on disk.
        val alreadyHave = if (partialFile.exists()) partialFile.length() else 0L
        val remaining = (expectedBytes - alreadyHave).coerceAtLeast(0L)
        if (!hasRoomFor(remaining)) {
            return fail(
                catalogId,
                DownloadUnavailable.InsufficientStorage,
                partialFile,
                clearPartial = false,
            )
        }

        // --- open a byte range -------------------------------------------------
        val resuming = alreadyHave > 0L && expectedBytes > alreadyHave
        val response = try {
            transport.openStream(
                url = url,
                rangeStart = if (resuming) alreadyHave else null,
                rangeEnd = null,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return fail(catalogId, DownloadUnavailable.Network(e.message ?: "connection failed"))
        }

        // A server that ignored the range answered 200 with the whole body. Appending that
        // to an existing partial file would corrupt it, so the only safe moves are to
        // restart or to stop.
        val serverResumed = response.code == 206
        if (resuming && !serverResumed) {
            response.close()
            return fail(
                catalogId,
                DownloadUnavailable.ResumeUnsupported,
                partialFile,
                clearPartial = true,
            )
        }
        if (response.code !in 200..299) {
            response.close()
            return fail(catalogId, DownloadUnavailable.Network("the server answered ${response.code}"))
        }

        // The server's own idea of the total, when it has one. Preferred over the catalog's
        // figure because it is measured rather than declared.
        val totalBytes = when {
            response.totalBytes > 0L -> response.totalBytes
            expectedBytes > 0L -> expectedBytes
            else -> 0L
        }

        val handle = JobHandle(catalogId)
        active[catalogId] = handle

        var written = if (resuming) alreadyHave else 0L
        val startedAt = nowMs()
        var lastTickBytes = written
        var lastTickAt = startedAt
        var speedBytesPerSecond = 0.0

        // Every frame this function produces is surfaced through the caller's sink, so a
        // caller that collects the flow sees each transition exactly once and in order.
        val frame: suspend (DownloadProgress) -> DownloadProgress = { f ->
            onProgress(f)
            f
        }

        frame(
            progressOf(
                catalogId,
                DownloadState.DOWNLOADING,
                written,
                totalBytes,
                partialFile.path,
            ),
        )

        val outcome: DownloadProgress = try {
            // The transfer loop cannot use `use`'s return value for control flow: a pause
            // and a cancel both end the loop early with a *frame*, not with the loop's
            // value. `settled` carries that decision out of the nested blocks.
            var settled: DownloadProgress? = null

            response.use { stream ->
                RandomAccessFile(partialFile, "rw").use { out ->
                    out.seek(written)
                    val buffer = ByteArray(CHUNK_BYTES)

                    while (settled == null) {
                        currentCoroutineContext().ensureActive()

                        if (handle.cancelRequested) {
                            settled = frame(cancelled(catalogId, written, totalBytes, partialFile.path))
                            break
                        }
                        if (handle.pauseRequested) {
                            // Keep the partial file: resuming reads its length back from
                            // disk, so a pause that kills the process loses nothing.
                            settled = frame(
                                progressOf(
                                    catalogId,
                                    DownloadState.PAUSED,
                                    out.length(),
                                    totalBytes,
                                    partialFile.path,
                                ),
                            )
                            break
                        }

                        val n = stream.read(buffer, 0, buffer.size)
                        if (n < 0) break
                        if (n == 0) {
                            delay(IDLE_BACKOFF_MS)
                            continue
                        }
                        out.write(buffer, 0, n)
                        written += n

                        val now = nowMs()
                        val elapsed = now - lastTickAt
                        if (elapsed >= SPEED_SAMPLE_MS) {
                            val instant = (written - lastTickBytes) * 1000.0 / elapsed
                            // Exponential smoothing: a single slow chunk should not make the
                            // reported speed swing to zero and back.
                            speedBytesPerSecond = if (speedBytesPerSecond <= 0.0) {
                                instant
                            } else {
                                speedBytesPerSecond * 0.7 + instant * 0.3
                            }
                            lastTickBytes = written
                            lastTickAt = now
                        }

                        frame(
                            progressOf(
                                catalogId,
                                DownloadState.DOWNLOADING,
                                written,
                                totalBytes,
                                partialFile.path,
                            ).copy(
                                speedBytesPerSecond = speedBytesPerSecond,
                                bytesPerSecondLabel = formatSpeed(speedBytesPerSecond),
                                etaSeconds = etaSeconds(written, totalBytes, speedBytesPerSecond),
                                etaLabel = formatDuration(etaSeconds(written, totalBytes, speedBytesPerSecond)),
                            ),
                        )
                    }

                    // Short read: the transfer ended early. Not a success.
                    if (settled == null) {
                        val expected = if (totalBytes > 0L) totalBytes else expectedBytes
                        if (expected > 0L && out.length() < expected) {
                            settled = frame(
                                fail(
                                    catalogId,
                                    DownloadUnavailable.Network(
                                        "the connection closed before the file finished",
                                    ),
                                    partialFile,
                                    clearPartial = false,
                                ),
                            )
                        }
                    }
                }
            }

            // A pause or cancel ends the pipeline here. Verification must not run on a
            // file the transfer has not finished writing.
            val early = settled
            if (early != null) {
                early
            } else {
                // --- verification --------------------------------------------------
                frame(
                    progressOf(
                        catalogId,
                        DownloadState.VERIFYING,
                        written,
                        totalBytes,
                        partialFile.path,
                    ),
                )

                val checksumFailed = if (expectedSha256.isBlank()) {
                    null
                } else {
                    val actual = sha256(partialFile)
                    if (actual.equals(expectedSha256, ignoreCase = true)) {
                        null
                    } else {
                        // Corrupt bytes are worse than no bytes: the file can never load,
                        // and it is occupying the storage the retry needs.
                        frame(
                            fail(
                                catalogId,
                                DownloadUnavailable.ChecksumMismatch(expectedSha256, actual),
                                partialFile,
                                clearPartial = true,
                            ),
                        )
                    }
                }

                if (checksumFailed != null) {
                    checksumFailed
                } else {
                    // --- install ---------------------------------------------------
                    frame(
                        progressOf(
                            catalogId,
                            DownloadState.REGISTERING,
                            written,
                            totalBytes,
                            partialFile.path,
                        ),
                    )
                    frame(
                        install(
                            catalogId = catalogId,
                            partialFile = partialFile,
                            finalFile = finalFile,
                            displayName = displayName,
                            expectedSha256 = expectedSha256,
                            architectureHint = architectureHint,
                            quantizationHint = quantizationHint,
                            contextHint = contextHint,
                            written = written,
                            totalBytes = totalBytes,
                        ),
                    )
                }
            }
        } catch (e: CancellationException) {
            onProgress(cancelled(catalogId, written, totalBytes, partialFile.path))
            cancelled(catalogId, written, totalBytes, partialFile.path)
        } catch (e: IOException) {
            val f = fail(
                catalogId,
                DownloadUnavailable.Network(e.message ?: "the transfer failed"),
                partialFile,
                clearPartial = false,
            )
            onProgress(f)
            f
        } finally {
            active.remove(catalogId)
        }

        return outcome
    }

    /**
     * Turns a complete `.part` file into a registered, configured model.
     *
     * The order here is the point, and it is not negotiable:
     * read header -> classify -> *reject if unusable* -> atomic move -> register. A model
     * that cannot be loaded never becomes a registry entry, so the library can never show
     * a model that will fail on first use.
     */
    private suspend fun install(
        catalogId: String,
        partialFile: File,
        finalFile: File,
        displayName: String,
        expectedSha256: String,
        architectureHint: String,
        quantizationHint: String,
        contextHint: Int,
        written: Long,
        totalBytes: Long,
    ): DownloadProgress {
        val header = readHeaderBytes(partialFile)
        val read = if (header != null) GgufReader.read(header) else null
        val verdict = GgufCompatibility.classify(read ?: unreadableHeader(), partialFile.length(), 0L)

        if (verdict.compatibility == CharalyCompatibility.UNSUPPORTED) {
            return fail(
                catalogId,
                DownloadUnavailable.Other(verdict.reason),
                partialFile,
                clearPartial = true,
            )
        }

        // Atomic-ish move. renameTo within one filesystem is atomic on Android's
        // internal storage; when it cannot, fall back to a copy and delete the partial.
        var moved = partialFile.renameTo(finalFile)
        if (!moved) {
            moved = runCatching {
                partialFile.copyTo(finalFile, overwrite = true)
                partialFile.delete()
                true
            }.getOrDefault(false)
        }
        if (!moved) {
            return fail(
                catalogId,
                DownloadUnavailable.Other("The model file could not be moved into place."),
                partialFile,
                clearPartial = true,
            )
        }

        // Build the runtime package from the file's own header. Every field here is
        // observed or derived by ModelPresetLibrary; nothing is left for the user to fill.
        val packageResult = if (read is dev.charaly.runtime.model.gguf.GgufReadResult.Success) {
            ModelPresetLibrary.build(
                metadata = read.metadata,
                displayName = displayName,
                absolutePath = finalFile.absolutePath,
                fileSizeBytes = finalFile.length(),
                availableRamBytes = storage.availableBytes(),
                sha256 = expectedSha256,
                modelId = catalogId,
            )
        } else {
            // The header did not parse but the architecture hint came from the catalog.
            // Still installable as MANUAL_IMPORT_ONLY, which is the honest middle state.
            null
        }

        val artifact = packageResult?.artifact
        val installed = InstalledModel(
            id = catalogId,
            displayName = displayName,
            absolutePath = finalFile.absolutePath,
            sizeBytes = finalFile.length(),
            origin = ModelOrigin.DOWNLOADED,
            catalogId = catalogId,
            architecture = verdict.architecture.ifBlank { architectureHint },
            quantization = packageResult?.artifact?.quantization?.ifBlank { null } ?: quantizationHint,
            contextLength = packageResult?.artifact?.contextLength?.takeIf { it > 0 } ?: contextHint,
            parameterCount = packageResult?.artifact?.parameterCount ?: 0L,
            sha256 = expectedSha256,
            // Verified only when a hash existed and matched. A file with no published hash
            // is not "verified"; saying so would make the word meaningless.
            verified = expectedSha256.isNotBlank(),
            installedAtEpochMs = nowMs(),
            profiles = packageResult?.let { listOf(ModelPresetLibrary.toProfile(it)) }.orEmpty(),
            compatibility = ModelCompatibility(
                loadFailed = false,
                failureReason = "",
                lastLoadedAtEpochMs = 0L,
                chatTemplateDetected = artifact?.chatTemplate?.takeIf { it.isNotBlank() }?.let { "present" }.orEmpty(),
            ),
        )
        registry.register(installed)

        return progressOf(catalogId, DownloadState.READY, written, totalBytes, finalFile.path).copy(
            installedModelId = installed.id,
            message = if (verdict.isReady) {
                "Ready for Charaly"
            } else {
                verdict.reason
            },
            compatibilityLabel = verdict.label(),
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** The leading bytes of a file, enough for the GGUF key/value block. */
    private fun readHeaderBytes(file: File): ByteArray? = runCatching {
        val size = file.length()
        if (size <= 0L) return null
        val toRead = minOf(size, HEADER_READ_BYTES).toInt()
        file.inputStream().use { stream ->
            val buffer = ByteArray(toRead)
            var offset = 0
            while (offset < toRead) {
                val n = stream.read(buffer, offset, toRead - offset)
                if (n <= 0) break
                offset += n
            }
            if (offset <= 0) null else buffer.copyOf(offset)
        }
    }.getOrNull()

    /** sha256 over a file, streamed so a multi-gigabyte model is never held in memory. */
    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(CHUNK_BYTES)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = stream.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hasRoomFor(bytes: Long): Boolean {
        if (bytes <= 0L) return true
        val free = storage.availableBytes()
        // An unknown free figure (-1) is allowed through: refusing on a missing reading
        // would break models on every device whose platform declines to report it.
        if (free <= 0L) return true
        return free > bytes + storageSafetyMarginBytes
    }

    private suspend fun progressOf(
        catalogId: String,
        state: DownloadState,
        bytes: Long,
        total: Long,
        path: String,
    ): DownloadProgress {
        val frame = DownloadProgress(
            catalogId = catalogId,
            state = state,
            bytesDownloaded = bytes,
            totalBytes = total,
            temporaryPath = path,
        )
        snapshots[catalogId] = frame
        progressBus.emit(frame)
        return frame
    }

    private suspend fun fail(
        catalogId: String,
        reason: DownloadUnavailable,
        partialFile: File? = null,
        clearPartial: Boolean = true,
    ): DownloadProgress {
        if (clearPartial) partialFile?.delete()
        val previous = snapshots[catalogId]
        val frame = DownloadProgress(
            catalogId = catalogId,
            state = DownloadState.FAILED,
            bytesDownloaded = previous?.bytesDownloaded ?: 0L,
            totalBytes = previous?.totalBytes ?: 0L,
            temporaryPath = previous?.temporaryPath.orEmpty(),
            message = reason.message,
            failure = reason,
        )
        snapshots[catalogId] = frame
        progressBus.emit(frame)
        return frame
    }

    private suspend fun cancelled(
        catalogId: String,
        bytes: Long,
        total: Long,
        path: String,
    ): DownloadProgress = progressOf(catalogId, DownloadState.CANCELLED, bytes, total, path)
        .copy(message = DownloadUnavailable.Cancelled.message)

    private fun defaultFileName(item: ModelCatalogItem): String =
        item.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-') + ".gguf"

    /** A [java.nio.file] free-function shim: seconds left, or null when unknowable. */
    private fun etaSeconds(done: Long, total: Long, speed: Double): Long? {
        if (speed <= 0.0 || total <= 0L || done >= total) return null
        return ((total - done) / speed).toLong()
    }

    private fun formatSpeed(bytesPerSecond: Double): String = when {
        bytesPerSecond <= 0.0 -> ""
        bytesPerSecond >= 1L shl 20 -> "%.1f MB/s".format(bytesPerSecond / (1L shl 20).toDouble())
        bytesPerSecond >= 1L shl 10 -> "%.0f kB/s".format(bytesPerSecond / 1024.0)
        else -> "%.0f B/s".format(bytesPerSecond)
    }

    private fun formatDuration(seconds: Long?): String {
        val s = seconds ?: return ""
        return when {
            s >= 3600 -> "${s / 3600}h ${(s % 3600) / 60}m left"
            s >= 60 -> "${s / 60}m left"
            else -> "${s}s left"
        }
    }

    /** Cooperative pause/cancel flags for one in-flight transfer. */
    private class JobHandle(val catalogId: String) {
        @Volatile var pauseRequested: Boolean = false
        @Volatile var cancelRequested: Boolean = false
    }

    /**
     * A stand-in read result for a file whose header could not be fetched at all.
     *
     * Classifying it yields UNSUPPORTED, which is the correct outcome: a file with no
     * readable header cannot be installed, and the user is told why. A function rather
     * than a constant because [dev.charaly.runtime.model.gguf.GgufReadResult.Failure] is
     * a data class, and a fresh instance per call keeps the classifier from ever being
     * handed the same object twice by accident.
     */
    private fun unreadableHeader(): dev.charaly.runtime.model.gguf.GgufReadResult =
        dev.charaly.runtime.model.gguf.GgufReadResult.Failure(
            dev.charaly.runtime.model.gguf.GgufFailure.TRUNCATED,
            "The model file's header could not be read.",
        )

    companion object {
        /** 1 MiB. Large enough that syscall overhead is negligible, small enough to cancel fast. */
        const val CHUNK_BYTES = 1024 * 1024

        /** Bytes read when classifying a completed file. Matches the reader's own bound. */
        const val HEADER_READ_BYTES: Long = 8L * 1024L * 1024L

        /** How often speed is recomputed. */
        const val SPEED_SAMPLE_MS = 500L

        /** Free space kept in reserve beyond the download itself. */
        const val DEFAULT_SAFETY_MARGIN = 256L * 1024L * 1024L

        /** Backoff when the socket yields 0 bytes rather than blocking or EOF. */
        private const val IDLE_BACKOFF_MS = 50L
    }
}
