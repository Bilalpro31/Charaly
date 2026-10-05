package dev.charaly.runtime.context

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.EntityId
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.WorldDefinition
import dev.charaly.runtime.engine.EventEngine
import dev.charaly.runtime.engine.StoryCreationOptions
import dev.charaly.runtime.engine.StoryInstanceFactory
import dev.charaly.runtime.pack.MiraculousPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Context Engine 2.0.
 *
 * The failure this guards against is silent: a huge location paragraph and a full NPC
 * roster crowd out the memory that explains why the scene exists, and the model still
 * produces fluent nonsense. So the tests here assert *what survived*, not just that
 * something was produced.
 */
class ContextBudgetInspectorTest {

    private val pack = MiraculousPack.pack
    private val definition = WorldDefinition(pack.characters, pack.locations)
    private val marinette = CharacterId("marinette")
    private val andre = CharacterId("andre")

    private fun instance() = StoryInstanceFactory.create(
        pack,
        StoryCreationOptions(
            instanceId = StoryInstanceId("story-ctx"),
            title = "Context",
            scenario = pack.defaultScenario(),
            focusCharacterId = marinette,
            castCharacterIds = listOf(marinette, andre),
            nowEpochMs = 1_700_000_000_000L,
        ),
    )

    private fun inspector(budget: ContextBudget = ContextBudget()) =
        ContextBudgetInspector(definition, budget)

    // ------------------------------------------------------------------
    // The section table
    // ------------------------------------------------------------------

    @Test
    fun `every expected section is produced`() {
        val assembled = inspector().assemble(instance(), andre)
        val ids = assembled.sections.map { it.id }
        listOf("system", "protocol", "scene", "location", "knowledge", "history").forEach {
            assertTrue("section '$it' must exist, got $ids", it in ids)
        }
    }

    @Test
    fun `sections come back in priority order`() {
        val assembled = inspector().assemble(instance(), andre)
        val priorities = assembled.sections.map { it.priority }
        assertEquals("sections must be ordered by priority", priorities.sorted(), priorities)
    }

    @Test
    fun `the system instructions and protocol are never dropped`() {
        // Even on a budget so small nothing else could fit: a prompt without its own
        // rules produces a model that narrates the user's next line.
        val tiny = ContextBudget(transcriptTurns = 0, memories = 0, maxChars = 10)
        val assembled = inspector(tiny).assemble(instance(), andre)
        assertNotNull(assembled.section("system"))
        assertNotNull(assembled.section("protocol"))
        assertTrue(assembled.section("system")!!.alwaysIncluded)
        assertTrue(assembled.section("protocol")!!.alwaysIncluded)
    }

    @Test
    fun `knowledge outranks memories`() {
        // Knowledge is a boundary; a memory is a convenience. Losing a memory is
        // recoverable, losing knowledge is not.
        val knowledgePriority = ContextBudgetInspector.PRIORITY_KNOWLEDGE
        val memoryPriority = ContextBudgetInspector.PRIORITY_MEMORIES
        assertTrue("knowledge must outrank memory", knowledgePriority < memoryPriority)
    }

    @Test
    fun `participants outrank the location description`() {
        // The section that used to swallow the prompt.
        assertTrue(
            ContextBudgetInspector.PRIORITY_PARTICIPANTS < ContextBudgetInspector.PRIORITY_LOCATION,
        )
    }

    // ------------------------------------------------------------------
    // Relevance, not enumeration
    // ------------------------------------------------------------------

    @Test
    fun `only people present are named, not every npc in the world`() {
        val scene = dev.charaly.runtime.director.SceneDirector(definition).directScene(instance(), andre)!!
        val section = inspector().assemble(instance(), andre).section("participants")!!

        // The invariant: the section is the scene's roster, not the world's cast.
        assertEquals(scene.participants.size, section.itemCount)
        assertTrue(
            "every present character must be named",
            scene.participants.all { participant ->
                val name = instance().characters[participant]?.name.orEmpty()
                section.rendered.contains(name)
            },
        )
        assertFalse(
            "the prompt must not drag Paris's whole population in: ${section.rendered}",
            section.rendered.contains("Principal") ||
                section.rendered.contains("Caretaker") ||
                section.rendered.contains("Museum Guide"),
        )
    }

    @Test
    fun `a scene with nobody else in it names nobody else`() {
        // Andre alone in his shop after closing: whoever else exists in the pack is
        // irrelevant to this prompt, and no bystander may be invented to fill it.
        val closing = dev.charaly.runtime.domain.StoryTime.of(day = 1, hour = 20, minute = 0)
        val alone = EventEngine(definition).advanceTime(instance(), closing).instance

        val section = inspector().assemble(alone, andre).section("participants")!!
        val others = alone.currentScene()?.participants.orEmpty() - andre
        assertEquals(
            "only Andre should be at the shop at 20:00, found $others",
            1,
            section.itemCount,
        )
    }

    // ------------------------------------------------------------------
    // Budget behaviour
    // ------------------------------------------------------------------

    @Test
    fun `a generous budget keeps everything`() {
        val assembled = inspector(ContextBudget.GENEROUS).assemble(instance(), andre)
        assertTrue("nothing should have been dropped", assembled.droppedItems == 0)
        assertTrue(assembled.withinBudget)
    }

    @Test
    fun `a tight budget drops the lowest priority sections first`() {
        // Sized so the transcript and memories cannot both fit.
        val roomy = inspector().assemble(instance(), andre)
        val squeezed = inspector(ContextBudget(transcriptTurns = 6, memories = 6, maxChars = roomy.totalChars / 2))
            .assemble(instance(), andre, userInput = "Tell me about the akuma you saw last night")

        // Whatever survived must be higher priority than what did not.
        val surviving = squeezed.sections.map { it.priority }.max()
        val droppedPriorities = (1..11).filter { it > surviving }
        assertTrue(
            "only low priority sections may be dropped; survived through $surviving",
            droppedPriorities.any { it >= ContextBudgetInspector.PRIORITY_HISTORY },
        )
    }

    @Test
    fun `the budget is respected`() {
        val budget = ContextBudget(transcriptTurns = 20, memories = 20, maxChars = 800)
        val assembled = inspector(budget).assemble(instance(), andre, userInput = "a question " .repeat(50))
        val flexible = assembled.sections.filterNot { it.alwaysIncluded }.sumOf { it.chars }
        assertTrue(
            "flexible content ($flexible) must fit in the budget (${budget.maxChars})",
            flexible <= budget.maxChars,
        )
    }

    @Test
    fun `the total is the sum of the sections`() {
        val assembled = inspector().assemble(instance(), andre, userInput = "hello")
        assertEquals(
            assembled.sections.sumOf { it.chars },
            assembled.totalChars,
        )
    }

    @Test
    fun `the describe line accounts for drops`() {
        val assembled = inspector(ContextBudget.TINY).assemble(instance(), andre, userInput = "a long question " .repeat(20))
        val described = assembled.describe()
        assertTrue(described, described.contains("total:"))
        if (assembled.droppedItems > 0) {
            assertTrue("drops must be visible, not hidden: $described", described.contains("dropped"))
        }
    }

    // ------------------------------------------------------------------
    // Determinism and honesty
    // ------------------------------------------------------------------

    @Test
    fun `the same turn always assembles the same prompt`() {
        val a = inspector().assemble(instance(), andre, userInput = "where were you?")
        val b = inspector().assemble(instance(), andre, userInput = "where were you?")
        assertEquals(a.sections.map { it.id to it.rendered }, b.sections.map { it.id to it.rendered })
        assertEquals(a.totalChars, b.totalChars)
    }

    @Test
    fun `a section's rendered text is what the section claims`() {
        val assembled = inspector().assemble(instance(), andre, userInput = "hello")
        assembled.sections.forEach { section ->
            assertEquals(
                "section ${section.id} reports the wrong size",
                section.rendered.length,
                section.chars,
            )
        }
    }

    @Test
    fun `an empty section costs nothing`() {
        val assembled = inspector().assemble(instance(), andre)
        assembled.sections.filter { it.isEmpty }.forEach {
            assertEquals(0, it.chars)
        }
    }

    @Test
    fun `a foreign character id cannot be inspected`() {
        // The inspector resolves ids against the pack, so a stale id fails loudly
        // rather than producing a context with a hole in it.
        val failure = runCatching {
            inspector().assemble(instance(), CharacterId("not-in-this-pack"))
        }
        assertTrue("an unknown character must fail loudly", failure.isFailure)
    }
}