package dev.charaly.runtime.storypack

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.StoryDuration
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.session.CharalyRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Isolation: N stories from one pack are N worlds.
 *
 * The pack is immutable authoring content; everything mutable lives in the instance. These
 * tests poke every kind of state a story owns - clock, relationships, variables, threads,
 * memories, objectives, decisions - through one story and assert that a sibling story never
 * flinches.
 */
class StoryPackInstanceIsolationTest {

    private fun taken(outcome: CharalyRuntime.ChoiceOutcome): StoryInstance =
        (outcome as CharalyRuntime.ChoiceOutcome.Taken).instance

    @Test
    fun `ten stories from one pack develop independently`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack())
            val pack = env.installer.load("pack.paris.afterdark")!!.pack

            val stories = (1..10).map { index ->
                env.runtime.startStory(pack, StoryInstanceId("story-$index"))
            }

            // Drive the first one forward.
            val target = stories.first()
            val advanced = env.runtime.advanceLatest(target.id, StoryDuration(minutes = 90))!!
            env.runtime.takeChoice(advanced, "scn-rooftop-scene", "choice-betray")

            val siblings = env.runtime.listInstances().filter { it.id != target.id }
            assertEquals(9, siblings.size)
            siblings.forEach { sibling ->
                assertEquals("clock moved for one story only", pack.initialWorldState.startTime, sibling.worldClock.now)
                assertTrue("no decisions leaked", sibling.choiceLedger.isEmpty())
                assertTrue("no objective resolved itself", sibling.objectives.all { it.status.name == "ACTIVE" })
            }
        }
        env.cleanup()
    }

    @Test
    fun `a relationship changed in one story is untouched in another`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack())
            val pack = env.installer.load("pack.paris.afterdark")!!.pack
            val alpha = env.runtime.startStory(pack, StoryInstanceId("story-alpha"))
            val beta = env.runtime.startStory(pack, StoryInstanceId("story-beta"))

            env.runtime.takeChoice(alpha, "scn-rooftop-scene", "choice-help")

            val alphaAfter = env.runtime.loadStory(alpha.id)!!
            val betaAfter = env.runtime.loadStory(beta.id)!!
            assertNotEquals(
                alphaAfter.relationships.values.first().trust,
                betaAfter.relationships.values.first().trust,
            )
            assertEquals(pack.initialRelationships.first().trust, betaAfter.relationships.values.first().trust)
        }
        env.cleanup()
    }

    @Test
    fun `the pack is immutable no matter how many stories play it`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack())
            val before = env.installer.load("pack.paris.afterdark")!!.pack
            val story = env.runtime.startStory(before, StoryInstanceId("story-writer"))
            env.runtime.takeChoice(story, "scn-rooftop-scene", "choice-help")

            assertEquals(before, env.installer.load("pack.paris.afterdark")!!.pack)
        }
        env.cleanup()
    }

    @Test
    fun `a branched story is its own world with a memory of its origin`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack())
            val pack = env.installer.load("pack.paris.afterdark")!!.pack
            val original = env.runtime.startStory(pack, StoryInstanceId("story-root"))
            val played = taken(env.runtime.takeChoice(original, "scn-rooftop-scene", "choice-help"))

            val branch = env.runtime.duplicateStory(
                played,
                StoryInstanceId("story-branch"),
                newTitle = "Dal",
                nowEpochMs = 42L,
            )
            assertNotNull(branch.branchedFrom)

            val branchAfter = taken(env.runtime.takeChoice(branch, "scn-rooftop-scene", "choice-betray"))
            val rootAfter = env.runtime.loadStory(original.id)!!
            assertEquals(listOf("choice-help", "choice-betray"), branchAfter.choiceLedger.map { it.choiceId })
            assertNotEquals(
                rootAfter.choiceLedger.map { it.choiceId },
                branchAfter.choiceLedger.map { it.choiceId },
            )
        }
        env.cleanup()
    }

    @Test
    fun `saves live outside the pack tree so deleting a pack cannot reach them`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack())
            val pack = env.installer.load("pack.paris.afterdark")!!.pack
            val story = env.runtime.startStory(pack, StoryInstanceId("story-safe"))
            env.installer.delete("pack.paris.afterdark", force = true)

            val reloaded = env.runtime.loadStory(story.id)!!
            assertEquals(story.conversation.size, reloaded.conversation.size)
            assertEquals(story.objectives.size, reloaded.objectives.size)
        }
        env.cleanup()
    }

    @Test
    fun `each instance keeps its own conversation and its own focus`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack())
            val pack = env.installer.load("pack.paris.afterdark")!!.pack
            val first = env.runtime.startStory(pack, StoryInstanceId("story-talk-a"))
            val second = env.runtime.startStory(pack, StoryInstanceId("story-talk-b"))

            env.runtime.setFocus(first, CharacterId("ch-adrien"))
            assertEquals(CharacterId("ch-adrien"), env.runtime.loadStory(first.id)!!.focusCharacterId)
            assertEquals(PackFixtures.marinette.id, env.runtime.loadStory(second.id)!!.focusCharacterId)
        }
        env.cleanup()
    }

    @Test
    fun `objective progress is per story`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack())
            val pack = env.installer.load("pack.paris.afterdark")!!.pack
            val winner = env.runtime.startStory(pack, StoryInstanceId("story-win"))
            val loser = env.runtime.startStory(pack, StoryInstanceId("story-lose"))

            env.runtime.takeChoice(winner, "scn-rooftop-scene", "choice-help")
            env.runtime.takeChoice(loser, "scn-rooftop-scene", "choice-betray")

            val winState = env.runtime.loadStory(winner.id)!!.objectives.first()
            val loseState = env.runtime.loadStory(loser.id)!!.objectives.first()
            assertEquals("COMPLETED", winState.status.name)
            assertEquals("FAILED", loseState.status.name)
        }
        env.cleanup()
    }
}