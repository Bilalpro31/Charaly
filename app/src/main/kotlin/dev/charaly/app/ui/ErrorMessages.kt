package dev.charaly.app.ui

import dev.charaly.runtime.inference.InferenceError
import dev.charaly.runtime.presentation.Loc
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

    /**
     * Runtime-level failures (story pipeline).
     *
     * Localised through the runtime catalogue, because these are the sentences a player
     * reads after something has already gone wrong - the worst possible moment to hand
     * somebody a string in a language they did not choose.
     */
    fun of(error: CharalyError): String = when (error) {
        is CharalyError.ModelNotLoaded -> Loc.t("chat.needs_model")
        is CharalyError.NoCharacter -> Loc.t("error.no_character")
        is CharalyError.NoScene -> Loc.t("error.no_scene")
        is CharalyError.Generation -> Loc.t("error.generation_failed")
        is CharalyError.Cancelled -> Loc.t("error.generation_cancelled")
        is CharalyError.Persistence -> Loc.t("error.persistence")
    }

    /** Inference-engine failures, translated into user language. */
    fun of(error: InferenceError): String = when (error) {
        is InferenceError.ModelNotLoaded -> Loc.t("chat.needs_model")
        is InferenceError.ModelNotFound -> Loc.t("error.model_not_found")
        is InferenceError.InvalidModel -> Loc.t("error.invalid_model")
        is InferenceError.OutOfMemory -> Loc.t("error.out_of_memory")
        is InferenceError.GenerationFailed -> Loc.t("error.generation_failed")
        is InferenceError.Cancelled -> Loc.t("error.generation_cancelled")
        is InferenceError.Unsupported -> Loc.t("error.unsupported_engine")
    }

    // Named rather than inline so a test can assert on the wording and a change to one
    // branch cannot silently reword only some of them.
    val NO_MODEL: String get() = Loc.t("chat.needs_model")
    val TOO_LARGE: String get() = Loc.t("error.too_large_model")
    val GENERATION_FAILED: String get() = Loc.t("error.generation_failed")
    val CORRUPT_MODEL: String get() = Loc.t("error.invalid_model")
}