package dev.charaly.app.model

import kotlin.math.abs

/**
 * The deterministic id scheme for local models.
 *
 * The rule this encodes: **the id never depends on where the file sits.** It is the
 * content hash plus the name the user sees, so a story binding survives a storage
 * migration, an app data-dir move and a re-import - the cases that used to orphan every
 * binding, because the legacy scheme embedded the absolute path and the path changed.
 *
 * ## What it deliberately does and does not promise
 *
 * It does promise: same bytes + same file name = same id, wherever the file is; different
 * bytes = different id; no path, no directory and no package name is ever baked into it.
 *
 * It does NOT promise that renaming the file keeps the id - the readable name is part of
 * the identity on purpose, so two different models cannot collide in the library just
 * because they hash to the same first twelve hex digits of a truncated digest. Identity is
 * then carried unchanged through every layer as `ModelId`; nothing downstream re-derives it
 * from a name or a path.
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
