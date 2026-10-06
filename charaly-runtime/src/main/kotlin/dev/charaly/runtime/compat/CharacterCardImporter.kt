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

    /**
     * Reads only the GGUF header, delegating the actual parsing to
     * [dev.charaly.runtime.model.gguf.GgufReader].
     *
     * ## Why this used to be its own parser
     *
     * This object predates [dev.charaly.runtime.model.gguf.GgufReader], and the two
     * then drifted apart: the array length was read as a 32-bit int where the GGUF
     * spec encodes it as uint64, and the file-type table had fallen behind the
     * `LLAMA_FTYPE_*` values this llama.cpp actually writes (file type 14 reported
     * "Q6_K" where it means "Q4_K_S"). Both bugs are gone because the parsing now
     * happens in exactly one place. [GgufReader] is the one with the bounds checks,
     * the overflow guards and the version gating, and this reader keeps its
     * callers' [Metadata] shape so nothing outside this file had to move.
     */
    fun read(input: java.io.InputStream): Result<Metadata> = runCatching {
        val result = dev.charaly.runtime.model.gguf.GgufReader.read(input)
        val metadata = (result as? dev.charaly.runtime.model.gguf.GgufReadResult.Success)?.metadata
            ?: throw IllegalArgumentException(
                (result as? dev.charaly.runtime.model.gguf.GgufReadResult.Failure)?.reason
                    ?: "not a GGUF file",
            )

        val rendered = metadata.keyValues.mapValues { it.value.render() }
        val fileType = metadata.keyValues["general.file_type"]?.asIntOrNull()
            ?: rendered["general.file_type"]?.toIntOrNull()

        fun firstBySuffix(suffix: String): Long? = rendered.entries
            .firstOrNull { it.key.endsWith(suffix) }
            ?.value?.toLongOrNull()

        Metadata(
            version = metadata.version,
            tensorCount = metadata.tensorCount,
            kvCount = metadata.pairCount.toLong(),
            architecture = metadata.architecture.takeIf { it.isNotBlank() },
            name = metadata.name.takeIf { it.isNotBlank() },
            quantization = fileType
                ?.let { dev.charaly.runtime.model.gguf.GgufMetadata.FILE_TYPE_LABELS[it] }
                ?: metadata.quantization.takeIf { it.isNotBlank() }
                ?: rendered["general.quantization_version"],
            contextLength = metadata.contextLength.takeIf { it > 0 }?.toLong()
                ?: firstBySuffix(".context_length"),
            embeddingLength = metadata.embeddingLength.takeIf { it > 0 }?.toLong()
                ?: firstBySuffix(".embedding_length"),
            blockCount = metadata.blockCount.takeIf { it > 0 }?.toLong()
                ?: firstBySuffix(".block_count"),
            values = rendered,
        )
    }

    fun read(bytes: ByteArray): Result<Metadata> = read(bytes.inputStream())
}
