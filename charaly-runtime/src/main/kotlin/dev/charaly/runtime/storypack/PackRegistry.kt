package dev.charaly.runtime.storypack

import dev.charaly.runtime.persistence.CharalyStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The install registry: what is on this device, and which editions of it.
 *
 * ## Why a registry and not "whatever is in the packs folder"
 *
 * Because "installed" is a *claim* - that a pack was validated, that its checksum matched,
 * that it landed on disk whole. A directory of files makes none of those claims. The
 * registry is the only place an install becomes true, which is why it is written *last*
 * and atomically: a crash before that line leaves an orphan directory that nothing reports
 * as installed, which is the correct outcome, whereas writing it first would leave an
 * installed-looking pack with no content behind it.
 *
 * ## Editions
 *
 * A record tracks the *active* edition plus every older edition kept on disk. Retention is
 * not tidiness, it is the mechanism behind "a story that started in 1.1.0 carries on with
 * 1.1.0 semantics": as long as the edition is retained, the save is playable exactly as it
 * was, and no migration is required or pretended.
 */
@Serializable
data class InstalledPackRecord(
    val packId: String,
    val packVersion: String,
    val title: String,
    val author: String = "",
    val publisher: String = "",
    val language: String = "tr",
    val entryPoint: String = "",
    val description: String = "",
    val premise: String = "",
    val tags: List<String> = emptyList(),
    /** Cover as a pack-relative asset path, or blank for generated art. */
    val coverPath: String = "",
    /** Pack digest from the manifest - identity of the content, not of the bytes. */
    val checksum: String = "",
    /** Digest of the `.charaly-pack` bytes this was installed from. */
    val archiveSha256: String = "",
    val sizeBytes: Long = 0L,
    val installedAtEpochMs: Long = 0L,
    val updatedAtEpochMs: Long = 0L,
    val enabled: Boolean = true,
    /** Where it came from: `import`, `catalog`, `builtin`. */
    val source: String = SOURCE_IMPORT,
    /** Older editions retained on disk so existing saves stay playable. */
    val retainedVersions: List<String> = emptyList(),
    /** Content counts for the library card, so it needs no full decode to render. */
    val characterCount: Int = 0,
    val locationCount: Int = 0,
    val scenarioCount: Int = 0,
) {
    /** Does this install have [version] on disk, in any capacity? */
    fun carriesEdition(version: String): Boolean =
        version == packVersion || version in retainedVersions

    companion object {
        const val SOURCE_IMPORT = "import"
        const val SOURCE_CATALOG = "catalog"
        const val SOURCE_BUILTIN = "builtin"
    }
}

/** Persistent port. One document, written atomically, reloaded on every cold start. */
interface StoryPackRegistry {
    suspend fun list(): List<InstalledPackRecord>
    suspend fun get(packId: String): InstalledPackRecord?
    suspend fun put(record: InstalledPackRecord)
    suspend fun remove(packId: String)
    fun observe(): Flow<List<InstalledPackRecord>>
}

/**
 * JSON-backed registry: `registry/story-packs.json`.
 *
 * Single document on purpose. There are dozens of packs, not millions, and one atomic
 * file write is a simpler guarantee than a table with a migration history.
 */
class JsonStoryPackRegistry(
    private val storage: CharalyStorage,
    private val json: Json = StoryPackFormat.json,
    private val document: String = DOCUMENT,
) : StoryPackRegistry {

    private val state = MutableStateFlow<List<InstalledPackRecord>>(emptyList())

    override fun observe(): Flow<List<InstalledPackRecord>> = state.asStateFlow()

    override suspend fun list(): List<InstalledPackRecord> {
        if (state.value.isEmpty()) reload()
        return state.value
    }

    override suspend fun get(packId: String): InstalledPackRecord? =
        list().firstOrNull { it.packId == packId }

    override suspend fun put(record: InstalledPackRecord) {
        val updated = list().filterNot { it.packId == record.packId } + record
        persist(updated.sortedBy { it.title.lowercase() })
    }

    override suspend fun remove(packId: String) {
        persist(list().filterNot { it.packId == packId })
    }

    /** Cold start: read the document, tolerate anything unparseable as "nothing installed". */
    suspend fun reload() {
        val raw = storage.read(document) ?: run { state.value = emptyList(); return }
        val decoded = runCatching {
            json.decodeFromString(RegistryDocument.serializer(), raw)
        }.getOrNull()
        state.value = decoded?.records.orEmpty().sortedBy { it.title.lowercase() }
    }

    private suspend fun persist(records: List<InstalledPackRecord>) {
        state.value = records
        storage.write(document, json.encodeToString(RegistryDocument.serializer(), RegistryDocument(records = records)))
    }

    @Serializable
    data class RegistryDocument(
        val schemaVersion: Int = StoryPackFormat.SCHEMA_VERSION,
        val records: List<InstalledPackRecord> = emptyList(),
    )

    companion object {
        const val DOCUMENT = "registry/story-packs.json"
    }
}

/** Registry for tests and previews. */
class InMemoryStoryPackRegistry : StoryPackRegistry {
    private val records = linkedMapOf<String, InstalledPackRecord>()
    private val state = MutableStateFlow<List<InstalledPackRecord>>(emptyList())

    override fun observe(): Flow<List<InstalledPackRecord>> = state.asStateFlow()

    override suspend fun list(): List<InstalledPackRecord> = records.values.toList()

    override suspend fun get(packId: String): InstalledPackRecord? = records[packId]

    override suspend fun put(record: InstalledPackRecord) {
        records[record.packId] = record
        state.value = records.values.toList()
    }

    override suspend fun remove(packId: String) {
        records.remove(packId)
        state.value = records.values.toList()
    }
}