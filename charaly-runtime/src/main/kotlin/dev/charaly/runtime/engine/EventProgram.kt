package dev.charaly.runtime.engine

import dev.charaly.runtime.domain.EventEffect
import dev.charaly.runtime.domain.EventTrigger
import dev.charaly.runtime.domain.MemoryId
import dev.charaly.runtime.domain.PackEventDefinition
import dev.charaly.runtime.domain.SceneId
import dev.charaly.runtime.domain.SeedEvent
import dev.charaly.runtime.domain.StartingScenario
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.StoryTime
import dev.charaly.runtime.domain.ThreadChange
import dev.charaly.runtime.domain.events.CharacterActivityChanged
import dev.charaly.runtime.domain.events.CharacterMoved
import dev.charaly.runtime.domain.events.EventPayload
import dev.charaly.runtime.domain.events.KnowledgeDiscovered
import dev.charaly.runtime.domain.events.KnowledgeRevoked
import dev.charaly.runtime.domain.events.MemoryCreated
import dev.charaly.runtime.domain.events.RelationshipChanged
import dev.charaly.runtime.domain.events.SceneEnded
import dev.charaly.runtime.domain.events.SceneStarted
import dev.charaly.runtime.domain.events.StoryThreadAdvanced
import dev.charaly.runtime.domain.events.TimeAdvanced
import dev.charaly.runtime.domain.events.WorldVariableSet
import dev.charaly.runtime.domain.memory.Memory
import dev.charaly.runtime.domain.memory.MemorySource

/**
 * Compiles authored pack events into schedulable, typed runtime events.
 *
 * This is the bridge between the authoring model (comfortable to write) and the
 * engine (strict about what it accepts):
 *
 * ```
 * PackEventDefinition (authoring)
 *      -> EventProgram.compile
 *      -> List<SeedEvent> wrapping EventPayload
 *      -> EventEngine.scheduleEvent      <- the only thing that can change the world
 * ```
 *
 * Two invariants live here:
 *  1. Every effect maps to exactly one typed payload. No effect can produce free
 *     text, so no amount of authored prose can mutate state.
 *  2. Compilation is pure and deterministic: the same pack + instance + scenario
 *     always yields the same seed events, which is what makes a story replayable.
 */
object EventProgram {

    /** Depth guard for [EventEffect.ScheduleEvent] chains. */
    private const val MAX_NESTED_DEPTH = 4

    const val EVENT_NOTE_PREFIX = "pack-event:"

    /**
     * The seed events for a story that starts with [scenario].
     *
     * Includes the pack's legacy [StoryPack.initialEvents], every event whose trigger
     * is not conditional (so scheduled events are queued up front and fire when the
     * clock reaches them), and anything the scenario lists explicitly.
     *
     * Conditional events are deliberately NOT scheduled here: they are polled by the
     * runtime, because whether they fire depends on how the story actually went.
     */
    fun compile(
        pack: StoryPack,
        scenario: StartingScenario?,
        instance: StoryInstance,
    ): List<SeedEvent> {
        val start = scenario?.startTime ?: instance.worldClock.now
        val scenarioId = scenario?.id.orEmpty()
        val seeds = mutableListOf<SeedEvent>()
        val claimed = mutableSetOf<String>()

        fun compileInto(definition: PackEventDefinition, at: StoryTime, depth: Int) {
            if (!claimed.add(definition.id)) return
            val context = contextFor(definition, at, instance)
            val payloads = definition.effects.mapNotNull { it.toPayload(context) } +
                definition.threadChanges.mapNotNull { it.toPayload() }
            val delay = (at.totalMinutes - start.totalMinutes).coerceAtLeast(0L)
            payloads.forEach { payload ->
                seeds += SeedEvent(
                    payload = payload,
                    delayMinutes = delay,
                    note = "$EVENT_NOTE_PREFIX${definition.id}",
                )
            }
            if (depth >= MAX_NESTED_DEPTH) return
            definition.effects.filterIsInstance<EventEffect.ScheduleEvent>().forEach { nested ->
                val child = pack.event(nested.eventId) ?: return@forEach
                compileInto(child, at.plusMinutes(nested.afterMinutes), depth + 1)
            }
        }

        pack.initialEvents.forEach { seed ->
            seeds += seed.copy(note = seed.note.ifBlank { "from story pack" })
        }

        pack.events.forEach { definition ->
            val at = when (val trigger = definition.trigger) {
                is EventTrigger.StoryStart -> {
                    val applies = trigger.isUniversal ||
                        (scenarioId.isNotBlank() && scenarioId in trigger.scenarioIds)
                    if (!applies) return@forEach
                    start
                }
                // A scheduled event is queued now and applies when the clock arrives.
                is EventTrigger.AfterDelay -> start.plusMinutes(trigger.delayMinutes)
                is EventTrigger.AtStoryTime -> if (trigger.time.isBefore(start)) start else trigger.time
                // Conditional events are polled, never pre-scheduled.
                is EventTrigger.WhenConditionMet -> return@forEach
            }
            compileInto(definition, at, depth = 0)
        }

        scenario?.eventIds?.forEach { eventId ->
            val definition = pack.event(eventId) ?: return@forEach
            if (definition.trigger is EventTrigger.WhenConditionMet) return@forEach
            if (definition.trigger is EventTrigger.StoryStart) return@forEach
            val at = when (val trigger = definition.trigger) {
                is EventTrigger.AfterDelay -> start.plusMinutes(trigger.delayMinutes)
                is EventTrigger.AtStoryTime -> if (trigger.time.isBefore(start)) start else trigger.time
                else -> start
            }
            compileInto(definition, at, depth = 0)
        }

        return seeds
    }

    /**
     * Conditionally triggered events whose conditions are met right now and whose
     * cooldown has elapsed.
     *
     * The runtime schedules these through the event engine, so even an
     * "automatic" world change is validated exactly like a user action.
     */
    fun conditionalCandidates(
        pack: StoryPack,
        instance: StoryInstance,
    ): List<PackEventDefinition> = pack.events.filter { definition ->
        val trigger = definition.trigger
        if (trigger !is EventTrigger.WhenConditionMet) return@filter false
        val lastFired = instance.firedEvents[definition.id]
        if (lastFired != null && !definition.repeatable) return@filter false
        if (lastFired != null && definition.cooldownMinutes > 0) {
            if (lastFired.minutesUntil(instance.worldClock.now) < definition.cooldownMinutes) return@filter false
        }
        dev.charaly.runtime.domain.EventConditions.all(definition.conditions, instance)
    }

    /** Schedulable payloads for an event that is firing now. */
    fun payloadsFor(
        definition: PackEventDefinition,
        instance: StoryInstance,
        at: StoryTime = instance.worldClock.now,
    ): List<EventPayload> {
        val context = contextFor(definition, at, instance)
        return definition.effects.mapNotNull { it.toPayload(context) } +
            definition.threadChanges.mapNotNull { it.toPayload() }
    }

    private fun contextFor(
        definition: PackEventDefinition,
        at: StoryTime,
        instance: StoryInstance,
    ): EffectContext = EffectContext(
        instance = instance,
        at = at,
        eventKey = definition.id,
        sceneId = SceneId("scene-${definition.id}-${at.totalMinutes}"),
        memoryIdSeed = "mem-${definition.id}-${at.totalMinutes}",
    )
}

/**
 * What an effect needs to become a typed payload.
 *
 * Ids are derived deterministically from the event key and the scheduled story
 * time, so replaying a story produces identical memory and scene ids.
 */
data class EffectContext(
    val instance: StoryInstance,
    val at: StoryTime,
    val eventKey: String,
    val sceneId: SceneId,
    val memoryIdSeed: String,
) {
    private var counter: Int = 0

    fun nextMemoryId(): MemoryId = MemoryId("$memoryIdSeed-${counter++}")
}

/**
 * The one and only effect -> payload mapping.
 *
 * Anything that cannot be expressed as a typed payload returns null rather than
 * degrading into free text.
 */
fun EventEffect.toPayload(context: EffectContext): EventPayload? = when (this) {
    is EventEffect.MoveCharacter -> CharacterMoved(
        characterId = characterId,
        from = context.instance.characters[characterId]?.locationId,
        to = toLocationId,
        arrivalActivity = activity,
    )

    is EventEffect.ChangeActivity -> CharacterActivityChanged(
        characterId = characterId,
        activity = activity,
        mood = mood,
    )

    is EventEffect.ChangeRelationship -> RelationshipChanged(
        sourceId = sourceId,
        targetId = targetId,
        delta = delta,
        relationshipType = relationshipType,
        reason = reason,
    )

    is EventEffect.SetVariable -> WorldVariableSet(key = key, value = value)

    is EventEffect.AdvanceThread -> StoryThreadAdvanced(
        threadId = threadId,
        stage = stage,
        status = status,
        note = note,
    )

    is EventEffect.GrantKnowledge -> KnowledgeDiscovered(
        characterId = characterId,
        factId = factId,
        via = via.ifBlank { context.eventKey },
    )

    is EventEffect.RevokeKnowledge -> KnowledgeRevoked(
        characterId = characterId,
        factId = factId,
        reason = reason,
    )

    is EventEffect.CreateMemory -> MemoryCreated(
        memory = Memory(
            id = context.nextMemoryId(),
            characterId = characterId,
            content = content,
            importance = importance,
            createdAt = context.at,
            source = MemorySource.AUTHORED,
            relatedCharacterIds = relatedCharacterIds,
            relatedLocationId = relatedLocationId,
        ),
    )

    is EventEffect.StartScene -> SceneStarted(
        sceneId = context.sceneId,
        locationId = locationId,
        participants = participants,
        objective = objective,
        activeThreadIds = threadIds,
    )

    is EventEffect.EndScene -> SceneEnded(sceneId = sceneId, reason = reason)

    is EventEffect.AdvanceTime -> TimeAdvanced(
        by = dev.charaly.runtime.domain.StoryDuration(minutes = minutes),
        to = context.at.plusMinutes(minutes),
    )

    // A nested schedule is resolved at compile time, never emitted as a payload.
    is EventEffect.ScheduleEvent -> null
}

/** Thread nudges ride on the same typed payload as an explicit advance. */
fun ThreadChange.toPayload(): EventPayload? =
    if (stage == null && status == null) {
        null
    } else {
        StoryThreadAdvanced(
            threadId = threadId,
            stage = stage ?: 0,
            status = status,
            note = note,
        )
    }

/** One-line preview used by the event editor. */
fun PackEventDefinition.effectPreviewLines(): List<String> =
    (effects.map { it.describe() } + threadChanges.mapNotNull { it.toPayload()?.summary }).distinct()

/** Flat-callable alias, for presentation code that imports this by name. */
fun effectPreview(definition: PackEventDefinition): List<String> = definition.effectPreviewLines()