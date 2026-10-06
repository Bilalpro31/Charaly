package dev.charaly.runtime.engine

import dev.charaly.runtime.domain.CharacterActivity
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.EntityId
import dev.charaly.runtime.domain.EventId
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.Relationship
import dev.charaly.runtime.domain.RelationshipKey
import dev.charaly.runtime.domain.Scene
import dev.charaly.runtime.domain.SceneState
import dev.charaly.runtime.domain.StoryDuration
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryThreadStatus
import dev.charaly.runtime.domain.StoryTime
import dev.charaly.runtime.domain.WorldDefinition
import dev.charaly.runtime.domain.WorldVariable
import dev.charaly.runtime.domain.events.CharacterActivityChanged
import dev.charaly.runtime.domain.events.CharacterEnteredScene
import dev.charaly.runtime.domain.events.CharacterLeftScene
import dev.charaly.runtime.domain.events.BeliefFormed
import dev.charaly.runtime.domain.events.ConsequenceArmed
import dev.charaly.runtime.domain.events.ConsequenceFired
import dev.charaly.runtime.domain.events.GoalUpdated
import dev.charaly.runtime.domain.events.CharacterMoved
import dev.charaly.runtime.domain.events.CharacterObserved
import dev.charaly.runtime.domain.events.MemoryUpdated
import dev.charaly.runtime.domain.events.MisconceptionCorrected
import dev.charaly.runtime.domain.events.MisconceptionFormed
import dev.charaly.runtime.domain.events.PromiseForgotten
import dev.charaly.runtime.domain.events.PromiseMade
import dev.charaly.runtime.domain.events.PromiseResolved
import dev.charaly.runtime.domain.events.SecretRevealed
import dev.charaly.runtime.domain.events.SuspicionRaised
import dev.charaly.runtime.domain.events.CharacterRoutineApplied
import dev.charaly.runtime.domain.events.EventOrigin
import dev.charaly.runtime.domain.events.LocationEntered
import dev.charaly.runtime.domain.events.EventPayload
import dev.charaly.runtime.domain.events.FactRevealed
import dev.charaly.runtime.domain.events.KnowledgeDiscovered
import dev.charaly.runtime.domain.events.KnowledgeRevoked
import dev.charaly.runtime.domain.events.MemoryCreated
import dev.charaly.runtime.domain.events.RelationshipChanged
import dev.charaly.runtime.domain.events.SceneEnded
import dev.charaly.runtime.domain.events.SceneStarted
import dev.charaly.runtime.domain.events.ScheduledEvent
import dev.charaly.runtime.domain.events.StoryThreadAdvanced
import dev.charaly.runtime.domain.events.TimeAdvanced
import dev.charaly.runtime.domain.events.WorldEvent
import dev.charaly.runtime.domain.events.WorldVariableSet
import dev.charaly.runtime.domain.knowledge.KnowledgeEntry

/** Why an event cannot be applied. Validation always runs *before* mutation. */
enum class EventRejection {
    UNKNOWN_CHARACTER,
    UNKNOWN_LOCATION,
    UNKNOWN_SCENE,
    UNKNOWN_THREAD,
    UNKNOWN_FACT,
    SELF_RELATIONSHIP,
    NO_OP_DELTA,
    ILLEGAL_MOVEMENT,
    NOT_CO_LOCATED,
    SCENE_ALREADY_ENDED,
    SCENE_ALREADY_EXISTS,
    TIME_NOT_MONOTONIC,
    INCONSISTENT_TIME,
    STALE_FROM_LOCATION,
    EMPTY_CHANGE,
    STAGE_REGRESSION,
    MISSING_CHARACTER_STATE,
    UNKNOWN_MEMORY,
    UNKNOWN_PROMISE,
    UNKNOWN_GOAL,
    UNKNOWN_CONSEQUENCE,
    ALREADY_RESOLVED,
    DUPLICATE_COMMITMENT,
}

data class EventValidation(
    val errors: List<EventRejection> = emptyList(),
    val notes: List<String> = emptyList(),
    /** Valid, but applying it would not change the world. */
    val isNoOp: Boolean = false,
) {
    val isValid: Boolean get() = errors.isEmpty()

    fun describe(): String = when {
        !isValid -> errors.joinToString("; ") { it.name.lowercase().replace('_', ' ') }
        notes.isEmpty() -> "valid"
        else -> notes.joinToString("; ")
    }

    companion object {
        val VALID = EventValidation()
        fun invalid(vararg errors: EventRejection) = EventValidation(errors = errors.toList())
    }
}

sealed interface ScheduleResult {
    data class Scheduled(val instance: StoryInstance, val events: List<ScheduledEvent>) : ScheduleResult
    data class Rejected(val event: ScheduledEvent?, val validation: EventValidation) : ScheduleResult
}

sealed interface EventApplication {
    /** The world either changed or deliberately did not; a new instance is returned. */
    data class Applied(
        val instance: StoryInstance,
        val event: ScheduledEvent,
        val changes: List<String>,
        val noOp: Boolean = false,
    ) : EventApplication

    data class Rejected(val event: ScheduledEvent, val validation: EventValidation) : EventApplication
}

sealed interface ProcessResult {
    data class Processed(
        val instance: StoryInstance,
        val applications: List<EventApplication.Applied>,
    ) : ProcessResult

    data class Stopped(
        val instance: StoryInstance,
        val reason: String,
        val rejected: List<EventApplication.Rejected>,
    ) : ProcessResult
}

data class EventBatchResult(
    val instance: StoryInstance,
    val applied: List<ScheduledEvent>,
    val rejected: List<RejectedEvent>,
) {
    val isClean: Boolean get() = rejected.isEmpty()

    data class RejectedEvent(val event: ScheduledEvent, val validation: EventValidation)
}

/**
 * Deterministic scheduling, validation and application of world events.
 *
 * The engine is a pure function of (WorldDefinition, StoryInstance, WorldEvent):
 * it keeps no mutable state of its own, so replaying the same event sequence
 * always produces the same world. That is what makes Charaly's world
 * authoritative *and* testable without a device, an emulator or a server.
 */
class EventEngine(val definition: WorldDefinition) {

    // ------------------------------------------------------------------
    // Scheduling
    // ------------------------------------------------------------------

    fun scheduleEvent(
        instance: StoryInstance,
        payload: EventPayload,
        at: StoryTime,
        origin: EventOrigin = EventOrigin.USER,
        note: String = "",
        causedBy: EventId? = null,
        because: dev.charaly.runtime.domain.CausalReason? = null,
    ): ScheduleResult {
        var counter = instance.idCounter
        val event = ScheduledEvent(
            id = EntityId("evt-${++counter}"),
            scheduledAt = instance.worldClock.scheduleAt(at),
            origin = origin,
            payload = payload,
            sequence = instance.eventQueue.nextSequence,
            note = note,
            causeId = causedBy,
            causeReason = because,
        )
        val queued = instance.copy(
            eventQueue = instance.eventQueue.enqueue(event),
            idCounter = counter,
        )
        return ScheduleResult.Scheduled(queued, listOf(event))
    }

    fun scheduleAfter(
        instance: StoryInstance,
        payload: EventPayload,
        by: StoryDuration,
        origin: EventOrigin = EventOrigin.USER,
        note: String = "",
        causedBy: EventId? = null,
        because: dev.charaly.runtime.domain.CausalReason? = null,
    ): ScheduleResult = scheduleEvent(
        instance,
        payload,
        instance.worldClock.scheduleAfter(by),
        origin,
        note,
        causedBy,
        because,
    )

    /**
     * Schedules a payload as a consequence of the event currently being applied.
     *
     * The one way to create a causal chain: the cause is recorded at *scheduling* time
     * rather than guessed at apply time, because by the time the effect runs the cause
     * has usually left the queue and nothing would be left to attribute it to.
     */
    fun scheduleAsConsequenceOf(
        instance: StoryInstance,
        payload: EventPayload,
        cause: EventId,
        because: dev.charaly.runtime.domain.CausalReason,
        by: StoryDuration = StoryDuration.ZERO,
        note: String = "",
    ): ScheduleResult = scheduleAfter(
        instance = instance,
        payload = payload,
        by = by,
        origin = EventOrigin.ENGINE,
        note = note,
        causedBy = cause,
        because = because,
    )

    fun scheduleAll(
        instance: StoryInstance,
        payloads: List<EventPayload>,
        at: StoryTime,
        origin: EventOrigin = EventOrigin.USER,
    ): ScheduleResult {
        var current = instance
        val events = mutableListOf<ScheduledEvent>()
        payloads.forEach { payload ->
            when (val result = scheduleEvent(current, payload, at, origin)) {
                is ScheduleResult.Scheduled -> {
                    current = result.instance
                    events += result.events
                }
                is ScheduleResult.Rejected -> return result
            }
        }
        return ScheduleResult.Scheduled(current, events)
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    fun validateEvent(instance: StoryInstance, event: WorldEvent): EventValidation =
        validatePayload(instance, event.payload, eventOriginNote = null)

    private fun validatePayload(
        instance: StoryInstance,
        payload: EventPayload,
        eventOriginNote: String?,
    ): EventValidation {
        val errors = mutableListOf<EventRejection>()
        val notes = mutableListOf<String>()
        var noOp = false
        if (eventOriginNote != null) notes += eventOriginNote

        fun runtimeOf(id: CharacterId) = instance.characters[id].also {
            if (it == null && definition.character(id) != null) errors += EventRejection.MISSING_CHARACTER_STATE
        }

        when (payload) {
            is CharacterMoved -> {
                if (definition.character(payload.characterId) == null) errors += EventRejection.UNKNOWN_CHARACTER
                val to = definition.location(payload.to)
                if (to == null) errors += EventRejection.UNKNOWN_LOCATION
                val runtime = runtimeOf(payload.characterId)
                if (runtime != null && to != null && !definition.isAdjacent(runtime.locationId, payload.to)) {
                    errors += EventRejection.ILLEGAL_MOVEMENT
                }
                if (runtime != null && runtime.locationId == payload.to) {
                    notes += "already at ${payload.to.value}"
                    noOp = true
                }
                if (runtime != null && payload.from != null && runtime.locationId != payload.from) {
                    errors += EventRejection.STALE_FROM_LOCATION
                }
            }

            is CharacterEnteredScene -> {
                if (definition.character(payload.characterId) == null) errors += EventRejection.UNKNOWN_CHARACTER
                val scene = instance.worldState.activeScenes[payload.sceneId]
                if (scene == null) errors += EventRejection.UNKNOWN_SCENE
                if (scene != null && !scene.isActive()) errors += EventRejection.SCENE_ALREADY_ENDED
                val runtime = runtimeOf(payload.characterId)
                if (runtime != null && scene != null && runtime.locationId != scene.locationId) {
                    errors += EventRejection.NOT_CO_LOCATED
                }
                if (scene != null && payload.characterId in scene.participants) {
                    notes += "${payload.characterId.value} is already a participant"
                    noOp = true
                }
            }

            is CharacterLeftScene -> {
                if (definition.character(payload.characterId) == null) errors += EventRejection.UNKNOWN_CHARACTER
                val scene = instance.worldState.activeScenes[payload.sceneId]
                if (scene == null) errors += EventRejection.UNKNOWN_SCENE
                if (scene != null && payload.characterId !in scene.participants) {
                    notes += "${payload.characterId.value} was not a participant"
                    noOp = true
                }
            }

            is RelationshipChanged -> {
                if (definition.character(payload.sourceId) == null) errors += EventRejection.UNKNOWN_CHARACTER
                if (definition.character(payload.targetId) == null) errors += EventRejection.UNKNOWN_CHARACTER
                if (payload.sourceId == payload.targetId) errors += EventRejection.SELF_RELATIONSHIP
                if (payload.delta.isZero) errors += EventRejection.NO_OP_DELTA
            }

            is KnowledgeDiscovered -> {
                if (definition.character(payload.characterId) == null) errors += EventRejection.UNKNOWN_CHARACTER
                if (instance.knowledge.fact(payload.factId) == null) errors += EventRejection.UNKNOWN_FACT
                if (instance.knowledge.knows(payload.characterId, payload.factId)) {
                    notes += "${payload.characterId.value} already knows ${payload.factId.value}"
                    noOp = true
                }
            }

            is KnowledgeRevoked -> {
                if (definition.character(payload.characterId) == null) errors += EventRejection.UNKNOWN_CHARACTER
                if (instance.knowledge.fact(payload.factId) == null) errors += EventRejection.UNKNOWN_FACT
                if (!instance.knowledge.knows(payload.characterId, payload.factId)) {
                    notes += "${payload.characterId.value} did not know ${payload.factId.value}"
                    noOp = true
                }
            }

            is MemoryCreated -> {
                if (definition.character(payload.memory.characterId) == null) {
                    errors += EventRejection.UNKNOWN_CHARACTER
                }
                if (payload.memory.content.isBlank()) errors += EventRejection.EMPTY_CHANGE
            }

            is StoryThreadAdvanced -> {
                val thread = instance.storyThreads[payload.threadId]
                if (thread == null) {
                    errors += EventRejection.UNKNOWN_THREAD
                } else {
                    if (payload.stage < thread.stage) errors += EventRejection.STAGE_REGRESSION
                    if (payload.stage == thread.stage && payload.status == null) {
                        notes += "thread already at stage ${thread.stage}"
                        noOp = true
                    }
                }
            }

            is SceneStarted -> {
                if (instance.worldState.activeScenes.containsKey(payload.sceneId)) {
                    errors += EventRejection.SCENE_ALREADY_EXISTS
                }
                if (definition.location(payload.locationId) == null) errors += EventRejection.UNKNOWN_LOCATION
                payload.participants.forEach { participantId ->
                    if (definition.character(participantId) == null) errors += EventRejection.UNKNOWN_CHARACTER
                    val runtime = runtimeOf(participantId)
                    if (runtime != null && runtime.locationId != payload.locationId) {
                        errors += EventRejection.NOT_CO_LOCATED
                    }
                }
                if (payload.participants.isEmpty()) notes += "scene starts with no participants"
                payload.activeThreadIds.forEach { threadId ->
                    if (instance.storyThreads[threadId] == null) errors += EventRejection.UNKNOWN_THREAD
                }
            }

            is SceneEnded -> {
                val scene = instance.worldState.activeScenes[payload.sceneId]
                if (scene == null) errors += EventRejection.UNKNOWN_SCENE
                if (scene != null && !scene.isActive()) errors += EventRejection.SCENE_ALREADY_ENDED
            }

            is TimeAdvanced -> {
                if (payload.by.minutes <= 0L) errors += EventRejection.EMPTY_CHANGE
                if (payload.to.isBefore(instance.worldClock.now)) errors += EventRejection.TIME_NOT_MONOTONIC
                val expected = instance.worldClock.now.plusMinutes(payload.by.minutes)
                if (payload.to != expected) errors += EventRejection.INCONSISTENT_TIME
            }

            is CharacterActivityChanged -> {
                if (definition.character(payload.characterId) == null) errors += EventRejection.UNKNOWN_CHARACTER
                runtimeOf(payload.characterId)
            }

            is CharacterRoutineApplied -> {
                if (definition.character(payload.characterId) == null) {
                    errors += EventRejection.UNKNOWN_CHARACTER
                }
                if (definition.location(payload.to) == null) errors += EventRejection.UNKNOWN_LOCATION
                val runtime = runtimeOf(payload.characterId)
                // A stale `from` means the world moved on since this was computed, so
                // the routine placement is based on a world that no longer exists.
                if (runtime != null && runtime.locationId != payload.from) {
                    errors += EventRejection.STALE_FROM_LOCATION
                }
                // Nothing to do when the character is already where the routine says.
                if (runtime != null && runtime.locationId == payload.to &&
                    runtime.activity == payload.activity &&
                    runtime.activityLabel == payload.activityLabel
                ) {
                    notes += "${payload.characterId.value} is already following this part of their routine"
                    noOp = true
                }
            }

            is LocationEntered -> {
                if (definition.location(payload.locationId) == null) errors += EventRejection.UNKNOWN_LOCATION
                if (payload.sceneId != null && instance.worldState.activeScenes[payload.sceneId] == null) {
                    errors += EventRejection.UNKNOWN_SCENE
                }
                val already = currentPlayerLocation(instance)
                if (already == payload.locationId) {
                    notes += "the player is already at ${payload.locationId.value}"
                    noOp = true
                }
            }

            is WorldVariableSet -> {
                if (payload.key.isBlank()) errors += EventRejection.EMPTY_CHANGE
            }

            is FactRevealed -> Unit

            is SecretRevealed -> {
                if (definition.character(payload.revealedBy) == null) errors += EventRejection.UNKNOWN_CHARACTER
                if (instance.knowledge.fact(payload.factId) == null) errors += EventRejection.UNKNOWN_FACT
                if (payload.recipients.isEmpty()) {
                    // A reveal to nobody is a contradiction: a secret that came out has
                    // an audience, and letting this through would register the fact as
                    // revealed while teaching it to no one.
                    errors += EventRejection.EMPTY_CHANGE
                }
                payload.recipients.forEach {
                    if (definition.character(it) == null) errors += EventRejection.UNKNOWN_CHARACTER
                }
                if (payload.recipients.distinct().size != payload.recipients.size) {
                    errors += EventRejection.EMPTY_CHANGE
                }
                val alreadyKnown = payload.recipients.count { instance.knowledge.knows(it, payload.factId) }
                if (payload.recipients.isNotEmpty() && alreadyKnown == payload.recipients.size) {
                    notes += "everyone it was revealed to already knew"
                    noOp = true
                }
            }

            is CharacterObserved -> {
                if (definition.character(payload.characterId) == null) errors += EventRejection.UNKNOWN_CHARACTER
                if (payload.description.isBlank()) errors += EventRejection.EMPTY_CHANGE
                if (payload.locationId != null && definition.location(payload.locationId) == null) {
                    errors += EventRejection.UNKNOWN_LOCATION
                }
                // An observation is a record of something that happened, so it is
                // always a change: re-witnessing the same thing at the same moment is
                // folded into the existing record rather than refused, because refusing
                // it would mean a character who watches something twice somehow
                // witnessed it only once.
                //
                // The dedup key is spelled out here rather than by constructing an
                // Observation, because constructing one runs its own precondition - and
                // validation must not throw on malformed input, it must reject it.
                val key = dev.charaly.runtime.domain.knowledge.Observation.dedupKey(
                    description = payload.description,
                    at = instance.worldClock.now,
                    locationId = payload.locationId,
                )
                if (instance.knowledge.mind(payload.characterId).observations.any { it.dedupKey() == key }) {
                    notes += "${payload.characterId.value} has already recorded this"
                }
            }

            is BeliefFormed -> {
                if (definition.character(payload.characterId) == null) errors += EventRejection.UNKNOWN_CHARACTER
                if (payload.subject.isBlank() || payload.claim.isBlank()) errors += EventRejection.EMPTY_CHANGE
                if (payload.confidence !in 0..100) errors += EventRejection.INCONSISTENT_TIME
                val existing = instance.knowledge.mind(payload.characterId).beliefAbout(payload.subject)
                if (existing != null && existing.claim.equals(payload.claim, ignoreCase = true) &&
                    existing.confidence == payload.confidence
                ) {
                    notes += "${payload.characterId.value} already believes exactly that"
                    noOp = true
                }
            }

            is SuspicionRaised -> {
                if (definition.character(payload.characterId) == null) errors += EventRejection.UNKNOWN_CHARACTER
                if (payload.subject.isBlank() || payload.claim.isBlank()) errors += EventRejection.EMPTY_CHANGE
                if (payload.strength !in 0..100) errors += EventRejection.INCONSISTENT_TIME
                val existing = instance.knowledge.mind(payload.characterId).suspicionAbout(payload.subject)
                if (existing != null && existing.strength == payload.strength) {
                    notes += "${payload.characterId.value} is already exactly this suspicious"
                    noOp = true
                }
            }

            is MisconceptionFormed -> {
                if (definition.character(payload.characterId) == null) errors += EventRejection.UNKNOWN_CHARACTER
                if (payload.subject.isBlank() || payload.claim.isBlank()) errors += EventRejection.EMPTY_CHANGE
                // The truth is not optional. Without it this is not a recorded error but
                // an unlabelable falsehood, which no later reveal could ever find.
                if (payload.truth.isBlank()) errors += EventRejection.EMPTY_CHANGE
                // A character cannot be wrong about something that is true.
                if (payload.claim.trim().equals(payload.truth.trim(), ignoreCase = true)) {
                    errors += EventRejection.EMPTY_CHANGE
                }
            }

            is MisconceptionCorrected -> {
                if (definition.character(payload.characterId) == null) errors += EventRejection.UNKNOWN_CHARACTER
                if (payload.subject.isBlank()) errors += EventRejection.EMPTY_CHANGE
                val mind = instance.knowledge.mind(payload.characterId)
                // A correction is a no-op only when there is nothing to correct. An
                // answer to a standing *suspicion* counts, because a character who was
                // wondering whether the new student was hiding something has just been
                // told, and refusing that would leave them wondering forever.
                if (mind.misconceptionAbout(payload.subject) == null &&
                    mind.suspicionAbout(payload.subject) == null
                ) {
                    notes += "${payload.characterId.value} held nothing to correct about ${payload.subject}"
                    noOp = true
                }
            }

            is MemoryUpdated -> {
                val existing = instance.memories.byId(payload.memoryId)
                if (existing == null) errors += EventRejection.UNKNOWN_MEMORY
                if (payload.at.isBefore(instance.worldClock.now)) errors += EventRejection.TIME_NOT_MONOTONIC
                if (existing != null && !existing.isCurrent) {
                    notes += "memory ${payload.memoryId.value} is already history"
                    noOp = true
                }
            }

            is PromiseMade -> {
                if (definition.character(payload.keeperId) == null) errors += EventRejection.UNKNOWN_CHARACTER
                if (definition.character(payload.beneficiaryId) == null) errors += EventRejection.UNKNOWN_CHARACTER
                // A promise to oneself is a plan. Refusing it here keeps the ledger
                // meaningful: every entry has somebody who is waiting on it.
                if (payload.keeperId == payload.beneficiaryId) errors += EventRejection.SELF_RELATIONSHIP
                if (payload.text.isBlank()) errors += EventRejection.EMPTY_CHANGE
                if (payload.dueAtMinuteOfDay !in -1..1439) errors += EventRejection.EMPTY_CHANGE
                if (instance.worldState.commitments.promise(payload.promiseId) != null) {
                    errors += EventRejection.DUPLICATE_COMMITMENT
                }
            }

            is PromiseResolved -> {
                val promise = instance.worldState.commitments.promise(payload.promiseId)
                if (promise == null) errors += EventRejection.UNKNOWN_PROMISE
                // Resolving something twice is not a no-op, it is a contradiction: the
                // ledger would have to show both that a promise was kept and broken.
                if (payload.status.isOpen) errors += EventRejection.EMPTY_CHANGE
                if (promise != null && promise.status.isResolved) errors += EventRejection.ALREADY_RESOLVED
                payload.witnessedBy?.let {
                    if (definition.character(it) == null) errors += EventRejection.UNKNOWN_CHARACTER
                }
            }

            is PromiseForgotten -> {
                val promise = instance.worldState.commitments.promise(payload.promiseId)
                if (promise == null) errors += EventRejection.UNKNOWN_PROMISE
                if (promise != null && !promise.rememberedByKeeper) {
                    notes += "the keeper already forgot promise ${payload.promiseId.value}"
                    noOp = true
                }
            }

            is GoalUpdated -> {
                if (definition.character(payload.ownerId) == null) errors += EventRejection.UNKNOWN_CHARACTER
                if (payload.text.isBlank() &&
                    instance.worldState.commitments.goal(payload.goalId) == null
                ) {
                    errors += EventRejection.EMPTY_CHANGE
                }
                val existing = instance.worldState.commitments.goal(payload.goalId)
                if (existing != null && payload.progressDelta == 0 && payload.status == null) {
                    notes += "nothing to change about goal ${payload.goalId.value}"
                    noOp = true
                }
                if (existing != null && existing.status.isResolved) {
                    errors += EventRejection.ALREADY_RESOLVED
                }
            }

            is ConsequenceArmed -> {
                if (definition.character(payload.decidedBy) == null) errors += EventRejection.UNKNOWN_CHARACTER
                if (payload.decision.isBlank() || payload.outcome.isBlank()) errors += EventRejection.EMPTY_CHANGE
                if (payload.delayMinutes < 0L) errors += EventRejection.EMPTY_CHANGE
                payload.requiresPresenceOf?.let {
                    if (definition.character(it) == null) errors += EventRejection.UNKNOWN_CHARACTER
                }
                payload.requiresLocationId?.let {
                    if (definition.location(it) == null) errors += EventRejection.UNKNOWN_LOCATION
                }
                if (instance.worldState.commitments.consequence(payload.consequenceId) != null) {
                    errors += EventRejection.DUPLICATE_COMMITMENT
                }
            }

            is ConsequenceFired -> {
                val consequence = instance.worldState.commitments.consequence(payload.consequenceId)
                if (consequence == null) errors += EventRejection.UNKNOWN_CONSEQUENCE
                if (consequence != null && consequence.resolved) {
                    notes += "consequence ${payload.consequenceId.value} has already landed"
                    noOp = true
                }
            }
        }

        return EventValidation(
            errors = errors.distinct(),
            notes = notes.distinct(),
            // An event that would not change anything is only a no-op if it is also
            // free of errors.
            isNoOp = errors.isEmpty() && noOp,
        )
    }

    // ------------------------------------------------------------------
    // Application (pure reducer)
    // ------------------------------------------------------------------

    fun applyEvent(instance: StoryInstance, event: WorldEvent): EventApplication {
        val validation = validateEvent(instance, event)
        val scheduled = event as? ScheduledEvent
            ?: return EventApplication.Rejected(
                event = ScheduledEvent(
                    id = EntityId("evt-foreign"),
                    scheduledAt = event.scheduledAt,
                    origin = event.origin,
                    payload = event.payload,
                    sequence = 0L,
                ),
                validation = EventValidation.invalid(EventRejection.EMPTY_CHANGE),
            )

        if (!validation.isValid) return EventApplication.Rejected(scheduled, validation)

        var current = instance.evolved(eventQueue = instance.eventQueue.remove(scheduled.id))
        val changes = mutableListOf<String>()
        var stillActive = false

        if (validation.isNoOp) {
            val recorded = current.evolved(
                worldState = current.worldState.recordEvent(scheduled.id, stillActive = false),
            )
            return EventApplication.Applied(
                instance = recorded,
                event = scheduled,
                changes = listOf("no change: " + validation.notes.joinToString("; ")),
                noOp = true,
            )
        }

        when (val payload = scheduled.payload) {
            is CharacterMoved -> {
                val runtime = current.characters.getValue(payload.characterId)
                val from = runtime.locationId
                current = current.evolved(
                    worldState = current.worldState.withCharacter(
                        runtime.copy(
                            locationId = payload.to,
                            activity = payload.arrivalActivity,
                            lastUpdatedAt = current.worldClock.now,
                        ),
                    ),
                )
                // Leaving a place means leaving the scenes that happened there.
                current.worldState.activeScenes.values
                    .filter { from != null && it.locationId == from && payload.characterId in it.participants }
                    .forEach { scene ->
                        current = current.evolved(
                            worldState = current.worldState
                                .withScene(scene.copy(participants = scene.participants - payload.characterId))
                                .withCharacter(
                                    current.characters.getValue(payload.characterId)
                                        .leaveScene(scene.id, current.worldClock.now),
                                ),
                        )
                        changes += "${payload.characterId.value} left scene ${scene.id.value} (moved away)"
                    }
                changes += "${runtime.name} moved to ${definition.nameOf(payload.to)}"
            }

            is CharacterEnteredScene -> {
                val scene = current.worldState.activeScenes.getValue(payload.sceneId)
                val runtime = current.characters.getValue(payload.characterId)
                current = current.evolved(
                    worldState = current.worldState
                        .withScene(
                            scene.copy(
                                participants = scene.participants + payload.characterId,
                                focusCharacterId = scene.focusCharacterId ?: payload.characterId,
                            ),
                        )
                        .withCharacter(runtime.enterScene(scene.id, current.worldClock.now)),
                    currentSceneId = current.currentSceneId ?: scene.id,
                )
                stillActive = true
                changes += "${runtime.name} entered scene ${scene.id.value}"
            }

            is CharacterLeftScene -> {
                val scene = current.worldState.activeScenes.getValue(payload.sceneId)
                val runtime = current.characters.getValue(payload.characterId)
                current = current.evolved(
                    worldState = current.worldState
                        .withScene(scene.copy(participants = scene.participants - payload.characterId))
                        .withCharacter(runtime.leaveScene(scene.id, current.worldClock.now)),
                )
                changes += "${runtime.name} left scene ${scene.id.value}"
            }

            is RelationshipChanged -> {
                val existing = current.relationships[RelationshipKey(payload.sourceId, payload.targetId)]
                    ?: Relationship(sourceId = payload.sourceId, targetId = payload.targetId)
                current = current.evolved(
                    worldState = current.worldState.withRelationship(
                        existing.apply(payload.delta, payload.relationshipType, payload.reason, current.worldClock.now),
                    ),
                )
                changes += "relationship ${payload.sourceId.value} -> ${payload.targetId.value} ${payload.delta}"
            }

            is KnowledgeDiscovered -> {
                current = current.evolved(
                    knowledge = current.knowledge.withKnowledge(
                        payload.characterId,
                        listOf(
                            KnowledgeEntry(
                                factId = payload.factId,
                                learnedAt = current.worldClock.now,
                                confidence = payload.confidence,
                                via = payload.via,
                            ),
                        ),
                    ),
                )
                val runtime = current.characters[payload.characterId]
                if (runtime != null && !runtime.knows(payload.factId)) {
                    current = current.evolved(
                        worldState = current.worldState.withCharacter(
                            runtime.copy(
                                knownFactIds = runtime.knownFactIds + payload.factId,
                                lastUpdatedAt = current.worldClock.now,
                            ),
                        ),
                    )
                }
                changes += "${payload.characterId.value} learned ${payload.factId.value}"
            }

            is KnowledgeRevoked -> {
                current = current.evolved(knowledge = current.knowledge.forget(payload.characterId, payload.factId))
                val runtime = current.characters[payload.characterId]
                if (runtime != null && runtime.knows(payload.factId)) {
                    current = current.evolved(
                        worldState = current.worldState.withCharacter(
                            runtime.copy(
                                knownFactIds = runtime.knownFactIds - payload.factId,
                                lastUpdatedAt = current.worldClock.now,
                            ),
                        ),
                    )
                }
                changes += "${payload.characterId.value} forgot ${payload.factId.value}"
            }

            is MemoryCreated -> {
                current = current.evolved(memories = current.memories.add(payload.memory))
                val runtime = current.characters[payload.memory.characterId]
                if (runtime != null && payload.memory.id !in runtime.memoryIds) {
                    current = current.evolved(
                        worldState = current.worldState.withCharacter(
                            runtime.copy(
                                memoryIds = runtime.memoryIds + payload.memory.id,
                                lastUpdatedAt = current.worldClock.now,
                            ),
                        ),
                    )
                }
                changes += "memory ${payload.memory.id.value} stored for ${payload.memory.characterId.value}"
            }

            is StoryThreadAdvanced -> {
                val thread = current.storyThreads.getValue(payload.threadId)
                val progresses = payload.stage > thread.stage
                val updated = thread.copy(
                    stage = maxOf(thread.stage, payload.stage),
                    status = payload.status
                        ?: if (progresses) StoryThreadStatus.ACTIVE else thread.status,
                    state = if (payload.note.isBlank()) thread.state else thread.state + ("stage" to payload.note),
                    updatedAt = current.worldClock.now,
                )
                current = current.evolved(worldState = current.worldState.withThread(updated))
                changes += "thread '${thread.title}' -> stage ${updated.stage} (${updated.status})"
            }

            is SceneStarted -> {
                val scene = Scene(
                    id = payload.sceneId,
                    locationId = payload.locationId,
                    participants = payload.participants,
                    focusCharacterId = payload.participants.firstOrNull(),
                    activeThreadIds = payload.activeThreadIds,
                    objective = payload.objective,
                    startedAt = current.worldClock.now,
                )
                current = current.evolved(
                    worldState = current.worldState.withScene(scene),
                    currentSceneId = scene.id,
                )
                payload.participants.forEach { participantId ->
                    val runtime = current.characters[participantId]
                    if (runtime != null) {
                        current = current.evolved(
                            worldState = current.worldState.withCharacter(
                                runtime.enterScene(scene.id, current.worldClock.now),
                            ),
                        )
                    }
                }
                stillActive = true
                changes += "scene ${scene.id.value} started at ${definition.nameOf(payload.locationId)}"
            }

            is SceneEnded -> {
                val scene = current.worldState.activeScenes.getValue(payload.sceneId)
                val ended = scene.copy(state = SceneState.ENDED, endedAt = current.worldClock.now)
                var worldState = current.worldState.copy(
                    activeScenes = current.worldState.activeScenes - scene.id,
                    activeEvents = current.worldState.activeEvents - scene.id,
                )
                scene.participants.forEach { participantId ->
                    val runtime = current.characters[participantId]
                    if (runtime != null) {
                        worldState = worldState.withCharacter(runtime.leaveScene(scene.id, current.worldClock.now))
                    }
                }
                current = current.evolved(
                    worldState = worldState,
                    currentSceneId = if (current.currentSceneId == scene.id) null else current.currentSceneId,
                )
                changes += "scene ${scene.id.value} ended (${payload.reason.ifBlank { "no reason given" }})"
            }

            is TimeAdvanced -> {
                current = current.evolved(
                    worldState = current.worldState.copy(
                        worldClock = current.worldState.worldClock.advanceTo(payload.to),
                    ),
                )
                changes += "clock -> ${current.worldClock.now.format()} (+${payload.by.minutes}m)"
            }

            is CharacterActivityChanged -> {
                val runtime = current.characters.getValue(payload.characterId)
                current = current.evolved(
                    worldState = current.worldState.withCharacter(
                        runtime.copy(
                            activity = payload.activity,
                            activityLabel = payload.activityLabel.ifBlank { runtime.activityLabel },
                            mood = payload.mood.ifBlank { runtime.mood },
                            lastUpdatedAt = current.worldClock.now,
                        ),
                    ),
                )
                changes += "${runtime.name} activity -> ${payload.activity.name.lowercase()}"
            }

            is LocationEntered -> {
                // Recorded as a world variable so the player's position is ordinary,
                // inspectable world state that pack events can condition on - the same
                // mechanism any other flag uses.
                current = current.evolved(
                    worldState = current.worldState.withVariable(
                        (current.worldState.variables[PLAYER_LOCATION]
                            ?: WorldVariable(
                                key = PLAYER_LOCATION,
                                description = "Where the player currently is",
                            )).copy(value = payload.locationId.value),
                    ),
                )
                changes += "the player entered ${definition.nameOf(payload.locationId)}"
            }

            is CharacterRoutineApplied -> {
                val runtime = current.characters.getValue(payload.characterId)
                val from = runtime.locationId
                current = current.evolved(
                    worldState = current.worldState.withCharacter(
                        runtime.copy(
                            locationId = payload.to,
                            activity = payload.activity,
                            activityLabel = payload.activityLabel,
                            // Which entry of the daily schedule put them here.
                            //
                            // This has to be written by the reducer, not just carried on
                            // the payload. `PresenceEngine` compares the resolved entry
                            // against this field to decide whether a character's schedule
                            // has actually moved on, so leaving it at its -1 default meant
                            // `entryMoved` was true forever: the "a routine is a default,
                            // not a leash" guard could never suppress a redundant
                            // relocation, and no screen could report where somebody was
                            // placed from. Recording it here is what makes the guard and
                            // the developer inspector both work.
                            routineEntryMinute = payload.routineEntryMinute,
                            lastUpdatedAt = current.worldClock.now,
                        ),
                    ),
                )
                // Leaving a place means leaving the scenes that happened there.
                current.worldState.activeScenes.values
                    .filter { from != null && it.locationId == from && payload.characterId in it.participants }
                    .forEach { scene ->
                        current = current.evolved(
                            worldState = current.worldState
                                .withScene(scene.copy(participants = scene.participants - payload.characterId))
                                .withCharacter(
                                    current.characters.getValue(payload.characterId)
                                        .leaveScene(scene.id, current.worldClock.now),
                                ),
                        )
                        changes += "${payload.characterId.value} left scene ${scene.id.value} (routine)"
                    }
                changes += "${runtime.name} routine -> ${definition.nameOf(payload.to)}" +
                    if (payload.activityLabel.isNotBlank()) " (${payload.activityLabel})" else ""
            }

            is WorldVariableSet -> {
                val existing = current.worldState.variables[payload.key]
                val variable = (existing ?: WorldVariable(key = payload.key)).copy(value = payload.value)
                current = current.evolved(worldState = current.worldState.withVariable(variable))
                changes += "variable ${payload.key} = ${payload.value}"
            }

            is FactRevealed -> {
                current = current.evolved(knowledge = current.knowledge.withFact(payload.fact))
                changes += "fact ${payload.fact.id.value} added to world truth"
            }

            is CharacterObserved -> {
                current = current.evolved(
                    knowledge = current.knowledge.updateMind(payload.characterId) { mind ->
                        mind.withObservation(
                            dev.charaly.runtime.domain.knowledge.Observation(
                                description = payload.description,
                                at = current.worldClock.now,
                                locationId = payload.locationId,
                                witnesses = payload.witnesses,
                                sourceEventId = scheduled.id,
                            ),
                        )
                    },
                )
                changes += "${payload.characterId.value} observed '${payload.description}'"
            }

            is BeliefFormed -> {
                current = current.evolved(
                    knowledge = current.knowledge.updateMind(payload.characterId) { mind ->
                        mind.withBelief(
                            dev.charaly.runtime.domain.knowledge.Belief(
                                subject = payload.subject,
                                claim = payload.claim,
                                confidence = payload.confidence.coerceIn(0, 100),
                                at = current.worldClock.now,
                                via = payload.via,
                                locationId = payload.locationId,
                            ),
                        )
                    },
                )
                changes += "${payload.characterId.value} believes '${payload.claim}' (${payload.confidence}%)"
            }

            is SuspicionRaised -> {
                current = current.evolved(
                    knowledge = current.knowledge.updateMind(payload.characterId) { mind ->
                        mind.withSuspicion(
                            dev.charaly.runtime.domain.knowledge.Suspicion(
                                subject = payload.subject,
                                claim = payload.claim,
                                strength = payload.strength.coerceIn(0, 100),
                                at = current.worldClock.now,
                                via = payload.via,
                            ),
                        )
                    },
                )
                changes += "${payload.characterId.value} suspects '${payload.claim}' (${payload.strength}%)"
            }

            is MisconceptionFormed -> {
                current = current.evolved(
                    knowledge = current.knowledge.updateMind(payload.characterId) { mind ->
                        mind.withMisconception(
                            dev.charaly.runtime.domain.knowledge.Misconception(
                                subject = payload.subject,
                                claim = payload.claim,
                                truth = payload.truth,
                                at = current.worldClock.now,
                                via = payload.via,
                            ),
                        )
                    },
                )
                changes += "${payload.characterId.value} is wrong about ${payload.subject} (in fact: ${payload.truth})"
            }

            is MisconceptionCorrected -> {
                val mind = current.knowledge.mind(payload.characterId)
                val misconception = mind.misconceptionAbout(payload.subject)
                current = current.evolved(
                    knowledge = current.knowledge.updateMind(payload.characterId) { existing ->
                        var next = existing.copy(
                            // Retire the error rather than editing it: the story having
                            // held a false belief is itself information, and quietly
                            // deleting it makes the correction look like nothing happened.
                            misconceptions = existing.misconceptions
                                .filterNot { it.subject.equals(payload.subject, ignoreCase = true) },
                        )
                        val corrected = payload.correctedBelief.ifBlank { misconception?.truth.orEmpty() }
                        if (corrected.isNotBlank()) {
                            next = next.withBelief(
                                dev.charaly.runtime.domain.knowledge.Belief(
                                    subject = payload.subject,
                                    claim = corrected,
                                    confidence = payload.confidence.coerceIn(0, 100),
                                    at = current.worldClock.now,
                                    via = payload.via,
                                ),
                            )
                        }
                        // A correction resolves whatever suspicion produced the error:
                        // leaving it in place means the character is still wondering about
                        // something the story has just answered for them.
                        next = next.copy(
                            suspicions = next.suspicions.filterNot {
                                it.subject.equals(payload.subject, ignoreCase = true)
                            },
                        )
                        next
                    },
                )
                changes += "${payload.characterId.value}'s belief about ${payload.subject} is corrected"
            }

            is MemoryUpdated -> {
                val existing = current.memories.byId(payload.memoryId)!!
                current = current.evolved(
                    memories = current.memories.update(payload.memoryId) {
                        it.copy(
                            accessCount = it.accessCount + 1,
                            supersededBy = null,
                            validUntil = null,
                        )
                    },
                )
                changes += "memory ${existing.id.value} refreshed${if (payload.reason.isBlank()) "" else " (${payload.reason})"}"
            }

            is PromiseMade -> {
                val promise = dev.charaly.runtime.domain.Promise(
                    id = payload.promiseId,
                    text = payload.text,
                    keeperId = payload.keeperId,
                    beneficiaryId = payload.beneficiaryId,
                    madeAt = current.worldClock.now,
                    dueAtMinuteOfDay = payload.dueAtMinuteOfDay,
                    dueOnDay = payload.dueOnDay,
                    locationId = payload.locationId,
                )
                current = current.evolved(
                    worldState = current.worldState.withCommitments(
                        current.worldState.commitments.withPromise(promise),
                    ),
                )
                // The beneficiary learns they have been promised something. Without this
                // the promise exists in the world and in nobody's head, which is the one
                // outcome that makes the whole feature pointless.
                val promisedAt = current.worldClock.now
                current = remember(
                    current,
                    "promise-${payload.promiseId.value}",
                    { memoryId ->
                        dev.charaly.runtime.domain.memory.Memory(
                            id = memoryId,
                            characterId = payload.beneficiaryId,
                            content = "you were promised: ${payload.text}",
                            importance = 4,
                            createdAt = promisedAt,
                            source = dev.charaly.runtime.domain.memory.MemorySource.EVENT,
                            tier = dev.charaly.runtime.domain.memory.MemoryTier.PROMISE,
                            sourceEventId = scheduled.id,
                            visibility = dev.charaly.runtime.domain.memory.MemoryVisibility.CHARACTER,
                        )
                    },
                    changes,
                )
                changes += "${payload.keeperId.value} promised ${payload.beneficiaryId.value}: ${payload.text}"
            }

            is PromiseResolved -> {
                val promise = current.worldState.commitments.promise(payload.promiseId)!!
                current = current.evolved(
                    worldState = current.worldState.withCommitments(
                        current.worldState.commitments.withPromise(
                            promise.resolve(payload.status, current.worldClock.now, payload.note),
                        ),
                    ),
                )
                // Both parties are told how it ended - the beneficiary especially,
                // because a broken promise they were not told about is just a plot hole.
                val resolvedAt = current.worldClock.now
                listOf(promise.keeperId, promise.beneficiaryId).distinct().forEach { party ->
                    current = remember(
                        current,
                        "promise-resolved-${promise.id.value}-${party.value}",
                        { memoryId ->
                            dev.charaly.runtime.domain.memory.Memory(
                                id = memoryId,
                                characterId = party,
                                content = "the promise \"${promise.text}\" was " +
                                    "${payload.status.name.lowercase()}" +
                                    if (payload.note.isBlank()) "" else ": ${payload.note}",
                                importance = 4,
                                createdAt = resolvedAt,
                                source = dev.charaly.runtime.domain.memory.MemorySource.EVENT,
                                tier = dev.charaly.runtime.domain.memory.MemoryTier.PROMISE,
                                sourceEventId = scheduled.id,
                                visibility = dev.charaly.runtime.domain.memory.MemoryVisibility.CHARACTER,
                            )
                        },
                        changes,
                    )
                }
                // A witnessed broken promise is a relationship event, not a private one.
                if (payload.status == dev.charaly.runtime.domain.CommitmentStatus.BROKEN &&
                    payload.witnessedBy != null
                ) {
                    current = current.evolved(
                        worldState = current.worldState.withRelationship(
                            dev.charaly.runtime.domain.Relationship(
                                sourceId = payload.witnessedBy,
                                targetId = promise.keeperId,
                            ).apply(
                                dev.charaly.runtime.domain.RelationshipDelta(tension = 25, trust = -15),
                                note = "saw promise broken",
                                at = current.worldClock.now,
                            ),
                        ),
                    )
                    changes += "${payload.witnessedBy.value} loses trust in ${promise.keeperId.value}"
                }
                changes += "promise ${promise.id.value} is ${payload.status.name.lowercase()}"
            }

            is PromiseForgotten -> {
                val promise = current.worldState.commitments.promise(payload.promiseId)!!
                current = current.evolved(
                    worldState = current.worldState.withCommitments(
                        current.worldState.commitments.withPromise(
                            promise.copy(rememberedByKeeper = false),
                        ),
                    ),
                )
                // The promise stays in the ledger. Forgetting is what the *keeper*
                // lost, not what the world lost - and that distinction is the entire
                // reason a broken promise can still be held against someone later.
                changes += "${promise.keeperId.value} forgets promise ${promise.id.value}"
            }

            is GoalUpdated -> {
                val goalStamp = current.worldClock.now
                val ledger = current.worldState.commitments
                val existing = ledger.goal(payload.goalId)
                val goal = existing ?: dev.charaly.runtime.domain.Goal(
                    id = payload.goalId,
                    text = payload.text,
                    ownerId = payload.ownerId,
                    updatedAt = goalStamp,
                    createdAt = goalStamp,
                )
                val updated = goal.copy(
                    progress = (goal.progress + payload.progressDelta).coerceIn(0, 100),
                    status = payload.status ?: goal.status,
                    updatedAt = goalStamp,
                    resolutionNote = payload.note.ifBlank { goal.resolutionNote },
                )
                current = current.evolved(
                    worldState = current.worldState.withCommitments(ledger.withGoal(updated)),
                )
                // The owner is the only one who can see their own progress.
                current = remember(
                    current,
                    "goal-${payload.goalId.value}",
                    { memoryId ->
                        dev.charaly.runtime.domain.memory.Memory(
                            id = memoryId,
                            characterId = payload.ownerId,
                            content = "${updated.text} - ${updated.progress}% there",
                            importance = 3,
                            createdAt = goalStamp,
                            source = dev.charaly.runtime.domain.memory.MemorySource.EVENT,
                            tier = dev.charaly.runtime.domain.memory.MemoryTier.GOAL,
                            sourceEventId = scheduled.id,
                            // Private: nobody else needs to know how far along someone is,
                            // and a stranger knowing your progress is not information.
                            visibility = dev.charaly.runtime.domain.memory.MemoryVisibility.PRIVATE,
                        )
                    },
                    changes,
                )
                changes += "goal ${updated.id.value}: ${updated.progress}%" +
                    (payload.status?.let { " (${it.name.lowercase()})" } ?: "")
            }

            is ConsequenceArmed -> {
                val consequence = dev.charaly.runtime.domain.Consequence(
                    id = payload.consequenceId,
                    decision = payload.decision,
                    decidedBy = payload.decidedBy,
                    decidedAt = current.worldClock.now,
                    outcome = payload.outcome,
                    delayMinutes = payload.delayMinutes,
                    requiresPresenceOf = payload.requiresPresenceOf,
                    requiresLocationId = payload.requiresLocationId,
                )
                current = current.evolved(
                    worldState = current.worldState.withCommitments(
                        current.worldState.commitments.withConsequence(consequence),
                    ),
                )
                changes += "consequence ${consequence.id.value} armed: ${consequence.decision}"
            }

            is ConsequenceFired -> {
                val consequence = current.worldState.commitments.consequence(payload.consequenceId)!!
                current = current.evolved(
                    worldState = current.worldState.withCommitments(
                        current.worldState.commitments.withConsequence(
                            consequence.copy(resolved = true, firedAt = current.worldClock.now),
                        ),
                    ),
                )
                // Whoever decided it learns it came to pass. This is what makes a
                // choice feel like it had weight: the person who made it finds out.
                val firedAt = current.worldClock.now
                current = remember(
                    current,
                    "consequence-${payload.consequenceId.value}",
                    { memoryId ->
                        dev.charaly.runtime.domain.memory.Memory(
                            id = memoryId,
                            characterId = consequence.decidedBy,
                            content = "because you chose to ${consequence.decision}, " +
                                "the result was: ${consequence.outcome}",
                            importance = 5,
                            createdAt = firedAt,
                            source = dev.charaly.runtime.domain.memory.MemorySource.EVENT,
                            tier = dev.charaly.runtime.domain.memory.MemoryTier.CONSEQUENCE,
                            sourceEventId = scheduled.id,
                            visibility = dev.charaly.runtime.domain.memory.MemoryVisibility.PRIVATE,
                        )
                    },
                    changes,
                )
                changes += "consequence ${consequence.id.value} lands: ${consequence.outcome}"
            }

            is SecretRevealed -> {
                val fact = current.knowledge.fact(payload.factId)!!
                val at = current.worldClock.now
                // Everyone told, one grant at a time, exactly as knowledge is normally
                // granted. A reveal is not a special case that bypasses the boundary.
                payload.recipients.distinct().forEach { recipient ->
                    val known = current.knowledge.knows(recipient, payload.factId)
                    current = current.evolved(
                        knowledge = current.knowledge.withKnowledge(
                            recipient,
                            listOf(
                                KnowledgeEntry(
                                    factId = payload.factId,
                                    learnedAt = at,
                                    confidence = 100,
                                    via = "${payload.revealedBy.value} told them",
                                ),
                            ),
                        ),
                    )
                    if (!known) {
                        val runtime = current.characters[recipient]
                        if (runtime != null) {
                            current = current.evolved(
                                worldState = current.worldState.withCharacter(
                                    runtime.copy(
                                        knownFactIds = runtime.knownFactIds + payload.factId,
                                        lastUpdatedAt = at,
                                    ),
                                ),
                            )
                        }
                        current = remember(
                            current,
                            "secret-${payload.factId.value}-${recipient.value}",
                            { memoryId ->
                                dev.charaly.runtime.domain.memory.Memory(
                                    id = memoryId,
                                    characterId = recipient,
                                    content = "${payload.revealedBy.value} told you: ${fact.render()}",
                                    importance = 5,
                                    createdAt = at,
                                    source = dev.charaly.runtime.domain.memory.MemorySource.EVENT,
                                    tier = dev.charaly.runtime.domain.memory.MemoryTier.SECRET,
                                    relatedFactIds = listOf(payload.factId),
                                    sourceEventId = scheduled.id,
                                    // Secret, not world: a thing that has been told to
                                    // somebody is not thereby public, and marking it WORLD
                                    // would hand it to every bystander in the story.
                                    visibility = dev.charaly.runtime.domain.memory.MemoryVisibility.SECRET,
                                    visibleTo = listOf(recipient, payload.revealedBy),
                                )
                            },
                            changes,
                        )
                    }
                }
                changes += "${payload.revealedBy.value} revealed ${payload.factId.value} to " +
                    payload.recipients.joinToString { it.value }
            }
        }

        // The causal edge is recorded only when the event actually changed something and
        // declared a cause. A no-op did not happen, so it cannot have caused anything,
        // and recording it as a node that everything else points at would make the graph
        // lie in the one way that matters.
        val wasNoOp = changes.any { it.startsWith("no change") }
        val withEvent = current.worldState.recordEvent(scheduled.id, stillActive)
        val link = if (wasNoOp) null else scheduled.causalLink()
        val recorded = current.evolved(
            worldState = if (link == null) withEvent else withEvent.recordCausality(link),
        )
        return EventApplication.Applied(recorded, scheduled, changes)
    }

    /**
     * The causal edge this event implies, or null when it has no declared cause.
     *
     * Inferred from the payload *and* the origin, because the same event type means
     * different things depending on who asked for it: a `CharacterMoved` caused by the
     * clock is a routine, and the identical payload caused by a player's decision is
     * something they did. Reading the origin as well as the type is what keeps those
     * apart.
     */
    private fun ScheduledEvent.causalLink(): dev.charaly.runtime.domain.CausalLink? {
        val cause = causeId ?: return null
        val reason = causeReason ?: defaultReason() ?: return null
        return dev.charaly.runtime.domain.CausalLink(
            causeId = cause,
            effectId = id,
            reason = reason,
            at = scheduledAt,
            detail = note,
        )
    }

    /** The reason implied by an event's own shape, when the caller gave none. */
    private fun ScheduledEvent.defaultReason(): dev.charaly.runtime.domain.CausalReason? = when (payload) {
        is CharacterRoutineApplied -> dev.charaly.runtime.domain.CausalReason.ROUTINE
        is TimeAdvanced -> dev.charaly.runtime.domain.CausalReason.CLOCK
        is CharacterMoved -> if (origin == EventOrigin.MODEL_PROPOSAL) {
            dev.charaly.runtime.domain.CausalReason.MODEL_PROPOSAL
        } else {
            dev.charaly.runtime.domain.CausalReason.MOVEMENT
        }
        is RelationshipChanged -> dev.charaly.runtime.domain.CausalReason.ENCOUNTER
        is KnowledgeDiscovered -> dev.charaly.runtime.domain.CausalReason.KNOWLEDGE_GAINED
        is SecretRevealed -> dev.charaly.runtime.domain.CausalReason.SECRET_REVEALED
        is PromiseResolved -> if (payload.status == dev.charaly.runtime.domain.CommitmentStatus.BROKEN) {
            dev.charaly.runtime.domain.CausalReason.PROMISE_BROKEN
        } else {
            dev.charaly.runtime.domain.CausalReason.PROMISE_KEPT
        }
        is GoalUpdated -> dev.charaly.runtime.domain.CausalReason.GOAL_PROGRESS
        is ConsequenceFired -> dev.charaly.runtime.domain.CausalReason.CONSEQUENCE
        is SecretRevealed -> dev.charaly.runtime.domain.CausalReason.SECRET_REVEALED
        else -> if (origin == EventOrigin.STORY_PACK) {
            dev.charaly.runtime.domain.CausalReason.SCRIPTED
        } else {
            null
        }
    }

    /**
     * Mints a memory from a template and stores it as part of the current event.
     *
     * The single write path for event-created memories: it allocates the id from the
     * instance counter, stores it, and keeps the owner's index in step. Every arm that
     * wants to leave a memory behind goes through here rather than assembling one by
     * hand, because the id is the part that is easy to get wrong.
     */
    private fun remember(
        instance: StoryInstance,
        prefix: String,
        build: (dev.charaly.runtime.domain.MemoryId) -> dev.charaly.runtime.domain.memory.Memory,
        changes: MutableList<String>,
    ): StoryInstance {
        val (withId, memoryId) = instance.allocateId(prefix)
        val memory = build(memoryId)
        var updated = withId.evolved(memories = withId.memories.add(memory))
        val runtime = updated.characters[memory.characterId]
        if (runtime != null && memory.id !in runtime.memoryIds) {
            updated = updated.evolved(
                worldState = updated.worldState.withCharacter(
                    runtime.copy(
                        memoryIds = runtime.memoryIds + memory.id,
                        lastUpdatedAt = updated.worldClock.now,
                    ),
                ),
            )
        }
        changes += "memory ${memory.id.value} stored for ${memory.characterId.value}"
        return updated
    }

    private fun Memory(
        id: dev.charaly.runtime.domain.MemoryId,
        characterId: CharacterId,
        content: String,
        importance: Int = 4,
        tier: dev.charaly.runtime.domain.memory.MemoryTier,
        visibility: dev.charaly.runtime.domain.memory.MemoryVisibility,
        sourceEventId: dev.charaly.runtime.domain.EventId? = null,
    ) = dev.charaly.runtime.domain.memory.Memory(
        id = id,
        characterId = characterId,
        content = content,
        importance = importance,
        createdAt = StoryTime.START,
        source = dev.charaly.runtime.domain.memory.MemorySource.EVENT,
        tier = tier,
        visibility = visibility,
        sourceEventId = sourceEventId,
    )


    // ------------------------------------------------------------------
    // Processing
    // ------------------------------------------------------------------

    fun processNextEvent(instance: StoryInstance): ProcessResult {
        val next = instance.eventQueue.peekNext()
            ?: return ProcessResult.Stopped(instance, "event queue is empty", emptyList())
        return when (val applied = applyEvent(instance, next)) {
            is EventApplication.Applied -> ProcessResult.Processed(applied.instance, listOf(applied))
            is EventApplication.Rejected -> ProcessResult.Stopped(instance, applied.validation.describe(), listOf(applied))
        }
    }

    /**
     * Advances the clock to [target] and applies everything that becomes due, in
     * deterministic (scheduledAt, sequence) order. Events scheduled *during*
     * processing are picked up in the same pass when they are already due.
     *
     * A rejected event is dropped instead of retried, so one invalid event can
     * never wedge the world.
     */
    fun processEventsUntil(instance: StoryInstance, target: StoryTime): EventBatchResult {
        var current = instance
        val applied = mutableListOf<ScheduledEvent>()
        val rejected = mutableListOf<EventBatchResult.RejectedEvent>()

        if (target.isAfter(current.worldClock.now)) {
            val counter = current.idCounter + 1
            val clockEvent = ScheduledEvent(
                id = EntityId("evt-$counter"),
                scheduledAt = target,
                origin = EventOrigin.ENGINE,
                payload = TimeAdvanced(
                    by = StoryDuration(current.worldClock.now.minutesUntil(target)),
                    to = target,
                ),
                sequence = current.eventQueue.nextSequence,
                note = "clock advanced to ${target.format()}",
            )
            current = current.copy(idCounter = counter)
            when (val result = applyEvent(current, clockEvent)) {
                is EventApplication.Applied -> {
                    current = result.instance
                    applied += result.event
                }
                is EventApplication.Rejected -> rejected += EventBatchResult.RejectedEvent(result.event, result.validation)
            }
        }

        var guard = 0
        while (guard++ < MAX_EVENTS_PER_PASS) {
            val due = current.eventQueue.due(current.worldClock.now).firstOrNull() ?: break
            when (val result = applyEvent(current, due)) {
                is EventApplication.Applied -> {
                    current = result.instance
                    if (!result.noOp) applied += result.event
                }
                is EventApplication.Rejected -> {
                    rejected += EventBatchResult.RejectedEvent(result.event, result.validation)
                    current = current.evolved(eventQueue = current.eventQueue.remove(result.event.id))
                }
            }
        }
        return EventBatchResult(current, applied, rejected)
    }

    /** Convenience wrapper: advance by a duration and drain due events. */
    fun advanceClock(instance: StoryInstance, by: StoryDuration): EventBatchResult =
        advanceTime(instance, instance.worldClock.now.plusMinutes(by.minutes))

    /**
     * Moves story time to [target] and brings the world along with it.
     *
     * This is the *complete* time transition, and it is what every caller should use:
     *
     *  1. the clock advances and everything already scheduled becomes due, in
     *     deterministic order;
     *  2. scheduled characters are moved to where their routine now says they are.
     *
     * Step 2 lives here rather than in the session layer on purpose. An NPC schedule is
     * authoritative world behaviour, not a UI concern: if it only ran in
     * [dev.charaly.runtime.session.CharalyRuntime.advance], then any other caller of
     * [advanceClockOnly] - a test, a future tool, a replay - would move time without
     * moving the world with it, and the two would disagree.
     *
     * Still a pure function of (definition, instance, target): no wall clock, no
     * randomness, no model.
     */
    fun advanceTime(instance: StoryInstance, target: StoryTime): EventBatchResult {
        val clocked = advanceClockOnly(instance, target)
        val (withPresence, presenceEvents) = applyPresence(clocked.instance)
        return EventBatchResult(
            instance = withPresence,
            applied = clocked.applied + presenceEvents,
            rejected = clocked.rejected,
        )
    }

    /**
     * Relocates every scheduled character whose routine entry has moved on.
     *
     * Rejected relocations are dropped, never retried: one impossible schedule must not
     * be able to wedge the world's clock.
     */
    private fun applyPresence(instance: StoryInstance): Pair<StoryInstance, List<ScheduledEvent>> {
        val proposals = PresenceEngine(definition).relocationsFor(instance)
        if (proposals.isEmpty()) return instance to emptyList()
        var current = instance
        val appliedEvents = mutableListOf<ScheduledEvent>()
        proposals.forEach { payload ->
            when (val scheduled = scheduleEvent(
                instance = current,
                payload = payload,
                at = current.worldClock.now,
                origin = EventOrigin.ENGINE,
                note = payload.summary,
                // The clock is what moved everyone. Chaining every relocation to the
                // single TimeAdvanced event is what lets "why was he here?" be
                // answered with "because it is his day" rather than invented.
                causedBy = current.worldState.eventLog.lastOrNull(),
                because = dev.charaly.runtime.domain.CausalReason.CLOCK,
            )) {
                is ScheduleResult.Rejected -> Unit
                is ScheduleResult.Scheduled -> {
                    val event = scheduled.events.first()
                    when (val applied = applyEvent(scheduled.instance, event)) {
                        is EventApplication.Applied -> {
                            current = applied.instance
                            if (!applied.noOp) appliedEvents += event
                        }
                        is EventApplication.Rejected -> Unit
                    }
                }
            }
        }
        return current to appliedEvents
    }

    /** The clock-and-drain half of [advanceTime], kept separate for clarity. */
    private fun advanceClockOnly(instance: StoryInstance, target: StoryTime): EventBatchResult =
        processEventsUntil(instance, target)

    /** Convenience: build and apply an ad-hoc event without queueing it. */
    fun applyImmediately(
        instance: StoryInstance,
        payload: EventPayload,
        origin: EventOrigin = EventOrigin.ENGINE,
        note: String = "",
    ): EventApplication {
        val counter = instance.idCounter + 1
        val event = ScheduledEvent(
            id = EntityId("evt-$counter"),
            scheduledAt = instance.worldClock.now,
            origin = origin,
            payload = payload,
            sequence = instance.eventQueue.nextSequence,
            note = note,
        )
        return applyEvent(instance.copy(idCounter = counter), event)
    }

    companion object {
        const val MAX_EVENTS_PER_PASS = 500

        /**
         * Allocates one id from the instance's counter, and advances it.
         *
         * Returns the bumped instance as well as the id, because an id allocated without
         * advancing the counter is not an allocation: two memories minted inside one
         * event reducer would come out identical, and a collided id silently overwrites
         * a character's existing memory instead of adding a new one.
         */
        fun StoryInstance.allocateId(prefix: String): Pair<StoryInstance, dev.charaly.runtime.domain.MemoryId> {
            val next = idCounter + 1
            return copy(idCounter = next) to dev.charaly.runtime.domain.MemoryId("$prefix-$next")
        }

        /**
         * The world variable that records where the player is.
         *
         * Kept as a plain world variable rather than a field on the instance so that
         * pack events can condition on the player's position with the same
         * `EventCondition` machinery every other flag uses.
         */
        const val PLAYER_LOCATION = "player_location"

        /** The player's recorded location, or null if they have not moved yet. */
        fun currentPlayerLocation(instance: StoryInstance): LocationId? =
            instance.worldState.variables[PLAYER_LOCATION]?.value?.takeIf { it.isNotBlank() }
                ?.let(::LocationId)

        fun activityOf(name: String): CharacterActivity =
            CharacterActivity.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: CharacterActivity.UNKNOWN
    }
}
