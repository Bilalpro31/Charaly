package dev.charaly.runtime.integration

import dev.charaly.runtime.domain.CharacterDefinition
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.CharacterRole
import dev.charaly.runtime.domain.EventCondition
import dev.charaly.runtime.domain.EventEffect
import dev.charaly.runtime.domain.FactId
import dev.charaly.runtime.domain.InitialKnowledge
import dev.charaly.runtime.domain.InitialWorldState
import dev.charaly.runtime.domain.Location
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.ObjectiveStatus
import dev.charaly.runtime.domain.PackObjective
import dev.charaly.runtime.domain.Relationship
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.StoryPackId
import dev.charaly.runtime.domain.WorldVariable
import dev.charaly.runtime.domain.WorldVariableType
import dev.charaly.runtime.domain.events.WorldVariableSet
import dev.charaly.runtime.domain.knowledge.Fact
import dev.charaly.runtime.engine.StoryCreationOptions
import dev.charaly.runtime.inference.InferenceEngine
import dev.charaly.runtime.inference.InferenceError
import dev.charaly.runtime.inference.InferenceRequest
import dev.charaly.runtime.inference.InferenceResult
import dev.charaly.runtime.inference.LoadOutcome
import dev.charaly.runtime.inference.ModelInfo
import dev.charaly.runtime.inference.ModelLoadRequest
import dev.charaly.runtime.inference.StreamChunk
import dev.charaly.runtime.model.ModelBinding
import dev.charaly.runtime.model.ModelProfileLibrary
import dev.charaly.runtime.persistence.CharalyRepository
import dev.charaly.runtime.persistence.FileCharalyStorage
import dev.charaly.runtime.persistence.InMemoryCharalyStorage
import dev.charaly.runtime.persistence.JsonCharalyRepository
import dev.charaly.runtime.session.CharalyError
import dev.charaly.runtime.session.CharalyRuntime
import dev.charaly.runtime.session.GenerationUpdate
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE TWELVE STORY JOURNEYS, end to end through the production turn loop.
 *
 * ```
 *  player message -> StoryInstance + WorldState -> SceneDirector -> ContextBuilder
 *                 -> InferenceEngine -> narrative/proposal separation
 *                 -> ProposedActionParser -> ActionValidator -> EventEngine -> WorldState
 *                 -> persistence -> refreshed context -> next turn
 * ```
 *
 * Every journey runs `CharalyRuntime.respond` - the exact call the stage makes - and
 * asserts STATE, never "the method returned happy". The pipeline is real: real
 * SceneDirector, real ContextBuilder, real parser, real validator, real event engine, real
 * repository. **The only substitute is the transport into [InferenceEngine]** (a scripted /
 * exploding / interrupting engine), so these are FAKE-INFERENCE integration tests. They are
 * not real-GGUF results and not device results; `RealStoryChatLoopTest` is the real-model
 * counterpart and skips itself without a model file.
 *
 * Fixture: one small pack with an isolated location (so illegal movement is expressible),
 * three characters (so knowledge boundaries are testable), a secret, an objective whose
 * payout is always refused, and Turkish content throughout - the UTF-8 path is exercised
 * from composer to prompt to parse to store to reload.
 */
class StoryLoopJourneysTest {

    // ---- the world ----------------------------------------------------

    private val hall = LocationId("loc-hall")
    private val garden = LocationId("loc-garden")
    private val tower = LocationId("loc-tower")

    private val alice = CharacterId("ch-alice")
    private val bob = CharacterId("ch-bob")
    private val carol = CharacterId("ch-carol")

    private val secretId = FactId("fact-mektup")
    private val openId = FactId("fact-fener")

    private val secretFact = Fact(
        id = secretId,
        description = "Mektup portrenin arkasında saklı.",
        secret = true,
    )
    private val openFact = Fact(
        id = openId,
        description = "Fener her akşam saat dokuzda söner.",
    )

    private fun journeyPack(): StoryPack = StoryPack(
        id = StoryPackId("pack.journey"),
        title = "Yolculuk",
        characters = listOf(
            CharacterDefinition(id = alice, name = "Ayşe", storyRole = CharacterRole.PROTAGONIST),
            CharacterDefinition(id = bob, name = "Bora", storyRole = CharacterRole.NPC),
            CharacterDefinition(id = carol, name = "Cemre", storyRole = CharacterRole.NPC),
        ),
        locations = listOf(
            Location(id = hall, name = "Salon", connections = listOf(garden)),
            Location(id = garden, name = "Bahçe", connections = listOf(hall)),
            // Deliberately unreachable: the move every journey 3/8 proposal aims at.
            Location(id = tower, name = "Kule", connections = emptyList()),
        ),
        initialWorldState = InitialWorldState(
            startLocations = mapOf(alice to hall, bob to hall, carol to garden),
            variables = listOf(
                WorldVariable(key = "journey_flag", type = WorldVariableType.BOOLEAN, value = "unset"),
            ),
        ),
        initialRelationships = listOf(
            Relationship(sourceId = alice, targetId = bob, trust = 40, familiarity = 30),
        ),
        initialKnowledge = InitialKnowledge(
            facts = listOf(secretFact, openFact),
            // Ayşe holds the secret from the pack's own start - the authored source. Bora
            // and Cemre start knowing nothing: `fact-fener` exists as truth with no knower,
            // which is exactly what journey 6 grants and watches for leaks.
            characterKnowledge = mapOf(
                alice to listOf(secretId.value),
            ),
        ),
        objectives = listOf(
            PackObjective(
                id = "obj-bayrak",
                title = "Bayrak",
                description = "Ama ödülü asla düşmeyecek bir hamle.",
                completionConditions = listOf(EventCondition.VariableEquals("journey_flag", "yes")),
                // Always refused (the tower is unreachable), so a buggy resolver would
                // have something to hide: the objective must stay ACTIVE and retryable.
                onComplete = listOf(EventEffect.MoveCharacter(alice, tower)),
            ),
        ),
    )

    // ---- engines ------------------------------------------------------

    /** Canned replies, one per turn, in request order. Fake inference, real everything else. */
    private class Scripted(private val replies: List<String>) : InferenceEngine {
        val requests = mutableListOf<InferenceRequest>()
        private var index = 0
        private var loaded = true

        override suspend fun loadModel(request: ModelLoadRequest): LoadOutcome {
            loaded = true
            return LoadOutcome.Loaded(
                ModelInfo(id = request.installedModelId, path = request.path, displayName = request.displayName),
            )
        }

        override suspend fun unloadModel() {
            loaded = false
        }

        override suspend fun generate(request: InferenceRequest): InferenceResult =
            InferenceResult(text = next())

        override fun stream(request: InferenceRequest): Flow<StreamChunk> = flow {
            requests += request
            val text = next()
            text.chunked(24).forEach { emit(StreamChunk(text = it, done = false)) }
            emit(StreamChunk(text = "", done = true))
        }

        override fun stop() = Unit
        override fun isLoaded(): Boolean = loaded
        override fun modelInfo(): ModelInfo? =
            if (loaded) ModelInfo("scripted", "/models/scripted.gguf", "Scripted") else null

        private fun next(): String = replies.getOrElse(index++) { replies.lastOrNull().orEmpty() }
    }

    /** Dies mid-reply, exactly as a decode error does. */
    private class Exploding : InferenceEngine {
        private var loaded = true
        override suspend fun loadModel(request: ModelLoadRequest): LoadOutcome {
            loaded = true
            return LoadOutcome.Loaded(ModelInfo("boom", "/models/boom.gguf", "Boom"))
        }

        override suspend fun unloadModel() {
            loaded = false
        }

        override suspend fun generate(request: InferenceRequest): InferenceResult =
            throw InferenceError.GenerationFailed("decode died")

        override fun stream(request: InferenceRequest): Flow<StreamChunk> = flow {
            emit(StreamChunk(text = "Bu cüm", done = false))
            emit(StreamChunk(text = "le asla ", done = false))
            throw InferenceError.GenerationFailed("decode died")
        }

        override fun stop() = Unit
        override fun isLoaded(): Boolean = loaded
        override fun modelInfo(): ModelInfo? = if (loaded) ModelInfo("boom", "/models/boom.gguf", "Boom") else null
    }

    /** Streams a little and then reports cancellation - what "stop" looks like from here. */
    private class Interrupted : InferenceEngine {
        private var loaded = true
        override suspend fun loadModel(request: ModelLoadRequest): LoadOutcome {
            loaded = true
            return LoadOutcome.Loaded(ModelInfo("cut", "/models/cut.gguf", "Cut"))
        }

        override suspend fun unloadModel() {
            loaded = false
        }

        override suspend fun generate(request: InferenceRequest): InferenceResult =
            throw InferenceError.Cancelled()

        override fun stream(request: InferenceRequest): Flow<StreamChunk> = flow {
            emit(StreamChunk(text = "Yarım ", done = false))
            emit(StreamChunk(text = "kalmış...", done = false))
            throw InferenceError.Cancelled()
        }

        override fun stop() = Unit
        override fun isLoaded(): Boolean = loaded
        override fun modelInfo(): ModelInfo? = if (loaded) ModelInfo("cut", "/models/cut.gguf", "Cut") else null
    }

    // ---- wiring -------------------------------------------------------

    private fun repository(): CharalyRepository = JsonCharalyRepository(InMemoryCharalyStorage())

    private fun runtime(engine: InferenceEngine, repository: CharalyRepository = repository()) = CharalyRuntime(
        repository = repository,
        engine = engine,
    )

    private suspend fun CharalyRuntime.begin(id: String = "story-journey"): StoryInstance = startStory(
        journeyPack(),
        StoryCreationOptions(
            instanceId = StoryInstanceId(id),
            title = "İlk Yolculuk",
            focusCharacterId = alice,
            modelBinding = ModelBinding.from(
                installedModelId = "local-journey",
                modelDisplayName = "Journey Model",
                profile = ModelProfileLibrary.all.first(),
            ),
            nowEpochMs = 1_700_000_000_000L,
        ),
    )

    /** One turn to completion: every update it produced, and the state it ended in. */
    private suspend fun CharalyRuntime.turn(
        instance: StoryInstance,
        input: String,
        speaker: CharacterId? = null,
    ): Pair<List<GenerationUpdate>, StoryInstance> {
        val updates = mutableListOf<GenerationUpdate>()
        var latest = instance
        respond(instance, input, speaker).collect { update ->
            updates += update
            (update as? GenerationUpdate.Finished)?.instance?.let { latest = it }
        }
        return updates to latest
    }

    private fun List<GenerationUpdate>.applied() = filterIsInstance<GenerationUpdate.AppliedAction>()
    private fun List<GenerationUpdate>.rejected() = filterIsInstance<GenerationUpdate.RejectedAction>()
    private fun List<GenerationUpdate>.failures() = filterIsInstance<GenerationUpdate.Failed>()

    private fun StoryInstance.texts(): List<String> = conversation.entries.map { it.text }

    // ==================================================================
    // 1. Narrative-only turn
    // ==================================================================

    /**
     * Prose alone must leave the world exactly where it was.
     *
     * The turn still costs story time and produces scene memories (that is what a turn
     * IS), but nothing about the world's facts, relationships, positions or variables may
     * move without a validated action.
     */
    @Test
    fun `journey 1 - a narrative-only turn changes the transcript and nothing else`() = runTest {
        val engine = Scripted(listOf("Yağmur başladı; ikisi de sustu. Çay soğudu, hiçbir şey değişmedi."))
        val rt = runtime(engine)
        val instance = rt.begin()
        val before = instance

        val (updates, after) = rt.turn(instance, "Pencereyi kapat istersen.")

        assertTrue(updates.applied().isEmpty())
        assertTrue(updates.rejected().isEmpty())
        assertEquals(
            "Türkçe, UTF-8 bozulmadan gelmeli",
            "Yağmur başladı; ikisi de sustu. Çay soğudu, hiçbir şey değişmedi.",
            after.texts().last(),
        )
        assertEquals("the reply is prose, not protocol", after.texts().size, 2)
        after.texts().forEach { assertFalse("protocol leaked into \"$it\"", it.contains("<charaly:action")) }

        assertEquals("no relationship moved", before.worldState.relationships, after.worldState.relationships)
        assertEquals(
            "no character teleported",
            before.worldState.characters.mapValues { it.value.locationId },
            after.worldState.characters.mapValues { it.value.locationId },
        )
        assertEquals("no variable flipped", before.worldState.variables, after.worldState.variables)
        assertEquals("nobody learned anything", before.knowledge.knowledge, after.knowledge.knowledge)
        assertTrue("nothing left queued", after.eventQueue.size == 0)
    }

    // ==================================================================
    // 2. Valid action through the whole pipeline
    // ==================================================================

    /**
     * A tagged proposal parses, validates, applies through the event engine, persists -
     * and the NEXT turn's context is built from the changed world.
     */
    @Test
    fun `journey 2 - a valid action lands, persists, and reaches the next turn's context`() = runTest {
        val engine = Scripted(
            listOf(
                "Akşam güzeldi. <charaly:action type=\"relate\" character=\"$alice\" target=\"Bora\" " +
                    "value=\"trust:+10\" reason=\"güzel bir akşamdı\"/>",
                "Devam edelim.",
            ),
        )
        val rt = runtime(engine)
        val instance = rt.begin()

        val (updates1, after1) = rt.turn(instance, "Bugün nasıldın?")
        assertEquals("exactly one action applied", 1, updates1.applied().size)
        assertTrue(updates1.rejected().isEmpty())

        val relationship = after1.worldState.relationship(alice, bob)!!
        assertEquals("trust moved by exactly the proposed delta", 50, relationship.trust)
        assertEquals("persistence happened mid-loop", after1, rt.loadStory(instance.id))

        // The change is in the context the next turn builds - structurally, not as prose.
        val context = rt.previewContext(after1, alice, "Devam edelim.")!!
        assertEquals(
            "the next turn must see the new relationship",
            50,
            context.relationships.single { it.sourceId == alice && it.targetId == bob }.trust,
        )
        val (updates2, _) = rt.turn(after1, "Hatırlıyor musun?")
        assertEquals("the world does not re-apply the action", 0, updates2.applied().size)
        assertEquals(50, after1.worldState.relationship(alice, bob)!!.trust)
    }

    // ==================================================================
    // 3. Invalid action
    // ==================================================================

    /** An illegal move is refused by the ENGINE and the forbidden change is not acquired. */
    @Test
    fun `journey 3 - an illegal move is refused and the world never gains it`() = runTest {
        val engine = Scripted(
            listOf(
                "Kuleye kaçmalı! <charaly:action type=\"move\" character=\"$bob\" target=\"$tower\" " +
                    "reason=\"saklanmak için\"/>",
            ),
        )
        val rt = runtime(engine)
        val instance = rt.begin()
        val before = instance.characters.getValue(bob).locationId

        val (updates, after) = rt.turn(instance, "Ben geliyorum.")

        assertEquals("the proposal is refused with a diagnostic", 1, updates.rejected().size)
        assertTrue(
            "the reason must name the rule: ${updates.rejected().single().review.reason}",
            updates.rejected().single().review.reason.contains("illegal movement"),
        )
        assertTrue("nothing was applied", updates.applied().isEmpty())
        assertEquals("Bora never reached the tower", before, after.characters.getValue(bob).locationId)
        assertFalse("and he is not standing there", after.worldState.charactersAt(tower).isNotEmpty())
    }

    // ==================================================================
    // 4. Malformed model output
    // ==================================================================

    /**
     * Broken output: no unvalidated mutation, no crash, no protocol on screen - including
     * a tag the model never closed, which is protocol debris and not narration.
     */
    @Test
    fun `journey 4 - malformed model output mutates nothing and is handled safely`() = runTest {
        val broken = listOf(
            "<charaly:action type=\"move\" character=\"$bob\" target=\"$tower\"",
            "<charaly:action/>",
            "<charaly:action type=\"teleport\" character=\"$carol\" target=\"$hall\"/> sadece laf",
            "",
        )
        broken.forEachIndexed { index, reply ->
            val engine = Scripted(listOf(reply))
            val rt = runtime(engine)
            val instance = rt.begin("story-malformed-$index")
            val before = rt.loadStory(instance.id)!!

            val (updates, after) = rt.turn(instance, "Bir şey söyle.")

            assertTrue("case $index: no crash, no partial apply", updates.applied().isEmpty())
            val worldMoves =
                after.worldState.relationships != before.worldState.relationships ||
                    after.worldState.characters.mapValues { it.value.locationId } !=
                    before.worldState.characters.mapValues { it.value.locationId } ||
                    after.worldState.variables != before.worldState.variables ||
                    after.knowledge.knowledge != before.knowledge.knowledge
            assertFalse("case $index: malformed output must not move the world", worldMoves)
            after.texts().forEach {
                assertFalse("case $index: protocol debris rendered: \"$it\"", it.contains("<charaly:action"))
            }
        }
    }

    // ==================================================================
    // 5. Mixed output
    // ==================================================================

    /** Valid prose survives; the invalid proposal is rejected and reported. */
    @Test
    fun `journey 5 - mixed output keeps the prose and rejects only the bad proposal`() = runTest {
        val engine = Scripted(
            listOf(
                "“Seninle gurur duyuyorum.” <charaly:action type=\"relate\" character=\"$alice\" " +
                    "target=\"Bora\" value=\"affinity:+5\"/> " +
                "<charaly:action type=\"move\" character=\"$alice\" target=\"$tower\" reason=\"manidar\"/>",
            ),
        )
        val rt = runtime(engine)
        val instance = rt.begin()

        val (updates, after) = rt.turn(instance, "Teşekkür ederim.")

        assertEquals("the good proposal landed", 1, updates.applied().size)
        assertEquals("the bad one was refused and reported", 1, updates.rejected().size)
        assertTrue(
            "and the refusal says why: ${updates.rejected().single().review.reason}",
            updates.rejected().single().review.reason.isNotBlank(),
        )

        // Prose preserved, protocol gone.
        val reply = after.texts().last()
        assertTrue("the sentence survives: \"$reply\"", reply.contains("Seninle gurur duyuyorum."))
        assertFalse(reply.contains("<charaly:action"))

        // Exactly the good change happened. (Affinity's domain default is 50.)
        val relationship = after.worldState.relationship(alice, bob)!!
        assertEquals(55, relationship.affinity)
        assertEquals(40, relationship.trust)
        assertEquals("Ayşe stayed in the salon", hall, after.characters.getValue(alice).locationId)
    }

    // ==================================================================
    // 6. Knowledge boundary
    // ==================================================================

    /**
     * One character learns a fact; nobody else does - and each character's PROMPT carries
     * exactly their own knowledge, never another head's.
     */
    @Test
    fun `journey 6 - knowledge stays with the character it was granted to`() = runTest {
        val engine = Scripted(
            listOf(
                "Bora'ya fenerden bahsettim. " +
                    "<charaly:action type=\"discover\" character=\"$bob\" target=\"$openId\" reason=\"Ayşe anlattı\"/>",
                "Anladım.",
            ),
        )
        val rt = runtime(engine)
        val instance = rt.begin()

        val (updates, after) = rt.turn(instance, "Fener ne oldu?")
        assertEquals(1, updates.applied().size)

        assertTrue("Bora learned it", after.knowledge.knows(bob, openId))
        assertFalse("Cemre did not", after.knowledge.knows(carol, openId))
        assertEquals(
            "the grant reached exactly its addressee",
            setOf(bob),
            after.knowledge.knownBy(openId).toSet(),
        )

        // PROMPT-LEVEL boundary: his prompt has it, hers does not, and the secret Ayşe
        // holds reaches nobody at all.
        val bobPrompt = rt.previewContext(after, bob, "Anladım.")!!.systemPrompt()
        val carolPrompt = rt.previewContext(after, carol, "Bahçede ne var?")!!.systemPrompt()
        assertTrue("Bora's prompt carries what Bora knows", bobPrompt.contains(openFact.description))
        assertFalse("Cemre's prompt must not", carolPrompt.contains(openFact.description))
        assertFalse("and the secret is Ayşe's alone", bobPrompt.contains(secretFact.description))
        assertFalse("even Cemre's", carolPrompt.contains(secretFact.description))
        val aysePrompt = rt.previewContext(after, alice, "?")!!.systemPrompt()
        assertTrue("Ayşe keeps her own secret knowledge", aysePrompt.contains(secretFact.description))
    }

    // ==================================================================
    // 7. Relationship update survives reload
    // ==================================================================

    /** Through a REAL file-backed repository, across a restart. */
    @Test
    fun `journey 7 - a relationship update survives a real save and reload`() = runTest {
        val root = Files.createTempDirectory("charaly-journey7").toFile()
        try {
            val engine = Scripted(
                listOf(
                    "Güven arttı. <charaly:action type=\"relate\" character=\"$alice\" target=\"Bora\" " +
                        "value=\"trust:+15,tension:+5\" reason=\"açıklama yaptık\"/>",
                ),
            )
            val rt = runtime(engine, JsonCharalyRepository(FileCharalyStorage(root)))
            val instance = rt.begin()
            val (updates, after) = rt.turn(instance, "Konuşalım.")
            assertEquals(1, updates.applied().size)

            // Cold start: a new repository over the same tree.
            val reopened = JsonCharalyRepository(FileCharalyStorage(root)).getInstance(instance.id)!!
            assertEquals(55, reopened.worldState.relationship(alice, bob)!!.trust)
            assertEquals(5, reopened.worldState.relationship(alice, bob)!!.tension)
            assertEquals("the exchange is still there", after.texts(), reopened.texts())
        } finally {
            root.deleteRecursively()
        }
    }

    // ==================================================================
    // 8. Objectives and the event queue
    // ==================================================================

    /**
     * A refused payout must neither resolve its objective nor leave anything stuck: the
     * objective stays ACTIVE and retryable, and refused events never camp in the queue.
     */
    @Test
    fun `journey 8 - refusals neither resolve objectives nor clog the queue`() = runTest {
        val engine = Scripted(listOf("Kule hep kapalı kaldı."))
        val rt = runtime(engine)
        val instance = rt.begin()
        val (updates, afterTurn) = rt.turn(instance, "Git bakalım.")
        assertTrue("the narrative turn applied nothing", updates.applied().isEmpty())
        assertEquals("no refused event lingers", 0, afterTurn.eventQueue.size)

        // A scheduled event that is refused every time is dropped every time - it must
        // not sit in the queue waiting to be refused again on the next clock advance.
        repeat(3) { attempt ->
            val after = rt.scheduleAndProcess(afterTurn, dev.charaly.runtime.domain.events.CharacterMoved(alice, from = hall, to = tower))
            assertEquals("attempt $attempt: nothing left to reject again", 0, after.eventQueue.size)
            assertEquals("and Alice is still in the salon", hall, after.characters.getValue(alice).locationId)
        }

        // Now make the completion condition true and force the payout - which the engine
        // will refuse (the tower is unreachable). The objective must NOT resolve.
        val flagged = rt.scheduleAndProcess(afterTurn, WorldVariableSet("journey_flag", "yes"))
        val evaluated = rt.evaluateObjectives(flagged)
        assertEquals(
            "a refused payout must not resolve the objective",
            ObjectiveStatus.ACTIVE,
            evaluated.objective("obj-bayrak")?.status,
        )
        assertEquals("and Alice certainly never reached the tower", hall, evaluated.characters.getValue(alice).locationId)

        // Retriable rather than stuck: evaluating again behaves identically.
        val again = rt.evaluateObjectives(evaluated)
        assertEquals(ObjectiveStatus.ACTIVE, again.objective("obj-bayrak")?.status)
    }

    // ==================================================================
    // 9. Stop / cancellation
    // ==================================================================

    /**
     * A turn cut off mid-reply leaves NOTHING behind: the failed turn is not persisted at
     * all, so there is no half-applied mutation and no duplicated message when it is
     * re-run. (Fake inference reporting `InferenceError.Cancelled`, the exact error the
     * native engine raises when `stop()` interrupts a decode.)
     */
    @Test
    fun `journey 9 - a cancelled turn leaves no partial world and no duplicated message`() = runTest {
        val cut = runtime(Interrupted())
        val instance = cut.begin()
        val before = cut.loadStory(instance.id)!!

        val (updates, _) = cut.turn(instance, "Uzun bir hikâye anlat.")
        assertEquals(
            "the cancellation is reported, not swallowed",
            1,
            updates.failures().count { it.error is CharalyError.Cancelled },
        )

        val afterFailure = cut.loadStory(instance.id)!!
        assertEquals("nothing half-saved", before, afterFailure)
        assertTrue("no orphan user line", afterFailure.conversation.entries.isEmpty())

        // The retry (same input, healthy engine) is indistinguishable from a first try.
        val engine = Scripted(listOf("Tamam, anlatıyorum: bir zamanlar..."))
        val rt = runtime(engine)
        val (retryUpdates, retried) = rt.turn(afterFailure, "Uzun bir hikâye anlat.")
        assertTrue("the retry itself succeeds", retryUpdates.failures().isEmpty())
        assertEquals("one user line, one reply - never two", 2, retried.conversation.entries.size)
        assertEquals(1, retried.texts().count { it == "Uzun bir hikâye anlat." })
    }

    // ==================================================================
    // 10. Retry without duplication
    // ==================================================================

    /**
     * Retrying an interrupted turn duplicates nothing - and regenerating a FINISHED turn
     * (the transcript-only redo) must not leave the player's line standing twice.
     */
    @Test
    fun `journey 10 - retry and regeneration duplicate no message and no action`() = runTest {
        // (a) the interrupted turn re-run: its actions apply exactly once.
        val boom = runtime(Exploding())
        val instance = boom.begin()
        val before = boom.loadStory(instance.id)!!
        val (failed, _) = boom.turn(instance, "Söz veriyorum.")
        assertEquals(1, failed.failures().size)
        assertEquals("an interrupted turn applies nothing", before, boom.loadStory(instance.id)!!)

        val engine = Scripted(
            listOf(
                "Söz tutuldu. <charaly:action type=\"relate\" character=\"$alice\" target=\"Bora\" " +
                    "value=\"trust:+7\" reason=\"söz verdik\"/>",
                "Başka bir akşam. <charaly:action type=\"relate\" character=\"$alice\" target=\"Bora\" " +
                    "value=\"trust:+7\" reason=\"yeniden\"/>",
            ),
        )
        val rt = runtime(engine)
        val (retriedUpdates, retried) = rt.turn(before, "Söz veriyorum.")
        assertEquals(1, retriedUpdates.applied().size)
        assertEquals("exactly one player line, one reply", 2, retried.conversation.entries.size)
        assertEquals(47, retried.worldState.relationship(alice, bob)!!.trust)

        // (b) regeneration of a finished turn: the exchange is redone, not repeated.
        val (trimmed, input) = rt.prepareRegeneration(retried)
        assertEquals("the drafted line comes back for re-sending", "Söz veriyorum.", input)
        assertEquals("the whole exchange stepped aside", 0, trimmed.conversation.entries.size)

        val (regenUpdates, regenerated) = rt.turn(trimmed, input!!)
        assertEquals(1, regenerated.conversation.entries.count { it.text == "Söz veriyorum." })
        assertEquals("one exchange, not two", 2, regenerated.conversation.entries.size)
        assertEquals(1, regenUpdates.applied().size)
        // The regenerated proposal is a NEW world change on top of the world as it stands -
        // deliberate (the earlier turn's change already happened, see `dropFrom`'s contract),
        // and never a re-application of the old turn's events.
        assertEquals(54, regenerated.worldState.relationship(alice, bob)!!.trust)
    }

    // ==================================================================
    // 11. Save / reload consistency
    // ==================================================================

    /** One rich turn, reopened cold: conversation, knowledge, relationships, history agree. */
    @Test
    fun `journey 11 - after a rich turn the reload agrees with the world that was shown`() = runTest {
        val root = Files.createTempDirectory("charaly-journey11").toFile()
        try {
            val engine = Scripted(
                listOf(
                    "Bak, fener yine yanıyor. <charaly:action type=\"relate\" character=\"$alice\" " +
                        "target=\"Bora\" value=\"trust:+5,familiarity:+3\"/> " +
                        "<charaly:action type=\"discover\" character=\"$bob\" target=\"$openId\" " +
                        "reason=\"birlikte gördük\"/>",
                ),
            )
            val rt = runtime(engine, JsonCharalyRepository(FileCharalyStorage(root)))
            val instance = rt.begin()
            val (updates, shown) = rt.turn(instance, "Dinliyorum.")
            assertEquals(2, updates.applied().size)

            val reloaded = JsonCharalyRepository(FileCharalyStorage(root)).getInstance(instance.id)!!
            assertEquals("same conversation", shown.texts(), reloaded.texts())
            assertEquals(
                "same relationships",
                shown.worldState.relationships,
                reloaded.worldState.relationships,
            )
            assertEquals("same knowledge", shown.knowledge.knowledge, reloaded.knowledge.knowledge)
            assertTrue("the grant travelled", reloaded.knowledge.knows(bob, openId))
            assertEquals(
                "same event history",
                shown.worldState.eventLog,
                reloaded.worldState.eventLog,
            )
            assertEquals(
                "same change log",
                shown.worldState.changeLog.map { it.summary },
                reloaded.worldState.changeLog.map { it.summary },
            )
            assertEquals("same memories", shown.memories.current().size, reloaded.memories.current().size)
            assertEquals("same clock", shown.worldClock.now, reloaded.worldClock.now)
        } finally {
            root.deleteRecursively()
        }
    }

    // ==================================================================
    // 12. Multi-turn continuity
    // ==================================================================

    /** Three consecutive turns, each built from the persisted state of the ones before. */
    @Test
    fun `journey 12 - three turns share one continuous world and conversation`() = runTest {
        val engine = Scripted(
            listOf(
                "İlk cevap: dün akşamı hatırlıyorum.",
                "İkinci cevap. <charaly:action type=\"relate\" character=\"$alice\" target=\"Bora\" " +
                    "value=\"trust:+5\"/>",
                "Üçüncü cevap. <charaly:action type=\"discover\" character=\"$bob\" target=\"$openId\" " +
                    "reason=\"sohbetten\"/>",
            ),
        )
        val rt = runtime(engine)
        val instance = rt.begin()

        val (u1, t1) = rt.turn(instance, "Dün akşam ne oldu?")
        val (u2, t2) = rt.turn(t1, "Peki bugün?")
        val (u3, t3) = rt.turn(t2, "Son olarak fener?")
        assertEquals("six entries: three exchanges", 6, t3.conversation.entries.size)

        // Context continuity: the third prompt carries the words of both earlier turns.
        val prompt = engine.requests.last()
        val rendered = prompt.systemPrompt + prompt.messages.joinToString("\n") { it.content }
        listOf("Dün akşam ne oldu?", "İlk cevap", "Peki bugün?", "İkinci cevap").forEach {
            assertTrue("turn 3 must remember \"$it\"", rendered.contains(it))
        }

        // World continuity: what turn 2 changed and what turn 3 taught are both current.
        assertEquals(45, t3.worldState.relationship(alice, bob)!!.trust)
        assertTrue(t3.knowledge.knows(bob, openId))
        val bobPrompt = rt.previewContext(t3, bob, "Son bir şey:")!!.systemPrompt()
        assertTrue("the learned fact is in the next context", bobPrompt.contains(openFact.description))

        // And each update stream really was a separate, complete turn.
        listOf(u1, u2, u3).forEach { updates ->
            assertTrue("a healthy turn reports no failure", updates.failures().isEmpty())
            assertNotNull(updates.filterIsInstance<GenerationUpdate.Finished>().single().perf)
        }
    }

    // ==================================================================
    // Regression: streaming prose never shows protocol
    // ==================================================================

    /**
     * The player watches the reply arrive token by token. The action tags travel in that
     * stream, and what the stage renders is the accumulated prose - stripped of protocol at
     * every point, not only at commit.
     */
    @Test
    fun `regression - streamed prose is stripped of protocol exactly like the transcript`() = runTest {
        val engine = Scripted(
            listOf(
                "Gülümsüyor. <charaly:action type=\"relate\" character=\"$alice\" target=\"Bora\" " +
                    "value=\"trust:+1\"/> Sonra konuşur.",
            ),
        )
        val rt = runtime(engine)
        val instance = rt.begin()

        val streamed = StringBuilder()
        var accumulatedShown: String? = null
        val strippedChunks = mutableListOf<String>()
        rt.respond(instance, "Evet?").collect { update ->
            if (update is GenerationUpdate.Chunk) {
                streamed.append(update.text)
                accumulatedShown = update.accumulated
                // What the stage does with a chunk stream: strip before display.
                strippedChunks += dev.charaly.runtime.director.ProposedActionParser
                    .stripActions(update.accumulated)
            }
        }

        // The raw accumulation carries the protocol (that is what the parser needs)...
        assertTrue(accumulatedShown!!.contains("<charaly:action"))
        // ...and every point of the visible stream has it stripped away.
        strippedChunks.forEach { assertFalse("protocol visible mid-stream: \"$it\"", it.contains("<charaly:action")) }
        assertTrue(strippedChunks.last().contains("Gülümsüyor."))
        assertTrue(strippedChunks.last().contains("Sonra konuşur."))
    }

    /**
     * The same rule for a tag the model never closed: truncated protocol is debris and is
     * cut from what anyone sees - while parsing it proposes exactly nothing.
     */
    @Test
    fun `regression - an unterminated action tag is never rendered and never applied`() = runTest {
        val engine = Scripted(listOf("Yarım cümle. <charaly:action type=\"move\" character=\"$bob\""))
        val rt = runtime(engine)
        val instance = rt.begin()

        val (updates, after) = rt.turn(instance, "Devam?")
        assertTrue(updates.applied().isEmpty())
        assertTrue(updates.rejected().isEmpty())
        assertFalse(
            "no protocol debris in the transcript",
            after.texts().any { it.contains("<charaly:action") },
        )
        assertTrue(after.texts().last().contains("Yarım cümle."))
    }

    /** A tiny sanity check on the fixture itself, so the journeys cannot pass vacuously. */
    @Test
    fun `fixture - the journey world is wired the way the journeys assume`() {
        val pack = journeyPack()
        val definition = dev.charaly.runtime.domain.WorldDefinition(pack.characters, pack.locations)
        assertFalse("the tower must be unreachable", definition.isAdjacent(hall, tower))
        assertFalse("and from the garden", definition.isAdjacent(garden, tower))
        assertTrue(definition.isAdjacent(hall, garden))
    }
}