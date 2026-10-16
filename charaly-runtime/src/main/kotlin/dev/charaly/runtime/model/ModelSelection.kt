package dev.charaly.runtime.model

import java.io.File

/**
 * THE AUTHORITATIVE MODEL ANSWER.
 *
 * ## Why this type exists
 *
 * Before this, "is a model connected?" had four different answers in four different
 * layers, and the device bug that produced this file was the disagreement between them:
 *
 * ```
 *   ModelRegistry   YES   the GGUF was imported, registered, and set active
 *   StoryInstance   YES   modelBinding.installedModelId names it
 *   runtime.engine  NO    llama.cpp has nothing resident (still loading, or the load failed)
 *   Chat            NO    modelReady == false -> "Connect a local model"
 * ```
 *
 * Three of those are about *identity* (which file will speak) and one is about *residency*
 * (is the file currently in RAM). The chat screen was reading the fourth one and therefore
 * told a user with a working, imported, selected model to go and connect a model.
 *
 * So [ModelSelection] is the one value every layer reads. It keeps the two facts apart:
 *
 * * [canGenerate] - identity + installability. This is what gates the composer, the Continue
 *   button and `respond()`. Residency is deliberately **not** part of it: a 4 GB model
 *   takes tens of seconds to become resident, and a user who just imported it has not
 *   failed at anything.
 * * [needsEngineLoad] - residency. This is what the runtime resolves lazily on first use.
 *
 * ## The invariant it protects
 *
 * One id, one path, one display name, at every layer. If a screen can render a model name,
 * it is rendering [displayName]; if it can open the model, it is opening [absolutePath];
 * if it binds a story, it is binding [modelId]. There is no second lookup table that could
 * name a different file.
 */
data class ModelSelection(
    /** [InstalledModel.id]. Empty means "nothing is bound". */
    val modelId: String = "",
    /** [InstalledModel.displayName]. The only name a screen may show for this model. */
    val displayName: String = "",
    /** [InstalledModel.absolutePath]. The only path a loader may open. */
    val absolutePath: String = "",
    /** [ModelBinding.profileId]. */
    val profileId: String = "",
    /** [ModelBinding.profileName]. */
    val profileName: String = "",
    /** Clamped, phone-survivable context window from the model header. */
    val contextTokens: Int = 0,
    /** From the GGUF header, never inferred from the file name. */
    val architecture: String = "",
    /**
     * Whether the bundled engine registers [architecture].
     *
     * `true` for a blank architecture, deliberately: an unread header means "unknown",
     * and refusing to load a real model because its header has not been parsed yet would
     * be the exact failure this whole type exists to remove.
     */
    val engineSupports: Boolean = true,
    /** Whether the file is still on disk. Checked, because a deleted file is not a model. */
    val filePresent: Boolean = true,
    /** The engine currently holds this model in RAM. A performance fact, not a permission. */
    val isResident: Boolean = false,
    /**
     * A human-readable reason the last load attempt failed, or empty.
     *
     * Recorded from the engine, never from an exception's `toString()`.
     *
     * This is *history*, not a prohibition - see [canGenerate].
     */
    val loadFailure: String = "",
    /**
     * The id a story is bound to that no longer resolves to any installed model.
     *
     * Set only when a binding names a model the registry does not have. Distinct from
     * [filePresent] because the two call for different sentences: "your file was deleted"
     * and "this story is bound to a model that is not on this device any more".
     */
    val unresolvedBindingId: String = "",
) {
    /** Something is bound. Not the same as "it will run". */
    val isBound: Boolean get() = modelId.isNotBlank()

    /**
     * Why this story cannot generate, or [ModelBlockReason.NONE].
     *
     * Ordered so the *newest* fact wins: a file that vanished outranks a stale note about
     * a load that failed weeks ago.
     */
    val blocked: ModelBlockReason
        get() = when {
            !isBound && unresolvedBindingId.isNotBlank() -> ModelBlockReason.FILE_MISSING
            !isBound -> ModelBlockReason.NOT_INSTALLED
            !filePresent -> ModelBlockReason.FILE_MISSING
            loadFailure.isNotBlank() -> ModelBlockReason.LOAD_FAILED
            !engineSupports -> ModelBlockReason.UNSUPPORTED
            else -> ModelBlockReason.NONE
        }

    /**
     * Whether a turn can be requested at all.
     *
     * True the moment a usable model is bound - including while it is still loading, and
     * including after a load attempt failed.
     *
     * ## Why LOAD_FAILED is not a lock
     *
     * It used to be, and that was the "the chat says connect a local model while the model
     * is sitting right there in the library" bug. [loadFailure] is a *record of a past
     * attempt*: it survives a restart (it is written to the registry), and a load can fail
     * for reasons that are gone by the next attempt - a low-memory moment, another app in
     * the foreground, a process that was still starting up. Treating that record as a
     * permission made one bad attempt permanent: the composer greyed out with "this story
     * needs a local model", `ensureModelResident` refused to even try again, and the model
     * could not be recovered without knowing to re-select it by hand.
     *
     * So a failed load is *diagnosed* ([blocked], [loadFailure], the model detail screen)
     * and then *retried*. The runtime's `ensureModelResident` is the retry, and if the load
     * fails again the user gets that attempt's real reason rather than a locked box.
     */
    val canGenerate: Boolean
        get() = when (blocked) {
            ModelBlockReason.NONE, ModelBlockReason.LOAD_FAILED -> true
            else -> false
        }

    /** Usable, but the weights are not in RAM yet. The runtime resolves this on first use. */
    val needsEngineLoad: Boolean get() = blocked == ModelBlockReason.NONE && !isResident

    /**
     * True only when the model is bound, usable *and* the engine already holds it.
     *
     * `LOADED`, in the vocabulary of [ModelLifecycle]. [canGenerate] is `READY`. They are
     * deliberately different answers, and neither is the gate on entering a chat.
     */
    val isReady: Boolean get() = blocked == ModelBlockReason.NONE && isResident

    /**
     * The one lifecycle state for this model, derived from the facts above.
     *
     * A screen that wants to say "LOADING" or "LOAD_FAILED" reads this rather than
     * assembling its own verdict from booleans - which is how one screen ended up saying
     * "Ready" for the same model another called "Could not load".
     */
    val lifecycle: ModelLifecycle
        get() = modelLifecycle(
            installed = isBound,
            filePresent = filePresent,
            verified = filePresent,
            unsupported = isBound && !engineSupports,
            loadFailed = isBound && loadFailure.isNotBlank(),
            resident = isResident,
        )

    companion object {
        val NONE = ModelSelection()

        /**
         * The one place that turns a binding's identity into a lifecycle-compatible
         * request. Kept here so `ModelSelection` is the only type that has to know both
         * vocabularies.
         */
        fun lifecycleOf(selection: ModelSelection): ModelLifecycle = selection.lifecycle

        /**
         * The legacy-shaped answer, for call sites that only have a boolean.
         *
         * Present so an existing `modelReady = true/false` call site keeps meaning exactly
         * what it meant, rather than silently turning into something else.
         */
        fun ofReadyFlag(ready: Boolean): ModelSelection = if (ready) {
            ModelSelection(
                modelId = "legacy",
                displayName = "",
                profileId = "",
                filePresent = true,
                isResident = true,
            )
        } else {
            ModelSelection(modelId = "", filePresent = true)
        }
    }
}

/**
 * Why a model cannot be used right now.
 *
 * An enum rather than a nullable string so "we do not know" and "we know and it is
 * broken" cannot collapse into the same blank.
 */
enum class ModelBlockReason {
    /** Nothing is bound. */
    NOT_INSTALLED,

    /** Bound, but the file is gone from disk. */
    FILE_MISSING,

    /** Bound and present, but the last load attempt failed. */
    LOAD_FAILED,

    /** Bound, present, and the bundled engine does not register its architecture. */
    UNSUPPORTED,

    /** Usable. */
    NONE,
}

/**
 * Builds the one [ModelSelection] every layer reads.
 *
 * Pure: it takes the registry list, the active id, the story's binding and the engine's
 * residency, and answers. It never loads, never writes, and never guesses.
 *
 * ## Precedence, and why a missing bound model does not fall back
 *
 * ```
 *   story binding.installedModelId   (what this story was created to speak with)
 *   activeModelId                    (what the user just imported/selected)
 *   nothing                          (an honest "no model")
 * ```
 *
 * A story whose bound model was deleted does **not** silently adopt the currently active
 * model. Its own transcript, sampler settings and context budget were resolved against
 * that file, and swapping the file underneath them mid-story is exactly the kind of quiet
 * change this codebase refuses everywhere else. A story bound to nothing (created before
 * any model was installed) *does* adopt the active model, because it was never bound to
 * anything.
 */
object ModelSelectionResolver {

    fun resolve(
        binding: ModelBinding,
        installed: List<InstalledModel>,
        activeModelId: String,
        residentModelId: String? = null,
        /** Existence check, injected so this stays pure and testable. */
        fileExists: (InstalledModel) -> Boolean = { candidate ->
            runCatching { File(candidate.absolutePath).isFile }.getOrDefault(true)
        },
        engineSupports: (InstalledModel) -> Boolean = { candidate ->
            EngineCapabilities.supports(candidate.architecture) || candidate.architecture.isBlank()
        },
    ): ModelSelection {
        val target = installed.firstOrNull { it.id == binding.installedModelId }
            ?: if (binding.installedModelId.isBlank()) {
                installed.firstOrNull { it.id == activeModelId }
            } else {
                null
            }
        if (target == null) {
            // A binding that names a model the registry does not have is a *different*
            // problem from "nothing was ever bound", and it is the one that produced a
            // misleading "connect a local model" on a story that had a perfectly good
            // model bound to it. Record which id failed to resolve so the UI can say
            // "this story's model is no longer on this device" instead.
            return ModelSelection.NONE.copy(
                profileId = binding.profileId,
                profileName = binding.profileName,
                unresolvedBindingId = binding.installedModelId,
            )
        }
        return ModelSelection(
            modelId = target.id,
            displayName = target.displayName,
            absolutePath = target.absolutePath,
            profileId = binding.profileId.ifBlank { target.profiles.firstOrNull()?.id.orEmpty() },
            profileName = binding.profileName.ifBlank { target.profiles.firstOrNull()?.name.orEmpty() },
            contextTokens = target.effectiveContextTokens(),
            architecture = target.architecture,
            engineSupports = engineSupports(target),
            filePresent = fileExists(target),
            isResident = residentModelId != null && residentModelId == target.id,
            loadFailure = target.compatibility.failureReason
                .takeIf { target.compatibility.loadFailed }
                .orEmpty(),
        )
    }

    /**
     * The selection a *new* story would get.
     *
     * Same precedence as [resolve] but starting from "no binding yet", so the story-setup
     * screen and the runtime that creates the story cannot disagree about which model is
     * about to be used.
     */
    fun resolveForNewStory(
        packDefaultProfileId: String,
        requestedModelId: String,
        installed: List<InstalledModel>,
        activeModelId: String,
        residentModelId: String? = null,
        fileExists: (InstalledModel) -> Boolean = { candidate ->
            runCatching { File(candidate.absolutePath).isFile }.getOrDefault(true)
        },
        engineSupports: (InstalledModel) -> Boolean = { candidate ->
            EngineCapabilities.supports(candidate.architecture) || candidate.architecture.isBlank()
        },
        nowEpochMs: Long = 0L,
    ): ModelSelection = resolve(
        binding = ModelBindingResolver.resolve(
            packDefaultProfileId = packDefaultProfileId,
            installedModel = installed.firstOrNull { it.id == requestedModelId }
                ?: installed.firstOrNull { it.id == activeModelId },
            userProfileId = null,
            nowEpochMs = nowEpochMs,
        ),
        installed = installed,
        activeModelId = activeModelId,
        residentModelId = residentModelId,
        fileExists = fileExists,
        engineSupports = engineSupports,
    )
}