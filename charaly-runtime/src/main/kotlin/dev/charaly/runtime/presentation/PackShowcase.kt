package dev.charaly.runtime.presentation

import dev.charaly.runtime.domain.HeroTreatment
import dev.charaly.runtime.domain.PackIdentity
import dev.charaly.runtime.domain.StoryPack

/**
 * What a Story Pack detail screen is allowed to show a player.
 *
 * ## The problem this type exists to solve
 *
 * The pack detail screen used to render the pack's **authoring data**: its event
 * programs, its location catalogue, its character roster, its factions and its lore
 * entries, each in its own labelled section with a count.
 *
 * That is the wrong content for the wrong audience. It reads as a database dump rather
 * than as a story, and it front-loads information the story engine is supposed to
 * *reveal*. A player who can see that the pack defines nine event types and
 * twenty-six locations before pressing a button is being handed the design document
 * instead of the world.
 *
 * So the detail screen now renders exactly one thing: [PackShowcase].
 *
 * ```
 *   cover artwork
 *   title
 *   universe / era
 *   premise          <- the one substantial paragraph
 *   hooks            <- 2-3 atmospheric lines
 *   tone, atmosphere <- short metadata
 *   [ Enter Story ]
 * ```
 *
 * Everything else a pack contains - `CanonBible`, `WorldRules`, `CharacterDefinition`s,
 * `Location`s, `PackEventDefinition`s, routines, secrets, relationships, threads - stays
 * in the pack and is used by the engine. It is simply never projected here.
 *
 * ## Why this is a separate type instead of a parameter
 *
 * Making this a projection type rather than a flag on the existing snapshot is
 * deliberate. A flag (`developerMode: Boolean`) means the default path is "show
 * everything" and the careful path is opt-in, which is exactly backwards: the strict
 * projection has to be the only thing a normal build can construct.
 *
 * ## Where the hidden data actually goes
 *
 * Nothing is lost. During play the same information reaches the user through
 * [WorldPresenter], [StoryPresenters] and the engine itself - as a location name in the
 * chat header, as a character who walks in, as a thread that surfaces hours later. The
 * difference is that it arrives *in the story*, in order, at the moment it matters.
 */
data class PackShowcase(
    val id: String,
    val title: String,
    /** "Contemporary Paris", "Vaurel, the last autumn of the reign..." */
    val universe: String,
    /** One or two sentences. Authored, never generated. */
    val premise: String,
    /** 2-3 short atmospheric lines. Never a gameplay list. */
    val hooks: List<String>,
    /** "Warm, breathless, a little melancholy underneath the jokes." */
    val tone: String,
    /** Short adjective phrase: "Wet neon, low voices". */
    val atmosphere: String,
    /** The invitation above the primary button: "Step into Paris." */
    val invitation: String,
    val tagline: String,
    val genres: List<String>,
    /** The pack's accent identity, for the app layer's theme. */
    val theme: ResolvedTheme,
    val artwork: dev.charaly.runtime.domain.PackArtwork,
    val heroTreatment: HeroTreatment,
    /** Rights/attribution notice, when the pack declares one. Shown, never hidden. */
    val fandomNotice: String,
    val contentNotes: List<String>,
    /**
     * Whether this pack has a playthrough in progress.
     *
     * The only cast of any kind the detail screen acknowledges: a single "Continue"
     * affordance. The roster of previous playthroughs belongs to the Stories screen.
     */
    val canContinue: Boolean,
) {
    val hasHooks: Boolean get() = hooks.isNotEmpty()

    /** Never blank, because a button with no label reads as a bug. */
    val primaryActionLabel: String get() = invitation.ifBlank { "Enter Story" }

    /** Secondary action, shown only when there is something to continue. */
    val continueLabel: String get() = if (canContinue) "Continue Story" else ""
}

/**
 * Builds the [PackShowcase] for a pack.
 *
 * The whole projection is here, in one function, so that "does the detail screen leak
 * engine data" is a question with one answer rather than a review of every screen.
 */
object PackShowcaseBuilder {

    /** Hard cap on hooks. Three is a mood; eight is a backlog. */
    const val MAX_HOOKS = 3

    /**
     * Projects a pack down to its presentation.
     *
     * @param canContinue whether a playthrough exists, so the screen can offer it.
     */
    fun build(pack: StoryPack, canContinue: Boolean = false): PackShowcase =
        build(pack.identity, pack.id.value, pack.title, canContinue)

    /**
     * Projects an identity. Split out so tests can assert the projection without
     * constructing a whole pack.
     */
    fun build(
        identity: PackIdentity,
        packId: String,
        title: String,
        canContinue: Boolean = false,
    ): PackShowcase = PackShowcase(
        id = packId,
        title = title,
        universe = universeLine(identity),
        // A pack that never authored a premise falls back to its tagline, which is a
        // weaker but honest description rather than an empty block.
        premise = identity.premise.ifBlank { identity.tagline },
        hooks = identity.hooks
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .take(MAX_HOOKS),
        tone = identity.tone,
        atmosphere = identity.atmosphere.ifBlank { identity.theme.mood },
        invitation = identity.invitation(),
        tagline = identity.tagline,
        genres = identity.genres,
        theme = ResolvedTheme.of(identity.theme),
        artwork = identity.cover,
        heroTreatment = identity.theme.resolvedHeroTreatment(),
        fandomNotice = identity.fandomNotice,
        contentNotes = identity.contentNotes,
        canContinue = canContinue,
    )

    /**
     * "Contemporary Paris" - the era, with the genres as a fallback.
     *
     * Falls back rather than showing an empty line, because a showcase with a blank
     * metadata row looks unfinished rather than minimal.
     */
    private fun universeLine(identity: PackIdentity): String = when {
        identity.era.isNotBlank() -> identity.era
        identity.genres.isNotEmpty() -> identity.genres.take(2).joinToString(" · ")
        else -> ""
    }
}

/**
 * The developer-only view of a pack's internals.
 *
 * ## Why this exists rather than a boolean on [PackShowcase]
 *
 * Somebody has to be able to look at a pack's event programs, locations and roster.
 * That is a legitimate need - pack authoring, debugging a broken pack, understanding why
 * the world behaved as it did - and hiding it entirely would only mean people read the
 * save files instead.
 *
 * So it exists as a *different type*, constructed only from
 * `DeveloperModeInspector`/developer-gated code, and rendered only on a screen that is
 * unreachable when developer mode is off. The distinction that matters: there is no way
 * to obtain one of these from a [PackShowcase], so the two cannot be confused.
 */
data class PackInternals(
    val characters: List<CharacterCard>,
    val locations: List<LocationCard>,
    val events: List<EventCard>,
    val factions: List<FactionCard>,
    val lore: List<LoreCard>,
    val threads: List<ThreadCard>,
    val canonLines: List<String>,
    val stats: List<StatChip>,
) {
    companion object {
        val EMPTY = PackInternals(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    }
}
