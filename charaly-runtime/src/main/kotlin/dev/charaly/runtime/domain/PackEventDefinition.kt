package dev.charaly.runtime.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An AUTHORED event: the thing a pack editor writes and the Event Editor screen
 * edits.
 *
 * This is deliberately *not* an [dev.charaly.runtime.domain.events.WorldEvent].
 * A pack event is a recipe: a trigger, a set of deterministic conditions and a
 * list of structured effects. [dev.charaly.runtime.engine.EventProgram] compiles it
 * into typed payloads, and only the [dev.charaly.runtime.engine.EventEngine] may
 * apply those.
 *
 * The reason for the split is the core Charaly rule: arbitrary prose must never be
 * able to mutate the world. Because effects are a closed set of typed cases, a
 * pack author cannot smuggle "and also set her relationship to god mode" into a
 * description field.
 */
@Serializable
data class PackEventDefinition(
    val id: String,
    val title: String,
    val description: String = "",
    /**
     * A short dramatic seed the ContextBuilder may surface as scene colour. It is
     * prose *for the model*, never an instruction to the engine.
     */
    val narrativeSeed: String = "",
    val trigger: EventTrigger,
    val conditions: List<EventCondition> = emptyList(),
    val effects: List<EventEffect> = emptyList(),
    val participants: List<CharacterId> = emptyList(),
    val locationId: LocationId? = null,
    val cooldownMinutes: Long = 0L,
    val repeatable: Boolean = false,
    val threadChanges: List<ThreadChange> = emptyList(),
    val tags: List<String> = emptyList(),
    val artwork: PackArtwork = PackArtwork(),
    /**
     * Optional artwork for this event.
     *
     * Presentation only. A pack event is a recipe for a world change; its picture says
     * nothing about what it does and cannot influence it.
     */
    val visualAsset: VisualAsset? = null,
) {
    init {
        require(id.isNotBlank()) { "PackEventDefinition needs an id" }
        require(title.isNotBlank()) { "PackEventDefinition $id needs a title" }
        require(cooldownMinutes >= 0) { "cooldownMinutes must not be negative ($id)" }
    }

    /** How the UI should group this event in a library. */
    val category: EventCategory
        get() = when (trigger) {
            is EventTrigger.StoryStart -> EventCategory.STORY_START
            is EventTrigger.AfterDelay -> EventCategory.SCHEDULED
            is EventTrigger.AtStoryTime -> EventCategory.SCHEDULED
            is EventTrigger.WhenConditionMet -> EventCategory.CONDITIONAL
        }

    fun involves(characterId: CharacterId): Boolean =
        characterId in participants || effects.any { it.mentions(characterId) }

    fun touches(locationId: LocationId): Boolean =
        this.locationId == locationId || effects.any { it.touches(locationId) }

    /**
     * The event's own visual asset, if the pack author declared one.
     *
     * Optional by design: most events are too small to deserve artwork, and requiring one
     * would mean padding the library with decorative images.
     */
    fun visualAsset(): VisualAsset? = visualAsset
}

@Serializable
enum class EventCategory { STORY_START, SCHEDULED, CONDITIONAL }

/** When a pack event tries to fire. Deterministic; never "whenever the model feels like it". */
@Serializable
sealed interface EventTrigger {

    @Serializable
    @SerialName("StoryStart")
    data class StoryStart(val scenarioIds: List<String> = emptyList()) : EventTrigger {
        /** Empty scenario list means "every opening". */
        val isUniversal: Boolean get() = scenarioIds.isEmpty()
    }

    @Serializable
    @SerialName("AfterDelay")
    data class AfterDelay(val delayMinutes: Long) : EventTrigger {
        init {
            require(delayMinutes >= 0) { "delayMinutes must not be negative" }
        }
    }

    @Serializable
    @SerialName("AtStoryTime")
    data class AtStoryTime(val time: StoryTime) : EventTrigger

    @Serializable
    @SerialName("WhenConditionMet")
    data class WhenConditionMet(val pollEveryMinutes: Long = 30L) : EventTrigger {
        init {
            require(pollEveryMinutes >= 0) { "pollEveryMinutes must not be negative" }
        }
    }
}

/**
 * A deterministic predicate over authoritative world state.
 *
 * Conditions are the "conditional events" feature. They are evaluated by the
 * runtime after a world change and after the clock advances, never by the model.
 */
@Serializable
sealed interface EventCondition {

    @Serializable
    @SerialName("VariableEquals")
    data class VariableEquals(val key: String, val value: String) : EventCondition

    @Serializable
    @SerialName("VariableNotEquals")
    data class VariableNotEquals(val key: String, val value: String) : EventCondition

    @Serializable
    @SerialName("CharacterAtLocation")
    data class CharacterAtLocation(val characterId: CharacterId, val locationId: LocationId) : EventCondition

    @Serializable
    @SerialName("CharacterNotAtLocation")
    data class CharacterNotAtLocation(val characterId: CharacterId, val locationId: LocationId) : EventCondition

    @Serializable
    @SerialName("ThreadStageAtLeast")
    data class ThreadStageAtLeast(val threadId: ThreadId, val stage: Int) : EventCondition

    @Serializable
    @SerialName("ThreadStatusIs")
    data class ThreadStatusIs(val threadId: ThreadId, val status: StoryThreadStatus) : EventCondition

    @Serializable
    @SerialName("RelationshipTrustAtLeast")
    data class RelationshipTrustAtLeast(
        val sourceId: CharacterId,
        val targetId: CharacterId,
        val minTrust: Int,
    ) : EventCondition

    @Serializable
    @SerialName("StoryTimeReached")
    data class StoryTimeReached(val time: StoryTime) : EventCondition

    @Serializable
    @SerialName("CharacterKnowsFact")
    data class CharacterKnowsFact(val characterId: CharacterId, val factId: FactId) : EventCondition

    @Serializable
    @SerialName("FactExists")
    data class FactExists(val factId: FactId) : EventCondition

    @Serializable
    @SerialName("SceneIsOpen")
    data class SceneIsOpen(val locationId: LocationId) : EventCondition

    /** Human readable form used by the event editor and the story timeline. */
    fun describe(): String = when (this) {
        is VariableEquals -> "$key is \"$value\""
        is VariableNotEquals -> "$key is not \"$value\""
        is CharacterAtLocation -> "${characterId.value} is at ${locationId.value}"
        is CharacterNotAtLocation -> "${characterId.value} is not at ${locationId.value}"
        is ThreadStageAtLeast -> "thread ${threadId.value} is at stage $stage or later"
        is ThreadStatusIs -> "thread ${threadId.value} is ${status.name.lowercase()}"
        is RelationshipTrustAtLeast -> "trust ${sourceId.value}->${targetId.value} is $minTrust+"
        is StoryTimeReached -> "the clock reaches ${time.formatClock()}"
        is CharacterKnowsFact -> "${characterId.value} knows ${factId.value}"
        is FactExists -> "${factId.value} exists"
        is SceneIsOpen -> "a scene is open at ${locationId.value}"
    }
}

/**
 * One structured consequence of a pack event.
 *
 * Every case maps onto exactly one [dev.charaly.runtime.domain.events.EventPayload].
 * Adding a case here is the only supported way to add a new kind of world change.
 */
@Serializable
sealed interface EventEffect {

    /** Characters this effect touches, for the event editor's "participants" hint. */
    fun mentions(characterId: CharacterId): Boolean = false

    /** Locations this effect touches. */
    fun touches(locationId: LocationId): Boolean = false

    /** Short label used by the event editor preview. */
    fun describe(): String

    @Serializable
    @SerialName("MoveCharacter")
    data class MoveCharacter(
        val characterId: CharacterId,
        val toLocationId: LocationId,
        val activity: CharacterActivity = CharacterActivity.IDLE,
    ) : EventEffect {
        override fun mentions(characterId: CharacterId) = characterId == this.characterId
        override fun touches(locationId: LocationId) = locationId == toLocationId
        override fun describe() = "move ${characterId.value} to ${toLocationId.value}"
    }

    @Serializable
    @SerialName("ChangeActivity")
    data class ChangeActivity(
        val characterId: CharacterId,
        val activity: CharacterActivity,
        val mood: String = "",
    ) : EventEffect {
        override fun mentions(characterId: CharacterId) = characterId == this.characterId
        override fun describe() = "set ${characterId.value} to ${activity.name.lowercase()}"
    }

    @Serializable
    @SerialName("ChangeRelationship")
    data class ChangeRelationship(
        val sourceId: CharacterId,
        val targetId: CharacterId,
        val delta: RelationshipDelta = RelationshipDelta(),
        val relationshipType: RelationshipType? = null,
        val reason: String = "",
    ) : EventEffect {
        override fun mentions(characterId: CharacterId) =
            characterId == sourceId || characterId == targetId
        override fun describe() =
            "relationship ${sourceId.value}->${targetId.value} ${delta.describe()}"
    }

    @Serializable
    @SerialName("SetVariable")
    data class SetVariable(val key: String, val value: String) : EventEffect {
        override fun describe() = "set world variable $key = \"$value\""
    }

    @Serializable
    @SerialName("AdvanceThread")
    data class AdvanceThread(
        val threadId: ThreadId,
        val stage: Int = 1,
        val status: StoryThreadStatus? = null,
        val note: String = "",
    ) : EventEffect {
        override fun describe() = "advance thread ${threadId.value} to stage $stage"
    }

    @Serializable
    @SerialName("GrantKnowledge")
    data class GrantKnowledge(
        val characterId: CharacterId,
        val factId: FactId,
        val via: String = "",
    ) : EventEffect {
        override fun mentions(characterId: CharacterId) = characterId == this.characterId
        override fun touches(locationId: LocationId) = false
        override fun describe() = "teach ${characterId.value} about ${factId.value}"
    }

    @Serializable
    @SerialName("RevokeKnowledge")
    data class RevokeKnowledge(
        val characterId: CharacterId,
        val factId: FactId,
        val reason: String = "",
    ) : EventEffect {
        override fun mentions(characterId: CharacterId) = characterId == this.characterId
        override fun describe() = "make ${characterId.value} forget ${factId.value}"
    }

    @Serializable
    @SerialName("CreateMemory")
    data class CreateMemory(
        val characterId: CharacterId,
        val content: String,
        val importance: Int = 3,
        val relatedCharacterIds: List<CharacterId> = emptyList(),
        val relatedLocationId: LocationId? = null,
    ) : EventEffect {
        init {
            require(content.isNotBlank()) { "CreateMemory needs content" }
            require(importance in 1..5) { "memory importance must be 1..5" }
        }

        override fun mentions(characterId: CharacterId) =
            characterId == this.characterId || characterId in relatedCharacterIds
        override fun touches(locationId: LocationId) = locationId == relatedLocationId
        override fun describe() = "remember \"${content.take(40)}\" as ${characterId.value}"
    }

    @Serializable
    @SerialName("StartScene")
    data class StartScene(
        val locationId: LocationId,
        val participants: List<CharacterId> = emptyList(),
        val objective: String = "",
        val threadIds: List<ThreadId> = emptyList(),
    ) : EventEffect {
        override fun touches(locationId: LocationId) = locationId == this.locationId
        override fun mentions(characterId: CharacterId) = characterId in participants
        override fun describe() = "open a scene at ${locationId.value}"
    }

    @Serializable
    @SerialName("EndScene")
    data class EndScene(val sceneId: SceneId, val reason: String = "") : EventEffect {
        override fun describe() = "close scene ${sceneId.value}"
    }

    @Serializable
    @SerialName("AdvanceTime")
    data class AdvanceTime(val minutes: Long) : EventEffect {
        init {
            require(minutes >= 0) { "AdvanceTime minutes must not be negative" }
        }

        override fun describe() = "advance the clock $minutes min"
    }

    @Serializable
    @SerialName("ScheduleEvent")
    data class ScheduleEvent(
        /** Id of another [PackEventDefinition] to schedule a little later. */
        val eventId: String,
        val afterMinutes: Long = 10L,
    ) : EventEffect {
        init {
            require(eventId.isNotBlank()) { "ScheduleEvent needs an eventId" }
            require(afterMinutes >= 0) { "afterMinutes must not be negative" }
        }

        override fun describe() = "schedule \"$eventId\" in $afterMinutes min"
    }
}

/** A thread nudge authored alongside a pack event. */
@Serializable
data class ThreadChange(
    val threadId: ThreadId,
    val stage: Int? = null,
    val status: StoryThreadStatus? = null,
    val note: String = "",
)

/** Human readable delta, used by the event editor and the story timeline. */
fun RelationshipDelta.describe(): String = buildString {
    if (trust != 0) append("trust${if (trust > 0) "+" else ""}$trust ")
    if (familiarity != 0) append("familiarity${if (familiarity > 0) "+" else ""}$familiarity ")
    if (affinity != 0) append("affinity${if (affinity > 0) "+" else ""}$affinity ")
}.trim().ifEmpty { "no change" }