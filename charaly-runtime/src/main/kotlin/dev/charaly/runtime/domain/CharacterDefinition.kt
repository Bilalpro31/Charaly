package dev.charaly.runtime.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * STATIC identity of a character.
 *
 * This is the "who they are" record (SillyTavern style character card territory).
 * It contains no live state: no location, no relationships, no knowledge.
 * All of that lives in [CharacterRuntime], [dev.charaly.runtime.domain.Relationship]
 * and [dev.charaly.runtime.domain.knowledge.KnowledgeStore].
 */
@Serializable
data class CharacterDefinition(
    val id: CharacterId,
    val name: String,
    val description: String = "",
    val personality: String = "",
    val background: String = "",
    val goals: List<String> = emptyList(),
    val persona: String = "",
    val greeting: String = "",
    val exampleDialogue: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    /** Optional SillyTavern `character_book` lore entries mapped into world facts. */
    val loreEntries: List<LoreEntry> = emptyList(),
    val avatarUri: String? = null,
    // ---- first-class content --------------------------------------------
    /** Short label under the name on a character card. */
    val tagline: String = "",
    /** What this character looks like / does, in one line. */
    val shortDescription: String = "",
    val identityRole: String = "",
    val fears: List<String> = emptyList(),
    val speakingStyle: SpeakingStyle = SpeakingStyle(),
    /**
     * Facts this character must NOT know unless the world discloses them. Enforced
     * as prompt text by the ContextBuilder; the real gate is the KnowledgeStore.
     */
    val knowledgeBoundaries: List<String> = emptyList(),
    val startingLocationId: LocationId? = null,
    val startingActivity: CharacterActivity? = null,
    /**
     * This character's daily routine.
     *
     * A routine is what makes a character a *resident* of the world rather than a
     * line in a cast list: the engine moves Andre to his shop at 09:00 and sends him
     * home at 19:00 whether or not the player ever speaks to him. An empty routine
     * means "not scheduled" - the story places this character explicitly instead.
     */
    val routine: Routine = Routine(),
    /**
     * Whether this is core cast, a meaningful recurring NPC, or background crowd.
     * Background characters deliberately carry no individual schedule, knowledge or
     * memory; see [CharacterRole].
     */
    val storyRole: CharacterRole = CharacterRole.PROTAGONIST,
    /** Author instructions layered under the world rules in the system prompt. */
    val systemInstructions: String = "",
    val memoryPolicy: MemoryPolicy = MemoryPolicy(),
    val factionId: String = "",
    val artwork: PackArtwork = PackArtwork(),
    /**
     * This character's own artwork, addressed by purpose.
     *
     * Distinct from [artwork], which is the older single-seed shape kept for
     * compatibility. A character has a *portrait* and a *thumbnail*, and they are
     * different sizes for good reason - a carousel tile must not be handed a
     * full-resolution face. Empty is legitimate and means "use the generated fallback".
     */
    val visualAssets: List<VisualAsset> = emptyList(),
    val accentHex: String = "",
) {
    init {
        require(name.isNotBlank()) { "Character name must not be blank ($id)" }
    }

    /** The label shown on a character card. */
    val displayName: String get() = name

    /** The one-line blurb used by cards and pickers. */
    fun summaryLine(): String = when {
        tagline.isNotBlank() -> tagline
        shortDescription.isNotBlank() -> shortDescription
        else -> description.lineSequence().firstOrNull()?.trim().orEmpty()
    }

    fun accentLong(): Long = if (accentHex.isNotBlank()) PackColor.parse(accentHex) else 0L

    /** Prompt-friendly identity block. Deterministic ordering. */
    fun identityBlock(): String = buildString {
        appendLine("Name: $name")
        if (description.isNotBlank()) appendLine("Appearance/Role: ${description.trim()}")
        if (background.isNotBlank()) appendLine("Background: ${background.trim()}")
        if (persona.isNotBlank()) appendLine("Persona: ${persona.trim()}")
    }

    /** Everything the LLM is allowed to be told about *who this character is*. */
    fun personaBlock(): String = buildString {
        append(identityBlock())
        if (personality.isNotBlank()) appendLine("Personality: ${personality.trim()}")
        if (identityRole.isNotBlank()) appendLine("Role: ${identityRole.trim()}")
        if (goals.isNotEmpty()) appendLine("Goals: ${goals.joinToString("; ")}")
        if (fears.isNotEmpty()) appendLine("Fears: ${fears.joinToString("; ")}")
        if (!speakingStyle.isEmpty) append(speakingStyle.promptBlock())
        // The declared knowledge boundaries are part of the character, not metadata
        // about them. Every pack author writes "he knows nothing about the Miraculous"
        // and it has to actually reach the model, because a negative constraint the
        // model never sees is not a constraint - and a pack full of declared secrets
        // that were silently dropped to the floor is how a small model starts inventing
        // them.
        if (knowledgeBoundaries.isNotEmpty()) {
            appendLine("Knows nothing about: ${knowledgeBoundaries.joinToString("; ")}")
        }
    }

    /** Voice rules as prompt text. Only the author decides what these are. */
    fun voicePromptBlock(): String = speakingStyle.promptBlock()
}

/** Internal helper: a [SpeakingStyle] rendered for a prompt. */
fun SpeakingStyle.promptBlock(): String = buildString {
    if (tone.isNotBlank()) appendLine("Tone of voice: ${tone.trim()}")
    if (vocabulary.isNotBlank()) appendLine("Vocabulary: ${vocabulary.trim()}")
    if (quirks.isNotEmpty()) appendLine("Habits: ${quirks.joinToString("; ")}")
    if (avoids.isNotEmpty()) appendLine("Never: ${avoids.joinToString("; ")}")
}

/**
 * A SillyTavern character-book entry, preserved (not executed) so that lore
 * imported from Character Card v2 keeps working inside Charaly.
 */
@Serializable
data class LoreEntry(
    val id: String,
    val keys: List<String> = emptyList(),
    val content: String = "",
    val comment: String = "",
    val constant: Boolean = false,
    val enabled: Boolean = true,
)

/**
 * A place in the story world. Static definition; occupancy lives in
 * [CharacterRuntime.locationId].
 */
@Serializable
data class Location(
    val id: LocationId,
    val name: String,
    val description: String = "",
    val tags: List<String> = emptyList(),
    val isInterior: Boolean = true,
    /** Explicit adjacency keeps movement validation deterministic. */
    val connections: List<LocationId> = emptyList(),
    // ---- first-class content --------------------------------------------
    /** One line used on location cards. */
    val summaryLine: String = "",
    /** Standing rules the world enforces here ("no phones after midnight"). */
    val rules: List<String> = emptyList(),
    val lore: String = "",
    /** Who starts here, as declared by the pack. */
    val startingOccupants: List<CharacterId> = emptyList(),
    val artwork: PackArtwork = PackArtwork(),
    /**
     * This location's own artwork, addressed by purpose.
     *
     * A location carries both a wide image and a small thumbnail, because the place tile
     * in a carousel and the hero behind the scene header want very different crops of
     * the same place.
     */
    val visualAssets: List<VisualAsset> = emptyList(),
    val accentHex: String = "",
) {
    init {
        require(name.isNotBlank()) { "Location name must not be blank ($id)" }
    }

    fun accentLong(): Long = if (accentHex.isNotBlank()) PackColor.parse(accentHex) else 0L

    /** The blurb a card shows, falling back to the first description sentence. */
    fun blurb(): String = when {
        summaryLine.isNotBlank() -> summaryLine
        else -> description.lineSequence().firstOrNull()?.trim().orEmpty()
    }
}

/**
 * A named world variable (a flag, a count, a mood of the world).
 * Typed on purpose: world state must not degrade into `Map<String, Any>`.
 */
@Serializable
data class WorldVariable(
    val key: String,
    @SerialName("type") val type: WorldVariableType = WorldVariableType.TEXT,
    val value: String = "",
    val description: String = "",
) {
    init {
        require(key.isNotBlank()) { "WorldVariable key must not be blank" }
    }

    fun asLong(): Long? = value.trim().toLongOrNull()
    fun asInt(): Int? = value.trim().toIntOrNull()
    fun asBoolean(): Boolean? = value.trim().lowercase().let {
        when (it) {
            "true", "yes", "1", "on" -> true
            "false", "no", "0", "off" -> false
            else -> null
        }
    }
}

@Serializable
enum class WorldVariableType {
    TEXT,
    NUMBER,
    BOOLEAN,
}
