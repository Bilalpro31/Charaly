package dev.charaly.runtime.integration

import dev.charaly.runtime.domain.CharacterDefinition
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.EventCondition
import dev.charaly.runtime.domain.EventEffect
import dev.charaly.runtime.domain.EventTrigger
import dev.charaly.runtime.domain.InitialWorldState
import dev.charaly.runtime.domain.Location
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.PackEventDefinition
import dev.charaly.runtime.domain.Relationship
import dev.charaly.runtime.domain.RelationshipDelta
import dev.charaly.runtime.domain.RelationshipType
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.StoryPackId
import dev.charaly.runtime.domain.events.EventOrigin
import dev.charaly.runtime.domain.events.WorldVariableSet
import dev.charaly.runtime.engine.EventEngine
import dev.charaly.runtime.engine.StoryCreationOptions
import dev.charaly.runtime.inference.InferenceEngine
import dev.charaly.runtime.inference.InferenceRequest
import dev.charaly.runtime.inference.InferenceResult
import dev.charaly.runtime.inference.LoadOutcome
import dev.charaly.runtime.inference.ModelInfo
import dev.charaly.runtime.inference.ModelLoadRequest
import dev.charaly.runtime.inference.StreamChunk
import dev.charaly.runtime.model.ModelBinding
import dev.charaly.runtime.model.ModelProfileLibrary
import dev.charaly.runtime.persistence.InMemoryCharalyStorage
import dev.charaly.runtime.persistence.JsonCharalyRepository
import dev.charaly.runtime.session.CharalyRuntime
import dev.charaly.runtime.session.GenerationUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ONE PLAYER TURN MUST APPLY A PACK CONDITIONAL EVENT'S EFFECTS EXACTLY ONCE.
 *
 * The risk Phase 5 recorded (item 7) was that `respond()` polls conditional events twice
 * per turn - once after the turn's actions, once inside `advance()` after the clock moves
 * - so an event that is `repeatable` with `cooldownMinutes = 0` can satisfy its own
 * conditions in both polls and land its effects twice in one turn.
 *
 * This test is the executable form of that risk: the probe event is repeatable with no
 * cooldown and its trigger condition never becomes false, so the second poll is always
 * eligible. The measurable effect is a cumulative relationship delta (`trust + 10`): if
 * the event lands twice the trust moves by 20 instead of 10.
 *
 * It also pins the two things the fix must NOT break:
 *  - `repeatable` still means "may fire again on a later turn" (it is not once-ever), and
 *  - event order and replay determinism still hold run to run.
 */
class ConditionalEventExactlyOnceTest {

    private val hall = LocationId("loc-hall")
    private val garden = LocationId("loc-garden")
    private val alice = CharacterId("ch-alice")
    private val bob = CharacterId("ch-bob")

    private val probeId = "evt-probe-repeatable"

    /**
     * Repeatable, zero cooldown, condition that stays true once set: exactly the shape
     * that is exposed to double polling.
     */
    private val probe = PackEventDefinition(
        id = probeId,
        title = "The probe event",
        trigger = EventTrigger.WhenConditionMet(pollEveryMinutes = 0),
        conditions = listOf(EventCondition.VariableEquals("probe_ready", "yes")),
        effects = listOf(
            EventEffect.ChangeRelationship(
                sourceId = alice,
                targetId = bob,
                delta = RelationshipDelta(trust = 10),
                reason = "probe",
            ),
        ),
        repeatable = true,
        cooldownMinutes = 0L,
    )

    private fun pack(): StoryPack = StoryPack(
        id = StoryPackId("pack.double-fire"),
        title = "Double Fire Probe",
        characters = listOf(
            CharacterDefinition(id = alice, name = "Alice"),
            CharacterDefinition(id = bob, name = "Bob"),
        ),
        locations = listOf(
            Location(id = hall, name = "Hall", connections = listOf(garden)),
            Location(id = garden, name = "Garden", connections = listOf(hall)),
        ),
        initialWorldState = InitialWorldState(
            startLocations = mapOf(alice to hall, bob to garden),
        ),
        initialRelationships = listOf(
            Relationship(
                sourceId = alice,
                targetId = bob,
                relationshipType = RelationshipType.ACQUAINTANCE,
                familiarity = 40,
                trust = 50,
            ),
        ),
        events = listOf(probe),
    )

    private class Scripted(private val text: String) : InferenceEngine {
        override suspend fun loadModel(request: ModelLoadRequest): LoadOutcome =
            LoadOutcome.Loaded(ModelInfo(request.installedModelId, request.path, "scripted"))

        override suspend fun unloadModel() = Unit
        override suspend fun generate(request: InferenceRequest) = InferenceResult(text)
        override fun stream(request: InferenceRequest): Flow<StreamChunk> = flow {
            emit(StreamChunk(text = text))
            emit(StreamChunk("", done = true))
        }

        override fun stop() = Unit
        override fun isLoaded() = true
        override fun modelInfo() = ModelInfo("scripted", "/m.gguf", "scripted")
    }

    private data class Run(
        val turns: List<StoryInstance>,
        val trustSteps: List<Int>,
    )

    /** Starts a story, arms the condition, and plays [turns] scripted turns. */
    private suspend fun play(turns: Int): Run {
        val pack = pack()
        val runtime = CharalyRuntime(
            repository = JsonCharalyRepository(InMemoryCharalyStorage()),
            engine = Scripted("«Devam ediyorum.»"),
        )
        runtime.createPack(pack)
        var instance = runtime.startStory(
            pack,
            StoryCreationOptions(
                instanceId = StoryInstanceId("double-fire"),
                focusCharacterId = alice,
                castCharacterIds = listOf(alice, bob),
                modelBinding = ModelBinding.from(
                    installedModelId = "scripted",
                    modelDisplayName = "Scripted",
                    profile = ModelProfileLibrary.all.first(),
                ),
                nowEpochMs = 1_700_000_000_000L,
            ),
        )
        // Arm the trigger condition through the engine, the way an authored effect would.
        val armed = EventEngine(runtime.definitionFor(instance)).applyImmediately(
            instance,
            WorldVariableSet(key = "probe_ready", value = "yes"),
            origin = EventOrigin.ENGINE,
        )
        instance = (armed as dev.charaly.runtime.engine.EventApplication.Applied).instance

        val seen = mutableListOf(instance)
        val trustSteps = mutableListOf(trustOf(instance))
        repeat(turns) {
            runtime.respond(instance, "Devam edelim, kisa yaz.", alice).collect { update ->
                (update as? GenerationUpdate.Finished)?.instance?.let { instance = it }
            }
            seen += instance
            trustSteps += trustOf(instance)
        }
        return Run(seen, trustSteps)
    }

    private fun trustOf(instance: StoryInstance): Int =
        instance.worldState.relationship(alice, bob)?.trust ?: -1

    /** How many times the probe's relationship change shows up in the world's change feed. */
    private fun probeFeedLines(instance: StoryInstance): Int =
        instance.worldState.recentChanges(200)
            .count { it.contains("${alice.value}->${bob.value}") }

    @Test
    fun `a repeatable zero-cooldown conditional event lands exactly once per turn`() = runTest {
        val run = play(turns = 2)
        val afterTurn1 = run.trustSteps[1] - run.trustSteps[0]
        val afterTurn2 = run.trustSteps[2] - run.trustSteps[1]

        assertEquals(
            "turn 1 must apply the probe's trust delta exactly once (10), not once per poll",
            10,
            afterTurn1,
        )
        assertEquals(
            "a repeatable event may still fire again on a LATER turn",
            10,
            afterTurn2,
        )
        assertEquals("both turns together", 20, run.trustSteps[2] - run.trustSteps[0])

        val feedLines = probeFeedLines(run.turns[1])
        assertEquals(
            "the change feed must record one relationship change for the turn, not two",
            1,
            feedLines,
        )
    }

    @Test
    fun `single-turn event processing stays deterministic and ordered`() = runTest {
        val first = play(turns = 2)
        val second = play(turns = 2)

        assertEquals(
            "the same turns must produce the same trust trajectory every run",
            first.trustSteps,
            second.trustSteps,
        )
        first.turns.zip(second.turns).forEachIndexed { index, (a, b) ->
            assertEquals("turn $index world state must replay identically", a.worldState, b.worldState)
            assertEquals("turn $index clock must replay identically", a.worldClock, b.worldClock)
        }
        // The event fired (recorded) but left no queue residue behind.
        val last = first.turns.last()
        assertTrue("the probe must be recorded as fired", last.firedEvents.containsKey(probeId))
        assertTrue("the queue must be empty afterwards", last.eventQueue.pending.isEmpty())
    }
}