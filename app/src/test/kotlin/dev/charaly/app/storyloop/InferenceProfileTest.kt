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
import dev.charaly.runtime.inference.InferenceTimings
import dev.charaly.runtime.model.InstalledModel
import dev.charaly.runtime.model.JsonModelRegistry
import dev.charaly.runtime.model.ModelBinding
import dev.charaly.runtime.model.ModelOrigin
import dev.charaly.runtime.model.ModelPresetLibrary
import dev.charaly.runtime.model.toGenerationParams
import dev.charaly.runtime.model.gguf.GgufReadResult
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
 * An end-to-end per-stage profile of one real story playthrough, plus the structural
 * consistency checks that do not need a human reader.
 *
 * Everything measured comes from a clock: the runtime's own [dev.charaly.runtime.session.TurnPerf]
 * plus the engine's native [InferenceTimings]. It is a report, not a gate - it always
 * passes - and it is skipped when no model is offered.
 *
 * The scenario is fixed and Turkish so results are comparable across models. Narrative
 * *quality* cannot be scored mechanically; this reports the objective proxies (UTF-8
 * integrity, repetition) and the state-correctness facts that CAN be checked, and says
 * plainly that a human/LLM read is still required for prose quality.
 */
class InferenceProfileTest {

    private val pack = MiraculousPack.pack
    private val marinette = CharacterId(MiraculousPack.MARINETTE)

    private val modelFile: File? = System.getProperty("charalyModelFile")
        ?.takeIf { it.isNotBlank() }
        ?.let { File(it) }
        ?.takeIf { it.isFile }

    /** Fixed, comparable scenario: greeting, recall, relationship, secret, move, time, ... */
    private val scenario = listOf(
        "Merhaba Marinette, bugun nasilsin?",
        "Okulda bugun neler oldu, bana anlat.",
        "Alya'ya guveniyorum; onunla konusur musun?",
        "Bana bir sir verecek misin?",
        "Kutuphaneye gitmek istiyorum.",
        "Ne kadar zaman gecti?",
        "Alya simdi ne yapiyor?",
        "Dun olanlari hatirliyor musun?",
        "Bana bir soz verir misin?",
        "Bu gece planimiz ne?",
    )

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

    private fun p50(values: List<Long>): Long = values.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }
    private fun p95(values: List<Long>): Long = values.sorted().let {
        if (it.isEmpty()) 0 else it[minOf(it.size - 1, (it.size * 95) / 100)]
    }

    /**
     * The first six-word span that repeats inside one reply - the mechanical face of
     * "rambling". Six words is long enough that a repeat is a loop, not a coincidence.
     */
    private fun mostRepeatedWordGram(text: String): String? {
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size < 12) return null
        val seen = LinkedHashMap<String, Int>()
        for (i in 0..words.size - 6) {
            val gram = words.subList(i, i + 6).joinToString(" ").lowercase()
            seen[gram] = (seen[gram] ?: 0) + 1
        }
        return seen.entries.firstOrNull { it.value >= 2 }?.key
    }

    @Test
    fun `end-to-end per-stage profile and structural consistency`() = runBlocking {
        assumeTrue("no native library", LlamaNative.isAvailable())
        assumeTrue("no model", modelFile != null)
        val gguf = modelFile!!
        val threads = System.getProperty("charalyThreads")?.toIntOrNull() ?: 0
        val contextSize = System.getProperty("charalyContextSize")?.toIntOrNull() ?: 4096
        val turnCount = System.getProperty("charalyTurns")?.toIntOrNull() ?: scenario.size
        val scenario = scenario.take(turnCount.coerceIn(1, scenario.size))

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
            LocalLlamaInferenceEngine.EngineConfig(contextSize = contextSize, threads = threads),
        )
        val registry = JsonModelRegistry(InMemoryCharalyStorage())
        val runtime = CharalyRuntime(
            repository = JsonCharalyRepository(InMemoryCharalyStorage()),
            engine = engine,
            params = profile.sampler.toGenerationParams(),
            residency = LocalModelResidency(registry, engine),
        )
        val installed = InstalledModel(
            id = "profile-model",
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
                instanceId = StoryInstanceId("profile-story"),
                title = "Profile",
                scenario = pack.defaultScenario(),
                focusCharacterId = marinette,
                castCharacterIds = listOf(marinette),
                modelBinding = ModelBinding.from(installed.id, installed.displayName, profile),
                nowEpochMs = 1_700_000_000_000L,
            ),
        )

        val report = StringBuilder()
        report.appendLine("MODEL: ${gguf.name} (${gguf.length()} bytes)")
        report.appendLine("ARCH=${metadata.architecture} CTX=${contextSize} THREADS=${threads} TEMPLATE=${metadata.hasChatTemplate}")
        report.appendLine("PROFILE maxTokens=${profile.sampler.maxTokens} stops=${profile.sampler.stopSequences}")
        report.appendLine()

        val ttfts = mutableListOf<Long>()
        val evalMicrosList = mutableListOf<Long>()
        val tokenizeMicrosList = mutableListOf<Long>()
        val genMicrosList = mutableListOf<Long>()
        val totalList = mutableListOf<Long>()
        val ctxBuildList = mutableListOf<Long>()
        val parseList = mutableListOf<Long>()
        var reuseReused = 0L
        var reusePrompt = 0L
        var actionParsed = 0
        var actionAccepted = 0
        var rejectedButChanged = 0
        var mojibake = 0
        var repeatedPhraseTurns = 0
        var longestReplyChars = 0
        var repliesAtCap = 0
        val stopReasons = mutableMapOf<String, Int>()

        scenario.forEachIndexed { index, input ->
            val t = runtime.turn(instance, input)
            val next = t.story ?: instance
            val perf = t.perf
            val native: InferenceTimings? = engine.lastTimings()

            // ---- structural consistency ----
            val finished = t.updates.filterIsInstance<GenerationUpdate.Finished>().singleOrNull()
            val failed = t.updates.any { it is GenerationUpdate.Failed }
            val applied = t.updates.filterIsInstance<GenerationUpdate.AppliedAction>().size
            val rejected = t.updates.filterIsInstance<GenerationUpdate.RejectedAction>().size
            val modelChanges = next.worldState.changeLog.count {
                it.origin == dev.charaly.runtime.domain.events.EventOrigin.MODEL_PROPOSAL
            } - instance.worldState.changeLog.count {
                it.origin == dev.charaly.runtime.domain.events.EventOrigin.MODEL_PROPOSAL
            }
            if (modelChanges != applied) rejectedButChanged++
            if (t.text.contains('\uFFFD')) mojibake++

            // ---- narrative quality proxies (mechanical, deliberately not a taste score) ----
            val repeatedGram = mostRepeatedWordGram(t.text)
            if (repeatedGram != null) repeatedPhraseTurns++
            if (t.text.length > longestReplyChars) longestReplyChars = t.text.length

            val actions = ProposedActionParser.parse(t.text)
            val validator = ActionValidator(runtime.definitionFor(next))
            actions.forEach { if (validator.toPayload(it) is ActionReview.Accepted) actionAccepted++ }
            actionParsed += actions.size

            // ---- collect metrics ----
            val wallTtft = if (t.ttftNanos == 0L) -1L else (t.ttftNanos - t.startedNanos) / 1_000_000L
            val wallTotal = (t.totalNanos - t.startedNanos) / 1_000_000L
            if (wallTtft >= 0) ttfts += wallTtft
            if (native != null) {
                evalMicrosList += native.evalMicros
                tokenizeMicrosList += native.tokenizeMicros
                genMicrosList += native.generationMicros
                reuseReused += native.reusedTokens
                reusePrompt += native.promptTokens
            }
            totalList += wallTotal
            perf?.contextBuildMillis?.let { ctxBuildList += it }
            perf?.parserMillis?.let { parseList += it }
            stopReasons[native?.stopReason ?: "?"] = (stopReasons[native?.stopReason ?: "?"] ?: 0) + 1
            if (native?.stopReason == "length") repliesAtCap++

            report.appendLine("[TURN ${index + 1}] $input")
            report.appendLine("  context_build=${perf?.contextBuildMillis}ms residency=${perf?.residencyMillis}ms")
            report.appendLine("  prompt_tokens=${native?.promptTokens} tokenize=${native?.tokenizeMicros?.div(1000)}ms eval=${native?.evalMicros?.div(1000)}ms")
            report.appendLine("  reused=${native?.reusedTokens} ratio=${if ((native?.promptTokens ?: 0) > 0) "%.3f".format(native!!.reusedTokens.toDouble() / native.promptTokens) else "n/a"}")
            report.appendLine("  ttft=${wallTtft}ms gen=${native?.generationMicros?.div(1000)}ms generated=${native?.generatedTokens} stop=${native?.stopReason}")
            report.appendLine("  total=${wallTotal}ms tok/s=${perf?.tokensPerSecond}")
            report.appendLine("  applied=$applied rejected=$rejected model_changes=$modelChanges parser=${perf?.parserMillis}ms events=${perf?.eventsMillis}ms persist=${perf?.persistenceMillis}ms")
            report.appendLine("  prompt_has_time=${runtime.previewContext(next, marinette)?.systemPrompt()?.contains("story time:")} memories=${runtime.previewContext(next, marinette)?.memories?.size}")
            report.appendLine("  text_chars=${t.text.length} repeated_gram=${repeatedGram ?: "-"}")
            report.appendLine()

            if (finished == null || failed) {
                report.appendLine("  !! turn did not finish cleanly (failed=$failed)")
            }
            instance = next
        }

        // save / reload
        val reloaded = runtime.loadStory(instance.id)
        val reloadOk = reloaded != null &&
            reloaded.worldState == instance.worldState &&
            reloaded.conversation == instance.conversation

        report.appendLine("PERCENTILES (wall, ms):")
        report.appendLine("  ttft   p50=${p50(ttfts)} p95=${p95(ttfts)}")
        report.appendLine("  total  p50=${p50(totalList)} p95=${p95(totalList)}")
        report.appendLine("  ctx_build p50=${p50(ctxBuildList)} p95=${p95(ctxBuildList)}")
        report.appendLine("  tokenize p50=${p50(tokenizeMicrosList) / 1000} p95=${p95(tokenizeMicrosList) / 1000}")
        report.appendLine("  prompt_eval p50=${p50(evalMicrosList) / 1000} p95=${p95(evalMicrosList) / 1000}")
        report.appendLine("  generation p50=${p50(genMicrosList) / 1000} p95=${p95(genMicrosList) / 1000}")
        report.appendLine("  parser p50=${p50(parseList)} p95=${p95(parseList)}")
        report.appendLine("WARM KV reuse ratio=${if (reusePrompt > 0) "%.3f".format(reuseReused.toDouble() / reusePrompt) else "n/a"} (reused=$reuseReused of $reusePrompt)")
        report.appendLine("STOP REASONS: $stopReasons")
        report.appendLine("ACTIONS parsed=$actionParsed accepted=$actionAccepted")
        report.appendLine("CONSISTENCY: turns_with_rejected_but_changed=$rejectedButChanged mojibake_turns=$mojibake reload_ok=$reloadOk")
        report.appendLine(
            "QUALITY PROXIES: longest_reply_chars=$longestReplyChars repeated_phrase_turns=$repeatedPhraseTurns " +
                "replies_at_cap=$repliesAtCap",
        )
        report.appendLine(
            "NOTE: prose quality (voice, contradiction, sense) needs a human/LLM read; " +
                "the proxies above are mechanical signals only.",
        )

        assertTrue("the model must stay resident", engine.isLoaded())
        assertTrue("no turn may mutate the world without a validated action", rejectedButChanged == 0)
        assertTrue("save/reload must reproduce the same world and transcript", reloadOk)

        val out = File("/tmp/opencode/inference-profile-${gguf.nameWithoutExtension}.txt")
        out.parentFile?.mkdirs()
        out.writeText(report.toString())
        println(report)
    }
}
