package dev.charaly.runtime.model

import dev.charaly.runtime.model.gguf.CharalyCompatibility
import dev.charaly.runtime.model.gguf.GgufCompatibility
import dev.charaly.runtime.model.gguf.GgufMetadata

/**
 * Derives a complete, ready-to-use [ModelPackage] from a model's own GGUF header.
 *
 * ## Why this is the automatic step the installer runs
 *
 * Item 23 of the product spec says the user must not hand-assemble a model. That is only
 * true if everything needed to *talk* to a model is derivable from the file, and it
 * nearly all is:
 *
 * | what                        | where it comes from                          |
 * |-----------------------------|---------------------------------------------|
 * | architecture                | `general.architecture`                       |
 * | context budget              | `<arch>.context_length`                      |
 * | whether a system role works | the presence of a system token in the template |
 * | stop sequences              | derived from the template's own end-of-turn marker |
 * | prose register              | derived from parameter count                 |
 * | how much context is safe    | derived from parameter count and device RAM  |
 *
 * What cannot be derived - and what this therefore refuses to invent - is *how good the
 * model's prose is*. A 1B model's output is not fixed by making its temperature
 * conservative. So the suitability tier comes from a size band, and it is labelled
 * "Estimated" in the UI rather than presented as a measurement.
 *
 * ## The one honest escape hatch
 *
 * A user who finds a model's defaults wrong can override every sampler field under
 * Advanced. That is an override, not a configuration step: nothing here *requires* the
 * user to do anything.
 */
object ModelPresetLibrary {

    /**
     * Model size bands.
     *
     * The boundaries are where prompt-following changes qualitatively on small models,
     * not round numbers chosen for presentation.
     */
    enum class SizeBand(val label: String, val maxParameters: Long) {
        TINY("Very small", 2_000_000_000L),
        SMALL("Small", 4_500_000_000L),
        MEDIUM("Medium", 10_000_000_000L),
        LARGE("Large", Long.MAX_VALUE),
        ;

        companion object {
            /** The band for a parameter count, or SMALL when it is unknown. */
            fun of(parameterCount: Long): SizeBand = when {
                parameterCount <= 0L -> SMALL
                else -> entries.firstOrNull { parameterCount <= it.maxParameters } ?: LARGE
            }
        }
    }

    /**
     * Builds the package for a header.
     *
     * @param metadata the read GGUF header
     * @param displayName what to call the model in the UI; falls back to the file's own name
     * @param absolutePath where the file is on this device
     * @param fileSizeBytes observed size, used for the RAM estimate
     * @param availableRamBytes what the device reports free, used to size the context budget
     * @param sha256 observed hash, when one was computed
     */
    fun build(
        metadata: GgufMetadata,
        displayName: String = metadata.name,
        absolutePath: String,
        fileSizeBytes: Long = 0L,
        availableRamBytes: Long = 0L,
        sha256: String = "",
        modelId: String = absolutePath.substringAfterLast('/'),
    ): ModelPackage {
        val verdict = GgufCompatibility.classify(metadata, fileSizeBytes, availableRamBytes)
        val band = SizeBand.of(metadata.parameterCount)

        val artifact = ModelArtifact(
            modelId = modelId,
            displayName = displayName.ifBlank { metadata.name.ifBlank { modelId } },
            absolutePath = absolutePath,
            sizeBytes = fileSizeBytes,
            architecture = metadata.architecture,
            quantization = metadata.quantizationLabel.ifBlank { metadata.quantization },
            contextLength = metadata.contextLength,
            parameterCount = metadata.parameterCount,
            sha256 = sha256,
            chatTemplate = metadata.chatTemplate,
            verified = verdict.isReady,
        )

        val runtime = runtimeProfile(metadata, band, availableRamBytes, verdict)
        val prompt = promptProfile(metadata, band)
        val compatibility = CompatibilityProfile(
            architecture = verdict.architecture,
            engineVerdict = EngineVerdict.of(verdict.architecture),
            minimumRamBytes = verdict.estimatedRamBytes,
            recommendedRamBytes = verdict.estimatedRamBytes,
            supportsSystemRole = prompt.supportsSystemRole,
        )

        return ModelPackage(
            artifact = artifact,
            compatibility = compatibility,
            runtime = runtime,
            prompt = prompt,
            output = outputProtocol(metadata),
        )
    }

    /**
     * Sampling and context budget.
     *
     * The rules encoded here are the ones that make small models playable:
     *
     *  * **Context is capped by the file**, then again by the device. Asking for more
     *    context than a model was trained for produces truncation, not quality.
     *  * **A small model gets a short context.** A 1B model with 8k of transcript in the
     *    prompt loses the instruction it was given. Truncating the transcript is better
     *    than letting the model attend itself into incoherence.
     *  * **Temperature falls with size.** Small models are already noisy; adding heat
     *    turns them into random. Large models are coherent enough to use it.
     *  * **Stop sequences come from the template**, so a model ends its turn where its
     *    own format says a turn ends rather than where Charaly guessed.
     */
    internal fun runtimeProfile(
        metadata: GgufMetadata,
        band: SizeBand,
        availableRamBytes: Long,
        verdict: dev.charaly.runtime.model.gguf.CompatibilityVerdict,
    ): ModelRuntimeProfile {
        val fileContext = metadata.contextLength
        val context = contextBudget(band, fileContext, availableRamBytes)

        val temperature = when (band) {
            SizeBand.TINY -> 0.70f
            SizeBand.SMALL -> 0.80f
            SizeBand.MEDIUM -> 0.88f
            SizeBand.LARGE -> 0.92f
        }
        val topP = when (band) {
            SizeBand.TINY -> 0.90f
            SizeBand.SMALL -> 0.93f
            SizeBand.MEDIUM -> 0.95f
            SizeBand.LARGE -> 0.96f
        }

        return ModelRuntimeProfile(
            recommendedContext = context,
            // Output length scales with size for the same reason context does: a tiny
            // model given 512 tokens produces 512 tokens of rambling.
            maxOutputTokens = when (band) {
                SizeBand.TINY -> 220
                SizeBand.SMALL -> 300
                SizeBand.MEDIUM -> 400
                SizeBand.LARGE -> 512
            },
            temperature = temperature,
            topP = topP,
            topK = when (band) {
                SizeBand.TINY -> 30
                SizeBand.SMALL -> 40
                SizeBand.MEDIUM -> 50
                SizeBand.LARGE -> 60
            },
            minP = 0.05f,
            repetitionPenalty = if (band == SizeBand.TINY) 1.15f else 1.10f,
            stopSequences = stopSequences(metadata),
            transcriptTurns = when (band) {
                SizeBand.TINY -> 3
                SizeBand.SMALL -> 5
                SizeBand.MEDIUM -> 7
                SizeBand.LARGE -> 9
            },
            memoryCount = when (band) {
                SizeBand.TINY -> 3
                SizeBand.SMALL -> 5
                SizeBand.MEDIUM -> 7
                SizeBand.LARGE -> 9
            },
        )
    }

    /**
     * The context window Charaly will actually request.
     *
     * Three ceilings, applied in order: what the file declares, what a model of this size
     * can usefully hold, and what this device's memory can pay for in KV cache.
     */
    internal fun contextBudget(band: SizeBand, fileContext: Int, availableRamBytes: Long): Int {
        val useful = when (band) {
            SizeBand.TINY -> 2_048
            SizeBand.SMALL -> 4_096
            SizeBand.MEDIUM -> 8_192
            SizeBand.LARGE -> 8_192
        }
        val byFile = if (fileContext > 0) minOf(fileContext, useful) else useful
        // The device ceiling mirrors ModelBindingResolver.DEVICE_CONTEXT_CEILING: a phone
        // cannot hold a 128k KV cache, and asking for one does not make the model better.
        val byDevice = if (availableRamBytes > 0L) {
            // Rough: 2 GiB of RAM buys about 8k tokens of KV cache for a mid-size model.
            val affordable = if (availableRamBytes >= 6L * 1024 * 1024 * 1024) 8_192 else 4_096
            minOf(byFile, affordable)
        } else {
            byFile
        }
        return byDevice.coerceIn(512, ModelBindingResolver.DEVICE_CONTEXT_CEILING)
    }

    /**
     * End-of-turn markers taken from the model's own chat template.
     *
     * ## Why this matters more than it looks
     *
     * Without a stop sequence a model narrates the player's next line too, and the story
     * starts writing itself - the user sees their own dialogue appearing in the reply.
     * The markers differ per template family (ChatML, Llama-3 instruct, Gemma), so
     * hardcoding one set would break the others. Reading the template means a new format
     * family works without a code change.
     *
     * The recognised set is deliberately small and conservative: a false stop sequence
     * truncates a reply mid-sentence, which is a worse failure than a slightly long one.
     */
    internal fun stopSequences(metadata: GgufMetadata): List<String> {
        val template = metadata.chatTemplate
        if (template.isBlank()) return defaultStopSequences()
        val lower = template.lowercase()
        val found = mutableListOf<String>()

        // ChatML: <|im_end|>, <|endoftext|>
        if (lower.contains("<|im_end|>") || lower.contains("im_end")) {
            found += "<|im_end|>"
            found += "<|end_of_turn>"
            found += "<end_of_turn>"
            found += "<|eot_id|>"
        }
        // Llama 3 instruct: <|eot_id|>, <|start_header_id|>
        if (lower.contains("eot_id") || lower.contains("start_header_id")) {
            found += "<|eot_id|>"
            found += "<|eom_id|>"
        }
        // Gemma: <end_of_turn>
        if (lower.contains("end_of_turn")) {
            found += "<end_of_turn>"
        }
        // Llama 2 instruct: [/INST]
        if (lower.contains("[/inst]") || lower.contains("<<sys>>")) {
            found += "[/INST]"
        }

        // ChatML's <|im_end|> is the common denominator for anything using im_* tokens.
        if (lower.contains("im_start") && lower.contains("im_end")) {
            found += "<|im_end|>"
        }

        val distinct = found.distinct()
        // A model with a template we recognise gets the template's markers. One with no
        // recognised template gets the safe default rather than an empty list, because an
        // empty stop list means the runaway-reply failure.
        return if (distinct.isEmpty()) defaultStopSequences() else distinct
    }

    /**
     * End-of-turn markers for a model with no usable template.
     *
     * llama.cpp adds the EOS token itself, so this is a belt-and-braces list. Kept short
     * on purpose: a long speculative list risks cutting a reply at a substring that
     * legitimately appears inside dialogue.
     */
    internal fun defaultStopSequences(): List<String> = listOf(
        "\nUser:",
        "\nHuman:",
        "<|im_end|>",
        "<end_of_turn>",
    )

    /**
     * The prompt scaffolding for one model.
     *
     * [supportsSystemRole] is the important field and it comes from the template, not
     * from a preference: a template that cannot emit a leading system turn will drop
     * Charaly's most important instructions, so for those models the system prompt is
     * folded into the first user turn by the template itself.
     */
    internal fun promptProfile(metadata: GgufMetadata, band: SizeBand): PromptProfile =
        PromptProfile(
            systemPrompt = systemPromptFor(band),
            characterTemplate = characterTemplate(),
            worldTemplate = worldTemplate(),
            memoryTemplate = memoryTemplate(),
            sceneTemplate = sceneTemplate(band),
            actionProtocolPrompt = PromptProfile.actionProtocol(),
            supportsSystemRole = metadata.supportsSystemRole,
        )

    /**
     * The engine-protocol half of the system prompt.
     *
     * Deliberately contains **no world facts**. Canon and world state are added by
     * [dev.charaly.runtime.context.ContextBuilder] at request time, from state the engine
     * owns. A model cannot be handed canon here because the prompt is static and canon is
     * per-story.
     *
     * The contract from the spec is stated explicitly, because a small model will follow a
     * prohibition it can see far more reliably than one it has to infer:
     *
     * ```
     * You may: narrate, speak in dialogue, react to the scene, keep character voice.
     * You may not: change world state, declare canon, reveal what a character does not
     *            know, or act on the world's rules directly. Propose instead.
     * ```
     */
    internal fun systemPromptFor(band: SizeBand): String = buildString {
        appendLine("You are the narrative model inside Charaly, playing a character inside a living story.")
        appendLine()
        appendLine("You write only what this character says, does and notices.")
        appendLine()
        appendLine("You can: narrate, speak in dialogue, react to the scene, keep the character's voice.")
        appendLine(
            "You cannot: change the world, declare what is canon, reveal anything this character " +
                "does not know, or act on the world's rules directly.",
        )
        appendLine("If something should change, propose it and let the world decide whether it happens.")
        appendLine()
        when (band) {
            SizeBand.TINY -> appendLine("Keep replies short: two or three sentences.")
            SizeBand.SMALL -> appendLine("Keep replies brief.")
            else -> Unit
        }
        appendLine("Never use markdown, headings, bullet points or bracketed stage directions.")
        appendLine("Write plain prose: dialogue in plain quotation marks, narration around it.")
    }

    /**
     * How a character is introduced.
     *
     * Kept to a few lines because the [dev.charaly.runtime.domain.CharacterDefinition]
     * already carries the traits, and a prompt that restates the definition at length is
     * mostly tokens spent on material the engine will change.
     */
    internal fun characterTemplate(): String =
        "You are playing: {name}. {tagline} Voice: {voice}."

    /** How world state is framed, as provided by ContextBuilder. */
    internal fun worldTemplate(): String =
        "Where you are: {location}. What is true here right now: {facts}"

    /** How retrieved memory is framed, as provided by ContextBuilder. */
    internal fun memoryTemplate(): String =
        "What you remember: {memories}"

    /**
     * How the scene is framed.
     *
     * Small models get fewer named parts, because each additional labelled slot is an
     * instruction they are more likely to conflate.
     */
    internal fun sceneTemplate(band: SizeBand): String = when (band) {
        SizeBand.TINY -> "Now: {location}. With you: {present}."
        SizeBand.SMALL -> "Now: {location}, {time}. With you: {present}. You are here because: {objective}"
        else ->
            "Now: {location}, {time}. With you: {present}. You are here because: {objective}\n" +
                "How you feel about them: {relationships}"
    }

    /**
     * What this model is allowed to emit.
     *
     * `proposedAction` is true whenever the model has a chat template *and* is not in the
     * smallest band: a tiny model asked to emit structured tags emits malformed ones, and
     * a malformed proposal is worse than no proposal because it consumes a turn to
     * discard.
     */
    internal fun outputProtocol(metadata: GgufMetadata): OutputProtocol = OutputProtocol(
        dialogue = true,
        narration = true,
        proposedAction = metadata.supportsSystemRole && SizeBand.of(metadata.parameterCount) != SizeBand.TINY,
        // Scene control is not exposed at all: letting a model end a scene means it can
        // end the story's structure. The engine ends scenes.
        sceneControl = false,
    )

    /**
     * The user-facing suitability label.
     *
     * Derived from size band, and returned as an explicit "Estimated" so the UI can show
     * it as an estimate. See the class doc for why this is not a benchmark.
     */
    fun suitabilityLabel(metadata: GgufMetadata): String = when (SizeBand.of(metadata.parameterCount)) {
        SizeBand.TINY -> "Estimated: fast, short memory"
        SizeBand.SMALL -> "Estimated: balanced"
        SizeBand.MEDIUM -> "Estimated: good prose"
        SizeBand.LARGE -> "Estimated: strongest prose"
    }

    /**
     * Converts a package into the [ModelProfile] the rest of the engine binds.
     *
     * Named `bundle` rather than `package`, which is a keyword.
     */
    fun toProfile(bundle: ModelPackage, id: String = bundle.modelId): ModelProfile = ModelProfile(
        id = id,
        name = bundle.displayName,
        description = bundle.artifact.techSummary,
        sampler = SamplerSettings(
            temperature = bundle.runtime.temperature,
            topP = bundle.runtime.topP,
            topK = bundle.runtime.topK,
            minP = bundle.runtime.minP,
            repeatPenalty = bundle.runtime.repetitionPenalty,
            maxTokens = bundle.runtime.maxOutputTokens,
            stopSequences = bundle.runtime.stopSequences,
        ),
        contextPolicy = ContextPolicy(
            transcriptTurns = bundle.runtime.transcriptTurns,
            memories = bundle.runtime.memoryCount,
            maxChars = contextCharBudget(bundle.runtime.recommendedContext),
        ),
        narrativeStyle = NarrativeStyle.NEUTRAL,
        thinkingBehavior = ThinkingBehavior.STRIP,
        isBuiltIn = false,
    )

    /**
     * Rough character ceiling for a prompt, from the token budget.
     *
     * Four characters per token is the usual English ratio; the divisor of two leaves
     * headroom for the JSON-ish protocol, for names, and for languages where a character
     * is a token. A ceiling that is slightly too low costs a dropped line; one that is too
     * high costs a truncated one, so the conservative direction is deliberate.
     */
    internal fun contextCharBudget(contextTokens: Int): Int =
        (contextTokens * CHARS_PER_TOKEN / 2).coerceIn(1_500, 12_000)

    /** Observed English characters per token. */
    const val CHARS_PER_TOKEN = 4

    /**
     * Whether a package is one a story can actually bind to.
     *
     * Redundant with [ModelPackage.isReadyForCharaly] on purpose: callers holding only a
     * verdict should not need the whole package.
     */
    fun verdictFor(metadata: GgufMetadata, fileSizeBytes: Long, availableRamBytes: Long): CharalyCompatibility =
        GgufCompatibility.classify(metadata, fileSizeBytes, availableRamBytes).compatibility
}
