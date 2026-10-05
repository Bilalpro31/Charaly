package dev.charaly.runtime.domain.events

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.EventId
import dev.charaly.runtime.domain.FactId
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.MemoryId
import dev.charaly.runtime.domain.RelationshipDelta
import dev.charaly.runtime.domain.RelationshipType
import dev.charaly.runtime.domain.SceneId
import dev.charaly.runtime.domain.StoryDuration
import dev.charaly.runtime.domain.StoryThreadStatus
import dev.charaly.runtime.domain.StoryTime
import dev.charaly.runtime.domain.ThreadId
import dev.charaly.runtime.domain.memory.Memory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An authoritative world change.
 *
 * Charaly never stores world changes as natural language. Every mutation of the
 * world must be expressed as one of these typed events and applied by the
 * [dev.charaly.runtime.engine.EventEngine].
 *
 * IMPORTANT: model output is *not* a WorldEvent. Model output can only become a
 * proposed action (see `dev.charaly.runtime.engine.ProposedAction`) which is
 * validated and then scheduled as one of these events.
 */
@Serializable
sealed interface WorldEvent {
    val id: EventId
    val scheduledAt: StoryTime
    val origin: EventOrigin
    /** The typed change this event represents. */
    val payload: EventPayload

    /** Deterministic ordering key: scheduled time, then insertion sequence. */
    val orderKey: Long get() = scheduledAt.totalMinutes
}

@Serializable
enum class EventOrigin {
    /** Shipped with the story pack (story start conditions). */
    STORY_PACK,
    /** Scheduled by the runtime itself as a consequence of another event. */
    ENGINE,
    /** Explicitly scheduled by the user from the UI/inspector. */
    USER,
    /** Proposed by the model, validated, then scheduled. Never applied blindly. */
    MODEL_PROPOSAL,
}

@Serializable
data class ScheduledEvent(
    override val id: EventId,
    override val scheduledAt: StoryTime,
    override val origin: EventOrigin,
    override val payload: EventPayload,
    val sequence: Long,
    val note: String = "",
) : WorldEvent {
    val isDue: Boolean get() = true

    fun describe(): String =
        "${payload::class.simpleName ?: "Event"}@$scheduledAt ($note)"
}

// ---------------------------------------------------------------------------
// Typed payloads
// ---------------------------------------------------------------------------

@Serializable
sealed interface EventPayload {
    val summary: String
}

@Serializable
@SerialName("CharacterMoved")
data class CharacterMoved(
    val characterId: CharacterId,
    val from: LocationId?,
    val to: LocationId,
    /** Activity after arrival. Defaults to IDLE; the runtime decides, never prose. */
    val arrivalActivity: dev.charaly.runtime.domain.CharacterActivity =
        dev.charaly.runtime.domain.CharacterActivity.IDLE,
) : EventPayload {
    override val summary: String
        get() = "${characterId.value} moves from ${from?.value ?: "nowhere"} to ${to.value}"
}

/** Character becomes a participant of an active scene. */
@Serializable
@SerialName("CharacterEnteredScene")
data class CharacterEnteredScene(
    val characterId: CharacterId,
    val sceneId: SceneId,
    val locationId: LocationId,
) : EventPayload {
    override val summary: String get() = "${characterId.value} enters scene ${sceneId.value}"
}

@Serializable
@SerialName("CharacterLeftScene")
data class CharacterLeftScene(
    val characterId: CharacterId,
    val sceneId: SceneId,
) : EventPayload {
    override val summary: String get() = "${characterId.value} leaves scene ${sceneId.value}"
}

@Serializable
@SerialName("RelationshipChanged")
data class RelationshipChanged(
    val sourceId: CharacterId,
    val targetId: CharacterId,
    val delta: RelationshipDelta,
    val relationshipType: RelationshipType? = null,
    val reason: String = "",
) : EventPayload {
    override val summary: String
        get() = "relationship ${sourceId.value}->${targetId.value} changes ($delta)${if (reason.isBlank()) "" else ": $reason"}"
}

@Serializable
@SerialName("KnowledgeDiscovered")
data class KnowledgeDiscovered(
    val characterId: CharacterId,
    val factId: FactId,
    val via: String = "",
    val confidence: Int = 100,
) : EventPayload {
    override val summary: String get() = "${characterId.value} learns fact ${factId.value}"
}

/** Removes a character's knowledge of a fact (misinformation / forgetting). */
@Serializable
@SerialName("KnowledgeRevoked")
data class KnowledgeRevoked(
    val characterId: CharacterId,
    val factId: FactId,
    val reason: String = "",
) : EventPayload {
    override val summary: String get() = "${characterId.value} forgets fact ${factId.value}"
}

@Serializable
@SerialName("MemoryCreated")
data class MemoryCreated(
    val memory: Memory,
) : EventPayload {
    override val summary: String get() = "memory ${memory.id.value} created for ${memory.characterId.value}"
}

@Serializable
@SerialName("StoryThreadAdvanced")
data class StoryThreadAdvanced(
    val threadId: ThreadId,
    val stage: Int,
    val status: StoryThreadStatus? = null,
    val note: String = "",
) : EventPayload {
    override val summary: String
        get() = "thread ${threadId.value} -> stage $stage${status?.let { " ($it)" } ?: ""}"
}

@Serializable
@SerialName("SceneStarted")
data class SceneStarted(
    val sceneId: SceneId,
    val locationId: LocationId,
    val participants: List<CharacterId>,
    val objective: String = "",
    val activeThreadIds: List<ThreadId> = emptyList(),
) : EventPayload {
    override val summary: String get() = "scene ${sceneId.value} starts at ${locationId.value}"
}

@Serializable
@SerialName("SceneEnded")
data class SceneEnded(
    val sceneId: SceneId,
    val reason: String = "",
) : EventPayload {
    override val summary: String get() = "scene ${sceneId.value} ends ($reason)"
}

@Serializable
@SerialName("TimeAdvanced")
data class TimeAdvanced(
    val by: StoryDuration,
    val to: StoryTime,
) : EventPayload {
    override val summary: String get() = "time advances by ${by.minutes} min to ${to.format()}"
}

@Serializable
@SerialName("CharacterActivityChanged")
data class CharacterActivityChanged(
    val characterId: CharacterId,
    val activity: dev.charaly.runtime.domain.CharacterActivity,
    val mood: String = "",
    /** Display wording for the activity, e.g. "serving customers". Never parsed. */
    val activityLabel: String = "",
) : EventPayload {
    override val summary: String get() = "${characterId.value} is now ${activity.name.lowercase()}"
}

/**
 * The player arrived somewhere.
 *
 * Distinct from [CharacterMoved] on purpose. Movement says "a character is now here";
 * arrival says "the player is here, so whoever is in this place has just been
 * encountered". That second claim is what makes an ice-cream vendor discoverable by
 * walking into his shop, rather than by being added to a conversation - so it needs its
 * own event to be authored, observed and replayed independently.
 */
@Serializable
@SerialName("LocationEntered")
data class LocationEntered(
    val locationId: LocationId,
    /** Scene opened by arriving, when the pack authorises one. */
    val sceneId: SceneId? = null,
    /** What the player noticed on the way in, for the narration prompt. */
    val observation: String = "",
) : EventPayload {
    override val summary: String
        get() = "the player entered ${locationId.value}" +
            if (observation.isNotBlank()) " ($observation)" else ""
}

/**
 * A scheduled character is moved to where their routine says they belong.
 *
 * This is deliberately a *separate payload* from [CharacterMoved] rather than a flag
 * on it, because the two express genuinely different authority:
 *
 *  * [CharacterMoved] is a traversal. It is validated against the location graph
 *    ([dev.charaly.runtime.domain.WorldDefinition.isAdjacent]), because a character
 *    cannot walk from the bakery to the museum in one step.
 *  * This is not a traversal. Nobody walked anywhere: the clock passed 19:00, the
 *    ice-cream vendor's routine says he closes up and goes home, and his
 *    authoritative state is now his flat. Demanding graph adjacency here would make
 *    authored schedules unimplementable, and expressing it as [CharacterMoved] would
 *    make the engine's movement rule dishonest.
 *
 * It is still a structured, validated, logged, replayable event - the world is only
 * ever changed this way. It is produced exclusively by
 * [dev.charaly.runtime.engine.PresenceEngine] from authored routine data, never from
 * model output.
 */
@Serializable
@SerialName("CharacterRoutineApplied")
data class CharacterRoutineApplied(
    val characterId: CharacterId,
    val from: LocationId?,
    val to: LocationId,
    val activity: dev.charaly.runtime.domain.CharacterActivity,
    val activityLabel: String = "",
    /** The routine entry (minutes-of-day) that produced this relocation. */
    val routineEntryMinute: Int = 0,
) : EventPayload {
    override val summary: String
        get() = "${characterId.value} follows their routine to ${to.value}" +
            if (activityLabel.isNotBlank()) " ($activityLabel)" else ""
}

@Serializable
@SerialName("WorldVariableSet")
data class WorldVariableSet(
    val key: String,
    val value: String,
) : EventPayload {
    override val summary: String get() = "world variable $key = $value"
}

/** Explicitly registers a new fact in world truth (does not teach it to anyone). */
/** Explicitly registers a new fact in world truth (teaches it to nobody). */
@Serializable
@SerialName("FactRevealed")
data class FactRevealed(
    val fact: dev.charaly.runtime.domain.knowledge.Fact,
) : EventPayload {
    override val summary: String get() = "fact ${fact.id.value} becomes part of world truth"
}
