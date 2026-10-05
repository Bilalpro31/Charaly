package dev.charaly.app.ui

import dev.charaly.runtime.inference.InferenceError
import dev.charaly.runtime.session.CharalyError

/**
 * Every failure the user can see, written as a sentence plus a next step.
 *
 * Two rules, and they are the reason this is its own file:
 *  1. no stack traces, no file offsets, no error codes - a person is reading this;
 *  2. each message says what happened AND what to do, because "something went wrong"
 *     is not an answer.
 *
 * The mapping is a pure function, so the copy is unit tested instead of eyeballed.
 */
object ErrorMessages {

    /** Runtime-level failures (story pipeline). */
    fun of(error: CharalyError): String = when (error) {
        is CharalyError.ModelNotLoaded -> NO_MODEL
        is CharalyError.NoCharacter -> "Nobody is available to talk in this scene yet."
        is CharalyError.NoScene -> "The scene could not be placed. Move somewhere else first."
        is CharalyError.Generation -> GENERATION_FAILED
        is CharalyError.Cancelled -> "Generation stopped."
        is CharalyError.Persistence -> "That change could not be saved on this device."
    }

    /** Inference-engine failures, translated into user language. */
    fun of(error: InferenceError): String = when (error) {
        is InferenceError.ModelNotLoaded -> NO_MODEL
        is InferenceError.ModelNotFound -> "This model file could not be found on this device."
        is InferenceError.InvalidModel -> "This model file could not be loaded."
        is InferenceError.OutOfMemory -> TOO_LARGE
        is InferenceError.GenerationFailed -> "The local model stopped unexpectedly."
        is InferenceError.Cancelled -> "Generation stopped."
        is InferenceError.Unsupported ->
            "Local inference is unavailable in this build. Rebuild with scripts/setup-llama.sh."
    }

    const val NO_MODEL = "This story needs a local model before it can continue."
    const val TOO_LARGE = "This model may exceed the available device memory."
    const val GENERATION_FAILED = "Your local model couldn't finish this scene."
    const val CORRUPT_MODEL = "This model file could not be loaded."
}