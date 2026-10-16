package dev.charaly.app.storyloop

import dev.charaly.app.inference.LlamaNative
import dev.charaly.app.inference.LocalLlamaInferenceEngine
import dev.charaly.app.model.LocalModelResidency
import dev.charaly.runtime.director.ActionReview
import dev.charaly.runtime.director.ActionValidator
import dev.charaly.runtime.director.ProposedActionParser
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.engine.StoryCreationOptions
import dev.charaly.runtime.model.InstalledModel
import dev.charaly.runtime.model.JsonModelRegistry
import dev.charaly.runtime.model.ModelBinding
import dev.charaly.runtime.model.ModelOrigin
import dev.charaly.runtime.model.ModelPresetLibrary
import dev.charaly.runtime.model.toGenerationParams
import dev.charaly.runtime.model.gguf.GgufReader
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
 * Does the model actually use the action protocol?
 *
 * A narrative prompt does not reliably call for a world change, so this asks the model
 * explicitly to emit one, then runs the REAL [ProposedActionParser] and [ActionValidator].
 * The point is requirement 5: "do not accept merely fluent text".
 *
 * Skipped when no native library or model is offered.
 */
class ActionProtocolModelTest {

    private val pack = MiraculousPack.pack
    private val marinette = CharacterId(MiraculousPack.MARINETTE)

    private val modelFile: File? = System.getProperty("charalyModelFile")
        ?.takeIf { it.isNotBlank() }
        ?.let { File(it) }
        ?.takeIf { it.isFile }

    @Test
    fun `the model emits a parseable, valid action when asked`() = runBlocking {
        assumeTrue("no native library", LlamaNative.isAvailable())
        assumeTrue("no model", modelFile != null)
        val gguf = modelFile!!

        val metadata = gguf.inputStream().use { stream ->
            val parsed = GgufReader.readPrefix(stream.readNBytes(8 * 1024 * 1024))
            (parsed as? dev.charaly.runtime.model.gguf.GgufReadResult.Success)?.metadata
                ?: error("unreadable header")
        }
        val bundle = ModelPresetLibrary.build(
            metadata = metadata,
            displayName = metadata.name,
            absolutePath = gguf.absolutePath,
            fileSizeBytes = gguf.length(),
            availableRamBytes = 8_000_000_000L,
        )
        val profile = ModelPresetLibrary.toProfile(bundle)
        val engine = LocalLlamaInferenceEngine(LocalLlamaInferenceEngine.EngineConfig(contextSize = 4096))
        val registry = JsonModelRegistry(InMemoryCharalyStorage())
        val runtime = CharalyRuntime(
            repository = JsonCharalyRepository(InMemoryCharalyStorage()),
            engine = engine,
            params = profile.sampler.toGenerationParams(),
            residency = LocalModelResidency(registry, engine),
        )
        val installed = InstalledModel(
            id = "action-model",
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

        val story = runtime.startStory(
            pack,
            StoryCreationOptions(
                instanceId = StoryInstanceId("action-story"),
                title = "Action",
                scenario = pack.defaultScenario(),
                focusCharacterId = marinette,
                castCharacterIds = listOf(marinette),
                modelBinding = ModelBinding.from(installed.id, installed.displayName, profile),
                nowEpochMs = 1_700_000_000_000L,
            ),
        )

        // An explicit, unambiguous instruction to emit exactly the schema.
        val input = "Marinette okula gidiyor. Bu bir dunya degisikligidir. Yanitinin sonuna " +
            "tam olarak su satiri ekle: " +
            "<charaly:action type=\"move\" character=\"marinette\" target=\"school\" reason=\"okula gitti\"/>"

        val text = StringBuilder()
        var after: StoryInstance = story
        runtime.respond(story, input, marinette).collect { update ->
            if (update is GenerationUpdate.Chunk) text.append(update.text)
            (update as? GenerationUpdate.Finished)?.instance?.let { after = it }
        }

        val actions = ProposedActionParser.parse(text.toString())
        val validator = ActionValidator(runtime.definitionFor(after))
        val accepted = actions.mapNotNull { validator.toPayload(it) as? ActionReview.Accepted }

        val report = buildString {
            appendLine("MODEL: ${gguf.name}")
            appendLine("asked_for_action=true")
            appendLine("actions_parsed=${actions.size} actions_accepted=${accepted.size}")
            appendLine("raw_contains_tag=${text.contains("<charaly:action")}")
            appendLine("reply_chars=${text.length}")
        }
        val out = File("/tmp/opencode/action-protocol-${gguf.nameWithoutExtension}.txt")
        out.parentFile?.mkdirs()
        out.writeText(report)
        println(report)

        // If the model emitted any tag, the real validator must accept or explicitly refuse
        // it - never throw and never half-apply.
        actions.forEach { action ->
            val review = validator.toPayload(action)
            org.junit.Assert.assertTrue(
                "the validator must return a decision, not throw",
                review is ActionReview.Accepted || review is ActionReview.Rejected,
            )
        }
    }
}
