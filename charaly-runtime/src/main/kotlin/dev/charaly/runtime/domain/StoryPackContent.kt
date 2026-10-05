package dev.charaly.runtime.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * First-class *content* for a StoryPack: everything that makes a pack feel like a
 * universe instead of a database row.
 *
 * Rules for this whole file:
 *  * it is AUTHORING data. It is written by a pack author (or the pack creator)
 *    and read by the runtime;
 *  * it never becomes world state by itself. The compiled form of a
 *    [PackEventDefinition] is a typed [dev.charaly.runtime.domain.events.EventPayload],
 *    which is the only thing the [dev.charaly.runtime.engine.EventEngine] accepts;
 *  * it is pure Kotlin/JVM, so the whole authoring model is unit testable without
 *    a device.
 */

// ---------------------------------------------------------------------------
// Visual identity
// ---------------------------------------------------------------------------

/**
 * A pack's accent palette.
 *
 * Colours are stored as hex strings on purpose: the runtime module must never
 * depend on `android.graphics.Color`, so the app layer parses them. A pack tints
 * the shared Charaly brand, it never replaces it.
 */
@Serializable
data class PackTheme(
    val primaryHex: String = "#8B7BF0",
    val secondaryHex: String = "#E0659B",
    val accentHex: String = "#F2B25C",
    val inkHex: String = "#F4F1FA",
    val surfaceHex: String = "#17161D",
    val mood: String = "",
) {
    fun primary(): Long = PackColor.parse(primaryHex)
    fun secondary(): Long = PackColor.parse(secondaryHex)
    fun accent(): Long = PackColor.parse(accentHex)
    fun ink(): Long = PackColor.parse(inkHex)
    fun surface(): Long = PackColor.parse(surfaceHex)
}

/** Tiny hex parser so the runtime can reason about colours without Android. */
object PackColor {
    fun parse(hex: String, fallback: Long = 0xFF8B7BF0): Long {
        val cleaned = hex.trim().removePrefix("#")
        return runCatching {
            when (cleaned.length) {
                6 -> (0xFF000000L or cleaned.toLong(16))
                8 -> cleaned.toLong(16)
                else -> fallback
            }
        }.getOrDefault(fallback)
    }
}

/**
 * Where a piece of artwork comes from.
 *
 * Charaly never requires the network for its own UI. [GENERATED] is a
 * deterministic, locally drawn composition derived from [seed]; [LOCAL_URI] is an
 * image the user already has on the device. There is no remote url option on
 * purpose.
 */
@Serializable
data class PackArtwork(
    val kind: ArtworkKind = ArtworkKind.GENERATED,
    val seed: String = "",
    val uri: String? = null,
    val glyph: String = "",
    val caption: String = "",
) {
    companion object {
        fun generated(seed: String, glyph: String = "", caption: String = ""): PackArtwork =
            PackArtwork(kind = ArtworkKind.GENERATED, seed = seed, glyph = glyph, caption = caption)
    }
}

@Serializable
enum class ArtworkKind {
    GENERATED,
    LOCAL_URI,
}

// ---------------------------------------------------------------------------
// World content
// ---------------------------------------------------------------------------

/** A group inside a world: a school, a company, a court, a gang. */
@Serializable
data class Faction(
    val id: String,
    val name: String,
    val motto: String = "",
    val description: String = "",
    val memberCharacterIds: List<CharacterId> = emptyList(),
    val seatLocationId: LocationId? = null,
    val colorHex: String = "",
) {
    init {
        require(id.isNotBlank()) { "Faction needs an id" }
        require(name.isNotBlank()) { "Faction $id needs a name" }
    }
}

/**
 * A piece of world lore.
 *
 * Lore is *authored background*, distinct from [dev.charaly.runtime.domain.knowledge.Fact]
 * (runtime truth, per-character visibility). A secret lore entry is never given to
 * the model unless a matching fact has been discovered.
 */
@Serializable
data class WorldLoreEntry(
    val id: String,
    val title: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val relatedCharacterIds: List<CharacterId> = emptyList(),
    val relatedLocationIds: List<LocationId> = emptyList(),
    val secret: Boolean = false,
    /** 1 (background) .. 5 (core to the story). */
    val importance: Int = 2,
) {
    init {
        require(content.isNotBlank()) { "Lore $id needs content" }
        require(importance in 1..5) { "Lore importance must be 1..5, was $importance" }
    }
}

/**
 * A way of entering a pack: "First Day", "Night Patrol", ...
 *
 * A scenario selects the starting time, place, cast and the pack events that fire.
 * It is authored data compiled into scheduled events by
 * [dev.charaly.runtime.engine.EventProgram].
 */
@Serializable
data class StartingScenario(
    val id: String,
    val title: String,
    val tagline: String = "",
    val description: String = "",
    val startTime: StoryTime = StoryTime.START,
    val startLocationId: LocationId,
    val focusCharacterId: CharacterId? = null,
    val castCharacterIds: List<CharacterId> = emptyList(),
    val startActivities: Map<CharacterId, CharacterActivity> = emptyMap(),
    /** threadId -> starting stage. Overrides the pack default for this opening. */
    val threadStages: Map<String, Int> = emptyMap(),
    val startVariables: List<WorldVariable> = emptyList(),
    /** Ids of [PackEventDefinition]s that fire at the start of this scenario. */
    val eventIds: List<String> = emptyList(),
    val narrativeSeed: String = "",
    val artwork: PackArtwork = PackArtwork(),
    /** Optional hook artwork. Falls back to a generated composition. */
    val visualAsset: VisualAsset? = null,
) {
    init {
        require(id.isNotBlank()) { "Scenario needs an id" }
        require(title.isNotBlank()) { "Scenario $id needs a title" }
    }

    fun artworkAsset(): VisualAsset? = visualAsset
}

/**
 * A role the *player* can take inside a world.
 *
 * The persona is injected as the user's own identity. It never becomes a
 * CharacterRuntime: the player is not an NPC, so the event engine has nothing to
 * move and nothing to validate about them.
 */
@Serializable
data class PersonaTemplate(
    val id: String,
    val name: String,
    val tagline: String = "",
    val description: String = "",
    val rolePrompt: String = "",
    val startingLocationId: LocationId? = null,
    val suggestedCharacterIds: List<CharacterId> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "Persona needs an id" }
        require(name.isNotBlank()) { "Persona $id needs a name" }
    }
}

/** How a character is allowed to remember things. */
@Serializable
data class MemoryPolicy(
    val enabled: Boolean = true,
    val defaultImportance: Int = 3,
    val rememberDialogue: Boolean = true,
    val rememberActions: Boolean = true,
    val maxRetainedMemories: Int = 40,
) {
    init {
        require(defaultImportance in 1..5) { "defaultImportance must be 1..5" }
        require(maxRetainedMemories > 0) { "maxRetainedMemories must be positive" }
    }
}

/** Character speech style, kept separate from personality on purpose. */
@Serializable
data class SpeakingStyle(
    val tone: String = "",
    val vocabulary: String = "",
    val quirks: List<String> = emptyList(),
    val avoids: List<String> = emptyList(),
    val exampleOpeners: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = tone.isBlank() && vocabulary.isBlank() && quirks.isEmpty() &&
            avoids.isEmpty() && exampleOpeners.isEmpty()
}

/**
 * A pack-scoped visual theme, so one world can never tint another's cards.
 * Accent values are per pack instance; isolation is structural, not conventional.
 */
@Serializable
data class PackIdentity(
    val tagline: String = "",
    val genres: List<String> = emptyList(),
    val contentNotes: List<String> = emptyList(),
    val era: String = "",
    val tone: String = "",
    val theme: PackTheme = PackTheme(),
    val cover: PackArtwork = PackArtwork(),
    val fandomNotice: String = "",
    val isFeatured: Boolean = false,
    val isDemo: Boolean = false,
) {
    val primaryGenre: String get() = genres.firstOrNull().orEmpty()
}