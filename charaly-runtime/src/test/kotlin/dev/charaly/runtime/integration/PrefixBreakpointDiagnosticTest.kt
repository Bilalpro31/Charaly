package dev.charaly.runtime.integration

import dev.charaly.runtime.context.ContextBuilder
import dev.charaly.runtime.context.InferenceContext
import dev.charaly.runtime.director.ProposedActionParser
import dev.charaly.runtime.director.SceneDirector
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.TranscriptEntry
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
 * Where consecutive turns' prompts stop being identical - the only thing the KV prefix
 * cache can reuse.
 *
 * The cache reuses a *contiguous* leading token run, so the interesting number is not the
 * prompt size but the offset at which turn N and turn N+1 first differ, and which prompt
 * section owns that offset. This measures that over ten real turns (real `respond()`,
 * real `SceneDirector`, real `ContextBuilder`; only the model's words are scripted) and
 * attributes every break to the section header it happens in or after.
 *
 * It then screens the candidate layouts on the *same* ten turns so the choice of a layout
 * is made on numbers, not on taste. Character-level (no model needed, runs in seconds);
 * the authoritative token-level figures come from `StoryLatencyBenchmarkTest` /
 * `InferenceProfileTest` on real weights (`reused_tokens`, `reuse_ratio`).
 *
 * Nothing here changes production code. This is the measurement behind the Phase 6
 * prompt-layout decision.
 */
class PrefixBreakpointDiagnosticTest {

    private val pack: StoryPack = MiraculousPack.pack
    private val marinette = CharacterId(MiraculousPack.MARINETTE)

    private val reply =
        "«Yavas yavas devam ediyorum.» Sokak lambalari yanip sönerken o, kösenin arkasindaki " +
            "isigin hâlâ durdugunu gördü ve bir an durdu. Sonra yürümeye devam etti; çünkü " +
            "durmak, geride biraktigi konusmayi yeniden baslatacak gibiydi ve bugün bunu " +
            "istemiyordu. Akşam üstü kalabaligi azalmisti, vitrinlerin caminda iki şehir " +
            "yan yana görünüyordu ve hangisinin gerçek olduğunu düşünmemeye calisiyordu."

    /** Section headers in the order the shipped prompt renders them. */
    private val sectionMarkers = listOf(
        "WORLD RULES",
        "YOUR IDENTITY",
        "WHAT YOU KNOW",
        "WORLD FACTS YOU CAN OBSERVE",
        "WHAT YOU REMEMBER",
        "PEOPLE YOU KNOW",
        "WHAT YOU HAVE SEEN AND WORKED OUT",
        "WHAT YOU ARE ON THE HOOK FOR",
        "WHAT OTHERS PROMISED YOU",
        "WHAT JUST HAPPENED",
        "RIGHT NOW",
        "PACE",
        "ACTION PROTOCOL",
    )

    private enum class Layout(val label: String) {
        /** The pre-Phase-6 order: stable + volatile + protocol, then transcript. */
        SHIPPED("legacy order (state block before transcript)"),

        /** Constant protocol text hoisted above the volatile block (same content). */
        PROTOCOL_EARLY("stable + protocol + volatile, then transcript"),

        /** The Phase 6 order: per-turn state rides AFTER the transcript. */
        VOLATILE_AFTER_TRANSCRIPT("stable + protocol, then transcript, then volatile state"),

        /** Legacy layout, but the history window only ever evicts in chunks. */
        SHIPPED_STICKY_WINDOW("legacy layout + chunked history eviction"),

        /** Both changes together. */
        VOLATILE_AFTER_STICKY("volatile after transcript + chunked history eviction"),
    }

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

    /**
     * Chunked ("sticky") history eviction: keep the window anchored to the past instead of
     * sliding one exchange at a time. The visible head only changes once per `window`
     * exchanges, so the shared prompt prefix survives many turns in a row.
     */
    private fun stickyWindowTurns(window: Int, maxTurn: Int): Int {
        if (window <= 0) return 0
        val cutoff = ((maxTurn - window) / window) * window + 1
        return maxTurn - maxOf(cutoff, 1) + 1
    }

    private fun render(
        layout: Layout,
        context: InferenceContext,
        request: InferenceRequest,
    ): String {
        val stable = context.systemPromptStable()
        val volatile = context.systemPromptVolatile()
        val protocol = ProposedActionParser.INSTRUCTIONS
        // `buildRequest` folds the volatile tail onto the turn's own user line (Phase 6). To
        // compare layouts on IDENTICAL inputs this reconstructs the raw message list - same
        // words the transcript holds - and lets each layout place the tail itself.
        val tailSuffix = "\n\n" + volatile.trim()
        val messages = request.messages.joinToString("\n") { message ->
            val label = when (message.role) {
                ChatRole.SYSTEM -> "system"
                ChatRole.USER -> "user"
                ChatRole.ASSISTANT -> message.name ?: "assistant"
            }
            val content = if (tailSuffix.length > 2) {
                message.content.removeSuffix(tailSuffix)
            } else {
                message.content
            }
            "[$label] $content"
        }
        return when (layout) {
            Layout.SHIPPED, Layout.SHIPPED_STICKY_WINDOW ->
                "$stable$volatile\n\n$protocol\n\n$messages"
            Layout.PROTOCOL_EARLY ->
                "$stable\n\n$protocol$volatile\n\n$messages"
            Layout.VOLATILE_AFTER_TRANSCRIPT, Layout.VOLATILE_AFTER_STICKY ->
                "$stable\n\n$protocol\n\n$messages\n\n$volatile"
        }
    }

    /** The section the given offset falls into or immediately after. */
    private fun sectionAt(prompt: String, offset: Int): String {
        var current = "(prompt opening)"
        sectionMarkers.forEach { marker ->
            val at = prompt.indexOf(marker)
            if (at in 0..offset) current = marker
        }
        return current
    }

    private fun commonPrefix(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }

    private data class Sample(
        val turn: Int,
        val chars: Int,
        val reuseRatio: Double,
        val breakOffset: Int,
        val breakSection: String,
    )

    private suspend fun measure(layout: Layout, window: Int, sticky: Boolean): List<Sample> {
        val runtime = CharalyRuntime(
            repository = JsonCharalyRepository(InMemoryCharalyStorage()),
            engine = ScriptedEngine(reply),
        )
        runtime.createPack(pack)
        var instance: StoryInstance = runtime.startStory(
            pack,
            StoryCreationOptions(
                instanceId = StoryInstanceId("breakpoint-${layout.name}-$window"),
                title = "Kirilma",
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

        val samples = mutableListOf<Sample>()
        var previous: String? = null
        repeat(10) { index ->
            val input = "Tur ${index + 1}: devam edelim, kisa yaz."
            val definition = runtime.definitionFor(instance)
            val scene = SceneDirector(definition).directScene(instance, marinette) ?: error("no scene")
            val builder = ContextBuilder(definition, runtime.contextBudgetFor(instance))
            val maxTurn = instance.conversation.entries.maxOfOrNull { it.turn } ?: 0
            val windowNow = if (sticky) stickyWindowTurns(window, maxOf(maxTurn, 1)) else window
            val context = builder.buildContext(instance, scene, marinette, input, transcriptTurns = windowNow)
            val request = builder.buildRequest(context)
            val rendered = render(layout, context, request)

            previous?.let { prev ->
                val offset = commonPrefix(prev, rendered)
                samples += Sample(
                    turn = index + 1,
                    chars = rendered.length,
                    reuseRatio = if (rendered.isEmpty()) 0.0 else offset.toDouble() / rendered.length,
                    breakOffset = offset,
                    breakSection = sectionAt(rendered, offset),
                )
            }
            previous = rendered

            runtime.respond(instance, input, marinette).collect { update ->
                (update as? GenerationUpdate.Finished)?.instance?.let { instance = it }
            }
        }
        return samples
    }

    @Test
    fun `every prompt break is attributed to a section, and the candidate layouts are screened`() =
        runTest {
            val turns = 10
            val report = StringBuilder()
            report.appendLine("PREFIX BREAKPOINT DIAGNOSTIC (10 turns, real runtime, scripted reply)")
            report.appendLine("char-level; authoritative token-level figure: StoryLatencyBenchmarkTest reuse_ratio")
            report.appendLine()

            val runs = Layout.entries.associateWith { layout ->
                val sticky = layout == Layout.SHIPPED_STICKY_WINDOW || layout == Layout.VOLATILE_AFTER_STICKY
                measure(layout, window = 6, sticky = sticky)
            }

            Layout.entries.forEach { layout ->
                val samples = runs.getValue(layout)
                val avg = if (samples.isEmpty()) 0.0 else samples.sumOf { it.reuseRatio } / samples.size
                report.appendLine("${layout.label}")
                report.appendLine("  per-turn reuse=${samples.map { "%.2f".format(it.reuseRatio) }}")
                report.appendLine("  average=%.3f  chars(last)=%d".format(avg, samples.lastOrNull()?.chars ?: 0))
                if (layout == Layout.SHIPPED || layout == Layout.VOLATILE_AFTER_STICKY) {
                    report.appendLine("  break attribution:")
                    samples.forEach { sample ->
                        report.appendLine(
                            "    turn ${sample.turn}: shared ${sample.breakOffset} of ${sample.chars} chars " +
                                "-> first difference in/after \"${sample.breakSection}\"",
                        )
                    }
                }
                report.appendLine()
            }

            val out = File("/tmp/opencode/prefix-breakpoints.txt")
            out.parentFile?.mkdirs()
            out.writeText(report.toString())
            println(report)

            // Guard against a vacuous measurement: nine comparisons per layout, sane ratios.
            runs.forEach { (layout, samples) ->
                assertTrue("${layout}: one comparison per later turn", samples.size == turns - 1)
                assertTrue(
                    "${layout}: ratios must be sane",
                    samples.all { it.reuseRatio in 0.0..1.0 },
                )
            }
        }
}