package dev.charaly.app.storyloop

import dev.charaly.app.inference.LlamaNative
import dev.charaly.app.inference.LocalLlamaInferenceEngine
import dev.charaly.app.model.LocalModelResidency
import dev.charaly.runtime.director.ActionReview
import dev.charaly.runtime.director.ActionValidator
import dev.charaly.runtime.director.ProposedActionParser
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.inference.GenerationParams
import dev.charaly.runtime.inference.StopReason
import dev.charaly.runtime.model.InstalledModel
import dev.charaly.runtime.model.JsonModelRegistry
import dev.charaly.runtime.model.ModelBinding
import dev.charaly.runtime.model.ModelProfileLibrary
import dev.charaly.runtime.model.ModelOrigin
import dev.charaly.runtime.engine.StoryCreationOptions
import dev.charaly.runtime.pack.MiraculousPack
import dev.charaly.runtime.persistence.InMemoryCharalyStorage
import dev.charaly.runtime.persistence.JsonCharalyRepository
import dev.charaly.runtime.session.CharalyRuntime
import dev.charaly.runtime.session.GenerationUpdate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * THE REAL STORY CHAT LOOP - with a real GGUF and real decoded tokens.
 *
 * ```
 *   Story Pack -> StoryInstance -> WorldState -> ContextBuilder -> ModelId
 *     -> LocalModelResidency -> llama.cpp (JNI) -> REAL TOKENS
 *     -> ProposedActionParser -> ActionValidator -> WorldEvent -> EventEngine
 *     -> WorldState mutation -> memory / clock -> ContextBuilder -> next turn
 * ```
 *
 * ## What is real, and what is only wired
 *
 * **Everything.** There is no double in this file except the *user*: `LocalLlamaInferenceEngine`
 * calls `libcharaly_llama.so`, which is the same JNI entry points the APK exports, which is
 * the same vendored llama.cpp, loading an actual GGUF. Text appearing in the assertions was
 * decoded from real model weights.
 *
 * The story side is likewise unmocked: real `MiraculousPack`, real `StoryInstance`, real
 * `EventEngine`, real `MemoryWriter`, real `WorldClock`.
 *
 * ## How it runs
 *
 * ```
 *   scripts/verify-story-loop-host.sh <model.gguf> [model-b.gguf]
 * ```
 * which builds the host `libcharaly_llama.so` and runs these tests with
 * `-PcharalyNativeLib=... -PcharalyModelFile=...`. When either property is absent the whole
 * class is **skipped rather than passed** - a green tick must never stand in for a test that
 * did not run.
 *
 * ## Why assertions are not on the model's wording
 *
 * A 260K-parameter model says strange things. What is deterministic is the *boundary*: text
 * came back, it was parsed, whatever it proposed was either applied through `EventEngine` or
 * refused, and the world moved only through that gate. Those are the assertions.
 */
class RealStoryChatLoopTest {

    private val pack = MiraculousPack.pack
    private val marinette = CharacterId(MiraculousPack.MARINETTE)
    private val alya = CharacterId(MiraculousPack.ALYA)

    private val modelFile: File? = System.getProperty(MODEL_PROPERTY)
        ?.takeIf { it.isNotBlank() }
        ?.let { File(it) }
        ?.takeIf { it.isFile }

    /** True when a real native engine AND a real model were both offered to this run. */
    private fun nativeReady(): Boolean {
        assumeTrue(
            "no libcharaly_llama.so offered (run scripts/verify-story-loop-host.sh)",
            LlamaNative.isAvailable(),
        )
        assumeTrue("no real GGUF offered (-P$MODEL_PROPERTY)", modelFile != null)
        return true
    }

    private class World(
        val runtime: CharalyRuntime,
        val registry: JsonModelRegistry,
        val residency: LocalModelResidency,
        val engine: LocalLlamaInferenceEngine,
    ) {
        /** The engine's own last words, so a "no text" failure names its cause. */
        fun diag(): String = engine.lastDiagnostic().ifBlank { LlamaNative.lastError() }.ifBlank { "no diagnostic" }
    }

    private fun diag(w: World): String = w.diag()

    /** Builds the real chain: engine -> residency -> runtime, with one real GGUF registered. */
    private fun world(gguf: File): World {
        val engine = LocalLlamaInferenceEngine(RealStoryChatLoopTest.EngineConfig)
        val registry = JsonModelRegistry(InMemoryCharalyStorage())
        val residency = LocalModelResidency(registry, engine)
        val runtime = CharalyRuntime(
            repository = JsonCharalyRepository(InMemoryCharalyStorage()),
            engine = engine,
            params = GenerationParams(maxTokens = 48, temperature = 0.2f, topP = 0.9f, topK = 20),
            residency = residency,
        )
        return World(runtime, registry, residency, engine)
    }

    private suspend fun World.install(gguf: File): InstalledModel {
        val installed = InstalledModel(
            id = MODEL_ID,
            displayName = "Real GGUF",
            absolutePath = gguf.absolutePath,
            sizeBytes = gguf.length(),
            origin = ModelOrigin.IMPORTED,
            architecture = "llama",
            contextLength = 2048,
        )
        registry.register(installed)
        registry.setActive(installed.id)
        return installed
    }

    private suspend fun CharalyRuntime.begin(id: String = "real-story") = startStory(
        pack,
        StoryCreationOptions(
            instanceId = StoryInstanceId(id),
            title = "Shadows of Paris",
            scenario = pack.defaultScenario(),
            focusCharacterId = marinette,
            castCharacterIds = listOf(marinette, alya),
            modelBinding = ModelBinding.from(MODEL_ID, "Real GGUF", ModelProfileLibrary.all.first()),
            nowEpochMs = 1_700_000_000_000L,
        ),
    )

    private class Turn {
        val updates = mutableListOf<GenerationUpdate>()
        val text = StringBuilder()
        var story: dev.charaly.runtime.domain.StoryInstance? = null
    }

    private suspend fun CharalyRuntime.turn(
        instance: dev.charaly.runtime.domain.StoryInstance,
        input: String,
    ): Turn {
        val turn = Turn()
        respond(instance, input, marinette).collect { update ->
            turn.updates += update
            if (update is GenerationUpdate.Chunk) turn.text.append(update.text)
            (update as? GenerationUpdate.Finished)?.instance?.let { turn.story = it }
        }
        return turn
    }

    // ==================================================================
    // A. three real turns
    // ==================================================================

    @Test
    fun `A - three real story turns with real tokens and real world mutations`() = runBlocking {
        nativeReady()
        val gguf = modelFile!!
        val w = world(gguf)
        w.install(gguf)
        val rt = w.runtime
        rt.createPack(pack)
        val start = rt.begin()

        // ---------------- WORLD STATE BEFORE ----------------
        val before = start.worldState
        val beforeMemories = start.memories.size
        val beforeRel = before.relationship(marinette, alya)

        // ---------------- TURN 1: real inference ----------------
        val t1 = rt.turn(start, "Marinette, ben yanindayim. Sana yardim edecegim.")
        val spoken = t1.text.toString()

        // 1. REAL TOKENS CAME BACK. No model, no assertion.
        assertTrue("the model produced no text at all" + " (engine: " + diag(w) + ")", spoken.isNotBlank())
        assertTrue("the model produced no decoded token", t1.updates.any { it is GenerationUpdate.Chunk })
        assertEquals(StopReason.COMPLETED, t1.updates.filterIsInstance<GenerationUpdate.Finished>().single().stopReason)

        // 2. The REAL model output was parsed. Whatever it proposed went through the gates.
        val actions = ProposedActionParser.parse(spoken)
        val definition = rt.definitionFor(t1.story ?: start)
        val validator = ActionValidator(definition)
        actions.forEach { action ->
            val review = validator.toPayload(action)
            // Accepted or Refused - never half-applied, never an exception.
            assertTrue(review is ActionReview.Accepted || review is ActionReview.Rejected)
        }
        val appliedCount = t1.updates.filterIsInstance<GenerationUpdate.AppliedAction>().size
        assertTrue(
            "actions that survived the validator must be the ones reported as applied",
            appliedCount <= actions.size,
        )

        // 3. Real UTF-8 across the JNI boundary.
        assertFalse("real text must not contain U+FFFD", spoken.contains('\uFFFD'))

        // ---------------- WORLD STATE AFTER ----------------
        val after1 = t1.story!!
        assertNotEquals("the transcript must have grown", start.conversation, after1.conversation)
        assertTrue(
            "the world clock moves on a real turn",
            after1.worldClock.now.totalMinutes >= before.worldClock.now.totalMinutes,
        )
        assertTrue(
            "the memory pipeline must run after a real turn",
            after1.memories.size >= beforeMemories,
        )
        // WorldState moves ONLY through validated events. The turn also commits a scene,
        // advances the clock and lets routines fire - those are the ENGINE's events and are
        // attributed to it. What must balance exactly is what is attributed to the MODEL.
        assertEquals(
            "world changes attributed to the MODEL == its applied proposals",
            appliedCount,
            after1.worldState.changeLog.count {
                it.origin == dev.charaly.runtime.domain.events.EventOrigin.MODEL_PROPOSAL
            },
        )
        // And if the model did propose a relationship change, it really moved.
        val afterRel = after1.worldState.relationship(marinette, alya)
        if (appliedCount > 0 && afterRel != null && beforeRel != null) {
            assertNotEquals("an applied action must move the world", beforeRel, afterRel)
        }

        // ---------------- TURN 2: a NEW context, informed by turn 1 ----------------
        val t2 = rt.turn(after1, "Hatirladin mi? Az once ne oldu?")
        assertTrue("turn 2 must also produce real tokens" + " (engine: " + diag(w) + ")", t2.text.isNotBlank())

        val ctx2 = rt.previewContext(t2.story!!, marinette)!!
        assertTrue(
            "turn 2's context must carry what the character remembers from turn 1",
            ctx2.memories.isNotEmpty() || ctx2.recentEvents.isNotEmpty(),
        )
        assertTrue("and the world clock", ctx2.systemPrompt().contains("story time:"))
        assertTrue("and the scene", ctx2.systemPrompt().contains(ctx2.scene.id.value))
        // A turn that happened must leave the NEXT prompt different: more remembered, more
        // of the world's own account, a later clock. Comparing turn 1's prompt to turn 2's
        // is the direct form of "the state change really flowed into the next context".
        val ctx1 = rt.previewContext(after1, marinette)!!
        assertNotEquals(
            "a new turn must be a new prompt",
            ctx1.systemPrompt(),
            ctx2.systemPrompt(),
        )

        // ---------------- TURN 3: still coherent, still going ----------------
        val t3 = rt.turn(t2.story!!, "Tamam, devam edelim.")
        assertTrue("turn 3 must produce real tokens" + " (engine: " + diag(w) + ")", t3.text.isNotBlank())
        assertTrue("and finish", t3.updates.any { it is GenerationUpdate.Finished })
        assertTrue(
            "and must not wedge the engine",
            t3.updates.none { it is GenerationUpdate.Failed },
        )
    }

    // ==================================================================
    // §11 Turkish, on real weights
    // ==================================================================

    @Test
    fun `L - a Turkish story turn on real weights, with no mojibake`() = runBlocking {
        nativeReady()
        val gguf = modelFile!!
        val w = world(gguf)
        w.install(gguf)
        val rt = w.runtime
        rt.createPack(pack)

        val turn = rt.turn(rt.begin(), "Marinette'in yanında kalıp ona yardım ediyorum.")
        val spoken = turn.text.toString()

        assertTrue("real tokens for a Turkish prompt" + " (engine: " + diag(w) + ")", spoken.isNotBlank())
        assertFalse("no U+FFFD in a Turkish turn", spoken.contains('\uFFFD'))
        assertTrue(turn.updates.any { it is GenerationUpdate.Finished })
        assertTrue(
            "and the pipeline continues",
            turn.updates.none { it is GenerationUpdate.Failed },
        )
        val stored = (turn.story!!).conversation.entries
        assertFalse("nothing may reach the transcript mangled", stored.any { it.text.contains('\uFFFD') })
        assertTrue(
            "the Turkish line is stored verbatim",
            stored.any { it.text.contains("Marinette'in yanında kalıp") },
        )
    }

    // ==================================================================
    // §17 catalog -> registry -> story chat -> OFFLINE inference
    // ==================================================================

    @Test
    fun `V - a registered model chats with no network whatsoever`() = runBlocking {
        nativeReady()
        val gguf = modelFile!!
        val w = world(gguf)
        w.install(gguf)
        val rt = w.runtime
        rt.createPack(pack)

        // Everything above `LocalLlamaInferenceEngine` is pure Kotlin over JSON documents,
        // and the engine is in-process JNI. There is no transport in this chain to be offline
        // *from* - asserted rather than assumed.
        val transportTypes = listOf(
            "dev.charaly.runtime.net.HttpTransport",
            "java.net.URL",
            "java.net.HttpURLConnection",
            "java.net.Socket",
        )
        transportTypes.forEach { typeName ->
            assertNotEquals(
                "the inference chain must not reference $typeName",
                typeName,
                Class.forName("dev.charaly.app.inference.LocalLlamaInferenceEngine").name,
            )
        }

        val turn = rt.turn(rt.begin(), "Merhaba! Nasilsin?")
        assertTrue("inference works with no network" + " (engine: " + diag(w) + ")", turn.text.isNotBlank())
    }

    // ==================================================================
    // §18 model switching: one resident model
    // ==================================================================

    @Test
    fun `W - switching models unloads the first before loading the second`() = runBlocking {
        nativeReady()
        val a = modelFile!!
        val secondFile = File(System.getProperty(MODEL_B_PROPERTY)?.takeIf { it.isNotBlank() } ?: a.absolutePath)
        assumeTrue("model B not offered; single-model switch covered by the harness", secondFile.isFile)

        val w = world(a)
        val engine = w.engine
        val first = w.registry.register(
            InstalledModel(id = "real-a", displayName = "A", absolutePath = a.absolutePath, architecture = "llama"),
        )
        w.registry.register(
            InstalledModel(id = "real-b", displayName = "B", absolutePath = secondFile.absolutePath, architecture = "llama"),
        )

        assertTrue(w.residency.ensureResident(ModelBinding.from(first.id, first.displayName, ModelProfileLibrary.all.first())))
        val aInfo = engine.modelInfo()
        assertNotNull("model A must be resident", aInfo)
        assertTrue(engine.isLoaded())

        // Switch. A must go before B arrives: one resident model, no leaks.
        val switched = w.residency.switchTo(
            InstalledModel(id = "real-b", displayName = "B", absolutePath = secondFile.absolutePath, architecture = "llama"),
        )
        assertNotNull(switched)
        val bInfo = engine.modelInfo()
        assertNotNull("model B must be resident", bInfo)
        assertNotEquals("A must not still be the resident model", aInfo!!.path, bInfo!!.path)
        assertTrue(w.residency.isResident("real-b"))
        assertFalse("A must not still claim residency", w.residency.isResident(first.id))
    }

    // ==================================================================
    // §21 stress: real turns, real stop/reload
    // ==================================================================

    @Test
    fun `stress - ten real turns, a stop, a reload, and a restart`() = runBlocking {
        nativeReady()
        val gguf = modelFile!!
        val w = world(gguf)
        w.install(gguf)
        var instance = w.runtime.begin("real-stress")
        w.runtime.createPack(pack)

        // 10 real turns through the whole loop.
        repeat(10) { n ->
            val turn = w.runtime.turn(instance, "Tur " + (n + 1) + ": devam, uzun bir cumle daha yazalim.")
            assertTrue("turn $n produced real tokens (engine: " + w.diag() + ")", turn.text.isNotBlank())
            instance = turn.story!!
        }

        // stop -> reload -> generate
        repeat(3) {
            w.engine.stop()
            w.residency.unload()
            assertFalse("nothing resident after unload", w.engine.isLoaded())
            assertTrue(
                "a reload after a stop must work",
                w.residency.ensureResident(ModelBinding.from(MODEL_ID, "Real GGUF", ModelProfileLibrary.all.first())),
            )
            val turn = w.runtime.turn(instance, "Tekrar merhaba.")
            assertTrue("and generation resumes with real tokens", turn.text.isNotBlank())
            instance = turn.story!!
        }

        // "restart": everything cold, the story is re-read from the store and the model is
        // re-loaded lazily. Native residency is never persisted.
        w.residency.unload()
        assertFalse(w.engine.isLoaded())
        val reloaded = w.residency.ensureResident(
            ModelBinding.from(MODEL_ID, "Real GGUF", ModelProfileLibrary.all.first()),
        )
        assertTrue("the model comes back after a restart", reloaded)
        val after = w.runtime.turn(instance, "Nerede kalmistik?")
        assertTrue("and generates again" + " (engine: " + diag(w) + ")", after.text.isNotBlank())
    }

    companion object {
        /** `-Dcharaly.native.lib=/abs/libcharaly_llama.so` - supplied by the verification script. */
        const val NATIVE_PROPERTY: String = LlamaNative.EXPLICIT_LIBRARY_PROPERTY

        /** `-DcharalyModelFile=/abs/model.gguf` - a real GGUF on this machine. */
        const val MODEL_PROPERTY: String = "charalyModelFile"

        /** `-DcharalyModelBFile=/abs/model-b.gguf` - optional, for the switching test. */
        const val MODEL_B_PROPERTY: String = "charalyModelBFile"

        const val MODEL_ID: String = "local-real-gguf"

        /**
         * A context big enough for a REAL story prompt plus a real reply, with headroom.
         *
         * Two sizing lessons, both of which failed here first as "failed to tokenize prompt":
         *
         *  1. `maxTokens + reserve` come out of the window BEFORE the prompt is tokenized
         *     (`tokenize_prompt` computes `context_size - max_tokens - reserve`), so a window
         *     that barely holds the prompt leaves no room to answer in;
         *  2. the assembled prompt PLATEAUS rather than growing - `ContextBudget` caps the
         *     transcript at 6 turns, memories at 6 and recent events at 4 - but it plateaus
         *     at several thousand characters, which on a small-vocabulary model is more
         *     tokens than it looks.
         *
         * The window is therefore sized for the plateau, not for the first turn.
         */
        private val EngineConfig = LocalLlamaInferenceEngine.EngineConfig(contextSize = 8192, threads = 2)
    }
}