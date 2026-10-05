package dev.charaly.runtime.pack

import dev.charaly.runtime.domain.CharacterActivity
import dev.charaly.runtime.domain.EventTrigger
import dev.charaly.runtime.domain.StoryDuration
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.StoryPackId
import dev.charaly.runtime.engine.EventProgram
import dev.charaly.runtime.engine.StoryCreationOptions
import dev.charaly.runtime.engine.StoryInstanceFactory
import dev.charaly.runtime.model.ModelBindingResolver
import dev.charaly.runtime.model.ModelProfileLibrary
import dev.charaly.runtime.persistence.InMemoryCharalyStorage
import dev.charaly.runtime.persistence.JsonCharalyRepository
import dev.charaly.runtime.session.CharalyRuntime
import dev.charaly.runtime.inference.MockInferenceEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The demo worlds have to behave like real data, not like fixtures.
 *
 * These tests assert the properties the UI depends on: authored events compile into
 * typed payloads, conditional events fire through the event engine and nowhere else,
 * knowledge stays separated per character, and three worlds stay three worlds.
 */
class DemoPackBehaviourTest {

    private fun runtime(): Pair<CharalyRuntime, InMemoryCharalyStorage> {
        val storage = InMemoryCharalyStorage()
        return CharalyRuntime(
            repository = JsonCharalyRepository(storage),
            engine = MockInferenceEngine(),
        ) to storage
    }

    @Test
    fun `authored events compile into typed payloads only`() {
        val pack = MiraculousPack.pack
        pack.events.forEach { event ->
            val payloads = EventProgram.payloadsFor(
                definition = event,
                instance = StoryInstanceFactory.create(pack, StoryInstanceId("compile-${event.id}")),
            )
            // Every effect must become a typed payload, or it is silently dropped:
            // that is the rule that stops prose from mutating the world.
            assertEquals(
                "${event.id} should compile all of its effects",
                event.effects.count { it !is dev.charaly.runtime.domain.EventEffect.ScheduleEvent } +
                    event.threadChanges.size,
                payloads.size,
            )
            payloads.forEach { payload ->
                assertNotNull(payload.summary)
                assertTrue("${event.id} produced an empty summary", payload.summary.isNotBlank())
            }
        }
    }

    @Test
    fun `a story start schedules the pack's opening events`() = runTest {
        val (runtime, _) = runtime()
        val pack = MiraculousPack.pack
        runtime.createPack(pack)
        val scenario = pack.scenario("school-morning")!!
        val instance = runtime.startStory(
            pack,
            StoryCreationOptions(
                instanceId = StoryInstanceId("marinette-school"),
                scenario = scenario,
                focusCharacterId = scenario.focusCharacterId,
                castCharacterIds = scenario.castCharacterIds,
            ),
        )

        // "School Morning" is a StoryStart event scoped to this scenario, so its
        // activity change must actually have been applied.
        val alya = instance.characters[scenario.focusCharacterId!!]!!
        assertEquals(CharacterActivity.TALKING, alya.activity)
    }

    @Test
    fun `a conditional event fires only when its conditions hold`() = runTest {
        val (runtime, _) = runtime()
        val pack = NeonDistrictPack.pack
        runtime.createPack(pack)
        val instance = runtime.startStory(
            pack,
            StoryCreationOptions(instanceId = StoryInstanceId("neon-conditional")),
        )

        // Nothing has moved yet, so a location-gated event must not have fired.
        val conditionalIds = pack.events
            .filter { it.trigger is EventTrigger.WhenConditionMet }
            .map { it.id }
        assertTrue("the demo pack should have conditional events", conditionalIds.isNotEmpty())
        conditionalIds.forEach { id ->
            assertFalse(
                "$id fired before its conditions were met",
                instance.firedEvents.containsKey(id),
            )
        }

        // Ask the runtime to poll. Only events the engine accepted *right now* may
        // appear in the fired set: the monitor is the only thing that fires them.
        val candidates = EventProgram.conditionalCandidates(pack, instance).map { it.id }.toSet()
        val afterPoll = runtime.pollConditionalEvents(instance)
        afterPoll.firedEvents.keys
            .filter { it in conditionalIds }
            .forEach { fired ->
                assertTrue(
                    "$fired fired although its conditions did not hold",
                    fired in candidates,
                )
            }
    }

    @Test
    fun `advancing the clock drains scheduled events through the engine`() = runTest {
        val (runtime, _) = runtime()
        val pack = MiraculousPack.pack
        runtime.createPack(pack)
        val instance = runtime.startStory(
            pack,
            StoryCreationOptions(
                instanceId = StoryInstanceId("marinette-clock"),
                scenario = pack.scenario("school-morning"),
            ),
        )

        val before = instance.worldState.revision
        // The pack schedules "Unexpected Akuma Alert" 90 minutes in, and
        // "Evening Patrol" 180 minutes in.
        val advanced = runtime.advance(instance, StoryDuration.hours(4))
        assertTrue("the clock should have advanced", advanced.worldClock.now.totalMinutes > instance.worldClock.now.totalMinutes)
        assertTrue("events should have been applied", advanced.worldState.revision > before)
        assertEquals(
            "true",
            advanced.worldState.variables["akuma_alert"]?.value,
        )
    }

    @Test
    fun `knowledge stays separated between characters`() = runTest {
        val (runtime, _) = runtime()
        val pack = MiraculousPack.pack
        runtime.createPack(pack)
        val instance = runtime.startStory(pack, StoryCreationOptions(instanceId = StoryInstanceId("marinette-knowledge")))

        val marinette = dev.charaly.runtime.domain.CharacterId("marinette")
        val gabriel = dev.charaly.runtime.domain.CharacterId("gabriel")

        // World truth: the fact exists.
        val secret = dev.charaly.runtime.domain.FactId("fact-gabriel-motive")
        assertNotNull(instance.knowledge.fact(secret))
        // Gabriel knows it; Marinette does not.
        assertTrue(instance.knowledge.knows(gabriel, secret))
        assertFalse(instance.knowledge.knows(marinette, secret))
    }

    @Test
    fun `three worlds stay three worlds`() = runTest {
        val (runtime, _) = runtime()
        DemoStoryPacks.all.forEach { runtime.createPack(it) }

        val ids = DemoStoryPacks.all.map { it.id.value }.toSet()
        assertEquals(3, ids.size)

        val instances: List<dev.charaly.runtime.domain.StoryInstance> = DemoStoryPacks.all.map { pack ->
            runtime.startStory(pack, StoryCreationOptions(instanceId = StoryInstanceId("iso-${pack.id.value}")))
        }

        // Each instance only ever knows its own characters.
        val expected = DemoStoryPacks.all.map { it.characters.size }
        assertEquals(expected, instances.map { it.characters.size })

        // And an id from one pack is unknown in another.
        val miraculous = instances[0]
        val neon = instances[1]
        val kingdom = instances[2]
        assertNull(neon.characters[dev.charaly.runtime.domain.CharacterId("marinette")])
        assertNull(kingdom.characters[dev.charaly.runtime.domain.CharacterId("nova")])
        assertNull(miraculous.characters[dev.charaly.runtime.domain.CharacterId("elara")])
    }

    @Test
    fun `a story records the model it was created with`() = runTest {
        val (runtime, _) = runtime()
        val pack = MiraculousPack.pack
        runtime.createPack(pack)
        val binding = ModelBindingResolver.resolve(
            packDefaultProfileId = pack.defaultModelProfileId,
            installedModel = null,
            nowEpochMs = 1_000L,
        )
        assertEquals(ModelProfileLibrary.BALANCED, binding.profileId)

        val instance = runtime.startStory(
            pack,
            StoryCreationOptions(
                instanceId = StoryInstanceId("marinette-model"),
                modelBinding = binding,
                nowEpochMs = 1_000L,
            ),
        )
        assertEquals("Roleplay Balanced", instance.modelBinding.profileName)
        assertEquals(1_000L, instance.sessionMeta.createdAtEpochMs)
    }

    @Test
    fun `a restarted story is byte-for-byte the same story`() = runTest {
        val (runtime, storage) = runtime()
        val pack = LastKingdomPack.pack
        runtime.createPack(pack)
        val created = runtime.startStory(
            pack,
            StoryCreationOptions(
                instanceId = StoryInstanceId("kingdom-restart"),
                title = "The First Council",
            ),
        )

        // A brand new runtime over the same storage: this is what "relaunch the app"
        // looks like.
        val restarted = CharalyRuntime(
            repository = JsonCharalyRepository(storage),
            engine = MockInferenceEngine(),
        )
        val restored = restarted.loadStory(created.id)
        assertNotNull(restored)
        assertEquals(created.worldState, restored!!.worldState)
        assertEquals(created.conversation, restored.conversation)
        assertEquals(created.title, restored.title)
        assertEquals(created.modelBinding, restored.modelBinding)
    }

    @Test
    fun `every demo pack has a reachable default scenario`() {
        DemoStoryPacks.all.forEach { pack ->
            val scenario = pack.defaultScenario()
            assertNotNull("${pack.title} has no opening", scenario)
            assertNotNull(pack.location(scenario!!.startLocationId))
            assertNotNull(pack.defaultPersona())
        }
    }

    @Test
    fun `pack ids are unique across the shipped library`() {
        val all = DemoStoryPacks.initialLibrary()
        // No two packs may share an id, or one world would overwrite the other.
        assertEquals(all.size, all.map { it.id }.toSet().size)
        // No two packs may share a character id either: a StoryInstance resolves ids
        // against its own pack, so a collision would make isolation a convention
        // instead of a guarantee.
        val characterIds = all.flatMap { pack -> pack.characters.map { it.id } }
        assertEquals(characterIds.size, characterIds.toSet().size)
        val locationIds = all.flatMap { pack -> pack.locations.map { it.id } }
        assertEquals(locationIds.size, locationIds.toSet().size)
    }
}