package dev.charaly.runtime.storypack

import dev.charaly.runtime.domain.EventCondition
import dev.charaly.runtime.domain.EventEffect
import dev.charaly.runtime.domain.ObjectiveStatus
import dev.charaly.runtime.domain.SceneChoice
import dev.charaly.runtime.domain.SceneTemplate
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.engine.BranchEngine
import dev.charaly.runtime.engine.ObjectiveEngine
import dev.charaly.runtime.engine.StoryCreationOptions
import dev.charaly.runtime.engine.StoryInstanceFactory
import dev.charaly.runtime.session.CharalyRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Branching and objectives: conditional storytelling that is *deterministic*.
 *
 * The promise: pack + state + input gives the same result every time. Not "usually the
 * same world", not "the model remembers it went well" - the same state transition, decided
 * by conditions the engine evaluates, applied as typed effects, recorded in a ledger.
 */
class StoryPackBranchingTest {

    private fun start(env: PackTestEnv, id: String = "story-branch") = runBlocking {
        env.install(PackFixtures.pack())
        val pack = env.installer.load("pack.paris.afterdark")!!.pack
        // Through the opening a player actually picks - the scenario places the cast and
        // opens the authored scene, which is what makes branches available at all.
        env.runtime.startStory(
            pack,
            dev.charaly.runtime.engine.StoryCreationOptions(
                instanceId = StoryInstanceId(id),
                scenario = pack.scenarios.first(),
            ),
        )
    }

    @Test
    fun `only branches whose conditions hold are offered`() {
        val env = PackTestEnv()
        val story = start(env)
        val pack = runBlocking { env.runtime.packFor(story)!! }
        val scene = pack.scenes.first()

        val offered = runBlocking { env.runtime.offeredChoices(story) }.map { it.choiceId }
        assertTrue(offered.contains("choice-help"))
        assertTrue(offered.contains("choice-betray"))
        assertFalse("trust is 20, the guarded branch needs 40", offered.contains("choice-guarded"))
        env.cleanup()
    }

    @Test
    fun `a branch changes the world exactly as authored`() {
        val env = PackTestEnv()
        val story = start(env)
        val outcome = runBlocking { env.runtime.takeChoice(story, "scn-rooftop-scene", "choice-help") }

        val taken = outcome as CharalyRuntime.ChoiceOutcome.Taken
        assertEquals("Yardımın karşılığını henüz görmedin.", taken.note)
        val trust = taken.instance.relationships.values.first().trust
        assertEquals(20 + 15, trust)
        assertEquals("yes", taken.instance.worldState.variables[PackFixtures.playerHelped]?.value)
        assertEquals(listOf("choice-help"), taken.instance.choiceLedger.map { it.choiceId })
        assertEquals(1, taken.instance.storyThreads.values.first().stage)
        env.cleanup()
    }

    @Test
    fun `the same input in the same state gives the same result`() {
        val env = PackTestEnv()
        val pack = runBlocking {
            env.install(PackFixtures.pack())
            env.installer.load("pack.paris.afterdark")!!.pack
        }

        val results = runBlocking {
            (1..5).map { index ->
                val story = env.runtime.startStory(pack, StoryInstanceId("story-det-$index"))
                val outcome = env.runtime.takeChoice(story, "scn-rooftop-scene", "choice-betray")
                        as CharalyRuntime.ChoiceOutcome.Taken
                outcome.instance.copy(id = StoryInstanceId("x"))
            }
        }
        val first = results.first()
        results.drop(1).forEach { other ->
            assertEquals(first.worldState.worldClock.now, other.worldState.worldClock.now)
            assertEquals(
                first.relationships.values.map { it.trust },
                other.relationships.values.map { it.trust },
            )
            assertEquals(first.worldState.variables, other.worldState.variables)
            assertEquals(first.objectives.map { it.status }, other.objectives.map { it.status })
            assertEquals(first.choiceLedger.map { it.choiceId }, other.choiceLedger.map { it.choiceId })
        }
        env.cleanup()
    }

    @Test
    fun `a once-only branch cannot be taken twice`() {
        val env = PackTestEnv()
        val story = start(env)
        val first = runBlocking { env.runtime.takeChoice(story, "scn-rooftop-scene", "choice-help") }
        val second = runBlocking {
            env.runtime.takeChoice((first as CharalyRuntime.ChoiceOutcome.Taken).instance, "scn-rooftop-scene", "choice-help")
        }
        assertTrue("the second attempt is refused", second is CharalyRuntime.ChoiceOutcome.Refused)
        env.cleanup()
    }

    @Test
    fun `a refused branch changes nothing at all`() {
        val env = PackTestEnv()
        val story = start(env)
        val outcome = runBlocking { env.runtime.takeChoice(story, "scn-rooftop-scene", "choice-guarded") }
        assertTrue(outcome is CharalyRuntime.ChoiceOutcome.Refused)
        val reloaded = runBlocking { env.runtime.loadStory(story.id) }!!
        assertEquals(emptyList<Any>(), reloaded.choiceLedger)
        assertEquals(packDefaultTrust, reloaded.relationships.values.first().trust)
        env.cleanup()
    }

    private val packDefaultTrust = PackFixtures.pack().initialRelationships.first().trust

    @Test
    fun `an objective completes when its condition holds and pays out through the engine`() {
        val env = PackTestEnv()
        val story = start(env)
        val taken = runBlocking { env.runtime.takeChoice(story, "scn-rooftop-scene", "choice-help") }
                as CharalyRuntime.ChoiceOutcome.Taken

        val objective = taken.instance.objectives.first()
        assertEquals(ObjectiveStatus.COMPLETED, objective.status)
        // The reward is a real knowledge grant: Adrien now knows the guarded fact.
        assertTrue(taken.instance.knowledge.knows(PackFixtures.adrien.id, PackFixtures.guardianFact.id))
    }

    @Test
    fun `an objective fails when its failure condition holds`() {
        val env = PackTestEnv()
        val story = start(env)
        val taken = runBlocking { env.runtime.takeChoice(story, "scn-rooftop-scene", "choice-betray") }
                as CharalyRuntime.ChoiceOutcome.Taken
        assertEquals(ObjectiveStatus.FAILED, taken.instance.objectives.first().status)
    }

    @Test
    fun `an opening branch is a real fork, not flavour text`() {
        val env = PackTestEnv()
        val story = start(env)
        val opening = runBlocking { env.runtime.offeredChoices(story) }
            .first { it.sceneId == BranchEngine.OPENING_SCENE_ID && it.choiceId == "open-climb" }

        val outcome = runBlocking { env.runtime.takeChoice(story, opening.sceneId, opening.choiceId) }
                as CharalyRuntime.ChoiceOutcome.Taken
        assertTrue(outcome.instance.choiceLedger.any { it.choiceId == "open-climb" })
        assertEquals(25, outcome.instance.relationships.values.first().trust)
    }

    @Test
    fun `objective seeding follows the opening that was chosen`() {
        val pack = PackFixtures.pack()
        val scenario = pack.scenarios.first()
        val seeded = ObjectiveEngine.seed(pack, scenario, pack.initialWorldState.startTime)
        assertEquals(listOf("obj-trust"), seeded.map { it.objectiveId })

        val otherOpening = scenario.copy(id = "scn-other", objectiveIds = emptyList())
        val seededElsewhere = ObjectiveEngine.seed(pack.copy(objectives = pack.objectives.map {
            it.copy(autoActivate = false, scenarioIds = listOf("scn-rooftop"))
        }), otherOpening, pack.initialWorldState.startTime)
        assertTrue("a story is not asked for another opening's quest", seededElsewhere.isEmpty())
    }

    @Test
    fun `failure wins when an objective manages to satisfy both`() {
        val pack = PackFixtures.pack().copy(
            objectives = PackFixtures.pack().objectives.map {
                it.copy(
                    failureConditions = listOf(EventCondition.VariableEquals(PackFixtures.playerHelped, "yes")),
                )
            },
        )
        val scenario = pack.scenarios.first()
        val instance = StoryInstanceFactory.create(pack, StoryCreationOptions(instanceId = StoryInstanceId("s"), scenario = scenario))
        val resolved = instance.copy(
            worldState = instance.worldState.copy(
                variables = instance.worldState.variables +
                    (PackFixtures.playerHelped to instance.worldState.variables.getValue(PackFixtures.playerHelped)
                        .copy(value = "yes")),
            ),
        )
        val evaluation = ObjectiveEngine.evaluate(resolved, pack)
        assertEquals(ObjectiveStatus.FAILED, evaluation.objectives.first().status)
    }

    @Test
    fun `scene exit conditions describe when the scene closes`() {
        val pack = PackFixtures.pack()
        val scene = pack.scenes.first()
        val instance = StoryInstanceFactory.create(pack, StoryCreationOptions(instanceId = StoryInstanceId("s"), scenario = pack.scenarios.first()))
        assertFalse("the exit condition waits on the branch", scene.wantsToClose(instance))
    }

    @Test
    fun `a branch can open availability for another scene`() {
        val pack = PackFixtures.pack()
        val locked = SceneTemplate(
            id = "scn-locked",
            name = "Kapı arkasındaki çatı",
            locationId = PackFixtures.paris.id,
            entryConditions = listOf(EventCondition.VariableEquals(PackFixtures.playerHelped, "yes")),
            choices = listOf(
                SceneChoice(
                    id = "choice-locked-go",
                    label = "İlerle",
                    effects = listOf(EventEffect.SetVariable("reached_end", "yes")),
                ),
            ),
        )
        val extended = pack.copy(scenes = pack.scenes + locked)
        val instance = StoryInstanceFactory.create(extended, StoryCreationOptions(instanceId = StoryInstanceId("s"), scenario = extended.scenarios.first()))

        assertFalse("unreachable while the condition is unmet", locked.isAvailable(instance))
        val engine = dev.charaly.runtime.engine.EventEngine(
            dev.charaly.runtime.domain.WorldDefinition(extended.characters, extended.locations),
        )
        // Deterministic chain: branch -> variable -> new availability.
        val payloads = BranchEngine.compile(instance, "scn-rooftop-scene", extended.scenes.first().choice("choice-help")!!, instance.worldClock.now)
        var current = instance
        payloads.forEach { payload ->
            current = (engine.applyImmediately(current, payload, dev.charaly.runtime.domain.events.EventOrigin.STORY_PACK)
                    as dev.charaly.runtime.engine.EventApplication.Applied).instance
        }
        assertTrue("the branch unlocked the scene", locked.isAvailable(current))
        assertTrue(BranchEngine.offered(current, extended).any { it.choiceId == "choice-locked-go" })
    }

    @Test
    fun `compile refuses to compile a branch that is not on offer`() {
        val pack = PackFixtures.pack()
        val instance = StoryInstanceFactory.create(pack, StoryCreationOptions(instanceId = StoryInstanceId("s"), scenario = pack.scenarios.first()))
        val choice = pack.scenes.first().choice("choice-guarded")!!
        assertTrue(BranchEngine.compile(instance, "scn-rooftop-scene", choice, instance.worldClock.now).isEmpty())
    }
}