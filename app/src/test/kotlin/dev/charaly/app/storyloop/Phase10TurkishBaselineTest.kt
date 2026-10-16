package dev.charaly.app.storyloop

import dev.charaly.app.inference.LlamaNative
import dev.charaly.app.inference.LocalLlamaInferenceEngine
import dev.charaly.app.model.LocalModelResidency
import dev.charaly.runtime.context.ContextBuilder
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.StoryTime
import dev.charaly.runtime.domain.TranscriptEntry
import dev.charaly.runtime.domain.TranscriptRole
import dev.charaly.runtime.domain.TranscriptStyle
import dev.charaly.runtime.engine.StoryCreationOptions
import dev.charaly.runtime.inference.ChatRole
import dev.charaly.runtime.inference.InferenceRequest
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * PHASE 10 A — the A/B that decides whether the language lock is the thing that works.
 *
 * ## The comparison
 *
 * Two runs of the same four-turn Turkish conversation, through the same engine, against
 * the same loaded Qwen3-4B weights, over the same growing transcript. **The only
 * difference is one block of system text**: the AFTER run sends the Phase 10 language
 * block, the BEFORE run sends the Phase 9 prompt, reconstructed below by reversing the
 * Phase 10 edit exactly.
 *
 * Reconstructing rather than checking out an old revision is deliberate:
 *
 *  - it is the same binary, the same llama.cpp, the same sampler, the same KV cache - so
 *    no difference can hide in anything except the prompt;
 *  - the transcript fed to both runs is byte-identical, turn for turn, because the AFTER
 *    run's own replies are what the transcript contains. Both variants therefore answer
 *    the identical question about the identical history;
 *  - it is reproducible from this file, so the claim can be re-checked rather than taken
 *    on trust from a log.
 *
 * ## The Phase 9 prompt, exactly
 *
 * ```
 *   "You are X, a character inside a living story world."
 *   <blank>
 *   "WORLD RULES"
 *   ...
 *   "- Reply in Turkish when the user writes in Turkish. Match the user's language;"
 *   "  if the user explicitly asks for another language, switch to that language."
 *   ...
 * ```
 *
 * - no leading LANGUAGE block, no closing "Reply in Turkish." - which is precisely the
 * Phase 9 shape.
 *
 * Run it with:
 * ```
 *   ./gradlew :app:testDebugUnitTest --offline \
 *     -PcharalyNativeLib=... -PcharalyModelFile=... \
 *     -PcharalyPhase10Baseline=true --tests '*Phase10TurkishBaselineTest'
 * ```
 * Without `-PcharalyPhase10Baseline=true` only the AFTER run happens: the A/B doubles the
 * generation cost, and the default offline suite must not quietly turn into a twenty
 * minute model benchmark.
 */
class Phase10TurkishBaselineTest {

    private val pack = MiraculousPack.pack
    private val marinette = CharacterId(MiraculousPack.MARINETTE)

    private val modelFile: File? = System.getProperty("charalyModelFile")
        ?.takeIf { it.isNotBlank() }
        ?.let { File(it) }
        ?.takeIf { it.isFile }

    private val runBaseline = System.getProperty("charalyPhase10Baseline")
        ?.equals("true", ignoreCase = true) == true

    private val script = listOf(
        "Merhaba, nasılsın?",
        "Bugün okulda biraz endişeliyim.",
        "Sence arkadaşlarımla nasıl konuşmalıyım?",
        "Peki yarın ne yapabilirim?",
    )

    @Test
    fun `the language lock, against the Phase 9 prompt it replaces`() = runBlocking {
        // The whole class is opt-in. The A/B doubles the generation cost of an already
        // slow real-weights run, and the four-message Turkish assertion it would otherwise
        // duplicate lives in Phase10TurkishLatencyTest. Without the property this test
        // reports that it skipped rather than passing for having done nothing.
        assumeTrue(
            "the A/B is opt-in (-PcharalyPhase10Baseline=true); " +
                "Phase10TurkishLatencyTest covers the four-message assertion",
            runBaseline,
        )
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
            id = "phase10-baseline-model",
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

        var instance: StoryInstance = runtime.startStory(
            pack,
            StoryCreationOptions(
                instanceId = StoryInstanceId("phase10-baseline"),
                title = "Phase 10 A/B",
                scenario = pack.defaultScenario(),
                focusCharacterId = marinette,
                castCharacterIds = listOf(marinette),
                modelBinding = ModelBinding.from(installed.id, installed.displayName, profile),
                nowEpochMs = 1_700_000_000_000L,
            ),
        )

        val report = StringBuilder()
        report.appendLine("### PHASE 10 A/B - language lock vs the Phase 9 prompt")
        report.appendLine("model            : ${gguf.name}")
        report.appendLine("baseline variant : $runBaseline")
        report.appendLine()
        report.appendLine("BEFORE = Phase 9 prompt (one English line, sixth bullet of WORLD RULES)")
        report.appendLine("AFTER  = Phase 10 prompt (leading LANGUAGE block + closing reminder)")
        report.appendLine("everything else - messages, transcript, sampler, weights - identical")
        report.appendLine()

        val afterReplies = mutableListOf<String>()
        val beforeReplies = mutableListOf<String>()
        val afterTtls = mutableListOf<Long>()
        val beforeTtls = mutableListOf<Long>()

        // Load through the app's own residency path, so the A/B runs through the same
        // loader the player hits. Calling `engine.stream` directly without this throws
        // ModelNotLoaded - the engine is lazily loaded on the first turn for a reason.
        val loaded = residency.ensureResident(
            ModelBinding.from(installed.id, installed.displayName, profile),
        )
        assertTrue("the model could not be loaded for the A/B", loaded)

        script.forEachIndexed { index, userLine ->
            val context = runtime.previewContext(instance, marinette, userLine)
                ?: error("no context could be built for turn ${index + 1}")
            val request = ContextBuilder(
                runtime.definitionFor(instance),
                runtime.contextBudgetFor(instance),
            ).buildRequest(context, runtime.paramsFor(instance))

            val beforeSystem = toPhase9Prompt(request.systemPrompt)
            assertTrue(
                "the Phase 9 reconstruction must not contain the Phase 10 block",
                !beforeSystem.contains("LANGUAGE -"),
            )
            assertTrue(
                "the Phase 9 reconstruction must carry the Phase 9 language line",
                beforeSystem.contains("Reply in Turkish when the user writes in Turkish."),
            )
            assertTrue(
                "the Phase 10 closing reminder must reach the model on the player's own line",
                closingReminderFoldedIntoPlayerLine(request),
            )
            assertFalse(
                "the Phase 9 variant must not carry the closing reminder",
                beforeSystem.trimEnd().endsWith(CLOSING_REMINDER),
            )

            val after = generate(engine, request)
            afterTtls += after.ttftMillis
            afterReplies += after.text

            var before: Reply? = null
            if (runBaseline) {
                before = generate(engine, request.copy(systemPrompt = beforeSystem))
                beforeTtls += before.ttftMillis
                beforeReplies += before.text
            }

            report.appendLine("--- MESAJ ${index + 1}: $userLine")
            report.appendLine("AFTER  : ${after.text}")
            report.appendLine("        lang=${verdict(after.text)}  ttft=${after.ttftMillis}ms  ${after.tokens} tok")
            if (before != null) {
                report.appendLine("BEFORE : ${before.text}")
                report.appendLine("        lang=${verdict(before.text)}  ttft=${before.ttftMillis}ms  ${before.tokens} tok")
            }
            report.appendLine()

            // The transcript grows from the AFTER reply for both variants, so the two runs
            // are answering the same question about the same history on every turn.
            instance = instance.evolved(
                conversation = instance.conversation
                    .append(
                        TranscriptEntry(
                            id = "u${index + 1}",
                            turn = instance.conversation.nextTurn(),
                            role = TranscriptRole.USER,
                            text = userLine,
                            at = StoryTime.START,
                            sceneId = instance.currentSceneId,
                        ),
                    )
                    .append(
                        TranscriptEntry(
                            id = "c${index + 1}",
                            turn = instance.conversation.nextTurn(),
                            role = TranscriptRole.CHARACTER,
                            text = after.text.ifBlank { "(no reply)" },
                            at = StoryTime.START,
                            sceneId = instance.currentSceneId,
                            speakerId = marinette,
                            style = TranscriptStyle.DIALOGUE,
                        ),
                    ),
            )
        }

        report.appendLine("### VERDICTS")
        report.appendLine("AFTER  : ${afterReplies.joinToString(", ") { verdict(it) }}")
        if (runBaseline) {
            report.appendLine("BEFORE : ${beforeReplies.joinToString(", ") { verdict(it) }}")
            report.appendLine("AFTER  ttft: ${afterTtls.joinToString(", ")} ms")
            report.appendLine("BEFORE ttft: ${beforeTtls.joinToString(", ")} ms")
        }

        report.appendLine()
        report.appendLine("### BEFORE VERDICTS (the finding, never raised)")
        report.appendLine("The old prompt's answers are printed above for comparison. An English")
        report.appendLine("answer here is the defect Phase 10 exists to end, not a test failure.")

        val text = report.toString()
        File(REPORT_PATH).writeText(text)
        println(text)

        // Only the AFTER prompt is asserted. Asserting the old one would make the test pass
        // for the wrong reason - it would be green either way, which is exactly how the
        // Phase 9 regression survived.
        afterReplies.forEachIndexed { index, reply ->
            assertTrue(
                "AFTER turn ${index + 1} answered in ${verdict(reply)}, not Turkish:\n$reply",
                verdict(reply).startsWith("TURKISH"),
            )
        }
    }

    // ------------------------------------------------------------------
    // plumbing
    // ------------------------------------------------------------------

    private data class Reply(val text: String, val ttftMillis: Long, val tokens: Int)

    /** One streamed generation, timed from the call to the first token that arrived. */
    private suspend fun generate(
        engine: LocalLlamaInferenceEngine,
        request: InferenceRequest,
    ): Reply {
        val entered = System.nanoTime()
        var first = 0L
        val text = StringBuilder()
        var tokens = 0
        engine.stream(request).collect { chunk ->
            if (!chunk.done) {
                if (first == 0L) first = System.nanoTime()
                text.append(chunk.text)
                tokens++
            }
        }
        val ttft = if (first == 0L) -1L else (first - entered) / 1_000_000L
        return Reply(text.toString(), ttft, tokens)
    }

    /**
     * Rebuilds the Phase 9 system block from the Phase 10 one.
     *
     * Three exact edits, no guessing:
     *
     *  1. delete the leading LANGUAGE block, from its heading up to (not including)
     *     "WORLD RULES" - which is exactly the Phase 9 opening;
     *  2. restore the Phase 9 language line, which Phase 10 replaced;
     *  3. the closing "Reply in Turkish." reminder.
     *
     * ## Why the third edit is conditional
     *
     * That reminder lives in the [dev.charaly.runtime.context.InferenceContext.systemPromptVolatile]
     * tail, and `ContextBuilder.buildRequest` folds that tail into the LAST USER MESSAGE
     * rather than leaving it in the system block. So `request.systemPrompt` ends with the
     * action protocol, not with the reminder, and a reconstruction that required it there
     * would fail against the very prompt it is trying to reproduce.
     *
     * The reminder is therefore stripped when present, and its absence is asserted
     * separately, against the message the tail actually travels in.
     *
     * If any of the unconditional edits stops matching, the test fails loudly rather than
     * quietly A/B-ing two prompts that are not the ones being claimed. The character's name
     * is not assumed anywhere: the block is located by its own heading.
     */
    internal fun toPhase9Prompt(system: String): String {
        val languageAt = system.indexOf(LANGUAGE_HEADING)
        assertTrue(
            "the Phase 10 language block was not found, so there is nothing to reverse",
            languageAt >= 0,
        )
        val rulesAt = system.indexOf("WORLD RULES", languageAt)
        assertTrue("WORLD RULES was not found after the language block", rulesAt > languageAt)

        var out = system.substring(0, languageAt) + system.substring(rulesAt)

        val phase10Line = "- Match the user's language. The LANGUAGE block above is the one that decides it.\n"
        val phase9Line =
            "- Reply in Turkish when the user writes in Turkish. Match the user's language;\n" +
                "  if the user explicitly asks for another language, switch to that language.\n"
        assertTrue("the Phase 10 language line was not found", out.contains(phase10Line))
        out = out.replace(phase10Line, phase9Line)

        val trimmed = out.trimEnd()
        if (trimmed.endsWith(CLOSING_REMINDER)) {
            out = trimmed.removeSuffix(CLOSING_REMINDER).trimEnd() + "\n"
        }

        assertTrue("the Phase 9 language line was not restored", out.contains("Reply in Turkish when the user writes"))
        assertTrue("the Phase 10 block survived the reconstruction", !out.contains(LANGUAGE_HEADING))
        assertTrue("the Phase 10 reminder survived the reconstruction", !out.trimEnd().endsWith(CLOSING_REMINDER))
        return out
    }

    /**
     * The reminder Phase 10 folds into the player's own line, where the volatile tail travels.
     *
     * Asserted on the rendered request's last message rather than on [toPhase9Prompt]'s
     * output for the reason given there.
     */
    internal fun closingReminderFoldedIntoPlayerLine(request: InferenceRequest): Boolean {
        val last = request.messages.lastOrNull() ?: return false
        return last.role == ChatRole.USER && last.content.trimEnd().endsWith(CLOSING_REMINDER)
    }

    /** Turkish / English / unknown, from the reply's own words. */
    private fun verdict(raw: String): String {
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
        const val REPORT_PATH = "/tmp/opencode/phase10_turkish_ab.txt"

        /** The heading of the Phase 10 block; everything from here to WORLD RULES is removed. */
        const val LANGUAGE_HEADING = "LANGUAGE - THIS IS THE FIRST RULE"

        /** The one-line reminder Phase 10 appends to the end of the prompt. */
        const val CLOSING_REMINDER = "Reply in Turkish."

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
