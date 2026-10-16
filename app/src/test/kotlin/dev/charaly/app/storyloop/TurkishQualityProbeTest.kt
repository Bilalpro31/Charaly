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
 * PHASE 7 probe: dumps the FULL raw reply for Turkish turns, so the Turkish-language rule
 * and the meta-heading ban can be judged by reading actual output rather than by proxies.
 *
 * A report, never a gate: it always passes and it skips without a native library or model.
 */
class TurkishQualityProbeTest {

    private val pack = MiraculousPack.pack
    private val marinette = CharacterId(MiraculousPack.MARINETTE)

    private val modelFile: File? = System.getProperty("charalyModelFile")
        ?.takeIf { it.isNotBlank() }
        ?.let { File(it) }
        ?.takeIf { it.isFile }

    private val scenario = listOf(
        "Merhaba, bugün okulda biraz endişeliyim. Sen nasılsın?",
        "Sana bir şey sormak istiyorum, biraz zamanın var mı?",
        "Geçen hafta kütüphanede gördüğüm kitabı hatırlıyor musun?",
    )

    @Test
    fun `dump full Turkish replies`() = runBlocking {
        assumeTrue("no native library", LlamaNative.isAvailable())
        assumeTrue("no model", modelFile != null)
        val gguf = modelFile!!
        val contextSize = System.getProperty("charalyContextSize")?.toIntOrNull() ?: 4096

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
            LocalLlamaInferenceEngine.EngineConfig(contextSize = contextSize, threads = 0),
        )
        val registry = JsonModelRegistry(InMemoryCharalyStorage())
        val runtime = CharalyRuntime(
            repository = JsonCharalyRepository(InMemoryCharalyStorage()),
            engine = engine,
            params = profile.sampler.toGenerationParams(),
            residency = LocalModelResidency(registry, engine),
        )
        val installed = InstalledModel(
            id = "probe-model",
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
                instanceId = StoryInstanceId("probe-story"),
                title = "Probe",
                scenario = pack.defaultScenario(),
                focusCharacterId = marinette,
                castCharacterIds = listOf(marinette),
                modelBinding = ModelBinding.from(installed.id, installed.displayName, profile),
                nowEpochMs = 1_700_000_000_000L,
            ),
        )

        val out = StringBuilder()
        out.appendLine("MODEL: ${gguf.name}")
        out.appendLine("=== RAW REPLIES (no strip, exactly what the model emitted) ===")

        scenario.forEachIndexed { index, input ->
            var raw = ""
            runtime.respond(instance, input, marinette).collect { update ->
                when (update) {
                    is GenerationUpdate.Chunk -> raw = update.accumulated
                    is GenerationUpdate.Finished -> instance = update.instance ?: instance
                    else -> Unit
                }
            }
            out.appendLine()
            out.appendLine("--- TURN ${index + 1} USER: $input")
            out.appendLine("RAW: $raw")
            val meta = META_HEADING.findAll(raw).map { it.value }.toList()
            out.appendLine("META_HEADINGS: $meta")
            val turkishChars = raw.count { ch -> ch.isLetter() && ch.code in 0x00C0..0x024F }
            out.appendLine("DIACRITIC_CHARS: $turkishChars  LEN: ${raw.length}")
        }

        val text = out.toString()
        File("/tmp/opencode/phase7_turkish_probe.txt").writeText(text)
        println(text)
    }

    private companion object {
        val META_HEADING = Regex(
            "(?i)(final\\s*answer|final\\s*thought\\w*|yarn|yan\\s*t\\w*|sonuç\\s*:|asistan|assistant\\s*:|system\\s*:|<\\s*think)",
        )
    }
}