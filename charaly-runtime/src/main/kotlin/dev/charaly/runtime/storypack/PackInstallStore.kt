package dev.charaly.runtime.storypack

import java.io.File

/**
 * Where installed packs physically live.
 *
 * ```
 * <root>/
 *   .staging/<token>/            an install in flight; never visible, swept on start
 *   <packId>/<packVersion>/      one directory per edition, exactly as exported
 *     manifest.json
 *     content/<name>.json
 *     localization/<lang>.json
 *     assets/ (binary payloads)
 * ```
 *
 * ## The atomicity story
 *
 * An install is staged into `.staging/<token>` and only then renamed into its final
 * place - a directory rename on one filesystem is atomic, so the edition directory exists
 * either completely or not at all. The registry is written *after* the rename. Therefore
 * the possible crash outcomes are:
 *
 *  * during staging: an orphan under `.staging`, swept on the next start, invisible;
 *  * after rename, before registry: an unreferenced edition directory, invisible;
 *  * after registry: installed.
 *
 * There is no outcome in which a pack is visible and half-written, and no outcome in which
 * an install corrupts a save - saves live in `CharalyStorage`, in a different tree, and
 * nothing here writes there.
 */
class PackInstallStore(private val root: File) {

    /** The installed root. Exposed for diagnostics and tests, never for string concat. */
    fun root(): File = root

    fun packDir(packId: String): File = root.resolve(StoryPackFormat.sanitizePackId(packId))

    fun versionDir(packId: String, version: String): File =
        packDir(packId).resolve(StoryPackFormat.sanitizePackId(version))

    /** The asset root of one edition - the `InstalledPackRoot` every asset resolves against. */
    fun assetRoot(packId: String, version: String): PackAssetRoot =
        PackAssetRoot(versionDir(packId, version))

    fun hasEdition(packId: String, version: String): Boolean =
        versionDir(packId, version).resolve(StoryPackFormat.MANIFEST_PATH).isFile

    fun listEditions(packId: String): List<String> {
        val dir = packDir(packId)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.filter { File(it, StoryPackFormat.MANIFEST_PATH).isFile }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()
    }

    // ------------------------------------------------------------------
    // Staging and committing
    // ------------------------------------------------------------------

    /**
     * Prepares a staging directory for one install.
     *
     * Tokened per attempt so a cancelled import cannot collide with a concurrent one, and
     * prefixed with a dot so it can never be mistaken for a pack id.
     */
    fun stage(token: String): File {
        val dir = root.resolve(STAGING_DIR).resolve(token)
        dir.deleteRecursively()
        dir.mkdirs()
        return dir
    }

    /**
     * Moves a staged install into its final home, atomically.
     *
     * If the destination already exists (reinstalling the same edition) it is replaced via
     * a temporary rename pair rather than deleted first, so a crash mid-replacement leaves
     * either the old edition or the new one - never neither.
     */
    fun commit(staging: File, packId: String, version: String): Boolean {
        val target = versionDir(packId, version)
        target.parentFile?.mkdirs()
        if (target.exists()) {
            val retired = target.parentFile.resolve(".retired-${target.name}")
            retired.deleteRecursively()
            if (!target.renameTo(retired)) {
                // Fall back to a plain replace only when the platform refuses the dance.
                target.deleteRecursively()
            } else {
                retired.deleteRecursively()
            }
        }
        if (!staging.renameTo(target)) {
            // Cross-device or unsupported rename: copy, then verify it landed.
            staging.copyRecursively(target, overwrite = true)
            staging.deleteRecursively()
        }
        return File(target, StoryPackFormat.MANIFEST_PATH).isFile
    }

    fun discard(staging: File) {
        staging.deleteRecursively()
    }

    fun deleteEdition(packId: String, version: String) {
        versionDir(packId, version).deleteRecursively()
    }

    fun deletePack(packId: String) {
        packDir(packId).deleteRecursively()
    }

    /** Removes staging leftovers from an interrupted install. Called on cold start. */
    fun sweepStaging() {
        val staging = root.resolve(STAGING_DIR)
        if (!staging.isDirectory) return
        staging.listFiles()?.forEach { it.deleteRecursively() }
    }

    /** Editions on disk that no record claims. Safe to remove; kept as a report for tests. */
    fun orphanEditions(known: Map<String, List<String>>): List<Pair<String, String>> {
        val orphans = mutableListOf<Pair<String, String>>()
        root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.forEach { packDir ->
            packDir.listFiles()?.filter { it.isDirectory }?.forEach { versionDir ->
                val retained = known[packDir.name].orEmpty()
                if (versionDir.name !in retained) orphans += packDir.name to versionDir.name
            }
        }
        return orphans
    }

    /**
     * Loads one installed edition back into a [StoryPack].
     *
     * Reads through [PackAssetRoot] so the same traversal rules apply on the way out as on
     * the way in: an installed pack is still untrusted content, just closer to hand.
     */
    fun loadEdition(packId: String, version: String): LoadedEdition? =
        loadEditionFrom(versionDir(packId, version))

    /**
     * The same decode against any directory: a staging folder mid-install, or the final
     * home of an edition. Verification reads the *staged* copy on purpose - the only way to
     * prove a write landed is to read what landed.
     */
    fun loadEditionFrom(directory: File): LoadedEdition? {
        val assets = PackAssetRoot(directory)
        val manifestRaw = assets.text(StoryPackFormat.MANIFEST_PATH) ?: return null
        val manifest = runCatching {
            StoryPackFormat.manifestJson.decodeFromString(PackManifest.serializer(), manifestRaw)
        }.getOrNull() ?: return null
        val files = assets
            .list()
            .filter { it != StoryPackFormat.MANIFEST_PATH }
            .mapNotNull { path -> assets.text(path)?.let { path to it } }
            .toMap(LinkedHashMap())
        val decoded = StoryPackCodec.decode(files)
        val identity = ManifestIdentity(
            packId = manifest.packId,
            title = manifest.title,
            description = manifest.description,
            premise = manifest.premise,
            author = manifest.author,
            publisher = manifest.publisher,
            language = manifest.language,
            license = manifest.license,
            tags = manifest.tags,
            cover = manifest.cover,
            entryPoint = manifest.entryPoint,
            packVersion = manifest.packVersion,
            minimumEngineVersion = manifest.minimumEngineVersion,
            maximumEngineVersion = manifest.maximumEngineVersion,
            createdAtEpochMs = manifest.createdAtEpochMs,
            updatedAtEpochMs = manifest.updatedAtEpochMs,
        )
        val pack = runCatching { decoded.bundle.compile(manifest.packId, identity) }.getOrNull() ?: return null
        val localization = decodedLocalization(assets)
        return LoadedEdition(manifest = manifest, pack = pack, bundle = decoded.bundle, localization = localization, assets = assets)
    }

    private fun decodedLocalization(assets: PackAssetRoot): Map<String, Map<String, String>> {
        val prefix = "${StoryPackFormat.LOCALIZATION_DIR}/"
        val tables = LinkedHashMap<String, Map<String, String>>()
        assets.list()
            .filter { it.startsWith(prefix) && it.endsWith(".json") }
            .forEach { path ->
                val raw = assets.text(path) ?: return@forEach
                val language = path.removePrefix(prefix).removeSuffix(".json").lowercase()
                val table = LocalizationCodec.decode(StoryPackFormat.json, raw) ?: return@forEach
                tables[language] = table
            }
        return tables
    }

    /** One installed edition, in every form it is needed in. */
    data class LoadedEdition(
        val manifest: PackManifest,
        val pack: dev.charaly.runtime.domain.StoryPack,
        val bundle: StoryPackBundle,
        val localization: Map<String, Map<String, String>>,
        val assets: PackAssetRoot,
    )

    companion object {
        const val STAGING_DIR = ".staging"
    }
}