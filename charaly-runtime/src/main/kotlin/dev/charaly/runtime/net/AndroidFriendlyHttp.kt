package dev.charaly.runtime.net

/**
 * A [HttpTransport] that is honest about what it can and cannot do.
 *
 * ## Why the runtime module cannot just open a socket itself
 *
 * `charaly-runtime` is a pure Kotlin/JVM library. It has no Android dependency, which is
 * what makes the entire story engine testable on a plain JVM with no device. It
 * therefore cannot construct an `HttpURLConnection`, and it must not: a library that
 * opens sockets itself could never be exercised without a network.
 *
 * The app module supplies a real Android-backed implementation of this same port. So
 * network access is a *wiring* decision in the app module, and the engine stays
 * independent of it.
 *
 * ## Why this specific implementation is safe to have in the runtime
 *
 * It does nothing. It is here so that any code path which reaches for a transport
 * without one injected gets a refusal with a reason, rather than a `NullPointerException`
 * or a silently skipped download.
 *
 * Notably it does **not** pretend to work: [getText] and [openStream] both fail with the
 * precise reason, and no method ever reports progress it did not measure.
 */
class OfflineHttpTransport(
    private val reason: String = "This build has no network transport installed.",
) : HttpTransport {

    override suspend fun getText(url: String, headers: Map<String, String>): HttpTextResponse =
        HttpTextResponse(
            code = 0,
            body = "",
            headers = mapOf("charaly-error" to reason),
        )

    override suspend fun openStream(
        url: String,
        rangeStart: Long?,
        rangeEnd: Long?,
    ): HttpStreamResponse = throw java.io.IOException(reason)
}

/**
 * Whether this runtime was built with any network capability at all.
 *
 * Purely informational, for the offline indicator in Settings. It reports what is
 * *wired*, not what is reachable - a device in airplane mode with a transport installed
 * still has a transport.
 */
object NetworkCapability {

    /**
     * True when the app supplied a real transport.
     *
     * Set from the app module at startup. Default false is the honest answer for the
     * bare runtime, which is how the JVM tests see it.
     */
    @Volatile
    var transportInstalled: Boolean = false

    /**
     * Whether network is required for *any* core feature.
     *
     * The answer is no, and it is worth stating as a constant rather than leaving it to
     * inference: stories, memories, world state, inference and installed models are all
     * local. The network is used for exactly one thing, and the app is fully functional
     * without it.
     */
    const val NETWORK_REQUIRED_FOR_CORE: Boolean = false

    /**
     * Whether the Hub is worth trying right now.
     *
     * ## Why the runtime reports this rather than taking an answer
     *
     * "Is there internet" is a question only the platform can answer, and only the app
     * module can ask it. But the *policy* - what an unreachable Hub means for the product -
     * belongs here, so that every caller gets the same answer: discovery and download are
     * unavailable, and nothing else is.
     *
     * It is deliberately a coarse answer. A device with a captive portal has connectivity
     * and no Hub; reporting "online" there would produce a search that fails after a
     * timeout instead of immediately. So the pessimistic reading is the cheap, fast and
     * correct-by-default one, and a real failure while "online" still surfaces its own
     * message with a Retry action.
     */
    fun offline(connectivityAvailable: Boolean = false): Boolean = !connectivityAvailable

    /** What the offline indicator shows. */
    fun statusLabel(internetAvailable: Boolean): String = when {
        !transportInstalled -> "Offline only"
        !internetAvailable -> "Offline Ready"
        else -> "Ready"
    }
}
