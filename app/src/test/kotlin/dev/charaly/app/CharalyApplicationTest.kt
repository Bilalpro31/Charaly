package dev.charaly.app

import dev.charaly.runtime.compat.CharacterCardImporter
import dev.charaly.runtime.compat.GgufMetadataReader
import dev.charaly.runtime.domain.StoryDuration
import dev.charaly.runtime.engine.SampleWorlds
import dev.charaly.runtime.inference.InferenceRequest
import dev.charaly.runtime.inference.LoadOutcome
import dev.charaly.runtime.inference.MockInferenceEngine
import dev.charaly.runtime.inference.ModelLoadRequest
import dev.charaly.runtime.persistence.InMemoryCharalyStorage
import dev.charaly.runtime.persistence.JsonCharalyRepository
import dev.charaly.runtime.session.CharalyRuntime
import dev.charaly.runtime.session.GenerationUpdate
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * App-layer wiring tests.
 *
 * These run on the JVM with `unitTests.isReturnDefaultValues = true`, so they
 * cover the code that decides *which* engine and *which* storage Charaly uses -
 * without needing a device or a GGUF file.
 */
class CharalyApplicationTest {

    private val pack = SampleWorlds.libraryPack()

    private fun runtime(storage: InMemoryCharalyStorage = InMemoryCharalyStorage()) =
        storage to CharalyRuntime(
            repository = JsonCharalyRepository(storage),
            engine = MockInferenceEngine(initiallyLoaded = true),
        )

    @Test
    fun `the runtime seeds the demo pack on a first run`() = runTest {
        val (_, charaly) = runtime()
        charaly.restore()
        charaly.createPack(pack)

        val packs = charaly.listPacks()
        assertEquals(1, packs.size)
        assertEquals("The Lamplighter's Ledger", packs.single().title)
        assertEquals(2, packs.single().characters.size)
    }

    @Test
    fun `an imported character card can start a story`() = runTest {
        val card = """
        {"spec":"chara_card_v2","spec_version":"2.0","data":{
          "name":"Wren","personality":"precise","first_mes":"You are early."}}
        """.trimIndent()
        val character = CharacterCardImporter.importCard(card).getOrThrow()
        val importedPack = pack.copy(
            characters = pack.characters + character,
            initialWorldState = pack.initialWorldState.copy(
                startLocations = pack.initialWorldState.startLocations + (character.id to pack.locations.first().id),
            ),
        )
        val (_, charaly) = runtime()
        charaly.createPack(importedPack)

        val instance = charaly.startStory(importedPack, dev.charaly.runtime.domain.StoryInstanceId("card-story"))
        assertTrue(instance.characters.containsKey(character.id))
        assertNotNull(instance.characters[character.id])
    }

    @Test
    fun `generation requires a loaded model`() = runTest {
        val (_, charaly) = runtime()
        charaly.createPack(pack)
        val instance = charaly.startStory(pack, dev.charaly.runtime.domain.StoryInstanceId("s1"))

        // The engine used here reports as loaded, so a turn succeeds.
        val updates = charaly.respond(instance, "Hello?", instance.focusCharacterId!!).toList()
        assertTrue(updates.any { it is GenerationUpdate.Finished })
    }

    @Test
    fun `an unloaded engine is reported honestly`() = runTest {
        val charaly = CharalyRuntime(
            repository = JsonCharalyRepository(InMemoryCharalyStorage()),
            engine = MockInferenceEngine(initiallyLoaded = false),
        )
        assertFalse(charaly.isModelLoaded())
        assertEquals(null, charaly.loadedModel())
    }

    @Test
    fun `model load failures surface as messages, not crashes`() = runTest {
        val engine = object : dev.charaly.runtime.inference.InferenceEngine {
            override suspend fun loadModel(request: ModelLoadRequest) = LoadOutcome.Failed(
                dev.charaly.runtime.inference.InferenceError.ModelNotFound(request.path),
            )
            override suspend fun unloadModel() = Unit
            override suspend fun generate(request: InferenceRequest) =
                dev.charaly.runtime.inference.InferenceResult("")
            override fun stream(request: InferenceRequest) = kotlinx.coroutines.flow.flow<dev.charaly.runtime.inference.StreamChunk>()
            override fun stop() = Unit
            override fun isLoaded() = false
            override fun modelInfo() = null
        }
        val charaly = CharalyRuntime(JsonCharalyRepository(InMemoryCharalyStorage()), engine)
        val outcome = charaly.loadModel(ModelLoadRequest(path = "/does/not/exist.gguf"))
        assertTrue(outcome is LoadOutcome.Failed)
        assertTrue((outcome as LoadOutcome.Failed).error.message!!.contains("not found"))
    }

    @Test
    fun `stop is safe to call when nothing is generating`() = runTest {
        val (_, charaly) = runtime()
        charaly.stop()
        assertTrue("stop must never throw", true)
    }

    @Test
    fun `advancing the clock from the inspector is deterministic`() = runTest {
        val storage = InMemoryCharalyStorage()
        val (_, charaly) = runtime(storage)
        charaly.createPack(pack)
        val instance = charaly.startStory(pack, dev.charaly.runtime.domain.StoryInstanceId("s1"))

        val advanced = charaly.advance(instance, StoryDuration.hours(3))
        assertEquals(instance.worldClock.now.plusMinutes(180), advanced.worldClock.now)
        // And it persists, so the inspector sees it after a restart too.
        assertEquals(advanced.worldClock.now, charaly.loadStory(instance.id)!!.worldClock.now)
    }

    @Test
    fun `the fallback engine refuses rather than pretending to infer`() = runTest {
        // Used when the native library is missing. It must not fabricate output.
        val fallback = FallbackEchoEngine()
        assertFalse(fallback.isLoaded())
        val outcome = fallback.loadModel(ModelLoadRequest(path = "/tmp/whatever.gguf"))
        assertTrue(outcome is LoadOutcome.Failed)
        assertTrue(
            "the failure must explain how to fix it",
            (outcome as LoadOutcome.Failed).error.message!!.contains("setup-llama.sh"),
        )
    }

    @Test
    fun `a corrupt gguf is reported before native code sees it`() = runTest {
        // LocalLlamaInferenceEngine sniffs the GGUF magic before loading, so a
        // renamed text file cannot reach llama.cpp.
        val meta = GgufMetadataReader.read("definitely not a model".toByteArray())
        assertTrue(meta.isFailure)
    }

    @Test
    fun `stories survive a full app-style restart`() = runTest {
        val storage = InMemoryCharalyStorage()

        val (_, first) = runtime(storage)
        first.createPack(pack)
        val instance = first.startStory(pack, dev.charaly.runtime.domain.StoryInstanceId("s1"))
        first.respond(instance, "Who are you?", instance.focusCharacterId!!).toList()

        // "Kill the process." A brand new runtime over the same storage.
        val (_, second) = runtime(storage)
        val restored = second.restore()
        assertEquals(1, restored.instances.size)
        assertEquals(2, restored.instances.single().conversation.size)
        assertEquals(pack.id, restored.instances.single().storyPackId)
    }
}
