package dev.charaly.runtime.presentation

import dev.charaly.runtime.domain.CharacterActivity
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.CharacterRole
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.WorldDefinition

/**
 * Turns a running world into "places you can walk into and people who are in them".
 *
 * This is the projection behind Charaly's central claim: the user enters a *world*, not
 * a conversation. Everything here is read from authoritative state - who is standing
 * where, what they are doing, how the player is known to them - so nothing on this
 * screen can be a fiction the engine has not agreed to.
 *
 * It is deliberately a pure function. The screen cannot change anything by rendering,
 * and the same world always produces the same snapshot.
 */
object WorldPresenter {

    /**
     * @param instance the running story
     * @param pack the static half of that story
     */
    fun build(instance: StoryInstance, pack: StoryPack): WorldSnapshot = build(
        instance = instance,
        definition = WorldDefinition(pack.characters, pack.locations),
        packTitle = pack.title,
        accent = ResolvedTheme.of(pack.identity.theme).primary,
    )

    fun build(
        instance: StoryInstance,
        definition: WorldDefinition,
        packTitle: String,
        accent: Long = ResolvedTheme.BRAND.primary,
    ): WorldSnapshot {
        val playerHere = instance.currentLocation()?.id
        val characters = instance.worldState.characters.values

        // Who is standing where, straight from world state.
        val occupantsByLocation: Map<LocationId?, List<CharacterId>> =
            characters.groupBy({ it.locationId }, { it.characterId })

        val locations = packLocations(instance, definition).map { location ->
            val occupants = occupantsByLocation[location.id].orEmpty()
            WorldLocation(
                id = location.id.value,
                name = location.name,
                blurb = location.blurb(),
                artwork = location.artwork,
                accent = location.accentLong().takeIf { it != 0L } ?: accent,
                // Named occupants are listed; the rest of the crowd is a count, because
                // giving a background figure a name would be inventing world state.
                presentCharacterIds = occupants.map { it.value },
                presentCharacterNames = occupants.map { definition.nameOf(it) },
                isHere = playerHere == location.id,
                rules = location.rules,
            )
        }

        val people = characters
            .map { runtime ->
                val character = definition.character(runtime.characterId)
                WorldPerson(
                    id = runtime.characterId.value,
                    name = character?.name ?: runtime.name,
                    role = character?.identityRole.orEmpty(),
                    locationName = definition.location(runtime.locationId)?.name ?: "somewhere in $packTitle",
                    activityLabel = activityLabel(runtime),
                    accent = character?.accentLong()?.takeIf { it != 0L } ?: accent,
                    artwork = character?.artwork ?: dev.charaly.runtime.domain.PackArtwork.generated(runtime.characterId.value),
                    isHere = playerHere != null && runtime.locationId == playerHere,
                    // How the world currently classifies this relationship, in the
                    // player's language rather than as a score.
                    relationshipLabel = relationshipLabel(instance, runtime.characterId),
                    isNpc = character?.storyRole == CharacterRole.NPC,
                )
            }
            // People where the player is standing come first: they are the ones who can
            // actually be spoken to right now.
            .sortedWith(
                compareByDescending<WorldPerson> { it.isHere }
                    .thenBy { it.name.lowercase() },
            )

        return WorldSnapshot(
            worldTitle = packTitle,
            currentLocationName = instance.currentLocation()?.name.orEmpty(),
            timeLabel = instance.worldClock.now.storyLabel(),
            locations = locations,
            characters = people,
        )
    }

    /**
     * The places worth showing.
     *
     * Every location in the pack, ordered so the player's own location is first and the
     * rest stay stable. A place with no occupants is still listed - an empty street is
     * information, and hiding it would make the city feel smaller than it is.
     */
    private fun packLocations(
        instance: StoryInstance,
        definition: WorldDefinition,
    ): List<dev.charaly.runtime.domain.Location> {
        val all = instance.worldState.locations.values.ifEmpty { definition.locations }
        val here = instance.currentLocation()?.id
        return all.sortedWith(
            compareByDescending<dev.charaly.runtime.domain.Location> { it.id == here }
                .thenBy { it.name.lowercase() },
        )
    }

    /** Human wording for what a character is doing right now. */
    fun activityLabel(runtime: dev.charaly.runtime.domain.CharacterRuntime): String {
        // A routine's own wording wins: "serving customers" tells the player more than
        // "working", and it is authored, not generated.
        if (runtime.activityLabel.isNotBlank()) return runtime.activityLabel
        return fallbackActivityLabel(runtime.activity)
    }

    fun fallbackActivityLabel(activity: CharacterActivity): String = when (activity) {
        CharacterActivity.IDLE -> "standing around"
        CharacterActivity.WORKING -> "working"
        CharacterActivity.RESTING -> "resting"
        CharacterActivity.TRAVELLING -> "on the move"
        CharacterActivity.TALKING -> "talking to someone"
        CharacterActivity.INVESTIGATING -> "looking into something"
        CharacterActivity.FLEEING -> "running"
        CharacterActivity.UNKNOWN -> ""
    }

    /**
     * How the player is known to this character.
     *
     * Reads the relationship the world actually holds, and reports "someone they have
     * not classified" rather than inventing a neutral default: a stranger being called a
     * friend would be a lie told by the UI.
     */
    fun relationshipLabel(instance: StoryInstance, characterId: CharacterId): String {
        val relationship = instance.worldState.relationships.values.firstOrNull {
            it.sourceId == characterId || it.targetId == characterId
        } ?: return "not met yet"
        return relationship.relationshipType.label
    }
}

// ---------------------------------------------------------------------------
// Snapshot
// ---------------------------------------------------------------------------

data class WorldLocation(
    val id: String,
    val name: String,
    val blurb: String,
    val artwork: dev.charaly.runtime.domain.PackArtwork,
    val accent: Long,
    val presentCharacterIds: List<String>,
    val presentCharacterNames: List<String>,
    val isHere: Boolean,
    val rules: List<String> = emptyList(),
) {
    /** Someone is standing in this place right now. */
    val hasCompany: Boolean get() = presentCharacterIds.isNotEmpty()

    /**
     * Occupants as one readable line.
     *
     * Never empty: a place nobody is in reads "Empty right now", which is a fact about
     * the world rather than a missing field.
     */
    fun occupancyLine(): String = when {
        presentCharacterNames.isEmpty() -> "Empty right now"
        presentCharacterNames.size == 1 -> presentCharacterNames.first()
        presentCharacterNames.size <= 3 -> presentCharacterNames.joinToString(", ")
        else -> "${presentCharacterNames.take(3).joinToString(", ")} + ${presentCharacterNames.size - 3} more"
    }
}

data class WorldPerson(
    val id: String,
    val name: String,
    val role: String,
    val locationName: String,
    val activityLabel: String,
    val accent: Long,
    val artwork: dev.charaly.runtime.domain.PackArtwork,
    val isHere: Boolean,
    val relationshipLabel: String,
    val isNpc: Boolean = false,
) {
    /** The two lines the character card shows: where they are, then what they do. */
    fun whereabouts(): String = listOfNotNull(
        locationName.takeIf { it.isNotBlank() },
        activityLabel.takeIf { it.isNotBlank() },
    ).joinToString("  ·  ")
}

data class WorldSnapshot(
    val worldTitle: String,
    val currentLocationName: String,
    val timeLabel: String,
    val locations: List<WorldLocation>,
    val characters: List<WorldPerson>,
) {
    val isEmpty: Boolean get() = locations.isEmpty() && characters.isEmpty()

    fun location(id: String): WorldLocation? = locations.firstOrNull { it.id == id }

    fun peopleHere(): List<WorldPerson> = characters.filter { it.isHere }

    /** Places with somebody in them, which is what a player looking for company wants. */
    fun occupiedPlaces(): List<WorldLocation> = locations.filter { it.hasCompany }
}