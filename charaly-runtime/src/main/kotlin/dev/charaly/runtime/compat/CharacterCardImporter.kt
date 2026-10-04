package dev.charaly.runtime.compat

import dev.charaly.runtime.domain.CharacterDefinition
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.LoreEntry
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.StoryPackId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * SillyTavern Character Card v2 compatibility.
 *
 * This is a *compatibility layer*, not the core of Charaly. Its only job is to
 * keep existing user data usable: a card becomes a [CharacterDefinition] plus
 * optional lore entries, which the runtime then treats like any other authored
 * character. Character cards are never allowed to carry authoritative world
 * state.
 */
object CharacterCardImporter {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Imports one card as a character definition. */
    fun importCard(rawJson: String, fallbackName: String = "Imported"): Result<CharacterDefinition> = runCatching {
        val root = json.parseToJsonElement(rawJson).jsonObject
        val data = (root["data"] as? JsonObject) ?: root
        val specVersion = (root["spec_version"] ?: data["spec_version"])?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 2

        val name = data.str("name") ?: fallbackName
        require(specVersion == 1 || specVersion == 2) { "unsupported card spec_version $specVersion" }
        CharacterDefinition(
            id = CharacterId(slugify(name)),
            name = name,
            description = data.str("description").orEmpty(),
            personality = data.str("personality").orEmpty(),
            background = (data.str("scenario") ?: "").let { scenario ->
                listOfNotNull(data.str("creator_notes"), scenario.takeIf { it.isNotBlank() })
                    .joinToString("\n\n")
                    .trim()
            },
            goals = listOfNotNull(data.str("system_prompt")?.takeIf { it.isNotBlank() }),
            persona = data.str("persona").orEmpty(),
            greeting = data.str("first_mes").orEmpty(),
            exampleDialogue = data.strList("mes_example"),
            tags = data.strList("tags"),
            loreEntries = parseLoreBook(data["character_book"]),
        )
    }

    /** Imports a card into a whole story pack (one character, plus its lore). */
    fun importPack(rawJson: String, packId: StoryPackId = StoryPackId("pack-imported")): Result<StoryPack> =
        importCard(rawJson).map { character ->
            StoryPack(
                id = packId,
                title = character.name,
                description = "Imported from a Character Card.",
                characters = listOf(character),
            )
        }

    private fun parseLoreBook(node: kotlinx.serialization.json.JsonElement?): List<LoreEntry> {
        val book = node as? JsonObject ?: return emptyList()
        val entries = book["entries"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return entries.mapIndexedNotNull { index, element ->
            val entry = element as? JsonObject ?: return@mapIndexedNotNull null
            val content = entry.str("content")?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
            LoreEntry(
                id = entry.str("id")?.toString() ?: "lore-$index",
                keys = entry.strList("keys"),
                content = content.trim(),
                comment = entry.str("comment").orEmpty(),
                constant = entry.bool("constant") ?: false,
                enabled = entry.bool("enabled") ?: true,
            )
        }
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    /**
     * Reads a list of strings, tolerating the two shapes cards use in practice:
     * `tags` is a JSON array, while `mes_example` is a single newline-separated
     * string. Both are common enough that rejecting either would break imports.
     */
    private fun JsonObject.strList(key: String): List<String> = when (val node = this[key]) {
        null -> emptyList()
        is kotlinx.serialization.json.JsonArray ->
            node.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
        else -> node.jsonPrimitive.contentOrNull
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toList()
            .orEmpty()
    }

    private fun JsonObject.bool(key: String): Boolean? =
        this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

    internal fun slugify(value: String): String =
        value.lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .split('-')
            .filter { it.isNotEmpty() }
            .joinToString("-")
            .take(40)
            .ifBlank { "character" }
}

/**
 * A local GGUF header reader.
 *
 * Charaly needs to show model metadata *before* loading (size, quantization,
 * architecture) so the model picker is useful. This parses only the GGUF header
 * key/value block, streams the file, and never reads tensor data, so it stays
 * cheap on a phone.
 */
object GgufMetadataReader {

    /** "GGUF" read as a little-endian int32. */
    private const val MAGIC = 0x46554747

    data class Metadata(
        val version: Int,
        val tensorCount: Long,
        val kvCount: Long,
        val architecture: String?,
        val name: String?,
        val quantization: String?,
        val contextLength: Long?,
        val embeddingLength: Long?,
        val blockCount: Long?,
        val values: Map<String, String>,
    ) {
        fun summary(): String = listOfNotNull(
            architecture?.let { "arch=$it" },
            quantization?.let { "quant=$it" },
            contextLength?.let { "ctx=$it" },
            tensorCount.takeIf { it > 0 }?.let { "tensors=$it" },
        ).joinToString(" ")
    }

    fun read(input: java.io.InputStream): Result<Metadata> = runCatching {
        val data = input.buffered()
        val magic = data.readIntLe()
        require(magic == MAGIC) { "not a GGUF file (magic 0x%08x)".format(magic) }
        val version = data.readIntLe()
        val tensorCount = data.readLongLe()
        val kvCount = data.readLongLe()

        val values = linkedMapOf<String, String>()
        repeat(kvCount.toInt().coerceAtMost(MAX_KEYS)) {
            val key = data.readString()
            val type = data.readIntLe()
            values[key] = data.readValue(type)
        }

        Metadata(
            version = version,
            tensorCount = tensorCount,
            kvCount = kvCount,
            architecture = values["general.architecture"],
            name = values["general.name"],
            quantization = values["general.file_type"]?.let { quantName(it) }
                ?: values["general.quantization_version"],
            contextLength = values[values.keys.firstOrNull { it.endsWith(".context_length") } ?: ""]?.toLongOrNull(),
            embeddingLength = values[values.keys.firstOrNull { it.endsWith(".embedding_length") } ?: ""]?.toLongOrNull(),
            blockCount = values[values.keys.firstOrNull { it.endsWith(".block_count") } ?: ""]?.toLongOrNull(),
            values = values,
        )
    }

    fun read(bytes: ByteArray): Result<Metadata> = read(bytes.inputStream())

    private fun quantName(fileType: String): String = when (fileType) {
        "0" -> "F32"
        "1" -> "F16"
        "2" -> "Q4_0"
        "3" -> "Q4_1"
        "7" -> "Q8_0"
        "8" -> "Q5_0"
        "9" -> "Q5_1"
        "10" -> "Q2_K"
        "11" -> "Q3_K"
        "12" -> "Q4_K"
        "13" -> "Q5_K"
        "14" -> "Q6_K"
        "15" -> "Q8_K"
        else -> "type $fileType"
    }

    private fun java.io.InputStream.readIntLe(): Int {
        val b = readNBytes(4)
        require(b.size == 4) { "truncated GGUF header" }
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
    }

    private fun java.io.InputStream.readLongLe(): Long {
        val b = readNBytes(8)
        require(b.size == 8) { "truncated GGUF header" }
        var value = 0L
        for (i in 7 downTo 0) {
            value = (value shl 8) or (b[i].toLong() and 0xFF)
        }
        return value
    }

    private fun java.io.InputStream.readShortLe(): Int {
        val b = readNBytes(2)
        require(b.size == 2) { "truncated GGUF header" }
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
    }

    private fun java.io.InputStream.readString(): String {
        val length = readLongLe()
        require(length in 0..MAX_STRING) { "implausible GGUF string length $length" }
        val bytes = readNBytes(length.toInt())
        require(bytes.size == length.toInt()) { "truncated GGUF string" }
        return String(bytes, Charsets.UTF_8)
    }

    /** Reads one GGUF metadata value and returns its printable form. */
    /** GGUF metadata value types, per the GGUF specification. */
    private fun java.io.InputStream.readValue(type: Int): String = when (type) {
        GGUF_TYPE_UINT8, GGUF_TYPE_INT8 -> readNBytes(1)[0].toInt().toString()
        GGUF_TYPE_UINT16, GGUF_TYPE_INT16 -> readShortLe().toString()
        GGUF_TYPE_UINT32, GGUF_TYPE_INT32 -> readIntLe().toString()
        GGUF_TYPE_UINT64, GGUF_TYPE_INT64 -> readLongLe().toString()
        GGUF_TYPE_FLOAT32 -> readFloatLe().toString()
        GGUF_TYPE_FLOAT64 -> Double.fromBits(readLongLe()).toString()
        GGUF_TYPE_BOOL -> (read().toInt() != 0).toString()
        GGUF_TYPE_STRING -> readString()
        GGUF_TYPE_ARRAY -> {
            val elementType = readIntLe()
            val count = readIntLe()
            if (count < 0 || count > MAX_ARRAY) {
                "[array x$count]"
            } else {
                // Arrays are walked but discarded: display metadata does not need
                // them, and a phone must not buffer a whole vocabulary on open.
                repeat(count) { readValue(elementType) }
                "[array x$count of type $elementType]"
            }
        }
        else -> "[unsupported type $type]"
    }

    private fun java.io.InputStream.readFloatLe(): Float = Float.fromBits(readIntLe())

    private const val GGUF_TYPE_UINT8 = 0
    private const val GGUF_TYPE_INT8 = 1
    private const val GGUF_TYPE_UINT16 = 2
    private const val GGUF_TYPE_INT16 = 3
    private const val GGUF_TYPE_UINT32 = 4
    private const val GGUF_TYPE_INT32 = 5
    private const val GGUF_TYPE_FLOAT32 = 6
    private const val GGUF_TYPE_BOOL = 7
    private const val GGUF_TYPE_STRING = 8
    private const val GGUF_TYPE_ARRAY = 9
    private const val GGUF_TYPE_UINT64 = 10
    private const val GGUF_TYPE_INT64 = 11
    private const val GGUF_TYPE_FLOAT64 = 12

    private const val MAX_KEYS = 4096
    private const val MAX_ARRAY = 1 shl 22
    private const val MAX_STRING = 1 shl 20
}
