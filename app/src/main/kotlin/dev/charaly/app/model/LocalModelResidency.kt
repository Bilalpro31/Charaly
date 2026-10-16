package dev.charaly.app.model

import dev.charaly.runtime.inference.InferenceEngine
import dev.charaly.runtime.inference.InferenceError
import dev.charaly.runtime.inference.LoadOutcome
import dev.charaly.runtime.inference.ModelLoadRequest
import dev.charaly.runtime.model.InstalledModel
import dev.charaly.runtime.model.ModelBinding
import dev.charaly.runtime.model.ModelRegistry
import dev.charaly.runtime.model.ModelResidency
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * THE lazy model loader.
 *
 * ```
 *   ModelBinding.installedModelId
 *        -> ModelRegistry.get(id)          (identity -> the one record)
 *        -> file on disk?                  (a deleted file is not a model)
 *        -> ModelLoadRequest               (path + id, never a name lookup)
 *        -> InferenceEngine.loadModel      (JNI -> llama.cpp -> the GGUF)
 * ```
 *
 * ## The three things it guarantees
 *
 * 1. **One id, all the way down.** The [ModelLoadRequest.installedModelId] is the registry's
 *    id, so "is the model I asked for the one that is resident?" is a question with an
 *    answer. Nothing here looks a model up by file name or by display name.
 *
 * 2. **One load at a time.** Two turns fired at once (or a turn overlapping a model switch)
 *    arrive here together. Without a single-flight lock the second `loadModel` would free the
 *    handle the first one had just created and the app would report a model the engine no
 *    longer holds - which is precisely the "registered but not loaded" disagreement this
 *    class exists to end.
 *
 * 3. **A load failure is not a verdict.** It is recorded, reported, and the next call tries
 *    again. Nothing here marks a model permanently unusable, because a load can fail for
 *    reasons that have gone by the next attempt (a low-memory moment, a busy device).
 *
 * The engine itself also serialises and refuses to free a context under a running decode;
 * this class must not rely on being the only caller.
 */
class LocalModelResidency(
    private val registry: ModelRegistry,
    private val engine: InferenceEngine,
) : ModelResidency {

    /** Serialises load/unload so "one model resident" is a fact, not a hope. */
    private val lock = Mutex()

    /** The reason the last [ensureResident] failed, in user language. Blank when none. */
    @Volatile
    var lastFailure: String = ""
        private set

    /** The engine's own error for the last failure, for the diagnostics screen only. */
    @Volatile
    var lastDiagnostic: String = ""
        private set

    /** What happened the last time residency was resolved. Drives the UI's model row. */
    @Volatile
    var lastOutcome: Outcome = Outcome.AlreadyResident(null)
        private set

    override suspend fun ensureResident(binding: ModelBinding): Boolean = lock.withLock {
        resolveLocked(binding.installedModelId)
    }

    /**
     * Makes [model] the single resident model, unloading whatever is there first.
     *
     * Explicitly for a *user* selection: the diagram "A loaded -> select B -> unload A ->
     * load B" is a single exclusive step here, so A's native memory is released before B's
     * load begins and neither can leak.
     */
    suspend fun switchTo(model: InstalledModel): Outcome = lock.withLock {
        if (engine.isLoaded()) runCatching { engine.unloadModel() }
        loadLocked(model)
    }

    /** Drops the resident model and its context. Safe to call when nothing is resident. */
    suspend fun unload(): Unit = lock.withLock {
        runCatching { engine.unloadModel() }
        lastOutcome = Outcome.Unloaded
        lastFailure = ""
        lastDiagnostic = ""
    }

    fun isResident(modelId: String): Boolean = engine.isLoaded() && lastLoadedId == modelId

    @Volatile
    private var lastLoadedId: String = ""

    private suspend fun resolveLocked(modelId: String): Boolean {
        if (modelId.isBlank()) {
            lastOutcome = Outcome.NothingBound
            lastFailure = ""
            return false
        }
        // Already the resident model? Then there is nothing to do - reloading a model that
        // is in memory is a multi-second no-op, and doing it on every turn would make a
        // chat feel broken while being technically correct.
        if (engine.isLoaded() && lastLoadedId == modelId) {
            lastOutcome = Outcome.AlreadyResident(modelId)
            lastFailure = ""
            return true
        }
        val model = registry.get(modelId)
        if (model == null) {
            // A story bound to a model the registry does not have. This is a binding
            // problem and it says so; it is not "no model installed".
            lastOutcome = Outcome.Unresolved(modelId)
            lastFailure = ""
            return false
        }
        return when (val outcome = loadLocked(model)) {
            is Outcome.Loaded, is Outcome.AlreadyResident -> true
            else -> false
        }
    }

    private suspend fun loadLocked(model: InstalledModel): Outcome {
        if (!modelFilePresent(model)) {
            lastOutcome = Outcome.FileMissing(model.id)
            lastFailure = ""
            lastDiagnostic = ""
            return lastOutcome
        }
        val result = runCatching {
            engine.loadModel(
                ModelLoadRequest(
                    path = model.absolutePath,
                    displayName = model.displayName,
                    // THE registry id, carried across the boundary so residency can be
                    // attributed to the right model. See ModelLoadRequest.installedModelId.
                    installedModelId = model.id,
                ),
            )
        }
        val outcome = when (val loaded = result.getOrNull()) {
            is LoadOutcome.Loaded -> {
                lastLoadedId = model.id
                lastFailure = ""
                lastDiagnostic = ""
                Outcome.Loaded(model.id, loaded.info.displayName)
            }
            is LoadOutcome.Failed -> {
                lastLoadedId = ""
                lastFailure = failureSentence(loaded.error)
                lastDiagnostic = loaded.error.message.orEmpty()
                Outcome.Failed(model.id, lastFailure)
            }
            null -> {
                lastLoadedId = ""
                lastFailure = ""
                lastDiagnostic = result.exceptionOrNull()?.message.orEmpty()
                Outcome.Failed(model.id, "")
            }
        }
        lastOutcome = outcome
        return outcome
    }

    /**
     * The sentence shown for a failed load.
     *
     * Goes through the same mapper the chat screen uses, so "not enough memory" reads the
     * same here as it does in a story. An [InferenceError] never reaches the user as its
     * own `message`, which is how a path or a JNI detail would leak.
     */
    private fun failureSentence(error: InferenceError): String =
        dev.charaly.app.ui.ErrorMessages.of(error)

    private fun modelFilePresent(model: InstalledModel): Boolean =
        runCatching { java.io.File(model.absolutePath).isFile }.getOrDefault(false)

    /** What resolving a residency actually did. */
    sealed interface Outcome {
        /** Nothing was bound, so nothing could be loaded. */
        data object NothingBound : Outcome

        /** The engine was already holding this id. */
        data class AlreadyResident(val modelId: String?) : Outcome

        /** A load ran and succeeded. */
        data class Loaded(val modelId: String, val displayName: String) : Outcome

        /** The load ran and failed. [reason] is written for a person. */
        data class Failed(val modelId: String, val reason: String) : Outcome

        /** The registry entry exists but the file behind it does not. */
        data class FileMissing(val modelId: String) : Outcome

        /** The binding names an id the registry does not have. */
        data class Unresolved(val modelId: String) : Outcome

        /** The resident model was dropped. */
        data object Unloaded : Outcome
    }
}