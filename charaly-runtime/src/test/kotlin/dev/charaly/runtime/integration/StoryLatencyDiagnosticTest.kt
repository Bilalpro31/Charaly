package dev.charaly.runtime.integration

import dev.charaly.runtime.context.ContextBudget
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.engine.StoryCreationOptions
import dev.charaly.runtime.inference.GenerationParams
import dev.charaly.runtime.inference.InferenceEngine
import dev.charaly.runtime.inference.InferenceRequest
import dev.charaly.runtime.inference.InferenceResult
import dev.charaly.runtime.inference.LoadOutcome
import dev.charaly.runtime.inference.ModelInfo
import dev.charaly.runtime.inference.ModelLoadRequest
import dev.charaly.runtime.inference.StreamChunk
import dev.charaly.runtime.model.ModelBinding
import dev.charaly.runtime.model.ModelProfileLibrary
import dev.charaly.runtime.pack.MiraculousPack
import dev.charaly.runtime.persistence.CharalyStorage
import dev.charaly.runtime.persistence.JsonCharalyRepository
import dev.charaly.runtime.session.CharalyRuntime
import dev.charaly.runtime.session.GenerationUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The non-inference half of the latency question, measured on a plain JVM.
 *
 * A device benchmark answers "how fast did the model run". This answers the other half:
 * how much prompt the model is handed, whether that prompt grows without bound, and how
 * much per-turn work the runtime adds around inference. Those are all deterministic and
 * measurable without a GGUF, so they stay measurable in CI.
 *
 * The report is written to `/tmp/opencode/story-latency-diagnostic.txt`.
 */
class StoryLatencyDiagnosticTest {

    private val pack = MiraculousPack.pack
    private val marinette = CharacterId(MiraculousPack.MARINETTE)

    /** Counts real document writes, so "how many saves per turn" is a measurement. */
    private class CountingStorage : CharalyStorage {
        private val files = linkedMapOf<String, String>()
        val writes = mutableListOf<String>()

        override suspend fun read(name: String): String? = files[name]

        override suspend fun write(name: String, contents: String) {
            writes += name
            files[name] = contents
        }

        override suspend fun delete(name: String) {
            files.remove(name)
        }

        override suspend fun list(prefix: String): List<String> =
            files.keys.filter { it.startsWith(prefix) }.sorted()
    }

    /** Already resident; this test is about the runtime around inference, not the model. */
    private class ResidentEngine(private val reply: String) : InferenceEngine {
        override suspend fun loadModel(request: ModelLoadRequest): LoadOutcome =
            LoadOutcome.Loaded(ModelInfo(id = request.installedModelId, path = request.path, displayName = "resident"))

        override suspend fun unloadModel() = Unit
        override suspend fun generate(request: InferenceRequest): InferenceResult = InferenceResult(reply)
        override fun stream(request: InferenceRequest): Flow<StreamChunk> = flow {
            reply.chunked(32).forEach { emit(StreamChunk(text = it, done = false)) }
            emit(StreamChunk(text = "", done = true))
        }
        override fun stop() = Unit
        override fun isLoaded(): Boolean = true
        override fun modelInfo(): ModelInfo = ModelInfo("resident", "/m.gguf", "Resident")
    }

    @Test
    fun `the prompt is measured, bounded, and grows by turns not by history`() = runTest {
        val storage = CountingStorage()
        val runtime = CharalyRuntime(
            repository = JsonCharalyRepository(storage),
            engine = ResidentEngine(
                "«On continue ensemble.» " +
                    "<charaly:action type=\"relate\" character=\"marinette\" target=\"alya\" value=\"trust:2\" reason=\"x\"/>",
            ),
            params = GenerationParams(maxTokens = 256),
        )
        runtime.createPack(pack)
        var instance: StoryInstance = runtime.startStory(
            pack,
            StoryCreationOptions(
                instanceId = StoryInstanceId("latency-diag"),
                title = "Diagnostic",
                scenario = pack.defaultScenario(),
                focusCharacterId = marinette,
                castCharacterIds = listOf(marinette),
                modelBinding = ModelBinding.from(
                    installedModelId = "local-diag",
                    modelDisplayName = "Diag",
                    profile = ModelProfileLibrary.all.first(),
                ),
                nowEpochMs = 1_700_000_000_000L,
            ),
        )

        val report = StringBuilder()
        report.appendLine("CONTEXT BUDGET: $ContextBudget.DEFAULT")
        report.appendLine()
        var peakChars = 0
        val firstTurnChars: MutableMap<String, Int> = mutableMapOf()

        repeat(10) { turn ->
            val input = "Tur ${turn + 1}: devam edelim, biraz daha anlat."
            val before = instance

            // Context size for this turn, section by section (real rendered sizes).
            val context = runtime.previewContext(before, marinette, input)
                ?: error("no context for a story with a focus character")
            val sections = runtime.contextSections(before, marinette)
            val totalChars = context.estimatedChars()
            if (turn == 0) sections.forEach { firstTurnChars[it.id] = it.chars }

            // How long prompt assembly (no inference) actually takes, averaged over runs.
            val buildStart = System.nanoTime()
            repeat(20) { runtime.previewContext(before, marinette, input) }
            val buildMicros = (System.nanoTime() - buildStart) / 20 / 1_000L

            val writesBefore = storage.writes.size
            val updates = mutableListOf<GenerationUpdate>()
            runtime.respond(before, input, marinette).collect { update ->
                updates += update
                (update as? GenerationUpdate.Finished)?.instance?.let { instance = it }
            }
            val writesThisTurn = storage.writes.size - writesBefore

            peakChars = maxOf(peakChars, totalChars)
            report.appendLine(
                "turn ${turn + 1}: chars=$totalChars build=${buildMicros}us " +
                    "system=${context.systemPrompt().length} " +
                    "transcriptEntries=${context.recentTranscript.size} " +
                    "memories=${context.memories.size} knowledge=${context.knowledge.size} " +
                    "saves=$writesThisTurn",
            )
            assertTrue("turn ${turn + 1} must finish", updates.any { it is GenerationUpdate.Finished })
        }

        report.appendLine()
        report.appendLine("FIRST TURN SECTION SIZES:")
        firstTurnChars.toSortedMap().forEach { (id, chars) -> report.appendLine("  $id=$chars") }
        report.appendLine()
        report.appendLine("peak_chars=$peakChars  budget=${ContextBudget.DEFAULT.maxChars}")

        val out = File("/tmp/opencode/story-latency-diagnostic.txt")
        out.parentFile?.mkdirs()
        out.writeText(report.toString())
        println(report)

        // The transcript is a bounded window (budget.transcriptTurns), so the prompt
        // plateaus instead of growing with the length of the story.
        assertTrue(
            "the prompt must stay bounded as the story grows (peak=$peakChars)",
            peakChars <= ContextBudget.DEFAULT.maxChars * 2,
        )
        assertTrue(
            "the transcript window must be capped",
            runtime.previewContext(instance, marinette)!!.recentTranscript.size <= ContextBudget.DEFAULT.transcriptTurns,
        )
    }
}
