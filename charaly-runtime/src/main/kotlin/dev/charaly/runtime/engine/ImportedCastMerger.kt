package dev.charaly.runtime.engine

import dev.charaly.runtime.domain.CharacterDefinition
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.Location
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.Routine
import dev.charaly.runtime.domain.StartingScenario
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.StoryPackId
import dev.charaly.runtime.domain.StoryTime
import dev.charaly.runtime.domain.CharacterActivity

/**
 * Puts imported characters into a running story.
 *
 * ## The problem this solves properly
 *
 * An imported card is a person with no place in any existing world. Dropping one into a
 * pack's character list is not enough, because a `StoryInstance` is built from a pack and
 * the pack's location graph is what makes a character *reachable*: `StoryInstanceFactory`
 * places every character at a declared location, and the event engine refuses to move
 * anybody anywhere the pack does not define. An imported character with no location either
 * fails to instantiate or exists somewhere nobody can walk to.
 *
 * So importing a character into a story also gives them a place to be found. Not a
 * fabricated district - a *room* attached to the story's starting location, named after
 * the character. The world gains exactly one place and exactly one resident, both derived
 * from what the user supplied, and everything else is untouched.
 *
 * ## Why the cast never leaks across packs
 *
 * The merged characters are written into a **copy** of the pack with its own
 * [StoryPackId]. The original pack object is not modified, so:
 *
 *  * a second story from the same pack does not inherit the imported character;
 *  * `PackEventIsolationTest` and `StoryIsolationTest` keep passing, because those walk the
 *    pack's own id space;
 *  * deleting the story cannot delete a character out of a shipped pack.
 *
 * This is the same rule the rest of the codebase follows: a playthrough owns its world,
 * and importing a character into one playthrough must not edit the template.
 */
object ImportedCastMerger {

    /** The pack id suffix that marks a pack as "a story's own copy, with its own cast". */
    const val MERGED_PACK_SUFFIX = "-cast"

    /**
     * Returns a pack that is [base] plus [imported], as an independent copy.
     *
     * Returns [base] unchanged when nothing is being added, so the common path does not
     * create a pointless second pack id.
     */
    fun merge(base: StoryPack, imported: List<CharacterDefinition>): StoryPack {
        if (imported.isEmpty()) return base

        val existingIds = base.characterIds.map { it.value }.toSet()
        val addedCharacters = imported.map { character ->
            // A collision inside one pack would be a silent overwrite of the pack's own
            // character, so an imported one is renamed rather than merged over.
            if (character.id.value !in existingIds) {
                character
            } else {
                character.copy(id = CharacterId("${character.id.value}-imported"))
            }
        }
        val allIds = (existingIds + addedCharacters.map { it.id.value }).toSet()

        val opening = openingLocationOf(base)
        val homeIds = opening?.let { listOf(roomIdFor(it, addedCharacters)) }.orEmpty()
        val newLocations = buildList {
            opening?.let { add(roomFor(it, addedCharacters, allIds)) }
        }

        return base.copy(
            // A new id, so this copy is its own world with its own event and memory space.
            id = StoryPackId("${base.id.value}$MERGED_PACK_SUFFIX"),
            characters = base.characters + addedCharacters,
            locations = base.locations + newLocations,
            scenarios = base.scenarios.map { scenario ->
                scenario.copy(
                    castCharacterIds = (scenario.castCharacterIds + addedCharacters.map { it.id }).distinct(),
                )
            },
            personas = base.personas,
        )
    }

    /**
     * Places every character that has nowhere to be at the story's opening location.
     *
     * A character whose card supplied no starting location gets one, because a character
     * the engine cannot place is a character the player can never meet. Anything the pack
     * already placed is left alone.
     */
    fun placeUnplacedCharacters(
        pack: StoryPack,
        story: dev.charaly.runtime.domain.StoryInstance,
    ): dev.charaly.runtime.domain.StoryInstance {
        val playerLocation = EventEngine.currentPlayerLocation(story)
        val fallback = playerLocation
            ?: pack.locations.firstOrNull()?.id
            ?: return story

        val homeless = story.characters.values.filter { it.locationId == null }.map { it.characterId }
        if (homeless.isEmpty()) return story

        var current = story
        val engine = EventEngine(dev.charaly.runtime.domain.WorldDefinition(pack.characters, pack.locations))
        homeless.forEach { id ->
            val applied = engine.applyImmediately(
                current,
                dev.charaly.runtime.domain.events.CharacterMoved(
                    characterId = id,
                    from = current.characters[id]?.locationId,
                    to = fallback,
                ),
            )
            if (applied is EventApplication.Applied) current = applied.instance
        }
        return current
    }

    /** The location a pack's default opening begins in. */
    private fun openingLocationOf(pack: StoryPack): LocationId? =
        pack.scenarios.firstOrNull()?.startLocationId
            ?: pack.characters.firstOrNull()?.startingLocationId
            ?: pack.locations.firstOrNull()?.id

    /**
     * A room next to [parent] where the imported characters can be found.
     *
     * Named after the characters rather than given an invented premise, because the app
     * has no basis for a description of a place it invented. One room, however many
     * characters: a pack that gained three rooms per import would make the location graph
     * unreadable, and one room is enough for them all to be in the same place.
     */
    private fun roomFor(
        parent: LocationId,
        characters: List<CharacterDefinition>,
        takenIds: Set<String>,
    ): Location {
        val who = characters.joinToString(" and ") { it.name }
        return Location(
            id = roomIdFor(parent, characters),
            name = if (characters.size == 1) "${characters.first().name}'s room" else "$who, here",
            description = "Where $who ${if (characters.size == 1) "is" else "are"} in this story.",
            summaryLine = "$who, in this story.",
            connections = listOf(parent),
            startingOccupants = characters.map { it.id },
            tags = listOf("imported"),
        ).let { location ->
            // A collision would make two locations share an id and the graph ambiguous, so
            // the suffix is extended until it is unique.
            if (location.id.value in takenIds) {
                location.copy(id = LocationId("${location.id.value}-${characters.size}"))
            } else {
                location
            }
        }
    }

    private fun roomIdFor(parent: LocationId, characters: List<CharacterDefinition>): LocationId {
        val stem = characters.joinToString("-") { it.id.value }.ifBlank { "imported" }
        return LocationId("$parent-$stem-room")
    }
}

/**
 * A character placed in a story, for the New Story cast picker.
 *
 * Where [CharacterDefinition] is who someone is, this is where they are *right now* in a
 * particular playthrough - which is the fact the cast screen needs and the one a static
 * definition cannot answer.
 */
data class CastableImportedCharacter(
    val definition: CharacterDefinition,
    val locationId: LocationId?,
    val locationName: String,
    val routineSummary: String = "",
) {
    val id: CharacterId get() = definition.id

    val isScheduled: Boolean get() = definition.routine.isScheduled
}

/** Reads a pack's imported-character placements into the picker's shape. */
object ImportedCastReader {

    fun castable(
        pack: StoryPack,
        importedIds: Set<CharacterId>,
    ): List<CastableImportedCharacter> = importedIds
        .mapNotNull { id ->
            val definition = pack.characters.firstOrNull { it.id == id } ?: return@mapNotNull null
            val locationId = definition.startingLocationId
                ?: pack.startLocationOf(id)
            CastableImportedCharacter(
                definition = definition,
                locationId = locationId,
                locationName = locationId?.let { pack.location(it)?.name }.orEmpty(),
                routineSummary = definition.routine.summary,
            )
        }
        .sortedBy { it.definition.name.lowercase() }
}