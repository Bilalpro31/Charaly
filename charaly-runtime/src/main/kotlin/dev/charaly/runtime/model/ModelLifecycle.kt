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
 * The single-residency, transition-checked model runtime state machine.
 *
 * ## Why a machine and not a set of booleans
 *
 * `[loaded, loading, generating]` as three independent flags admits eighteen combinations,
 * twelve of which cannot happen - and "cannot happen" states are exactly where a race ends
 * up. `GENERATING -> unload` in particular is not a state anybody designed, it is just what
 * two unlocked coroutines produce when a story turn overlaps a model switch: the native
 * context is freed underneath a decode.
 *
 * So transitions are declared, and anything not declared is refused. The rules:
 *
 * ```
 *   NOT_INSTALLED -> IMPORTED -> READY -> LOADING -> LOADED -> GENERATING -> LOADED
 *                                          ^           |           |
 *                                          |           v           v
 *                                     LOAD_FAILED <----+-------(stop)
 *                                          |
 *                                          v
 *                                        READY          (a failure is retryable)
 * ```
 *
 * `GENERATING -> LOADING` and `GENERATING -> NOT_INSTALLED` do not exist. To leave
 * `GENERATING` you must stop the generation first - which is what "a generation may not be
 * unloaded out from under" means in code rather than in a code review.
 *
 * This is pure: it holds no engine, no handle and no thread. `LocalLlamaInferenceEngine`
 * drives it, and the rules themselves are asserted on a JVM with no model file at all.
 */
class ModelLifecycleMachine(initial: ModelLifecycle = ModelLifecycle.NOT_INSTALLED) {

    private var current: ModelLifecycle = initial

    /** The state the machine is in. Reading it never mutates it. */
    val state: ModelLifecycle get() = current

    /**
     * Attempts [next]. Returns the new state, or [TransitionResult.Rejected] with the rule
     * that was violated - never a silent no-op, because a caller that thinks it loaded a
     * model and did not is the bug class this whole file exists to remove.
     */
    @Synchronized
    fun transition(next: ModelLifecycle): TransitionResult {
        if (next == current) return TransitionResult.Rejected(current, next, "already in $current")
        if (!isAllowed(current, next)) {
            return TransitionResult.Rejected(current, next, rule(current, next))
        }
        current = next
        return TransitionResult.Entered(next)
    }

    /** Unconditional reset, for process-level teardown. Not part of the transition table. */
    @Synchronized
    fun reset(to: ModelLifecycle = ModelLifecycle.NOT_INSTALLED) {
        current = to
    }

    sealed interface TransitionResult {
        data class Entered(val state: ModelLifecycle) : TransitionResult
        data class Rejected(
            val from: ModelLifecycle,
            val to: ModelLifecycle,
            val because: String,
        ) : TransitionResult
    }

    companion object {

        /**
         * The declared edges.
         *
         * A state may only move along one of these. Anything omitted is illegal, including
         * `GENERATING -> LOADING`, `GENERATING -> NOT_INSTALLED` and `LOADING -> GENERATING`
         * (a load must finish before a decode may start).
         */
        val ALLOWED: Map<ModelLifecycle, Set<ModelLifecycle>> = mapOf(
            ModelLifecycle.NOT_INSTALLED to setOf(
                ModelLifecycle.IMPORTING, ModelLifecycle.IMPORTED, ModelLifecycle.FILE_MISSING,
                // A brand-new engine knows no model until it is asked to load one, so the
                // first fact it ever learns is "loading". Demanding an IMPORTED step first
                // would make the very first load illegal.
                ModelLifecycle.LOAD_REQUESTED, ModelLifecycle.LOADING,
            ),
            ModelLifecycle.IMPORTING to setOf(
                ModelLifecycle.IMPORTED, ModelLifecycle.NOT_INSTALLED, ModelLifecycle.CORRUPT,
            ),
            ModelLifecycle.IMPORTED to setOf(
                ModelLifecycle.VALIDATING, ModelLifecycle.READY, ModelLifecycle.CORRUPT,
                ModelLifecycle.NOT_INSTALLED,
            ),
            ModelLifecycle.VALIDATING to setOf(
                ModelLifecycle.READY, ModelLifecycle.CORRUPT, ModelLifecycle.IMPORTED,
            ),
            ModelLifecycle.READY to setOf(
                ModelLifecycle.LOADING, ModelLifecycle.LOAD_REQUESTED,
                ModelLifecycle.FILE_MISSING, ModelLifecycle.UNSUPPORTED,
                ModelLifecycle.NOT_INSTALLED,
            ),
            ModelLifecycle.LOAD_REQUESTED to setOf(
                ModelLifecycle.LOADING, ModelLifecycle.READY,
                ModelLifecycle.LOAD_FAILED, ModelLifecycle.FILE_MISSING,
            ),
            ModelLifecycle.LOADING to setOf(
                ModelLifecycle.LOADED, ModelLifecycle.LOAD_FAILED,
            ),
            ModelLifecycle.LOADED to setOf(
                ModelLifecycle.GENERATING, ModelLifecycle.READY,
                ModelLifecycle.NOT_INSTALLED, ModelLifecycle.FILE_MISSING,
            ),
            ModelLifecycle.GENERATING to setOf(
                // ONLY back to LOADED. Stopping a decode is a precondition of everything
                // else: unload, switch and process death all have to go through LOADED.
                ModelLifecycle.LOADED,
            ),
            ModelLifecycle.LOAD_FAILED to setOf(
                ModelLifecycle.READY, ModelLifecycle.LOADING, ModelLifecycle.LOAD_REQUESTED,
                ModelLifecycle.UNSUPPORTED, ModelLifecycle.NOT_INSTALLED, ModelLifecycle.FILE_MISSING,
            ),
            ModelLifecycle.UNSUPPORTED to setOf(
                ModelLifecycle.READY, ModelLifecycle.NOT_INSTALLED, ModelLifecycle.FILE_MISSING,
            ),
            ModelLifecycle.CORRUPT to setOf(
                ModelLifecycle.READY, ModelLifecycle.IMPORTED,
                ModelLifecycle.NOT_INSTALLED, ModelLifecycle.FILE_MISSING,
            ),
            ModelLifecycle.FILE_MISSING to setOf(
                ModelLifecycle.READY, ModelLifecycle.IMPORTED,
                ModelLifecycle.NOT_INSTALLED,
            ),
        )

        fun isAllowed(from: ModelLifecycle, to: ModelLifecycle): Boolean =
            ALLOWED[from]?.contains(to) == true

        private fun rule(from: ModelLifecycle, to: ModelLifecycle): String = when {
            from == ModelLifecycle.GENERATING ->
                "stop the running generation before leaving GENERATING"
            from == ModelLifecycle.LOADING && to == ModelLifecycle.GENERATING ->
                "a load must settle before a decode may start"
            to == ModelLifecycle.LOADING && from !in setOf(
                ModelLifecycle.READY, ModelLifecycle.LOAD_REQUESTED, ModelLifecycle.LOAD_FAILED,
                ModelLifecycle.NOT_INSTALLED,
            ) ->
                "a model must be READY (or a retry of LOAD_FAILED) before it can load"
            else -> "no transition is declared from $from to $to"
        }
    }
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
