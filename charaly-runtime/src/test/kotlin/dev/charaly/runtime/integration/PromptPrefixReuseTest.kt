package dev.charaly.runtime.integration

import dev.charaly.runtime.context.ContextBuilder
import dev.charaly.runtime.context.ContextBudget
import dev.charaly.runtime.context.InferenceContext
import dev.charaly.runtime.director.SceneDirector
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.engine.StoryCreationOptions
import dev.charaly.runtime.inference.ChatRole
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
import dev.charaly.runtime.persistence.InMemoryCharalyStorage
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
 * The prompt prefix reuse ratio, measured without a model.
 *
 * The model's key/value cache can only reuse a *contiguous prefix*, so the number that
 * decides whether prefix caching pays off is "how many characters at the start of this
 * turn's prompt are identical to the previous turn's". This measures exactly that over ten
 * turns, for the two orderings and the two history-window policies, on the real runtime.
 *
 * It is a character-level proxy for a token-level result; the authoritative token-level
 * figure comes from `StoryLatencyBenchmarkTest` on real weights. Both are reported.
 */
class PromptPrefixReuseTest {

    private val pack = MiraculousPack.pack
    private val marinette = CharacterId(MiraculousPack.MARINETTE)

    private enum class Layout(val label: String) {
        ORIGINAL("original order (memory between world-facts and relationships)"),
        MEMORY_LATE("memory-late in system block (shipped)"),
        VOLATILE_LATE("volatile-late after the transcript"),
    }

    /**
     * Reconstructs the ORIGINAL system-prompt order: the memory block used to sit between
     * `WORLD FACTS YOU CAN OBSERVE` and `PEOPLE YOU KNOW`. The shipped order moved it to the
     * end of the system block; this lets the two be compared on identical state.
     */
    private fun originalSystemPrompt(context: InferenceContext): String {
        val stable = context.systemPromptStable()
        val memoryBlock = run {
            val rendered = context.preRenderedMemories
            if (!rendered.isNullOrEmpty()) return@run rendered
            if (context.memories.isEmpty()) return@run null
            buildString {
                appendLine("WHAT YOU REMEMBER")
                context.memories.forEach { appendLine("- (${it.importance}/5) ${it.content}") }
            }.trimEnd()
        } ?: return stable
        val anchors = listOf(
            "PEOPLE YOU KNOW",
            "WHAT YOU HAVE SEEN AND WORKED OUT",
            "WHAT YOU ARE ON THE HOOK FOR",
            "WHAT OTHERS PROMISED YOU",
        )
        val at = anchors.mapNotNull { marker ->
            stable.indexOf(marker).takeIf { it >= 0 }
        }.minOrNull() ?: return stable.trimEnd() + "\n\n" + memoryBlock + "\n"
        return stable.substring(0, at) + memoryBlock + "\n\n" + stable.substring(at)
    }

    private val reply =
        "«On continue ensemble, doucement, sans rien precipiter.» Elle regarda la rue un instant, " +
            "puis se remit a marcher vers le coin de la rue ou la lumiere tombait encore. " +
            "Les vitrines etaient allumees, et le reflet des enseignes glissait sur l'asphalte mouille " +
            "comme une seconde ville posee sous la premiere. Elle pensa a tout ce qui avait change " +
            "depuis le matin, et a tout ce qui n'avait pas change du tout, et cette pensee la rassura " +
            "plus qu'elle ne l'aurait cru. Un chien aboya quelque part derriere une grille, puis se tut. " +
            "La nuit tombait avec cette douceur particuliere des soirs de printemps, quand il fait encore " +
            "assez clair pour lire les visages mais plus assez pour lire les enseignes, et elle ralentit " +
            "le pas sans savoir pourquoi, simplement parce que marcher vite aurait donne l'impression " +
            "de fuir quelque chose, et elle ne fuyait rien du tout, pas ce soir, pas encore, pas comme ca."

    private class ScriptedEngine(private val text: String) : InferenceEngine {
        override suspend fun loadModel(request: ModelLoadRequest): LoadOutcome =
            LoadOutcome.Loaded(ModelInfo(request.installedModelId, request.path, "scripted"))

        override suspend fun unloadModel() = Unit
        override suspend fun generate(request: InferenceRequest) = InferenceResult(text)
        override fun stream(request: InferenceRequest): Flow<StreamChunk> = flow {
            text.chunked(32).forEach { emit(StreamChunk(text = it)) }
            emit(StreamChunk("", done = true))
        }
        override fun stop() = Unit
        override fun isLoaded() = true
        override fun modelInfo() = ModelInfo("scripted", "/m.gguf", "scripted")
    }

    private fun serialize(
        layout: Layout,
        context: InferenceContext,
        request: InferenceRequest,
    ): String {
        val head = when (layout) {
            Layout.ORIGINAL -> originalSystemPrompt(context)
            Layout.MEMORY_LATE -> context.systemPrompt()
            Layout.VOLATILE_LATE -> context.systemPromptStable()
        }
        val tail = when (layout) {
            Layout.VOLATILE_LATE -> context.systemPromptVolatile()
            else -> ""
        }
        return buildString {
            append(head.trim()).append('\n').append('\n')
            request.messages.forEach { message ->
                val label = when (message.role) {
                    ChatRole.SYSTEM -> "system"
                    ChatRole.USER -> "user"
                    ChatRole.ASSISTANT -> message.name ?: "assistant"
                }
                append("[$label] ${message.content}\n")
            }
            if (tail.isNotBlank()) append(tail.trim()).append('\n')
            append("[assistant]")
        }
    }

    private fun commonPrefixRatio(a: String, b: String): Double {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return if (b.isEmpty()) 0.0 else i.toDouble() / b.length.toDouble()
    }

    private suspend fun measure(layout: Layout, window: Int, turns: Int): List<Double> {
        val runtime = CharalyRuntime(
            repository = JsonCharalyRepository(InMemoryCharalyStorage()),
            engine = ScriptedEngine(reply),
        )
        runtime.createPack(pack)
        var instance: StoryInstance = runtime.startStory(
            pack,
            StoryCreationOptions(
                instanceId = StoryInstanceId("prefix-${layout.name}-$window"),
                title = "Prefix",
                scenario = pack.defaultScenario(),
                focusCharacterId = marinette,
                castCharacterIds = listOf(marinette),
                modelBinding = ModelBinding.from(
                    installedModelId = "scripted",
                    modelDisplayName = "Scripted",
                    profile = ModelProfileLibrary.all.first(),
                ),
                nowEpochMs = 1_700_000_000_000L,
            ),
        )

        val ratios = mutableListOf<Double>()
        var previous: String? = null
        repeat(turns) { index ->
            val input = "Tur ${index + 1}: devam edelim, biraz daha anlat."
            val definition = runtime.definitionFor(instance)
            val scene = SceneDirector(definition).directScene(instance, marinette)
                ?: error("no scene")
            val builder = ContextBuilder(definition, runtime.contextBudgetFor(instance))
            val context = builder.buildContext(instance, scene, marinette, input, transcriptTurns = window)
            val request = builder.buildRequest(context)
            val rendered = serialize(layout, context, request)
            previous?.let { ratios += commonPrefixRatio(it, rendered) }
            previous = rendered

            runtime.respond(instance, input, marinette).collect { update ->
                (update as? GenerationUpdate.Finished)?.instance?.let { instance = it }
            }
        }
        return ratios
    }

    @Test
    fun `prefix reuse depends on the history window more than on the section order`() = runTest {
        val turns = 10
        val report = StringBuilder()
        report.appendLine("CHARACTER-LEVEL PREFIX REUSE RATIO (10 turns, real runtime, scripted reply)")
        report.appendLine()

        val combos = listOf(
            Triple(Layout.ORIGINAL, 6, "original order + sliding window (before)"),
            Triple(Layout.MEMORY_LATE, 6, "memory-late in system + sliding window (proposed)"),
            Triple(Layout.VOLATILE_LATE, 6, "volatile-late after transcript + sliding window"),
            Triple(Layout.MEMORY_LATE, 40, "memory-late in system + append-only window"),
            Triple(Layout.VOLATILE_LATE, 40, "volatile-late after transcript + append-only window"),
        )
        combos.forEach { (layout, window, label) ->
            val ratios = measure(layout, window, turns)
            val avg = if (ratios.isEmpty()) 0.0 else ratios.average()
            report.appendLine("$label")
            report.appendLine("  per-turn=${ratios.map { "%.2f".format(it) }}")
            report.appendLine("  average=${"%.3f".format(avg)}")
            report.appendLine()
        }

        val out = File("/tmp/opencode/prompt-prefix-reuse.txt")
        out.parentFile?.mkdirs()
        out.writeText(report.toString())
        println(report)

        assertTrue("the measurement must cover the turns", turns == 10)
    }
}
