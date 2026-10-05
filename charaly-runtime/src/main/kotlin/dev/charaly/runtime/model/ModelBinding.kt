package dev.charaly.runtime.model

import kotlinx.serialization.Serializable

/**
 * The model configuration a StoryInstance actually runs with.
 *
 * This is a *snapshot*, resolved once when the story is created:
 *
 * ```
 * StoryPack.defaultModelProfileId
 *      + installed model capabilities
 *      + user choice for this story
 *      -> ModelBinding (copied into the instance)
 * ```
 *
 * Because it is copied, a user changing the global default later cannot silently
 * change an existing story. That is what makes a story reproducible.
 */
@Serializable
data class ModelBinding(
    /** Stable id of the InstalledModel, or an empty string when none is bound. */
    val installedModelId: String = "",
    val modelDisplayName: String = "",
    val profileId: String = "",
    val profileName: String = "",
    val sampler: SamplerSettings = SamplerSettings(),
    val contextPolicy: ContextPolicy = ContextPolicy(),
    val narrativeStyle: NarrativeStyle = NarrativeStyle.NEUTRAL,
    val thinkingBehavior: ThinkingBehavior = ThinkingBehavior.STRIP,
    /** Model-reported context window, clamped to something a phone can survive. */
    val maxContextTokens: Int = 2048,
    val boundAtEpochMs: Long = 0L,
) {
    val isBound: Boolean get() = installedModelId.isNotBlank()

    fun describe(): String = when {
        isBound -> "$modelDisplayName · ${profileName.ifBlank { profileId }}"
        else -> "No model bound yet"
    }

    companion object {
        val EMPTY = ModelBinding()

        fun from(
            installedModelId: String,
            modelDisplayName: String,
            profile: ModelProfile,
            maxContextTokens: Int = 2048,
            boundAtEpochMs: Long = 0L,
        ): ModelBinding = ModelBinding(
            installedModelId = installedModelId,
            modelDisplayName = modelDisplayName,
            profileId = profile.id,
            profileName = profile.name,
            sampler = profile.sampler,
            contextPolicy = profile.contextPolicy,
            narrativeStyle = profile.narrativeStyle,
            thinkingBehavior = profile.thinkingBehavior,
            maxContextTokens = maxContextTokens,
            boundAtEpochMs = boundAtEpochMs,
        )

        /** Re-binding only swaps the sampler/policy, keeping the same model. */
        fun withProfile(binding: ModelBinding, profile: ModelProfile): ModelBinding =
            binding.copy(
                profileId = profile.id,
                profileName = profile.name,
                sampler = profile.sampler,
                contextPolicy = profile.contextPolicy,
                narrativeStyle = profile.narrativeStyle,
                thinkingBehavior = profile.thinkingBehavior,
            )
    }
}

/** Sampler settings -> the inference port's parameters. The only conversion. */
fun SamplerSettings.toGenerationParams(): dev.charaly.runtime.inference.GenerationParams =
    dev.charaly.runtime.inference.GenerationParams(
        maxTokens = maxTokens,
        temperature = temperature,
        topP = topP,
        topK = topK,
        minP = minP,
        repeatPenalty = repeatPenalty,
        seed = seed,
        stopSequences = stopSequences,
    )

/** And back, for the model detail screen's Advanced section. */
fun dev.charaly.runtime.inference.GenerationParams.toSamplerSettings(): SamplerSettings =
    SamplerSettings(
        temperature = temperature,
        topP = topP,
        topK = topK,
        minP = minP,
        repeatPenalty = repeatPenalty,
        maxTokens = maxTokens,
        seed = seed,
        stopSequences = stopSequences,
    )

/**
 * Combines the three inputs the spec requires, in one deterministic place.
 *
 * Nothing else is allowed to assemble generation parameters: the UI asks this
 * resolver, the resolver asks the binding, and ContextBuilder stays the only
 * class that turns any of it into prompt text.
 */
object ModelBindingResolver {

    /** Phones cannot hold a 128k context. This is a ceiling, not a suggestion. */
    const val DEVICE_CONTEXT_CEILING = 8192

    /**
     * Resolves the binding for a new story.
     *
     * Precedence: explicit user choice > StoryPack default > built-in balanced.
     */
    fun resolve(
        packDefaultProfileId: String,
        installedModel: InstalledModel?,
        userProfileId: String? = null,
        nowEpochMs: Long = 0L,
    ): ModelBinding {
        val profile = ModelProfileLibrary.resolve(
            userProfileId?.takeIf { it.isNotBlank() }
                ?: packDefaultProfileId.takeIf { it.isNotBlank() },
        )
        val model = installedModel ?: return ModelBinding(
            profileId = profile.id,
            profileName = profile.name,
            sampler = profile.sampler,
            contextPolicy = profile.contextPolicy,
            narrativeStyle = profile.narrativeStyle,
            thinkingBehavior = profile.thinkingBehavior,
            boundAtEpochMs = nowEpochMs,
        )
        return ModelBinding.from(
            installedModelId = model.id,
            modelDisplayName = model.displayName,
            profile = profile,
            maxContextTokens = model.effectiveContextTokens(),
            boundAtEpochMs = nowEpochMs,
        )
    }
}

/**
 * Wraps a resolved binding for use by the runtime, and refuses to silently ignore
 * a model that disappeared from disk.
 */
data class StoryModelRuntime(
    val binding: ModelBinding,
    val installedModel: InstalledModel?,
) {
    val isReady: Boolean get() = installedModel != null

    fun profile(): ModelProfile = ModelProfileLibrary.resolve(binding.profileId).copy(
        sampler = binding.sampler,
        contextPolicy = binding.contextPolicy,
        narrativeStyle = binding.narrativeStyle,
        thinkingBehavior = binding.thinkingBehavior,
    )

    /** Human readable reason a story cannot generate, or null when it can. */
    fun unavailableReason(available: Boolean): String? = when {
        available && isReady -> null
        available -> "This story needs a local model before it can continue."
        else -> "The model for this story is no longer on this device."
    }
}