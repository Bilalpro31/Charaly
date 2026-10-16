package dev.charaly.runtime.model

/**
 * Turns "this story is bound to a model" into "that model's weights are in memory".
 *
 * ## Why this is a port and not a call into the engine
 *
 * The runtime knows *which* model a story is bound to ([ModelBinding.installedModelId]) and
 * nothing about where its file lives. The app knows the file and the registry. Between them
 * sits exactly one decision - "bring this id into memory" - and if that decision is made in
 * two places it is made differently.
 *
 * This is the seam requirement "READY != LOADED" hangs on:
 *
 * ```
 *   READY  the registry has the id, the file is there, the GGUF parses   <- ModelSelection
 *   LOADED the weights are in native memory right now                    <- this port
 * ```
 *
 * A story turn may be requested the instant the model is READY. The load is *lazy* and
 * happens here, on first use, and it may fail and be tried again. Nothing about a load
 * outcome is a permission to enter a chat.
 */
fun interface ModelResidency {

    /**
     * Brings the model bound by [binding] into native memory.
     *
     * Returns true when the engine is holding exactly that model when this returns. False
     * means it could not be brought in - the caller reports the reason, which implementations
     * expose separately so the user gets "not enough memory" rather than "false".
     */
    suspend fun ensureResident(binding: ModelBinding): Boolean

    companion object {
        /**
         * Answers from the engine's own state and never loads anything.
         *
         * The default for a runtime built without a loader - tests, and any host that has an
         * engine but no model files. It keeps the historical behaviour exactly: `respond`
         * fails with `ModelNotLoaded` when the engine is cold.
         */
        fun immediate(engine: dev.charaly.runtime.inference.InferenceEngine): ModelResidency =
            ModelResidency { engine.isLoaded() }
    }
}