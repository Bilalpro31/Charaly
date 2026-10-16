package dev.charaly.runtime.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `catalog.json` - the remotely published model catalog.
 *
 * ## Why a manifest and not a live search
 *
 * The Hub discovery client answers "what exists"; a manifest answers "what Charaly
 * recommends, and exactly which bytes to fetch". Those are different questions and only the
 * second one is safe to automate against: a manifest pins a URL **and** the SHA-256 of the
 * file behind it, so a download can be verified rather than trusted. Discovery cannot do
 * that, because a repository's contents change underneath a listing.
 *
 * ## Why it is optional
 *
 * Charaly's core promise is that it works in aeroplane mode. The catalog is a way to get a
 * model *onto* the device; everything else - import, story chat, generation - must go on
 * without it. So this type is pure data over a string: fetching is
 * [RemoteModelCatalog]'s problem, and failing to fetch is not an error any player sees.
 *
 * Schema:
 * ```json
 * {
 *   "version": "2026-10-08",
 *   "generatedAt": "2026-10-08T00:00:00Z",
 *   "items": [ { "id": "...", "name": "...", "publisher": "...", "download": { "url": "...", "sha256": "..." } } ]
 * }
 * ```
 */
@Serializable
data class CatalogManifest(
    /** The manifest's own version/date stamp, shown as "catalog updated ...". */
    val version: String = "",
    /** ISO-8601, for display only. Never parsed into a clock. */
    val generatedAt: String = "",
    val items: List<ModelCatalogItem> = emptyList(),
) {
    /**
     * The ids this manifest publishes, in order.
     *
     * Duplicates keep their first position: a manifest with the same id twice is a broken
     * manifest, and silently taking the last one would make the catalog flicker depending
     * on which half of the document the reader happened to reach.
     */
    val ids: List<String> get() = items.map { it.id }.distinct()

    companion object {
        /**
         * Strict about the envelope, forgiving about the entries.
         *
         * A document that is not JSON at all, or has no usable entry in it, is `null` - the
         * caller then falls back to the built-in catalog rather than rendering a Hub with a
         * parse stack trace in it. Individual entries that fail validation are dropped rather
         * than failing the document, because one publisher typing a blank name must not blank
         * out everybody else's model.
         */
        fun parse(raw: String): CatalogManifest? {
            if (raw.isBlank()) return null
            val root = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(raw)
            }.getOrNull() as? kotlinx.serialization.json.JsonObject ?: return null

            // Entries are decoded ONE AT A TIME on purpose.
            //
            // `ModelCatalogItem`'s `init` rejects a blank id or name - correctly, a nameless
            // model is not a model - but decoding the whole array in one shot would make one
            // publisher's typo blank out every other publisher's entry. A manifest is a
            // directory assembled by several hands; tolerance belongs at the seam.
            val array = root["items"] as? kotlinx.serialization.json.JsonArray ?: return null
            val usable = array.mapNotNull { element ->
                runCatching {
                    defaultManifestJson.decodeFromJsonElement(ModelCatalogItem.serializer(), element)
                }.getOrNull()
            }
            if (usable.isEmpty()) return null

            val version = (root["version"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            val generatedAt = (root["generatedAt"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            return CatalogManifest(version = version, generatedAt = generatedAt, items = usable)
        }
    }
}

/**
 * Which source a catalog's items came from, and whether the network was involved.
 *
 * Returned rather than thrown so the Model Hub can say "showing the built-in list" instead
 * of showing nothing at all with no explanation.
 */
sealed interface CatalogSource {
    /** Fresh from the remote manifest. */
    data class Remote(val manifestVersion: String, val itemCount: Int) : CatalogSource

    /** The remote manifest could not be used; the in-binary list is being shown. */
    data class Fallback(val reason: String) : CatalogSource

    /** Refresh has not been attempted yet. */
    data object NotLoaded : CatalogSource
}

internal val defaultManifestJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    isLenient = true
}