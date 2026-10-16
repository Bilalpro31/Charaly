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
    /**
     * Where pinned pack *editions* come from.
     *
     * A story pins the pack version it was opened from, and content must follow the pin -
     * installing 1.2.0 must not move a 1.1.0 story's characters or places. When the source
     * is absent (tests, deleted installs) the resolver behaves exactly as it always has.
     */
    private val editions: dev.charaly.runtime.storypack.PackEditionSource? = null,
) {
    /** Cached per *edition*, not per pack: two stories of one pack may need two answers. */
    private val cache = mutableMapOf<String, WorldDefinition>()

    suspend fun forInstance(instance: StoryInstance): WorldDefinition {
        val key = editionKey(instance)
        cache[key]?.let { return it }
        val definition = pinnedEdition(instance)
            ?: repository.getPack(instance.storyPackId)
            ?: fallbackPack(instance)
        val resolved = WorldDefinition(definition.characters, definition.locations)
        cache[key] = resolved
        return resolved
    }

    /** The pack edition a story is pinned to, when it is pinned and it is still there. */
    private suspend fun pinnedEdition(instance: StoryInstance): StoryPack? {
        val version = instance.packVersion
        if (version.isBlank()) return null
        return editions?.editionFor(instance.storyPackId.value, version)
    }

    /** A runtime-derived stand-in when no pack content is available at all. */
    private fun fallbackPack(instance: StoryInstance): StoryPack = StoryPack(
        id = instance.storyPackId,
        title = instance.packTitle,
        characters = instance.worldState.characters.values.map { runtime ->
            dev.charaly.runtime.domain.CharacterDefinition(
                id = runtime.characterId,
                name = runtime.name,
            )
        },
        locations = instance.worldState.locations.values.toList(),
    )

    fun forPack(pack: StoryPack): WorldDefinition =
        WorldDefinition(pack.characters, pack.locations).also { cache["${pack.id}@${pack.semanticVersion}"] = it }

    /**
     * Non-suspend peek at the same cache.
     *
     * Used by read-only diagnostics (the developer panel), which must never trigger
     * storage IO. Falls back to a runtime-derived definition, exactly like the
     * suspending path.
     */
    fun peek(instance: StoryInstance): WorldDefinition =
        cache[editionKey(instance)]
            ?: fallbackPeek(instance).also { cache[editionKey(instance)] = it }

    fun invalidate(packId: StoryPackId) {
        cache.keys.filter { it.startsWith("$packId@") }.forEach { cache.remove(it) }
        cache.remove(packId.value)
    }

    private fun editionKey(instance: StoryInstance): String =
        "${instance.storyPackId}@${instance.packVersion.ifBlank { "active" }}"

    private fun fallbackPeek(instance: StoryInstance): WorldDefinition {
        val derived = fallbackPack(instance)
        return WorldDefinition(derived.characters, derived.locations)
    }
}
