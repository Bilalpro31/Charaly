package dev.charaly.runtime.net

/**
 * The network port.
 *
 * ## Why Charaly's engine has an interface instead of a socket
 *
 * `charaly-runtime` is a pure Kotlin/JVM module that must never touch the Android
 * framework, so it cannot call `HttpURLConnection` directly. It also must not *depend*
 * on a network: the story engine has to be fully testable with no network at all.
 *
 * This interface is what makes both true. The app module supplies an Android-backed
 * implementation; tests supply a scripted fake and assert on exact byte counts, resume
 * offsets and checksum behaviour without a server.
 *
 * ## What is deliberately absent
 *
 * No URL rewriting, no retry policy, no caching. Those belong to the implementation
 * behind this port. What the port exposes is exactly the two operations the model
 * catalog needs - a small JSON request, and a byte range of a large file - so there is
 * no way for an implementation to quietly add behaviour the engine does not expect.
 */
interface HttpTransport {

    /** Fetches a small text resource, such as an API listing. Never used for model files. */
    suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): HttpTextResponse

    /**
     * Opens a byte range of a resource for streaming to disk.
     *
     * @param rangeStart first byte to fetch, or null to start at zero
     * @param rangeEnd inclusive last byte, or null for "to the end"
     *
     * Returning a stream rather than a byte array is the whole point: a GGUF is measured
     * in gigabytes and must never be held in memory.
     */
    suspend fun openStream(url: String, rangeStart: Long? = null, rangeEnd: Long? = null): HttpStreamResponse
}

/** A small text response. [code] is the HTTP status. */
data class HttpTextResponse(
    val code: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
) {
    val isSuccessful: Boolean get() = code in 200..299
}

/**
 * An open byte stream.
 *
 * [totalBytes] is the size of the *whole resource*, not of the range, so progress can
 * be reported against the file's real size. It is -1 when the server does not say, which
 * the download pipeline treats as "unknown, do not fake a percentage".
 *
 * [supportsResume] is the server's answer, not an assumption: the Hugging Face CDN
 * supports HTTP range requests, but an implementation must be able to report that it
 * does not, and the pipeline has to cope.
 */
class HttpStreamResponse(
    val code: Int,
    val stream: java.io.InputStream,
    val totalBytes: Long,
    val supportsResume: Boolean,
    val contentLength: Long = -1L,
    val headers: Map<String, String> = emptyMap(),
) : java.io.Closeable {
    fun read(buffer: ByteArray, offset: Int, length: Int): Int = stream.read(buffer, offset, length)

    override fun close() {
        runCatching { stream.close() }
    }
}

/**
 * A read-only view of this device's free space.
 *
 * ## Why the download pipeline asks before it starts
 *
 * A 4.5 GB download into 3 GB of free space fails *after* the user has waited, and on
 * some devices it fails in a way that leaves a partial file consuming the remaining
 * space. The check is a port so it is testable without a device: the fake reports a small
 * number and the pipeline is asserted to refuse before opening a socket.
 */
interface StorageProbe {
    /** Bytes available for writing, or -1 when the platform will not say. */
    fun availableBytes(): Long

    companion object {
        /** Used by tests and previews: a device with room for anything. */
        val UNLIMITED: StorageProbe = object : StorageProbe {
            override fun availableBytes(): Long = Long.MAX_VALUE
        }
    }
}
