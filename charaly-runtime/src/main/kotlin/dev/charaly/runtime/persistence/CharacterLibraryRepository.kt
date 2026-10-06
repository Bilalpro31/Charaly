package dev.charaly.runtime.persistence

import dev.charaly.runtime.compat.CharacterCardPreview
import dev.charaly.runtime.domain.CharacterId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A character the user imported, as stored.
 *
 * ## Why imported characters are stored, not folded into a pack
 *
 * A card describes one person. Turning it into a `StoryPack` on import would be the
 * simplest thing to write and the wrong one: the user would get a world containing a
 * character they did not ask for a world for, in a place that does not exist, with a
 * premise the app invented. They asked to import a character.
 *
 * So the card lands here, in a library, and the story flow *offers* it. Adding it to a
 * story is a separate, explicit act.
 *
 * The card's own JSON is kept in [rawJson] so a later version of the importer can read a
 * field this one ignores, without the user having to find the file again. It is never
 * replayed into the runtime - only [preview] is, and only through
 * [CharacterCardPreview.toCharacterDefinition].
 */
@Serializable
data class ImportedCharacter(
    val preview: CharacterCardPreview,
    /** The card exactly as read, kept for forward compatibility. Never parsed at runtime. */
    val rawJson: String,
    val importedAtEpochMs: Long = 0L,
) {
    val id: CharacterId get() = CharacterId(preview.id)

    val name: String get() = preview.name
}

/**
 * The user's imported characters.
 *
 * One JSON document on the same storage port as stories and the model registry. No
 * database, no server, no migration framework: the whole library is a handful of small
 * records, and a document that fails to decode is treated as empty rather than as a
 * startup error, because a corrupt library must not stop the app from opening a story the
 * user actually wants.
 */
interface CharacterLibraryRepository {
    suspend fun list(): List<ImportedCharacter>

    suspend fun get(id: CharacterId): ImportedCharacter?

    /**
     * Saves a card.
     *
     * Idempotent on the card's own id: importing the same card twice updates the existing
     * entry rather than creating a second one, because the id is derived from the name and
     * a duplicate is not something a user can tell apart or delete twice.
     */
    suspend fun save(character: ImportedCharacter): ImportedCharacter

    suspend fun delete(id: CharacterId)

    /** Whether an id is already taken, for resolving a collision before writing. */
    suspend fun contains(id: CharacterId): Boolean = get(id) != null

    /**
     * Saves a card under an id that is not already taken.
     *
     * A collision is real rather than hypothetical: two different people can both be
     * called "Ash", and both cards slugify to the same id. Overwriting would make one of
     * them unreachable, so the second import is given a suffix and both stay usable.
     * Deterministic - the same two cards in the same order always produce the same ids.
     *
     * "The same card" is decided by content, not by id: an identical card re-imported
     * updates its entry rather than arriving as a numbered twin the user cannot tell apart
     * or delete twice. Two cards that merely share a name are different cards.
     */
    suspend fun saveResolvingCollisions(character: ImportedCharacter): ImportedCharacter
}

class JsonCharacterLibraryRepository(
    private val storage: CharalyStorage,
    private val json: Json = defaultJson,
) : CharacterLibraryRepository {

    private var cached: List<ImportedCharacter>? = null

    override suspend fun list(): List<ImportedCharacter> =
        characters().sortedBy { it.name.lowercase() }

    override suspend fun get(id: CharacterId): ImportedCharacter? =
        characters().firstOrNull { it.preview.id == id.value }

    override suspend fun save(character: ImportedCharacter): ImportedCharacter {
        val current = characters()
        val kept = current.filterNot { it.preview.id == character.preview.id }
        val next = kept + character
        persist(next)
        return character
    }

    override suspend fun delete(id: CharacterId) {
        val current = characters()
        if (current.none { it.preview.id == id.value }) return
        persist(current.filterNot { it.preview.id == id.value })
    }

    override suspend fun saveResolvingCollisions(
        character: ImportedCharacter,
    ): ImportedCharacter {
        // An identical card is the *same* card, so it updates in place. Deciding this by
        // content rather than by id is what stops a user who imports the same file twice
        // from finding two entries they cannot tell apart and can only delete one at a time.
        val identical = get(CharacterId(character.preview.id))
            ?.takeIf { it.rawJson == character.rawJson }
            ?: return saveResolvingNameCollisions(character)
        return save(character.copy(preview = character.preview.copy(id = identical.preview.id)))
    }

    /** The two different cards that share a name. */
    private suspend fun saveResolvingNameCollisions(
        character: ImportedCharacter,
    ): ImportedCharacter {
        var candidate = character.preview.id
        var suffix = 2
        while (get(CharacterId(candidate)) != null) {
            candidate = "${character.preview.id}-$suffix"
            suffix++
        }
        if (candidate == character.preview.id) return save(character)
        return save(character.copy(preview = character.preview.copy(id = candidate)))
    }

    private suspend fun characters(): List<ImportedCharacter> {
        cached?.let { return it }
        val raw = storage.read(DOCUMENT)
        val decoded = raw
            ?.let { runCatching { json.decodeFromString(LibraryDocument.serializer(), it) }.getOrNull() }
            ?.characters
            .orEmpty()
        cached = decoded
        return decoded
    }

    private suspend fun persist(characters: List<ImportedCharacter>) {
        storage.write(
            DOCUMENT,
            json.encodeToString(LibraryDocument.serializer(), LibraryDocument(characters)),
        )
        cached = characters
    }

    companion object {
        const val DOCUMENT = "characters/library.json"

        val defaultJson: Json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
            classDiscriminator = "type"
        }
    }
}

@Serializable
private data class LibraryDocument(
    val characters: List<ImportedCharacter> = emptyList(),
)

/** In-memory library for tests and previews. */
class InMemoryCharacterLibraryRepository(
    initial: List<ImportedCharacter> = emptyList(),
) : CharacterLibraryRepository {

    private val characters = initial.associateBy { it.preview.id }.toMutableMap()

    override suspend fun list(): List<ImportedCharacter> = characters.values.sortedBy { it.name.lowercase() }

    override suspend fun get(id: CharacterId): ImportedCharacter? = characters[id.value]

    override suspend fun save(character: ImportedCharacter): ImportedCharacter {
        characters[character.preview.id] = character
        return character
    }

    override suspend fun delete(id: CharacterId) {
        characters.remove(id.value)
    }

    override suspend fun saveResolvingCollisions(
        character: ImportedCharacter,
    ): ImportedCharacter {
        val identical = characters[character.preview.id]
            ?.takeIf { it.rawJson == character.rawJson }
        if (identical != null) {
            return save(character.copy(preview = character.preview.copy(id = identical.preview.id)))
        }

        var candidate = character.preview.id
        var suffix = 2
        while (characters.containsKey(candidate)) {
            candidate = "${character.preview.id}-$suffix"
            suffix++
        }
        if (candidate == character.preview.id) return save(character)
        return save(character.copy(preview = character.preview.copy(id = candidate)))
    }
}