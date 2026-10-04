package dev.charaly.runtime.domain

import kotlinx.serialization.Serializable

/**
 * The STATIC half of a running world: character definitions and places.
 *
 * Splitting this out of [WorldState] keeps static identity out of the
 * authoritative dynamic state, and gives the EventEngine exactly what it needs
 * to validate an event without touching the instance.
 */
@Serializable
data class WorldDefinition(
    val characters: List<CharacterDefinition> = emptyList(),
    val locations: List<Location> = emptyList(),
) {
    private val characterIndex: Map<CharacterId, CharacterDefinition> by lazy {
        characters.associateBy { it.id }
    }
    private val locationIndex: Map<LocationId, Location> by lazy {
        locations.associateBy { it.id }
    }

    fun character(id: CharacterId?): CharacterDefinition? = id?.let { characterIndex[it] }

    fun characterByName(name: String): CharacterDefinition? =
        characters.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    fun location(id: LocationId?): Location? = id?.let { locationIndex[it] }

    fun locationByName(name: String): Location? =
        locations.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    fun nameOf(id: EntityId): String = character(id)?.name ?: location(id)?.name ?: id.value

    fun isAdjacent(from: LocationId?, to: LocationId): Boolean {
        if (from == null) return true
        if (from == to) return true
        val origin = location(from) ?: return false
        if (origin.connections.isEmpty()) return true // unpinned topology: free movement
        return origin.connections.contains(to)
    }

    companion object {
        val EMPTY = WorldDefinition()
    }
}
