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
    val primaryHex: String = CharalySurface.INK_PRIMARY,
    val secondaryHex: String = CharalySurface.INK_SECONDARY,
    val accentHex: String = CharalySurface.INK_MUTED,
    val inkHex: String = CharalySurface.INK_PRIMARY,
    /**
     * The pack's own base surface, for cards that sit inside a pack-themed region.
     *
     * Near-black by convention, never a saturated colour: a pack's surface is a stage,
     * and the accent is what is on it.
     */
    val surfaceHex: String = CharalySurface.RAISED,
    val mood: String = "",
    /**
     * Optional gradient stops for hero artwork and primary actions.
     *
     * ## Why a gradient is declared, not derived
     *
     * A gradient computed from the accent would look the same on every pack, which is
     * exactly the "random gradient behind every card" look this design system is trying
     * to avoid. Declaring the stops means a pack's identity is an authoring decision, and
     * a pack author can make the Miraculous hero run red-to-black rather than
     * red-to-violet by accident.
     *
     * Empty means "no gradient here". That is a valid and common answer: most surfaces in
     * Charaly are flat black, and a gradient is a highlight, not a texture.
     */
    val gradientStartHex: String = "",
    val gradientEndHex: String = "",
    /**
     * How the hero treats its artwork.
     *
     * See [HeroTreatment]. Determines whether the pack wants a full-bleed image, a
     * gradient wash behind type, or nothing decorative at all.
     */
    val heroTreatment: String = HeroTreatment.WASH.name,
) {
    fun primary(): Long = PackColor.parse(primaryHex)
    fun secondary(): Long = PackColor.parse(secondaryHex)
    fun accent(): Long = PackColor.parse(accentHex)
    fun ink(): Long = PackColor.parse(inkHex)
    fun surface(): Long = PackColor.parse(surfaceHex)

    fun gradientStart(): Long = PackColor.parse(gradientStartHex, primary())
    fun gradientEnd(): Long = PackColor.parse(gradientEndHex, 0L)

    /** Whether a usable two-stop gradient has actually been declared. */
    val hasGradient: Boolean
        get() = gradientStartHex.isNotBlank() && gradientEndHex.isNotBlank()

    fun resolvedHeroTreatment(): HeroTreatment = HeroTreatment.parse(heroTreatment)
}

/**
 * How a pack's hero should be composed.
 *
 * An enum in the runtime rather than a string switch in the app layer, so the pack
 * authoring model is the single place that knows what treatments exist.
 */
@Serializable
enum class HeroTreatment {
    /** Full-bleed artwork, darkened, with type over it. The immersive default. */
    FULL_BLEED,

    /** A gradient wash with generated composition. Works with weak or absent artwork. */
    WASH,

    /** Flat. Type and colour only. For packs whose identity is typographic. */
    FLAT,
    ;

    companion object {
        fun parse(raw: String): HeroTreatment =
            entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: WASH
    }
}

/**
 * Charaly's own accent identities.
 *
 * ## Why purple is no longer the product's colour
 *
 * Purple was a default, not a decision: it was the Material 3 seed colour, adopted
 * because it was there. That is why the first version of this app read as a generic dark
 * CRUD template rather than as a story product.
 *
 * What replaces it is a *neutral* default - white on graphite - which is deliberately
 * unremarkable so that a pack's own identity is the only colour on screen. The named
 * identities below are packs, not brand chrome, and they exist so every pack does not
 * have to hand-pick four hex values from scratch.
 *
 * None of these change the background. The background is always real black: see
 * `CharalySurface`. An accent colours the *content*, never the page.
 */
object CharalyAccent {

    /**
     * White on graphite. The default, and the only one used when no pack is open.
     *
     * Declared as the single source and referenced by [PackTheme]'s defaults, rather
     * than the other way round, because a PackTheme default cannot reference an object
     * property without an initialisation-order cycle.
     */
    val NEUTRAL = PackTheme(
        primaryHex = CharalySurface.INK_PRIMARY,
        secondaryHex = CharalySurface.INK_SECONDARY,
        accentHex = CharalySurface.INK_MUTED,
        inkHex = CharalySurface.INK_PRIMARY,
        surfaceHex = CharalySurface.RAISED,
        mood = "neutral, graphite, quiet",
        gradientStartHex = "",
        gradientEndHex = "",
        heroTreatment = HeroTreatment.WASH.name,
    )

    /** Alias retained for readability at call sites that read as "no pack identity". */
    val NeutralIdentity: PackTheme get() = NEUTRAL

    /** Miraculous: red, black, white. Paris rooftops and a spider's web. */
    val MIRACULOUS = PackTheme(
        primaryHex = "#E4274C",
        secondaryHex = "#0A0A0C",
        accentHex = "#FFFFFF",
        inkHex = "#FFF5F6",
        surfaceHex = "#0B0A0C",
        mood = "heroic, bright, Parisian",
        gradientStartHex = "#E4274C",
        gradientEndHex = "#0A0A0C",
        heroTreatment = HeroTreatment.FULL_BLEED.name,
    )

    /** Neon District: cyan and magenta on black. */
    val NEON_DISTRICT = PackTheme(
        primaryHex = "#00E5FF",
        secondaryHex = "#FF2E9A",
        accentHex = "#FFE600",
        inkHex = "#EAF9FF",
        surfaceHex = "#05070A",
        mood = "neon, wet, electric",
        gradientStartHex = "#00E5FF",
        gradientEndHex = "#FF2E9A",
        heroTreatment = HeroTreatment.FULL_BLEED.name,
    )

    /** The Last Kingdom: gold on deep blue. */
    val LAST_KINGDOM = PackTheme(
        primaryHex = "#D4AF37",
        secondaryHex = "#12294D",
        accentHex = "#F3E9C8",
        inkHex = "#F5F0E1",
        surfaceHex = "#07090E",
        mood = "old, weighty, candlelit",
        gradientStartHex = "#D4AF37",
        gradientEndHex = "#12294D",
        heroTreatment = HeroTreatment.WASH.name,
    )

    /**
     * The identities above, addressable by id.
     *
     * Looked up rather than matched on class so a pack's theme can be described in data
     * (`accentIdentity = "miraculous"`) and still resolve if a pack is saved and reloaded
     * in a future build.
     */
    fun byId(id: String): PackTheme? = when (id.trim().lowercase()) {
        "miraculous" -> MIRACULOUS
        "neon", "neon-district", "cyberpunk" -> NEON_DISTRICT
        "kingdom", "last-kingdom", "fantasy" -> LAST_KINGDOM
        else -> null
    }

    val all: List<Pair<String, PackTheme>> = listOf(
        "miraculous" to MIRACULOUS,
        "neon-district" to NEON_DISTRICT,
        "last-kingdom" to LAST_KINGDOM,
    )
}

/**
 * Charaly's surface scale: true black, in five documented steps.
 *
 * ## Why the background is #000000 and not #0E0D12
 *
 * The earlier palette used a blue-black. It looked fine in isolation and wrong in
 * context, because a dark navy page makes every piece of artwork on it read as slightly
 * wrong - the image appears to have a colour cast that the page is imposing on it.
 *
 * Real black has no cast to impose. It is the only background under which generated and
 * licensed artwork both look like themselves, and it is what makes a red-accented pack
 * and a gold-accented pack feel like two different products rather than two themes of
 * the same one.
 *
 * The steps above black exist for *elevation*, not for tint: #050505 and #080808 are
 * nearly indistinguishable from black on a phone at arm's length, which is exactly the
 * point. A card should be felt, not seen.
 */
object CharalySurface {
/* Pure black. Not #0E0D12, not a navy: see the doc comment above. */
    const val VOID = "#000000"
    const val BASE = "#050505"
    const val RAISED = "#080808"
    const val ELEVATED = "#0D0D0D"
    const val OVERLAY = "#141414"
    const val HAIRLINE = "#1F1F1F"

    // Ink on true black. Three levels: primary, secondary, muted.
    const val INK_PRIMARY = "#F5F5F7"
    const val INK_SECONDARY = "#9E9EA8"
    const val INK_MUTED = "#63636E"

    // ---- scene light -----------------------------------------------------
    //
    // The only two chromatic colours Charaly itself introduces, and both are the colour of
    // the light in a night scene rather than a brand choice. A night backdrop with a warm
    // light source reads as a lamp; a neutral one reads as a rectangle that happens to be
    // dark, which is the failure this replaces.
    //
    // Declared here rather than inline because a colour literal in a screen is a decision
    // nobody can audit, and these two are decisions everybody should be able to see.
    const val NIGHT_SKY = "#0B1026"

    /** Moonlight. Cool, so it cannot be mistaken for the pack's accent. */
    const val NIGHT_LIGHT = "#BFD4FF"
}

/**
 * Tiny hex parser so the runtime can reason about colours without Android.
 *
 * ## Why the fallback is neutral ink and not a hue
 *
 * This default is reached whenever a hex string is blank or malformed, which means it is
 * what every *unset* colour resolves to - a pack with no accent of its own, a character with
 * no declared colour, an artwork slot with nothing to draw from. That is a large fraction of
 * all colour decisions in the app.
 *
 * It used to be the brand violet, which meant all of them rendered purple by default: the
 * exact "purple AI wrapper" identity this design system exists to remove, reintroduced
 * through a function signature rather than a palette. `CharalySurface.INK_PRIMARY` is the
 * right answer - with nothing chosen, there is no hue for anything to belong to, and the
 * shell's near-white is what "Charaly's own colour" means everywhere else.
 */
object PackColor {
    /**
     * What an unset or malformed colour resolves to.
     *
     * A constant rather than a call to [parse], because a default argument that called the
     * function it belongs to would recurse on every invocation - a stack overflow in the
     * one function every colour in the app passes through.
     */
    val NEUTRAL_INK: Long = 0xFF000000L or CharalySurface.INK_PRIMARY.removePrefix("#").toLong(16)

    fun parse(hex: String, fallback: Long = NEUTRAL_INK): Long {
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
    /**
     * The one-line invitation shown on the pack detail screen above "Enter Story".
     *
     * "Step into Paris."
     *
     * ## Why this is separate from [tagline]
     *
     * A tagline describes the *product* ("A living story set in Paris"); this is an
     * instruction to the *reader*. Separating them means a pack can have a precise,
     * informative tagline and a short, inviting call to action without either having to
     * do the other's job.
     */
    val enterInvitation: String = "",
    /**
     * Short atmospheric lines. Two or three, never a list.
     *
     * ## Why these exist and the event list does not
     *
     * The pack detail screen used to list the pack's events, locations and character
     * roster. That is the pack's *authoring data* shown as if it were content, and it
     * makes a story pack read like a database dump.
     *
     * A hook does the opposite job: it establishes mood in the user's own terms before
     * any world state is exposed. "Paris is quieter than usual today" tells a player
     * more about the experience than six event names do, and it reveals nothing the
     * engine will not discover in play.
     *
     * Deliberately free of gameplay semantics - a hook never names an event program, a
     * trigger, or a location id.
     */
    val hooks: List<String> = emptyList(),
    /**
     * The premise: one or two sentences on what this world *is*.
     *
     * This is the only substantial prose a pack detail screen shows, and it is
     * authored, not generated, so it can be trusted as the pack's own description.
     */
    val premise: String = "",
    /** Atmosphere, as a short adjective phrase. Shown as metadata, not prose. */
    val atmosphere: String = "",
) {
    val primaryGenre: String get() = genres.firstOrNull().orEmpty()

    /**
     * The invitation to enter, with a sensible default.
     *
     * Never blank: a detail screen whose button has no label reads as a bug.
     */
    fun invitation(fallback: String = "Step in"): String =
        enterInvitation.ifBlank { fallback }
}