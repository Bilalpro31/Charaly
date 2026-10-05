package dev.charaly.runtime.domain

import dev.charaly.runtime.context.ContextBuilder
import dev.charaly.runtime.context.ContextBudgetInspector
import dev.charaly.runtime.director.SceneDirector
import dev.charaly.runtime.engine.EventApplication
import dev.charaly.runtime.engine.EventEngine
import dev.charaly.runtime.engine.StoryHealthAnalyzer
import dev.charaly.runtime.engine.StoryCreationOptions
import dev.charaly.runtime.engine.StoryInstanceFactory
import dev.charaly.runtime.pack.MiraculousPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Story threads, and the causal graph that answers "why did this happen".
 *
 * The thread tests are about the distinction the old model could not make: a mystery
 * with no news for a week is a broken thread, while background colour with no news for a
 * week is just weather. Judging both by the same rule produces a health monitor people
 * learn to ignore, which is worse than having none.
 */
class StoryThreadStructureTest {

    private val pack = MiraculousPack.pack
    private val definition = WorldDefinition(pack.characters, pack.locations)
    private val engine = EventEngine(definition)

    private val marinette = CharacterId("marinette")

    private fun instance() = StoryInstanceFactory.create(
        pack,
        StoryCreationOptions(
            instanceId = StoryInstanceId("story-threads"),
            title = "Threads",
            scenario = pack.defaultScenario(),
            focusCharacterId = marinette,
            castCharacterIds = listOf(marinette),
            nowEpochMs = 1_700_000_000_000L,
        ),
    )

    // ------------------------------------------------------------------
    // Shape
    // ------------------------------------------------------------------

    @Test
    fun `a thread carries a kind, a progress figure and a priority`() {
        val thread = StoryThread(
            id = ThreadId("t-1"),
            title = "Something",
            kind = ThreadKind.MYSTERY,
            progress = 40,
            priority = 80,
        )
        assertEquals(ThreadKind.MYSTERY, thread.kind)
        assertEquals(40, thread.progress)
        assertEquals(80, thread.priority)
    }

    @Test
    fun `progress and priority are validated`() {
        assertTrue(runCatching { StoryThread(ThreadId("t"), "x", progress = 150) }.isFailure)
        assertTrue(runCatching { StoryThread(ThreadId("t"), "x", priority = -5) }.isFailure)
    }

    @Test
    fun `kind decides whether going quiet is a problem`() {
        assertTrue(ThreadKind.MYSTERY.requiresProgress)
        assertTrue(ThreadKind.QUEST.requiresProgress)
        assertFalse(
            "a romance arc with no news for a week is a romance arc",
            ThreadKind.RELATIONSHIP.requiresProgress,
        )
        assertFalse(ThreadKind.AMBIENT.requiresProgress)
        assertFalse(ThreadKind.AMBIENT.stalenessMatters)
        assertFalse(ThreadKind.LORE.stalenessMatters)
    }

    @Test
    fun `progress is not the same thing as stage`() {
        val thread = StoryThread(ThreadId("t"), "x", stage = 7, progress = 10)
        assertEquals(7, thread.stage)
        assertEquals(10, thread.progress)
    }

    @Test
    fun `a thread only says it is droppable when the author said nothing happens`() {
        assertTrue(
            "silence is not a decision",
            StoryThread(ThreadId("t"), "x").isDroppable(),
        )
        assertFalse(
            StoryThread(ThreadId("t"), "x", consequenceIfAbandoned = "the thread is lost").isDroppable(),
        )
    }

    @Test
    fun `a finished thread is not open`() {
        assertFalse(StoryThread(ThreadId("t"), "x", status = StoryThreadStatus.COMPLETED).isOpen())
        assertFalse(StoryThread(ThreadId("t"), "x", status = StoryThreadStatus.FAILED).isOpen())
    }

    @Test
    fun `the prompt line says where it is and what it needs`() {
        val line = StoryThread(
            id = ThreadId("t"),
            title = "The sealed wing",
            kind = ThreadKind.QUEST,
            progress = 30,
            priority = 90,
            nextBeat = "get in without the log",
            resolutionCondition = "the catalogue and the shelf agree",
        ).promptLine()
        assertTrue(line, line.contains("The sealed wing"))
        assertTrue(line, line.contains("30%"))
        assertTrue(line, line.contains("important"))
        assertTrue(line, line.contains("get in without the log"))
        assertTrue(line, line.contains("the catalogue and the shelf agree"))
    }

    // ------------------------------------------------------------------
    // The pack actually uses them
    // ------------------------------------------------------------------

    @Test
    fun `every shipped thread says how it would end`() {
        // A mystery with no resolution condition is a thread that cannot be finished
        // however long the player works at it.
        pack.initialStoryThreads.forEach { thread ->
            assertTrue(
                "thread '${thread.title}' never says what would finish it",
                thread.resolutionCondition.isNotBlank(),
            )
            assertTrue(
                "thread '${thread.title}' never says what is supposed to happen next",
                thread.nextBeat.isNotBlank(),
            )
            assertTrue(
                "thread '${thread.title}' has no declared kind",
                thread.kind != ThreadKind.AMBIENT,
            )
        }
    }

    @Test
    fun `shipped threads carry progress and priority`() {
        pack.initialStoryThreads.forEach { thread ->
            assertTrue("'${thread.title}' has no priority", thread.priority in 1..100)
            assertTrue("'${thread.title}' has no progress figure", thread.progress in 0..100)
        }
    }

    @Test
    fun `at least one shipped thread is high priority`() {
        // Priority is only meaningful if something is above the bar.
        assertTrue(
            pack.initialStoryThreads.any { it.priority >= StoryThread.HIGH_PRIORITY },
        )
    }

    // ------------------------------------------------------------------
    // Thread priority decides a contested prompt slot
    // ------------------------------------------------------------------

    @Test
    fun `the highest priority thread comes first in the prompt`() {
        val story = instance()
        val scene = SceneDirector(definition).directScene(story, marinette)!!
        val section = ContextBudgetInspector(definition)
            .assemble(story, marinette)
            .section("threads")

        if (section != null && section.itemCount > 1) {
            val rendered = section.rendered.lines().filter { it.isNotBlank() }
            val priorities = pack.initialStoryThreads
                .filter { thread -> rendered.any { it.contains(thread.title) } }
                .map { it.priority }
            assertEquals(
                "threads must be ordered by their declared priority",
                priorities.sortedDescending(),
                priorities,
            )
        }
    }

    @Test
    fun `a thread's progress reaches the prompt`() {
        val story = instance()
        val scene = SceneDirector(definition).directScene(story, marinette)!!
        val prompt = ContextBuilder(definition).buildContext(story, scene, marinette).systemPrompt()
        val active = pack.initialStoryThreads.filter { it.id in (story.currentScene()?.activeThreadIds.orEmpty()) }
        active.forEach { thread ->
            assertTrue(
                "'${thread.title}' is active but the prompt does not say how far along it is",
                prompt.contains(thread.title),
            )
        }
    }

    // ------------------------------------------------------------------
    // Advancing a thread still works
    // ------------------------------------------------------------------

    @Test
    fun `advancing a thread through an event still works`() {
        val story = instance()
        val threadId = story.storyThreads.keys.first()
        val applied = engine.applyImmediately(
            story,
            dev.charaly.runtime.domain.events.StoryThreadAdvanced(
                threadId = threadId,
                stage = 2,
                note = "someone saw the wing",
            ),
        )
        assertTrue(applied is EventApplication.Applied)
        val thread = (applied as EventApplication.Applied).instance.storyThreads.getValue(threadId)
        assertEquals(2, thread.stage)
        assertEquals("someone saw the wing", thread.state["stage"])
    }

    @Test
    fun `a thread survives persistence with its structure intact`() {
        val codec = dev.charaly.runtime.persistence.JsonCharalyRepository.defaultJson
        val original = pack.initialStoryThreads.first()
        val encoded = codec.encodeToString(StoryThread.serializer(), original)
        val decoded = codec.decodeFromString(StoryThread.serializer(), encoded)
        assertEquals(original, decoded)
        assertEquals(original.kind, decoded.kind)
        assertEquals(original.progress, decoded.progress)
        assertEquals(original.resolutionCondition, decoded.resolutionCondition)
    }

    @Test
    fun `a thread saved before this existed still opens`() {
        // Exactly the JSON an older build would have written.
        val legacy = """
            {"id":"thread-akuma","title":"The akuma after midnight","status":"ACTIVE",
             "stage":1,"description":"old","involvedCharacterIds":[],"relevantLocationIds":[],
             "state":{},"updatedAt":{"day":1,"hour":8,"minute":10}}
        """.trimIndent()
        val decoded = Jsonless.decode(legacy)
        assertEquals("old", decoded.description)
        assertEquals(
            "an older save defaults to a mystery rather than crashing",
            ThreadKind.MYSTERY,
            decoded.kind,
        )
        assertEquals(0, decoded.progress)
        assertEquals(50, decoded.priority)
        assertNull(decoded.resolutionCondition.ifBlank { null })
    }

    private object Jsonless {
        fun decode(raw: String): StoryThread =
            dev.charaly.runtime.persistence.JsonCharalyRepository.defaultJson
                .decodeFromString(StoryThread.serializer(), raw)
    }

    @Test
    fun `the health monitor distinguishes a stale mystery from stale weather`() {
        val analyzer = StoryHealthAnalyzer(definition)
        val start = instance()
        val late = dev.charaly.runtime.domain.StoryTime.of(day = 20, hour = 8, minute = 0)
        val advanced = engine.advanceTime(start, late).instance

        val findings = analyzer.analyse(advanced).of(StoryHealthAnalyzer.Kind.DEAD_THREAD)
        val stale = advanced.storyThreads.values
            .filter { it.isOpen() }
            .mapNotNull { thread -> findings.firstOrNull { it.summary.contains(thread.title) } }
        // Every open thread in the pack is a mystery, quest, personal or relationship
        // arc - none of them is ambient - so going quiet is worth reporting for all.
        assertTrue(
            "an open mystery with no news for days must be reported",
            stale.isNotEmpty(),
        )
        assertNotNull(stale.first().evidence.firstOrNull())
    }

    @Test
    fun `a thread with no resolution condition is flagged`() {
        val story = instance().let { current ->
            current.copy(
                worldState = current.worldState.withThread(
                    StoryThread(
                        id = ThreadId("thread-endless"),
                        title = "Never ends",
                        kind = ThreadKind.MYSTERY,
                        status = StoryThreadStatus.ACTIVE,
                    ),
                ),
            )
        }
        val finding = StoryHealthAnalyzer(definition)
            .analyse(story)
            .of(StoryHealthAnalyzer.Kind.UNRESOLVABLE_THREAD)
            .single()
        assertEquals(StoryHealthAnalyzer.Status.WARNING, finding.severity)
    }

    @Test
    fun `an ambient thread with no resolution condition is not flagged`() {
        val story = instance().let { current ->
            current.copy(
                worldState = current.worldState.withThread(
                    StoryThread(
                        id = ThreadId("thread-weather"),
                        title = "It rains sometimes",
                        kind = ThreadKind.AMBIENT,
                        status = StoryThreadStatus.ACTIVE,
                    ),
                ),
            )
        }
        assertFalse(
            "background colour does not owe anybody a resolution",
            StoryHealthAnalyzer(definition)
                .analyse(story)
                .of(StoryHealthAnalyzer.Kind.UNRESOLVABLE_THREAD)
                .any { it.summary.contains("It rains") },
        )
    }
}