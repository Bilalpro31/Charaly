package dev.charaly.runtime.context

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.EntityId
import dev.charaly.runtime.domain.FactId
import dev.charaly.runtime.domain.Relationship
import dev.charaly.runtime.domain.RelationshipDelta
import dev.charaly.runtime.domain.RelationshipType
import dev.charaly.runtime.domain.Scene
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.StoryTime
import dev.charaly.runtime.domain.TranscriptEntry
import dev.charaly.runtime.domain.TranscriptRole
import dev.charaly.runtime.domain.WorldDefinition
import dev.charaly.runtime.domain.WorldVariable
import dev.charaly.runtime.domain.WorldVariableType
import dev.charaly.runtime.domain.knowledge.Fact
import dev.charaly.runtime.domain.knowledge.KnowledgeEntry
import dev.charaly.runtime.domain.memory.Memory
import dev.charaly.runtime.domain.memory.MemorySource
import dev.charaly.runtime.engine.EventApplication
import dev.charaly.runtime.engine.EventEngine
import dev.charaly.runtime.engine.SampleWorlds
import dev.charaly.runtime.engine.StoryInstanceFactory
import dev.charaly.runtime.inference.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The ContextBuilder is the ONLY place where world state becomes prompt text.
 *
 * Its most important job is negative: a character must never be told something
 * they do not know. These tests hold that line.
 */
class ContextBuilderTest {

    private lateinit var pack: StoryPack
    private lateinit var definition: WorldDefinition
    private lateinit var engine: EventEngine
    private lateinit var instance: dev.charaly.runtime.domain.StoryInstance
    private lateinit var builder: ContextBuilder

    private val alice = SampleWorlds.alice.id
    private val bob = SampleWorlds.bob.id
    private val library = SampleWorlds.library.id
    private val ledgerFact = FactId("fact-ledger")
    private val lampsFact = FactId("fact-lamps")

    @Before
    fun setUp() {
        pack = SampleWorlds.libraryPack()
        definition = WorldDefinition(pack.characters, pack.locations)
        engine = EventEngine(definition)
        instance = StoryInstanceFactory.create(pack, StoryInstanceId("s1"))
        builder = ContextBuilder(definition)
    }

    private fun sceneAt(location: dev.charaly.runtime.domain.LocationId, participants: List<CharacterId>) =
        Scene(
            id = dev.charaly.runtime.domain.SceneId("scene-test"),
            locationId = location,
            participants = participants,
            focusCharacterId = participants.first(),
            activeThreadIds = listOf(dev.charaly.runtime.domain.ThreadId("thread-ledger")),
        )

    // ------------------------------------------------------------------
    // Knowledge isolation
    // ------------------------------------------------------------------

    @Test
    fun `alice is not told where the ledger is`() {
        val context = builder.buildContext(instance, sceneAt(library, listOf(alice)), alice)
        assertFalse(
            "the ledger location leaked into Alice's prompt",
            context.systemPrompt().contains("locked inside the cabinet"),
        )
        assertTrue(context.knowledge.none { it.id == ledgerFact })
    }

    @Test
    fun `bob is told what he knows`() {
        val context = builder.buildContext(instance, sceneAt(library, listOf(bob)), bob)
        assertTrue(context.knowledge.any { it.id == ledgerFact })
        assertTrue(context.systemPrompt().contains("locked inside the cabinet"))
    }

    @Test
    fun `knowledge can widen only through an event`() {
        val learned = apply(
            engine.applyImmediately(instance, dev.charaly.runtime.domain.events.KnowledgeDiscovered(alice, ledgerFact)),
        )
        val context = builder.buildContext(learned, sceneAt(library, listOf(alice)), alice)
        assertTrue(context.knowledge.any { it.id == ledgerFact })
    }

    @Test
    fun `a character's own secrets are not hidden from them`() {
        val context = builder.buildContext(instance, sceneAt(library, listOf(alice)), alice)
        assertTrue(context.knowledge.any { it.id == lampsFact })
    }

    // ------------------------------------------------------------------
    // Content
    // ------------------------------------------------------------------

    @Test
    fun `the prompt states the model is not the world`() {
        val prompt = builder.buildContext(instance, sceneAt(library, listOf(alice)), alice).systemPrompt()
        assertTrue(prompt.contains("do not run the world") || prompt.contains("You do not run the world"))
        assertTrue(prompt.contains("personally knows"))
    }

    @Test
    fun `identity personality and goals are included`() {
        val prompt = builder.buildContext(instance, sceneAt(library, listOf(alice)), alice).systemPrompt()
        assertTrue(prompt.contains("Alice"))
        assertTrue(prompt.contains("dry, observant"))
        assertTrue(prompt.contains("Recover the ledger"))
    }

    @Test
    fun `relationships of scene participants are included`() {
        // Bob walks over, so Alice and Bob now share a scene.
        val moved = apply(
            engine.applyImmediately(instance, dev.charaly.runtime.domain.events.CharacterMoved(bob, from = SampleWorlds.square.id, to = library)),
        )
        val context = builder.buildContext(moved, sceneAt(library, listOf(alice, bob)), alice)
        assertEquals(1, context.relationships.size)
        assertTrue(context.systemPrompt().contains("bob"))
        assertTrue(context.systemPrompt().contains("acquaintance"))
    }

    @Test
    fun `no relationship is invented for a stranger`() {
        // Alice alone in the library: she has no relationship to report.
        val alone = builder.buildContext(instance, sceneAt(library, listOf(alice)), alice)
        assertTrue(alone.relationships.isEmpty())
        assertFalse(alone.systemPrompt().contains("PEOPLE YOU KNOW"))
    }

    @Test
    fun `memories are budgeted rather than dumped whole`() {
        var withMemories = instance
        listOf(
            Memory(EntityId("mem-noise"), alice, "bought bread", importance = 1),
            Memory(EntityId("mem-key"), alice, "the cabinet has three locks", importance = 5),
        ).forEach { memory ->
            withMemories = apply(
                engine.applyImmediately(withMemories, dev.charaly.runtime.domain.events.MemoryCreated(memory)),
            )
        }
        val selected = builder.buildContext(withMemories, sceneAt(library, listOf(alice)), alice).memories
        assertEquals(2, selected.size)
        assertEquals("the important memory must rank first", "mem-key", selected.first().id.value)

        // A tighter budget must actually cut the context down.
        val tight = ContextBuilder(definition, ContextBudget(memories = 1))
            .buildContext(withMemories, sceneAt(library, listOf(alice)), alice)
            .memories
        assertEquals(1, tight.size)
        assertEquals("mem-key", tight.single().id.value)
    }

    @Test
    fun `the scene is described structurally`() {
        val context = builder.buildContext(instance, sceneAt(library, listOf(alice)), alice)
        assertEquals(library, context.scene.locationId)
        assertTrue(context.systemPrompt().contains("library"))
    }

    @Test
    fun `world variables the character can observe are included`() {
        val context = builder.buildContext(instance, sceneAt(library, listOf(alice)), alice)
        assertTrue(context.systemPrompt().contains("library_lamp"))
    }

    @Test
    fun `transcript is windowed by turn, not unbounded`() {
        // 20 exchanges of user+character lines, numbered as turns 1..20.
        var chatty = instance
        repeat(20) { exchange ->
            listOf(
                TranscriptRole.USER to "user line $exchange",
                TranscriptRole.CHARACTER to "alice line $exchange",
            ).forEachIndexed { offset, (role, text) ->
                chatty = chatty.evolved(
                    conversation = chatty.conversation.append(
                        TranscriptEntry(
                            id = "line-$exchange-$offset",
                            turn = exchange + 1,
                            role = role,
                            text = text,
                            at = StoryTime(1, 9, 0),
                        ),
                    ),
                )
            }
        }

        val context = builder.buildContext(chatty, sceneAt(library, listOf(alice)), alice, transcriptTurns = 3)
        assertEquals("3 turns = 6 lines", 6, context.recentTranscript.size)
        assertTrue(context.recentTranscript.none { it.text.contains("line 16") })
        assertTrue("the newest turn must be included", context.recentTranscript.any { it.text.contains("line 19") })
    }

    @Test
    fun `recent transcript keeps the newest turns in order`() {
        var chatty = instance
        listOf("first", "second", "third").forEachIndexed { index, text ->
            chatty = chatty.evolved(
                conversation = chatty.conversation.append(
                    TranscriptEntry("e$index", index + 1, TranscriptRole.USER, text, StoryTime.START),
                ),
            )
        }
        val context = builder.buildContext(chatty, sceneAt(library, listOf(alice)), alice, transcriptTurns = 2)
        assertEquals(listOf("second", "third"), context.recentTranscript.map { it.text })
    }

    // ------------------------------------------------------------------
    // Request assembly
    // ------------------------------------------------------------------

    @Test
    fun `the request carries the speaker and the user input`() {
        val request = builder.buildRequest(instance, sceneAt(library, listOf(alice)), alice, "Where is the key?")
        assertEquals(alice, request.speakerId)
        // The player's words lead the message verbatim (the volatile state reminder is
        // appended AFTER them, see `PrefixBreakpointDiagnosticTest`), and the turn is theirs.
        assertTrue(
            "the player's words must lead the message verbatim",
            request.messages.last().content.startsWith("Where is the key?"),
        )
        assertEquals(ChatRole.USER, request.messages.last().role)
    }

    @Test
    fun `the user input is not duplicated when it is also in the transcript`() {
        var saved = instance.evolved(
            conversation = instance.conversation.append(
                TranscriptEntry("u1", 1, TranscriptRole.USER, "hello", StoryTime.START),
            ),
        )
        saved = apply(
            engine.applyImmediately(saved, dev.charaly.runtime.domain.events.SceneStarted(dev.charaly.runtime.domain.SceneId("s"), library, listOf(alice))),
        )
        val context = builder.buildContext(saved, saved.currentScene()!!, alice, "hello")
        val request = builder.buildRequest(context)
        assertEquals(
            "input must appear exactly once (it may lead a message the state reminder tails)",
            1,
            request.messages.count { it.content == "hello" || it.content.startsWith("hello\n") },
        )
    }

    @Test
    fun `system prompt is a single system message, never mixed into dialogue`() {
        val request = builder.buildRequest(instance, sceneAt(library, listOf(alice)), alice, "hi")
        assertTrue(request.systemPrompt.isNotBlank())
        assertFalse("system text lives in systemPrompt", request.messages.any { it.role == ChatRole.SYSTEM })
    }

    @Test
    fun `the stable and volatile blocks together are the whole system prompt`() {
        // The split exists to document the cache boundary, not to lose text: concatenating
        // the two halves must reproduce the single system string the inspector and the size
        // estimate use, so no rule, memory or clock line can fall through it. (Phase 6
        // re-cut the boundary along churn rate - memory and mind now travel in the volatile
        // half, because they change on ordinary turns and belong behind the transcript.)
        val context = builder.buildContext(instance, sceneAt(library, listOf(alice)), alice, "hi")
        assertEquals(context.systemPrompt(), context.systemPromptStable() + context.systemPromptVolatile())
        assertTrue("rules and identity are stable", context.systemPromptStable().contains("WORLD RULES"))
        assertTrue("knowledge is stable", context.systemPromptStable().contains("WHAT YOU KNOW"))
        assertTrue(
            "the clock and pace are in the trailing half",
            context.systemPromptVolatile().contains("RIGHT NOW") &&
                context.systemPromptVolatile().contains("story time:") &&
                context.systemPromptVolatile().contains("PACE"),
        )
    }

    @Test
    fun `the request carries the action protocol in the system block`() {
        // Without the protocol the model is never told it may propose a world change, so it
        // only ever narrates. The parser and validator are unchanged; this is the one place
        // the model learns the schema.
        val request = builder.buildRequest(instance, sceneAt(library, listOf(alice)), alice, "hi")
        assertTrue(
            "the system block must carry the action protocol",
            request.systemPrompt.contains("<charaly:action") && request.systemPrompt.contains("move, discover, relate, advance_thread, end_scene"),
        )
    }

    @Test
    fun `the request carries one system block and keeps it out of the transcript`() {
        // The shipped contract: system text lives in `systemPrompt`, the transcript is
        // messages, and the user input appears once. Phase 6 moved the *volatile* half -
        // memories, mind, what just happened, clock and pace - behind the transcript so the
        // KV cache keeps the conversation across turns (`PrefixBreakpointDiagnosticTest`);
        // its CONTENT is unchanged and every line still reaches the model. What is pinned
        // here is therefore stronger than before: all of it reaches the model, none of it
        // enters the transcript, and the state reminder trails the dialogue instead of
        // interrupting it.
        val request = builder.buildRequest(instance, sceneAt(library, listOf(alice)), alice, "hi")
        val rendered = request.systemPrompt + "\n\n" + request.messages.joinToString("\n") { it.content }

        assertTrue("the system block leads with the stable text", request.systemPrompt.contains("WORLD RULES"))
        assertTrue("the constant action protocol rides in the stable system block", request.systemPrompt.contains("<charaly:action"))
        assertTrue("the clock must reach the model somewhere in the request", rendered.contains("story time:"))
        assertTrue("and so must the pace instruction", rendered.contains("PACE"))

        val last = request.messages.last()
        assertTrue(
            "the player's words must still lead the last message verbatim",
            last.content.startsWith("hi"),
        )
        assertTrue(
            "the volatile state reminder must trail the dialogue, not interrupt it",
            last.content.indexOf("story time:") > last.content.indexOf("hi"),
        )
        assertEquals("the user input is still there exactly once", 1, request.messages.count { it.content.startsWith("hi") })
        assertFalse("system text still lives outside messages", request.messages.any { it.role == ChatRole.SYSTEM })
    }

    @Test
    fun `a character with no knowledge gets an honest empty section`() {
        // A pack whose only character starts with zero knowledge and no facts.
        val ignorant = SampleWorlds.alice.copy(id = CharacterId("clerk"), name = "Clerk")
        val barePack = pack.copy(
            characters = listOf(ignorant),
            initialRelationships = emptyList(),
            initialKnowledge = dev.charaly.runtime.domain.InitialKnowledge(),
            initialStoryThreads = emptyList(),
            initialWorldState = dev.charaly.runtime.domain.InitialWorldState(
                startTime = pack.initialWorldState.startTime,
                startLocations = mapOf(ignorant.id to library),
            ),
        )
        val bare = StoryInstanceFactory.create(barePack, StoryInstanceId("bare"))
        val bareBuilder = ContextBuilder(WorldDefinition(barePack.characters, barePack.locations))
        val context = bareBuilder.buildContext(bare, sceneAt(library, listOf(ignorant.id)), ignorant.id)
        assertTrue(context.knowledge.isEmpty())
        assertTrue(
            "the prompt must admit ignorance rather than invent facts",
            context.systemPrompt().contains("nothing beyond"),
        )
    }

    @Test
    fun `cost estimation grows with content`() {
        val small = builder.buildContext(instance, sceneAt(library, listOf(alice)), alice).estimatedChars()
        val richer = apply(
            engine.applyImmediately(instance, dev.charaly.runtime.domain.events.KnowledgeDiscovered(alice, ledgerFact)),
        )
        val large = builder.buildContext(richer, sceneAt(library, listOf(alice)), alice, "a question").estimatedChars()
        assertTrue(large > small)
    }

    @Test
    fun `budget presets exist and differ`() {
        assertTrue(ContextBudget.TINY.memories < ContextBudget.GENEROUS.memories)
        assertTrue(ContextBudget.DEFAULT.maxChars in 2000..20000)
    }

    @Test
    fun `an unknown character fails loudly`() {
        val failure = runCatching { builder.buildContext(instance, sceneAt(library, listOf(alice)), CharacterId("ghost")) }
        assertTrue(failure.isFailure)
    }

    @Test
    fun `every visible fact was actually known by the character`() {
        val context = builder.buildContext(instance, sceneAt(library, listOf(alice)), alice)
        context.knowledge.forEach {
            assertTrue("context leaked ${it.id}", instance.knowledge.knows(alice, it.id))
        }
    }

    @Test
    fun `being in the same room as a character who knows a secret does not grant it`() {
        // Bob walks into the library where Alice is. Alice still does not learn
        // where the ledger is just because Bob happens to know and be present.
        val moved = apply(
            engine.applyImmediately(instance, dev.charaly.runtime.domain.events.CharacterMoved(bob, from = SampleWorlds.square.id, to = library)),
        )
        val shared = sceneAt(library, listOf(alice, bob))

        assertTrue(
            "Bob keeps what he already knew",
            builder.buildContext(moved, shared, bob).knowledge.any { it.id == ledgerFact },
        )
        assertTrue(
            "co-location must not leak knowledge",
            builder.buildContext(moved, shared, alice).knowledge.none { it.id == ledgerFact },
        )
    }

    @Test
    fun `facts at the scene location are surfaced`() {
        val context = builder.buildContext(instance, sceneAt(library, listOf(bob)), bob)
        assertTrue(context.facts.any { it.locationId == library })
    }

    private fun apply(application: EventApplication): dev.charaly.runtime.domain.StoryInstance {
        assertTrue(application is EventApplication.Applied)
        return (application as EventApplication.Applied).instance
    }
}
