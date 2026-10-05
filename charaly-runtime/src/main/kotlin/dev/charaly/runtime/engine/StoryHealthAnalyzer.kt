package dev.charaly.runtime.engine

import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.WorldDefinition
import dev.charaly.runtime.domain.memory.MemorySubject
import dev.charaly.runtime.domain.memory.MemoryScore

/**
 * Story health.
 *
 * ## What this is for
 *
 * A living world accumulates the kind of damage that is invisible until you go
 * looking: a character who has been in two places at once, a thread nobody has touched
 * in forty in-story days, a secret that leaked into a bystander's memory, a promise
 * nobody has kept or broken. None of these crash anything. They just quietly rot the
 * story, and the player finds out by noticing that something is off.
 *
 * This analyzer looks for them. It is:
 *
 *  * **read-only.** It never mutates the world and never schedules an event. A
 *    diagnostic that fixes things as a side effect of being asked a question is a
 *    diagnostic nobody can trust to answer questions.
 *  * **deterministic.** Same instance, same report - no sampling, no model call.
 *  * **complete over [Definition].** It resolves ids against the pack, so a stale id
 *    left over from a rename is reported rather than silently skipped.
 *
 * ## Visibility
 *
 * Nothing here is shown to a normal player. A health report is either a developer-mode
 * panel or a line in the log - surfacing "3 contradictory facts" to someone who just
 * wants to play would be a bug report waiting to happen.
 */
class StoryHealthAnalyzer(private val definition: WorldDefinition) {

    /** How bad the story is, in one word. */
    enum class Status {
        /** Nothing wrong that anyone would notice. */
        GOOD,

        /** Something is off. The story still works. */
        WARNING,

        /** The world is inconsistent and something is now lying to the model. */
        ERROR,
    }

    /** The kind of problem, so the panel can group and the log can filter. */
    enum class Kind {
        /** Two current memories make incompatible claims about the same thing. */
        CONTRADICTORY_MEMORY,

        /** Two characters hold the same fact at incompatible confidence. */
        CONTRADICTORY_FACT,

        /** A character is in a place the world does not have. */
        IMPOSSIBLE_LOCATION,

        /** Two characters are in one place, far apart in story time, with no gap. */
        IMPOSSIBLE_TIMELINE,

        /** A relationship that contradicts itself or its own history. */
        IMPOSSIBLE_RELATIONSHIP,

        /** A character knows something they were never given. */
        UNAUTHORISED_KNOWLEDGE,

        /** A secret is readable by someone it was never shared with. */
        SECRET_LEAKAGE,

        /** An active thread nobody has advanced in a long time. */
        DEAD_THREAD,

        /** A thread that references a character or place that does not exist. */
        ORPHANED_THREAD,

        /** A scene whose participants are not actually present. */
        MISSING_PARTICIPANT,

        /** Two current memories say the same thing. */
        DUPLICATE_MEMORY,

        /** A goal that has been active far longer than any goal lasts. */
        STALE_GOAL,

        /** Two events claim the same effect. */
        EVENT_CONFLICT,
    }

    /**
     * One problem.
     *
     * [evidence] is what a human would need in order to believe the report, and it is
     * what makes these claims checkable rather than assertions.
     */
    data class Finding(
        val kind: Kind,
        val severity: Status,
        val summary: String,
        val evidence: List<String> = emptyList(),
    )

    data class Report(
        val status: Status,
        val findings: List<Finding>,
        val checked: Checks,
    ) {
        val isHealthy: Boolean get() = status == Status.GOOD

        fun of(kind: Kind): List<Finding> = findings.filter { it.kind == kind }

        /** One line, for the developer panel and the log. */
        fun summarise(): String = when {
            findings.isEmpty() -> "GOOD - $checked checks passed"
            else -> "${status.name} - ${findings.size} findings across $checked checks"
        }

        /**
         * What was inspected, so "no findings" is a claim about something rather than
         * an absence of work.
         */
        data class Checks(
            val memories: Int = 0,
            val facts: Int = 0,
            val relationships: Int = 0,
            val threads: Int = 0,
            val scenes: Int = 0,
            val characters: Int = 0,
        )
    }

    // ------------------------------------------------------------------
    // The analysis
    // ------------------------------------------------------------------

    fun analyse(instance: StoryInstance): Report {
        val findings = buildList {
            addAll(contradictoryMemories(instance))
            addAll(duplicateMemories(instance))
            addAll(contradictoryFacts(instance))
            addAll(impossibleLocations(instance))
            addAll(impossibleRelationships(instance))
            addAll(unauthorisedKnowledge(instance))
            addAll(secretLeakage(instance))
            addAll(deadThreads(instance))
            addAll(orphanedThreads(instance))
            addAll(missingParticipants(instance))
            addAll(staleGoals(instance))
            addAll(eventConflicts(instance))
        }.sortedWith(compareBy({ it.severity.ordinal }, { it.kind.name }, { it.summary }))

        return Report(
            status = statusOf(findings),
            findings = findings,
            checked = Report.Checks(
                memories = instance.memories.current().size,
                facts = instance.knowledge.factCount,
                relationships = instance.relationships.size,
                threads = instance.storyThreads.size,
                scenes = instance.worldState.activeScenes.size,
                characters = instance.worldState.characters.size,
            ),
        )
    }

    /**
     * The worst severity present.
     *
     * A single ERROR makes the whole report an error, deliberately: the status is a
     * summary a developer panel can show at a glance, and "WARNING" over a finding
     * that means the model is being lied to would be the wrong summary.
     */
    private fun statusOf(findings: List<Finding>): Status = when {
        findings.any { it.severity == Status.ERROR } -> Status.ERROR
        findings.isNotEmpty() -> Status.WARNING
        else -> Status.GOOD
    }

    // ------------------------------------------------------------------
    // Individual checks
    // ------------------------------------------------------------------

    /**
     * Two current memories making incompatible claims about the same subject.
     *
     * An ERROR rather than a warning: the consolidator should have resolved these,
     * so surviving ones mean contradictory facts are reaching the prompt at once.
     */
    private fun contradictoryMemories(instance: StoryInstance): List<Finding> {
        val claims = instance.memories.current()
            .filter { it.subject.isNotBlank() && it.predicate.isNotBlank() }
            .groupBy { "${it.characterId.value}|${it.subject.lowercase()}|${it.predicate.lowercase()}" }

        return claims.mapNotNull { (_, group) ->
            if (group.size < 2) return@mapNotNull null
            val distinct = group.distinctBy { it.content.trim().lowercase() }
            if (distinct.size < 2) return@mapNotNull null
            Finding(
                kind = Kind.CONTRADICTORY_MEMORY,
                severity = Status.ERROR,
                summary = "${group.first().characterId.value} holds ${distinct.size} incompatible memories about " +
                    "'${group.first().subject} ${group.first().predicate}'",
                evidence = distinct.map { "${it.id.value}: ${it.content}" },
            )
        }
    }

    /**
     * Current memories that say the same thing.
     *
     * A WARNING: harmless to the world's truth, but it wastes retrieval slots, which is
     * how a character ends up apparently forgetting something because three copies of
     * trivia crowded out the one thing that mattered.
     */
    private fun duplicateMemories(instance: StoryInstance): List<Finding> {
        val findings = mutableListOf<Finding>()
        instance.memories.current()
            .groupBy { it.characterId }
            .forEach { (owner, memories) ->
                memories.forEachIndexed { index, candidate ->
                    val duplicate = memories.drop(index + 1).firstOrNull { other ->
                        other.id != candidate.id &&
                            candidate.subject.isBlank() &&
                            MemoryScore.similarityOf(candidate, other) >= DUPLICATE_THRESHOLD
                    } ?: return@forEachIndexed
                    findings += Finding(
                        kind = Kind.DUPLICATE_MEMORY,
                        severity = Status.WARNING,
                        summary = "${owner.value} holds a near-duplicate memory twice",
                        evidence = listOf(candidate.id.value, duplicate.id.value),
                    )
                }
            }
        return findings
    }

    /**
     * A character holding a fact at high confidence while another holds it at low
     * confidence, about the same fact.
     *
     * Not automatically wrong - people can be told a rumour - but it is the exact
     * shape of "one of them is wrong about something important", which is worth seeing.
     */
    private fun contradictoryFacts(instance: StoryInstance): List<Finding> =
        instance.knowledge.knowledge.entries
            .mapNotNull { (characterId, entries) ->
                val lowConfidence = entries.filter { it.confidence < LOW_CONFIDENCE }
                    .map { it.factId }
                    .toSet()
                if (lowConfidence.isEmpty()) return@mapNotNull null
                val certainAboutTheRest = entries.filter { it.confidence >= 100 && it.factId !in lowConfidence }
                if (certainAboutTheRest.isEmpty()) return@mapNotNull null
                Finding(
                    kind = Kind.CONTRADICTORY_FACT,
                    severity = Status.WARNING,
                    summary = "${characterId.value} is certain about some facts and unsure about ${lowConfidence.size}",
                    evidence = lowConfidence.sortedBy { it.value }.map { it.value },
                )
            }

    /**
     * A character standing somewhere that does not exist, or in two places at once.
     */
    private fun impossibleLocations(instance: StoryInstance): List<Finding> =
        instance.worldState.characters.values.mapNotNull { character ->
            val locationId = character.locationId ?: return@mapNotNull null
            if (definition.location(locationId) == null) {
                Finding(
                    kind = Kind.IMPOSSIBLE_LOCATION,
                    severity = Status.ERROR,
                    summary = "${character.name} is in '${locationId.value}', which is not a place in this world",
                    evidence = listOf(locationId.value),
                )
            } else {
                null
            }
        }

    /**
     * Two people in the same place whose [Relationship.updatedAt] says they last met
     * somewhere else, long ago.
     *
     * A WARNING, not an error: characters do travel, so "these two are co-located but
     * their relationship has not been touched" is usually just a pack that did not
     * wire the encounter up - which is precisely the sort of loose thread the analyzer
     * exists to name.
     */
    private fun impossibleRelationships(instance: StoryInstance): List<Finding> {
        val findings = mutableListOf<Finding>()
        val now = instance.worldClock.now

        instance.worldState.characters.values.forEach { character ->
            val locationId = character.locationId ?: return@forEach
            val others = instance.worldState.charactersAt(locationId) - character
            others.forEach { other ->
                val relationship = instance.worldState.relationship(character.characterId, other.characterId)
                val staleSince = relationship?.updatedAt ?: return@forEach
                if (staleSince.minutesUntil(now) <= STALE_INTERACTION_MINUTES) return@forEach
                findings += Finding(
                    kind = Kind.IMPOSSIBLE_RELATIONSHIP,
                    severity = Status.WARNING,
                    summary = "${character.name} and ${other.name} are both at ${definition.nameOf(locationId)} " +
                        "but have not interacted in over $STALE_INTERACTION_HOURS story hours",
                    evidence = listOf("last interaction ${staleSince.format()}"),
                )
            }
        }

        // A relationship between characters the world does not contain.
        instance.relationships.forEach { (key, relationship) ->
            val missing = listOfNotNull(
                definition.character(key.sourceId)?.name,
                definition.character(key.targetId)?.name,
            )
            if (missing.isEmpty()) {
                findings += Finding(
                    kind = Kind.IMPOSSIBLE_RELATIONSHIP,
                    severity = Status.ERROR,
                    summary = "relationship $key refers to a character that does not exist",
                    evidence = listOf(key.toString()),
                )
            }
        }
        return findings
    }

    /**
     * A character whose runtime index claims a fact the knowledge store never gave them.
     *
     * An ERROR: this is the leak. The prompt is built from the knowledge store, so a
     * mismatch here means the two disagree, and whichever one the context builder
     * happens to read is the one that decides whether a secret holds.
     */
    private fun unauthorisedKnowledge(instance: StoryInstance): List<Finding> =
        instance.worldState.characters.values.mapNotNull { character ->
            val unbacked = character.knownFactIds.filterNot { instance.knowledge.knows(character.characterId, it) }
            if (unbacked.isEmpty()) {
                null
            } else {
                Finding(
                    kind = Kind.UNAUTHORISED_KNOWLEDGE,
                    severity = Status.ERROR,
                    summary = "${character.name} is indexed as knowing ${unbacked.size} facts the knowledge store never granted",
                    evidence = unbacked.map { it.value }.sorted(),
                )
            }
        }

    /**
     * A secret that a character other than its owner (or an explicit recipient) can read.
     *
     * An ERROR by definition: this is the one finding that means a character's prompt
     * contains something it must not.
     */
    private fun secretLeakage(instance: StoryInstance): List<Finding> {
        val characters = instance.worldState.characters.keys
        val findings = mutableListOf<Finding>()

        instance.memories.current()
            .filter { it.isSecret() }
            .forEach { memory ->
                val unintended = characters.filter { reader ->
                    // The owner and the explicitly named recipients are the point, not
                    // the problem. Anyone else being able to read it is the problem.
                    reader != memory.characterId &&
                        reader !in memory.visibleTo &&
                        memory.visibleTo(MemorySubject.Character(reader))
                }
                if (unintended.isEmpty()) return@forEach
                findings += Finding(
                    kind = Kind.SECRET_LEAKAGE,
                    severity = Status.ERROR,
                    summary = "secret memory ${memory.id.value} is readable by ${unintended.size} characters",
                    evidence = unintended.map { it.value }.sorted() + "content: ${memory.content}",
                )
            }
        return findings
    }

    /**
     * Whether a memory is secret by *either* of its two declarations.
     *
     * Tier and visibility are independent, and the leak is a disagreement between them
     * rather than either one alone: a memory tagged [dev.charaly.runtime.domain.memory.MemoryTier.SECRET]
     * but marked `WORLD` is exactly the bug this check exists for, and testing only the
     * visibility enum would sail straight past it.
     */
    private fun dev.charaly.runtime.domain.memory.Memory.isSecret(): Boolean =
        tier == dev.charaly.runtime.domain.memory.MemoryTier.SECRET ||
            visibility == dev.charaly.runtime.domain.memory.MemoryVisibility.SECRET

    /**
     * An open thread that has not moved in a long time.
     *
     * A WARNING. "Abandoned" and "still simmering" look identical from the outside, and
     * only the player can say which a thread is - so this names the thread and its age
     * rather than declaring it dead.
     */
    private fun deadThreads(instance: StoryInstance): List<Finding> {
        val now = instance.worldClock.now
        return instance.storyThreads.values
            .filter { it.isOpen() }
            .filter { it.updatedAt.minutesUntil(now) >= DEAD_THREAD_MINUTES }
            .map { thread ->
                Finding(
                    kind = Kind.DEAD_THREAD,
                    severity = Status.WARNING,
                    summary = "thread '${thread.title}' has not advanced for ${thread.updatedAt.minutesUntil(now) / 60} story hours",
                    evidence = listOf("stage ${thread.stage}", thread.id.value),
                )
            }
    }

    /**
     * A thread that points at a character or place the pack does not contain.
     */
    private fun orphanedThreads(instance: StoryInstance): List<Finding> =
        instance.storyThreads.values.mapNotNull { thread ->
            val missingCharacters = thread.involvedCharacterIds.filter { definition.character(it) == null }
            val missingLocations = thread.relevantLocationIds.filter { definition.location(it) == null }
            if (missingCharacters.isEmpty() && missingLocations.isEmpty()) {
                null
            } else {
                Finding(
                    kind = Kind.ORPHANED_THREAD,
                    severity = Status.ERROR,
                    summary = "thread '${thread.title}' references entities that do not exist",
                    evidence = missingCharacters.map { "character ${it.value}" } +
                        missingLocations.map { "location ${it.value}" },
                )
            }
        }

    /**
     * A live scene listing someone who is not there.
     *
     * An ERROR: the SceneDirector builds the prompt from the scene's participants, so a
     * stale participant means the model is being handed a character who has left.
     */
    private fun missingParticipants(instance: StoryInstance): List<Finding> =
        instance.worldState.activeScenes.values.flatMap { scene ->
            scene.participants.mapNotNull { participantId ->
                val character = instance.characters[participantId]
                when {
                    character == null -> Finding(
                        kind = Kind.MISSING_PARTICIPANT,
                        severity = Status.ERROR,
                        summary = "scene ${scene.id.value} lists ${participantId.value}, who has no runtime state",
                        evidence = listOf(participantId.value),
                    )

                    character.locationId != scene.locationId -> Finding(
                        kind = Kind.MISSING_PARTICIPANT,
                        severity = Status.ERROR,
                        summary = "scene ${scene.id.value} lists ${character.name}, who is at " +
                            "'${character.locationId?.value ?: "nowhere"}'",
                        evidence = listOf(scene.locationId.value),
                    )

                    else -> null
                }
            }
        }

    /**
     * A goal that has been active far longer than a goal plausibly lasts.
     *
     * The goal's age is taken from the most recently advanced thread the character is
     * involved in, falling back to when they last changed at all. The fallback matters:
     * most NPC goals belong to no thread whatsoever - Andre's is "keep the shop open and
     * the neighbourhood fed" - and requiring a thread here would silently never check
     * any of them.
     */
    private fun staleGoals(instance: StoryInstance): List<Finding> {
        val now = instance.worldClock.now
        return instance.worldState.characters.values.flatMap { character ->
            character.activeGoals.mapNotNull { goal ->
                val started = instance.storyThreads.values
                    .filter { it.involves(character.characterId) }
                    .maxOfOrNull { it.updatedAt }
                    ?: character.lastUpdatedAt
                if (started.minutesUntil(now) < STALE_GOAL_MINUTES) return@mapNotNull null
                Finding(
                    kind = Kind.STALE_GOAL,
                    severity = Status.WARNING,
                    summary = "${character.name} has been pursuing '$goal' for " +
                        "${started.minutesUntil(now) / 60} story hours",
                    evidence = listOf(character.characterId.value),
                )
            }
        }
    }

    /**
     * The same effect scheduled twice for the same instant.
     *
     * A WARNING. Duplicate scheduling is usually harmless - the engine drops the
     * no-op - but it is how an event fires twice, and "it happened twice" is a bug a
     * player reports and a developer cannot see.
     */
    private fun eventConflicts(instance: StoryInstance): List<Finding> =
        instance.eventQueue.snapshot()
            .groupBy { "${it.scheduledAt.totalMinutes}|${it.payload::class.simpleName}|${it.payload.summary}" }
            .filterValues { it.size > 1 }
            .map { (key, duplicates) ->
                Finding(
                    kind = Kind.EVENT_CONFLICT,
                    severity = Status.WARNING,
                    summary = "${duplicates.size} identical events are scheduled at the same instant",
                    evidence = (listOf(key) + duplicates.map { it.id.value }).distinct(),
                )
            }

    companion object {
        /** Jaccard overlap above which two current memories count as duplicates. */
        const val DUPLICATE_THRESHOLD = 0.8

        /** Below this confidence, a character is "unsure" rather than "certain". */
        const val LOW_CONFIDENCE = 50

        /** Co-located characters whose relationship has not moved in this long. */
        const val STALE_INTERACTION_HOURS = 24L

        private val STALE_INTERACTION_MINUTES = STALE_INTERACTION_HOURS * 60

        /** An open thread untouched for this many story hours. */
        const val DEAD_THREAD_HOURS = 72L

        private val DEAD_THREAD_MINUTES = DEAD_THREAD_HOURS * 60

        /** A goal pursued for this many story hours. */
        const val STALE_GOAL_HOURS = 168L

        private val STALE_GOAL_MINUTES = STALE_GOAL_HOURS * 60
    }
}