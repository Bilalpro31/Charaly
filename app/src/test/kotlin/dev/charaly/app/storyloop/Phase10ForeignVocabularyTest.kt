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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * PHASE 10 A — the case Phase 7 actually broke on.
 *
 * ## Why this character and not Marinette
 *
 * Marinette's `vocabulary` line was fixed in Phase 7 to say "Speak in the SAME language the
 * player uses - Turkish when they write Turkish", and the Phase 9 logs show her answering
 * Turkish on Qwen3-4B. Re-proving her would be a test that passes for the wrong reason.
 *
 * Adrien's was not. His sheet still reads:
 *
 * ```
 * Vocabulary: Precise, upper-register French; few slang words, used precisely.
 * ```
 *
 * That is an English sentence describing a FRENCH voice, sitting in a prompt that is
 * otherwise English, addressed to a player who writes Turkish. It is the exact shape of
 * the Phase 7 failure - "a bare 'French-English speech' instruction is a DIRECT ORDER to
 * answer in English" - and it is the shape the Phase 10 language block exists to outrank.
 *
 * The block says, in Turkish, that everything below it is the author's note-taking and is
 * never the language the character speaks. If that sentence does not hold against a
 * character whose sheet names a third language, it does not hold at all.
 *
 * Two turns, not four: the question is whether the FIRST reply is Turkish, and whether a
 * short follow-up keeps it. Four turns would measure conversation quality, which is a
 * reader's judgement and is printed rather than asserted.
 */
class Phase10ForeignVocabularyTest {

    private val pack = MiraculousPack.pack
    private val adrien = CharacterId(MiraculousPack.ADRIEN)

    private val modelFile: File? = System.getProperty("charalyModelFile")
        ?.takeIf { it.isNotBlank() }
        ?.let { File(it) }
        ?.takeIf { it.isFile }

    private val script = listOf(
        "Merhaba, bugün biraz endişeliyim.",
        "Sence ne yapmalıyım?",
    )

    @Test
    fun `a character whose sheet names a third language still answers a Turkish player in Turkish`() = runBlocking {
        assumeTrue("no libcharaly_llama.so offered", LlamaNative.isAvailable())
        assumeTrue("no real GGUF offered (-PcharalyModelFile)", modelFile != null)
        val gguf = modelFile!!
        val contextSize = System.getProperty("charalyContextSize")?.toIntOrNull() ?: 4096

        val metadata = gguf.inputStream().use { stream ->
            val parsed = GgufReader.readPrefix(stream.readNBytes(16 * 1024 * 1024))
            (parsed as? GgufReadResult.Success)?.metadata ?: error("unreadable GGUF header")
        }
        val profile = ModelPresetLibrary.toProfile(
            ModelPresetLibrary.build(
                metadata = metadata,
                displayName = metadata.name,
                absolutePath = gguf.absolutePath,
                fileSizeBytes = gguf.length(),
                availableRamBytes = 8_000_000_000L,
            ),
        )

        val engine = LocalLlamaInferenceEngine(
            LocalLlamaInferenceEngine.EngineConfig(contextSize = contextSize),
        )
        val registry = JsonModelRegistry(InMemoryCharalyStorage())
        val residency = LocalModelResidency(registry, engine)
        val runtime = CharalyRuntime(
            repository = JsonCharalyRepository(InMemoryCharalyStorage()),
            engine = engine,
            params = profile.sampler.toGenerationParams(),
            residency = residency,
        )
        val installed = InstalledModel(
            id = "phase10-adrien-model",
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
                instanceId = StoryInstanceId("phase10-adrien"),
                title = "Phase 10 foreign vocabulary",
                scenario = pack.defaultScenario(),
                focusCharacterId = adrien,
                castCharacterIds = listOf(adrien),
                modelBinding = ModelBinding.from(installed.id, installed.displayName, profile),
                nowEpochMs = 1_700_000_000_000L,
            ),
        )

        val report = StringBuilder()
        report.appendLine("### PHASE 10 - A CHARACTER WHOSE SHEET NAMES A THIRD LANGUAGE")
        report.appendLine("model     : ${gguf.name}")
        report.appendLine("character : Adrien Agreste")
        report.appendLine("sheet     : \"Vocabulary: Precise, upper-register French; few slang words, used precisely.\"")
        report.appendLine("player    : Turkish")
        report.appendLine()

        val failures = mutableListOf<String>()

        script.forEachIndexed { index, userLine ->
            var raw = ""
            var ttft = 0L
            val entered = System.nanoTime()
            runtime.respond(instance, userLine, adrien).collect { update ->
                when (update) {
                    is GenerationUpdate.Chunk -> raw = update.accumulated
                    is GenerationUpdate.Finished -> {
                        instance = update.instance ?: instance
                        ttft = (System.nanoTime() - entered) / 1_000_000L
                    }
                    is GenerationUpdate.Failed -> report.appendLine("!! FAILED: ${update.error}")
                    else -> Unit
                }
            }

            val verdict = verdictOf(raw)
            report.appendLine("--- MESAJ ${index + 1}: $userLine")
            report.appendLine("CEVAP : $raw")
            report.appendLine("DİL   : $verdict   ttft=${ttft}ms")
            report.appendLine()

            if (raw.isBlank()) {
                failures += "turn ${index + 1} produced no tokens (engine: ${engine.lastDiagnostic()})"
            } else if (!verdict.startsWith("TURKISH")) {
                failures += "turn ${index + 1} answered in $verdict, not Turkish"
            }
            if (raw.any { it == '�' }) {
                failures += "turn ${index + 1}: U+FFFD - Turkish did not survive the JNI boundary"
            }
        }

        report.appendLine("### FAILURES (${failures.size})")
        failures.forEach { report.appendLine(" - $it") }

        val text = report.toString()
        File(REPORT_PATH).writeText(text)
        println(text)
        assertTrue(
            "a character whose sheet names French must still answer a Turkish player in Turkish:\n" +
                failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    /** Turkish / English / unknown, from the reply's own words. */
    private fun verdictOf(raw: String): String {
        val words = raw.lowercase(Locale.ROOT).split(Regex("[^a-zçğıöşü]+")).filter { it.isNotBlank() }
        val tr = words.count { TR_WORDS.contains(it) } * 2 +
            Regex("[ıİğĞşŞçÇöÖüÜ]").findAll(raw).count()
        val en = words.count { EN_WORDS.contains(it) } * 2
        return when {
            tr > 0 && tr >= en -> "TURKISH(tr=$tr,en=$en)"
            en > 0 && en > tr -> "ENGLISH(tr=$tr,en=$en)"
            else -> "UNKNOWN(tr=$tr,en=$en)"
        }
    }

    private companion object {
        const val REPORT_PATH = "/tmp/opencode/phase10_adrien.txt"

        val TR_WORDS = setOf(
            "merhaba", "nasılsın", "nasıl", "neden", "ne", "iyi", "kötü", "güzel", "ve", "ile",
            "için", "olarak", "çok", "daha", "bir", "bu", "şu", "ben", "sen", "siz", "biz",
            "var", "yok", "değil", "mi", "mı", "mu", "mü", "ama", "fakat", "gibi", "her",
            "şey", "gün", "bugün", "yarın", "dün", "okul", "arkadaş", "arkadaşlar", "birlikte",
            "yap", "yapabilirim", "yapmalıyım", "endişeli", "korku", "yardım", "teşekkür",
            "evet", "hayır", "tamam", "aslında", "sana", "seni", "beni", "bana", "senin",
            "benim", "çünkü", "zaman", "mutlu", "üzgün", "hadi", "konuş", "konuşmalıyım",
        )
        val EN_WORDS = setOf(
            "the", "of", "and", "to", "in", "is", "are", "you", "your", "i", "my", "me",
            "we", "they", "it", "that", "this", "for", "with", "not", "but", "have",
            "will", "can", "would", "should", "could", "was", "were", "be", "do", "if",
            "so", "as", "at", "on", "or", "an", "a", "he", "she", "his", "him", "them",
            "what", "how", "why", "when", "where", "there", "about", "from", "hello",
            "thanks", "sorry", "yes", "okay",
        )
    }
}
