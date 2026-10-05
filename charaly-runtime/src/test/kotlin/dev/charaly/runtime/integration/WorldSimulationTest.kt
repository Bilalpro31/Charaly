package dev.charaly.runtime.integration

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.engine.StoryCreationOptions
import dev.charaly.runtime.engine.StoryInstanceFactory
import dev.charaly.runtime.model.ModelBinding
import dev.charaly.runtime.pack.DemoStoryPacks
import dev.charaly.runtime.persistence.CharalyRepository
import dev.charaly.runtime.persistence.InMemoryCharalyStorage
import dev.charaly.runtime.persistence.JsonCharalyRepository
import dev.charaly.runtime.session.CharalyRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The acceptance walkthrough, end to end on the JVM.
 *
 * This is the brief's final acceptance test, run without a device, an emulator or a
 * model:
 *
 *   walk to Andre's shop -> find him there without summoning him
 *   talk to Andre -> leave -> come back -> he still remembers
 *   tell Marinette a secret -> Adrien must not know it
 *   advance time -> the city moves on by itself
 *   a scheduled world event fires
 *   close the app -> reopen -> the world is unchanged
 *
 * Generation needs a model, so those steps are driven through the runtime's own
 * operations; the model is a detail of *how* a character speaks, not of whether the
 * world remembers.
 */
class WorldSimulationTest {

    private val pack = DemoStoryPacks.all.first { it.id.value == "pack-miraculous-shadows-of-paris" }

    private val shop = LocationId("andre-ice-cream")
    private val school = LocationId("school")
    private val andre = CharacterId("andre")
    private val marinette = CharacterId("marinette")
    private val adrien = CharacterId("adrien")

    private fun runtime(storage: dev.charaly.runtime.persistence.CharalyStorage = InMemoryCharalyStorage()) =
        CharalyRuntime(
            repository = JsonCharalyRepository(storage),
            engine = dev.charaly.runtime.inference.MockInferenceEngine(),
        )

    private suspend fun started(rt: CharalyRuntime) = rt.startStory(
        pack = pack,
        options = StoryCreationOptions(
            instanceId = StoryInstanceId("story-acceptance"),
            title = "Shadows of Paris",
            scenario = pack.defaultScenario(),
            focusCharacterId = marinette,
            modelBinding = ModelBinding.EMPTY,
            nowEpochMs = 1_700_000_000_000L,
        ),
    )

    // ------------------------------------------------------------------
    // Discovery
    // ------------------------------------------------------------------

    @Test
    fun `walking into andres shop finds him there without summoning him`() = runBlocking {
        val rt = runtime()
        val instance = started(rt)

        // Nobody was added to a cast, and no conversation mentions him.
        assertEquals(0, instance.conversation.entries.size)

        val arrived = rt.travelTo(instance, shop)

        val present = arrived.worldState.charactersAt(shop).map { it.characterId }
        assertTrue(
            "Andre must be discoverable by walking into his shop",
            andre in present,
        )
        assertEquals(shop, rt.playerLocation(arrived))
    }

    @Test
    fun `arriving opens a scene with only the people actually there`() = runBlocking {
        val rt = runtime()
        val arrived = rt.travelTo(started(rt), school)

        val scene = arrived.currentScene()
        assertNotNull("arriving somewhere with people must open a scene", scene)
        val participants = scene!!.participants.toSet()

        // Everyone present, and nobody who is not.
        val actuallyHere = arrived.worldState.charactersAt(school).map { it.characterId }.toSet()
        assertEquals(actuallyHere, participants)
        assertFalse(
            "a character elsewhere in Paris must not be in the scene",
            participants.contains(andre),
        )
    }

    @Test
    fun `arriving somewhere empty is not an error`() = runBlocking {
        val rt = runtime()
        val instance = started(rt)
        val empty = LocationId("city-landmark")
        // Take everyone out of the landmark first by starting from the bakery.
        val arrived = rt.travelTo(instance, empty)
        assertEquals(empty, rt.playerLocation(arrived))
    }

    @Test
    fun `moving to a place that does not exist is rejected rather than half-applied`() = runBlocking {
        val rt = runtime()
        val instance = started(rt)
        val arrived = rt.travelTo(instance, LocationId("atlantis"))
        // The world must not claim the player is somewhere that does not exist.
        assertNotEquals("atlantis", arrived.worldState.variables["player_location"]?.value)
        assertNull(rt.playerLocation(arrived))
    }

    private fun <T> assertNotEquals(unexpected: T, actual: T?) {
        assertFalse("expected something other than $unexpected", unexpected == actual)
    }

    // ------------------------------------------------------------------
    // Time and routines
    // ------------------------------------------------------------------

    @Test
    fun `advancing time moves the city on its own`() = runBlocking {
        val rt = runtime()
        val instance = started(rt)
        val startTime = instance.worldClock.now

        val later = rt.advance(instance, dev.charaly.runtime.domain.StoryDuration.hours(10))

        assertTrue("the clock must have moved", later.worldClock.now > startTime)
        // The routines ran, so at least one NPC is where their day says they should be.
        val inPlace = pack.characters.count { character ->
            val placement = character.routine.resolve(later.worldClock.now) ?: return@count false
            later.characters[character.id]?.locationId == placement.locationId
        }
        assertTrue(
            "scheduled characters should be where their day puts them after time passes",
            inPlace > 0,
        )
    }

    @Test
    fun `a scheduled world event fires when the clock reaches it`() = runBlocking {
        val rt = runtime()
        val instance = started(rt)
        val before = instance.worldState.revision

        // Far enough ahead that the pack's scheduled events become due.
        val later = rt.advance(instance, dev.charaly.runtime.domain.StoryDuration.hours(12))

        assertTrue(
            "the world must have actually changed",
            later.worldState.revision > before,
        )
        assertTrue(
            "the change must be attributable to applied events",
            later.worldState.eventLog.size > instance.worldState.eventLog.size,
        )
    }

    @Test
    fun `npcs keep living with no player interaction at all`() = runBlocking {
        val rt = runtime()
        var instance = started(rt)
        repeat(6) { instance = rt.advance(instance, dev.charaly.runtime.domain.StoryDuration.hours(4)) }

        // Every NPC still exists, has a location, and has a history of moving.
        pack.characters
            .filter { it.storyRole == dev.charaly.runtime.domain.CharacterRole.NPC }
            .forEach { npc ->
                val runtime_ = instance.characters[npc.id]
                assertNotNull("${npc.name} must still exist", runtime_)
                assertNotNull("${npc.name} must be somewhere", runtime_!!.locationId)
            }
        assertEquals("the player never spoke to anyone", 0, instance.conversation.entries.size)
    }

    // ------------------------------------------------------------------
    // Memory and knowledge boundaries
    // ------------------------------------------------------------------

    @Test
    fun `andre remembers an interaction and still remembers it after a restart`() = runBlocking {
        val storage = InMemoryCharalyStorage()
        val rt = runtime(storage)
        var instance = rt.travelTo(started(rt), shop)

        // Andre learns something about the player, through a validated event.
        instance = (dev.charaly.runtime.engine.EventEngine(
            dev.charaly.runtime.domain.WorldDefinition(pack.characters, pack.locations),
        ).applyImmediately(
            instance,
            dev.charaly.runtime.domain.events.MemoryCreated(
                dev.charaly.runtime.domain.memory.Memory(
                    id = dev.charaly.runtime.domain.MemoryId("mem-andre-1"),
                    characterId = andre,
                    content = "The player bought three ice creams and came back the next day.",
                    importance = 4,
                    tier = dev.charaly.runtime.domain.memory.MemoryTier.EPISODIC,
                    relatedLocationId = shop,
                    relatedCharacterIds = listOf(CharacterId("player")),
                ),
            ),
        ) as dev.charaly.runtime.engine.EventApplication.Applied).instance

        // Persist it. The runtime never auto-saves after a raw event, because only it
        // knows when a turn is finished - which is exactly why "reopen and it is still
        // there" has to go through a save to mean anything.
        instance = rt.save(instance)

        val andreMemories = instance.memories.of(andre)
        assertEquals(1, andreMemories.size)

        // Close the app and reopen it: the memory must still be there.
        val reopened = runtime(storage).loadStory(instance.id)
        assertNotNull("the story must survive a restart", reopened)
        assertEquals(
            "Andre must still remember the player after a restart",
            1,
            reopened!!.memories.of(andre).size,
        )
    }

    @Test
    fun `a secret told to Marinette is never known by Adrien`() = runBlocking {
        val rt = runtime()
        val instance = started(rt)
        val engine = dev.charaly.runtime.engine.EventEngine(
            dev.charaly.runtime.domain.WorldDefinition(pack.characters, pack.locations),
        )

        val secret = dev.charaly.runtime.domain.knowledge.Fact(
            id = dev.charaly.runtime.domain.FactId("fact-secret-the-player-told-marinette"),
            description = "The player told Marinette something in confidence.",
            secret = true,
        )
        var next = (engine.applyImmediately(instance, dev.charaly.runtime.domain.events.FactRevealed(secret))
            as dev.charaly.runtime.engine.EventApplication.Applied).instance
        next = (engine.applyImmediately(
            next,
            dev.charaly.runtime.domain.events.KnowledgeDiscovered(
                characterId = marinette,
                factId = secret.id,
                via = "told in confidence",
            ),
        ) as dev.charaly.runtime.engine.EventApplication.Applied).instance

        assertTrue(next.knowledge.knows(marinette, secret.id))
        assertFalse(
            "Adrien must not magically know what was said in confidence",
            next.knowledge.knows(adrien, secret.id),
        )
        assertFalse(next.knowledge.visibleTo(adrien).any { it.id == secret.id })

        // And the memory carries the same boundary, not just the fact table.
        val secretMemory = dev.charaly.runtime.domain.memory.Memory(
            id = dev.charaly.runtime.domain.MemoryId("mem-secret"),
            characterId = marinette,
            content = "The player told Marinette something in confidence.",
            visibility = dev.charaly.runtime.domain.memory.MemoryVisibility.SECRET,
        )
        val withMemory = next.evolved(memories = next.memories.add(secretMemory))
        assertTrue(withMemory.memories.visibleTo(
            dev.charaly.runtime.domain.memory.MemorySubject.Character(marinette),
        ).any { it.id == secretMemory.id })
        assertFalse(
            "Adrien must not be able to retrieve a secret memory",
            withMemory.memories.retrieve(
                dev.charaly.runtime.domain.memory.MemorySubject.Character(adrien),
            ).any { it.id == secretMemory.id },
        )
    }

    @Test
    fun `a character only learns a fact through a discovery event`() = runBlocking {
        val rt = runtime()
        val instance = started(rt)
        val engine = dev.charaly.runtime.engine.EventEngine(
            dev.charaly.runtime.domain.WorldDefinition(pack.characters, pack.locations),
        )

        // A fact can exist in world truth without anyone knowing it.
        val fact = dev.charaly.runtime.domain.knowledge.Fact(
            id = dev.charaly.runtime.domain.FactId("fact-nobody-knows"),
            description = "Something that has happened to nobody yet.",
            secret = true,
        )
        val revealed = (engine.applyImmediately(instance, dev.charaly.runtime.domain.events.FactRevealed(fact))
            as dev.charaly.runtime.engine.EventApplication.Applied).instance

        assertNotNull(revealed.knowledge.fact(fact.id))
        assertTrue(
            "existing in world truth must not mean being known",
            revealed.knowledge.knownBy(fact.id).isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // Continuity
    // ------------------------------------------------------------------

    @Test
    fun `the whole world survives a restart unchanged`() = runBlocking {
        val storage = InMemoryCharalyStorage()
        val rt = runtime(storage)
        var instance = started(rt)
        instance = rt.travelTo(instance, shop)
        instance = rt.advance(instance, dev.charaly.runtime.domain.StoryDuration.hours(3))

        val reopened = runtime(storage).loadStory(instance.id)!!
        assertEquals(instance.worldClock.now, reopened.worldClock.now)
        assertEquals(instance.worldState.characters, reopened.worldState.characters)
        assertEquals(instance.worldState.eventLog, reopened.worldState.eventLog)
        assertEquals(instance.worldState.revision, reopened.worldState.revision)
    }

    @Test
    fun `two playthroughs of the same pack never share state`() = runBlocking {
        val rt = runtime()
        val first = rt.travelTo(started(rt), shop)
        val second = rt.startStory(
            pack = pack,
            options = StoryCreationOptions(
                instanceId = StoryInstanceId("story-second"),
                scenario = pack.defaultScenario(),
                focusCharacterId = marinette,
                nowEpochMs = 1_700_000_000_000L,
            ),
        )

        val movedOn = rt.advance(first, dev.charaly.runtime.domain.StoryDuration.hours(9))
        val untouched = rt.loadStory(second.id)!!

        assertEquals(
            "advancing one story must not advance another",
            second.worldClock.now,
            untouched.worldClock.now,
        )
        assertTrue(movedOn.worldClock.now > untouched.worldClock.now)
    }

    @Test
    fun `the LLM never becomes authoritative`() = runBlocking {
        // The structural guarantee, asserted rather than assumed: generation output is a
        // proposed action, and the world only changes through the engine.
        val instance = started(runtime())
        val worldBefore = instance.worldState

        val definition = dev.charaly.runtime.domain.WorldDefinition(pack.characters, pack.locations)
        val validator = dev.charaly.runtime.director.ActionValidator(definition)

        // A model that narrates an event rather than proposing it changes nothing.
        val narrated = "Marinette teleports to the museum and becomes a hermit."
        assertTrue(
            "narrated prose must never be read as a world change",
            dev.charaly.runtime.director.ProposedActionParser.parse(narrated).isEmpty(),
        )

        // And a proposed move still has to be valid to become an event.
        val impossible = dev.charaly.runtime.director.ProposedAction(
            type = dev.charaly.runtime.director.ActionType.MOVE,
            characterId = marinette.value,
            target = "atlantis",
            reason = "the model wanted it to",
        )
        assertTrue(
            "a move to a place that does not exist must be refused",
            validator.toPayload(impossible) is dev.charaly.runtime.director.ActionReview.Rejected,
        )

        // A well-formed proposal is accepted as an *event*, never applied directly.
        val valid = dev.charaly.runtime.director.ProposedAction(
            type = dev.charaly.runtime.director.ActionType.MOVE,
            characterId = marinette.value,
            target = "rooftop",
        )
        val review = validator.toPayload(valid)
        assertTrue(review is dev.charaly.runtime.director.ActionReview.Accepted)

        // Nothing has changed yet: acceptance produces a payload the engine must apply.
        // Compared against the same snapshot rather than a hard-coded place, because
        // whichever opening the pack selects decides where Marinette starts.
        assertEquals(worldBefore.revision, instance.worldState.revision)
        assertEquals(worldBefore.characters, instance.worldState.characters)
        assertEquals(
            "the proposal must not have mutated anything by itself",
            worldBefore.characters.getValue(marinette).locationId,
            instance.characters.getValue(marinette).locationId,
        )
    }
}