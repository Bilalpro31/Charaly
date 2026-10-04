package dev.charaly.runtime.persistence

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.EntityId
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.StoryPackId
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.io.InputStream

/**
 * Persistence ports.
 *
 * There is exactly one persistence implementation family in Charaly: a set of
 * JSON documents written through [CharalyStorage]. On Android the storage is
 * app-private files; in tests it is a temp directory. No Room, no SQLite, no
 * server - the data is a handful of small structured documents.
 */
interface CharalyStorage {
    suspend fun read(name: String): String?
    suspend fun write(name: String, contents: String)
    suspend fun delete(name: String)
    suspend fun list(prefix: String): List<String>
}

/** Simple file-backed storage. Used on Android and in tests. */
class FileCharalyStorage(private val root: File) : CharalyStorage {

    init {
        root.mkdirs()
    }

    override suspend fun read(name: String): String? {
        val file = root.resolve(name)
        return if (file.isFile) file.readText() else null
    }

    override suspend fun write(name: String, contents: String) {
        val file = root.resolve(name)
        file.parentFile?.mkdirs()
        // Atomic-ish write: a crash mid-save must not corrupt the story.
        val tmp = root.resolve("$name.tmp")
        tmp.writeText(contents)
        if (!tmp.renameTo(file)) {
            file.writeText(contents)
            tmp.delete()
        }
    }

    override suspend fun delete(name: String) {
        root.resolve(name).delete()
    }

    /**
     * Lists the *relative paths* under [prefix] (e.g. "stories/s1.json"), so a
     * caller can hand the name straight back to [read].
     */
    override suspend fun list(prefix: String): List<String> =
        root.walkTopDown()
            .filter { it.isFile }
            .mapNotNull { file -> file.relativeToOrNull(root)?.path?.replace('\\', '/') }
            .filter { it.startsWith(prefix) }
            .sorted()
            .toList()

}

/** In-memory storage for tests and previews. */
class InMemoryCharalyStorage : CharalyStorage {
    private val files = linkedMapOf<String, String>()

    override suspend fun read(name: String): String? = files[name]

    override suspend fun write(name: String, contents: String) {
        files[name] = contents
    }

    override suspend fun delete(name: String) {
        files.remove(name)
    }

    override suspend fun list(prefix: String): List<String> =
        files.keys.filter { it.startsWith(prefix) }.sorted()
}

/** Repository for story packs (static definitions). */
interface StoryPackRepository {
    suspend fun listPacks(): List<StoryPack>
    suspend fun getPack(id: StoryPackId): StoryPack?
    suspend fun savePack(pack: StoryPack)
    suspend fun deletePack(id: StoryPackId)
    fun observePacks(): Flow<List<StoryPack>>
}

/** Repository for running stories (the authoritative world). */
interface StoryInstanceRepository {
    suspend fun listInstances(): List<StoryInstance>
    suspend fun getInstance(id: StoryInstanceId): StoryInstance?
    suspend fun saveInstance(instance: StoryInstance)
    suspend fun deleteInstance(id: StoryInstanceId)
    fun observeInstances(): Flow<List<StoryInstance>>
    suspend fun latestFor(packId: StoryPackId): StoryInstance?
}

/**
 * The Charaly runtime's persistence facade: one entry point used by the UI and
 * the session layer.
 */
interface CharalyRepository : StoryPackRepository, StoryInstanceRepository {

    /** Everything about one running story, restored exactly. */
    suspend fun exportStory(instanceId: StoryInstanceId): ExportedStory?

    suspend fun importStory(exported: ExportedStory): StoryInstance?

    /** Hard guarantee for section 27: launch offline, reload, continue. */
    suspend fun restoreAll(): RestoredWorld
}

data class ExportedStory(
    val instance: StoryInstance,
    val pack: StoryPack?,
)

data class RestoredWorld(
    val packs: List<StoryPack>,
    val instances: List<StoryInstance>,
    val failures: List<StorageFailure>,
)

data class StorageFailure(
    val document: String,
    val reason: String,
)

internal fun sanitizeId(id: EntityId): String = id.value.replace(Regex("[^A-Za-z0-9._-]"), "_")
