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
     */
    val loadFailure: String = "",
) {
    /** Something is bound. Not the same as "it will run". */
    val isBound: Boolean get() = modelId.isNotBlank()

    /** Why this story cannot generate, or [ModelBlockReason.NONE]. */
    val blocked: ModelBlockReason
        get() = when {
            !isBound -> ModelBlockReason.NOT_INSTALLED
            !filePresent -> ModelBlockReason.FILE_MISSING
            loadFailure.isNotBlank() -> ModelBlockReason.LOAD_FAILED
            !engineSupports -> ModelBlockReason.UNSUPPORTED
            else -> ModelBlockReason.NONE
        }

    /**
     * Whether a turn can be requested at all.
     *
     * True the moment a usable model is bound - including while it is still loading. The
     * runtime loads it lazily; asking the user to connect a model that is already
     * selected is the bug this replaces.
     */
    val canGenerate: Boolean get() = blocked == ModelBlockReason.NONE

    /** Usable, but the weights are not in RAM yet. The runtime resolves this on first use. */
    val needsEngineLoad: Boolean get() = canGenerate && !isResident

    /** True only when the model is bound *and* the engine already holds it. */
    val isReady: Boolean get() = canGenerate && isResident

    companion object {
        val NONE = ModelSelection()

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
            return ModelSelection.NONE.copy(
                profileId = binding.profileId,
                profileName = binding.profileName,
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