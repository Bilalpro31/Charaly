package dev.charaly.app.storyloop

import dev.charaly.app.inference.LlamaNative
import dev.charaly.app.inference.LocalLlamaInferenceEngine
import dev.charaly.app.model.LocalModelResidency
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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * A REAL multi-turn latency + cache-reuse + output-quality report, run against real weights
 * through the same JNI entry points the APK exports.
 *
 * The profile is derived from the model's own GGUF header through the same
 * `ModelPresetLibrary` the app uses, so the stop sequences, context budget and output length
 * are the ones a real install would run with - not a hand-picked profile.
 *
 * ```
 *   ./gradlew :app:testDebugUnitTest -PcharalyNativeLib=<lib.so> -PcharalyModelFile=<model.gguf> \
 *       --tests dev.charaly.app.storyloop.StoryLatencyBenchmarkTest
 * ```
 *
 * Output: `/tmp/opencode/story-latency-benchmark.txt`.
 */
class StoryLatencyBenchmarkTest {

    private val pack = MiraculousPack.pack
    private val marinette = CharacterId(MiraculousPack.MARINETTE)

    private val turns = 10

    private val modelFile: File? = System.getProperty("charalyModelFile")
        ?.takeIf { it.isNotBlank() }
        ?.let { File(it) }
        ?.takeIf { it.isFile }

    private fun ready() {
        assumeTrue("no libcharaly_llama.so offered", LlamaNative.isAvailable())
        assumeTrue("no real GGUF offered", modelFile != null)
    }

    private class Turn {
        val updates = mutableListOf<GenerationUpdate>()
        var text = ""
        var story: StoryInstance? = null
        var startedNanos = 0L
        var ttftNanos = 0L
        var totalNanos = 0L
        var perf: dev.charaly.runtime.session.TurnPerf? = null
    }

    private suspend fun CharalyRuntime.turn(instance: StoryInstance, input: String): Turn {
        val t = Turn()
        t.startedNanos = System.nanoTime()
        respond(instance, input, marinette).collect { update ->
            t.updates += update
            when (update) {
                is GenerationUpdate.Chunk -> if (t.ttftNanos == 0L) t.ttftNanos = System.nanoTime()
                is GenerationUpdate.Finished -> {
                    t.totalNanos = System.nanoTime()
                    t.story = update.instance
                    t.perf = update.perf
                }
                else -> Unit
            }
        }
        t.text = t.updates.filterIsInstance<GenerationUpdate.Chunk>().joinToString("") { it.text }
        return t
    }

    @Test
    fun `multi-turn latency, reuse and quality report against real weights`() = runBlocking {
        ready()
        val gguf = modelFile!!

        // Derive the profile from the model's own header, exactly as the app does on install.
        // Read just the header prefix, not the whole file into the test JVM.
        val metadata = gguf.inputStream().use { stream ->
            val prefix = stream.readNBytes(8 * 1024 * 1024)
            when (val parsed = GgufReader.readPrefix(prefix)) {
                is dev.charaly.runtime.model.gguf.GgufReadResult.Success -> parsed.metadata
                is dev.charaly.runtime.model.gguf.GgufReadResult.Failure ->
                    error("could not read GGUF header: ${parsed.reason}")
            }
        }
        val bundle = ModelPresetLibrary.build(
            metadata = metadata,
            displayName = metadata.name.ifBlank { gguf.nameWithoutExtension },
            absolutePath = gguf.absolutePath,
            fileSizeBytes = gguf.length(),
            availableRamBytes = 8_000_000_000L,
        )
        val profile = ModelPresetLibrary.toProfile(bundle)

        val engine = LocalLlamaInferenceEngine(
            LocalLlamaInferenceEngine.EngineConfig(contextSize = 4096),
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
            id = "latency-model",
            displayName = metadata.name.ifBlank { gguf.nameWithoutExtension },
            absolutePath = gguf.absolutePath,
            sizeBytes = gguf.length(),
            origin = ModelOrigin.IMPORTED,
            architecture = metadata.architecture,
            contextLength = metadata.contextLength,
        )
        registry.register(installed)
        registry.setActive(installed.id)
        runtime.createPack(pack)

        val binding = ModelBinding.from(installed.id, installed.displayName, profile)
        var instance = runtime.startStory(
            pack,
            StoryCreationOptions(
                instanceId = StoryInstanceId("latency-story"),
                title = "Latency",
                scenario = pack.defaultScenario(),
                focusCharacterId = marinette,
                castCharacterIds = listOf(marinette),
                modelBinding = binding,
                nowEpochMs = 1_700_000_000_000L,
            ),
        )

        val report = StringBuilder()
        report.appendLine("MODEL: ${gguf.name} (${gguf.length()} bytes)")
        report.appendLine("ARCH: ${metadata.architecture}  CTX: ${metadata.contextLength}  CHAT_TEMPLATE: ${metadata.hasChatTemplate}")
        report.appendLine("PROFILE: maxTokens=${profile.sampler.maxTokens} stops=${profile.sampler.stopSequences} ctxChars=${profile.contextPolicy.maxChars}")
        report.appendLine("ENGINE: contextSize=4096, 1 model, loaded lazily on turn 1")
        report.appendLine("TURNS: $turns")
        report.appendLine()

        var warmReuseTotal = 0
        var warmPromptTotal = 0
        val stopReasons = mutableMapOf<String, Int>()
        var actionsParsed = 0
        var actionsAccepted = 0
        var mojibake = 0

        repeat(turns) { index ->
            val input = "Tur ${index + 1}: devam edelim, kisa bir cumle yaz."
            val t = runtime.turn(instance, input)
            instance = t.story ?: instance

            val perf = t.perf
            val native = engine.lastTimings()
            val wallTtft = if (t.ttftNanos == 0L) -1L else (t.ttftNanos - t.startedNanos) / 1_000_000L
            val wallTotal = (t.totalNanos - t.startedNanos) / 1_000_000L
            val prompt = native?.promptTokens ?: 0
            val reused = native?.reusedTokens ?: 0
            val ratio = if (prompt > 0) reused.toDouble() / prompt.toDouble() else 0.0

            // Quality: real action pipeline + Turkish integrity.
            val actions = ProposedActionParser.parse(t.text)
            val validator = ActionValidator(runtime.definitionFor(instance))
            var accepted = 0
            actions.forEach { if (validator.toPayload(it) is dev.charaly.runtime.director.ActionReview.Accepted) accepted++ }
            actionsParsed += actions.size
            actionsAccepted += accepted
            if (t.text.contains('�')) mojibake++
            stopReasons[native?.stopReason ?: "?"] = (stopReasons[native?.stopReason ?: "?"] ?: 0) + 1

            report.appendLine("[TURN ${index + 1}]")
            report.appendLine("  wall_total=${wallTotal}ms wall_ttft=${if (wallTtft < 0) "n/a" else "${wallTtft}ms"}")
            report.appendLine("  context_chars=${perf?.contextChars} prompt_tokens=$prompt reused=$reused reuse_ratio=${"%.3f".format(ratio)} evaluated=${prompt - reused}")
            report.appendLine("  prompt_eval=${perf?.promptEvalMillis}ms generation=${perf?.generationMillis}ms")
            report.appendLine("  generated=${native?.generatedTokens} stop_reason=${native?.stopReason} budget=${native?.budget}")
            report.appendLine("  tok_per_sec=${perf?.tokensPerSecond} engine_loaded=${engine.isLoaded()} text_chars=${t.text.length}")
            report.appendLine("  actions_parsed=${actions.size} actions_accepted=$accepted mojibake=${t.text.contains('�')}")
            report.appendLine()

            if (index > 0) {
                warmReuseTotal += reused
                warmPromptTotal += prompt
            }
        }

        val warmRatio = if (warmPromptTotal > 0) warmReuseTotal.toDouble() / warmPromptTotal.toDouble() else 0.0
        report.appendLine("WARM AVERAGE reuse_ratio=${"%.3f".format(warmRatio)} (reused=$warmReuseTotal of prompt=$warmPromptTotal over ${turns - 1} warm turns)")
        report.appendLine("STOP REASONS: $stopReasons")
        report.appendLine("ACTIONS: parsed=$actionsParsed accepted=$actionsAccepted")
        report.appendLine("MOJIBAKE turns: $mojibake")

        assertTrue("the model must stay resident between turns", engine.isLoaded())

        val out = File("/tmp/opencode/story-latency-benchmark.txt")
        out.parentFile?.mkdirs()
        out.writeText(report.toString())
        println(report)
    }
}
