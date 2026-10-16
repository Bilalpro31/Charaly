package dev.charaly.runtime.storypack

import dev.charaly.runtime.net.HttpStreamResponse
import dev.charaly.runtime.net.HttpTextResponse
import dev.charaly.runtime.net.HttpTransport
import dev.charaly.runtime.net.StorageProbe
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * A scripted server: bytes, a manifest, and knobs for everything that goes wrong.
 *
 * Deliberately not a mock of the *pipeline* - the download manager under test is the real
 * one. Only the socket is fake, and it is fake in the ways sockets really fail: truncated
 * bodies, range requests honoured or ignored, status codes, silence.
 */
class FakeHttpTransport(
    private val text: Map<String, HttpTextResponse> = emptyMap(),
    private val blobs: Map<String, ByteArray> = emptyMap(),
    /** Throw an IOException after this many bytes have been read from any stream. */
    private val failStreamAfterBytes: Long = Long.MAX_VALUE,
    /** Answer range requests with 206 (honoured) or 200 (ignored: the body restarts). */
    private val honoursRanges: Boolean = true,
) : HttpTransport {

    val openStreamUrls = mutableListOf<String>()
    val rangeStarts = mutableListOf<Long?>()
    var getTextCalls = 0

    override suspend fun getText(url: String, headers: Map<String, String>): HttpTextResponse {
        getTextCalls++
        return text[url] ?: HttpTextResponse(404, "not found")
    }

    override suspend fun openStream(url: String, rangeStart: Long?, rangeEnd: Long?): HttpStreamResponse {
        openStreamUrls += url
        rangeStarts += rangeStart
        val whole = blobs[url]
            ?: return HttpStreamResponse(404, ByteArrayInputStream(ByteArray(0)), -1L, false)

        val start = if (honoursRanges) rangeStart ?: 0L else 0L
        val body = whole.copyOfRange(start.toInt(), whole.size)
        val truncated = failStreamAfterBytes
        val stream = object : InputStream() {
            var position = 0L
            override fun read(): Int {
                if (position >= truncated) throw IOException("connection reset")
                val value = body.getOrNull(position.toInt())?.toInt() ?: -1
                if (value >= 0) position++
                return value
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (position >= truncated) throw IOException("connection reset")
                val remaining = (body.size - position).toInt().coerceAtMost(len.toLong().coerceAtMost(truncated - position).toInt())
                if (remaining <= 0) return -1
                System.arraycopy(body, position.toInt(), b, off, remaining)
                position += remaining
                return remaining
            }
        }
        return HttpStreamResponse(
            code = if (start > 0 && honoursRanges) 206 else 200,
            stream = stream,
            totalBytes = whole.size.toLong(),
            supportsResume = honoursRanges,
            contentLength = body.size.toLong(),
        )
    }

    companion object {
        fun offline(): FakeHttpTransport = FakeHttpTransport()
    }
}

/** A device with a finite amount of room. */
class FakeStorageProbe(private val available: Long) : StorageProbe {
    override fun availableBytes(): Long = available
}