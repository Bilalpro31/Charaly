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
) {
    init {
        require(name.isNotBlank()) { "Character name must not be blank ($id)" }
    }

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
        if (goals.isNotEmpty()) appendLine("Goals: ${goals.joinToString("; ")}")
    }
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
) {
    init {
        require(name.isNotBlank()) { "Location name must not be blank ($id)" }
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
