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
) : EventPayload {
    override val summary: String get() = "${characterId.value} is now ${activity.name.lowercase()}"
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
