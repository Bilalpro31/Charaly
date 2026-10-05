package dev.charaly.runtime.domain.knowledge

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.EventId
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.StoryTime
import kotlinx.serialization.Serializable

/**
 * What one character has actually established about their world.
 *
 * ## Why facts are not enough
 *
 * A [KnowledgeStore] answers "is this true in the world". It cannot answer the
 * questions a character actually has:
 *
 *  * what have they *seen* with their own eyes, as distinct from been told?
 *  * what do they *believe*, which may be false?
 *  * what do they *suspect*, which is neither believed nor dismissed?
 *  * what do they believe that is *wrong*?
 *
 * Those are different things, and collapsing them is what produces the two failure
 * modes a living world falls into. Give a character omniscient facts and they solve
 * the mystery on page one. Give them only facts-or-nothing and they cannot suspect,
 * guess, or be wrong - so the story has no texture.
 *
 * ## The rule that matters
 *
 * **A misconception is not the absence of knowledge.** "Nino believes Adrien is the
 * new student" is a positive claim the engine holds, with the true fact recorded
 * alongside it. That is what makes a reveal work: the reveal is the engine replacing a
 * false belief, not a fact being added. It also means a character who holds a
 * misconception is *reliably* wrong, which a small model can act on far better than
 * vagueness.
 *
 * ## Ownership
 *
 * Everything here is per character and owned by the [KnowledgeStore]. Nothing in this
 * file can be read by a character other than the one it belongs to unless the store
 * explicitly grants it, and there is deliberately no bulk-read path.
 */
@Serializable
data class CharacterMind(
    val characterId: CharacterId,

    /**
     * What this character has seen for themselves.
     *
     * The distinction that matters: an *observation* is witnessed, so it survives the
     * character being wrong about what it means. Seeing someone at the museum at
     * midnight is an observation even if the character concludes nothing happened.
     */
    val observations: List<Observation> = emptyList(),

    /** Claims they hold as true, with how firmly. */
    val beliefs: List<Belief> = emptyList(),

    /** Claims they are not prepared to believe, but cannot rule out. */
    val suspicions: List<Suspicion> = emptyList(),

    /** Claims they hold as true and are not. */
    val misconceptions: List<Misconception> = emptyList(),
) {
    init {
        require(characterId.value.isNotBlank()) { "a mind needs an owner" }
    }

    /**
     * What can be shown to this character, in the order a prompt should read it.
     *
     * Deliberately ordered by how load-bearing each kind is: what they saw, then what
     * they are sure of, then what they half-believe, then what they are wrong about.
     * A character acting on a suspicion needs the suspicion last, because that is what
     * they would act on if pressed.
     */
    fun promptLines(): List<String> = buildList {
        observations.forEach { add("saw: ${it.render()}") }
        beliefs.filter { it.isFirm }.forEach { add("believes: ${it.claim}") }
        beliefs.filterNot { it.isFirm }.forEach { add("half-believes: ${it.claim}") }
        suspicions.forEach { add("suspects: ${it.claim}") }
        misconceptions.forEach { add("believes (wrongly): ${it.claim} - in fact, ${it.truth}") }
    }

    fun isEmpty(): Boolean =
        observations.isEmpty() && beliefs.isEmpty() && suspicions.isEmpty() && misconceptions.isEmpty()

    val size: Int
        get() = observations.size + beliefs.size + suspicions.size + misconceptions.size

    // ---- queries ---------------------------------------------------------

    fun beliefAbout(subject: String): Belief? =
        beliefs.firstOrNull { it.subject.equals(subject, ignoreCase = true) }

    fun misconceptionAbout(subject: String): Misconception? =
        misconceptions.firstOrNull { it.subject.equals(subject, ignoreCase = true) }

    fun suspicionAbout(subject: String): Suspicion? =
        suspicions.firstOrNull { it.subject.equals(subject, ignoreCase = true) }

    /** Suspicions, for a caller that has no business iterating the field directly. */
    fun suspicionList(): List<Suspicion> = suspicions

    /** Whether this character believes [claim] about [subject], wrongly or not. */
    fun believes(subject: String, claim: String): Boolean =
        beliefAbout(subject)?.claim?.equals(claim, ignoreCase = true) == true ||
            misconceptionAbout(subject)?.claim?.equals(claim, ignoreCase = true) == true

    // ---- transitions -----------------------------------------------------
    // Pure `copy` updates rather than mutating helpers: the store stays a value, which
    // is what lets a failed event leave nothing half-applied.

    fun withObservation(observation: Observation): CharacterMind = copy(
        observations = (observations.filterNot { it.dedupKey() == observation.dedupKey() } + observation)
            .sortedWith(ORDER_OBSERVATIONS),
    )

    fun withBelief(belief: Belief): CharacterMind = copy(
        beliefs = (beliefs.filterNot { it.subject.equals(belief.subject, ignoreCase = true) } + belief)
            .sortedWith(ORDER_BELIEFS),
    )

    fun withSuspicion(suspicion: Suspicion): CharacterMind = copy(
        suspicions = (suspicions.filterNot { it.subject.equals(suspicion.subject, ignoreCase = true) } + suspicion)
            .sortedWith(ORDER_SUSPICIONS),
    )

    /**
     * Records a belief that is not true.
     *
     * Note what it does *not* do: it does not remove the corresponding true belief, if
     * the character has one. A character can hold both "the museum was empty" (wrong)
     * and "Nathalie was in the east wing" (right), and the pair is what makes a reveal
     * land. Removing one because the other exists would flatten them into a single
     * answer, which is the opposite of what a mind should be.
     */
    fun withMisconception(misconception: Misconception): CharacterMind = copy(
        misconceptions = (
            misconceptions.filterNot {
                it.subject.equals(misconception.subject, ignoreCase = true) &&
                    it.claim.equals(misconception.claim, ignoreCase = true)
            } + misconception
            ).sortedWith(ORDER_MISCONCEPTIONS),
    )

    /** What this character saw at a place, most recent first. */
    fun observationsAt(locationId: LocationId): List<Observation> =
        observations.filter { it.locationId == locationId }

    /**
     * Observations matching an optional place.
     *
     * An overload rather than a nullable parameter because "nowhere in particular" and
     * "somewhere with no place attached" are the same question here, and a caller
     * passing `null` should not have to invent a sentinel location id to ask it.
     */
    fun observationsAt(locationId: LocationId?): List<Observation> =
        observations.filter { it.locationId == locationId }

    companion object {
        /** Oldest first, then by text: a total order, so replays match. */
        val ORDER_OBSERVATIONS: Comparator<Observation> =
            compareBy({ it.at }, { it.description })

        /** Firmer beliefs first, because those are the ones acted on. */
        val ORDER_BELIEFS: Comparator<Belief> =
            compareByDescending<Belief> { it.confidence }.thenBy { it.subject }

        val ORDER_SUSPICIONS: Comparator<Suspicion> =
            compareByDescending<Suspicion> { it.strength }.thenBy { it.subject }

        val ORDER_MISCONCEPTIONS: Comparator<Misconception> =
            compareBy({ it.subject }, { it.claim })

        fun empty(characterId: CharacterId): CharacterMind = CharacterMind(characterId)
    }
}

/** Something a character witnessed personally. */
@Serializable
data class Observation(
    /** What was seen. Plain text: an observation is a record, not a claim. */
    val description: String,
    val at: StoryTime,
    val locationId: LocationId? = null,
    /** Who was there, if it is known who. */
    val witnesses: List<CharacterId> = emptyList(),
    /** The event that produced this observation, for "inspect source". */
    val sourceEventId: EventId? = null,
) {
    init {
        require(description.isNotBlank()) { "an observation must describe something" }
    }

    /**
     * Two observations of the same thing at the same place and time are one.
     *
     * Without this, three characters witnessing the same incident would each log it and
     * the store would fill with the world's events repeated N times.
     */
    fun dedupKey(): String = dedupKey(description, at, locationId)

    companion object {
        /**
         * The same key, without an [Observation].
         *
         * On its companion rather than only as a method because the event engine's
         * validation needs to compare a *candidate* observation against the store
         * before the candidate is allowed to exist - and building one just to ask for
         * its key would run this class's precondition on input it is supposed to be
         * rejecting.
         */
        fun dedupKey(description: String, at: StoryTime, locationId: LocationId?): String =
            "${description.trim().lowercase()}|${locationId?.value.orEmpty()}|${at.totalMinutes}"
    }

    fun render(): String = buildString {
        append(description)
        locationId?.let { append(" (at ${it.value})") }
        if (witnesses.isNotEmpty()) append(", with ${witnesses.joinToString { w -> w.value }}")
    }
}

/**
 * A claim a character holds as true.
 *
 * Confidence is separate from how important the claim is, and both are separate from
 * the fact table: holding a belief at 40% is a different, and more useful, state than
 * either being certain or not knowing at all.
 */
@Serializable
data class Belief(
    val subject: String,
    val claim: String,
    val confidence: Int,
    val at: StoryTime = StoryTime.START,
    /** How it reached them: "saw it", "was told by Adrien", "worked it out". */
    val via: String = "",
    val locationId: LocationId? = null,
) {
    init {
        require(subject.isNotBlank()) { "a belief needs a subject" }
        require(claim.isNotBlank()) { "a belief needs a claim" }
        require(confidence in 0..100) { "confidence out of range: $confidence" }
    }

    /** Above this, a character speaks as though it were true. */
    val isFirm: Boolean get() = confidence >= FIRM_CONFIDENCE

    fun render(): String = "$claim ($confidence%)"

    companion object {
        const val FIRM_CONFIDENCE = 70
    }
}

/**
 * Something a character cannot dismiss but will not act on yet.
 *
 * Distinct from a weak [Belief] because it behaves differently: a weak belief leans
 * one way, a suspicion is undecided. A model given a suspicion will ask about it; a
 * model given a 30% belief will usually ignore it.
 */
@Serializable
data class Suspicion(
    val subject: String,
    val claim: String,
    /** How strongly the character resists dismissing it. 0..100. */
    val strength: Int,
    val at: StoryTime = StoryTime.START,
    val via: String = "",
) {
    init {
        require(subject.isNotBlank()) { "a suspicion needs a subject" }
        require(claim.isNotBlank()) { "a suspicion needs a claim" }
        require(strength in 0..100) { "strength out of range: $strength" }
    }

    fun render(): String = "$claim (suspicion, $strength%)"
}

/**
 * A claim a character holds as true and is not.
 *
 * [truth] is what is actually the case. Keeping it on the same record is what makes a
 * reveal possible: the engine can find the false belief and replace it, rather than the
 * story having to hope someone notices.
 *
 * Deliberately *not* a [Fact]. A fact is world truth and is shared; this is one
 * character's error about it, and it must never be usable as truth by anyone else.
 */
@Serializable
data class Misconception(
    val subject: String,
    /** What the character believes. */
    val claim: String,
    /** What is actually so. */
    val truth: String,
    val at: StoryTime = StoryTime.START,
    /** Who is responsible for the error. Usually nobody - that is the point. */
    val via: String = "",
) {
    init {
        require(subject.isNotBlank()) { "a misconception needs a subject" }
        require(claim.isNotBlank()) { "a misconception needs a claim" }
        require(truth.isNotBlank()) { "a misconception needs the truth recorded" }
    }

    fun render(): String = "$claim - actually: $truth"
}