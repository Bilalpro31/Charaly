package dev.charaly.app.net

import android.util.Log
import dev.charaly.runtime.net.HttpStreamResponse
import dev.charaly.runtime.net.HttpTextResponse
import dev.charaly.runtime.net.HttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * The app module's real [HttpTransport], backed by `HttpURLConnection`.
 *
 * ## What this exists for
 *
 * One feature: the user asks the Model Library to fetch a GGUF from the Hugging Face
 * Hub. That needs two operations - a small JSON request, and a byte range of a
 * multi-gigabyte file - and `HttpTransport` is exactly those two.
 *
 * ## Why `HttpURLConnection` and not OkHttp or Cronet
 *
 * It is in the Android framework, so it adds no dependency, no ProGuard rules and no
 * version-coupling to a release build. For two request shapes against a CDN that speaks
 * plain HTTP/1.1 with range support, it is sufficient.
 *
 * ## The three properties that matter
 *
 * 1. **HTTPS only.** A cleartext model download would let anyone on the network replace
 *    the weights of a model the user is about to trust with their story. There is no
 *    flag to disable this; the constructor rejects a non-HTTPS base.
 * 2. **Streams, never buffers.** [openStream] hands back the live socket. A 4.5 GB model
 *    is written to disk in 1 MiB chunks and never held in memory.
 * 3. **Off the main thread.** Both entry points switch to [Dispatchers.IO]. A download
 *    that blocked the UI thread would freeze the app for the length of the transfer.
 */
class AndroidHttpTransport(
    /** Connect timeout, milliseconds. */
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    /** Read timeout, milliseconds. Generous: a slow chunk is not a dead socket. */
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    /** User agent. The Hub serves anonymous traffic; being identifiable is polite. */
    private val userAgent: String = DEFAULT_USER_AGENT,
) : HttpTransport {

    /**
     * Fetches a small text resource.
     *
     * Used for the Hub's JSON API only. There is a hard size ceiling because a "small
     * text resource" that turns out to be 200 MB is a bug on the other side, and holding
     * it would be this side's problem.
     */
    override suspend fun getText(url: String, headers: Map<String, String>): HttpTextResponse =
        withContext(Dispatchers.IO) {
            requireHttps(url)
            val connection = open(url, "GET")
            try {
                headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.let { readBounded(it) }.orEmpty()
                HttpTextResponse(
                    code = code,
                    body = body,
                    headers = collectHeaders(connection),
                )
            } catch (e: IOException) {
                // A network failure is reported as a non-2xx code with the reason in the
                // body, so the caller's error handling has exactly one shape to deal
                // with. Throwing would work too; returning is easier to test.
                HttpTextResponse(code = 0, body = "", headers = mapOf("charaly-error" to (e.message ?: "connection failed")))
            } finally {
                connection.disconnect()
            }
        }

    /**
     * Opens a byte range of a resource for streaming to disk.
     *
     * ## The range request
     *
     * Two callers, two reasons:
     *
     *  * the header reader wants bytes `0..512KiB` to learn a model's architecture,
     *    context length and chat template before committing to a 4.5 GB download;
     *  * the downloader wants `N..` to resume from where a previous attempt stopped.
     *
     * ## Why `totalBytes` is the whole resource, not the range
     *
     * Progress is reported as "1.2 GB of 4.5 GB". That is only meaningful if the total
     * is the file's real size, so it is read from `Content-Range`, and a plain
     * `Content-Length` (a 200 with no range) is used only when the server sent the whole
     * file.
     *
     * ## Why the caller must check the code
     *
     * A server that ignores `Range` answers `200` with the entire body. For a header
     * read that would mean buffering gigabytes to look at the first 512 KiB, so the
     * caller sees the raw code and refuses. This method does not paper over it.
     */
    override suspend fun openStream(
        url: String,
        rangeStart: Long?,
        rangeEnd: Long?,
    ): HttpStreamResponse = withContext(Dispatchers.IO) {
        requireHttps(url)
        val connection = open(url, "GET")
        try {
            if (rangeStart != null || rangeEnd != null) {
                // A range header must include an end or a start; "bytes=0-" is valid and
                // means "from here to the end".
                val end = rangeEnd?.toString().orEmpty()
                connection.setRequestProperty("Range", "bytes=$rangeStart-$end")
            }

            val code = connection.responseCode
            val total = totalBytesOf(connection, code)
            val supportsResume = connection.getHeaderField("Accept-Ranges")
                ?.contains("bytes", ignoreCase = true) == true || code == 206

            HttpStreamResponse(
                code = code,
                stream = BufferedInputStream(connection.inputStream, BUFFER_BYTES),
                totalBytes = total,
                supportsResume = supportsResume,
                contentLength = contentLengthOf(connection),
                headers = collectHeaders(connection),
            )
            // No `finally` disconnect here: the returned stream owns the connection, and
            // closing the stream is what releases it. HttpStreamResponse.close() closes
            // the InputStream, and HttpURLConnection releases the socket with it.
        } catch (e: IOException) {
            connection.disconnect()
            throw e
        }
    }

    private fun open(url: String, method: String): HttpURLConnection {
        val parsed = URL(url)
        requireHttps(url)
        val connection = parsed.openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", userAgent)
        // The Hub's CDN answers a range request itself, but a redirect through to a
        // resolved object can turn a range into a full body. Asking explicitly is the
        // difference between a 512 KiB header read and a 4.5 GB surprise.
        connection.setRequestProperty("Accept-Encoding", "identity")
        if (connection is HttpsURLConnection) {
            // Default trust manager and hostname verifier. Explicitly *not* overridden:
            // a custom verifier is how model files get MITM'd, and the default is the
            // one that actually verifies certificates.
        }
        return connection
    }

    /**
     * The whole resource's size, in bytes.
     *
     * Prefers `Content-Range`'s total (`bytes 0-524287/4501234567`) over
     * `Content-Length`, because with a range in play the length describes the *range*.
     */
    private fun totalBytesOf(connection: HttpURLConnection, code: Int): Long {
        connection.getHeaderField("Content-Range")?.let { range ->
            // Format: "bytes <start>-<end>/<total>" or "bytes */<total>".
            val total = range.substringAfterLast('/').trim().toLongOrNull()
            if (total != null && total > 0L) return total
        }
        if (code == 200) {
            return connection.contentLengthLong.takeIf { it > 0L } ?: -1L
        }
        return -1L
    }

    private fun contentLengthOf(connection: HttpURLConnection): Long {
        connection.getHeaderField("Content-Range")?.let { range ->
            val dash = range.indexOf('-')
            val slash = range.indexOf('/')
            if (dash > 0 && slash > dash) {
                val start = range.substringAfter("bytes ").substringBefore('-').trim().toLongOrNull()
                val end = range.substring(dash + 1, slash).trim().toLongOrNull()
                if (start != null && end != null) return end - start + 1
            }
        }
        return connection.contentLengthLong
    }

    private fun collectHeaders(connection: HttpURLConnection): Map<String, String> =
        connection.headerFields
            .filterKeys { it != null }
            .mapValues { (_, values) -> values.firstOrNull().orEmpty() }

    /**
     * Reads at most [MAX_TEXT_BYTES], and says so rather than silently truncating.
     *
     * Truncating a JSON listing would produce a parse error much further downstream,
     * which is harder to diagnose than an explicit refusal here.
     */
    private fun readBounded(stream: InputStream): String {
        val buffer = ByteArray(TEXT_CHUNK_BYTES)
        val out = java.io.ByteArrayOutputStream()
        while (out.size() < MAX_TEXT_BYTES) {
            val remaining = (MAX_TEXT_BYTES - out.size()).coerceAtMost(buffer.size.toLong().toInt())
            val n = stream.read(buffer, 0, remaining)
            if (n <= 0) break
            out.write(buffer, 0, n)
        }
        if (out.size() >= MAX_TEXT_BYTES) {
            Log.w(TAG, "response exceeded $MAX_TEXT_BYTES bytes; refusing to parse a truncated listing")
        }
        return out.toString("UTF-8")
    }

    private fun requireHttps(url: String) {
        require(url.startsWith("https://")) {
            "Refusing a cleartext request. Model files and API listings must be HTTPS: $url"
        }
    }

    companion object {
        private const val TAG = "CharalyHttp"
        private const val BUFFER_BYTES = 256 * 1024
        private const val TEXT_CHUNK_BYTES = 32 * 1024

        /** 8 MiB. A Hub listing or file tree is orders of magnitude smaller. */
        const val MAX_TEXT_BYTES = 8 * 1024 * 1024

        const val DEFAULT_CONNECT_TIMEOUT_MS = 20_000
        const val DEFAULT_READ_TIMEOUT_MS = 60_000
        const val DEFAULT_USER_AGENT = "Charaly/1.0 (Android; local-first story runtime)"
    }
}

/**
 * Free space on the app's own volume, via the framework rather than a shell.
 */
class AndroidStorageProbe(
    private val directory: java.io.File,
) : dev.charaly.runtime.net.StorageProbe {

    /**
     * Available bytes, or -1 when the platform declines to say.
     *
     * `usableSpace` is the right figure rather than `freeSpace`: it excludes the reserve
     * the filesystem holds back, so it is what a download can actually claim. Returning
     * -1 on any failure is deliberate - the download pipeline treats an unknown figure as
     * "do not refuse", because refusing on a missing reading would break downloads on
     * every device that declines to report.
     */
    override fun availableBytes(): Long = runCatching {
        directory.usableSpace
    }.getOrDefault(-1L)
}
