package dev.charaly.runtime.model

/**
 * The one authoritative model lifecycle state, spanning import to generation.
 *
 * ## Why this enum exists beside [ModelBlockReason]
 *
 * [ModelBlockReason] answers "why cannot this story generate *right now*". It is
 * a per-turn blocker, and it deliberately collapses the cases that produce the
 * same blocker. The user-facing life of a model - importing, waiting for a
 * native load, resident, failed - is a different question, and it is the one the
 * import pipeline, the model library and the engine status panel all narrate.
 * Having it written down once, as data, is what keeps a screen from inventing
 * its own wording for "LOADING" while another invents "IMPORTING" for the same
 * underlying fact.
 *
 * The invariant it encodes:
 *
 * ```
 *   READY  !=  LOADED
 *
 *   READY   the file exists, the header parsed, the registry knows it.
 *   LOADED  the native engine holds the weights in RAM, right now.
 * ```
 *
 * A model is READY immediately after import and stays READY forever, even
 * across a process death, unless the file disappears or corrupts. It is
 * LOADED only between a successful `loadModel` and the next unload / process
 * exit. A freshly installed model is READY + NOT LOADED, and nothing about
 * that is an error.
 */
enum class ModelLifecycle {
    /** No registry entry; or an explicit reset. */
    NOT_INSTALLED,

    /** The .part copy is streaming onto disk. Never a registry entry. */
    IMPORTING,

    /** The file is on disk but the header has not been parsed yet. */
    IMPORTED,

    /** The header parse + compatibility check is running. */
    VALIDATING,

    /** Verified, registered, on disk. Not necessarily in RAM. */
    READY,

    /** Registry entry exists but the file is gone. */
    FILE_MISSING,

    /** A load was asked for; the engine has not started yet. */
    LOAD_REQUESTED,

    /** llama.cpp is reading the file into RAM. */
    LOADING,

    /** Resident in RAM. Generation can start. */
    LOADED,

    /** A decode is running. */
    GENERATING,

    /** The last load attempt failed; the engine holds nothing. */
    LOAD_FAILED,

    /** Registered, but this engine build cannot load its architecture. */
    UNSUPPORTED,

    /** The file failed GGUF validation. */
    CORRUPT,
    ;

    /** Whether the model file is known to be in a usable state. */
    val isUsableOnDisk: Boolean
        get() = this == READY || this == LOAD_REQUESTED || this == LOADING ||
            this == LOADED || this == GENERATING
}

/**
 * Derives the lifecycle from the facts the rest of the system already keeps.
 *
 * Pure and total: every input is nullable-or-blank, and a missing fact maps to
 * the most honest state rather than throwing. `generating` is transient engine
 * truth and wins when present because it is the newest fact in the system.
 */
fun modelLifecycle(
    /** Registry entry exists. */
    installed: Boolean,
    /** Import/validation pipeline is running. */
    importing: Boolean = false,
    validating: Boolean = false,
    /** File exists on disk (checked, not assumed). */
    filePresent: Boolean = true,
    /** Header parsed successfully at import time. */
    verified: Boolean = true,
    /** Header parsing explicitly failed: the file is not a usable GGUF. */
    validationFailed: Boolean = false,
    /** Architecture is unsupported by the bundled engine. */
    unsupported: Boolean = false,
    /** Last load attempt failed. */
    loadFailed: Boolean = false,
    /** A load was requested but has not completed. */
    loadRequested: Boolean = false,
    /** llama.cpp is actively loading. */
    loading: Boolean = false,
    /** Engine holds the weights. */
    resident: Boolean = false,
    /** A decode is running. */
    generating: Boolean = false,
): ModelLifecycle = when {
    importing -> ModelLifecycle.IMPORTING
    validating -> ModelLifecycle.VALIDATING
    !installed -> ModelLifecycle.NOT_INSTALLED
    unsupported -> ModelLifecycle.UNSUPPORTED
    validationFailed -> ModelLifecycle.CORRUPT
    !filePresent -> ModelLifecycle.FILE_MISSING
    loadFailed -> ModelLifecycle.LOAD_FAILED
    !verified -> ModelLifecycle.IMPORTED
    generating && resident -> ModelLifecycle.GENERATING
    resident -> ModelLifecycle.LOADED
    loading -> ModelLifecycle.LOADING
    loadRequested -> ModelLifecycle.LOAD_REQUESTED
    else -> ModelLifecycle.READY
}
