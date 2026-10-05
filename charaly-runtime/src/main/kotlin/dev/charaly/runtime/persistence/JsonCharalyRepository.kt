package dev.charaly.runtime.persistence

import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.StoryPackId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * JSON-document repository.
 *
 * Layout:
 *   packs/<packId>.json
 *   stories/<storyInstanceId>.json
 *
 * A corrupt or unreadable document is skipped and reported in
 * [RestoredWorld.failures] instead of crashing the app: one broken story must
 * not take every other story down with it.
 */
class JsonCharalyRepository(
    private val storage: CharalyStorage,
    private val json: Json = defaultJson,
) : CharalyRepository {

    private val packsFlow = MutableStateFlow<List<StoryPack>>(emptyList())
    private val instancesFlow = MutableStateFlow<List<StoryInstance>>(emptyList())

    /**
     * Migrations this repository performed or observed, for the UI to report.
     *
     * Reading a save is never destructive: an older document is decoded as-is and only
     * stamped on the next write. The record exists so the user can be *told* their
     * library was upgraded rather than discovering it silently.
     */
    private val _migrations = MutableStateFlow<List<Migration>>(emptyList())
    val migrations: Flow<List<Migration>> = _migrations.asStateFlow()

    /** The migrations observed by the most recent [restoreAll], for tests and diagnostics. */
    fun observedMigrations(): List<Migration> = _migrations.value

    // ---- packs ---------------------------------------------------------

    override fun observePacks(): Flow<List<StoryPack>> = packsFlow.asStateFlow()

    override suspend fun listPacks(): List<StoryPack> {
        refreshPacks()
        return packsFlow.value
    }

    override suspend fun getPack(id: StoryPackId): StoryPack? =
        packsFlow.value.firstOrNull { it.id == id } ?: decodePack(storage.read(packDoc(id)))

    override suspend fun savePack(pack: StoryPack) {
        writeDocument(packDoc(pack.id), json.encodeToString(StoryPack.serializer(), pack))
        refreshPacks()
    }

    override suspend fun deletePack(id: StoryPackId) {
        storage.delete(packDoc(id))
        refreshPacks()
    }

    // ---- instances -----------------------------------------------------

    override fun observeInstances(): Flow<List<StoryInstance>> = instancesFlow.asStateFlow()

    override suspend fun listInstances(): List<StoryInstance> {
        refreshInstances()
        return instancesFlow.value
    }

    override suspend fun getInstance(id: StoryInstanceId): StoryInstance? =
        instancesFlow.value.firstOrNull { it.id == id } ?: decodeInstance(storage.read(instanceDoc(id)))

    override suspend fun saveInstance(instance: StoryInstance) {
        writeDocument(instanceDoc(instance.id), json.encodeToString(StoryInstance.serializer(), instance))
        refreshInstances()
    }

    override suspend fun deleteInstance(id: StoryInstanceId) {
        storage.delete(instanceDoc(id))
        refreshInstances()
    }

    override suspend fun latestFor(packId: StoryPackId): StoryInstance? =
        instancesFlow.value.filter { it.storyPackId == packId }.maxByOrNull { it.updatedAt }

    // ---- export / restore ---------------------------------------------

    override suspend fun exportStory(instanceId: StoryInstanceId): ExportedStory? {
        val instance = getInstance(instanceId) ?: return null
        return ExportedStory(instance = instance, pack = getPack(instance.storyPackId))
    }

    override suspend fun importStory(exported: ExportedStory): StoryInstance? = runCatching {
        exported.pack?.let { savePack(it) }
        saveInstance(exported.instance)
        getInstance(exported.instance.id)
    }.getOrNull()

    override suspend fun restoreAll(): RestoredWorld {
        val failures = mutableListOf<StorageFailure>()
        val observedMigrations = linkedSetOf<Migration>()

        val restoredPacks = mutableListOf<StoryPack>()
        storage.list("$PACK_DIR/").forEach { doc ->
            val raw = storage.read(doc) ?: return@forEach
            noteMigration(doc, raw, observedMigrations)
            val pack = decodePack(raw)
            if (pack == null) failures += StorageFailure(doc, "unreadable story pack document") else restoredPacks += pack
        }

        val restoredInstances = mutableListOf<StoryInstance>()
        storage.list("$INSTANCE_DIR/").forEach { doc ->
            val raw = storage.read(doc) ?: return@forEach
            noteMigration(doc, raw, observedMigrations)
            val instance = decodeInstance(raw)
            if (instance == null) failures += StorageFailure(doc, "unreadable story document") else restoredInstances += instance
        }

        packsFlow.value = restoredPacks.sortedBy { it.title.lowercase() }
        instancesFlow.value = restoredInstances.sortedByDescending { it.updatedAt }
        _migrations.value = observedMigrations.toList()
        return RestoredWorld(packsFlow.value, instancesFlow.value, failures)
    }

    /**
     * Records that a document was written by an older schema.
     *
     * Nothing is rewritten here. An older document decodes correctly as-is because
     * every field added since has a default, and rewriting on read would risk a user's
     * stories for no benefit.
     */
    private fun noteMigration(doc: String, raw: String, into: MutableSet<Migration>) {
        val migration = SchemaMigration.needsWork(SchemaMigration.versionOf(raw))
        if (migration.isRequired) into += migration
    }

    // ---- internals -----------------------------------------------------

    private suspend fun refreshPacks() {
        val loaded = storage.list("$PACK_DIR/").mapNotNull { name -> storage.read(name)?.let { decodePack(it) } }
        packsFlow.value = loaded.sortedBy { it.title.lowercase() }
    }

    private suspend fun refreshInstances() {
        val loaded = storage.list("$INSTANCE_DIR/").mapNotNull { name -> storage.read(name)?.let { decodeInstance(it) } }
        instancesFlow.value = loaded.sortedByDescending { it.updatedAt }
    }

    private fun decodePack(raw: String?): StoryPack? =
        raw?.let { runCatching { json.decodeFromString(StoryPack.serializer(), it) }.getOrNull() }

    private fun decodeInstance(raw: String?): StoryInstance? =
        raw?.let { runCatching { json.decodeFromString(StoryInstance.serializer(), it) }.getOrNull() }

    /**
     * Writes a document with its schema version stamped in.
     *
     * Stamping happens here rather than at each call site so no document can be written
     * unversioned by omission, and stamping is non-destructive: the document body is
     * unchanged, only annotated.
     */
    private suspend fun writeDocument(name: String, body: String) {
        storage.write(name, SchemaMigration.stamp(body))
    }

    private fun packDoc(id: StoryPackId) = "$PACK_DIR/${sanitizeId(id)}.json"

    private fun instanceDoc(id: StoryInstanceId) = "$INSTANCE_DIR/${sanitizeId(id)}.json"

    companion object {
        const val PACK_DIR = "packs"
        const val INSTANCE_DIR = "stories"

        /**
         * `ignoreUnknownKeys` keeps old saves readable by newer builds, which is
         * what lets the domain model grow without a destructive migration.
         */
        val defaultJson: Json = Json {
            prettyPrint = false
            ignoreUnknownKeys = true
            encodeDefaults = true
            classDiscriminator = "type"
        }
    }
}
