package dev.charaly.runtime.persistence

import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.engine.StoryCreationOptions
import dev.charaly.runtime.engine.StoryInstanceFactory
import dev.charaly.runtime.pack.DemoStoryPacks
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An existing user's stories must still open after an update.
 *
 * That guarantee is the whole reason [SchemaMigration] exists, so it is tested against
 * the real thing: a document written by the *previous* schema shape, read by the
 * current one, with the new fields landing on their defaults.
 */
class SchemaMigrationTest {

    private val pack = DemoStoryPacks.all.first()

    private fun story(id: String = "story-1") = StoryInstanceFactory.create(
        pack,
        StoryCreationOptions(
            instanceId = StoryInstanceId(id),
            title = "An existing story",
            scenario = pack.defaultScenario(),
            focusCharacterId = pack.defaultScenario()?.focusCharacterId,
            nowEpochMs = 1_700_000_000_000L,
        ),
    )

    // ---- version stamping ------------------------------------------------

    @Test
    fun `a document written by this build carries the current schema version`() {
        val body = JsonCharalyRepository.defaultJson
            .encodeToString(dev.charaly.runtime.domain.StoryInstance.serializer(), story())
        val stamped = SchemaMigration.stamp(body)
        assertEquals(SchemaMigration.CURRENT_VERSION, SchemaMigration.versionOf(stamped))
    }

    @Test
    fun `stamping preserves every field of the document`() {
        val original = story("story-keep")
        val json = JsonCharalyRepository.defaultJson
        val body = json.encodeToString(dev.charaly.runtime.domain.StoryInstance.serializer(), original)

        // Start from an unversioned document, which is what stamping has to annotate.
        val unversioned = Json.parseToJsonElement(body).jsonObject.toMutableMap()
            .apply { remove(SchemaMigration.VERSION_FIELD) }
        val before = JsonObject(unversioned)

        val stamped = SchemaMigration.stamp(before.toString())
        val after = Json.parseToJsonElement(stamped).jsonObject

        // Exactly one field added...
        assertEquals(
            "stamping must add exactly the version field",
            before.keys.toSet() + SchemaMigration.VERSION_FIELD,
            after.keys.toSet(),
        )
        // ...and nothing that was already there changed.
        val changedExisting = before.keys.filter { before[it] != after[it] }
        assertTrue(
            "stamping must not modify existing fields, but changed $changedExisting",
            changedExisting.isEmpty(),
        )
        assertEquals(SchemaMigration.CURRENT_VERSION, after[SchemaMigration.VERSION_FIELD]!!.jsonPrimitive.int)
    }

    @Test
    fun `an unversioned document is read as the legacy version, never discarded`() {
        // A pre-versioning document has no version field at all.
        val body = """{"id":"story-1","packTitle":"Old"}"""
        assertEquals(SchemaMigration.LEGACY_VERSION, SchemaMigration.versionOf(body))
        assertTrue(
            SchemaMigration.needsWork(SchemaMigration.versionOf(body)).isRequired,
        )
    }

    @Test
    fun `a document from a newer build is not treated as legacy`() {
        val body = """{"${SchemaMigration.VERSION_FIELD}":999}"""
        assertEquals(999, SchemaMigration.versionOf(body))
    }

    @Test
    fun `unparseable input reports the legacy version rather than throwing`() {
        assertEquals(SchemaMigration.LEGACY_VERSION, SchemaMigration.versionOf("not json at all"))
    }

    @Test
    fun `a current-version document needs no migration`() {
        assertTrue(!SchemaMigration.needsWork(SchemaMigration.CURRENT_VERSION).isRequired)
    }

    // ---- reading real old saves -------------------------------------------

    /** The document path a story is stored at. */
    private val storyDoc = "${JsonCharalyRepository.INSTANCE_DIR}/story-1.json"

    /** The on-disk text of a story, with the version field removed: a real v1 save. */
    private suspend fun writeLegacyStory(storage: InMemoryCharalyStorage) {
        JsonCharalyRepository(storage).saveInstance(story())
        val onDisk = Json.parseToJsonElement(storage.read(storyDoc)!!).jsonObject.toMutableMap()
        onDisk.remove(SchemaMigration.VERSION_FIELD)
        storage.write(storyDoc, JsonObject(onDisk).toString())
    }

    @Test
    fun `a story saved without the new memory and routine fields still opens`() {
        val storage = InMemoryCharalyStorage()
        runBlocking { writeLegacyStory(storage) }

        val restored = runBlocking {
            JsonCharalyRepository(storage).getInstance(StoryInstanceId("story-1"))
        }

        assertNotNull("an old save must still open", restored)
        val instance = restored!!
        assertEquals("An existing story", instance.displayTitle)
        // The fields the new schema added take their defaults rather than being lost.
        instance.characters.values.forEach { runtime ->
            assertEquals(-1, runtime.routineEntryMinute)
            assertEquals("", runtime.activityLabel)
        }
    }

    @Test
    fun `an old save is reported as migrated without being rewritten`() = runBlocking {
        val storage = InMemoryCharalyStorage()
        writeLegacyStory(storage)
        val legacy = storage.read(storyDoc)

        val repository = JsonCharalyRepository(storage)
        val result = repository.restoreAll()

        assertEquals(1, result.instances.size)
        assertTrue(
            "the migration must be reported to the user",
            repository.observedMigrations().isNotEmpty(),
        )
        // Reading must not have rewritten the document.
        assertEquals("reading must not rewrite a save", legacy, storage.read(storyDoc))
    }

    @Test
    fun `restore reports no migration when everything is current`() = runBlocking {
        val storage = InMemoryCharalyStorage()
        val repository = JsonCharalyRepository(storage)
        repository.saveInstance(story())
        repository.restoreAll()
        assertTrue(repository.observedMigrations().isEmpty())
    }
}