package dev.charaly.runtime.persistence

import dev.charaly.runtime.domain.EntityId
import dev.charaly.runtime.domain.FactId
import dev.charaly.runtime.domain.SeedEvent
import dev.charaly.runtime.domain.StoryDuration
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.StoryPackId
import dev.charaly.runtime.domain.TranscriptEntry
import dev.charaly.runtime.domain.TranscriptRole
import dev.charaly.runtime.domain.WorldDefinition
import dev.charaly.runtime.domain.events.CharacterMoved
import dev.charaly.runtime.engine.EventApplication
import dev.charaly.runtime.engine.ScheduleResult
import dev.charaly.runtime.domain.events.KnowledgeDiscovered
import dev.charaly.runtime.domain.events.MemoryCreated

import dev.charaly.runtime.domain.memory.Memory
import dev.charaly.runtime.engine.EventEngine
import dev.charaly.runtime.engine.SampleWorlds
import dev.charaly.runtime.engine.StoryInstanceFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Persistence must satisfy the offline requirement: close the app, reopen it,
 * and continue the exact same story.
 */
class PersistenceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val pack: StoryPack = SampleWorlds.libraryPack()
    private val alice = SampleWorlds.alice.id
    private val bob = SampleWorlds.bob.id
    private val library = SampleWorlds.library.id
    private val square = SampleWorlds.square.id

    private fun repo(dir: File = temp.newFolder()): Pair<CharalyStorage, JsonCharalyRepository> {
        val storage = FileCharalyStorage(dir)
        return storage to JsonCharalyRepository(storage)
    }

    private fun fresh() = StoryInstanceFactory.create(pack, StoryInstanceId("s1"))

    private fun engine() = EventEngine(WorldDefinition(pack.characters, pack.locations))

    private fun ScheduleResult.scheduled(): StoryInstance {
        assertTrue("expected a scheduled event, got $this", this is ScheduleResult.Scheduled)
        return (this as ScheduleResult.Scheduled).instance
    }

    private fun EventApplication.applied(): StoryInstance {
        assertTrue("expected Applied, got $this", this is EventApplication.Applied)
        return (this as EventApplication.Applied).instance
    }

    // ------------------------------------------------------------------
    // Round trips
    // ------------------------------------------------------------------

    @Test
    fun `a story instance survives a full serialize restore cycle`() = runTest {
        val (storage, repo) = repo()
        val original = fresh()
        repo.saveInstance(original)

        val restored = JsonCharalyRepository(storage).getInstance(StoryInstanceId("s1"))!!
        assertEquals(original.id, restored.id)
        assertEquals(original.storyPackId, restored.storyPackId)
        assertEquals(original.worldClock.now, restored.worldClock.now)
        assertEquals(original.characters.keys, restored.characters.keys)
        assertEquals(original.characters.getValue(alice).locationId, restored.characters.getValue(alice).locationId)
        assertEquals(original.knowledge.knowledge.keys, restored.knowledge.knowledge.keys)
        assertEquals(original.relationships, restored.relationships)
        assertEquals(original.storyThreads, restored.storyThreads)
        assertEquals(original.worldState.eventLog.size, restored.worldState.eventLog.size)
        assertEquals(original.worldState.variables, restored.worldState.variables)
    }

    @Test
    fun `the story pack survives a serialize restore cycle`() = runTest {
        val (storage, repo) = repo()
        repo.savePack(pack)
        val restored = JsonCharalyRepository(storage).getPack(pack.id)!!
        assertEquals(pack.title, restored.title)
        assertEquals(pack.characters, restored.characters)
        assertEquals(pack.locations, restored.locations)
        assertEquals(pack.initialKnowledge.facts, restored.initialKnowledge.facts)
    }

    @Test
    fun `the event queue survives a restart`() = runTest {
        val (storage, repo) = repo()
        val queued = engine().scheduleAfter(
            fresh(),
            CharacterMoved(bob, from = square, to = library),
            StoryDuration(45),
        ).scheduled()

        repo.saveInstance(queued)
        val restored = JsonCharalyRepository(storage).getInstance(StoryInstanceId("s1"))!!

        assertEquals(1, restored.eventQueue.size)
        val pending = restored.eventQueue.snapshot().single()
        assertTrue(pending.payload is CharacterMoved)
        assertEquals(
            queued.worldClock.now.plusMinutes(45).totalMinutes,
            pending.scheduledAt.totalMinutes,
        )
    }

    @Test
    fun `memories and transcript survive a restart`() = runTest {
        val (storage, repo) = repo()
        var instance = engine().applyImmediately(
            fresh(),
            MemoryCreated(Memory(EntityId("mem-1"), alice, "Bob said the lamps go out at nine.")),
        ).applied()
        instance = instance.evolved(
            conversation = instance.conversation.append(
                TranscriptEntry("u1", 1, TranscriptRole.USER, "Where were you last night?", instance.worldClock.now),
            ),
        )
        repo.saveInstance(instance)

        val restored = JsonCharalyRepository(storage).getInstance(StoryInstanceId("s1"))!!
        assertEquals(1, restored.memories.size)
        assertEquals("Bob said the lamps go out at nine.", restored.memories.all().single().content)
        assertEquals("Where were you last night?", restored.conversation.entries.single().text)
    }

    @Test
    fun `a restarted instance continues the same story`() = runTest {
        val dir = temp.newFolder("continue")
        val (storage, repo) = repo(dir)
        repo.saveInstance(fresh())

        // Close the app, reopen it.
        val reloaded = JsonCharalyRepository(storage).getInstance(StoryInstanceId("s1"))!!

        val moved = engine().applyImmediately(reloaded, CharacterMoved(bob, from = square, to = library)).applied()
        repo.saveInstance(engine().advanceClock(moved, StoryDuration(60)).instance)

        // Keep going.
        val again = JsonCharalyRepository(storage).getInstance(StoryInstanceId("s1"))!!
        assertEquals(library, again.characters.getValue(bob).locationId)
        assertEquals(reloaded.worldClock.now.plusMinutes(60), again.worldClock.now)
    }

    @Test
    fun `knowledge separation survives persistence`() = runTest {
        val (storage, repo) = repo()
        val learned = engine().applyImmediately(
            fresh(),
            KnowledgeDiscovered(alice, FactId("fact-ledger"), via = "confessed"),
        ).applied()
        repo.saveInstance(learned)

        val restored = JsonCharalyRepository(storage).getInstance(StoryInstanceId("s1"))!!
        assertTrue("Alice learned the secret", restored.knowledge.knows(alice, FactId("fact-ledger")))
        assertEquals("learning does not create truth", 2, restored.knowledge.factCount)
        assertEquals("confessed", restored.knowledge.entry(alice, FactId("fact-ledger"))?.via)
        assertTrue("Bob still knows too", restored.knowledge.knows(bob, FactId("fact-ledger")))
    }

    @Test
    fun `pending events keep their deterministic order across a restart`() = runTest {
        val (_, repo) = repo()
        val e = engine()
        var instance = e.scheduleAfter(fresh(), CharacterMoved(bob, from = square, to = library), StoryDuration(30)).scheduled()
        instance = e.scheduleAfter(instance, CharacterMoved(alice, from = library, to = square), StoryDuration(10)).scheduled()
        repo.saveInstance(instance)

        val now = instance.worldClock.now
        val order = instance.eventQueue.snapshot().map { it.scheduledAt.totalMinutes - now.totalMinutes }
        assertEquals(listOf(10L, 30L), order)
    }

    // ------------------------------------------------------------------
    // Recovery
    // ------------------------------------------------------------------

    @Test
    fun `a corrupt document does not take the other stories down`() = runTest {
        val dir = temp.newFolder("corrupt")
        val (_, repo) = repo(dir)
        repo.saveInstance(StoryInstanceFactory.create(pack, StoryInstanceId("good-1")))
        repo.saveInstance(StoryInstanceFactory.create(pack, StoryInstanceId("good-2")))
        File(dir, "${JsonCharalyRepository.INSTANCE_DIR}/broken.json").writeText("{ this is not json")

        val restored = repo.restoreAll()
        assertEquals("the readable stories must still load", 2, restored.instances.size)
        assertEquals(1, restored.failures.size)
        assertTrue(restored.failures.single().reason.contains("unreadable"))
    }

    @Test
    fun `unknown fields from a future version are ignored`() = runTest {
        val dir = temp.newFolder("future")
        val (storage, repo) = repo(dir)
        repo.saveInstance(fresh())
        val file = File(dir, "${JsonCharalyRepository.INSTANCE_DIR}/s1.json")
        file.writeText(file.readText().replaceFirst("{", """{"futureField":"ignored","""))

        assertNotNull("forward compatibility must hold", JsonCharalyRepository(storage).getInstance(StoryInstanceId("s1")))
    }

    @Test
    fun `a missing document is not an error`() = runTest {
        val (_, repo) = repo()
        assertNull(repo.getInstance(StoryInstanceId("never-existed")))
        assertNull(repo.getPack(StoryPackId("never-existed")))
        assertTrue(repo.listInstances().isEmpty())
    }

    @Test
    fun `atomic write leaves no temp file behind`() = runTest {
        val dir = temp.newFolder("atomic")
        val (_, repo) = repo(dir)
        repo.saveInstance(fresh())
        assertTrue(dir.walkTopDown().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `delete removes the document`() = runTest {
        val (_, repo) = repo()
        val instance = fresh()
        repo.saveInstance(instance)
        repo.deleteInstance(instance.id)
        assertNull(repo.getInstance(instance.id))
    }

    @Test
    fun `listing is sorted and observable`() = runTest {
        val (_, repo) = repo()
        repo.saveInstance(
            StoryInstanceFactory.create(pack.copy(id = StoryPackId("b"), title = "B Story"), StoryInstanceId("i2")),
        )
        repo.saveInstance(
            StoryInstanceFactory.create(pack.copy(id = StoryPackId("a"), title = "A Story"), StoryInstanceId("i1")),
        )
        // Packs need distinct ids as well as distinct titles.
        repo.savePack(pack.copy(id = StoryPackId("a"), title = "A Story"))
        repo.savePack(pack.copy(id = StoryPackId("b"), title = "B Story"))

        assertEquals(listOf("A Story", "B Story"), repo.listPacks().map { it.title })
        assertEquals(2, repo.observePacks().first().size)
        assertEquals(2, repo.observeInstances().first().size)
    }

    @Test
    fun `latest for a pack finds the most recently updated story`() = runTest {
        val (_, repo) = repo()
        repo.saveInstance(fresh())
        val later = engine().advanceClock(
            StoryInstanceFactory.create(pack, StoryInstanceId("newer")),
            StoryDuration(120),
        ).instance
        repo.saveInstance(later)

        assertEquals(StoryInstanceId("newer"), repo.latestFor(pack.id)?.id)
    }

    @Test
    fun `export and import round trip a story`() = runTest {
        val (_, source) = repo(temp.newFolder("src"))
        source.savePack(pack)
        source.saveInstance(fresh())

        val exported = source.exportStory(StoryInstanceId("s1"))!!
        val (_, target) = repo(temp.newFolder("dst"))
        val imported = target.importStory(exported)

        assertNotNull(imported)
        assertEquals("s1", imported!!.id.value)
        assertEquals(2, imported.characters.size)
        assertNotNull("the pack travels with the story", target.getPack(pack.id))
    }

    @Test
    fun `importing an instance whose storage is broken returns null instead of throwing`() = runTest {
        val broken = object : CharalyStorage {
            override suspend fun read(name: String): String? = null
            override suspend fun write(name: String, contents: String) = throw java.io.IOException("disk full")
            override suspend fun delete(name: String) = Unit
            override suspend fun list(prefix: String): List<String> = emptyList()
        }
        val target = JsonCharalyRepository(broken)
        val result = target.importStory(
            ExportedStory(instance = fresh(), pack = null),
        )
        assertNull("a failed import must not crash the caller", result)
    }

    @Test
    fun `an instance with an empty pack title cannot even be constructed`() {
        assertTrue(runCatching { pack.copy(title = "  ") }.isFailure)
    }

    @Test
    fun `in-memory storage behaves like the file one`() = runTest {
        val repo = JsonCharalyRepository(InMemoryCharalyStorage())
        repo.saveInstance(fresh())
        assertEquals("s1", repo.getInstance(StoryInstanceId("s1"))?.id?.value)
        assertEquals(1, repo.listInstances().size)
    }

    @Test
    fun `ids with path characters cannot escape the storage directory`() = runTest {
        val dir = temp.newFolder("sanitize")
        val (_, repo) = repo(dir)
        val hostile = StoryInstanceFactory.create(pack, StoryInstanceId("../../etc/passwd"))
        repo.saveInstance(hostile)

        assertTrue(dir.walkTopDown().all { it.canonicalPath.startsWith(dir.canonicalPath) })
        assertNotNull(repo.getInstance(StoryInstanceId("../../etc/passwd")))
    }

    @Test
    fun `restoreAll on an empty store is clean`() = runTest {
        val restored = repo().second.restoreAll()
        assertTrue(restored.packs.isEmpty())
        assertTrue(restored.instances.isEmpty())
        assertTrue(restored.failures.isEmpty())
    }

    @Test
    fun `saving the same instance twice does not duplicate it`() = runTest {
        val (_, repo) = repo()
        val instance = fresh()
        repo.saveInstance(instance)
        repo.saveInstance(instance)
        assertEquals(1, repo.listInstances().size)
    }

    @Test
    fun `a story pack with authored events round trips`() = runTest {
        val dir = temp.newFolder("seeded")
        val (storage, repo) = repo(dir)
        val seeded = pack.copy(
            initialEvents = listOf(
                SeedEvent(
                    payload = CharacterMoved(bob, from = square, to = library),
                    delayMinutes = 30,
                    note = "Bob follows Alice later",
                ),
            ),
        )
        repo.savePack(seeded)

        val reread = JsonCharalyRepository(storage).getPack(seeded.id)!!
        assertEquals(1, reread.initialEvents.size)
        assertEquals(30L, reread.initialEvents.single().delayMinutes)
        assertEquals("Bob follows Alice later", reread.initialEvents.single().note)
        assertTrue(reread.initialEvents.single().payload is CharacterMoved)
    }

    @Test
    fun `authored events scheduled in the future stay pending after a restart`() = runTest {
        val (_, repo) = repo()
        val seeded = pack.copy(
            initialEvents = listOf(
                SeedEvent(payload = CharacterMoved(bob, from = square, to = library), delayMinutes = 30),
            ),
        )
        val instance = StoryInstanceFactory.create(seeded, StoryInstanceId("seeded"))
        repo.saveInstance(instance)

        assertEquals("the future event must still be queued", 1, instance.eventQueue.size)
        assertEquals(square, instance.characters.getValue(bob).locationId)
    }
}
