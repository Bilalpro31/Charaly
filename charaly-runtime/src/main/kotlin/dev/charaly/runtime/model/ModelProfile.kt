package dev.charaly.runtime.model

import dev.charaly.runtime.domain.StoryPackId
import kotlinx.serialization.Serializable

/**
 * Sampler settings, in ONE place.
 *
 * A StoryInstance stores a *resolved* copy of these values (see
 * [dev.charaly.runtime.domain.StoryInstance.modelBinding]) so that changing a
 * global default later can never silently change an existing story's behaviour.
 * Reproducibility is the point of copying them into the instance.
 */
@Serializable
data class SamplerSettings(
    val temperature: Float = 0.85f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.1f,
    val maxTokens: Int = 320,
    val seed: Long = -1L,
    val stopSequences: List<String> = emptyList(),
) {
    init {
        require(temperature in 0f..2f) { "temperature must be 0..2, was $temperature" }
        require(topP in 0f..1f) { "topP must be 0..1, was $topP" }
        require(topK >= 0) { "topK must not be negative" }
        require(minP in 0f..1f) { "minP must be 0..1, was $minP" }
        require(repeatPenalty in 0.5f..2f) { "repeatPenalty must be 0.5..2, was $repeatPenalty" }
        require(maxTokens > 0) { "maxTokens must be positive" }
    }

    /** What a normal user sees under "Sampling". */
    val temperament: SamplingTemperament
        get() = when {
            temperature >= 0.95f -> SamplingTemperament.CREATIVE
            temperature <= 0.6f -> SamplingTemperament.FOCUSED
            else -> SamplingTemperament.BALANCED
        }

    fun withTemperament(temperament: SamplingTemperament): SamplerSettings = when (temperament) {
        SamplingTemperament.CREATIVE -> copy(temperature = 1.05f, topP = 0.97f, minP = 0.02f, topK = 80)
        SamplingTemperament.BALANCED -> copy(temperature = 0.85f, topP = 0.95f, minP = 0.05f, topK = 40)
        SamplingTemperament.FOCUSED -> copy(temperature = 0.55f, topP = 0.9f, minP = 0.08f, topK = 25)
    }
}

@Serializable
enum class SamplingTemperament(val label: String) {
    CREATIVE("Creative"),
    BALANCED("Balanced"),
    FOCUSED("Focused"),
}

/**
 * How much world context is allowed into the prompt.
 *
 * Small mobile models drown in context. Keeping the budget explicit (instead of
 * "add everything") is what makes a story playable on a 4B model.
 */
@Serializable
data class ContextPolicy(
    val transcriptTurns: Int = 6,
    val memories: Int = 6,
    val maxChars: Int = 6000,
    val includeRelationships: Boolean = true,
    val includeWorldFacts: Boolean = true,
    val includeLore: Boolean = true,
) {
    init {
        require(transcriptTurns >= 0) { "transcriptTurns must not be negative" }
        require(memories >= 0) { "memories must not be negative" }
        require(maxChars > 0) { "maxChars must be positive" }
    }

    companion object {
        val TINY = ContextPolicy(transcriptTurns = 2, memories = 2, maxChars = 2200)
        val STANDARD = ContextPolicy()
        val GENEROUS = ContextPolicy(transcriptTurns = 12, memories = 10, maxChars = 16000)
    }
}

/** The prose register a profile asks for. Never a world-state instruction. */
@Serializable
enum class NarrativeStyle(val label: String, val guidance: String) {
    CINEMATIC("Cinematic", "Vivid, sensory prose. Show the place, the light, the small physical details."),
    INTIMATE("Intimate", "Close, warm and personal. Small talk carries the weight."),
    PLAYFUL("Playful", "Quick, witty, a little chaotic. Comic timing is allowed."),
    GRITTY("Gritty", "Tired, physical, rain in the air. No clean endings."),
    MYSTERY("Mystery", "Withheld and clue-driven. Never confirm what the reader cannot know yet."),
    NEUTRAL("Neutral", "Plain, readable prose that gets out of the way of the dialogue."),
}

/**
 * Reasoning models emit `<think>` blocks. A profile has to say what happens to
 * them, otherwise they leak into the transcript as narration.
 */
@Serializable
enum class ThinkingBehavior(val label: String) {
    NONE("None"),
    STRIP("Strip reasoning from the output"),
    ALLOW("Allow reasoning in the transcript"),
}

/**
 * A tuned runtime profile: how to talk to one model in one context.
 *
 * A profile is deliberately *not* story data. It knows nothing about Marinette or
 * about Paris; it knows how this particular GGUF file likes to be prompted. A
 * StoryPack points at a default profile id, the user picks another per story, and
 * the resolved values are copied into the StoryInstance.
 */
@Serializable
data class ModelProfile(
    val id: String,
    val name: String,
    val description: String = "",
    val sampler: SamplerSettings = SamplerSettings(),
    val contextPolicy: ContextPolicy = ContextPolicy(),
    val narrativeStyle: NarrativeStyle = NarrativeStyle.NEUTRAL,
    val responseFormat: String = "",
    val systemPromptPreamble: String = "",
    val actionTagInstructions: String = "",
    val chatTemplate: String = "",
    val thinkingBehavior: ThinkingBehavior = ThinkingBehavior.STRIP,
    /** Built-in profiles ship with the app; user profiles are stored alongside models. */
    val isBuiltIn: Boolean = false,
    /** Null for a global profile, set for a pack-specific override. */
    val storyPackId: StoryPackId? = null,
) {
    init {
        require(id.isNotBlank()) { "ModelProfile needs an id" }
        require(name.isNotBlank()) { "ModelProfile $id needs a name" }
    }

    val summaryLine: String
        get() = description.ifBlank { "${sampler.temperament.label} · ${narrativeStyle.label}" }
}

/**
 * The profiles Charaly ships with.
 *
 * The point of these presets is that a user should not have to hand-tune samplers
 * to get a usable roleplay out of a model.
 */
object ModelProfileLibrary {

    const val BALANCED = "profile-balanced"
    const val CREATIVE = "profile-creative"
    const val FOCUSED = "profile-focused"
    const val CINEMATIC = "profile-cinematic"
    const val TINY_CONTEXT = "profile-tiny"

    val all: List<ModelProfile> = listOf(
        ModelProfile(
            id = BALANCED,
            name = "Roleplay Balanced",
            description = "Warm, readable dialogue with room to breathe. The default for most stories.",
            sampler = SamplerSettings(temperature = 0.85f, topP = 0.95f, topK = 40, minP = 0.05f, repeatPenalty = 1.1f),
            contextPolicy = ContextPolicy(),
            narrativeStyle = NarrativeStyle.NEUTRAL,
            responseFormat = "Short paragraphs. Dialogue in plain quotes. No markdown, no headings, no stage directions in brackets.",
            systemPromptPreamble = "Write as a person inside the scene, not as a narrator describing a person.",
            isBuiltIn = true,
        ),
        ModelProfile(
            id = CREATIVE,
            name = "Roleplay Creative",
            description = "Looser and more surprising. Great for banter and improvisation.",
            sampler = SamplerSettings(temperature = 1.05f, topP = 0.97f, topK = 80, minP = 0.02f, repeatPenalty = 1.08f),
            contextPolicy = ContextPolicy(transcriptTurns = 6, memories = 6, maxChars = 6000),
            narrativeStyle = NarrativeStyle.PLAYFUL,
            responseFormat = "Short paragraphs, dialogue in plain quotes. Vivid but never purple.",
            isBuiltIn = true,
        ),
        ModelProfile(
            id = FOCUSED,
            name = "Roleplay Focused",
            description = "Tighter and more literal. Better for small models and heavy lore.",
            sampler = SamplerSettings(temperature = 0.55f, topP = 0.9f, topK = 25, minP = 0.08f, repeatPenalty = 1.15f),
            contextPolicy = ContextPolicy(transcriptTurns = 5, memories = 5, maxChars = 4800),
            narrativeStyle = NarrativeStyle.INTIMATE,
            responseFormat = "Two or three sentences. Dialogue in plain quotes.",
            isBuiltIn = true,
        ),
        ModelProfile(
            id = CINEMATIC,
            name = "Cinematic",
            description = "Scene-first prose with sensory detail between the lines.",
            sampler = SamplerSettings(temperature = 0.9f, topP = 0.94f, topK = 50, minP = 0.04f, repeatPenalty = 1.12f, maxTokens = 420),
            contextPolicy = ContextPolicy(transcriptTurns = 6, memories = 8, maxChars = 7000),
            narrativeStyle = NarrativeStyle.CINEMATIC,
            responseFormat = "One short paragraph of narration, then dialogue in plain quotes.",
            isBuiltIn = true,
        ),
        ModelProfile(
            id = TINY_CONTEXT,
            name = "Tiny Context",
            description = "For 1-3B models and small context windows. Keeps the prompt short on purpose.",
            sampler = SamplerSettings(temperature = 0.8f, topP = 0.92f, topK = 30, minP = 0.06f, maxTokens = 220),
            contextPolicy = ContextPolicy.TINY,
            narrativeStyle = NarrativeStyle.INTIMATE,
            responseFormat = "One or two sentences of dialogue only. No narration.",
            isBuiltIn = true,
        ),
    )

    fun byId(id: String): ModelProfile? = all.firstOrNull { it.id == id }

    /** Never returns null: an unknown id must not break a story. */
    fun resolve(id: String?, fallback: ModelProfile = all.first()): ModelProfile =
        byId(id ?: "") ?: fallback

    val default: ModelProfile get() = resolve(BALANCED)
}