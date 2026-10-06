package dev.charaly.app.inference

/**
 * Turns the flat `String[]` the JNI layer returns into the shapes the app wants.
 *
 * ## Why this is a separate, tested type
 *
 * Native crosses the boundary as `[key, value, key, value, ...]` because building a
 * real `java.util.HashMap` over JNI costs several calls per entry and a second way to
 * fail. That decision is only safe if the array is turned back into structured data in
 * exactly one place, and if that place is covered by tests that run on a plain JVM.
 *
 * Doing it inline at each call site is what produced the bug this file exists to fix:
 * `runCatching { LlamaNative.modelMetadata(handle) }.getOrDefault(emptyMap())` turned a
 * `ClassCastException` - a broken ABI, the most serious thing that can happen at this
 * boundary - into an empty map that looks exactly like a model with no metadata.
 *
 * ## The distinction being preserved
 *
 * ```
 *   empty array      -> Ok(empty)       the model simply has nothing to report
 *   odd-length array -> Malformed       the contract was violated; this is a bug
 *   native threw     -> Failed          the call did not complete
 * ```
 *
 * Collapsing the last two into the first is what lets a broken build look like a
 * working one, so none of them are silently equivalent here.
 */
internal object NativeReturnParser {

    /** Outcome of parsing a flat key/value array. */
    sealed interface KeyValues {
        /** Parsed cleanly. May be empty when native reported nothing. */
        data class Ok(val values: Map<String, String>) : KeyValues

        /**
         * The array did not honour the `[key, value, ...]` contract.
         *
         * Reached for an odd element count, or an empty key. Never thrown as an
         * exception: a malformed payload is a fact to report, not a crash, and the
         * whole reason this is inspectable is so the diagnostic screen can say so.
         */
        data class Malformed(val detail: String, val entries: Int) : KeyValues

        /** The native call itself did not complete. [reason] is safe to show. */
        data class Failed(val reason: String) : KeyValues

        /** The values, or empty when the array could not be trusted. */
        val valuesOrEmpty: Map<String, String>
            get() = (this as? Ok)?.values ?: emptyMap()
    }

    /**
     * Parses `[key, value, key, value, ...]`.
     *
     * A later duplicate key wins, matching what a `Map` built natively from the same
     * pairs would contain.
     */
    fun parseKeyValues(flat: Array<String>): KeyValues {
        if (flat.isEmpty()) return KeyValues.Ok(emptyMap())

        // Odd length means a key with no value, or a trailing key. The pairs are lost:
        // guessing which half was meant would be inventing data, so this is reported.
        if (flat.size % 2 != 0) {
            return KeyValues.Malformed(
                detail = "expected key/value pairs but native returned ${flat.size} elements",
                entries = flat.size / 2,
            )
        }

        val values = LinkedHashMap<String, String>(flat.size / 2)
        var index = 0
        while (index < flat.size) {
            val key = flat[index]
            val value = flat[index + 1]
            if (key.isEmpty()) {
                return KeyValues.Malformed(
                    detail = "native returned an empty key at index $index",
                    entries = flat.size / 2,
                )
            }
            values[key] = value
            index += 2
        }
        return KeyValues.Ok(values)
    }

    /**
     * Parses the device list.
     *
     * Native sends `"TYPE:name"`, e.g. `"CPU:CPU"` or `"GPU:Vulkan (Adreno 740)"`. The
     * composite form is kept because "which devices" and "of what kind" are different
     * questions and the UI asks both.
     *
     * Blanks and `TYPE:unknown` entries are dropped rather than shown, since a device
     * ggml could not name is not a device the user can use.
     */
    fun parseDevices(flat: Array<String>): List<String> =
        flat.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.endsWith(":unknown") }
            .distinct()
            .toList()

    /**
     * Runs a native call, mapping a thrown ABI/linkage failure to [KeyValues.Failed].
     *
     * The point of routing through here rather than `runCatching { }.getOrDefault(...)`
     * is that a failure now stays a failure. `UnsatisfiedLinkError` in particular is an
     * `Error`, not an `Exception`, and code that only catches `Exception` around this
     * boundary would let it escape into the UI thread.
     */
    fun parseKeyValuesCatching(what: String, call: () -> Array<String>): KeyValues =
        try {
            parseKeyValues(call())
        } catch (error: UnsatisfiedLinkError) {
            KeyValues.Failed("native library is not available ($what)")
        } catch (error: SecurityException) {
            KeyValues.Failed("native library could not be called ($what)")
        } catch (error: Throwable) {
            KeyValues.Failed("native call failed ($what): ${error.javaClass.simpleName}")
        }

    /** [parseKeyValuesCatching] for the device list, keeping the same failure shape. */
    fun parseDevicesCatching(what: String, call: () -> Array<String>): Result<List<String>> =
        try {
            Result.success(parseDevices(call()))
        } catch (error: UnsatisfiedLinkError) {
            Result.failure(NativeCallException("native library is not available ($what)", error))
        } catch (error: Throwable) {
            Result.failure(NativeCallException("native call failed ($what): ${error.javaClass.simpleName}", error))
        }

    /**
     * A native call that did not complete.
     *
     * Deliberately not an [android.util.Log]-style dump of a C++ message: [detail] is
     * written to be read by a person on the diagnostics screen.
     */
    class NativeCallException(
        val detail: String,
        cause: Throwable? = null,
    ) : Exception(detail, cause)
}