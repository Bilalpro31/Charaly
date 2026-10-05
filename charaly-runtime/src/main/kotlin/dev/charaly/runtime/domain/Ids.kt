package dev.charaly.runtime.domain

import kotlinx.serialization.Serializable

/**
 * Stable, human readable identifier used for every domain entity.
 *
 * Charaly deliberately uses an explicit id type instead of relying on object
 * identity: world state has to survive serialization (application restart),
 * migrations and cross-reference resolution (knowledge, memories, scenes all
 * point at characters and locations by id).
 */
@kotlinx.serialization.Serializable
@JvmInline
value class EntityId(val value: String) {
    init {
        require(value.isNotBlank()) { "EntityId must not be blank" }
    }

    override fun toString(): String = value
}

typealias CharacterId = EntityId
typealias LocationId = EntityId
typealias FactId = EntityId
typealias ThreadId = EntityId
typealias SceneId = EntityId
typealias EventId = EntityId
typealias MemoryId = EntityId
typealias StoryPackId = EntityId
typealias StoryInstanceId = EntityId
typealias PromiseId = EntityId
typealias GoalId = EntityId
typealias ConsequenceId = EntityId

/** Deterministic, monotonically increasing id generator. */
class IdGenerator(private val prefix: String = "id") {
    private var counter: Long = 0

    fun next(): EntityId {
        counter += 1
        return EntityId("$prefix-$counter")
    }

    /** Keeps generated ids unique after a reload of persisted state. */
    fun observe(existing: EntityId) {
        if (!existing.value.startsWith("$prefix-")) return
        val suffix = existing.value.removePrefix("$prefix-").toLongOrNull() ?: return
        if (suffix > counter) counter = suffix
    }

    fun snapshot(): Long = counter

    fun restore(value: Long) {
        if (value > counter) counter = value
    }
}
