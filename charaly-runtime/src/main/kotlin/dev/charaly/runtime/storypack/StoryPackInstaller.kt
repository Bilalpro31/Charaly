package dev.charaly.runtime.storypack

import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.persistence.StoryPackRepository

/**
 * Installing story packs: the one door content comes through.
 *
 * ```
 * inspect(bytes)          read-only: decode, validate, health. Never writes.
 * import(bytes)           validate -> stage -> re-read -> commit -> register
 * export(packId)          rebuild the archive from what is installed
 * delete(packId)          refuse while stories depend on it, otherwise remove cleanly
 * ```
 *
 * ## The four promises
 *
 * 1. **Nothing installs unvalidated.** `CRITICAL` is not a suggestion; there is no path
 *    from a rejected report to a registry entry.
 * 2. **Nothing installs in halves.** Staging directory, atomic rename, registry last.
 *    An interrupted import leaves litter at worst, and litter is swept.
 * 3. **Updating never rewrites a running story.** Old editions are retained on disk and
 *    stories stay pinned to theirs. A story whose edition is gone is *reported* as needing
 *    migration and left alone - never silently re-pointed at newer content.
 * 4. **Deleting never touches a save.** Saves are another document tree entirely. The
 *    worst case is a save whose pack is gone, which is a visible, recoverable state.
 */
class StoryPackInstaller(
    private val packs: StoryPackRepository,
    private val registry: StoryPackRegistry,
    private val store: PackInstallStore,
    private val engineVersion: String = StoryPackFormat.ENGINE_VERSION,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    /** How many stories currently depend on a pack id - the delete guard. */
    private val storyCountFor: suspend (String) -> Int = { 0 },
) : PackEditionSource {

    /** Dry run. Answers "what would happen", including every problem. */
    suspend fun inspect(bytes: ByteArray): ImportCandidate {
        val decoded = try {
            StoryPackDecoder.decode(bytes, engineVersion, assetPresence = { false })
        } catch (refused: StoryPackArchive.RefusedException) {
            val report = ValidationReport(
                issues = listOf(ValidationIssue.archiveRefusal(refused.code, refused.message.orEmpty())),
            )
            return ImportCandidate(report = report)
        }
        val health = decoded.pack?.let { PackHealthGate.analyse(it) }
        val issues = decoded.issues + health?.issues.orEmpty()
        val report = ValidationReport(issues = issues, census = decoded.bundle?.census())
        val manifest = decoded.manifest
        val existing = manifest?.let { registry.get(it.packId) }
        return ImportCandidate(
            decoded = decoded,
            health = health,
            report = report,
            existingVersion = existing?.packVersion,
            isUpgrade = existing != null && manifest != null && existing.packVersion != manifest.packVersion,
            retainedEditions = existing?.retainedVersions.orEmpty() + listOfNotNull(existing?.packVersion),
        )
    }

    /**
     * Installs a pack from archive bytes.
     *
     * The same path for a downloaded pack, a sideloaded file and the packs Charaly ships
     * with - because the moment a "demo" gets its own shortcut, the shortcut becomes the
     * way everything works and validation becomes decoration.
     */
    suspend fun import(bytes: ByteArray, source: String = InstalledPackRecord.SOURCE_IMPORT): ImportResult {
        val candidate = inspect(bytes)
        if (candidate.isRejected) return ImportResult.Rejected(candidate.report)
        if (!candidate.report.isInstallable) return ImportResult.Rejected(candidate.report)

        val decoded = requireNotNull(candidate.decoded)
        val pack = requireNotNull(decoded.pack)
        val manifest = requireNotNull(decoded.manifest)

        val token = "${nowMs()}-${bytes.size}-${manifest.packVersion}"
        val staging = store.stage(token)
        try {
            val written = mutableMapOf<String, ByteArray>()
            val manifestBytes = StoryPackFormat.manifestJson
                .encodeToString(PackManifest.serializer(), manifest)
                .toByteArray(Charsets.UTF_8)
            written[StoryPackFormat.MANIFEST_PATH] = manifestBytes
            decoded.contents?.textFiles?.forEach { (path, text) -> written[path] = text.toByteArray(Charsets.UTF_8) }
            decoded.contents?.assets?.forEach { (path, data) -> written[path] = data }
            decoded.localization.forEach { (language, table) ->
                val path = PackManifestBuilder.localizationPath(language)
                val encoded = LocalizationCodec.encode(StoryPackFormat.json, table)
                written[path] = encoded.toByteArray(Charsets.UTF_8)
            }
            written.forEach { (path, data) ->
                val target = PackAssetRoot(staging).resolve(path)
                    ?: throw IllegalStateException("unsafe path refused at write time: $path")
                target.parentFile?.mkdirs()
                target.writeBytes(data)
            }

            // Re-read what actually landed. A write that did not survive is caught here,
            // before anything claims this pack is installed.
            // Verified by reading back what actually landed in staging - before anything
            // claims this pack exists.
            val verification = store.loadEditionFrom(staging)
                ?: return run { store.discard(staging); ImportResult.Rejected(candidate.report) }
            if (verification.manifest.sha256.isNotBlank() &&
                PackDigest.of(verification.manifest.files) != verification.manifest.sha256
            ) {
                store.discard(staging)
                return ImportResult.Rejected(candidate.report)
            }

            if (!store.commit(staging, pack.id.value, manifest.packVersion)) {
                store.discard(staging)
                return ImportResult.Rejected(candidate.report)
            }
            // From here the content is whole on disk. Only the claims are left.
            packs.savePack(pack)

            val previous = registry.get(pack.id.value)
            val retained = buildList {
                previous?.retainedVersions.orEmpty().forEach { if (it != manifest.packVersion) add(it) }
                previous?.packVersion?.let { if (it != manifest.packVersion) add(it) }
            }.distinct().filter { version -> store.hasEdition(pack.id.value, version) }

            val now = nowMs()
            val record = InstalledPackRecord(
                packId = pack.id.value,
                packVersion = manifest.packVersion,
                title = pack.title,
                author = pack.author,
                publisher = pack.publisher,
                language = pack.language,
                entryPoint = manifest.entryPoint,
                description = pack.description,
                premise = pack.identity.premise,
                tags = pack.genreChips(),
                coverPath = PackManifestBuilder.coverRefOf(pack),
                checksum = manifest.sha256,
                archiveSha256 = PackDigest.ofBytes(bytes),
                sizeBytes = manifest.sizeBytes,
                installedAtEpochMs = previous?.installedAtEpochMs ?: now,
                updatedAtEpochMs = now,
                enabled = previous?.enabled ?: true,
                source = source,
                retainedVersions = retained,
                characterCount = pack.characters.size,
                locationCount = pack.locations.size,
                scenarioCount = pack.scenarios.size,
            )
            registry.put(record)
            return ImportResult.Installed(
                record = record,
                report = candidate.report,
                upgradedFrom = previous?.packVersion?.takeIf { it != manifest.packVersion },
            )
        } catch (cause: Exception) {
            store.discard(staging)
            throw cause
        }
    }

    /**
     * Exports an installed edition as `.charaly-pack` bytes.
     *
     * Rebuilt from the installed content rather than by copying the original archive: an
     * export is a statement about what is *here*, and it has to be valid on its own terms.
     * That is also what makes export → import a real round trip rather than a file copy.
     */
    suspend fun export(packId: String, version: String? = null): ByteArray? {
        val record = registry.get(packId) ?: return null
        val edition = version ?: record.packVersion
        if (!record.carriesEdition(edition)) return null
        val loaded = store.loadEdition(packId, edition) ?: return null

        val content = StoryPackCodec.encode(loaded.pack)
        val files = linkedMapOf<String, ByteArray>()
        content.forEach { (path, text) -> files[path] = text.toByteArray(Charsets.UTF_8) }
        loaded.assets.listAssets().forEach { path ->
            loaded.assets.bytes(path)?.let { files[path] = it }
        }
        val manifest = PackManifestBuilder.build(
            pack = loaded.pack,
            files = files,
            localization = loaded.localization,
            extraAssetPaths = loaded.assets.listAssets(),
        )
        val all = LinkedHashMap<String, ByteArray>()
        all[StoryPackFormat.MANIFEST_PATH] = StoryPackFormat.manifestJson
            .encodeToString(PackManifest.serializer(), manifest)
            .toByteArray(Charsets.UTF_8)
        all.putAll(files)
        loaded.localization.forEach { (language, table) ->
            all[PackManifestBuilder.localizationPath(language)] = LocalizationCodec.encode(StoryPackFormat.json, table).toByteArray(Charsets.UTF_8)
        }
        return StoryPackArchive.write(all)
    }

    /** Exports any [StoryPack] in hand (an authored one) without installing it first. */
    fun exportPack(pack: StoryPack, localization: Map<String, Map<String, String>> = emptyMap()): ByteArray {
        val content = StoryPackCodec.encode(pack)
        val files = linkedMapOf<String, ByteArray>()
        content.forEach { (path, text) -> files[path] = text.toByteArray(Charsets.UTF_8) }
        val manifest = PackManifestBuilder.build(pack = pack, files = files, localization = localization)
        val all = LinkedHashMap<String, ByteArray>()
        all[StoryPackFormat.MANIFEST_PATH] = StoryPackFormat.manifestJson
            .encodeToString(PackManifest.serializer(), manifest)
            .toByteArray(Charsets.UTF_8)
        all.putAll(files)
        localization.forEach { (language, table) ->
            all[PackManifestBuilder.localizationPath(language)] = LocalizationCodec.encode(StoryPackFormat.json, table).toByteArray(Charsets.UTF_8)
        }
        return StoryPackArchive.write(all)
    }

    /**
     * Deletes a pack.
     *
     * Refuses while any story is running on it unless the caller insists. Insisting keeps
     * every save on disk: a story whose world has gone missing is recoverable the moment
     * the pack is reinstalled, and destroying somebody's playthrough to tidy a directory
     * would be indefensible.
     */
    suspend fun delete(packId: String, force: Boolean = false): DeleteOutcome {
        val record = registry.get(packId) ?: return DeleteOutcome.NotFound(packId)
        val activeStories = storyCountFor(packId)
        if (activeStories > 0 && !force) return DeleteOutcome.Blocked(packId, record.title, activeStories)

        store.deletePack(packId)
        registry.remove(packId)
        runCatching { packs.deletePack(dev.charaly.runtime.domain.StoryPackId(packId)) }
        return DeleteOutcome.Deleted(record, keptSaves = activeStories)
    }

    suspend fun setEnabled(packId: String, enabled: Boolean) {
        val record = registry.get(packId) ?: return
        registry.put(record.copy(enabled = enabled))
    }

    suspend fun installedRecords(): List<InstalledPackRecord> = registry.list()

    /** The active edition of a pack, decoded. */
    suspend fun load(packId: String, version: String? = null): PackInstallStore.LoadedEdition? {
        val record = registry.get(packId) ?: return null
        val edition = version ?: record.packVersion
        return store.loadEdition(packId, edition)
    }

    /**
     * The [dev.charaly.runtime.session.WorldDefinitionResolver] port: content for the
     * edition a story is pinned to.
     *
     * Returns null when the story should fall back to the repository's active edition -
     * which is exactly what happens for saves that predate pinning.
     */
    override suspend fun editionFor(packId: String, version: String): StoryPack? {
        if (version.isBlank()) return null
        if (!store.hasEdition(packId, version)) return null
        return store.loadEdition(packId, version)?.pack
    }

    /**
     * Can this story still be played as its author wrote it?
     *
     * The anti-corruption question of section 9: pinned edition present -> continue;
     * edition gone -> say so, and do not migrate. Silent save corruption is the one outcome
     * that must never be convenient enough to happen by accident.
     */
    suspend fun compatibilityFor(instance: StoryInstance): PackCompatibilityReport {
        val record = registry.get(instance.storyPackId.value)
        val installed = record?.packVersion
        // Both the claim and the content: a registry entry that remembers an edition whose
        // directory is gone is not a playable edition, and saying otherwise is exactly the
        // quiet corruption this path exists to prevent.
        val hasPinned = instance.packVersion.isNotBlank() &&
            record?.carriesEdition(instance.packVersion) == true &&
            store.hasEdition(instance.storyPackId.value, instance.packVersion)
        return when {
            hasPinned -> PackCompatibilityReport.evaluate(
                pinnedPackId = instance.storyPackId.value,
                pinnedVersion = instance.packVersion,
                installedVersion = instance.packVersion,
            )
            else -> PackCompatibilityReport.evaluate(
                pinnedPackId = instance.storyPackId.value,
                pinnedVersion = instance.packVersion,
                installedVersion = installed,
            )
        }
    }

    /** Cold-start housekeeping: no half-installed pack survives a restart. */
    suspend fun sweep() {
        store.sweepStaging()
    }

    // ------------------------------------------------------------------
    // Typed results
    // ------------------------------------------------------------------

    /** What an import would do, without doing it. */
    data class ImportCandidate(
        val decoded: DecodedPack? = null,
        val health: PackHealthGate.Result? = null,
        val report: ValidationReport,
        val existingVersion: String? = null,
        val isUpgrade: Boolean = false,
        val retainedEditions: List<String> = emptyList(),
    ) {
        val packId: String get() = decoded?.manifest?.packId.orEmpty()
        val packVersion: String get() = decoded?.manifest?.packVersion.orEmpty()
        val title: String get() = decoded?.pack?.title ?: decoded?.manifest?.title.orEmpty()
        /** True when the archive could not even be decoded into pack content. */
        val isRejected: Boolean get() = decoded == null || decoded.pack == null

        /** Whether this pack would actually be installed if the user said yes. */
        val isInstallable: Boolean get() = !isRejected && report.isInstallable
    }

    sealed interface ImportResult {
        data class Installed(
            val record: InstalledPackRecord,
            val report: ValidationReport,
            val upgradedFrom: String? = null,
        ) : ImportResult

        data class Rejected(val report: ValidationReport) : ImportResult
    }

    sealed interface DeleteOutcome {
        data class Deleted(val record: InstalledPackRecord, val keptSaves: Int) : DeleteOutcome
        data class Blocked(val packId: String, val title: String, val activeStories: Int) : DeleteOutcome
        data class NotFound(val packId: String) : DeleteOutcome
    }
}

/**
 * Content source for pinned editions.
 *
 * Named separately from [StoryPackInstaller] so the session layer depends on one method
 * and not on the whole installer - the runtime needs "what did 1.1.0 say", and that is the
 * only thing it is allowed to ask.
 */
interface PackEditionSource {
    suspend fun editionFor(packId: String, version: String): StoryPack?
}