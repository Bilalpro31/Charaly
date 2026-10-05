package dev.charaly.runtime.session

import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.StoryPackId
import dev.charaly.runtime.domain.WorldDefinition
import dev.charaly.runtime.persistence.CharalyRepository

/**
 * Resolves the STATIC half of a running world.
 *
 * [StoryInstance] deliberately stores only dynamic state (see the ownership
 * rules), so static character definitions come from the pack it was created
 * from. This catalog connects the two and falls back to a runtime-derived
 * definition when the pack is gone, so a story whose pack was deleted still
 * opens with reduced character detail instead of crashing.
 */
class WorldDefinitionResolver(
    private val repository: CharalyRepository,
) {
    private val cache = mutableMapOf<StoryPackId, WorldDefinition>()

    suspend fun forInstance(instance: StoryInstance): WorldDefinition {
        cache[instance.storyPackId]?.let { return it }
        val definition = repository.getPack(instance.storyPackId)
            ?.let { WorldDefinition(it.characters, it.locations) }
            ?: fallback(instance)
        cache[instance.storyPackId] = definition
        return definition
    }

    fun forPack(pack: StoryPack): WorldDefinition =
        WorldDefinition(pack.characters, pack.locations).also { cache[pack.id] = it }

    /**
     * Non-suspend peek at the same cache.
     *
     * Used by read-only diagnostics (the developer panel), which must never trigger
     * storage IO. Falls back to a runtime-derived definition, exactly like the
     * suspending path.
     */
    fun peek(instance: StoryInstance): WorldDefinition =
        cache[instance.storyPackId]
            ?: fallback(instance).also { cache[instance.storyPackId] = it }

    fun invalidate(packId: StoryPackId) {
        cache.remove(packId)
    }

    private fun fallback(instance: StoryInstance): WorldDefinition =
        WorldDefinition(
            characters = instance.worldState.characters.values.map { runtime ->
                dev.charaly.runtime.domain.CharacterDefinition(
                    id = runtime.characterId,
                    name = runtime.name,
                )
            },
            locations = instance.worldState.locations.values.toList(),
        )
}
