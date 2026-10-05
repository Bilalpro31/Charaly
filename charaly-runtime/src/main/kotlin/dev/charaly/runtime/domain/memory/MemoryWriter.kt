package dev.charaly.runtime.domain.memory

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.EventId
import dev.charaly.runtime.domain.FactId
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.MemoryId
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryTime
import dev.charaly.runtime.domain.ThreadId
import dev.charaly.runtime.domain.TranscriptRole

/**
 * The memory write pipeline.
 *
 * ## Why this exists
 *
 * "Write every message to memory" is the obvious implementation and the wrong one. A
 * forty-turn conversation produces a hundred lines, and storing all of them gives a
 * character a memory of being asked how they are. Memory that inflates is memory that
 * stops being *retrievable*, because the six slots a prompt can hold get spent on the
 * most recent trivia instead of the thing the player actually returned to talk about.
 *
 * So writing is a pipeline with gates, and a candidate that fails a gate is discarded
 * rather than stored badly:
 *
 * ```
 * conversation
 *   -> candidates            what might be worth remembering
 *   -> importance            is this small talk?
 *   -> canon check           does the world already hold this as fact?
 *   -> contradiction check   does this replace something the character believed?
 *   -> visibility check      is this actually the character's to know?
 *   -> deduplication         has this already been said?
 *   -> MemoryStore
 * ```
 *
 * ## Determinism
 *
 * Nothing here calls the model, and no threshold is random. The same conversation
 * always produces the same memories, which is what makes "the engine is a pure
 * function" true all the way down to the store.
 *
 * The model may *propose* a candidate (see `ProposedMemory`), but every gate below
 * still runs on it. A hallucinated "you told me you are the queen" is scored, checked
 * against canon, checked against visibility, and rejected - never stored.
 */
object MemoryWriter {

    /**
     * Below this importance, a line is small talk and produces no memory.
     *
     * Calibrated so that greetings, farewells, weather and in-character filler score
     * below it, while anything that changes a relationship, reveals a fact or names a
     * commitment scores above. See `MemoryImportanceTest` for the calibration cases.
     */
    const val SMALL_TALK_THRESHOLD = 2

    /** Importance is clamped to this, so nothing outranks a defining memory. */
    const val MAX_IMPORTANCE = 5

    // ------------------------------------------------------------------
    // 1. Candidate extraction
    // ------------------------------------------------------------------

    /**
     * A proposal to remember something, before any gate has run.
     *
     * This is the shape the model fills in when it proposes a memory, and also the
     * shape the deterministic extractor produces. Keeping them identical is what
     * stops a proposed memory from being treated as more trustworthy than a found
     * one - the gates cannot tell them apart, by design.
     */
    data class Candidate(
        val ownerId: CharacterId,
        val content: String,
        /** What the writer believes this is. Gates may override it. */
        val tier: MemoryTier = MemoryTier.EPISODIC,
        val importance: Int = 3,
        val confidence: Int = 100,
        val source: MemorySource = MemorySource.EVENT,
        val sourceEventId: EventId? = null,
        val sourceMessageId: String? = null,
        val relatedCharacterIds: List<CharacterId> = emptyList(),
        val relatedLocationId: LocationId? = null,
        val relatedFactIds: List<FactId> = emptyList(),
        val relatedThreadIds: List<ThreadId> = emptyList(),
        val sceneId: String? = null,
        val visibility: MemoryVisibility = MemoryVisibility.CHARACTER,
        val visibleTo: List<CharacterId> = emptyList(),
        /** The claim this memory makes. Empty means "makes no claim". */
        val subject: String = "",
        val predicate: String = "",
    )

    /** Why a candidate did not become a memory. Shown in the developer inspector. */
    enum class Rejection {
        /** Scored as small talk. */
        TRIVIAL,

        /** Belongs to someone who does not exist in this story. */
        UNKNOWN_OWNER,

        /** The owner is not allowed to hold this at all. */
        FORBIDDEN,

        /** Duplicates a memory the owner already has. */
        DUPLICATE,

        /** Already stated better by world truth. */
        REDUNDANT_WITH_CANON,

        /** Says nothing at all. */
        EMPTY,
    }

    /** One gate's decision, so a rejection is explainable rather than silent. */
    data class Decision(
        val candidate: Candidate,
        val importance: Int,
        val rejection: Rejection? = null,
    ) {
        val accepted: Boolean get() = rejection == null
    }

    /**
     * The result of writing: the new store, and the memories that were created.
     *
     * Superseded predecessors are *not* returned as created - they were not created -
     * but they are still in the store as history.
     */
    data class WriteResult(
        val store: MemoryStore,
        val created: List<Memory>,
        val rejected: List<Decision>,
    ) {
        val createdCount: Int get() = created.size

        /** One line per gate failure, for the inspector. */
        fun rejectionSummary(): String =
            rejected.groupingBy { it.rejection }.eachCount()
                .entries.joinToString("; ") { "${it.key} x${it.value}" }
    }

    // ------------------------------------------------------------------
    // Importance
    // ------------------------------------------------------------------

    /**
     * Deterministic importance for a line of dialogue or narration.
     *
     * A weighted signal count rather than a sentiment or a model call: it is
     * reproducible, costs nothing, and is explainable ("this scored 4 because it
     * contains a secret word, a first person disclosure and a question"). Signals
     * chosen because they mark the difference between "hi" and "I know what you did".
     */
    fun importanceOf(content: String, tier: MemoryTier): Int =
        maxOf(rawImportance(content), tierFloor(tier)).coerceIn(1, MAX_IMPORTANCE)

    /**
     * Importance from the words alone, with no tier floor.
     *
     * This is what the small-talk gate consults, and keeping it separate from
     * [importanceOf] is the whole point. The tier floor exists so a short but important
     * *kind* of memory is stored at a meaningful weight; if that floor also fed the
     * gate then "hi" - tagged EPISODIC by the extractor - would score 2 and sail
     * through as something worth remembering. A floor is a floor, not a promotion.
     */
    fun rawImportance(content: String): Int {
        val text = content.lowercase()
        if (text.isBlank()) return 0
        // Its own tokenizer rather than MemoryScore's, because that one deliberately
        // drops words shorter than three characters as stop-word noise - which throws
        // away "I", "my" and "me", and those are exactly the words that mark a
        // self-disclosure.
        val words = text.split(Regex("[^a-z']+"))
            .asSequence()
            .filter(String::isNotBlank)
            .toSet()

        var score = 1

        // A disclosure: first person plus a verb of knowing/doing.
        val firstPerson = FIRST_PERSON.any { it in words }
        val discloses = SELF_DISCLOSURE.any { it in words }
        if (firstPerson) score += 1
        if (discloses) score += 1

        // Something that changes the world rather than describing the weather.
        if (WORLD_BEARING.any { it in words }) score += 1

        // A promise or commitment. "will"/"shall" are deliberately absent: they are far
        // too common in ordinary speech to be evidence of anything.
        if (COMMITMENT.any { it in words }) score += 2

        // A question mark is only evidence when the question is actually about
        // something. "Lovely morning, isn't it?" is small talk; "what did you see?" is
        // not. So it is gated on there being a claim for the question to be about.
        if (content.contains('?') && (firstPerson || discloses)) score += 1

        // A secret or an identity. Rare, and expensive to forget.
        if (SECRET_MARKERS.any { it in words }) score += 2

        // Volume is weak evidence, but a long line is usually a consequential one.
        if (content.length > 160) score += 1

        return score.coerceIn(1, MAX_IMPORTANCE)
    }

    /**
     * Whether a candidate is just talk.
     *
     * Two things save a candidate from being thrown away, and both matter:
     *
     *  * it says enough on its own to clear [SMALL_TALK_THRESHOLD], and
     *  * it is a *labelled claim* - a subject and a predicate.
     *
     * The second is not a loophole. [rawImportance] counts conversational signals
     * (first person, disclosure, a promise), because that is what separates "hi" from
     * "I know what you did". A declarative fact has none of them: "the museum is open
     * on Sunday" scores 1 and would be discarded as chatter, which is plainly wrong -
     * it is a durable fact about a place. So a candidate that states what it claims
     * about what is admitted on that basis alone, and scored afterwards.
     *
     * The asymmetry is deliberate: unlabelled text has to earn its place, labelled
     * claims only have to be true.
     */
    fun isTrivial(candidate: Candidate, rawScore: Int = rawImportance(candidate.content)): Boolean {
        if (rawScore >= SMALL_TALK_THRESHOLD) return false
        return candidate.subject.isBlank() || candidate.predicate.isBlank()
    }

    private fun tierFloor(tier: MemoryTier): Int = when (tier) {
        MemoryTier.CANON, MemoryTier.WORLD, MemoryTier.SEMANTIC -> 4
        MemoryTier.PROMISE, MemoryTier.GOAL, MemoryTier.CONSEQUENCE -> 4
        MemoryTier.RELATIONSHIP, MemoryTier.CHARACTER, MemoryTier.SECRET -> 3
        MemoryTier.THREAD -> 3
        MemoryTier.SCENE -> 2
        MemoryTier.EPISODIC -> 2
        MemoryTier.WORKING -> 1
    }

    private val FIRST_PERSON = setOf("i", "me", "my", "mine", "myself", "we", "our")
    private val SELF_DISCLOSURE = setOf("know", "saw", "found", "heard", "remember", "forgot", "learned", "realised", "realized")
    private val WORLD_BEARING = setOf("because", "instead", "changed", "gone", "stolen", "broken", "secret", "hidden", "threat", "danger")
    private val COMMITMENT = setOf("promise", "swear", "shall", "owe", "agreed", "vow")
    private val SECRET_MARKERS = setOf("secret", "identity", "alias", "villain", "miraculous", "akuma", "mask", "disguise", "true", "really")

    // ------------------------------------------------------------------
    // 2. The pipeline
    // ------------------------------------------------------------------

    /**
     * Runs every gate over [candidates] and returns the new store.
     *
     * Pure: it takes and returns a [MemoryStore], so the caller decides when to
     * persist and can replay the same input against the same store.
     */
    fun write(
        store: MemoryStore,
        candidates: List<Candidate>,
        now: StoryTime = StoryTime.START,
        idPrefix: String = "mem",
        isKnownCharacter: (CharacterId) -> Boolean = { true },
        canonFacts: List<String> = emptyList(),
    ): WriteResult {
        var working = store
        val created = mutableListOf<Memory>()
        val rejected = mutableListOf<Decision>()
        var counter = 0L

        candidates.forEach { candidate ->
            // --- gate 0: nothing to say
            if (candidate.content.isBlank()) {
                rejected += Decision(candidate, 0, Rejection.EMPTY)
                return@forEach
            }

            // --- gate 1: is this small talk?
            val rawScore = rawImportance(candidate.content)
            if (isTrivial(candidate, rawScore)) {
                rejected += Decision(candidate, rawScore, Rejection.TRIVIAL)
                return@forEach
            }
            // Only now does the tier's floor apply, as the stored weight rather than as
            // the admission decision.
            val importance = importanceOf(candidate.content, candidate.tier)

            // --- gate 2: does this owner exist?
            if (!isKnownCharacter(candidate.ownerId)) {
                rejected += Decision(candidate, importance, Rejection.UNKNOWN_OWNER)
                return@forEach
            }

            // --- gate 3: is this the owner's to know at all?
            // A private memory belonging to someone else is not "invisible", it is
            // unreachable: rejecting it here means it can never be granted onward.
            if (candidate.visibility == MemoryVisibility.PRIVATE &&
                candidate.ownerId !in candidate.visibleTo
            ) {
                rejected += Decision(candidate, importance, Rejection.FORBIDDEN)
                return@forEach
            }

            // --- gate 4: does the owner already hold this?
            val duplicate = working.of(candidate.ownerId).firstOrNull {
                it.isCurrent && MemoryConsolidator.similarity(it, previewOf(candidate)) >= MemoryConsolidator.DUPLICATE_OVERLAP
            }
            if (duplicate != null) {
                rejected += Decision(candidate, importance, Rejection.DUPLICATE)
                return@forEach
            }

            // --- gate 5: is world truth already this, and better?
            if (candidate.tier.isSubjective && canonFacts.any { fact ->
                    MemoryConsolidator.similarity(
                        previewOf(candidate.copy(content = fact)),
                        previewOf(candidate),
                    ) >= MemoryConsolidator.DUPLICATE_OVERLAP
                }
            ) {
                rejected += Decision(candidate, importance, Rejection.REDUNDANT_WITH_CANON)
                return@forEach
            }

            // --- gate 6: does this replace something the owner believed?
            // The id is allocated *first*, so the `supersededBy` pointers and the
            // incoming memory refer to one and the same id. Allocating it inside the
            // supersede step and again afterwards is how a superseded memory ends up
            // pointing at an id that belongs to nothing.
            val newId = MemoryId("$idPrefix-$now-${++counter}")
            val (afterSupersede, supersededIds) = supersedeContradictions(
                store = working,
                candidate = candidate,
                now = now,
                replacementId = newId,
            )
            working = afterSupersede

            val memory = toMemory(candidate, importance, newId, now)
                .copy(supersedes = supersededIds)
            working = working.add(memory)
            created += memory
        }

        return WriteResult(working, created, rejected)
    }

    /**
     * A throwaway [Memory] with the candidate's content, used only for similarity
     * comparisons. Never stored, so it needs no id discipline.
     */
    private fun previewOf(candidate: Candidate): Memory = Memory(
        id = MemoryId("preview"),
        characterId = candidate.ownerId,
        content = candidate.content,
        tier = candidate.tier,
        subject = candidate.subject,
        predicate = candidate.predicate,
    )

    /**
     * Retires the memories this candidate contradicts.
     *
     * Superseding rather than deleting is the point: knowing that a belief *changed*
     * is itself story information, and the developer inspector has to be able to show
     * "she used to believe the museum was empty".
     */
    private fun supersedeContradictions(
        store: MemoryStore,
        candidate: Candidate,
        now: StoryTime,
        replacementId: MemoryId,
    ): Pair<MemoryStore, List<MemoryId>> {
        if (candidate.subject.isBlank() || candidate.predicate.isBlank()) return store to emptyList()
        val key = MemoryConsolidator.keyOf(previewOf(candidate))
        val conflicting = store.current().filter {
            it.characterId == candidate.ownerId &&
                MemoryConsolidator.keyOf(it) == key &&
                it.contradicts(previewOf(candidate))
        }
        if (conflicting.isEmpty()) return store to emptyList()

        var result = store
        conflicting.forEach { older ->
            result = result.update(older.id) { it.supersedeBy(replacementId, now) }
        }
        return result to conflicting.map { it.id }
    }

    private fun toMemory(
        candidate: Candidate,
        importance: Int,
        id: MemoryId,
        now: StoryTime,
    ): Memory = Memory(
        id = id,
        characterId = candidate.ownerId,
        content = candidate.content.trim(),
        importance = importance.coerceIn(1, MAX_IMPORTANCE),
        createdAt = now,
        source = candidate.source,
        relatedCharacterIds = candidate.relatedCharacterIds.distinct(),
        relatedLocationId = candidate.relatedLocationId,
        relatedFactIds = candidate.relatedFactIds,
        sceneId = candidate.sceneId,
        tier = candidate.tier,
        visibility = candidate.visibility,
        visibleTo = candidate.visibleTo.distinct(),
        confidence = candidate.confidence.coerceIn(0, 100),
        sourceEventId = candidate.sourceEventId,
        sourceMessageId = candidate.sourceMessageId,
        relatedThreadIds = candidate.relatedThreadIds,
        subject = candidate.subject,
        predicate = candidate.predicate,
        validFrom = now,
    )

    // ------------------------------------------------------------------
    // 3. Deterministic extraction from a transcript
    // ------------------------------------------------------------------

    /**
     * Finds memory candidates in what was actually said.
     *
     * Deliberately conservative: the only lines that become candidates are
     *
     *  * a *player* line, which the owner is the speaker of the scene, because the
     *    player's own actions are what characters remember about them, and
     *  * a *character* line carrying a secret or commitment marker.
     *
     * Everything else is left to the importance gate to discard, so adding a new kind
     * of signal never silently starts storing everything.
     */
    fun extractFrom(
        instance: StoryInstance,
        sinceTurn: Int,
        ownerId: CharacterId,
    ): List<Candidate> {
        val now = instance.worldClock.now
        val threadIds = instance.currentScene()?.activeThreadIds.orEmpty()
        return instance.conversation.sinceTurn(sinceTurn)
            .filter { it.text.isNotBlank() }
            .mapNotNull { entry ->
                val isPlayerLine = entry.role == TranscriptRole.USER
                val isCharacterLine = entry.role == TranscriptRole.CHARACTER
                if (!isPlayerLine && !isCharacterLine) return@mapNotNull null

                val tier = if (isPlayerLine) MemoryTier.EPISODIC else tierForLine(entry.text)
                Candidate(
                    ownerId = ownerId,
                    content = entry.text.trim(),
                    tier = tier,
                    source = if (isPlayerLine) MemorySource.USER_INPUT else MemorySource.DIALOGUE,
                    sourceMessageId = entry.id,
                    relatedLocationId = instance.currentScene()?.locationId,
                    relatedThreadIds = threadIds,
                    // The scene the line belongs to, so a memory can be traced back to the exact
                    // moment it came from rather than only to a turn number.
                    sceneId = entry.sceneId?.value,
                    visibility = visibilityFor(tier),
                )
            }
    }

    private fun tierForLine(text: String): MemoryTier {
        val words = MemoryScore.tokenize(text)
        return when {
            SECRET_MARKERS.any { it in words } -> MemoryTier.CHARACTER
            COMMITMENT.any { it in words } -> MemoryTier.PROMISE
            else -> MemoryTier.EPISODIC
        }
    }

    private fun visibilityFor(tier: MemoryTier): MemoryVisibility = when (tier) {
        MemoryTier.SECRET -> MemoryVisibility.SECRET
        MemoryTier.PROMISE -> MemoryVisibility.CHARACTER
        else -> MemoryVisibility.CHARACTER
    }
}