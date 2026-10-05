package dev.charaly.runtime.persistence

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * Versioned persistence.
 *
 * ## Why this exists
 *
 * Charaly writes plain JSON documents, and the domain model has grown: memories gained
 * tiers and visibility, characters gained routines and roles, relationships gained
 * lifecycle fields. Relying on `@Serializable` defaults alone is *mostly* enough -
 * `ignoreUnknownKeys` plus defaults reads an old document fine.
 *
 * It is not enough in one direction: a document written by a *newer* Charaly that an
 * older one reads can silently drop fields, and there is no way to tell afterwards
 * whether what was lost was load-bearing. Writing a version into every document makes
 * that state observable, and it makes a deliberate migration possible instead of
 * accidental.
 *
 * ## What is deliberately not here
 *
 * No field rewriting. Migrations are additive-by-default and every change in this
 * project has been expressible as a new optional field, because a migration that
 * rewrites a user's stories is a migration that can lose them. [Migration.needsWork]
 * is the hook for the day that stops being true - and it reports rather than guesses.
 */
object SchemaMigration {

    /**
     * The current document schema.
     *
     * Bump this only when a change genuinely cannot be expressed as a new optional
     * field with a default.
     */
    const val CURRENT_VERSION = 2

    const val VERSION_FIELD = "schemaVersion"

    /**
     * A document that was written before versioning existed.
     *
     * Treated as version 1 rather than "unknown", because "unknown" would suggest the
     * document might be from a future build and should be discarded - and discarding a
     * user's stories is the one outcome this whole mechanism exists to prevent.
     */
    const val LEGACY_VERSION = 1

    private val lenientJson: Json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Reads a document's declared version without decoding its whole body. */
    fun versionOf(raw: String): Int = runCatching {
        val obj = lenientJson.parseToJsonElement(raw) as? JsonObject ?: return@runCatching LEGACY_VERSION
        obj[VERSION_FIELD]?.jsonPrimitive?.int ?: LEGACY_VERSION
    }.getOrDefault(LEGACY_VERSION)

    /**
     * Stamps a document with the current version.
     *
     * Applied to every write, so version tracking costs nothing and cannot be forgotten
     * at a call site.
     */
    fun stamp(raw: String): String = runCatching {
        val obj = lenientJson.parseToJsonElement(raw).let { it as? JsonObject ?: return@runCatching raw }
        val json = JsonObject(obj + (VERSION_FIELD to kotlinx.serialization.json.JsonPrimitive(CURRENT_VERSION)))
        lenientJson.encodeToString(JsonObject.serializer(), json)
    }.getOrDefault(raw)

    /**
     * What (if anything) has to happen to a document before it can be read.
     *
     * Reported rather than applied: the repository logs it, and the UI can tell the user
     * their library was upgraded. Silently "handling" a migration is how data gets
     * lost without anyone noticing.
     */
    fun needsWork(documentVersion: Int): Migration {
        if (documentVersion >= CURRENT_VERSION) return Migration.NONE
        return when (documentVersion) {
            LEGACY_VERSION -> Migration(
                from = LEGACY_VERSION,
                to = CURRENT_VERSION,
                // v1 -> v2 was additive only: the new memory and routine fields all have
                // defaults, so an old document reads correctly as written. Nothing is
                // rewritten, which is why no old story can be damaged here.
                description = "Upgraded an older save. Nothing was changed - new fields " +
                    "took their defaults.",
            )
            else -> Migration(
                from = documentVersion,
                to = CURRENT_VERSION,
                description = "This save was written by a newer version of Charaly. It " +
                    "will be read as far as this version understands.",
            )
        }
    }
}

/** One migration step, as reported to the user. */
data class Migration(
    val from: Int,
    val to: Int,
    val description: String = "",
) {
    val isRequired: Boolean get() = from != to

    companion object {
        val NONE = Migration(from = SchemaMigration.CURRENT_VERSION, to = SchemaMigration.CURRENT_VERSION)
    }
}