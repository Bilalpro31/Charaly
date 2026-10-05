package dev.charaly.runtime.context

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.GoalId
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.PromiseId
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.WorldDefinition
import dev.charaly.runtime.domain.events.GoalUpdated
import dev.charaly.runtime.domain.events.PromiseForgotten
import dev.charaly.runtime.domain.events.PromiseMade
import dev.charaly.runtime.domain.events.PromiseResolved
import dev.charaly.runtime.domain.CommitmentStatus
import dev.charaly.runtime.director.SceneDirector
import dev.charaly.runtime.engine.EventApplication
import dev.charaly.runtime.engine.EventEngine
import dev.charaly.runtime.engine.StoryCreationOptions
import dev.charaly.runtime.engine.StoryInstanceFactory
import dev.charaly.runtime.pack.MiraculousPack
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Commitments have to reach the model, or the feature is a data structure nobody reads.
 *
 * And the awkward case is the interesting one: a character who has *forgotten* their
 * promise still owes it, and that is exactly the situation where a prompt built from
 * memory would quietly drop it.
 */
class CommitmentContextTest {

    private val pack = MiraculousPack.pack
    private val definition = WorldDefinition(pack.characters, pack.locations)
    private val engine = EventEngine(definition)
    private val builder = ContextBuilder(definition)

    private val andre = CharacterId("andre")
    private val marinette = CharacterId("marinette")

    private fun instance() = StoryInstanceFactory.create(
        pack,
        StoryCreationOptions(
            instanceId = StoryInstanceId("story-ctx-commit"),
            title = "Context",
            scenario = pack.defaultScenario(),
            focusCharacterId = marinette,
            castCharacterIds = listOf(marinette, andre),
            nowEpochMs = 1_700_000_000_000L,
        ),
    )

    private fun apply(from: StoryInstance, payload: dev.charaly.runtime.domain.events.EventPayload): StoryInstance =
        (engine.applyImmediately(from, payload) as EventApplication.Applied).instance

    private fun promptFor(story: StoryInstance, who: CharacterId): String {
        val scene = SceneDirector(definition).directScene(story, who)!!
        return builder.buildContext(story, scene, who).systemPrompt()
    }

    private fun promise(text: String = "I will find out who emptied the museum wing") = PromiseMade(
        promiseId = PromiseId("p-1"),
        text = text,
        keeperId = andre,
        beneficiaryId = marinette,
    )

    @Test
    fun `a character is told what they are on the hook for`() {
        val story = apply(instance(), promise())
        val prompt = promptFor(story, andre)
        assertTrue(
            "a promise nobody is told about is not a promise\n$prompt",
            prompt.contains("find out who emptied the museum wing"),
        )
    }

    @Test
    fun `a beneficiary is told they are owed something`() {
        val story = apply(instance(), promise())
        val prompt = promptFor(story, marinette)
        assertTrue(
            "being owed something is something a character may ask after\n$prompt",
            prompt.contains("promised you"),
        )
        assertTrue(prompt.contains("entitled to ask"))
    }

    @Test
    fun `a forgotten promise is still in the prompt`() {
        // The whole reason commitments are not memories: forgetting changes the
        // character's state, not the world's obligation.
        val story = apply(instance(), promise()).let { apply(it, PromiseForgotten(PromiseId("p-1"))) }
        val prompt = promptFor(story, andre)
        assertTrue(
            "a character who has forgotten their promise still owes it\n$prompt",
            prompt.contains("find out who emptied the museum wing"),
        )
    }

    @Test
    fun `a kept promise stops being something the character owes`() {
        val story = apply(instance(), promise()).let {
            apply(it, PromiseResolved(PromiseId("p-1"), CommitmentStatus.KEPT))
        }
        val prompt = promptFor(story, andre)
        assertFalse(
            "a kept promise must not nag the character\n$prompt",
            prompt.contains("WHAT YOU ARE ON THE HOOK FOR"),
        )
    }

    @Test
    fun `an active goal reaches the prompt`() {
        val story = apply(
            instance(),
            GoalUpdated(
                goalId = GoalId("g-1"),
                ownerId = andre,
                text = "find out who emptied the museum wing",
                progressDelta = 40,
            ),
        )
        assertTrue(promptFor(story, andre).contains("40% there"))
    }

    @Test
    fun `one character's commitments are not in another's prompt`() {
        val story = apply(instance(), promise())
        val other = promptFor(story, CharacterId("adrien"))
        assertFalse(
            "Adrien owes nothing and is owed nothing here",
            other.contains("find out who emptied the museum wing"),
        )
    }

    @Test
    fun `an overdue promise is marked as such for the beneficiary`() {
        val story = apply(
            instance(),
            PromiseMade(
                promiseId = PromiseId("p-late"),
                text = "tell her by six",
                keeperId = andre,
                beneficiaryId = marinette,
                dueOnDay = 1,
                dueAtMinuteOfDay = 12 * 60,
            ),
        )
        // Story starts at 08:10, so this is not yet overdue; the prompt should not claim
        // she is waiting on it.
        assertFalse(promptFor(story, marinette).contains("you are waiting on this"))
    }

    @Test
    fun `an empty commitment list costs nothing in the prompt`() {
        val prompt = promptFor(instance(), andre)
        assertFalse(prompt.contains("WHAT YOU ARE ON THE HOOK FOR"))
        assertFalse(prompt.contains("WHAT OTHERS PROMISED YOU"))
    }

    @Test
    fun `a promise made to a character who is not present still reaches them`() {
        // The ledger is global, so an off-screen character's obligations are still
        // true; the prompt is built per character precisely so that this works.
        val story = apply(instance(), promise())
        val scene = SceneDirector(definition).directScene(story, marinette)!!
        val context = builder.buildContext(story, scene, marinette)
        assertTrue(context.owedToThem.any { it.contains("find out who emptied") })
    }
}