package dev.charaly.runtime.model.gguf

/**
 * A single key/value pair from a GGUF header, with its value kept in the file's own
 * type rather than coerced to a String.
 *
 * ## Why the type is preserved
 *
 * The single most consequential number in a GGUF header is `*.context_length`, which
 * is a uint32. Reading it as a float64 (as a naive "read everything as JSON-ish"
 * reader would) yields a value like 32768.0 -> 4.29e9 once cast, and Charaly would
 * then hand a model a context it cannot hold. Keeping [GgufValue] typed means the
 * caller asks for the type it expects and gets a rejection rather than a plausible
 * wrong number.
 *
 * Everything is read from the first few megabytes of the file only: the GGUF
 * key/value block precedes the tensor data, so a large model is never fully loaded
 * just to learn what it is.
 */
sealed interface GgufValue {

    /** Unsigned 8-bit. Used for small counts and enum-ish metadata. */
    data class U8(val value: Int) : GgufValue

    /** Signed 8-bit. */
    data class I8(val value: Int) : GgufValue

    /** Unsigned 16-bit. */
    data class U16(val value: Int) : GgufValue

    /** Signed 16-bit. */
    data class I16(val value: Int) : GgufValue

    /** Unsigned 32-bit. `*.context_length` and block counts arrive as this. */
    data class U32(val value: Long) : GgufValue

    /** Signed 32-bit. */
    data class I32(val value: Int) : GgufValue

    /** Unsigned 64-bit. Parameter counts and total tensor bytes arrive as this. */
    data class U64(val value: Long) : GgufValue

    /** Signed 64-bit. */
    data class I64(val value: Long) : GgufValue

    /** IEEE-754 binary32. Sampling defaults arrive as this. */
    data class F32(val value: Float) : GgufValue

    /** IEEE-754 binary64. */
    data class F64(val value: Double) : GgufValue

    /** `true` / `false`. */
    data class Bool(val value: Boolean) : GgufValue

    /** UTF-8, length-prefixed. `general.name`, `tokenizer.chat_template`. */
    data class Str(val value: String) : GgufValue

    /** `char[16]` UUID of the array this belongs to, as lowercase hex. */
    data class ArrayId(val value: String) : GgufValue

    /** A homogeneous array. */
    data class Arr(val elementType: GgufValueType, val items: List<GgufValue>) : GgufValue

    /**
     * As [Long], when the value is an integer type that fits.
     *
     * Returns null for a float, string or bool rather than guessing: `temperature` read
     * as a Long is 1, which is wrong in a way that would silently disable sampling.
     */
    fun asLongOrNull(): Long? = when (this) {
        is U8 -> value.toLong()
        is I8 -> value.toLong()
        is U16 -> value.toLong()
        is I16 -> value.toLong()
        is U32 -> value
        is I32 -> value.toLong()
        is U64 -> value
        is I64 -> value
        else -> null
    }

    /** As [Int], when it is an integer type that fits without overflow. */
    fun asIntOrNull(): Int? = asLongOrNull()?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()

    /** As [Float], accepting either float width. */
    fun asFloatOrNull(): Float? = when (this) {
        is F32 -> value
        is F64 -> value.toFloat()
        else -> null
    }

    /** As [String], accepting only a real string. */
    fun asStringOrNull(): String? = (this as? Str)?.value

    /** As [Boolean], accepting only a real bool. */
    fun asBooleanOrNull(): Boolean? = (this as? Bool)?.value

    /** Human-readable form, for diagnostics. Never used for numeric decisions. */
    fun render(): String = when (this) {
        is U8 -> value.toString()
        is I8 -> value.toString()
        is U16 -> value.toString()
        is I16 -> value.toString()
        is U32 -> value.toString()
        is I32 -> value.toString()
        is U64 -> value.toString()
        is I64 -> value.toString()
        is F32 -> value.toString()
        is F64 -> value.toString()
        is Bool -> value.toString()
        is Str -> value
        is ArrayId -> value
        is Arr -> items.joinToString(prefix = "[", postfix = "]") { it.render() }
    }
}

/**
 * The GGUF metadata value types, as encoded by the spec's `gguf_metadata_value_type`
 * enum.
 *
 * Declared explicitly rather than derived from the sealed hierarchy so the on-disk
 * integer can be mapped without instantiating anything, and so an unknown code in a
 * future file is reported as unknown rather than guessed.
 */
enum class GgufValueType(val code: Int) {
    UINT8(0),
    INT8(1),
    UINT16(2),
    INT16(3),
    UINT32(4),
    INT32(5),
    FLOAT32(6),
    BOOL(7),
    STRING(8),
    ARRAY(9),
    UINT64(10),
    INT64(11),
    FLOAT64(12),
    ;

    val isArray: Boolean get() = this == ARRAY

    companion object {
        private val byCode = entries.associateBy { it.code }

        /** The type for a spec code, or null when the file uses a code we do not know. */
        fun of(code: Int): GgufValueType? = byCode[code]

        /**
         * How many bytes one scalar of this type occupies, or null for variable-length.
         *
         * [STRING] and [ARRAY] are length-prefixed and so have no fixed width, which is
         * why the reader has to handle them specially rather than in a uniform loop.
         */
        val fixedWidthBytes: Map<GgufValueType, Int> = mapOf(
            UINT8 to 1, INT8 to 1,
            UINT16 to 2, INT16 to 2,
            UINT32 to 4, INT32 to 4,
            FLOAT32 to 4,
            BOOL to 1,
            UINT64 to 8, INT64 to 8,
            FLOAT64 to 8,
        )

        fun fixedWidth(type: GgufValueType): Int? = fixedWidthBytes[type]
    }
}

/**
 * The parsed GGUF header: everything Charaly can learn about a model without loading
 * its weights.
 *
 * ## What is observed versus derived
 *
 * [keyValues] is *observed*: every entry came out of the file. Everything the rest of
 * this class exposes ([architecture], [contextLength], [chatTemplate], ...) is a
 * *lookup into that map*, never a guess. A field that is absent stays absent, and
 * `has(key)` lets a caller tell "the file says 0" from "the file says nothing".
 */
class GgufMetadata(
    /** Spec version from the header. 2 and 3 are the current ones. */
    val version: Int,
    val tensorCount: Long,
    /** Tensor info entries that follow the key/value block. Not decoded here. */
    val tensorInfoCount: Long,
    val keyValues: Map<String, GgufValue>,
) {
    /** Metadata pairs actually read, which is what [GgufValueType.of] acceptance means. */
    val pairCount: Int get() = keyValues.size

    fun has(key: String): Boolean = keyValues.containsKey(key)

    operator fun get(key: String): GgufValue? = keyValues[key]

    /** First present key from [candidates], so aliases can be tried in one call. */
    private fun firstOf(vararg candidates: String): GgufValue? =
        candidates.firstNotNullOfOrNull { keyValues[it] }

    /**
     * `general.architecture`, verbatim from the file.
     *
     * This is the exact string that decides whether llama.cpp can load the model, so it
     * is deliberately *not* normalised here. [dev.charaly.runtime.model.EngineCapabilities]
     * owns normalisation, because only it knows about the vendored engine's aliases.
     */
    val architecture: String
        get() = firstOf("general.architecture")?.asStringOrNull().orEmpty()

    /**
     * Trained context length, as the file reports it.
     *
     * The key is architecture-prefixed in GGUF (`llama.block_count`,
     * `qwen3.context_length`), so the architecture has to be resolved first. 0 means
     * the file does not say, which is different from "the model has no context".
     */
    val contextLength: Int
        get() {
            val arch = architecture.ifBlank { return 0 }
            val key = "$arch.context_length"
            return keyValues[key]?.asIntOrNull()
                ?: keyValues["$arch.max_context_length"]?.asIntOrNull()
                ?: 0
        }

    /** `tokenizer.chat_template`, present when the model was published with one. */
    val chatTemplate: String
        get() = firstOf(
            "tokenizer.chat_template",
            "tokenizer.ggml.chat_template",
        )?.asStringOrNull().orEmpty()

    /** The model's published name, when it declares one. */
    val name: String
        get() = firstOf("general.name", "general.basename")?.asStringOrNull().orEmpty()

    /** `general.quantization_version`, informational only. */
    val quantizationVersion: Int
        get() = keyValues["general.quantization_version"]?.asIntOrNull() ?: 0

    /** The quantisation label used in tensor names, e.g. `Q4_K_M`. */
    val quantization: String
        get() {
            // Tensor types are encoded in the tensor-info block we do not decode, so the
            // quant is derived from the file type distribution when the file publishes
            // it, and otherwise left empty rather than invented from the file size.
            return keyValues["general.file_type"]?.asIntOrNull()?.let { FILE_TYPE_LABELS[it] }.orEmpty()
        }

    /** `general.quantization_label` if a converter wrote one. */
    val quantizationLabel: String
        get() = firstOf("general.quantization_label")?.asStringOrNull().orEmpty()

    /** Architecture-specific hyper-parameters, e.g. block/head/vocab counts. */
    fun architectureValue(suffix: String): GgufValue? = keyValues["$architecture.$suffix"]

    /**
     * Parameter count, when the file states it.
     *
     * Prefer the stated value. Only fall back to a structural estimate, and only when
     * the block/embedding counts needed for one are actually present - see
     * [estimateParameterCount].
     */
    val parameterCount: Long
        get() = keyValues["general.parameter_count"]?.asLongOrNull()
            ?: firstOf("general.size_label")?.asStringOrNull()?.let(::parseParameterCount)
            ?: estimateParameterCount()
            ?: 0L

    /**
     * A structural parameter estimate, or null when the header lacks what it needs.
     *
     * ## Why this is an estimate and is treated as one
     *
     * It multiplies the embedding matrix by the layer count and adds per-layer weights
     * derived from the hidden size, which is accurate to roughly the same order of
     * magnitude but *not* to the digit. That is enough to answer "is this a 1B model or
     * a 30B model", which is the only question the library and the device-fit check
     * actually ask. It is never used as a hard requirement, and
     * [dev.charaly.runtime.model.MetadataConfidence] stays APPROXIMATE when it is the
     * source.
     */
    fun estimateParameterCount(): Long? {
        val arch = architecture.ifBlank { return null }
        // `n` strips the architecture prefix from a key before looking it up, because
        // converters write hyper-parameters both as "qwen2.block_count" and as
        // "block_count".
        //
        // The recursion is bounded by the *key*, not by the number of segments: it stops
        // when stripping the prefix produces an unchanged key. The earlier version
        // recursed on the result of the lookup, which for an unknown key returned null
        // and then re-entered with the same key forever - so a header without a
        // block_count overflowed the stack inside a property getter, where the failure
        // looked nothing like its cause.
        fun n(key: String): Long? {
            architectureValue(key)?.asLongOrNull()?.let { return it }
            val bare = key.substringAfterLast('.')
            return if (bare == key) null else architectureValue(bare)?.asLongOrNull()
        }
        val layers = n("block_count") ?: return null
        val embd = n("embedding_length") ?: return null
        val vocab = n("vocab_size") ?: 0L
        if (layers <= 0 || embd <= 0) return null

        // Embedding + output head (many models tie them, so the head is not always
        // counted; the vocab term covers the common untied case).
        var params = (embd * vocab).takeIf { it > 0 } ?: (embd * embd)
        // Attention: q, k, v, o projections. GQA makes k/v narrower, which is why the
        // key and value counts are consulted rather than assumed equal to embd.
        val heads = n("attention.head_count") ?: n("head_count") ?: 1L
        val kvHeads = n("attention.head_count_kv") ?: n("head_count_kv") ?: heads
        val headDim = (if (heads > 0) embd / heads else embd).coerceAtLeast(1L)
        params += heads * headDim * embd // q
        params += kvHeads * headDim * embd // k
        params += kvHeads * headDim * embd // v
        params += embd * (heads * headDim) // o
        // Feed-forward. The 2.7x is the usual gated SwiGLU ratio; it is approximate by
        // construction, which is acceptable for an order-of-magnitude answer.
        val ff = n("feed_forward_length") ?: (embd * 3)
        params += 2 * embd * ff
        // Two norms per block plus the final norm: negligible, but included so the
        // estimate is not systematically low.
        params += 2 * embd

        return (params * layers).coerceAtLeast(0L)
    }

    /** Embedding width, useful for choosing a context budget. */
    val embeddingLength: Int
        get() = architectureValue("embedding_length")?.asIntOrNull()
            ?: keyValues["${architecture}.block_count"]?.let { 0 } ?: 0

    /** Number of transformer blocks. */
    val blockCount: Int
        get() = architectureValue("block_count")?.asIntOrNull() ?: 0

    /** Vocabulary size, when published. */
    val vocabSize: Int
        get() = architectureValue("vocab_size")?.asIntOrNull() ?: 0

    /**
     * Whether the file declares a chat template.
     *
     * A model without one still runs on llama.cpp - it falls back to a plain
     * prompt-and-response format - but the prompt will be much worse, so this is
     * surfaced rather than treated as a blocker.
     */
    val hasChatTemplate: Boolean get() = chatTemplate.isNotBlank()

    /**
     * Whether the template accepts a leading `system` role.
     *
     * ## Why this is derived from the template rather than assumed
     *
     * Several widely used templates either fold a leading system turn into the first
     * user message or drop it. Charaly's most important instructions live in the system
     * role, so a template that cannot carry it is a real capability difference, not a
     * cosmetic one. The check looks for the role token or the system role *name* being
     * present at all - a template that mentions neither will not emit a system turn.
     */
    val supportsSystemRole: Boolean
        get() {
            if (chatTemplate.isBlank()) return false
            val t = chatTemplate.lowercase()
            return t.contains("system") || t.contains("<|im_start|>") || t.contains("[INST]")
        }

    /** A compact, human-readable summary for the model detail screen. */
    fun describe(): String = buildString {
        appendLine("GGUF v$version · $tensorCount tensors · $pairCount metadata pairs")
        appendLine("architecture: ${architecture.ifBlank { "(not declared)" }}")
        appendLine("context: ${if (contextLength > 0) contextLength else "(not declared)"}")
        appendLine("parameters: ${if (parameterCount > 0) "%,d".format(parameterCount) else "(unknown)"}")
        appendLine("chat template: ${if (hasChatTemplate) "yes" else "none"}")
    }

    companion object {
        /**
         * llama.cpp `LLAMA_FTYPE_*` values mapped to their conventional names.
         *
         * Only the names a user would recognise. Anything not listed leaves
         * [quantization] empty rather than printing a raw integer, because "39" tells a
         * user nothing about whether their model is Q4 or Q8.
         */
        val FILE_TYPE_LABELS: Map<Int, String> = mapOf(
            0 to "F32",
            1 to "F16",
            2 to "Q4_0",
            3 to "Q4_1",
            7 to "Q8_0",
            8 to "Q5_0",
            9 to "Q5_1",
            10 to "Q2_K",
            11 to "Q3_K_S",
            12 to "Q3_K_M",
            13 to "Q3_K_L",
            14 to "Q4_K_S",
            15 to "Q4_K_M",
            16 to "Q5_K_S",
            17 to "Q5_K_M",
            18 to "Q6_K",
            19 to "IQ2_XXS",
            20 to "IQ2_XS",
            21 to "Q2_K_S",
            22 to "IQ3_XS",
            23 to "IQ3_XXS",
            24 to "IQ1_S",
            25 to "IQ4_NL",
            26 to "IQ3_S",
            27 to "IQ3_M",
            28 to "IQ2_S",
            29 to "IQ2_M",
            30 to "IQ4_XS",
            31 to "IQ1_M",
            32 to "BF16",
            36 to "TQ1_0",
            37 to "TQ2_0",
        )

        /** "7B", "1.7B", "270M" -> the integer it denotes. */
        fun parseParameterCount(label: String): Long? {
            val cleaned = label.trim().lowercase().replace("b", "").replace("m", "000000")
            // The replace above turns "1.7b" into "1.7"; handle both spellings.
            val numeric = cleaned.trim().toDoubleOrNull() ?: return null
            val scaled = when {
                label.trim().lowercase().endsWith("m") -> numeric
                label.trim().lowercase().endsWith("b") -> numeric * 1_000_000_000.0
                else -> return null
            }
            return scaled.toLong().takeIf { it > 0 }
        }
    }
}
