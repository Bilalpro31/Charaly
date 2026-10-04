package dev.charaly.runtime.domain.events

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.EventId
import dev.charaly.runtime.domain.StoryTime
import kotlinx.serialization.Serializable

/**
 * Pending, not-yet-applied world events.
 *
 * The queue is the *only* place where "the future" exists. It is deterministic:
 * ordering is (scheduledAt, insertion sequence), never hash order.
 */
@Serializable
data class EventQueue(
    val pending: List<ScheduledEvent> = emptyList(),
    val nextSequence: Long = 1L,
) {
    val size: Int get() = pending.size

    fun isEmpty(): Boolean = pending.isEmpty()

    fun snapshot(): List<ScheduledEvent> = pending.sortedWith(DETERMINISTIC)

    fun peekNext(): ScheduledEvent? = snapshot().firstOrNull()

    fun enqueue(event: ScheduledEvent): EventQueue =
        copy(pending = (pending + event).sortedWith(DETERMINISTIC), nextSequence = nextSequence + 1)

    fun enqueueAll(events: Collection<ScheduledEvent>): EventQueue =
        events.fold(this) { acc, event -> acc.enqueue(event) }

    fun remove(id: EventId): EventQueue =
        copy(pending = pending.filterNot { it.id == id })

    /** All events due at or before [now], in deterministic order. */
    fun due(now: StoryTime): List<ScheduledEvent> = snapshot().filter { it.scheduledAt.isAtOrBefore(now) }

    fun removeAll(ids: Collection<EventId>): EventQueue {
        val set = ids.toSet()
        return copy(pending = pending.filterNot { it.id in set })
    }

    fun clear(): EventQueue = copy(pending = emptyList())

    /** Pending events whose payload is about a given character (inspector). */
    fun pendingFor(characterId: CharacterId): List<ScheduledEvent> =
        snapshot().filter { event ->
            when (val p = event.payload) {
                is CharacterMoved -> p.characterId == characterId
                is CharacterEnteredScene -> p.characterId == characterId
                is CharacterLeftScene -> p.characterId == characterId
                is CharacterActivityChanged -> p.characterId == characterId
                is KnowledgeDiscovered -> p.characterId == characterId
                is KnowledgeRevoked -> p.characterId == characterId
                is MemoryCreated -> p.memory.characterId == characterId
                is RelationshipChanged -> p.sourceId == characterId || p.targetId == characterId
                is SceneStarted -> characterId in p.participants
                else -> false
            }
        }

    companion object {
        val EMPTY = EventQueue()

        val DETERMINISTIC: Comparator<ScheduledEvent> =
            compareBy({ it.scheduledAt }, { it.sequence })
    }
}
