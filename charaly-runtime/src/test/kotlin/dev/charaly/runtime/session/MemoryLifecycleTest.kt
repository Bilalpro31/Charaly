package dev.charaly.runtime.session

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.MemoryId
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.memory.Memory
import dev.charaly.runtime.domain.memory.MemoryTier
import dev.charaly.runtime.domain.memory.MemoryVisibility
import dev.charaly.runtime.engine.StoryCreationOptions
import dev.charaly.runtime.engine.StoryInstanceFactory
import dev.charaly.runtime.inference.MockInferenceEngine
import dev.charaly.runtime.pack.DemoStoryPacks
import dev.charaly.runtime.persistence.InMemoryCharalyStorage
import dev.charaly.runtime.persistence.JsonCharalyRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Memory as an operation, not a field.
 *
 * Pinning, forgetting and consolidating all have to go through the runtime so the story
 * stays internally consistent - a forgotten memory that is still in a character's index
 * is exactly the kind of quiet corruption that makes a long story untrustworthy.
 */
class MemoryLifecycleTest {

    private val pack = DemoStoryPacks.all.first {
        it.id.value == "pack-miraculous-shadows-of-paris"
    }.copy(initialKnowledge = dev.charaly.runtime.domain.InitialKnowledge())

    private val marinette = CharacterId("marinette")
    private val andre = CharacterId("andre")

    private fun runtime(storage: dev.charaly.runtime.persistence.CharalyStorage = InMemoryCharalyStorage()) =
        CharalyRuntime(
            repository = JsonCharalyRepository(storage),
            engine = MockInferenceEngine(),
        )

    private suspend fun started(rt: CharalyRuntime) = rt.startStory(
        pack = pack,
        options = StoryCreationOptions(
            instanceId = StoryInstanceId("story-mem"),
            scenario = pack.defaultScenario(),
            focusCharacterId = marinette,
            nowEpochMs = 0L,
        ),
    )

    private fun memory(
        id: String,
        owner: CharacterId,
        content: String,
        importance: Int = 3,
        minute: Int = 600,
        subject: String = "",
        predicate: String = "",
        tier: MemoryTier = MemoryTier.EPISODIC,
    ) = Memory(
        id = MemoryId(id),
        characterId = owner,
        content = content,
        importance = importance,
        createdAt = dev.charaly.runtime.domain.StoryTime.of(day = 1, hour = minute / 60, minute = minute % 60),
        tier = tier,
        subject = subject,
        predicate = predicate,
    )

    /** Stores a memory and puts it in the owner's runtime index, as the engine would. */
    private suspend fun CharalyRuntime.remember(
        instance: dev.charaly.runtime.domain.StoryInstance,
        memory: Memory,
    ): dev.charaly.runtime.domain.StoryInstance {
        val engine = dev.charaly.runtime.engine.EventEngine(
            dev.charaly.runtime.domain.WorldDefinition(pack.characters, pack.locations),
        )
        val applied = engine.applyImmediately(
            instance,
            dev.charaly.runtime.domain.events.MemoryCreated(memory),
        ) as dev.charaly.runtime.engine.EventApplication.Applied
        return save(applied.instance)
    }

    // ------------------------------------------------------------------
    // Pinning
    // ------------------------------------------------------------------

    @Test
    fun `pinning survives consolidation and pruning`() = runBlocking {
        val rt = runtime()
        var instance = rt.remember(
            started(rt),
            memory("m-pinned", andre, "Something the player wants remembered.", importance = 1),
        )
        assertFalse(instance.memories.byId(MemoryId("m-pinned"))!!.pinned)

        instance = rt.pinMemory(instance, MemoryId("m-pinned"))
        assertTrue(instance.memories.byId(MemoryId("m-pinned"))!!.pinned)

        // Now bury it: add plenty of higher-importance memories for the same owner.
        repeat(80) { i ->
            instance = rt.remember(
                instance,
                memory("m-filler-$i", andre, "Filler memory number $i about the shop.", importance = 5, minute = 60 + i),
            )
        }
        instance = rt.consolidateMemories(instance)
        val pruned = dev.charaly.runtime.domain.memory.MemoryConsolidator.prune(
            instance.memories,
            maxPerCharacter = 10,
        )
        assertNotNull(
            "a pinned memory must survive pruning",
            pruned.byId(MemoryId("m-pinned")),
        )
        assertTrue(pruned.byId(MemoryId("m-pinned"))!!.pinned)
    }

    @Test
    fun `pinning persists across a restart`() = runBlocking {
        val storage = InMemoryCharalyStorage()
        val rt = runtime(storage)
        val instance = rt.pinMemory(
            rt.remember(started(rt), memory("m1", andre, "Worth keeping.")),
            MemoryId("m1"),
        )
        val reopened = runtime(storage).loadStory(instance.id)!!
        assertTrue(reopened.memories.byId(MemoryId("m1"))!!.pinned)
    }

    @Test
    fun `pinning a memory that does not exist changes nothing`() = runBlocking {
        val rt = runtime()
        val instance = started(rt)
        assertEquals(instance, rt.pinMemory(instance, MemoryId("m-nope")))
    }

    // ------------------------------------------------------------------
    // Forgetting
    // ------------------------------------------------------------------

    @Test
    fun `forgetting a memory takes it out of retrieval and records when`() = runBlocking {
        val rt = runtime()
        var instance = rt.remember(started(rt), memory("m-secret", marinette, "A secret."))
        assertEquals(1, instance.memories.current().size)

        instance = rt.advance(instance, dev.charaly.runtime.domain.StoryDuration.hours(3))
        instance = rt.forgetMemory(instance, MemoryId("m-secret"))

        assertTrue(
            "a forgotten memory must not be offered to the model",
            instance.memories.current().none { it.id == MemoryId("m-secret") },
        )
        val record = instance.memories.byId(MemoryId("m-secret"))
        assertNotNull("the record is kept so the forget is auditable", record)
        assertNotNull("and it carries an end date", record!!.validUntil)
        assertFalse(record.isCurrent)
    }

    @Test
    fun `forgetting repairs the owning character's memory index`() = runBlocking {
        val rt = runtime()
        var instance = rt.remember(started(rt), memory("m1", andre, "Something."))
        val before = instance.characters.getValue(andre).memoryIds
        assertTrue(MemoryId("m1") in before)

        instance = rt.forgetMemory(instance, MemoryId("m1"))

        assertFalse(
            "a forgotten memory must not stay in the character's index",
            MemoryId("m1") in instance.characters.getValue(andre).memoryIds,
        )
    }

    @Test
    fun `forgetting persists across a restart`() = runBlocking {
        val storage = InMemoryCharalyStorage()
        val rt = runtime(storage)
        val instance = rt.forgetMemory(
            rt.remember(started(rt), memory("m1", andre, "Something.")),
            MemoryId("m1"),
        )
        val reopened = runtime(storage).loadStory(instance.id)!!
        assertFalse(reopened.memories.byId(MemoryId("m1"))!!.isCurrent)
    }

    @Test
    fun `forgetting a memory that does not exist changes nothing`() = runBlocking {
        val rt = runtime()
        val instance = started(rt)
        assertEquals(instance, rt.forgetMemory(instance, MemoryId("m-nope")))
    }

    // ------------------------------------------------------------------
    // Consolidation
    // ------------------------------------------------------------------

    @Test
    fun `consolidation merges a repeated memory and keeps the first occurrence`() = runBlocking {
        val rt = runtime()
        var instance = rt.remember(
            started(rt),
            memory("m1", andre, "The player bought three ice creams.", minute = 100),
        )
        instance = rt.remember(
            instance,
            memory("m2", andre, "The player bought three ice creams.", minute = 400),
        )
        assertEquals(2, instance.memories.current().size)

        instance = rt.consolidateMemories(instance)

        assertEquals("a repeated memory must be folded into one", 1, instance.memories.current().size)
        assertNotNull("the first occurrence is the one kept", instance.memories.byId(MemoryId("m1")))
        assertFalse(instance.memories.byId(MemoryId("m2"))!!.isCurrent)
    }

    @Test
    fun `consolidation resolves a contradiction in favour of the newer claim`() = runBlocking {
        val rt = runtime()
        var instance = rt.remember(
            started(rt),
            memory("m-paris", andre, "The player lives in Paris.", minute = 100, subject = "player", predicate = "lives in"),
        )
        instance = rt.remember(
            instance,
            memory("m-lyon", andre, "The player moved to Lyon.", minute = 700, subject = "player", predicate = "lives in"),
        )

        instance = rt.consolidateMemories(instance)

        assertNull(
            "the old claim must not be current",
            instance.memories.current().firstOrNull { it.id == MemoryId("m-paris") },
        )
        assertNotNull(
            "the new claim must be current",
            instance.memories.current().firstOrNull { it.id == MemoryId("m-lyon") },
        )
        assertEquals(MemoryId("m-lyon"), instance.memories.byId(MemoryId("m-paris"))!!.supersededBy)
    }

    @Test
    fun `consolidation is idempotent`() = runBlocking {
        val rt = runtime()
        var instance = rt.remember(
            started(rt),
            memory("m1", andre, "The player bought three ice creams.", minute = 100),
        )
        instance = rt.remember(
            instance,
            memory("m2", andre, "The player bought three ice creams.", minute = 400),
        )

        val once = rt.consolidateMemories(instance)
        val twice = rt.consolidateMemories(once)
        val thrice = rt.consolidateMemories(twice)

        assertEquals(once.memories, twice.memories)
        assertEquals(twice.memories, thrice.memories)
    }

    @Test
    fun `consolidation persists across a restart`() = runBlocking {
        val storage = InMemoryCharalyStorage()
        val rt = runtime(storage)
        var instance = rt.remember(
            started(rt),
            memory("m1", andre, "The player bought three ice creams.", minute = 100),
        )
        instance = rt.remember(
            instance,
            memory("m2", andre, "The player bought three ice creams.", minute = 400),
        )
        rt.consolidateMemories(instance)

        val reopened = runtime(storage).loadStory(instance.id)!!
        assertEquals(1, reopened.memories.current().size)
    }

    @Test
    fun `consolidating a story with no memories changes nothing`() = runBlocking {
        val rt = runtime()
        val instance = started(rt)
        assertEquals(instance, rt.consolidateMemories(instance))
    }
}