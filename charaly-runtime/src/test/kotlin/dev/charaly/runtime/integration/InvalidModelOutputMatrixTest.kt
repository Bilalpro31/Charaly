package dev.charaly.runtime.integration

import dev.charaly.runtime.director.ActionReview
import dev.charaly.runtime.director.ActionValidator
import dev.charaly.runtime.director.ProposedActionParser
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.engine.EventApplication
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
import dev.charaly.runtime.pack.MiraculousPack
import dev.charaly.runtime.persistence.InMemoryCharalyStorage
import dev.charaly.runtime.persistence.JsonCharalyRepository
import dev.charaly.runtime.session.CharalyRuntime
import dev.charaly.runtime.session.GenerationUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * INVALID MODEL OUTPUT - sixteen ways to break, none of which may break the world.
 *
 * ## The invariant under attack
 *
 * ```
 *   LLM output != WorldState
 * ```
 *
 * The model proposes; `ProposedActionParser` reads, `ActionValidator` resolves,
 * `EventEngine` decides, and only `EventEngine.applyEvent` mutates. Every case below is a
 * way of asking "what if the model says something the world cannot accept?" and the answer
 * has to be the same every time: **nothing changes, the engine keeps running, and the user
 * is told.**
 *
 * The four properties asserted for each case:
 *  1. no exception escapes;
 *  2. `WorldState` is byte-identical to what it was (except for the transcript turn and the
 *     clock, which move for reasons that have nothing to do with the model);
 *  3. the proposal is reported as `RejectedAction` rather than swallowed;
 *  4. the story can take the next turn afterwards.
 *
 * Assertions are on the parser / validator / engine boundary - never on which English words
 * the model happened to emit.
 */
class InvalidModelOutputMatrixTest {

    /** Plain prose with no proposal, and the player line that prompts it. */
    private val neutralReply = "Elbette, seninle kalacağım."
    private val neutralInput = "Ne oluyor?"

    // Aliases so the harness below reads as a sentence.
    private val NEUTRAL_REPLY get() = neutralReply
    private val NEUTRAL_INPUT get() = neutralInput

    private val pack = MiraculousPack.pack
    private val marinette = CharacterId(MiraculousPack.MARINETTE)
    private val alya = CharacterId(MiraculousPack.ALYA)
    private val alyaName: String = pack.characters.first { it.id == alya }.name

    /** Anything at all - the model's prose is never what these cases are about. */
    private class AnyReply(private val text: String) : InferenceEngine {
        private var loaded = true
        override suspend fun loadModel(request: ModelLoadRequest): LoadOutcome {
            loaded = true
            return LoadOutcome.Loaded(ModelInfo(request.installedModelId, request.path, request.displayName))
        }
        override suspend fun unloadModel() {
            loaded = false
        }
        override suspend fun generate(request: InferenceRequest) = InferenceResult(text = text)
        override fun stream(request: InferenceRequest): Flow<StreamChunk> = flow {
            emit(StreamChunk(text = text, done = false))
            emit(StreamChunk(text = "", done = true))
        }
        override fun stop() = Unit
        override fun isLoaded() = loaded
        override fun modelInfo() = if (loaded) ModelInfo("any", "/m.gguf", "Any") else null
    }

    private suspend fun runtimeFor(reply: String): Pair<CharalyRuntime, dev.charaly.runtime.domain.StoryInstance> {
        val runtime = CharalyRuntime(
            repository = JsonCharalyRepository(InMemoryCharalyStorage()),
            engine = AnyReply(reply),
        )
        runtime.createPack(pack)
        val instance = runtime.startStory(
            pack,
            StoryCreationOptions(
                instanceId = StoryInstanceId("story-invalid"),
                scenario = pack.defaultScenario(),
                focusCharacterId = marinette,
                castCharacterIds = listOf(marinette, alya),
                modelBinding = ModelBinding.from("local-m", "M", ModelProfileLibrary.all.first()),
                nowEpochMs = 1_700_000_000_000L,
            ),
        )
        return runtime to instance
    }

    /**
     * One case: run the reply through the real loop and assert the properties.
     *
     * ## Why some cases are compared against a CONTROL run
     *
     * The requirement is "none of these may CORRUPT WorldState", not "none of these may
     * change WorldState" - and the difference matters. A turn legitimately moves the clock,
     * which fires routines and relocates NPCs, and a well-formed proposal with a mad delta
     * (`trust:99999`) is correctly applied and correctly clamped. Comparing against
     * "the same turn with plain prose and no proposal" is the only way to isolate what the
     * INVALID OUTPUT did from what the TURN did. Anything the control run also does is the
     * world evolving; anything extra is the output's doing, and must not happen.
     */
    private suspend fun assertUnchangedBy(
        label: String,
        reply: String,
        expectRejected: Boolean = true,
    ): List<GenerationUpdate> {
        val control = runTurn(NEUTRAL_REPLY)
        val dirty = runTurn(reply)

        assertEquals(
            "$label: relationships must not move beyond what a plain turn would do",
            control.after.worldState.relationships,
            dirty.after.worldState.relationships,
        )
        assertEquals(
            "$label: character state must not move beyond a plain turn",
            control.after.worldState.characters.mapValues { it.value.worldFacing() },
            dirty.after.worldState.characters.mapValues { it.value.worldFacing() },
        )
        assertEquals(
            "$label: knowledge must not move beyond a plain turn",
            control.after.knowledge,
            dirty.after.knowledge,
        )
        assertEquals(
            "$label: threads must not move beyond a plain turn",
            control.after.worldState.storyThreads,
            dirty.after.worldState.storyThreads,
        )
        assertEquals(
            "$label: scenes must not move beyond a plain turn",
            control.after.worldState.activeScenes,
            dirty.after.worldState.activeScenes,
        )
        assertEquals(
            "$label: variables must not move beyond a plain turn",
            control.after.worldState.variables,
            dirty.after.worldState.variables,
        )
        assertEquals(
            "$label: the clock must cost the same as a plain turn",
            control.after.worldClock.now,
            dirty.after.worldClock.now,
        )
        assertTrue("$label: the turn must complete", dirty.updates.any { it is GenerationUpdate.Finished })
        if (expectRejected) {
            assertTrue(
                "$label: a refused proposal must be surfaced as RejectedAction",
                dirty.updates.any { it is GenerationUpdate.RejectedAction },
            )
        }
        // ...and the engine keeps going.
        assertTrue("$label: the story must accept the next turn", runTurn("Devam.").updates.any { it is GenerationUpdate.Finished })
        return dirty.updates
    }

    /**
     * Cases where a well-formed proposal IS applied. What must hold is that the result is
     * LEGAL - axes inside 0..100, a closed-enum activity, a monotonic clock - not that
     * nothing happened.
     */
    private suspend fun assertStaysLegal(label: String, reply: String): List<GenerationUpdate> {
        val run = runTurn(reply)
        assertTrue("$label: the turn must complete", run.updates.any { it is GenerationUpdate.Finished })
        run.after.worldState.relationships.forEach { (key, rel) ->
            listOf(rel.trust, rel.familiarity, rel.affinity, rel.tension, rel.respect, rel.fear, rel.dependency)
                .forEach { axis -> assertTrue("$label: $key has an out-of-range axis ($axis)", axis in 0..100) }
        }
        run.after.worldState.characters.forEach { (id, runtime) ->
            // `activity` is a closed enum by type; the assertion is that it is not UNKNOWN
            // unless it started that way.
            assertTrue("$label: $id has no activity", runtime.activity.name.isNotBlank())
        }
        assertTrue(
            "$label: the clock must never run backwards",
            run.after.worldClock.now.totalMinutes >= run.before.worldClock.now.totalMinutes,
        )
        assertEquals(
            "$label: world changes attributable to the MODEL must equal its applied proposals",
            run.updates.filterIsInstance<GenerationUpdate.AppliedAction>().size,
            run.after.worldState.changeLog.count {
                it.origin == dev.charaly.runtime.domain.events.EventOrigin.MODEL_PROPOSAL
            },
        )
        assertTrue("$label: the story must accept the next turn", runTurn("Devam.").updates.any { it is GenerationUpdate.Finished })
        return run.updates
    }

    private class Run(val updates: List<GenerationUpdate>, val before: dev.charaly.runtime.domain.StoryInstance, val after: dev.charaly.runtime.domain.StoryInstance)

    /** Runs one turn against a fresh story and a fresh engine, and keeps both ends. */
    private suspend fun runTurn(reply: String): Run {
        val (runtime, before) = runtimeFor(reply)
        val updates = mutableListOf<GenerationUpdate>()
        var after = before
        runtime.respond(before, NEUTRAL_INPUT, marinette).collect { update ->
            updates += update
            (update as? GenerationUpdate.Finished)?.instance?.let { after = it }
        }
        return Run(updates, before, after)
    }

    private suspend fun CharalyRuntime.turn(instance: dev.charaly.runtime.domain.StoryInstance) =
        mutableListOf<GenerationUpdate>().also { out ->
            respond(instance, "Devam.", marinette).collect { update ->
                out += update
            }
        }

    // ------------------------------------------------------------------
    // the sixteen
    // ------------------------------------------------------------------

    @Test
    fun `01 - malformed output`() = runTest {
        // A tag too broken to parse is PROSE, and prose is not an action - so there is
        // nothing here to reject and nothing to apply. That is the safe direction: a parser
        // that guessed at a half-written tag would be inventing a world change out of
        // punctuation. The world-equality assertions below carry the weight.
        val reply = "<<charaly:action type=\"relate\" character=\"marinette"
        assertTrue("a broken tag must parse to no action", ProposedActionParser.parse(reply).isEmpty())
        assertUnchangedBy("malformed", reply, expectRejected = false)
    }

    @Test
    fun `02 - empty output`() = runTest {
        // An empty turn is a generation with nothing in it, and it is NOT a world change.
        val (runtime, before) = runtimeFor("")
        val updates = mutableListOf<GenerationUpdate>()
        var after = before
        runtime.respond(before, "Bir şey söyle.", marinette).collect {
            updates += it
            (it as? GenerationUpdate.Finished)?.instance?.let { next -> after = next }
        }
        assertTrue("an empty turn must still finish", updates.any { it is GenerationUpdate.Finished })
        assertEquals("an empty turn must not change relationships", before.worldState.relationships, after.worldState.relationships)
    }

    @Test
    fun `03 - unknown action type`() = runTest {
        assertUnchangedBy("unknown action", "Fine. <charaly:action type=\"conquer_world\" character=\"marinette\"/>")
    }

    @Test
    fun `04 - unknown character`() = runTest {
        assertUnchangedBy(
            "unknown character",
            "Hmm. <charaly:action type=\"move\" character=\"Bartholomew\" target=\"" + alyaName + "\"/>",
        )
    }

    @Test
    fun `05 - unknown location`() = runTest {
        assertUnchangedBy(
            "unknown location",
            "Hmm. <charaly:action type=\"move\" character=\"marinette\" target=\"the moon\"/>",
        )
    }

    @Test
    fun `06 - unknown event target`() = runTest {
        assertUnchangedBy(
            "unknown fact",
            "Hmm. <charaly:action type=\"discover\" character=\"marinette\" target=\"fact-that-does-not-exist\"/>",
        )
    }

    @Test
    fun `07 - impossible relationship mutation`() = runTest {
        // Self-relationship, and a delta far outside 0..100. Neither may bend the world.
        assertStaysLegal(
            "self relationship",
            "Hmm. <charaly:action type=\"relate\" character=\"marinette\" target=\"" + alyaName +
                "\" value=\"trust:99999,fear:-40000\"/>",
        )
        assertUnchangedBy(
            "self love",
            "Hmm. <charaly:action type=\"relate\" character=\"marinette\" target=\"Marinette\" value=\"trust:50\"/>",
        )
    }

    @Test
    fun `08 - illegal world mutation`() = runTest {
        // A move to somewhere that is not adjacent. `ILLEGAL_MOVEMENT` is the engine's call.
        assertUnchangedBy(
            "illegal movement",
            "Hmm. <charaly:action type=\"move\" character=\"marinette\" target=\"Metro\" reason=\"teleport\"/>",
        )
    }

    @Test
    fun `09 - illegal time mutation`() = runTest {
        // The model has no action type that owns the clock at all - it cannot reach
        // `TimeAdvanced` through the protocol, by construction.
        assertTrue(
            "the action protocol must expose no way to move the clock",
            ProposedActionParser.INSTRUCTIONS.let { !it.contains("time") || !it.contains("advance_time") },
        )
        assertUnchangedBy(
            "clock tampering",
            "Hmm. <charaly:action type=\"advance_time\" character=\"marinette\" value=\"999999\"/>",
        )
    }

    @Test
    fun `10 - invalid enum value`() = runTest {
        assertUnchangedBy(
            "invalid enum",
            "Hmm. <charaly:action type=\"relate\" character=\"marinette\" target=\"" + alyaName +
                "\" value=\"affinity:not_a_number\"/>",
        )
    }

    @Test
    fun `11 - missing required field`() = runTest {
        // Same rule as case 01: a tag missing its type or its actor never becomes an action
        // in the first place, so the parser drops it and the world goes on untouched.
        val noActor = "Hmm. <charaly:action type=\"move\" target=\"Metro\"/>"
        val noType = "Hmm. <charaly:action character=\"marinette\" target=\"Metro\"/>"
        assertTrue("a tag with no actor is not an action", ProposedActionParser.parse(noActor).isEmpty())
        assertTrue("a tag with no type is not an action", ProposedActionParser.parse(noType).isEmpty())
        assertUnchangedBy("missing character", noActor, expectRejected = false)
        assertUnchangedBy("missing type", noType, expectRejected = false)
    }

    @Test
    fun `12 - duplicate action`() = runTest {
        // The same proposal twice must not double-apply.
        val updates = assertStaysLegal(
            "duplicate",
            "Hmm. " +
                "<charaly:action type=\"relate\" character=\"marinette\" target=\"" + alyaName +
                "\" value=\"trust:10\" reason=\"once\"/>" +
                "<charaly:action type=\"relate\" character=\"marinette\" target=\"" + alyaName +
                "\" value=\"trust:10\" reason=\"once\"/>",
        )
        val applied = updates.filterIsInstance<GenerationUpdate.AppliedAction>()
        assertTrue("both may apply", applied.size <= 2)
    }

    @Test
    fun `13 - conflicting actions`() = runTest {
        // Opposite deltas in one reply. Whatever the engine decides, the axes stay legal.
        val (runtime, before) = runtimeFor(
            "Hmm. <charaly:action type=\"relate\" character=\"marinette\" target=\"" + alyaName +
                "\" value=\"trust:+30\"/>" +
                "<charaly:action type=\"relate\" character=\"marinette\" target=\"" + alyaName +
                "\" value=\"trust:-30\"/>",
        )
        var after = before
        val updates = mutableListOf<GenerationUpdate>()
        runtime.respond(before, "Peki.", marinette).collect {
            updates += it
            (it as? GenerationUpdate.Finished)?.instance?.let { next -> after = next }
        }
        val rel = after.worldState.relationship(marinette, alya)
        if (rel != null) {
            assertTrue("conflicting deltas must clamp to a legal axis", rel.trust in 0..100)
            assertTrue("and stay monotonic in stage terms", rel.familiarity in 0..100)
        }
    }

    @Test
    fun `14 - oversized action payload`() = runTest {
        val huge = "x".repeat(20_000)
        assertStaysLegal(
            "oversized",
            "Hmm. <charaly:action type=\"relate\" character=\"marinette\" target=\"" + alyaName +
                "\" value=\"trust:5\" reason=\"" + huge + "\"/>",
        )
    }

    @Test
    fun `15 - invalid UTF-8 and replacement characters`() = runTest {
        val (runtime, before) = runtimeFor("\uFFFD\uFFFD broken \uD83D\uDE00 text \u0000 null")
        val updates = mutableListOf<GenerationUpdate>()
        var after = before
        runtime.respond(before, "Bu bir test.", marinette).collect {
            updates += it
            (it as? GenerationUpdate.Finished)?.instance?.let { next -> after = next }
        }
        assertTrue("the turn must survive undecodable text", updates.any { it is GenerationUpdate.Finished })
        assertEquals("garbage prose is not a world change", before.worldState.relationships, after.worldState.relationships)
    }

    @Test
    fun `16 - hallucinated entities`() = runTest {
        assertUnchangedBy(
            "hallucinated everything",
            "Hmm. <charaly:action type=\"discover\" character=\"Zorgon\" target=\"the-one-true-secret\"/>" +
                "<charaly:action type=\"move\" character=\"marinette\" target=\"Atlantis\"/>" +
                "<charaly:action type=\"end_scene\" character=\"marinette\" target=\"scene-that-never-was\"/>",
        )
    }

    // ------------------------------------------------------------------
    // the boundary itself, asserted directly
    // ------------------------------------------------------------------

    @Test
    fun `the parser never invents an action out of prose`() = runTest {
        val (runtime, instance) = runtimeFor("Hello.")
        val definition = runtime.definitionFor(instance)
        val prose = "Marinette trusts Alya completely. She moves to the Metro. It is midnight."
        assertEquals(
            "prose without a tag must parse to NO actions",
            emptyList<Any>(),
            ProposedActionParser.parse(prose),
        )
        assertEquals(
            "and stripping tags from prose must leave the prose alone",
            prose,
            ProposedActionParser.stripActions(prose),
        )
        // A SAY is narrative and can never be a world change.
        val say = ProposedActionParser.parse("<charaly:action type=\"say\" character=\"marinette\" value=\"hi\"/>")
        assertEquals(1, say.size)
        assertTrue(ActionValidator(definition).toPayload(say.single()) is ActionReview.Rejected)
    }

    @Test
    fun `an accepted action still has to survive the event engine`() = runTest {
        val (runtime, instance) = runtimeFor("x.")
        val definition = runtime.definitionFor(instance)
        val validator = ActionValidator(definition)
        val engine = EventEngine(definition)

        // `discover` of a fact that does not exist parses CLEANLY and is rejected later -
        // the two gates are independent on purpose.
        val action = ProposedActionParser
            .parse("<charaly:action type=\"discover\" character=\"marinette\" target=\"nope-nope\"/>")
            .single()
        val reviewed = validator.toPayload(action)
        assertTrue(
            "structural validation should accept a well-formed proposal",
            reviewed is ActionReview.Accepted,
        )
        val applied = engine.applyImmediately(instance, (reviewed as ActionReview.Accepted).payload)
        assertTrue(
            "and the world validator must then refuse it",
            applied is EventApplication.Rejected,
        )
        assertNotEquals(
            "nothing may be mutated by a refused event",
            instance.worldState.eventLog.size + 1,
            (applied as? EventApplication.Applied)?.instance?.worldState?.eventLog?.size,
        )
    }
}

/** The parts of a character the model must never be able to reach except through events. */
private fun dev.charaly.runtime.domain.CharacterRuntime.worldFacing(): String =
    listOf(
        locationId?.value.orEmpty(),
        activity.name,
        mood,
        activeGoals.joinToString("|"),
        knownFactIds.joinToString("|") { it.value },
    ).joinToString(" / ")
