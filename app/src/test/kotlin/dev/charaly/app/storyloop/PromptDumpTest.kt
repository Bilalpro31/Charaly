package dev.charaly.app.storyloop

import dev.charaly.app.inference.LlamaNative
import dev.charaly.app.inference.LocalLlamaInferenceEngine
import dev.charaly.app.model.LocalModelResidency
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.engine.StoryCreationOptions
import dev.charaly.runtime.model.InstalledModel
import dev.charaly.runtime.model.JsonModelRegistry
import dev.charaly.runtime.model.ModelBinding
import dev.charaly.runtime.model.ModelOrigin
import dev.charaly.runtime.model.ModelPresetLibrary
import dev.charaly.runtime.model.gguf.GgufReadResult
import dev.charaly.runtime.model.gguf.GgufReader
import dev.charaly.runtime.model.toGenerationParams
import dev.charaly.runtime.pack.MiraculousPack
import dev.charaly.runtime.persistence.InMemoryCharalyStorage
import dev.charaly.runtime.persistence.JsonCharalyRepository
import dev.charaly.runtime.session.CharalyRuntime
import dev.charaly.runtime.session.GenerationUpdate
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * PHASE 7 evidence: dumps the EXACT rendered prompt - system block and every message - plus
 * the templated string the model actually reads. Proves the Turkish rule reaches llama.cpp
 * and that transcript roles survive the chat template.
 */
class PromptDumpTest {

    private val pack = MiraculousPack.pack
    private val marinette = CharacterId(MiraculousPack.MARINETTE)

    private val modelFile: File? = System.getProperty("charalyModelFile")
        ?.takeIf { it.isNotBlank() }
        ?.let { File(it) }
        ?.takeIf { it.isFile }

    @Test
    fun `dump the exact prompt the model receives`() = runBlocking {
        assumeTrue("no native library", LlamaNative.isAvailable())
        assumeTrue("no model", modelFile != null)
        val gguf = modelFile!!

        val metadata = gguf.inputStream().use { stream ->
            val parsed = GgufReader.readPrefix(stream.readNBytes(8 * 1024 * 1024))
            (parsed as? GgufReadResult.Success)?.metadata ?: error("unreadable header")
        }
        val bundle = ModelPresetLibrary.build(
            metadata = metadata,
            displayName = metadata.name,
            absolutePath = gguf.absolutePath,
            fileSizeBytes = gguf.length(),
            availableRamBytes = 8_000_000_000L,
        )
        val profile = ModelPresetLibrary.toProfile(bundle)

        val engine = LocalLlamaInferenceEngine(
            LocalLlamaInferenceEngine.EngineConfig(contextSize = 4096, threads = 0),
        )
        val registry = JsonModelRegistry(InMemoryCharalyStorage())
        val runtime = CharalyRuntime(
            repository = JsonCharalyRepository(InMemoryCharalyStorage()),
            engine = engine,
            params = profile.sampler.toGenerationParams(),
            residency = LocalModelResidency(registry, engine),
        )
        val installed = InstalledModel(
            id = "dump-model",
            displayName = metadata.name,
            absolutePath = gguf.absolutePath,
            sizeBytes = gguf.length(),
            origin = ModelOrigin.IMPORTED,
            architecture = metadata.architecture,
            contextLength = metadata.contextLength,
        )
        registry.register(installed)
        registry.setActive(installed.id)
        runtime.createPack(pack)

        var instance = runtime.startStory(
            pack,
            StoryCreationOptions(
                instanceId = StoryInstanceId("dump-story"),
                title = "Dump",
                scenario = pack.defaultScenario(),
                focusCharacterId = marinette,
                castCharacterIds = listOf(marinette),
                modelBinding = ModelBinding.from(installed.id, installed.displayName, profile),
                nowEpochMs = 1_700_000_000_000L,
            ),
        )

        val out = StringBuilder()

        // Turn 1: the prompt for a fresh conversation.
        val ctx1 = runtime.previewContext(instance, marinette, "Merhaba, bugün okulda biraz endişeliyim.")
        out.appendLine("=== TURN 1 CONTEXT ===")
        ctx1?.let { context ->
            out.appendLine("--- systemPromptStable() ---")
            out.appendLine(context.systemPromptStable())
            out.appendLine("--- systemPromptVolatile() ---")
            out.appendLine(context.systemPromptVolatile())
            out.appendLine("--- estimatedChars=${context.estimatedChars()} ---")
        }

        // Run one real turn so a transcript exists, then dump turn 2's rendered prompt.
        runtime.respond(instance, "Merhaba, bugün okulda biraz endişeliyim.", marinette).collect { update ->
            if (update is GenerationUpdate.Finished) instance = update.instance ?: instance
        }
        runtime.respond(instance, "Sana bir şey sormak istiyorum, biraz zamanın var mı?", marinette).collect { update ->
            if (update is GenerationUpdate.Finished) instance = update.instance ?: instance
        }

        out.appendLine()
        out.appendLine("=== TRANSCRIPT ROLES AFTER TWO TURNS ===")
        instance.conversation.entries.forEach { entry ->
            out.appendLine("role=${entry.role} speaker=${entry.speakerId?.value ?: "-"} text=${entry.text.take(60)}")
        }

        out.appendLine()
        out.appendLine("=== RENDERED REQUEST (buildPrompt -> llama.cpp) ===")
        val definition = runtime.definitionFor(instance)
        val scene = dev.charaly.runtime.director.SceneDirector(definition).directScene(instance, marinette)
        if (scene != null) {
            val builder = dev.charaly.runtime.context.ContextBuilder(
                definition,
                runtime.contextBudgetFor(instance),
            )
            val request = builder.buildRequest(
                instance = instance,
                scene = scene,
                characterId = marinette,
                userInput = "Şimdi ne yapalım?",
                params = runtime.paramsFor(instance),
            )
            out.appendLine("--- systemPrompt ---")
            out.appendLine(request.systemPrompt)
            out.appendLine("--- messages (role | name | length) ---")
            request.messages.forEach { m ->
                out.appendLine("${m.role} | name=${m.name ?: "-"} | len=${m.content.length} | head=${m.content.take(70)}")
            }
            out.appendLine("--- templated prompt handed to generate() ---")
            val templated = engine.buildPromptInternal(request)
            out.appendLine(templated)
            out.appendLine("--- END templated (len=${templated.length}) ---")
            out.appendLine("hasChatTemplate=${metadata.hasChatTemplate}")
            out.appendLine("turkish_rule_present=${request.systemPrompt.contains("Reply in Turkish")}")
            out.appendLine("tail_folded_into_user_line=${request.messages.lastOrNull()?.content?.contains("RIGHT NOW") == true}")
        }

        val text = out.toString()
        File("/tmp/opencode/phase7_prompt_dump.txt").writeText(text)
        println(text)
    }
}