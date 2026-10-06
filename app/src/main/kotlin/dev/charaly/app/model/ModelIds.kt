package dev.charaly.app.model

import kotlin.math.abs

/**
 * The deterministic id scheme for local models.
 *
 * Contents, not location, decide identity: the same weights hash to the same
 * id no matter where the file sits, so a story binding survives a rename, a
 * re-import, or a app data-dir migration. The legacy scheme embedded the
 * absolute path, so every rename orphaned every binding.
 */
internal object ModelIds {

    fun idFor(displayName: String, sha256: String, absolutePath: String): String {
        val slug = displayName.lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .split('-')
            .filter { it.isNotEmpty() }
            .joinToString("-")
            .take(48)
            .ifBlank { "model" }
        return if (sha256.isNotBlank()) {
            "local-$slug-${sha256.take(12)}"
        } else {
            // Legacy fallback for entries whose hash was never recorded. Still
            // path-dependent, so it is the migration cost, not the destination.
            "local-$slug-${abs(absolutePath.hashCode())}"
        }
    }
}
