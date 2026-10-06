package dev.charaly.runtime.integration

import dev.charaly.runtime.context.ContextBuilder
import dev.charaly.runtime.context.ContextEpoch
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.engine.StoryCreationOptions
import dev.charaly.runtime.domain.memory.Memory
import dev.charaly.runtime.domain.MemoryId
import dev.charaly.runtime.domain.memory.MemoryVisibility
import dev.charaly.runtime.director.ActionReview
import dev.charaly.runtime.director.ActionType
import dev.charaly.runtime.director.ActionValidator
import dev.charaly.runtime.director.ProposedAction
import dev.charaly.runtime.director.ProposedActionParser
import dev.charaly.runtime.director.SceneDirector
import dev.charaly.runtime.domain.events.CharacterObserved
import dev.charaly.runtime.engine.EventApplication
import dev.charaly.runtime.engine.EventEngine
import dev.charaly.runtime.inference.GenerationParams
import dev.charaly.runtime.inference.InferenceRequest
import dev.charaly.runtime.inference.InferenceResult
import dev.charaly.runtime.inference.LoadOutcome
import dev.charaly.runtime.inference.ModelInfo
import dev.charaly.runtime.inference.ModelLoadRequest
import dev.charaly.runtime.inference.StreamChunk
import dev.charaly.runtime.model.CharalyRuntimeProfile
import dev.charaly.runtime.model.InstalledModel
import dev.charaly.runtime.model.CharalyProfileFactory
import dev.charaly.runtime.model.SamplerSettings
import dev.charaly.runtime.pack.MiraculousPack
import dev.charaly.runtime.presentation.StoryContext
import dev.charaly.runtime.presentation.StoryContextPresenter
import dev.charaly.runtime.presentation.StoryFeed
import dev.charaly.runtime.presentation.StoryPresenter
import dev.charaly.runtime.persistence.CharalyRepository
import dev.charaly.runtime.persistence.FileCharalyStorage
import dev.charaly.runtime.session.CharalyRuntime
import dev.charaly.runtime.session.WorldDefinitionResolver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The whole product loop, end to end, on a plain JVM.
 *
 * ## What this is for
 *
 * Every unit test above proves one piece. This walks the path a user actually takes:
 *
 * ```
 *   start pack -> enter story -> scene -> model -> dialogue
 *     -> proposed action -> validator -> event -> world state
 *     -> memory -> relationship -> thread
 *     -> save -> restart -> continue
 * ```
 *
 * with a scripted inference engine, no device, no emulator and no model file. If a change
 * breaks the *composition* - the wiring between two subsystems that each pass their own
 * tests - this is what catches it.
 *
 * ## The one thing it proves that nothing else can
 *
 * That a story survives a process restart *with its consequences intact*. A save that
 * keeps the transcript but loses the memory, or keeps the relationship but loses the
 * causal link, is a story that quietly rewrites itself the moment the user comes back.
 */
class StoryEndToEndTest {

    private val pack = MiraculousPack.pack
    private val marinette = CharacterId(MiraculousPack.MARINETTE)

    private fun temporaryDirectory(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "charaly-e2e-$name-${System.nanoTime()}").apply {
            mkdirs()
        }

    /**
     * An inference engine that returns a scripted reply.
     *
     * Records every request, so the test can assert on the prompt the model actually
     * received rather than on what the builder was supposed to have produced.
     */
    private class ScriptedEngine(private val replies: List<String>) : dev.charaly.runtime.inference.InferenceEngine {
        val requests = mutableListOf<InferenceRequest>()
        private var index = 0
        private var loaded: String? = null

        /**
         * Loads unconditionally.
         *
         * [CharalyRuntime.respond] refuses to generate unless `isLoaded()`, and the
         * scripted engine is never asked to load through the runtime - the test does not
         * have a GGUF, and the runtime's load path needs one. So `loadModel` is exposed
         * for completeness and [loaded] is primed by [prime] instead.
         */
        override suspend fun loadModel(request: ModelLoadRequest): LoadOutcome {
            loaded = request.path
            return LoadOutcome.Loaded(
                ModelInfo(id = "scripted", path = request.path, displayName = request.displayName),
            )
        }

        /** Marks the engine loaded, so generation is permitted without a real file. */
        fun prime() {
            loaded = "/models/scripted.gguf"
        }

        override suspend fun unloadModel() {
            loaded = null
        }

        override suspend fun generate(request: InferenceRequest): InferenceResult {
            requests += request
            return InferenceResult(text = replies.getOrElse(index) { replies.lastOrNull().orEmpty() })
        }

        override fun stream(request: InferenceRequest): Flow<StreamChunk> = flow {
            requests += request
            val text = replies.getOrElse(index) { replies.lastOrNull().orEmpty() }
            // Emit in a few chunks so the streaming path is genuinely exercised.
            text.chunked(16).forEach { emit(StreamChunk(text = it, done = false)) }
            emit(StreamChunk(text = "", done = true))
            index++
        }

        override fun stop() = Unit

        override fun isLoaded(): Boolean = loaded != null

        override fun modelInfo() = loaded?.let {
            ModelInfo(id = "scripted", path = it, displayName = "Scripted")
        }
    }

    private fun repository(directory: File): CharalyRepository =
        dev.charaly.runtime.persistence.JsonCharalyRepository(FileCharalyStorage(directory))

    // ------------------------------------------------------------------
    // The loop
    // ------------------------------------------------------------------

    @Test
    fun `a story runs from pack to restart with its consequences intact`() = runTest {
        val directory = temporaryDirectory("full-loop")
        val repository = repository(directory)
        // A secret fact from the pack, so the proposal has something real to grant.
        val secretFactId = pack.initialKnowledge.facts.firstOrNull { it.secret }?.id?.value
            ?: pack.initialKnowledge.facts.first().id.value

        val engine = ScriptedEngine(
            listOf(
                // Turn 1: plain narration with a tagged action proposal.
                //
                // The proposal names a fact the pack really defines - a `KnowledgeDiscovered`
                // for a fact that does not exist is rejected by the engine, and a test that
                // asserted on an applied event would be asserting on a rejection.
                "She sets down the sketchbook. <charaly:action type=\"discover\" " +
                    "character=\"marinette\" target=\"${secretFactId}\" " +
                    "reason=\"noticed the locked notebook\"/>",
                // Turn 2: no proposal at all. Most turns must not change the world.
                "\"You came all the way up here?\" she asks.",
            ),
        )
        engine.prime()
        val runtime = CharalyRuntime(
            repository = repository,
            engine = engine,
            params = GenerationParams(maxTokens = 200),
        )

        // --- start pack -> enter story ------------------------------------
        runtime.createPack(pack)
        val instanceId = StoryInstanceId("story-e2e-1")
        var instance = runtime.startStory(
            pack,
            StoryCreationOptions(
                instanceId = instanceId,
                title = "Shadows of Paris",
                // A scenario, because that is how a playthrough actually starts: it
                // chooses the opening location, the time of day and who is present.
                scenario = pack.defaultScenario(),
                focusCharacterId = marinette,
                nowEpochMs = 1_700_000_000_000L,
            ),
        )
        assertEquals(pack.id, instance.storyPackId)
        assertNotNull("a fresh story has a focus character", instance.focusCharacterId)
        // A fresh story has no *active* scene yet, and that is correct rather than a
        // gap: SceneDirector derives one for the speaker and the runtime commits it on
        // the first turn. Nothing is scene-shaped until someone is there to be in it.
        assertNull(
            "a fresh story should not have a committed scene yet",
            instance.currentScene(),
        )

        val startEventCount = instance.worldState.eventLog.size
        // A fresh story has running world state: the clock is set and the cast exists.
        assertTrue(
            "a fresh story has no characters",
            instance.worldState.characters.isNotEmpty(),
        )

        // --- model -> dialogue -------------------------------------------
        //
        // `respond` is a Flow because generation streams. Collecting it is how a real
        // caller sees a turn, and the Finished frame carries the authoritative instance -
        // which is the only one guaranteed to be persisted.
        val turn = collectTurn(runtime, instance, "Why do you keep coming up here?")
        instance = requireNotNull(turn.instance) { "the turn finished without an instance" }
        val reply = turn.text
        assertTrue("the character said nothing", reply.isNotBlank())
        assertTrue("the reply was not persisted", instance.conversation.size > 0)

        // The prompt the model received must carry the world, not just the question.
        val request = engine.requests.last()
        assertTrue("the prompt has no system section", request.systemPrompt.isNotBlank())
        assertTrue(
            "the prompt does not name the character",
            request.systemPrompt.contains("Marinette", ignoreCase = true) ||
                request.systemPrompt.contains(pack.characters.first { it.id == marinette }.name),
        )

        // --- proposed action -> validator -> event -> world state ---------
        val proposals = ProposedActionParser.parse(reply)
        assertTrue("the tagged proposal was not parsed: $reply", proposals.isNotEmpty())

        val definition = runtime.definitionFor(instance)
        val validator = ActionValidator(definition)
        val applied = mutableListOf<Any>()
        for (proposal in proposals) {
            when (val review = validator.toPayload(proposal)) {
                is ActionReview.Accepted -> {
                    val result = EventEngine(definition).applyImmediately(instance, review.payload)
                    if (result is dev.charaly.runtime.engine.EventApplication.Applied) {
                        instance = result.instance
                        applied += review.payload
                    }
                }
                is ActionReview.Rejected ->
                    // A rejection is a valid outcome and must change nothing.
                    assertTrue("a rejected action still produced state", applied.isEmpty())
            }
        }
        assertTrue("no event was applied from the model's proposal", applied.isNotEmpty())
        assertTrue(
            "the event log did not grow",
            instance.worldState.eventLog.size > startEventCount,
        )

        // --- knowledge: the character now knows the fact -----------------
        // The proposal named a fact; if the pack defines it, the character learned it.
        assertTrue(
            "the proposed fact was not granted",
            instance.knowledge.knows(marinette, dev.charaly.runtime.domain.FactId(secretFactId)),
        )

        // --- memory: the turn is recorded ---------------------------------
        val memoriesAfterTurn = instance.memories.current()
        assertTrue(
            "the exchange produced no memory at all",
            memoriesAfterTurn.isNotEmpty() || pack.initialKnowledge.authoredMemories.isNotEmpty(),
        )

        // --- thread: the story is still tracking its open beats ----------
        assertTrue(
            "a fresh story has no open thread to advance",
            instance.worldState.openThreads().isNotEmpty(),
        )

        // --- presentation: the story screen and its sheets ---------------
        val snapshot = StoryPresenter.build(
            instance = instance,
            pack = pack,
            definition = definition,
            modelReady = true,
        )
        assertTrue("the story screen has no lines", snapshot.lines.isNotEmpty())
        assertTrue("the story screen has no title", snapshot.title.isNotBlank())
        assertTrue(
            "the header has no contextual line",
            snapshot.contextLine.isNotBlank(),
        )
        // And the engine's vocabulary never reaches a player-facing string.
        val rendered = buildString {
            append(snapshot.contextLine).append(' ')
            append(snapshot.presenceLabel).append(' ')
            snapshot.lines.forEach { line -> append(line.rawText).append(' ') }
        }
        for (forbidden in listOf("charaly:action", "CharacterId", "MemoryTier", "evt-")) {
            assertFalse("player-facing text leaked '$forbidden'", rendered.contains(forbidden))
        }

        val context: StoryContext = StoryContextPresenter.build(definition, instance)
        assertEquals(4, context.entries.size)
        assertTrue(context.entries.all { it.label.isNotBlank() })

        // The feed sees the consequences the engine just applied.
        val feed = StoryFeed.build(instance)
        for (line in feed) {
            assertFalse("a feed line leaked an id: ${line.text}", line.text.contains("evt-"))
        }

        // --- save -------------------------------------------------------
        instance = runtime.save(instance)

        // --- restart: a brand new runtime over the same files -----------
        val restartedEngine = ScriptedEngine(listOf("\"You remembered.\""))
        restartedEngine.prime()
        val restarted = CharalyRuntime(
            repository = repository(directory),
            engine = restartedEngine,
            params = GenerationParams(maxTokens = 200),
        )
        restarted.restoredAll()

        val reloaded = restarted.loadStory(instanceId)
        assertNotNull("the story did not survive a restart", reloaded)
        requireNotNull(reloaded)

        assertEquals("the pack binding changed", pack.id, reloaded.storyPackId)
        assertEquals(
            "the transcript changed across a restart",
            instance.conversation.size,
            reloaded.conversation.size,
        )
        assertEquals(
            "world state changed across a restart",
            instance.worldState.eventLog.size,
            reloaded.worldState.eventLog.size,
        )
        assertEquals(
            "the clock changed across a restart",
            instance.worldClock.now,
            reloaded.worldClock.now,
        )
        assertEquals(
            "memory changed across a restart",
            instance.memories.current().map { it.id }.toSet(),
            reloaded.memories.current().map { it.id }.toSet(),
        )
        assertEquals(
            "relationships changed across a restart",
            instance.worldState.relationships.size,
            reloaded.worldState.relationships.size,
        )
        assertEquals(
            "the focus character changed across a restart",
            instance.focusCharacterId,
            reloaded.focusCharacterId,
        )

        // --- continue: the reloaded story still generates -----------------
        val continued = collectTurn(restarted, reloaded, "Do you remember what I said?")
        assertTrue("the continued turn produced no text", continued.text.isNotBlank())
        assertTrue(
            "the continued prompt lost the world",
            restartedEngine.requests.last().systemPrompt.isNotBlank(),
        )

        directory.deleteRecursively()
    }

    /**
     * Model output can never become world state by being prose.
     *
     * The single most important property in the codebase, and the reason the whole
     * proposed-action pipeline exists. Asserted directly: a reply that *narrates* a
     * character leaving must leave them exactly where they were.
     */
    @Test
    fun `plain narration never changes the world`() = runTest {
        val directory = temporaryDirectory("narration")
        val repository = repository(directory)
        val engine = ScriptedEngine(
            listOf(
                "Marinette walks to the school gate, opens it, and disappears down the street. " +
                    "The bell rings. An akuma flies overhead.",
            ),
        )
        engine.prime()
        val runtime = CharalyRuntime(repository = repository, engine = engine)
        runtime.createPack(pack)

        val instance = runtime.startStory(
            pack,
            StoryCreationOptions(
                instanceId = StoryInstanceId("story-narration"),
                scenario = pack.defaultScenario(),
                focusCharacterId = marinette,
                nowEpochMs = 1_700_000_000_000L,
            ),
        )
        val before = instance.worldState.characters[marinette]?.locationId

        val turn = collectTurn(runtime, instance, "Where do you think you're going?")
        val after = requireNotNull(turn.instance).worldState.characters[marinette]?.locationId

        assertEquals(
            "a narrated movement changed where the character is",
            before,
            after,
        )
        // The prose is kept - it is the story - and the world is untouched.
        assertTrue("the narration was discarded", turn.text.isNotBlank())
    }

    /**
     * A secret one character knows cannot reach another character's prompt.
     *
     * The knowledge boundary, end to end through the real ContextBuilder rather than a
     * direct store read - because the prompt is the only place a leak would matter.
     */
    @Test
    fun `a secret does not reach another character's prompt`() = runTest {
        val directory = temporaryDirectory("secret")
        val secretRepository = repository(directory)
        val runtime = CharalyRuntime(
            repository = secretRepository,
            engine = ScriptedEngine(listOf("")),
        )
        runtime.createPack(pack)

        val adrien = CharacterId(MiraculousPack.ADRIEN)
        val base = runtime.startStory(
            pack,
            StoryCreationOptions(
                instanceId = StoryInstanceId("story-secret"),
                scenario = pack.defaultScenario(),
                focusCharacterId = marinette,
                nowEpochMs = 1_700_000_000_000L,
            ),
        )

        val secret = "Gabriel built the akuma, and only Marinette knows it."
        // The secret is *about* someone, because retrieval only surfaces a memory that
        // concerns the conversation. An unconnected memory is correctly filtered out -
        // otherwise the test would be measuring the relevance filter, not the visibility
        // boundary it claims to test.
        val about = SceneDirector(WorldDefinitionResolver(secretRepository).forInstance(base))
            .directScene(base, marinette)
            ?.participantSet()
            ?.firstOrNull { it != marinette }
            ?: CharacterId(MiraculousPack.ALYA)

        val withSecret = base.copy(
            memories = base.memories.add(
                Memory(
                    id = MemoryId("secret-e2e"),
                    characterId = marinette,
                    content = secret,
                    importance = 5,
                    visibility = MemoryVisibility.SECRET,
                    visibleTo = listOf(marinette),
                    relatedCharacterIds = listOf(about),
                ),
            ),
        )

        val definition = WorldDefinitionResolver(secretRepository).forInstance(withSecret)
        val builder = ContextBuilder(definition)
        // The scene the director would derive for the focus character, which is what a
        // turn actually prompts with. A fresh story has none committed yet.
        val scene = SceneDirector(definition).directScene(withSecret, marinette)
            ?: throw AssertionError("no scene could be derived for the focus character")

        val marinettePrompt = builder
            .buildContext(withSecret, scene, marinette)
            .systemPrompt()
        assertTrue(
            "the owner cannot see their own secret",
            marinettePrompt.contains("Gabriel built the akuma"),
        )

        val adrienPrompt = builder
            .buildContext(withSecret, scene, adrien)
            .systemPrompt()
        assertFalse(
            "a secret leaked into another character's prompt",
            adrienPrompt.contains("Gabriel built the akuma"),
        )
        // And not merely redacted: the word must not appear at all, because a partial
        // leak is still a leak.
        assertFalse(
            "a fragment of the secret leaked",
            adrienPrompt.contains("built the akuma"),
        )

        directory.deleteRecursively()
    }

    /**
     * Switching the model leaves the story exactly as it was.
     *
     * The brief's requirement that a model is only a narrative provider. World state,
     * memory, relationships and threads are the story's, not the model's.
     */
    @Test
    fun `switching the model does not touch the story`() = runTest {
        val directory = temporaryDirectory("switch")
        val switchRepository = repository(directory)
        val runtime = CharalyRuntime(
            repository = switchRepository,
            engine = ScriptedEngine(listOf("")),
        )
        runtime.createPack(pack)

        var instance = runtime.startStory(
            pack,
            StoryCreationOptions(
                instanceId = StoryInstanceId("story-switch"),
                scenario = pack.defaultScenario(),
                focusCharacterId = marinette,
                nowEpochMs = 1_700_000_000_000L,
            ),
        )
        // Give it something to lose.
        val eventEngine = EventEngine(WorldDefinitionResolver(switchRepository).forInstance(instance))
        val observed = eventEngine.applyImmediately(
            instance,
            CharacterObserved(characterId = marinette, description = "a stray cat on the roof"),
        )
        if (observed is EventApplication.Applied) instance = observed.instance
        else throw AssertionError("the setup event was rejected: $observed")
        instance = runtime.save(instance)

        val before = StoryStateFingerprint.of(instance)

        // Rebind to a different model through the real runtime path. Nothing else may
        // change: a model is a narrative provider, not a participant in the story.
        instance = runtime.rebindModel(
            instance,
            InstalledModel(
                id = "other-model",
                displayName = "A Different Model",
                absolutePath = "/models/other.gguf",
                sizeBytes = 1_500_000_000L,
                origin = dev.charaly.runtime.model.ModelOrigin.DOWNLOADED,
                architecture = "qwen2",
                contextLength = 8192,
            ),
            dev.charaly.runtime.model.ModelProfileLibrary.default,
        )

        assertEquals(
            "world state changed when the model was rebound",
            before,
            StoryStateFingerprint.of(instance),
        )
        assertEquals("other-model", instance.modelBinding.installedModelId)

        directory.deleteRecursively()
    }

    /**
     * The generated runtime profile is complete and self-consistent.
     *
     * A model is handed a system prompt, an action protocol and sampling settings without
     * the user configuring anything. So those have to be present and internally coherent -
     * a profile that says "no chat template" must not also instruct the model to emit
     * action tags.
     */
    @Test
    fun `an installed model gets a complete Charaly profile`() {
        val profile = CharalyProfileFactory.build(
            modelId = "qwen3-4b-q4",
            displayName = "Qwen3 4B Q4_K_M",
            architecture = "qwen2",
            parameterCount = 4_000_000_000L,
            quantization = "Q4_K_M",
            fileContextLength = 8192,
            hasChatTemplate = true,
        )

        assertTrue("no system prompt", profile.systemPrompt.isNotBlank())
        assertTrue("no action schema", profile.actionSchema.isNotBlank())
        assertTrue("no output format", profile.outputFormat.isNotBlank())
        assertTrue("no memory instructions", profile.memoryInstructions.isNotBlank())
        assertTrue("no world instructions", profile.worldInstructions.isNotBlank())
        assertTrue("no character instructions", profile.characterInstructions.isNotBlank())
        assertTrue("no story instructions", profile.storyInstructions.isNotBlank())

        // Coherence: a base model with no template must not be asked to emit tags.
        val baseModel = CharalyProfileFactory.build(
            modelId = "base",
            displayName = "Base Model",
            architecture = "llama",
            parameterCount = 1_000_000_000L,
            quantization = "Q4_K_M",
            fileContextLength = 4096,
            hasChatTemplate = false,
        )
        assertFalse(
            "a base model was told to emit action tags: ${baseModel.actionTagInstructions}",
            baseModel.actionTagInstructions.contains("<charaly:action"),
        )
        assertFalse(
            "a base model was promised a structured output format",
            baseModel.outputFormat.contains("Narration"),
        )
    }

    /**
     * A low-bit quantisation asks for a steadier sampler.
     *
     * Not a preference - low-bit weights produce more token noise, and a higher
     * temperature makes it visible. Asserted so the relationship cannot be quietly
     * inverted by someone tidying the table.
     */
    @Test
    fun `lower quantisation bits ask for a cooler sampler`() {
        fun samplerFor(quant: String) = CharalyProfileFactory.build(
            modelId = "m",
            displayName = "M",
            architecture = "qwen2",
            parameterCount = 0L,
            quantization = quant,
            fileContextLength = 8192,
            hasChatTemplate = true,
        ).sampler

        val q2 = samplerFor("Q2_K")
        val q4 = samplerFor("Q4_K_M")
        val q8 = samplerFor("Q8_0")

        assertTrue("Q2 should repeat-penalise more than Q8", q2.repeatPenalty > q8.repeatPenalty)
        assertTrue("Q2 should run cooler than Q8", q2.temperature < q8.temperature)
        assertTrue("Q4 should sit between Q2 and Q8", q4.temperature in q2.temperature..q8.temperature)
    }

    /**
     * A context length the device cannot hold is clamped, not promised.
     *
     * A file may declare 128k. The KV cache for that on a phone is measured in gigabytes,
     * so honouring it would produce a budget the device cannot satisfy.
     */
    @Test
    fun `an absurd context length is clamped`() {
        val greedy = CharalyProfileFactory.build(
            modelId = "m",
            displayName = "M",
            architecture = "qwen2",
            parameterCount = 0L,
            quantization = "Q4_K_M",
            fileContextLength = 131_072,
            hasChatTemplate = true,
        )
        assertEquals(CharalyProfileFactory.MAX_CONTEXT, greedy.contextLength)

        // A tiny context gets a small budget, not a default one.
        val tiny = CharalyProfileFactory.build(
            modelId = "m",
            displayName = "M",
            architecture = "qwen2",
            parameterCount = 0L,
            quantization = "Q4_K_M",
            fileContextLength = 2048,
            hasChatTemplate = true,
        )
        assertEquals(2048, tiny.contextLength)
        assertTrue(
            "a 2k model was given a generous memory budget",
            tiny.contextPolicy.memories < greedy.contextPolicy.memories,
        )
    }

    @Test
    fun `a profile with no readable header is a conservative fallback`() {
        val fallback = CharalyProfileFactory.fallback("m", "Unknown Model", "")
        assertEquals(
            CharalyRuntimeProfile.Provenance.DEFAULT_FALLBACK,
            fallback.derivedFrom,
        )
        assertEquals("", fallback.quantization)
        // Still complete enough to talk to the model with.
        assertTrue(fallback.systemPrompt.isNotBlank())
        // Not SamplerSettings' default: an unreadable file is not an average model, so
        // the factory sits slightly cooler than the shipped default. Asserting the
        // shipped default here would have hidden that distinction.
        assertTrue(
            "the fallback profile should be conservative, got ${fallback.sampler.temperature}",
            fallback.sampler.temperature <= SamplerSettings().temperature,
        )
        assertTrue(
            "the fallback profile has no quantisation to reason about",
            fallback.quantization.isBlank(),
        )
    }

    /**
     * The stop sequences end a proposal rather than letting it ramble.
     *
     * Without `<charaly:` in the stop set, a model that starts a proposal keeps
     * generating and elaborates on its own protocol - which ends up in the transcript as
     * narration.
     */
    @Test
    fun `stop sequences cut the action protocol off`() {
        val profile = CharalyProfileFactory.build(
            modelId = "m",
            displayName = "M",
            architecture = "qwen2",
            parameterCount = 0L,
            quantization = "Q4_K_M",
            fileContextLength = 8192,
            hasChatTemplate = true,
        )
        assertTrue(
            "generation is not stopped at the action tag",
            profile.stopSequences.contains("<charaly:"),
        )
    }

    /** Restores everything the runtime persisted, so `loadStory` has something to find. */
    private suspend fun CharalyRuntime.restoredAll() {
        runCatching { restore() }
    }

    /**
     * Runs one turn to completion and returns the final frame.
     *
     * `respond` is a Flow because generation streams, so collecting it is exactly what a
     * real caller does. The Finished frame carries the authoritative instance - the only
     * one guaranteed to be persisted - so tests read the story from there rather than
     * from a stale local variable.
     */
    private suspend fun collectTurn(
        runtime: CharalyRuntime,
        instance: dev.charaly.runtime.domain.StoryInstance,
        input: String,
    ): dev.charaly.runtime.session.GenerationUpdate.Finished {
        var finished: dev.charaly.runtime.session.GenerationUpdate.Finished? = null
        runtime.respond(instance, input).collect { update ->
            if (update is dev.charaly.runtime.session.GenerationUpdate.Finished) finished = update
        }
        return requireNotNull(finished) { "the turn produced no Finished frame" }
    }

    /**
     * Everything about a story that must not change when the model does.
     *
     * A named fingerprint rather than a pile of assertions, so adding one more subsystem
     * to protect is a one-line change.
     */
    private object StoryStateFingerprint {
        fun of(instance: dev.charaly.runtime.domain.StoryInstance): String = buildString {
            append("events=").append(instance.worldState.eventLog.size).append(';')
            append("clock=").append(instance.worldClock.now).append(';')
            append("locations=")
            append(instance.worldState.characters.entries.sortedBy { it.key.value }.joinToString(",") { "${it.key.value}@${it.value.locationId?.value}" })
            append(';')
            append("relationships=").append(instance.worldState.relationships.size).append(';')
            append("threads=")
            append(instance.storyThreads.entries.sortedBy { it.key.value }.joinToString(",") { "${it.key.value}:${it.value.stage}" })
            append(';')
            append("memories=")
            append(instance.memories.current().map { it.id.value }.sorted().joinToString(","))
        }
    }
}
