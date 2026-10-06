package dev.charaly.runtime.model

import kotlinx.serialization.Serializable

/**
 * What Charaly asks a model to be, on this device.
 *
 * ## Why the system prompt is generated rather than written per model
 *
 * There is exactly one system prompt, built at runtime from this profile plus the
 * engine's own protocol. Not because a hardcoded string would be shorter, but because
 * the prompt has two halves with different owners:
 *
 * ```
 *   engine protocol    how to talk to Charaly at all
 *                      <- ModelProfile, fixed for a given GGUF file
 *
 *   world facts        what is true right now
 *                      <- ContextBuilder, per scene, per character
 * ```
 *
 * Conflating them is how a story engine ends up with canon baked into a prompt template,
 * where editing a system prompt silently rewrites the world.
 *
 * ## Why the user never edits any of this
 *
 * A user who has to hand-tune a prompt to get a working story will usually just not.
 * Every field here is derived: sampler values come from the quantisation and context
 * length read out of the file's own header, the output format comes from whether the
 * file declares a chat template, and the memory and world instructions are the same for
 * every model because they are the engine's contract, not the model's preference.
 *
 * The one thing a user *can* change is [NarrativeStyle], and that is prose register
 * rather than protocol - a real preference that changes output without changing
 * correctness.
 */
@Serializable
data class CharalyRuntimeProfile(
    /** Stable id. Derived from the artifact, so the same file always maps to the same id. */
    val id: String,
    val modelName: String,
    val family: String,
    val architecture: String,
    val parameterCount: Long,
    val quantization: String,
    val contextLength: Int,

    // ---- engine protocol ------------------------------------------------

    /** How the model is told what it is and what it may not do. */
    val systemPrompt: String,

    /** The machine-readable action protocol. See `ProposedActionParser`. */
    val actionSchema: String,
    val actionTagInstructions: String,

    /** Expected shape of a normal reply. Derived from whether a chat template exists. */
    val outputFormat: String,
    val stopSequences: List<String>,

    // ---- sampling -------------------------------------------------------

    val sampler: SamplerSettings,

    // ---- context --------------------------------------------------------

    /**
     * How much world context this model tolerates.
     *
     * The single most important number in this profile. A 1B model with a 4k window
     * cannot hold the same prompt as a 8B model with 32k, and a story app that ignores
     * this produces a model that has forgotten the location it is standing in.
     */
    val contextPolicy: ContextPolicy,

    // ---- instructions the engine owns ------------------------------------

    val memoryInstructions: String,
    val worldInstructions: String,
    val characterInstructions: String,
    val storyInstructions: String,

    // ---- presentation ----------------------------------------------------

    val narrativeStyle: NarrativeStyle,

    /**
     * Whether reasoning blocks are stripped.
     *
     * Reasoning models emit `<think>...</think>`. Allowed into the transcript, that text
     * becomes narration the player did not ask for, so the default is to strip.
     */
    val thinkingBehavior: ThinkingBehavior,

    /** Provenance. A profile built from a file is not the same as a hand-authored one. */
    val derivedFrom: Provenance,
) {
    @Serializable
    enum class Provenance {
        /** Read from the GGUF's own header. The normal case. */
        DERIVED_FROM_FILE,

        /** Charaly's default, used when a file's header could not be read. */
        DEFAULT_FALLBACK,

        /** Written by hand by a user. */
        USER_AUTHORED,
    }

    /**
     * The capability contract, as the model is told it.
     *
     * ## Why this is a contract and not a request
     *
     * The list of things a model cannot do is enforced in code - the engine validates
     * every proposed action, and plain prose can never become world state. This text is
     * not what makes that true.
     *
     * It is here so that the model does not *try*. A model told "you cannot change the
     * world" spends tokens describing a world change it would then have discarded, and
     * on a small model that wasted effort is visible in the prose: it narrates a door
     * opening and then contradicts itself two lines later.
     */
    fun capabilityContract(): String = CAPABILITY_CAN + "\n" + CAPABILITY_CANNOT

    /** One line for a card footer. Never a fabricated measurement. */
    fun summaryLine(): String = listOfNotNull(
        parameterLabel(),
        quantization.takeIf { it.isNotBlank() },
        contextLength.takeIf { it > 0 }?.let { "${it} ctx" },
    ).joinToString(" · ").ifBlank { "GGUF model" }

    private fun parameterLabel(): String? = when {
        parameterCount >= 1_000_000_000 -> "%.1fB".format(parameterCount / 1_000_000_000.0)
        parameterCount >= 1_000_000 -> "%dM".format(parameterCount / 1_000_000)
        parameterCount > 0 -> "$parameterCount"
        else -> null
    }

    /** Converts to the existing per-story [ModelProfile] shape. */
    fun toModelProfile(storyPackId: dev.charaly.runtime.domain.StoryPackId? = null): ModelProfile =
        ModelProfile(
            id = id,
            name = "$modelName - Charaly",
            description = summaryLine(),
            sampler = sampler,
            contextPolicy = contextPolicy,
            narrativeStyle = narrativeStyle,
            responseFormat = outputFormat,
            systemPromptPreamble = systemPrompt,
            actionTagInstructions = actionTagInstructions,
            thinkingBehavior = thinkingBehavior,
            isBuiltIn = false,
            storyPackId = storyPackId,
        )

    companion object {
        /**
         * The model may:
         *
         * Stated positively, and specifically. A model asked to "be creative" with the
         * world will be. Listing the four things it is *for* is what makes the rest of
         * the contract land.
         */
        const val CAPABILITY_CAN: String =
            "You can: narrate the scene, write dialogue in character, react to what " +
                "happens around you, and maintain one character's voice across a long " +
                "conversation."

        /**
         * The model may not:
         *
         * Note what is absent: there is no "do not be creative" clause. The model is
         * restricted in *what it may assert*, not in how it may write.
         */
        const val CAPABILITY_CANNOT: String =
            "You cannot: change the world directly, invent facts that are not given to " +
                "you, know anything a character has not learned, override what has " +
                "already happened, or create information that is being kept secret from " +
                "the person you are speaking to."

        const val MEMORY_INSTRUCTIONS: String =
            "Treat the memories listed below as everything you actually remember. If " +
                "something is not there, you do not know it - say so in character rather " +
                "than guessing. A memory that has been contradicted is no longer true."

        const val WORLD_INSTRUCTIONS: String =
            "The world continues without you. Time passes, people move, and things " +
                "happen elsewhere that you will hear about later. You are inside this " +
                "world, not running it."

        const val CHARACTER_INSTRUCTIONS: String =
            "Stay in one character's voice. Speak only as them, only from their " +
                "perspective, and only about what they can perceive right now."

        const val STORY_INSTRUCTIONS: String =
            "A scene may be quiet. Not everything that happens is dramatic, and an " +
                "uneventful moment is allowed. React to what is actually happening " +
                "rather than escalating it."
    }
}

/**
 * Builds a [CharalyRuntimeProfile] from a GGUF file's own metadata.
 *
 * ## The whole point: nothing here is a per-model hand-written config
 *
 * Every field is derived from something observed:
 *
 * ```
 *   quantisation     -> sampler temperature and repetition penalty
 *   context length   -> how much world context fits
 *   chat template    -> whether the output format is prose or structured
 *   parameter count  -> nothing, because it is display metadata only
 * ```
 *
 * A 4-bit quantisation genuinely does want a lower temperature and a higher repetition
 * penalty than a 6-bit one - low-bit weights produce more small token noise, and a
 * higher penalty suppresses it. That relationship is real, so it is encoded as a
 * function rather than as a table of guesses per model name.
 */
object CharalyProfileFactory {

    /**
     * Builds the profile for an installed artifact.
     *
     * [contextLength] is clamped: a file can declare an absurd value, and honouring it
     * would produce a prompt the device cannot hold. The clamp is a *budget*, not a
     * claim about the model's capability, which is why it is silent rather than
     * displayed.
     */
    fun build(
        modelId: String,
        displayName: String,
        architecture: String,
        parameterCount: Long,
        quantization: String,
        fileContextLength: Int,
        hasChatTemplate: Boolean,
        chatTemplateName: String = "",
        style: NarrativeStyle = NarrativeStyle.NEUTRAL,
        sizeBytes: Long = 0L,
    ): CharalyRuntimeProfile {
        val context = fileContextLength
            .takeIf { it > 0 }
            ?.coerceIn(MIN_CONTEXT, MAX_CONTEXT)
            ?: DEFAULT_CONTEXT

        val quant = quantization.lowercase()
        val sampler = samplerFor(quant, context)
        val policy = contextPolicyFor(context)

        return CharalyRuntimeProfile(
            id = "charaly-$modelId",
            modelName = displayName,
            family = familyOf(architecture, displayName),
            architecture = architecture,
            parameterCount = parameterCount,
            quantization = quantization,
            contextLength = context,
            systemPrompt = buildSystemPrompt(displayName),
            actionSchema = ACTION_PROTOCOL,
            actionTagInstructions = actionInstructions(hasChatTemplate),
            outputFormat = outputFormat(hasChatTemplate),
            stopSequences = stopSequences(),
            sampler = sampler,
            contextPolicy = policy,
            memoryInstructions = CharalyRuntimeProfile.MEMORY_INSTRUCTIONS,
            worldInstructions = CharalyRuntimeProfile.WORLD_INSTRUCTIONS,
            characterInstructions = CharalyRuntimeProfile.CHARACTER_INSTRUCTIONS,
            storyInstructions = CharalyRuntimeProfile.STORY_INSTRUCTIONS,
            narrativeStyle = style,
            thinkingBehavior = ThinkingBehavior.STRIP,
            derivedFrom = CharalyRuntimeProfile.Provenance.DERIVED_FROM_FILE,
        )
    }

    /** The header could not be read. A conservative profile, not a fabricated one. */
    fun fallback(
        modelId: String,
        displayName: String,
        architecture: String,
    ): CharalyRuntimeProfile = build(
        modelId = modelId,
        displayName = displayName,
        architecture = architecture,
        parameterCount = 0L,
        quantization = "",
        fileContextLength = 0,
        hasChatTemplate = false,
    ).copy(derivedFrom = CharalyRuntimeProfile.Provenance.DEFAULT_FALLBACK)

    /**
     * Sampling that follows from the quantisation.
     *
     * Lower bits means more quantisation noise, which shows up as small drift - the
     * model repeating itself, or losing the thread. Temperature and repetition penalty
     * both move in that direction. A file with no recognisable quantisation gets the
     * middle of the range rather than a confident guess in either direction.
     */
    private fun samplerFor(quantization: String, contextLength: Int): SamplerSettings {
        val quant = quantization.lowercase()
        val base = when {
            quant.contains("q2") || quant.contains("q3") || quant.contains("iq2") || quant.contains("iq3") ->
                SamplerSettings(temperature = 0.72f, topP = 0.92f, topK = 32, minP = 0.06f, repeatPenalty = 1.15f)
            quant.contains("q4") || quant.contains("iq4") ->
                SamplerSettings(temperature = 0.80f, topP = 0.94f, topK = 40, minP = 0.05f, repeatPenalty = 1.12f)
            quant.contains("q5") ->
                SamplerSettings(temperature = 0.85f, topP = 0.95f, topK = 40, minP = 0.05f, repeatPenalty = 1.10f)
            quant.contains("q6") || quant.contains("q8") || quant.contains("f16") || quant.contains("bf16") ->
                SamplerSettings(temperature = 0.88f, topP = 0.96f, topK = 48, minP = 0.04f, repeatPenalty = 1.08f)
            quant.contains("f32") || quant.contains("f16") ->
                SamplerSettings(temperature = 0.88f, topP = 0.96f, topK = 48, minP = 0.04f, repeatPenalty = 1.08f)
            else ->
                SamplerSettings(temperature = 0.82f, topP = 0.94f, topK = 40, minP = 0.05f, repeatPenalty = 1.12f)
        }
        // A long context needs a shorter reply: the story budget has to hold the
        // transcript as well as the scene, and a 4096-token monologue crowds out the
        // world state that makes the next turn coherent.
        val maxTokens = if (contextLength >= 16_000) 320 else if (contextLength >= 8_000) 380 else 300
        return base.copy(maxTokens = maxTokens, stopSequences = stopSequences())
    }

    /**
     * How much world context a window can hold.
     *
     * Driven by the *whole* budget rather than by a token count, because what actually
     * matters is characters: a 4k window split between transcript and world state leaves
     * room for roughly three or four memories, and asking for ten produces a prompt that
     * gets truncated from the end - silently dropping the most recent, most relevant one.
     */
    private fun contextPolicyFor(contextLength: Int): ContextPolicy = when {
        contextLength <= 4_096 -> ContextPolicy.TINY
        contextLength <= 8_192 -> ContextPolicy(
            transcriptTurns = 5,
            memories = 5,
            maxChars = 4800,
        )
        contextLength <= 16_384 -> ContextPolicy(
            transcriptTurns = 8,
            memories = 7,
            maxChars = 7000,
        )
        else -> ContextPolicy(
            transcriptTurns = 10,
            memories = 9,
            maxChars = 9000,
        )
    }

    private fun familyOf(architecture: String, displayName: String): String {
        val arch = architecture.lowercase()
        if (arch.isNotBlank()) return arch.replace('-', ' ')
        return displayName.substringBefore('-').lowercase().ifBlank { "unknown" }
    }

    /**
     * The engine protocol half of the system prompt.
     *
     * Deliberately *not* the world half. `ContextBuilder` appends what is true right now;
     * this states only how the model is expected to behave. See the class doc comment on
     * [CharalyRuntimeProfile] for why the two halves are kept apart.
     */
    fun buildSystemPrompt(modelName: String): String = buildString {
        appendLine("You are the narrative voice inside Charaly, a story world that is running without you.")
        appendLine("You are currently speaking as one character: $modelName's role in this scene.")
        appendLine()
        appendLine("WHAT YOU DO")
        appendLine(CharalyRuntimeProfile.CAPABILITY_CAN)
        appendLine()
        appendLine("WHAT YOU DO NOT DO")
        appendLine(CharalyRuntimeProfile.CAPABILITY_CANNOT)
        appendLine()
        appendLine("HOW YOU WRITE")
        appendLine("- You are inside the scene. Do not describe the scene from outside it.")
        appendLine("- Never speak for the person you are talking to, and never write their lines.")
        appendLine("- Short paragraphs. Dialogue in plain quotation marks. No markdown, no headings,")
        appendLine("  no bracketed stage directions.")
        appendLine("- A quiet moment is a legitimate moment. Do not escalate on every turn.")
        appendLine("- Never output system markers, tags or anything that is not the character's")
        appendLine("  words or the narration the scene needs.")
    }

    /**
     * The action protocol.
     *
     * ## Why it is a tag and not JSON
     *
     * A 2B model asked for JSON produces malformed JSON most of the time, and a
     * malformed structured proposal becomes either a crash or, worse, a silently
     * discarded event. An angle-bracketed tag inside prose is a format small models can
     * produce reliably, and it survives being embedded mid-sentence.
     *
     * The validator is still the authority - a proposal is a *request*, and every one of
     * them is checked before it can touch world state.
     */
    /**
     * Delegates to the parser's own constant rather than restating the protocol.
     *
     * Deliberate. A hand-written summary here would be a second source of truth, and the
     * two would drift: a model told to emit `RELATIONSHIP` when the validator only
     * accepts `relate` produces proposals that are silently rejected forever, which
     * looks like the engine ignoring the model rather than a mismatch in its own docs.
     *
     * So there is exactly one description of the protocol and it lives next to the code
     * that enforces it.
     */
    val ACTION_PROTOCOL: String
        get() = dev.charaly.runtime.director.ProposedActionParser.INSTRUCTIONS

    /**
     * The allowed action types, read from the parser.
     *
     * The list is a field on the parser's own enum, so a new action type becomes
     * documented here automatically. Hard-coding the list in a prompt string is how a
     * model ends up proposing types no validator will ever accept.
     */
    private fun actionTypeNames(): String =
        dev.charaly.runtime.director.ActionType.entries
            .filter { it != dev.charaly.runtime.director.ActionType.UNKNOWN }
            .joinToString(", ") { it.name.lowercase() }

    private fun actionInstructions(hasChatTemplate: Boolean): String =
        if (hasChatTemplate) {
            "Place any <charaly:action .../> tag on its own line after your reply. " +
                "Allowed types are: ${actionTypeNames()}. " +
                "Most turns need no tag at all - propose only when the world genuinely " +
                "should change, and never for something you have merely described."
        } else {
            "This model has no chat template, so keep replies plain and propose nothing. " +
                "The story engine records what happens from validated events instead."
        }

    /**
     * The expected reply shape.
     *
     * Derived rather than assumed: a file without a chat template is frequently a base
     * model, and asking a base model for a formatted narrative reliably produces a
     * template literal - "Sure! Here's the story:" - as its first line.
     */
    private fun outputFormat(hasChatTemplate: Boolean): String = if (hasChatTemplate) {
        "Narration in short paragraphs with the character's dialogue in plain quotes. " +
            "No preamble, no sign-off, no explanation of what you are doing."
    } else {
        "Plain dialogue only, in plain quotation marks. No narration, no preamble. " +
            "This model has no chat template."
    }

    /**
     * Stop sequences.
     *
     * `<charaly:` stops the generation right after a proposal, so the action tag never
     * gets continued by the model elaborating on its own protocol. `</charaly:` matters
     * for the same reason on the closing side.
     */
    private fun stopSequences(): List<String> = listOf(
        "<charaly:",
        "</charaly:",
        "\nUSER:",
        "\nSYSTEM:",
        "\nHUMAN:",
    )

    const val MIN_CONTEXT = 2_048

    /**
     * 32k. A file can declare more, but a phone cannot hold it: the KV cache for a
     * larger window on a 7B model is measured in gigabytes. Clamping here means the
     * budget is honest instead of aspirational.
     */
    const val MAX_CONTEXT = 32_768

    const val DEFAULT_CONTEXT = 4_096
}
