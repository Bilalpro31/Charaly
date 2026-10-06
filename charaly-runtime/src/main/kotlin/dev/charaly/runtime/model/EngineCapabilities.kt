package dev.charaly.runtime.model

import kotlinx.serialization.Serializable

/**
 * What the bundled llama.cpp build can actually load.
 *
 * ## Why this exists
 *
 * A Hugging Face or Google model identifier is a *publication* fact. Whether a GGUF
 * for that model can be loaded by the llama.cpp compiled into this APK is an
 * *engine* fact, and the two are not the same thing. A model can be perfectly real and
 * still be unloadable here, because GGUF loading is gated on the ggml architecture
 * string in the file header being present in `llama-arch.cpp`'s table.
 *
 * Guessing here is how model libraries end up shipping a "Download" that produces an
 * error three screens later. So the capability list is declared once, here, as data -
 * and every catalog entry's compatibility is *derived* from it rather than asserted
 * per model.
 *
 * ## Provenance
 *
 * [ARCHITECTURES] mirrors the `LLM_ARCH_*` name table in the vendored llama.cpp at
 * `app/src/main/cpp/llama.cpp/src/llama-arch.cpp`. [BUNDLED_COMMIT] identifies the
 * exact revision. A test asserts that the two agree, so bumping llama.cpp without
 * updating this list fails the build rather than silently mislabelling the library.
 *
 * The `ggml-` prefixed names are the legacy spellings llama.cpp still accepts for the
 * same architecture; they are listed so an older third-party conversion still resolves
 * to a known-good verdict instead of "unrecognised".
 */
object EngineCapabilities {

    /** The vendored llama.cpp revision this list was read from. */
    const val BUNDLED_COMMIT = "5143fa895e7725c5bd2135daf7d8f793d98fa91c"

    const val BUNDLED_DATE = "2025-09-05"

    /** ggml architecture names the bundled build registers and can therefore load. */
    val ARCHITECTURES: Set<String> = setOf(
        "arcee", "arctic", "arwkv7", "baichuan",
        "bailingmoe", "bert", "bitnet", "bloom",
        "chameleon", "chatglm", "codeshell", "cohere2",
        "command-r", "dbrx", "deci", "deepseek",
        "deepseek2", "dots1", "dream", "exaone",
        "exaone4", "falcon", "falcon-h1", "gemma",
        "gemma-embedding", "gemma2", "gemma3", "gemma3n",
        "glm4", "glm4moe", "gpt-oss", "gpt2",
        "gptj", "gptneox", "granite", "granitehybrid",
        "granitemoe", "grok", "hunyuan-dense", "hunyuan-moe",
        "internlm2", "jais", "jamba", "jina-bert-v2",
        "jina-bert-v3", "lfm2", "llada", "llama",
        "llama4", "mamba", "mamba2", "minicpm",
        "minicpm3", "mpt", "nemotron", "neo-bert",
        "nomic-bert", "nomic-bert-moe", "olmo", "olmo2",
        "olmoe", "openelm", "orion", "phi2",
        "phi3", "phimoe", "plamo", "plamo2",
        "plm", "qwen", "qwen2", "qwen2moe",
        "qwen2vl", "qwen3", "qwen3moe", "refact",
        "rwkv6", "rwkv6qwen2", "rwkv7", "smallthinker",
        "smollm3", "stablelm", "starcoder", "starcoder2",
        "t5", "t5encoder", "wavtokenizer-dec", "xverse",
    )

    /** Legacy spellings that map onto a registered architecture. */
    private val LEGACY_ALIASES: Map<String, String> = mapOf(
        "ggml-gemma" to "gemma",
        "ggml-gemma2" to "gemma2",
        "ggml-gemma3" to "gemma3",
    )

    /** Resolves a GGUF `general.architecture` string to a registered architecture. */
    fun normalise(architecture: String): String {
        val key = architecture.trim().lowercase()
        return LEGACY_ALIASES[key] ?: key
    }

    fun supports(architecture: String): Boolean {
        val key = normalise(architecture)
        if (key.isBlank()) return false
        return ARCHITECTURES.contains(key)
    }

    /**
     * Architectures of a given family that this build can load.
     *
     * Used by the catalog to tell "we support Gemma 2 and Gemma 3" from "we support no
     * Gemma at all", which is a genuinely different message for the user.
     */
    fun familyPrefixes(): Set<String> =
        ARCHITECTURES.map { it.takeWhile { ch -> !ch.isDigit() } }
            .filter { it.isNotBlank() }
            .toSet()
}

/**
 * Whether a catalog entry can be loaded by the bundled engine, right now.
 *
 * This is deliberately a three-way verdict rather than a boolean: "unknown
 * architecture" and "known architecture this engine does not have yet" are very
 * different situations, and conflating them is exactly how a library ends up
 * promising something it cannot deliver.
 */
@Serializable
enum class EngineSupport {
    /** The bundled engine registers this architecture. */
    SUPPORTED,

    /**
     * A real architecture this engine does not support yet.
     *
     * The UI must say so plainly. Offering a download for a model that cannot be
     * loaded is worse than not offering one at all.
     */
    ENGINE_UPDATE_REQUIRED,

    /** Not a recognised ggml architecture; loading it is a coin flip. */
    UNKNOWN_ARCHITECTURE,
    ;

    val isLoadable: Boolean get() = this == SUPPORTED

    /** Whether the library should offer any action at all for this entry. */
    val isActionable: Boolean get() = this == SUPPORTED
}

/**
 * A catalog entry's compatibility with the bundled engine, and the reason.
 *
 * `reason` is written for a human: it is shown verbatim in the model library.
 */
@Serializable
data class EngineVerdict(
    val support: EngineSupport,
    val architecture: String,
    val reason: String,
) {
    val isLoadable: Boolean get() = support.isLoadable

    companion object {
        /**
         * The architecture implied by a model's *name*, when nothing better is known.
         *
         * ## Why a name is a guess, and why it is still useful
         *
         * Every GGUF file states its architecture authoritatively in its header, and
         * [of] the header is always preferred. This exists for the one case where no
         * header has been read yet: a repository card, or a library listing.
         *
         * It is deliberately conservative: it returns `""` rather than a guess when
         * nothing matches, which routes the caller to [of]'s "does not know what kind of
         * model this is" answer instead of to a specific wrong one. Where a name *does*
         * match, the result is the architecture that name actually refers to - including
         * the case where this build has no support for it.
         *
         * The distinction matters: a repository called `gemma-4-gguf` resolving to
         * `gemma4` produces a *correct* "needs a newer engine" verdict, which is the
         * message the user needs. Resolving it to `gemma` would produce "supported" and a
         * download that fails at load time.
         */
        fun inferArchitecture(modelName: String): String {
            // Separators are removed before matching, because publishers are inconsistent
            // about them: the same family appears as `gemma-3-4b`, `Gemma3_4B` and
            // `gemma_3`. Normalising first means the table below can be written once
            // instead of once per spelling, which is where a table would otherwise rot.
            val key = buildString {
                for (ch in modelName.lowercase()) {
                    if (ch.isLetterOrDigit()) append(ch)
                }
            }
            if (key.isBlank()) return ""
            // Most specific first: "qwen3" must beat "qwen", "llama4" beat "llama".
            return FAMILY_HINTS.firstOrNull { (prefix, _) -> key.startsWith(prefix) }?.second.orEmpty()
        }

        /**
         * Recognised name prefixes, separator-free, mapped to the ggml architecture they
         * imply.
         *
         * Ordered most specific first and matched by prefix only. Prefix rather than
         * substring is what keeps "yi" from matching every name containing those two
         * letters, and it is why the table is written as whole family names.
         *
         * Every value is either a member of [EngineCapabilities.ARCHITECTURES] or an
         * *intentionally unregistered* name - `gemma4` is here purely so
         * [EngineVerdict.of] can answer "needs a newer engine" for Gemma 4, rather than
         * resolving it to the older `gemma` and claiming support this build does not have.
         * A test asserts that split, because a hint pointing at an unregistered
         * architecture for any *other* reason would produce a confident, wrong verdict.
         */
        private val FAMILY_HINTS: List<Pair<String, String>> = listOf(
            "qwen3moe" to "qwen3moe",
            "qwen35" to "qwen3",
            "qwen3" to "qwen3",
            "qwen2" to "qwen2",
            "qwen" to "qwen",
            "llama4" to "llama4",
            "llama3" to "llama",
            "llama2" to "llama",
            "llama" to "llama",
            // gemma4 before gemma3 before gemma: the whole point is that a Gemma 4 file
            // resolves to the architecture it actually is.
            "gemma4" to "gemma4",
            "gemma3n" to "gemma3n",
            "gemma3" to "gemma3",
            "gemma2" to "gemma2",
            "gemma" to "gemma",
            "mistral" to "llama",
            "mixtral" to "llama",
            "ministral" to "llama",
            "phi4" to "phi3",
            "phi3" to "phi3",
            "deepseek" to "deepseek2",
            "commandr" to "command-r",
            "granite" to "granite",
            "olmo" to "olmo2",
            "exaone" to "exaone",
            "smol" to "smollm3",
            "jamba" to "jamba",
            "glm4" to "glm4",
            "hunyuan" to "hunyuan-dense",
            "yi" to "llama",
            "mpt" to "mpt",
            "falcon" to "falcon",
        )

        /** Derives the verdict for [architecture] from [EngineCapabilities]. */
        fun of(architecture: String): EngineVerdict {
            val raw = architecture.trim()
            if (raw.isBlank()) {
                return EngineVerdict(
                    support = EngineSupport.UNKNOWN_ARCHITECTURE,
                    architecture = raw,
                    reason = "Charaly does not know what kind of model this is yet.",
                )
            }
            return when {
                EngineCapabilities.supports(raw) -> EngineVerdict(
                    support = EngineSupport.SUPPORTED,
                    architecture = raw,
                    reason = "Charaly's engine can load this.",
                )
                raw.equals("gemma4", ignoreCase = true) -> EngineVerdict(
                    support = EngineSupport.ENGINE_UPDATE_REQUIRED,
                    architecture = raw,
                    reason = "Gemma 4 needs a newer llama.cpp than the one built into " +
                        "this version of Charaly. Everything else works - it will load " +
                        "as soon as the engine is updated.",
                )
                else -> EngineVerdict(
                    support = EngineSupport.UNKNOWN_ARCHITECTURE,
                    architecture = raw,
                    reason = "Charaly does not recognise this architecture ($raw), so it " +
                        "cannot promise the model will load.",
                )
            }
        }
    }
}