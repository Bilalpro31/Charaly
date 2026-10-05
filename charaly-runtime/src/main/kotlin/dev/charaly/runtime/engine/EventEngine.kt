package dev.charaly.runtime.engine

import dev.charaly.runtime.domain.CharacterActivity
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.EntityId
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
import dev.charaly.runtime.domain.events.CharacterMoved
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
    ): ScheduleResult {
        var counter = instance.idCounter
        val event = ScheduledEvent(
            id = EntityId("evt-${++counter}"),
            scheduledAt = instance.worldClock.scheduleAt(at),
            origin = origin,
            payload = payload,
            sequence = instance.eventQueue.nextSequence,
            note = note,
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
    ): ScheduleResult = scheduleEvent(instance, payload, instance.worldClock.scheduleAfter(by), origin, note)

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
        }

        val recorded = current.evolved(
            worldState = current.worldState.recordEvent(scheduled.id, stillActive),
        )
        return EventApplication.Applied(recorded, scheduled, changes)
    }

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
