package dev.charaly.runtime.domain

import dev.charaly.runtime.domain.knowledge.Fact
import kotlinx.serialization.Serializable

/**
 * A pack's authoritative canon: everything that is true of the setting regardless of
 * what has happened in *this* playthrough.
 *
 * ## Why canon is separate from the story
 *
 * The brief's phrase is "CANON is not CURRENT STORY", and it is the difference between
 * a pack and a save file.
 *
 * A media pack is a *reference*: Marinette is Marinette, the school has one roof that is
 * locked, Gabriel is the father. Those do not change because the player chose to help
 * someone instead. What changes is everything else - who knows what, who has been where,
 * which promise was broken. Both live in the same `StoryInstance` once a story is
 * running, and confusing them is how a story ends up contradicting itself in the fifth
 * scene and having no way to say which version is the error.
 *
 * So a [StoryPack] holds a `CanonBible`, and a [StoryInstance] holds deviations from it.
 * Nothing in the bible can be edited by playing, which is not a limitation - it is the
 * property that makes "is this right?" answerable.
 *
 * ## Why a single object rather than six lists
 *
 * A pack already declares facts, lore, locations, characters and threads separately,
 * because each is its own kind of thing. `CanonBible` is the *index* over them: the
 * single answer to "what does this setting assert?", including which assertions are
 * secret and who is allowed to know them. Splitting canon across six collections is what
 * makes "list every secret in this pack" a question with six answers.
 */
@Serializable
data class CanonBible(
    /** What kind of world this is. Shown on the pack detail screen. */
    val universe: String = "",

    /** Where this sits in its own timeline. */
    val era: String = "",

    /**
     * The canonical timeline, oldest first.
     *
     * Ordered deliberately rather than a set: a setting's history has an order, and
     * asking "what was true before X" is a real question a pack should be able to answer.
     */
    val timeline: List<CanonEra> = emptyList(),

    /**
     * Assembled canon facts.
     *
     * Referenced by id rather than copied, because a fact has exactly one copy in the
     * pack and two copies is how they come to disagree.
     */
    val factIds: List<FactId> = emptyList(),

    /** Canon assertions that are not modelled as facts but must still be stated. */
    val worldRules: List<CanonRule> = emptyList(),

    /** The visual identity of the setting, for pack art and character art. */
    val visualIdentity: String = "",

    /** The register the setting is written in. */
    val tone: String = "",

    /** Organisation, faction or institution names, with their seat. */
    val organizations: List<CanonOrganization> = emptyList(),

    /** Recurring objects that matter to the story. */
    val importantObjects: List<CanonObject> = emptyList(),

    /** Major arcs, by thread id. */
    val majorArcThreadIds: List<ThreadId> = emptyList(),

    /** The regions or seasons this pack's canon varies across. */
    val eraMetadata: List<String> = emptyList(),

    /**
     * A notice the pack author requires to be shown.
     *
     * Required to be explicit rather than inferred from an author field: a fan pack of
     * someone else's setting has a legal obligation attached to it, and burying that in
     * a byline is how it ends up undistributed by accident.
     */
    val rightsNotice: String = "",
) {

    /** Facts in this pack that are secret - i.e. must never reach a prompt by default. */
    /**
     * Which of this pack's canon facts are secret.
     *
     * Resolved against world truth rather than stored as a second list: a list of
     * "secret fact ids" beside the facts themselves is a second source of truth, and
     * two sources of truth about which facts are secret is how one of them ends up
     * wrong in a way nobody notices until a secret leaks.
     */
    fun secretFactIds(truth: Map<FactId, Fact>): List<FactId> =
        factIds.filter { truth[it]?.secret == true }

    /** Whether this pack declares anything at all worth calling canon. */
    fun isEmpty(): Boolean =
        universe.isBlank() && timeline.isEmpty() && factIds.isEmpty() && worldRules.isEmpty()

    /**
     * Whether this pack separates canon from what the player changes.
     *
     * A useful thing for a pack library to display: a pack that answers false is a
     * demonstration pack or a tool, not a story, and the user should be able to see that
     * before entering rather than after.
     */
    fun declaresCanonSeparately(): Boolean = !isEmpty()

    /** One line for the pack detail screen. */
    fun describe(): String = buildString {
        if (universe.isNotBlank()) appendLine("Setting: $universe")
        if (era.isNotBlank()) appendLine("Era: $era")
        if (timeline.isNotEmpty()) appendLine("${timeline.size} canonical periods")
        if (worldRules.isNotEmpty()) appendLine("${worldRules.size} rules of the world")
        if (majorArcThreadIds.isNotEmpty()) appendLine("${majorArcThreadIds.size} major arcs")
    }

    /** Resolves fact ids to facts, silently dropping any the pack no longer has. */
    fun resolveFacts(truth: Map<FactId, Fact>): List<Fact> =
        factIds.mapNotNull { truth[it] }
}

/** One canonical period. */
@Serializable
data class CanonEra(
    val id: String,
    val title: String,
    val summary: String = "",
    /**
     * What is true *only* during this period.
     *
     * Empty means the period differs only in atmosphere, which is a legitimate kind of
     * entry. Stated rather than inferred because a reader has no other way to know
     * whether the author meant one.
     */
    val distinguishingFacts: List<String> = emptyList(),
)

/**
 * A rule of the setting that is not a fact about anybody.
 *
 * Separate from [Fact] because "Paris is in France" and "akuma are formed from a single
 * emotion in a single moment" are different kinds of claim: the first is geography, the
 * second is *law*, and a character reasoning about what can happen needs both but
 * confuses them constantly.
 */
@Serializable
data class CanonRule(
    val id: String,
    val statement: String,
    /**
     * What cannot be true if this rule holds.
     *
     * This is the field that earns its keep: it is what lets a pack say "there is no
     * daylight patrol" and have that be checkable rather than merely written.
     */
    val forbids: List<String> = emptyList(),
    val secret: Boolean = false,
)

/** An organisation, institution or faction of the setting. */
@Serializable
data class CanonOrganization(
    val id: String,
    val name: String,
    val purpose: String = "",
    val seatLocationId: LocationId? = null,
    /** Ids of characters who belong to it. */
    val memberCharacterIds: List<CharacterId> = emptyList(),
)

/** A recurring object that matters. */
@Serializable
data class CanonObject(
    val id: String,
    val name: String,
    val description: String = "",
    /**
     * Who has it, or knows about it.
     *
     * An object with no known holder is almost always a plot hook the pack has declared
     * and not wired up, and it is worth being able to ask.
     */
    val knownToCharacterIds: List<CharacterId> = emptyList(),
)

/**
 * What a running story did to canon.
 *
 * ## Why this is not simply a log
 *
 * The interesting question about canon is never "what is true" - the pack answers that.
 * It is "what did *this playthrough* do differently, and what does that cost?"
 *
 * So a deviation records what canon said, what this story has instead, and who is
 * affected. Withholding "what canon said" is what makes it a deviation rather than a
 * second fact table, and it is what lets the story notice that its own version has
 * stopped being the author's.
 */
@Serializable
data class CanonDeviation(
    /** Which canon entry moved. A fact id, a rule id, or a thread id. */
    val canonId: String,
    /** What the canon asserts. */
    val canonSaid: String,
    /** What this story has instead. Empty means canon was restored. */
    val storyHas: String,
    /** Characters whose situation this changes. */
    val affectedCharacterIds: List<CharacterId> = emptyList(),
    val noticedAt: StoryTime = StoryTime.START,
    /** Why the story took this route. The event that caused it. */
    val because: String = "",
) {
    val isRestored: Boolean get() = storyHas.isBlank()
}

/** Everything this playthrough did to canon. */
@Serializable
data class CanonLedger(
    val deviations: List<CanonDeviation> = emptyList(),
) {
    fun deviation(canonId: String): CanonDeviation? = deviations.firstOrNull { it.canonId == canonId }

    /** Entries this story has changed away from the author's canon. */
    fun activeDeviations(): List<CanonDeviation> = deviations.filterNot { it.isRestored }

    /** Whether a given character is living in a version that is not the canon version. */
    fun divergedFor(characterId: CharacterId): List<CanonDeviation> =
        activeDeviations().filter { characterId in it.affectedCharacterIds }

    fun with(deviation: CanonDeviation): CanonLedger = copy(
        deviations = deviations.filterNot { it.canonId == deviation.canonId } + deviation,
    )

    /** One line for the developer panel. */
    fun describe(): String = when {
        deviations.isEmpty() -> "This story is following canon exactly."
        else -> buildString {
            appendLine("${activeDeviations().size} deviations from canon")
            deviations.forEach { deviation ->
                val arrow = if (deviation.isRestored) "restored" else "-> ${deviation.storyHas}"
                appendLine("  ${deviation.canonId}: canon said \"${deviation.canonSaid}\", this story $arrow")
            }
        }.trimEnd()
    }

    companion object {
        val EMPTY = CanonLedger()
    }
}