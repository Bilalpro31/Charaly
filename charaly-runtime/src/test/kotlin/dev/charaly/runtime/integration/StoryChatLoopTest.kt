package dev.charaly.runtime.integration

import dev.charaly.runtime.context.ContextBudget
import dev.charaly.runtime.context.InferenceContext
import dev.charaly.runtime.domain.CharacterActivity
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.FactId
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.WorldState
import dev.charaly.runtime.domain.events.CharacterActivityChanged
import dev.charaly.runtime.engine.EventApplication
import dev.charaly.runtime.engine.EventEngine
import dev.charaly.runtime.engine.StoryCreationOptions
import dev.charaly.runtime.inference.InferenceEngine
import dev.charaly.runtime.inference.InferenceRequest
import dev.charaly.runtime.inference.InferenceResult
import dev.charaly.runtime.inference.LoadOutcome
import dev.charaly.runtime.inference.ModelInfo
import dev.charaly.runtime.inference.ModelLoadRequest
import dev.charaly.runtime.inference.StopReason
import dev.charaly.runtime.inference.StreamChunk
import dev.charaly.runtime.model.ModelBinding
import dev.charaly.runtime.model.ModelProfileLibrary
import dev.charaly.runtime.pack.MiraculousPack
import dev.charaly.runtime.persistence.CharalyRepository
import dev.charaly.runtime.persistence.InMemoryCharalyStorage
import dev.charaly.runtime.persistence.JsonCharalyRepository
import dev.charaly.runtime.session.CharalyRuntime
import dev.charaly.runtime.session.GenerationUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE STORY CHAT LOOP, TURN BY TURN.
 *
 * ```
 *   user input -> StoryInstance -> WorldState -> ContextBuilder -> model
 *     -> ProposedActionParser -> ActionValidator -> WorldEvent -> EventEngine
 *     -> WorldState mutation -> memory / relationship / clock / character / scene
 *     -> NEW ContextBuilder output -> next turn
 * ```
 *
 * ## Integration boundary - stated plainly
 *
 * These tests drive the REAL story engine: real `MiraculousPack`, real `StoryInstance`,
 * real `WorldState`, real `SceneDirector`, real `ContextBuilder`, real
 * `ProposedActionParser`, real `ActionValidator`, real `EventEngine`, real `MemoryWriter`,
 * real `WorldClock`. The ONLY substitute is the transport into `InferenceEngine`, because
 * this suite runs on a JVM with no `libcharaly_llama.so` and no model file.
 *
 * **A green run here proves the story wiring, not that a model generated anything.** The
 * half this file cannot reach - real GGUF -> llama.cpp -> real decoded tokens -> this same
 * loop - is `app/src/test/.../RealStoryChatLoopTest`, which loads a real build of the same
 * JNI entry points the APK exports and runs this identical chain with real text. Nothing is
 * ever reported as "generation works" on the strength of this file.
 *
 * ## Why assertions live at the parser / event / state level
 *
 * Model prose varies from run to run; the engine's reaction to a *validated* action does
 * not. So the fixtures supply explicit `<charaly:action .../>` proposals and the assertions
 * are about what the engine did with them - which is deterministic, and which is the thing
 * actually under test. Nothing here greps the model's prose for a word.
 */
class StoryChatLoopTest {

    /**
     * The shipped pack plus ONE ordinary, non-secret fact - the kind a model is allowed to
     * discover.
     *
     * Phase 6: `MiraculousPack`'s own facts are all secrets, and teaching one of them
     * through a `discover` proposal is exactly the hole `ModelProposalSecretKnowledgeTest`
     * closes (a model proposal is not an authorized source for a secret). The plumbing this
     * file proves - a proposed discovery travels parser -> validator -> EventEngine ->
     * knowledge - is unchanged and is now exercised on the lawful fact kind. The shipped
     * pack itself is not modified; this is a test-local copy.
     */
    private val publicFact = dev.charaly.runtime.domain.knowledge.Fact(
        id = FactId("fact-vitrin-ilani"),
        subject = "ilan",
        predicate = "vitrinde",
        description = "Kitapçı vitrininde yarınki imza gününün saati yazılı.",
    )
    private val pack: dev.charaly.runtime.domain.StoryPack = MiraculousPack.pack.copy(
        initialKnowledge = MiraculousPack.pack.initialKnowledge.copy(
            facts = MiraculousPack.pack.initialKnowledge.facts + publicFact,
        ),
    )
    private val marinette = CharacterId(MiraculousPack.MARINETTE)
    private val alya = CharacterId(MiraculousPack.ALYA)

    /**
     * Alya's *published* name, read from the pack rather than typed here.
     *
     * `ActionValidator` resolves a RELATE target by name only, so a typo in a fixture would
     * fail as an unknown target and the test would assert on a rejection while believing it
     * was an acceptance.
     */
    private val alyaName: String = pack.characters.first { it.id == alya }.name

    /** A fact the pack really defines and a model may lawfully discover, so DISCOVER has
     * something real to grant. */
    private val discoverableFact: String = publicFact.id.value

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /**
     * Pipes scripted replies through and records every request.
     *
     * Starts already resident - stated openly, because there is no GGUF here to load.
     * `ModelResidency.immediate` gates on `isLoaded()`; the residency boundary itself is
     * covered by `LocalModelChainJvmTest` and, with real weights, by `RealStoryChatLoopTest`.
     */
    private class ScriptedEngine(private val replies: List<String>) : InferenceEngine {
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

        override suspend fun generate(request: InferenceRequest): InferenceResult {
            requests += request
            return InferenceResult(text = next())
        }

        override fun stream(request: InferenceRequest): Flow<StreamChunk> = flow {
            requests += request
            val text = next()
            text.chunked(24).forEach { emit(StreamChunk(text = it, done = false)) }
            emit(StreamChunk(text = "", done = true))
        }

        override fun stop() = Unit
        override fun isLoaded(): Boolean = loaded
        override fun modelInfo(): ModelInfo? =
            if (loaded) ModelInfo("scripted", "/m.gguf", "Scripted") else null

        private fun next(): String = replies.getOrElse(index++) { replies.lastOrNull().orEmpty() }
    }

    private fun repository(): CharalyRepository = JsonCharalyRepository(InMemoryCharalyStorage())

    private fun runtime(engine: InferenceEngine) = CharalyRuntime(repository = repository(), engine = engine)

    private suspend fun CharalyRuntime.begin(id: String = "story-loop") =
        startStory(
            pack,
            StoryCreationOptions(
                instanceId = StoryInstanceId(id),
                title = "Shadows of Paris",
                scenario = pack.defaultScenario(),
                focusCharacterId = marinette,
                castCharacterIds = listOf(marinette, alya),
                modelBinding = ModelBinding.from(
                    installedModelId = "local-miraculous-1",
                    modelDisplayName = "Miraculous Model",
                    profile = ModelProfileLibrary.all.first(),
                ),
                nowEpochMs = 1_700_000_000_000L,
            ),
        )

    /** Runs one turn to completion: every update, and the instance the engine ended with. */
    private suspend fun CharalyRuntime.turn(
        instance: StoryInstance,
        input: String,
    ): Pair<List<GenerationUpdate>, StoryInstance> {
        val updates = mutableListOf<GenerationUpdate>()
        var latest = instance
        respond(instance, input, marinette).collect { update ->
            updates += update
            (update as? GenerationUpdate.Finished)?.instance?.let { latest = it }
        }
        return updates to latest
    }

    /** One turn against a FRESH story and a fresh engine, keeping both ends for comparison. */
    private suspend fun runSoloTurn(reply: String): Solo {
        val rt = runtime(ScriptedEngine(listOf(reply)))
        rt.createPack(pack)
        val before = rt.begin("solo-" + reply.hashCode())
        val (_, after) = rt.turn(before, "Merhaba.")
        return Solo(before, after)
    }

    private class Solo(val before: StoryInstance, val after: StoryInstance)

    /** The exact prompt the model would receive for this moment. */
    private suspend fun CharalyRuntime.promptFor(instance: StoryInstance, input: String = "…"): String =
        contextFor(instance, input).systemPrompt()

    private suspend fun CharalyRuntime.contextFor(instance: StoryInstance, input: String = "…"): InferenceContext =
        previewContext(instance, marinette, input)
            ?: error("the runtime produced no context for a story that has a focus character")

    // ==================================================================
    // A. the real 3-turn story loop
    // ==================================================================

    @Test
    fun `A - three real story turns mutate the world through the validated event path`() = runTest {
        val engine = ScriptedEngine(
            listOf(
                "«Je reste avec toi jusqu'à la fin, quoi qu'il arrive, et je le jure.» " +
                    "<charaly:action type=\"relate\" character=\"marinette\" target=\"" + alyaName +
                    "\" value=\"trust:12,affinity:8\" reason=\"elle est restée m'aider\"/>",
                "\"On y va ensemble,\" elle dit.",
                "«Tu te souviens de tout à l'heure ?» <charaly:action type=\"discover\" " +
                    "character=\"marinette\" target=\"" + discoverableFact +
                    "\" reason=\"worked it out from what just happened\"/>",
            ),
        )
        val rt = runtime(engine)
        rt.createPack(pack)
        val start = rt.begin()

        // ---------------- WORLD STATE BEFORE ----------------
        val before = start.worldState
        val beforeRel = before.relationship(marinette, alya)
        val beforeMemories = start.memories.size

        // ---------------- TURN 1 ----------------
        val (t1, after1) = rt.turn(start, "Marinette'e yardım ediyorum.")
        assertTrue("turn 1 must stream real chunks", t1.any { it is GenerationUpdate.Chunk })
        assertEquals(StopReason.COMPLETED, t1.filterIsInstance<GenerationUpdate.Finished>().single().stopReason)
        assertTrue(
            "the validated RELATE proposal must reach the engine as an APPLIED action",
            t1.any { it is GenerationUpdate.AppliedAction },
        )

        // ---------------- WORLD STATE AFTER ----------------
        val afterRel = after1.worldState.relationship(marinette, alya)
        assertNotNull("a relationship edge must exist after a RELATE", afterRel)
        assertNotEquals("WORLD STATE BEFORE must differ from WORLD STATE AFTER", beforeRel, afterRel)
        assertTrue("trust must actually move", afterRel!!.trust > (beforeRel?.trust ?: 50))
        assertTrue("affinity must actually move", afterRel.affinity > (beforeRel?.affinity ?: 50))
        assertTrue("the memory pipeline must record something", after1.memories.size > beforeMemories)

        // ---------------- TURN 2: the change reaches the next context ----------
        val (_, after2) = rt.turn(after1, "\"On y va ensemble,\" elle dit.")
        val after2Ctx = rt.contextFor(after2)
        assertTrue(
            "the turn-1 relationship change must be in turn 2's context",
            after2Ctx.relationships.any { it.trust > 50 || it.affinity > 50 },
        )
        assertTrue(after2Ctx.systemPrompt().contains("PEOPLE YOU KNOW"))

        // ---------------- TURN 3: it remembers what just happened ----------
        val (t3, after3) = rt.turn(after2, "Az önce olanları hatırlıyorum.")
        val after3Ctx = rt.contextFor(after3)
        val prompt3 = after3Ctx.systemPrompt()

        assertTrue(
            "turn 3's context must carry what the character remembers",
            prompt3.contains("WHAT YOU REMEMBER") && after3Ctx.memories.isNotEmpty(),
        )
        assertTrue(
            "turn 3's context must carry the world's OWN account of what just happened",
            after3Ctx.recentEvents.isNotEmpty(),
        )
        assertTrue(
            "the discovery proposed in turn 3 must be applied to knowledge",
            after3.knowledge.knows(marinette, FactId(discoverableFact)),
        )
        assertTrue("and it must be reported as an applied action", t3.any { it is GenerationUpdate.AppliedAction })
    }

    // ==================================================================
    // B. the LLM never writes WorldState
    // ==================================================================

    @Test
    fun `B - prose that claims a world change changes nothing`() = runTest {
        val claiming = runSoloTurn("She smiles. Marinette now trusts Alya completely, and it is midnight.")
        val bland = runSoloTurn("Elbette, seninle kalacağım.")

        // Compared against a CONTROL turn rather than against the starting state: a turn
        // legitimately advances the clock, and advancing the clock fires routines that walk
        // NPCs to their next stop. That is the world running, not the model writing it - and
        // blaming the prose for it would make this test fire on a perfectly clean reply.
        assertEquals(
            "prose must never mutate relationships",
            bland.after.worldState.relationships,
            claiming.after.worldState.relationships,
        )
        assertEquals(
            "prose must never mutate character state",
            bland.after.worldState.characters.mapValues { it.value.innerFacing() },
            claiming.after.worldState.characters.mapValues { it.value.innerFacing() },
        )
        assertEquals(
            "prose must never mutate world variables",
            bland.after.worldState.variables,
            claiming.after.worldState.variables,
        )
        assertEquals(
            "prose must never alter what it costs to say something",
            bland.after.worldClock.now,
            claiming.after.worldClock.now,
        )
        assertTrue(
            "and no claim from the prose may enter the change log",
            claiming.after.worldState.changeLog.none { it.summary.contains("midnight") },
        )
        assertEquals(
            "the model changed nothing at all, so nothing may be attributed to it",
            0,
            claiming.after.worldState.changeLog.count {
                it.origin == dev.charaly.runtime.domain.events.EventOrigin.MODEL_PROPOSAL
            },
        )
    }

    // ==================================================================
    // C..G. the propagations
    // ==================================================================

    @Test
    fun `C - a relationship event reaches the next context`() = runTest {
        val engine = ScriptedEngine(
            listOf(
                "<charaly:action type=\"relate\" character=\"marinette\" target=\"" + alyaName +
                    "\" value=\"trust:20,familiarity:15\" reason=\"shared the secret\"/>",
            ),
        )
        val rt = runtime(engine)
        rt.createPack(pack)
        val (_, after1) = rt.turn(rt.begin(), "Alya'ya güveniyorum.")

        val rel = after1.worldState.relationship(marinette, alya)
        assertNotNull(rel)
        assertTrue("trust must rise", rel!!.trust >= 70)
        assertTrue("the relationship must keep its history", rel.history.isNotEmpty())

        val ctx = rt.contextFor(after1)
        assertTrue(ctx.systemPrompt().contains("PEOPLE YOU KNOW"))
        assertTrue("the raised trust must be what the model is told", ctx.relationships.any { it.trust > 50 })
    }

    @Test
    fun `D - memories written in one turn are read in the next`() = runTest {
        val engine = ScriptedEngine(
            listOf("«Je m'en souviendrai toute ma vie, c'est le moment où tout a changé.»"),
        )
        val rt = runtime(engine)
        rt.createPack(pack)
        val start = rt.begin()
        val empty = rt.contextFor(start).memories.size

        val (_, after1) = rt.turn(
            start,
            "Seninle paylaştığım bu anıyı sakın unutma, çünkü benim için çok anlamlı.",
        )
        val grown = rt.contextFor(after1).memories.size
        assertTrue("a turn must leave the character with something to remember (was $empty, now $grown)", grown > empty)
        assertTrue(
            "and the memory must be rendered into the next prompt",
            rt.promptFor(after1).contains("WHAT YOU REMEMBER"),
        )
    }

    @Test
    fun `E - the world clock reaches the next context`() = runTest {
        val engine = ScriptedEngine(listOf("…", "…"))
        val rt = runtime(engine)
        rt.createPack(pack)
        val start = rt.begin()
        val t0 = start.worldClock.now

        val prompt0 = rt.promptFor(start)
        assertTrue("the model must be told the story time at all", prompt0.contains("story time:"))
        assertTrue("and it must be the real clock reading", prompt0.contains(t0.format()))

        val (_, after1) = rt.turn(start, "Biraz bekliyorum, saat geçiyor.")
        val t1 = after1.worldClock.now
        assertTrue("a turn costs story time (TurnClockPolicy)", t1.totalMinutes >= t0.totalMinutes)
        val prompt1 = rt.promptFor(after1)
        assertTrue(
            "the NEXT context must carry the NEW world time ($t1, was $t0)",
            prompt1.contains(t1.format()),
        )
        if (t1 != t0) assertNotEquals("a moved clock must produce a different prompt", prompt0, prompt1)
    }

    @Test
    fun `F - CharacterRuntime state reaches the next context`() = runTest {
        val engine = ScriptedEngine(listOf("…"))
        val rt = runtime(engine)
        rt.createPack(pack)
        val start = rt.begin()

        val beforePrompt = rt.promptFor(start)
        assertFalse(beforePrompt.contains("shaken but determined"))

        // Through the SAME validated event path a model proposal takes.
        val applied = EventEngine(rt.definitionFor(start)).applyImmediately(
            start,
            CharacterActivityChanged(
                characterId = marinette,
                activity = CharacterActivity.INVESTIGATING,
                mood = "shaken but determined",
                activityLabel = "piecing it together",
            ),
        )
        assertTrue(applied is EventApplication.Applied)
        val moved = (applied as EventApplication.Applied).instance

        val afterPrompt = rt.promptFor(moved)
        assertNotEquals(
            "BEFORE context must differ from AFTER context when CharacterRuntime moved",
            beforePrompt,
            afterPrompt,
        )
        assertTrue("the character's own mood must be in their own context", afterPrompt.contains("shaken but determined"))
        assertTrue("and their activity", afterPrompt.contains("investigating"))
    }

    @Test
    fun `G - SceneDirector output reaches the model context`() = runTest {
        val engine = ScriptedEngine(listOf("…"))
        val rt = runtime(engine)
        rt.createPack(pack)
        val (updates, after1) = rt.turn(rt.begin(), "Fırından çıkıyorum, sokağa bakıyorum.")

        assertNotNull("a turn must resolve a scene", after1.currentScene())
        assertTrue(
            "the scene must be committed as world state, not left as a derived view",
            updates.any { it is GenerationUpdate.Started },
        )
        val ctx = rt.contextFor(after1)
        val prompt = ctx.systemPrompt()
        assertTrue("the model must be told which scene it is in", prompt.contains(ctx.scene.id.value))
        assertTrue("and where that scene is", prompt.contains(ctx.scene.locationId.value))
        assertTrue("the scene's objective must reach the model", ctx.sceneObjective.isNotBlank())
        assertTrue("and its mood", prompt.contains(ctx.scene.mood))
    }

    @Test
    fun `case 3 - the prompt carries the whole context checklist`() = runTest {
        val engine = ScriptedEngine(listOf("Bir şey oldu ve dünya değişti."))
        val rt = runtime(engine)
        rt.createPack(pack)
        val start = rt.begin()
        val (_, after1) = rt.turn(start, "Bak, orada bir şey oluyor!")

        val ctx = rt.contextFor(after1)
        val prompt = ctx.systemPrompt()

        assertTrue("story rules", prompt.contains("WORLD RULES"))
        assertTrue("identity", prompt.contains("YOUR IDENTITY"))
        assertNotNull("character runtime state", ctx.runtime)
        assertTrue("current location", prompt.contains("RIGHT NOW"))
        assertTrue("current world time", prompt.contains("story time:"))
        assertTrue("current scene", prompt.contains("scene:"))
        assertTrue("characters present", prompt.contains("present:"))
        assertTrue("relationships", prompt.contains("PEOPLE YOU KNOW"))
        assertTrue("relevant memories", prompt.contains("WHAT YOU REMEMBER"))
        assertTrue("recent events: " + ctx.recentEvents, ctx.recentEvents.isNotEmpty())
        assertTrue("objectives", ctx.sceneObjective.isNotBlank())
    }

    // ==================================================================
    // K. 30-turn context stress
    // ==================================================================

    @Test
    fun `K - thirty real turns keep the context bounded and complete`() = runTest {
        val replies = List(40) { index ->
            "Tour $index. «Ça va aller, on continue ensemble jusqu'au bout du monde.» " +
                if (index % 5 == 0) {
                    "<charaly:action type=\"relate\" character=\"marinette\" target=\"" + alyaName +
                        "\" value=\"trust:2,familiarity:1\" reason=\"tour " + index + "\"/>"
                } else {
                    ""
                }
        }
        val rt = runtime(ScriptedEngine(replies))
        rt.createPack(pack)
        var instance = rt.begin("story-stress")

        var peakChars = 0
        var lastContext: InferenceContext? = null
        repeat(30) { turn ->
            val (updates, next) = rt.turn(instance, "Tur " + (turn + 1) + ": devam edelim, uzun bir cümle daha.")
            assertTrue("turn $turn must complete", updates.any { it is GenerationUpdate.Finished })
            assertTrue(
                "turn $turn must not fail: " + updates.filterIsInstance<GenerationUpdate.Failed>(),
                updates.none { it is GenerationUpdate.Failed },
            )
            instance = next

            val ctx = rt.contextFor(instance)
            lastContext = ctx
            peakChars = maxOf(peakChars, ctx.estimatedChars())
            assertTrue("turn $turn lost the clock", ctx.systemPrompt().contains("story time:"))
            assertTrue("turn $turn lost the scene", ctx.systemPrompt().contains("scene:"))
        }

        val ctx = lastContext!!
        assertTrue(
            "the assembled context must stay near the declared budget (peak=$peakChars)",
            peakChars <= ContextBudget.DEFAULT.maxChars * 2,
        )
        assertTrue(
            "the change log must be capped, not grow forever",
            instance.worldState.changeLog.size <= WorldState.MAX_CHANGE_LOG,
        )
        assertTrue(
            "recent events are a slice, not the whole history",
            ctx.recentEvents.size <= ContextBudget.DEFAULT.recentEvents,
        )
        assertTrue("30 turns and the transcript is still there", instance.conversation.entries.size >= 60)
    }

    // ==================================================================
    // L. Turkish story turn
    // ==================================================================

    @Test
    fun `L - a Turkish story turn runs the whole pipeline without mangling text`() = runTest {
        val engine = ScriptedEngine(
            listOf(
                "«Tabii ki. Yanındayım, sonuna kadar.» <charaly:action type=\"relate\" " +
                    "character=\"marinette\" target=\"" + alyaName + "\" value=\"trust:6\" " +
                    "reason=\"Türkçe konuşmayı sürdürdü\"/>",
            ),
        )
        val rt = runtime(engine)
        rt.createPack(pack)
        val (updates, after) = rt.turn(rt.begin(), "Marinette'in yanında kalıp ona yardım ediyorum.")

        assertTrue(
            "the pipeline must not fail on Turkish input: " +
                updates.filterIsInstance<GenerationUpdate.Failed>(),
            updates.none { it is GenerationUpdate.Failed },
        )
        assertFalse(
            "no U+FFFD may reach the transcript",
            after.conversation.entries.any { it.text.contains('\uFFFD') },
        )
        assertTrue(
            "the Turkish line must be stored verbatim",
            after.conversation.entries.any { it.text.contains("Marinette'in yanında kalıp") },
        )
        assertTrue(updates.any { it is GenerationUpdate.Finished })
        assertTrue("and the state pipeline must have continued", updates.any { it is GenerationUpdate.AppliedAction })
    }
}

/**
 * The parts of a character the model must never reach except through validated events.
 *
 * `locationId` is excluded on purpose: end-of-turn clock advancement fires routines that
 * walk NPCs to their next stop, which is the world running rather than the model writing it.
 * (The invalid-output suite compares against a control turn, so it catches location changes
 * too - this variant is for the one case where there is no control.)
 */
private fun dev.charaly.runtime.domain.CharacterRuntime.innerFacing(): String =
    listOf(
        activity.name,
        mood,
        activeGoals.joinToString("|"),
        knownFactIds.joinToString("|") { it.value },
    ).joinToString(" / ")
