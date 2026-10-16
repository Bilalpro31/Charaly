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
 * PHASE 8 diagnostic: the four fixed Turkish turns from the brief, run through the real
 * runtime with the real model, dumping BOTH the raw model output and what the player sees.
 *
 * A report, never a gate: it always passes and skips without a native library or model.
 * Quality is judged by reading this file, not by a diacritic count.
 */
class TurkishChatDiagnosticTest {

    private val pack = MiraculousPack.pack
    private val marinette = CharacterId(MiraculousPack.MARINETTE)

    private val modelFile: File? = System.getProperty("charalyModelFile")
        ?.takeIf { it.isNotBlank() }
        ?.let { File(it) }
        ?.takeIf { it.isFile }

    /** The four brief scenarios: language, comprehension, character consistency, follow-up. */
    private val scenario = listOf(
        "Merhaba, bugün okulda biraz endişeliyim. Sen nasılsın?",
        "Bugün arkadaşlarımla konuşmaya çekindim. Sence ne yapmalıyım?",
        "Sen olsaydın bu durumda ne yapardın?",
        "Neden böyle düşünüyorsun?",
    )

    @Test
    fun `four Turkish turns raw and displayed`() = runBlocking {
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
            id = "diag-model",
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
                instanceId = StoryInstanceId("diag-story"),
                title = "Diag",
                scenario = pack.defaultScenario(),
                focusCharacterId = marinette,
                castCharacterIds = listOf(marinette),
                modelBinding = ModelBinding.from(installed.id, installed.displayName, profile),
                nowEpochMs = 1_700_000_000_000L,
            ),
        )

        val out = StringBuilder()
        val turnLimit = System.getProperty("charalyTurns")?.toIntOrNull() ?: 4
        out.appendLine("MODEL: ${gguf.name}  CTX=$contextSize")
        out.appendLine("SAMPLER: maxTokens=${profile.sampler.maxTokens} temp=${profile.sampler.temperature} " +
            "topP=${profile.sampler.topP} topK=${profile.sampler.topK} minP=${profile.sampler.minP} " +
            "repeatPenalty=${profile.sampler.repeatPenalty}")
        out.appendLine("STOPS: ${profile.sampler.stopSequences}")

        scenario.take(turnLimit).forEachIndexed { index, input ->
            var raw = ""
            var displayed = ""
            var stopReason = "?"
            var genTokens = 0
            var reusedTokens = 0
            var promptTokens = 0
            val started = System.nanoTime()
            runtime.respond(instance, input, marinette).collect { update ->
                when (update) {
                    is GenerationUpdate.Chunk -> raw = update.accumulated
                    is GenerationUpdate.Finished -> {
                        displayed = update.text
                        stopReason = update.stopReason.name
                        instance = update.instance ?: instance
                    }
                    else -> Unit
                }
            }
            val wall = (System.nanoTime() - started) / 1_000_000L
            val timings = engine.lastTimings()
            genTokens = timings?.generatedTokens ?: 0
            reusedTokens = timings?.reusedTokens ?: 0
            promptTokens = timings?.promptTokens ?: 0

            out.appendLine()
            out.appendLine("======== TURN ${index + 1} ========")
            out.appendLine("USER: $input")
            out.appendLine("RAW_MODEL_OUTPUT:")
            out.appendLine(raw)
            out.appendLine("--- DISPLAYED_TO_PLAYER (after stripActions) ---")
            out.appendLine(displayed)
            out.appendLine("--- MEASUREMENTS ---")
            out.appendLine("stopReason=$stopReason generatedTokens=$genTokens promptTokens=$promptTokens " +
                "reusedTokens=$reusedTokens wallMs=$wall tokPerSec=" +
                (timings?.tokensPerSecond?.let { String.format("%.2f", it) } ?: "?"))
            out.appendLine("--- QUALITY SIGNALS ---")
            out.appendLine("META_HEADINGS=${META.findAll(raw).map { it.value }.toList()}")
            out.appendLine("PROMPT_LEAK=${PROMPT_LEAK.findAll(raw).map { it.value }.toList()}")
            out.appendLine("DIACRITICS=${raw.count { it.isLetter() && it.code in 0x00C0..0x024F }} LEN=${raw.length}")
            out.appendLine("ASKS_QUESTION=${raw.count { it == '?' }}")
            out.appendLine("REPEATED_6GRAM=${mostRepeatedGram(raw)}")
            // Flushed per turn: a host worker killed mid-run still leaves every completed
            // turn on disk, which is the entire point of writing evidence to a file.
            File("/tmp/opencode/phase8_turkish_diagnostic.txt").writeText(out.toString())
        }

        val text = out.toString()
        File("/tmp/opencode/phase8_turkish_diagnostic.txt").writeText(text)
        println(text)
    }

    /** The first six-word span that appears twice in one reply. */
    private fun mostRepeatedGram(text: String): String? {
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size < 12) return null
        val seen = LinkedHashMap<String, Int>()
        for (i in 0..words.size - 6) {
            val gram = words.subList(i, i + 6).joinToString(" ").lowercase()
            seen[gram] = (seen[gram] ?: 0) + 1
        }
        return seen.entries.firstOrNull { it.value >= 2 }?.let { "${it.key} (x${it.value})" }
    }

    private companion object {
        val META = Regex(
            "(?i)(final\\s*answer|final\\s*thought\\w*|\\byarn\\b|asistan\\s*:|assistant\\s*:|<\\s*think|sonuç\\s*:|özet\\s*:)",
        )
        /** Section headers that exist in OUR prompt - if these appear in the reply, it leaked. */
        val PROMPT_LEAK = Regex(
            "(?i)(what you know|what you remember|your identity|world rules|right now|pace|story time|personality|vocabulary|goals\\s*:|fears\\s*:)",
        )
    }
}