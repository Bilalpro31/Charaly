package dev.charaly.app.storyloop

import dev.charaly.app.inference.LlamaNative
import dev.charaly.app.inference.LocalLlamaInferenceEngine
import dev.charaly.app.model.LocalModelResidency
import dev.charaly.runtime.context.ContextBuilder
import dev.charaly.runtime.context.ReplyLanguage
import dev.charaly.runtime.director.SceneDirector
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.TranscriptRole
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
import dev.charaly.runtime.session.TurnPerf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * PHASE 10 — the Turkish language lock and the five-phase latency breakdown, on real
 * Qwen3-4B weights through the real chain.
 *
 * ```
 *   MiraculousPack -> StoryInstance -> WorldState -> SceneDirector -> ContextBuilder
 *     -> ModelPresetLibrary -> LocalLlamaInferenceEngine -> llama.cpp -> REAL TOKENS
 *     -> ProposedActionParser -> EventEngine -> memory/clock -> next turn's context
 * ```
 *
 * ## What is asserted
 *
 * 1. **Every reply is Turkish.** Not "looks Turkish" - the English share of the reply's
 *    words has to be below [MAX_ENGLISH_WORD_SHARE], and a reply with no Turkish evidence
 *    at all fails. An English answer is a FAILURE of this test, never a pass with a note.
 * 2. **The reply is about the question.** A reply that re-answers an earlier turn verbatim,
 *    or that starts by echoing the player's own line back as a puppet line, fails.
 * 3. **No special token, role header or U+FFFD reaches the reader.**
 * 4. **The world advanced.** The transcript grew by two entries per turn and the turn
 *    reported a real [TurnPerf] with a non-null first-token time.
 * 5. **The five latency phases are all measured.** A phase that could not be measured is
 *    printed as `?` and counted, never silently reported as fast.
 *
 * ## What is only printed
 *
 * Whether an answer is a *good* answer is a reader's judgement, and the full reply text is
 * always written to the report so that judgement can actually be made.
 *
 * ## How it runs
 *
 * ```
 *   scripts/verify-story-loop-host.sh <model.gguf>
 * ```
 * Without a native library or a model the whole class is **skipped, not passed**.
 */
class Phase10TurkishLatencyTest {

    private val pack = MiraculousPack.pack
    private val marinette = CharacterId(MiraculousPack.MARINETTE)

    private val modelFile: File? = System.getProperty("charalyModelFile")
        ?.takeIf { it.isNotBlank() }
        ?.let { File(it) }
        ?.takeIf { it.isFile }

    /** The brief's four messages, verbatim and in order, as ONE conversation. */
    private val script = listOf(
        "Merhaba, nasılsın?",
        "Bugün okulda biraz endişeliyim.",
        "Sence arkadaşlarımla nasıl konuşmalıyım?",
        "Peki yarın ne yapabilirim?",
    )

    @Test
    fun `four Turkish turns on real weights, with the five-phase latency breakdown`() = runBlocking {
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

        val bundle = ModelPresetLibrary.build(
            metadata = metadata,
            displayName = metadata.name,
            absolutePath = gguf.absolutePath,
            fileSizeBytes = gguf.length(),
            availableRamBytes = 8_000_000_000L,
        )
        val profile = ModelPresetLibrary.toProfile(bundle)

        val inner = LocalLlamaInferenceEngine(
            LocalLlamaInferenceEngine.EngineConfig(contextSize = contextSize, threads = 0),
        )
        val probe = Phase10TurnProbe(inner)
        val registry = JsonModelRegistry(InMemoryCharalyStorage())
        val residency = LocalModelResidency(registry, inner)
        val runtime = CharalyRuntime(
            repository = JsonCharalyRepository(InMemoryCharalyStorage()),
            engine = probe,
            params = profile.sampler.toGenerationParams(),
            residency = residency,
        )
        val installed = InstalledModel(
            id = "phase10-model",
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
                instanceId = StoryInstanceId("phase10-turkish-latency"),
                title = "Phase 10 Turkish + Latency",
                scenario = pack.defaultScenario(),
                focusCharacterId = marinette,
                castCharacterIds = listOf(marinette),
                modelBinding = ModelBinding.from(installed.id, installed.displayName, profile),
                nowEpochMs = 1_700_000_000_000L,
            ),
        )

        val report = StringBuilder()
        val failures = mutableListOf<String>()
        fun fail(message: String) { failures += message }

        report.appendLine("### MODEL UNDER TEST")
        report.appendLine("file          : ${gguf.name}")
        report.appendLine("size          : ${gguf.length()} bytes")
        report.appendLine("architecture  : ${metadata.architecture}")
        report.appendLine("parameters    : ${metadata.parameterCount}")
        report.appendLine("train context : ${metadata.contextLength}")
        report.appendLine("chat template : ${if (metadata.chatTemplate.isBlank()) "absent" else "present"}")
        report.appendLine("context asked : $contextSize")
        report.appendLine("stopSequences : ${profile.sampler.stopSequences}")
        report.appendLine("sampler       : t=${profile.sampler.temperature} p=${profile.sampler.topP} " +
            "k=${profile.sampler.topK} minP=${profile.sampler.minP} rp=${profile.sampler.repeatPenalty} " +
            "max=${profile.sampler.maxTokens}")
        report.appendLine("threads       : ${inner.configThreads()} (0 = llama.cpp chose)")

        // The prompt as it stands BEFORE the first turn, in full. This is the evidence
        // for "the language rule reaches the model", and it has to be dumped rather than
        // asserted because the claim is about text a regex cannot judge.
        report.appendLine()
        report.appendLine("### EXACT PROMPT, TURN 1 (as the engine would hand it to llama.cpp)")
        val firstContext = runtime.previewContext(instance, marinette, script.first())
        val firstRequest = firstContext?.let {
            ContextBuilder(runtime.definitionFor(instance), runtime.contextBudgetFor(instance))
                .buildRequest(it, runtime.paramsFor(instance))
        }
        if (firstRequest != null) {
            report.appendLine("--- systemPrompt (${firstRequest.systemPrompt.length} chars) ---")
            report.appendLine(firstRequest.systemPrompt)
            report.appendLine("--- messages (${firstRequest.messages.size}) ---")
            firstRequest.messages.forEach { m ->
                report.appendLine("[${m.role}] ${m.content}")
            }
            val systemPromptChars = firstRequest.systemPrompt.length
            val turkishChars = Regex("[çğıİöşüÇĞİÖŞÜ]").findAll(firstRequest.systemPrompt).count()
            report.appendLine(
                "--- system prompt: $systemPromptChars chars, " +
                    "$turkishChars of them Turkish letters " +
                    "(english share of the prompt: ${englishShare(firstRequest.systemPrompt)}) ---",
            )
        } else {
            report.appendLine("(no context could be previewed)")
        }

        val replies = mutableListOf<String>()
        val timings = mutableListOf<Phase10Timing>()
        val verdicts = mutableListOf<String>()

        report.appendLine()
        report.appendLine("### TURNS")

        script.forEachIndexed { index, userLine ->
            probe.reset()
            var raw = ""
            var perf: TurnPerf? = null
            val entriesBefore = instance.conversation.entries.size

            val sendAt = System.nanoTime()
            var finishedAt = 0L
            runtime.respond(instance, userLine, marinette).collect { update ->
                when (update) {
                    is GenerationUpdate.Chunk -> raw = update.accumulated
                    is GenerationUpdate.Finished -> {
                        instance = update.instance ?: instance
                        perf = update.perf
                        finishedAt = System.nanoTime()
                    }
                    is GenerationUpdate.Failed -> report.appendLine("!! FAILED: ${update.error}")
                    else -> Unit
                }
            }
            if (finishedAt == 0L) finishedAt = System.nanoTime()
            replies += raw

            val timing = Phase10Timing.of(index + 1, sendAt, probe, perf, finishedAt)
            timings += timing

            val language = languageOf(raw)
            val meta = metaArtifacts(raw)
            val special = specialTokens(raw)
            verdicts += language.label

            report.appendLine("--- MESAJ ${index + 1}")
            report.appendLine("KULLANICI : $userLine")
            report.appendLine("CEVAP    : $raw")
            report.appendLine("DİL      : ${language.label} (english word share=${"%.2f".format(Locale.US, language.englishShare)})")
            report.appendLine("  [meta artifacts] : ${meta.ifEmpty { "none" }}")
            report.appendLine("  [special tokens] : ${special.ifEmpty { "none" }}")
            report.appendLine("  [mangled tokens] : ${mangledMarkers(raw).ifEmpty { "none" }}")
            report.appendLine("  [U+FFFD]         : ${raw.count { it == '�' }}")
            report.appendLine(timing.row().trimEnd())
            report.appendLine()

            // ---- assertions, recorded rather than thrown --------------------
            if (raw.isBlank()) {
                fail("turn ${index + 1} produced no tokens at all (engine: ${inner.lastDiagnostic()})")
                return@forEachIndexed
            }
            // AN ENGLISH REPLY IS A FAILURE. This is the whole point of Phase 10 A.
            if (language.verdict != ReplyLanguage.TURKISH) {
                fail("turn ${index + 1} answered in ${language.label}, not Turkish")
            }
            if (language.englishShare > MAX_ENGLISH_WORD_SHARE) {
                fail(
                    "turn ${index + 1} is ${"%.0f".format(Locale.US, language.englishShare * 100)}% " +
                        "English words; a Turkish reply must be Turkish",
                )
            }
            if (language.turkishEvidence == 0) {
                fail("turn ${index + 1} contains no Turkish letter or function word at all")
            }
            if (meta.isNotEmpty()) fail("turn ${index + 1} leaked template/meta output -> $meta")
            if (special.isNotEmpty()) fail("turn ${index + 1} leaked a special token -> $special")
            if (raw.any { it == '�' }) {
                fail("turn ${index + 1}: U+FFFD - Turkish did not survive the JNI boundary")
            }
            puppetArtifacts(raw).forEach { fail("turn ${index + 1} narrated the player -> $it") }
            val after = instance.conversation.entries.size
            if (after < entriesBefore + 2) {
                fail("turn ${index + 1} did not advance the transcript ($entriesBefore -> $after)")
            }
            if (timing.ttftMillis <= 0L) {
                fail("turn ${index + 1}: no first-token time could be measured")
            }
        }

        // ---- multi-turn context, the mechanical half ------------------------
        report.appendLine("### MULTI-TURN CONTEXT")
        val duplicates = replies.map { it.trim() }.groupBy { it }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) {
            fail("the model returned a byte-identical reply for different questions: $duplicates")
        }
        val transcript = instance.conversation.entries
        report.appendLine("transcript entries: ${transcript.size}")
        transcript.forEach { entry ->
            report.appendLine(
                "  turn=${entry.turn} role=${entry.role} speaker=${entry.speakerId?.value ?: "-"} " +
                    "text=${entry.text.replace('\n', ' ').take(90)}",
            )
        }
        // The player's own lines must survive verbatim: if the transcript rewrote them,
        // every later turn would be answering a question nobody asked.
        val userLines = transcript.filter { it.role == TranscriptRole.USER }.map { it.text.trim() }
        script.forEach { line ->
            if (line !in userLines) fail("the transcript lost the player's line \"$line\" verbatim")
        }

        report.appendLine()
        report.appendLine("### LATENCY (five phases, ms)")
        timings.forEach { report.append(it.row()) }
        val measured = timings.count { it.ttftMillis > 0 }
        report.appendLine(
            "phases measured on $measured/${timings.size} turns; " +
                "any '?' above is UNMEASURED, not fast",
        )

        report.appendLine()
        report.appendLine("### SUMMARY")
        report.appendLine("language verdicts : ${verdicts.joinToString(", ")}")
        timings.forEach {
            report.appendLine(
                "turn ${it.turn}: ttft=${it.ttftMillis}ms send->call=${it.sendToInferenceMillis}ms " +
                    "first->last=${it.firstToLastMillis}ms total=${it.totalMillis}ms " +
                    "tok/s=${it.tokensPerSecond?.let { s -> String.format(Locale.US, "%.2f", s) } ?: "?"}",
            )
        }

        report.appendLine()
        report.appendLine("### FAILURES (${failures.size})")
        failures.forEach { report.appendLine(" - $it") }

        val text = report.toString()
        File(REPORT_PATH).writeText(text)
        println(text)

        assertTrue("Phase 10 checks failed:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    // ------------------------------------------------------------------
    // language scoring
    // ------------------------------------------------------------------

    /** A scored reading of one reply, with the evidence that produced it. */
    data class LanguageVerdict(
        val verdict: ReplyLanguage,
        val englishShare: Double,
        val turkishEvidence: Int,
        val label: String,
    )

    /**
     * Scores a reply as Turkish or English from its own words.
     *
     * Two independent signals, because either alone lies: the Turkish-only letters
     * (ı, İ, ğ, Ğ, ş, Ş, plus the diacritics Turkish shares with no other Latin
     * language) and the Turkish function words. English is scored the same way over
     * English function words, because "the/of/and" is the signature of an English text
     * far more reliably than a diacritic is of a Turkish one.
     *
     * The verdict is Turkish only when Turkish wins outright. Anything else is reported
     * honestly as what it looks like - which is the point: an English answer has to be
     * able to fail this test.
     */
    private fun languageOf(raw: String): LanguageVerdict {
        val words = raw.lowercase(Locale.ROOT)
            .split(NON_LETTER)
            .filter { it.isNotBlank() }
        val turkishWords = words.count { TURKISH_WORDS.contains(it) }
        val turkishLetters = Regex("[çğıİöşüÇĞİÖŞÜ]").findAll(raw).count()
        val englishWords = words.count { ENGLISH_WORDS.contains(it) }
        val turkishEvidence = turkishWords + turkishLetters
        val share = if (words.isEmpty()) 0.0 else englishWords.toDouble() / words.size

        // Turkish needs either a Turkish function word or a Turkish-only letter; a short
        // "Merhaba!" has a function word and no diacritic, and must still count.
        val verdict = when {
            turkishEvidence > 0 && turkishWords * 2 + turkishLetters > englishWords ->
                ReplyLanguage.TURKISH
            englishWords > turkishWords * 2 + turkishLetters -> ReplyLanguage.ENGLISH
            turkishEvidence > 0 -> ReplyLanguage.TURKISH
            englishWords > 0 -> ReplyLanguage.ENGLISH
            else -> ReplyLanguage.UNKNOWN
        }
        return LanguageVerdict(
            verdict = verdict,
            englishShare = share,
            turkishEvidence = turkishEvidence,
            label = "$verdict (tr_words=$turkishWords tr_letters=$turkishLetters en_words=$englishWords)",
        )
    }

    /** The share of a text's words that are English function words. Used for the prompt. */
    private fun englishShare(text: String): Double {
        val words = text.lowercase(Locale.ROOT).split(NON_LETTER).filter { it.isNotBlank() }
        if (words.isEmpty()) return 0.0
        return words.count { ENGLISH_WORDS.contains(it) }.toDouble() / words.size
    }

    // ------------------------------------------------------------------
    // detectors
    // ------------------------------------------------------------------

    private fun metaArtifacts(raw: String): List<String> {
        val lowered = raw.lowercase(Locale.ROOT)
        return META_LITERALS.filter { lowered.contains(it.lowercase(Locale.ROOT)) }
    }

    /** Raw ChatML / role / special tokens. Built at runtime; see [META_LITERALS]. */
    private fun specialTokens(raw: String): List<String> =
        SPECIAL_TOKENS.filter { raw.contains(it) }

    /**
     * A special token the model mangled on the way out.
     *
     * Distinct from [specialTokens], and reported separately, because it is a different
     * problem with a different owner. A literal `<|im_end|>` means the stop matcher failed
     * and the marker reached the reader - a code bug. `<d|im_end|>` means the MODEL emitted
     * a corrupted marker: the stop matcher correctly did not match it, because it is not
     * the marker, and the text is ordinary (if garbage) output that a 4B model produced.
     *
     * It is still garbage on the player's screen, so it is counted and printed. It is not
     * asserted, because it is stochastic model behaviour rather than a property of the
     * prompt: the same model on the same prompt does not produce it every time, and a test
     * that failed on a coin flip would be worse than one that records the flip.
     */
    private fun mangledMarkers(raw: String): List<String> {
        val hits = mutableListOf<String>()
        SPECIAL_TOKENS.forEach { token ->
            // An otherwise-intact marker with up to three non-space characters glued onto
            // its front, at a line boundary. The boundary is what keeps this from
            // flagging a clean marker that merely follows a letter.
            val mangled = Regex("(?:^|\\n|\\s)\\S{0,3}\\Q$token\\E")
            mangled.findAll(raw).forEach { match ->
                val value = match.value.trimStart()
                if (value != token) hits += value
            }
        }
        return hits.distinct()
    }

    private fun puppetArtifacts(raw: String): List<String> {
        val hits = mutableListOf<String>()
        val trimmed = raw.trimStart()
        PUPPET_PREFIXES.forEach { prefix ->
            if (trimmed.startsWith(prefix, ignoreCase = true)) hits += "starts with '$prefix'"
        }
        BRACKETED_SPEAKER.findAll(raw).forEach { hits += "bracketed speaker '${it.value}'" }
        return hits
    }

    private companion object {
        const val REPORT_PATH = "/tmp/opencode/phase10_turkish_latency.txt"

        /**
         * Above this share of English function words a reply is reported as an English
         * answer. A Turkish sentence that happens to contain "of" or "the" in a quoted
         * English phrase stays far below it; a genuinely English reply sits at 0.15+.
         */
        const val MAX_ENGLISH_WORD_SHARE = 0.12

        val NON_LETTER = Regex("[^a-zçğıöşüâîû]+")

        val TURKISH_WORDS = setOf(
            "merhaba", "selam", "nasılsın", "nasıl", "neden", "ne", "iyi", "kötü", "güzel",
            "ve", "ile", "için", "olarak", "çok", "daha", "bir", "bu", "şu", "o", "ben",
            "sen", "siz", "biz", "onlar", "var", "yok", "değil", "mi", "mı", "mu", "mü",
            "ama", "fakat", "gibi", "her", "şey", "gün", "bugün", "yarın", "dün", "okul",
            "arkadaş", "arkadaşlar", "birlikte", "nasıl", "yap", "yapabilirim", "yapmalıyım",
            "endişeli", "korku", "korkuyorum", "merak", "yardım", "teşekkür", "lütfen",
            "evet", "hayır", "tamam", "aslında", "sana", "seni", "beni", "kendini", "bana",
            "senin", "benim", "çünkü", "şeyi", "zaman", "günler", "kafan", "kafa", "yorgun",
            "mutlu", "üzgün", "gül", "gülümsemek", "tanıştım", "tanışalım", "anlatalım",
            "yararlı", "fikir", "düşüncesi", "hadi", "kendine", "kendini", "kendine",
        )

        val ENGLISH_WORDS = setOf(
            "the", "of", "and", "to", "in", "is", "are", "you", "your", "i", "my", "me",
            "we", "they", "it", "that", "this", "for", "with", "not", "but", "have", "has",
            "will", "can", "would", "should", "could", "was", "were", "be", "been", "do",
            "does", "if", "so", "as", "at", "on", "or", "an", "a", "he", "she", "his",
            "her", "him", "them", "what", "how", "why", "when", "where", "who", "there",
            "about", "from", "out", "up", "down", "all", "just", "know", "think", "feel",
            "good", "bad", "nice", "hello", "thanks", "sorry", "yes", "no", "okay",
        )

        val META_LITERALS = listOf(
            "<|im" + "_start|>",
            "<|im" + "_end|>",
            "<|end_of_turn|>",
            "<end_of_turn>",
            "<|eot_id|>",
            "<|start_header_id|>",
            "<think>",
            "</think>",
            "[user]",
            "[assistant]",
            "[system]",
            "\nuser:",
            "\nassistant:",
            "\nsystem:",
            "final answer",
            "final thought",
            "[action]",
            "world rules",
            "your identity",
            "what you know",
            "what you remember",
            "right now",
        )

        val SPECIAL_TOKENS = listOf(
            "<|im" + "_start|>",
            "<|im" + "_end|>",
            "<|endoftext|>",
            "<|end_of_turn|>",
            "<end_of_turn>",
            "<|eot_id|>",
            "<think>",
            "</think>",
            "<|object_ref_start|>",
            "<|fim_prefix|>",
            "<|file_separator|>",
        )

        val PUPPET_PREFIXES = listOf("sen:", "user:", "oyuncu:", "kullanıcı:", "you:", "i:")

        val BRACKETED_SPEAKER = Regex("""\[[^\]\n]{1,24}]""")
    }
}
