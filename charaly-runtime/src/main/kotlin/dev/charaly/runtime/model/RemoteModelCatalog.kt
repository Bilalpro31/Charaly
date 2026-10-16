package dev.charaly.runtime.model

import dev.charaly.runtime.net.HttpTransport

/**
 * A `ModelCatalog` that reads `catalog.json` and falls back to what ships inside the app.
 *
 * ## The one rule
 *
 * **Never fail the Model Hub.** There are three ways to end up showing the built-in list -
 * no network, a 404, a document somebody edited by hand and broke - and none of them is a
 * reason for the app to stop working. A player who already has a model installed must still
 * be able to chat with it while this class is failing to reach anything at all, which is why
 * nothing here is on the generation path.
 *
 * ## Merge policy
 *
 * Remote entries win on a shared id; the built-in list fills in anything the manifest did
 * not mention. So a manifest that ships two new models widens the catalog rather than
 * replacing it, and a manifest that loses an entry does not silently unpublish a model a
 * player may already have downloaded.
 *
 * Installed state is *not* tracked here on purpose: whether a file is on the device is
 * `ModelRegistry`'s answer, and duplicating it in a catalog is how "installed" and
 * "downloadable" start disagreeing.
 */
class RemoteModelCatalog(
    private val transport: HttpTransport,
    private val manifestUrl: String,
    private val fallback: ModelCatalog = BuiltInModelCatalog(),
) : ModelCatalog {

    @Volatile
    private var merged: List<ModelCatalogItem> = fallback.items

    @Volatile
    private var source: CatalogSource = CatalogSource.NotLoaded

    /** Which source the visible list came from. Rendered as a quiet footnote, not an error. */
    val catalogSource: CatalogSource get() = source

    override val items: List<ModelCatalogItem>
        get() = merged

    /**
     * Fetches the manifest and rebuilds the list.
     *
     * Suspend and honest about its outcome: it returns [CatalogSource.Fallback] with a
     * human reason rather than throwing, so the caller never has to wrap it in a
     * `runCatching` to avoid a blank screen.
     */
    suspend fun refresh(): CatalogSource {
        val result = runCatching { transport.getText(manifestUrl) }
        val response = result.getOrElse { error ->
            return settle(CatalogSource.Fallback(networkReason(error)))
        }
        if (!response.isSuccessful || response.body.isBlank()) {
            return settle(
                CatalogSource.Fallback(
                    if (response.code == 0) networkReason(null) else "the catalog server answered ${response.code}",
                ),
            )
        }
        val manifest = CatalogManifest.parse(response.body)
            ?: return settle(CatalogSource.Fallback("the catalog document could not be read"))
        val combined = merge(remote = manifest.items, base = fallback.items)
        merged = combined
        source = CatalogSource.Remote(manifest.version, combined.size)
        return source
    }

    /**
     * Puts the list back to the built-in one without a network round trip.
     *
     * Used when the app knows it is offline, so the Hub does not burn a request it cannot
     * complete and then announce the failure.
     */
    fun useFallback(reason: String = "offline"): CatalogSource = settle(CatalogSource.Fallback(reason))

    private fun settle(next: CatalogSource): CatalogSource {
        if (next is CatalogSource.Fallback) {
            merged = fallback.items
        }
        source = next
        return next
    }

    private fun merge(remote: List<ModelCatalogItem>, base: List<ModelCatalogItem>): List<ModelCatalogItem> {
        val byId = LinkedHashMap<String, ModelCatalogItem>()
        remote.forEach { byId[it.id] = it }
        base.forEach { if (it.id !in byId) byId[it.id] = it }
        return byId.values.toList()
    }

    private fun networkReason(error: Throwable?): String = when {
        error == null -> "no network"
        else -> error.message?.takeIf { it.isNotBlank() } ?: "no network"
    }

    companion object {
        /**
         * The built-in fallback document, exposed so it can be shipped as an asset or served
         * as the same `catalog.json` a static host would serve. Byte-identical to
         * [BuiltInModelCatalog], which is what keeps "what the app can do offline" and "what
         * the manifest says" from drifting.
         */
        fun builtinManifest(): CatalogManifest = CatalogManifest(
            version = "builtin",
            generatedAt = "",
            items = BuiltInModelCatalog.DEFAULT,
        )

        fun builtinManifestJson(): String =
            defaultManifestJson.encodeToString(CatalogManifest.serializer(), builtinManifest())
    }
}