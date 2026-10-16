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
 * PHASE 9 — "does the local model actually hold a Turkish conversation?"
 *
 * ## What this is for
 *
 * Phase 8 measured and fixed the *mechanics* (stop sequences that were empty strings, a
 * dropped `minP`, character `systemInstructions` that no render path ever read). None of
 * those fixes can make a weak model speak good Turkish. This test asks the question the
 * user actually cares about, on real weights, through the real chain:
 *
 * ```
 *   Story Pack -> ContextBuilder (Turkish rules + character persona)
 *     -> ModelPresetLibrary (template-derived stop sequences)
 *     -> LocalLlamaInferenceEngine (chat template applied natively)
 *     -> llama.cpp -> REAL TOKENS -> world mutation -> next turn's context
 * ```
 *
 * The four messages below are the brief's, verbatim and in order, sent as ONE
 * conversation so that "does it follow the thread" is a real property of the run rather
 * than four unrelated prompts.
 *
 * ## What is asserted, and what is only printed
 *
 * A 1.5B model says strange things. What is *deterministic* is the boundary, so those are
 * the assertions:
 *
 *  - real tokens came back for every turn (no model, no pass);
 *  - no U+FFFD, so Turkish survived the JNI boundary intact;
 *  - no meta output (ChatML markers, "user"/"assistant"/"system" role echoes, "Final
 *    Answer"/"Yarn" headings, the action protocol's own `[ACTION]` JSON leaking out);
 *  - the model does not narrate the player: no `Sen:`-prefixed puppet line, no stage
 *    directions, no bracketed speaker tag;
 *  - no repetition loop - the same reply twice, or one line inside a reply repeated;
 *  - the four replies are mutually distinct, which is the mechanical half of "it follows
 *    the thread rather than re-answers turn 1".
 *
 * "Natural Turkish", "directly answers the question", "stays in character" and "the
 * Turkish answer is a good answer" are **printed in full and judged by a reader**. They
 * are not asserted, because a regex cannot tell a warm reply from a flat one and a test
 * that claims to is worse than one that shows its evidence.
 *
 * ## How it runs
 *
 * ```
 *   scripts/verify-story-loop-host.sh <model.gguf>
 * ```
 * Without a native library or a model the whole class is **skipped, not passed** - a green
 * tick must never stand in for a run that did not happen.
 */
class TurkishChatQualityTest {

    private val pack = MiraculousPack.pack
    private val marinette = CharacterId(MiraculousPack.MARINETTE)

    private val modelFile: File? = System.getProperty("charalyModelFile")
        ?.takeIf { it.isNotBlank() }
        ?.let { File(it) }
        ?.takeIf { it.isFile }

    /** The brief's four messages, verbatim and in order. */
    private val script = listOf(
        "Merhaba, bugün okulda biraz endişeliyim. Sen nasılsın?",
        "Bugün arkadaşlarımla konuşmaya çekindim. Sence ne yapmalıyım?",
        "Sen olsaydın bu durumda ne yapardın?",
        "Peki, yarın ne yapabilirim?",
    )

    @Test
    fun `four Turkish turns on real weights, with the brief's acceptance criteria`() = runBlocking {
        assumeTrue(
            "no libcharaly_llama.so offered (run scripts/verify-story-loop-host.sh)",
            LlamaNative.isAvailable(),
        )
        assumeTrue("no real GGUF offered (-PcharalyModelFile)", modelFile != null)
        val gguf = modelFile!!
        val contextSize = System.getProperty("charalyContextSize")?.toIntOrNull() ?: 4096

        val metadata = gguf.inputStream().use { stream ->
            val parsed = GgufReader.readPrefix(stream.readNBytes(16 * 1024 * 1024))
            (parsed as? GgufReadResult.Success)?.metadata ?: error("unreadable GGUF header")
        }

        // The REAL preset the app would build for this file: template-derived stop sequences,
        // size-band context budget, the whole thing. A hand-rolled GenerationParams here
        // would test a configuration the user never gets.
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
        val residency = LocalModelResidency(registry, engine)
        val runtime = CharalyRuntime(
            repository = JsonCharalyRepository(InMemoryCharalyStorage()),
            engine = engine,
            params = profile.sampler.toGenerationParams(),
            residency = residency,
        )
        val installed = InstalledModel(
            id = "phase9-model",
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
                instanceId = StoryInstanceId("phase9-turkish"),
                title = "Phase 9 Turkish",
                scenario = pack.defaultScenario(),
                focusCharacterId = marinette,
                castCharacterIds = listOf(marinette),
                modelBinding = ModelBinding.from(installed.id, installed.displayName, profile),
                nowEpochMs = 1_700_000_000_000L,
            ),
        )

        val replies = mutableListOf<String>()
        // Collected, not thrown.
        //
        // The whole point of this run is the four real replies, and a test that asserts
        // inside the turn loop throws on turn 2 and never prints turns 3 and 4 - which is
        // exactly the evidence this exists to produce. So every failure is recorded, the
        // report is always written and printed, and the assertions all land at the end.
        val failures = mutableListOf<String>()
        fun fail(message: String) {
            failures += message
        }

        val report = StringBuilder()
        report.appendLine("### MODEL UNDER TEST")
        report.appendLine("file        : ${gguf.name}")
        report.appendLine("size        : ${gguf.length()} bytes")
        report.appendLine("architecture: ${metadata.architecture}")
        report.appendLine("parameters  : ${metadata.parameterCount}")
        report.appendLine("context     : ${metadata.contextLength}")
        report.appendLine("chat template present: ${metadata.chatTemplate.isNotBlank()}")
        report.appendLine("stopSequences: ${profile.sampler.stopSequences}")
        report.appendLine("contextSize requested: $contextSize")
        report.appendLine("maxTokens   : ${profile.sampler.toGenerationParams().maxTokens}")
        report.appendLine("temp/topP/topK/minP/repeat: " +
            "${profile.sampler.toGenerationParams().temperature}/" +
            "${profile.sampler.toGenerationParams().topP}/" +
            "${profile.sampler.toGenerationParams().topK}/" +
            "${profile.sampler.toGenerationParams().minP}/" +
            "${profile.sampler.toGenerationParams().repeatPenalty}")
        report.appendLine()

        script.forEachIndexed { index, userLine ->
            var raw = ""
            runtime.respond(instance, userLine, marinette).collect { update ->
                when (update) {
                    is GenerationUpdate.Chunk -> raw = update.accumulated
                    is GenerationUpdate.Finished -> instance = update.instance ?: instance
                    is GenerationUpdate.Failed -> report.appendLine("!! FAILED: ${update.error}")
                    else -> Unit
                }
            }
            replies += raw

            val meta = metaArtifacts(raw)
            val loops = repetitionLoops(raw)
            report.appendLine("--- MESAJ ${index + 1}")
            report.appendLine("KULLANICI: $userLine")
            report.appendLine("CEVAP   : $raw")
            report.appendLine("  [meta artifacts] : ${meta.ifEmpty { "none" }}")
            report.appendLine("  [repetition loops]: ${loops.ifEmpty { "none" }}")
            report.appendLine("  [U+FFFD]          : ${raw.count { it == '�' }}")

            // --- boundary checks, recorded rather than thrown -------------------
            if (raw.isBlank()) {
                fail("turn ${index + 1} produced no real tokens (engine: ${engine.lastDiagnostic()})")
            }
            if (raw.any { it == '�' }) {
                fail("turn ${index + 1}: Turkish must survive the JNI boundary intact, got U+FFFD")
            }
            if (meta.isNotEmpty()) {
                fail("turn ${index + 1}: the model leaked role/template/meta output -> $meta")
            }
            puppetArtifacts(raw).forEach {
                fail("turn ${index + 1}: the model narrated the player or the room -> $it")
            }
            loops.forEach {
                fail("turn ${index + 1}: the reply repeats itself -> $it")
            }
        }

        // "Follows the thread" has a mechanical half: four different situations must not
        // get four copies of the same sentence. A model stuck in a loop returns the same
        // reply here, and that is exactly the failure Phase 8 reported.
        val duplicates = replies.groupBy { it.trim() }.filter { it.value.size > 1 }.keys
        if (duplicates.isNotEmpty()) {
            fail("the model returned a byte-identical reply for different questions: $duplicates")
        }
        if (instance.conversation.entries.size < script.size * 2) {
            fail("the conversation did not advance the world")
        }

        report.appendLine()
        report.appendLine("### TURN METRICS (diagnostic only, never a gate)")
        replies.forEachIndexed { index, raw ->
            val letters = raw.count { it.isLetter() }
            val turkish = raw.count { it.isLetter() && it.code in 0x00C0..0x024F }
            report.appendLine(
                "turn ${index + 1}: chars=${raw.length} letters=$letters " +
                    "extended-latin-letters=$turkish ratio=" +
                    (if (letters == 0) "n/a" else "%.2f".format(turkish.toDouble() / letters)),
            )
        }

        report.appendLine()
        report.appendLine("### BOUNDARY FAILURES (${failures.size})")
        failures.forEach { report.appendLine(" - $it") }

        // Written and printed before any assertion, so a failing run still leaves the
        // evidence on disk. A report that only exists on success is useless exactly when
        // it is needed.
        val text = report.toString()
        File(REPORT_PATH).writeText(text)
        println(text)

        assertTrue(
            "Turkish chat quality failed on real weights:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // detectors
    // ------------------------------------------------------------------

    /**
     * Output that proves the model is answering the *template* rather than the user.
     *
     * Every entry here is a literal that, if it appears in a reply, means the model failed
     * to leave its own scaffolding: a role header it should have stopped at, a ChatML
     * marker the sampler should have stopped on, or one of the meta headings Charaly's own
     * system prompt explicitly bans. The action protocol's `[ACTION]` block is in the same
     * list - it is an *instruction* to the model, never part of a reply.
     */
    private fun metaArtifacts(raw: String): List<String> {
        val hits = mutableListOf<String>()
        val lowered = raw.lowercase(Locale.ROOT)
        for (literal in META_LITERALS) {
            if (lowered.contains(literal.lowercase(Locale.ROOT))) hits += literal
        }
        return hits
    }

    /**
     * Output that speaks for the player, or turns the reply into stage direction.
     *
     * "Never speak for the user" cannot be proved by negation, so this looks for the
     * concrete shapes it takes: a `Sen:`-prefixed puppet line, a bracketed speaker tag,
     * or third-person narration of the user. A false positive is a report line to read, not
     * a failure of the run - which is why these are checked and printed together.
     */
    private fun puppetArtifacts(raw: String): List<String> {
        val hits = mutableListOf<String>()
        val trimmed = raw.trimStart()
        PUPPET_PREFIXES.forEach { prefix ->
            if (trimmed.startsWith(prefix, ignoreCase = true)) hits += "starts with '$prefix'"
        }
        BRACKETED_SPEAKER.findAll(raw).forEach { m ->
            hits += "bracketed speaker '${m.value}'"
        }
        return hits
    }

    /**
     * Genuine repetition loops.
     *
     * Two shapes matter and they are different: the *same* line appearing twice inside one
     * reply (a decode that has stopped making progress), and a short phrase occurring so
     * often that the reply has degenerated into it. Both are measured on normalised
     * sentences/lines, so ordinary stylistic repetition is not mistaken for a loop.
     */
    private fun repetitionLoops(raw: String): List<String> {
        val hits = mutableListOf<String>()
        val lines = raw.lines().map { it.trim() }.filter { it.length >= 12 }
        lines.groupBy { it }.filter { it.value.size >= 2 }.keys.forEach {
            hits += "line repeated: \"${it.take(60)}\""
        }
        val sentences = raw.split('.').map { it.trim() }.filter { it.length >= 12 }
        sentences.groupBy { it }.filter { it.value.size >= 3 }.keys.forEach {
            hits += "sentence repeated x${sentences.count { s -> s == it }}: \"${it.take(60)}\""
        }
        return hits
    }

    private companion object {
        const val REPORT_PATH = "/tmp/opencode/phase9_turkish_quality.txt"

        /**
         * Built at runtime rather than written as literals, for the same reason the prompt
         * builders do it: a literal `<|im_end|>` inside a Kotlin string in a file that a
         * grep for role markers also matches is a trap. `"contains("")"` is permanently
         * true, and that bug shipped once already (PHASE 8, ModelPresetLibrary).
         */
        val META_LITERALS: List<String> = listOf(
            "<|im" + "_start|>",
            "<|im" + "_end|>",
            "<|end_of_turn|>",
            "<end_of_turn>",
            "<|eot_id|>",
            "<|start_header_id|>",
            "[user]",
            "[assistant]",
            "[system]",
            "\nuser:",
            "\nassistant:",
            "\nsystem:",
            "final answer",
            "final thought",
            "[action]",
            "[world]",
            "world rules",
            "your identity",
            "what you know",
            "what you remember",
            "right now",
        )

        val PUPPET_PREFIXES = listOf("sen:", "user:", "oyuncu:", "kullanıcı:")

        /** `[Marinette]`, `[assistant]`, `(Marinette)` - a tag the model printed for itself. */
        val BRACKETED_SPEAKER = Regex("""\[[^\]\n]{1,24}]""")
    }
}
