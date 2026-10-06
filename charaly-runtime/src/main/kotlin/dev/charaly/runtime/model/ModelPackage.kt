package dev.charaly.runtime.model

import kotlinx.serialization.Serializable

/**
 * Everything Charaly needs to know about one model, bundled as a single unit.
 *
 * ## Why a package and not just a file
 *
 * A GGUF on disk is bytes. To actually *run* it well you also need: how much context it
 * tolerates, what sampler settings keep it in character, what system prompt it responds
 * to, what stop tokens stop it from rambling, and how it was built. Historically those
 * are separate things the user had to assemble - which is why a model library can show
 * an installed model and still produce bad stories.
 *
 * Bundling them means `USE MODEL` actually works. The user picks a model, and Charaly
 * already knows how to talk to it. Everything remains overridable under Advanced.
 *
 * ## Provenance of each part
 *
 * - [Artifact] is read from the file itself. It is the only *observed* part.
 * - [Compatibility] is derived from [EngineCapabilities].
 * - [Sampling], [Context] and [Prompt] are chosen by [ModelPresetLibrary] from the
 *   artifact, and can be overridden.
 */
@Serializable
data class ModelPackage(
    val artifact: ModelArtifact,
    val compatibility: CompatibilityProfile,
    val runtime: ModelRuntimeProfile,
    val prompt: PromptProfile,
    val output: OutputProtocol,
) {
    val modelId: String get() = artifact.modelId
    val displayName: String get() = artifact.displayName

    /** The one call a user needs. No configuration. */
    fun isReadyForCharaly(): Boolean = compatibility.supportsArchitecture &&
        compatibility.engineVerdict.isLoadable &&
        artifact.contextLength > 0

    /** The honest one-line explanation when it is not ready. */
    fun readinessReason(): String = when {
        compatibility.engineVerdict.isLoadable -> ""
        else -> compatibility.engineVerdict.reason
    }
}

/**
 * What is physically on disk.
 *
 * Everything here is *observed*, never assumed: the header is read at import time. If a
 * field could not be read it is blank and the compatibility profile says so, rather than
 * a plausible guess being written into the registry.
 */
@Serializable
data class ModelArtifact(
    val modelId: String,
    val displayName: String,
    val absolutePath: String,
    val sizeBytes: Long = 0L,
    /** ggml architecture straight out of the GGUF header. */
    val architecture: String = "",
    val quantization: String = "",
    val contextLength: Int = 0,
    val parameterCount: Long = 0L,
    val sha256: String = "",
    val chatTemplate: String = "",
    val verified: Boolean = false,
) {
    init {
        require(modelId.isNotBlank()) { "ModelArtifact needs a modelId" }
        require(absolutePath.isNotBlank()) { "ModelArtifact needs a path" }
    }

    val sizeLabel: String get() = formatBytes(sizeBytes)

    /**
     * Parameter count in the form a model card shows: "7B", "1.7B", "270M".
     *
     * Reads "unknown" rather than "0B" when the file does not state one, because
     * "0B" on a card looks like a broken entry rather than an absent measurement.
     */
    val parameterLabel: String
        get() = when {
            parameterCount >= 1_000_000_000L -> "%.1fB".format(parameterCount / 1_000_000_000.0)
            parameterCount >= 1_000_000L -> "%dM".format(parameterCount / 1_000_000L)
            parameterCount > 0L -> parameterCount.toString()
            else -> "unknown"
        }

    /**
     * The one-line technical summary a model card shows in its secondary line:
     * `7B · Q4_K_M · 4.5 GB · 8192 ctx`.
     *
     * Parts the file did not declare are omitted rather than filled with a placeholder,
     * so the line never contains "unknown · unknown".
     */
    val techSummary: String
        get() = listOfNotNull(
            parameterLabel.takeIf { it != "unknown" },
            quantization.takeIf { it.isNotBlank() },
            sizeLabel.takeIf { it != "unknown" },
            contextLength.takeIf { it > 0 }?.let { "$it ctx" },
        ).joinToString(" · ").ifBlank { "no metadata" }
}

/**
 * How to run it, and how to talk to it.
 *
 * The defaults here are what "USE MODEL" applies. They are conservative on purpose: a
 * small on-device model given a 32k context and a high temperature produces a worse
 * story than one given a sane budget, and the user should not have to discover that.
 */
@Serializable
data class ModelRuntimeProfile(
    val recommendedContext: Int = 4096,
    val maxOutputTokens: Int = 320,
    val temperature: Float = 0.85f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val minP: Float = 0.05f,
    val repetitionPenalty: Float = 1.1f,
    /**
     * Tokens that end a reply.
     *
     * Critical rather than cosmetic: without a stop token a model will happily narrate
     * the user's next line too, and the story starts writing itself.
     */
    val stopSequences: List<String> = emptyList(),
    val useGpuAcceleration: Boolean = true,
    val threads: Int = 0,
    val batchSize: Int = 256,
    /** Transcript turns to include in the prompt. */
    val transcriptTurns: Int = 6,
    /** How many memories to retrieve. */
    val memoryCount: Int = 6,
) {
    init {
        require(recommendedContext >= 512) { "context must be usable ($recommendedContext)" }
        require(maxOutputTokens > 0) { "maxOutputTokens must be positive" }
        require(temperature in 0f..2f) { "temperature must be 0..2, was $temperature" }
        require(topP in 0f..1f) { "topP must be 0..1, was $topP" }
        require(topK >= 0) { "topK must not be negative" }
    }

    /**
     * Context clamped to what the artifact can actually hold.
     *
     * Asking a model for more context than it supports produces truncation, not quality,
     * so the ceiling is the file's own number.
     */
    fun effectiveContext(artifactContext: Int): Int =
        if (artifactContext > 0) minOf(recommendedContext, artifactContext) else recommendedContext
}

/**
 * The prompt scaffolding this model responds well to.
 *
 * Templates rather than fixed strings, because a small model needs simpler instructions
 * than a large one and a chat-tuned model needs different phrasing from a completion
 * one. Everything a user would otherwise assemble by hand.
 */
@Serializable
data class PromptProfile(
    val systemPrompt: String = "",
    val characterTemplate: String = "",
    val worldTemplate: String = "",
    val memoryTemplate: String = "",
    val sceneTemplate: String = "",
    /**
     * The action-protocol instructions.
     *
     * This is what turns prose into a proposed action. It must match
     * [dev.charaly.runtime.director.ProposedActionParser.INSTRUCTIONS] exactly, so it is
     * not written by hand - it is imported from the parser, keeping prompt and parser
     * from drifting apart.
     */
    val actionProtocolPrompt: String = "",
    /**
     * Whether this model's template puts a system role first.
     *
     * Verified per architecture rather than assumed. A template that does not support a
     * leading system turn will silently drop the most important instructions in the
     * prompt, which is worse than not sending them.
     */
    val supportsSystemRole: Boolean = true,
) {
    companion object {
        /**
         * The action instructions, taken from the parser itself.
         *
         * Duplicated prompt text is how a protocol silently stops working: the prompt
         * says `move` and the parser expects `character_move`. Deriving one from the
         * other makes that class of bug impossible.
         */
        fun actionProtocol(): String = dev.charaly.runtime.director.ProposedActionParser.INSTRUCTIONS
    }
}

/**
 * What the model is allowed to emit, and how Charaly reads it back.
 *
 * Two layers, deliberately: the reader (narrative) and the writer (proposals). The user
 * sees only the first; the second is consumed by the parser and never rendered.
 */
@Serializable
data class OutputProtocol(
    /** The model may produce dialogue and narration. Always true in practice. */
    val dialogue: Boolean = true,
    val narration: Boolean = true,
    /** The model may emit structured action tags, which are validated before use. */
    val proposedAction: Boolean = true,
    /** The model may ask for a scene transition. */
    val sceneControl: Boolean = false,
) {
    val supportsStructuredProposals: Boolean get() = proposedAction
}

/**
 * What this build can actually do with the model.
 *
 * Every field here is either read from the engine or measured. Nothing is a promise:
 * `minimumRamBytes` is derived from the artifact size plus a context allowance, and
 * `supportedArchitecture` comes from [EngineCapabilities].
 */
@Serializable
data class CompatibilityProfile(
    val architecture: String,
    val engineVerdict: EngineVerdict,
    val bundledLlamaCppCommit: String = EngineCapabilities.BUNDLED_COMMIT,
    val androidAbis: List<String> = listOf("arm64-v8a"),
    /** Approximate RAM to hold the weights plus a usable context. */
    val minimumRamBytes: Long = 0L,
    val recommendedRamBytes: Long = 0L,
    val supportsMultimodal: Boolean = false,
    val supportsSystemRole: Boolean = true,
) {
    val supportsArchitecture: Boolean get() = engineVerdict.isLoadable

    val isUsable: Boolean
        get() = engineVerdict.isLoadable && (minimumRamBytes <= 0L || recommendedRamBytes > 0L)

    /** Why this model cannot be used, in words a user can act on. */
    fun blocker(): String = when {
        !engineVerdict.isLoadable -> engineVerdict.reason
        else -> ""
    }
}