package dev.charaly.runtime.compat

import dev.charaly.runtime.domain.CharacterDefinition
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.LoreEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * A card read off a file, before anything is written to storage.
 *
 * ## Why a preview type exists at all
 *
 * An import writes to the user's character library. Doing that from a file the user
 * double-tapped, with no chance to look at what was in it, is not a flow worth shipping -
 * and the failure mode is silent, because a malformed card produces either an exception or
 * a character with an empty description.
 *
 * So the pipeline is explicit and it has a checkpoint:
 *
 * ```
 *   bytes -> decode -> validate -> CharacterCardPreview -> user confirms -> save
 * ```
 *
 * [CharacterCardPreview] is what sits at the checkpoint. It carries everything the user
 * needs to judge the card, it carries no storage handle and no engine, and turning it into
 * a [CharacterDefinition] is a pure function. Nothing is persisted until the user says so,
 * so cancelling really does leave no mutation - which is the only claim worth making about
 * a cancel button.
 *
 * ## What a card may and may not bring
 *
 * A card describes *who someone is*: name, appearance, personality, how they speak, a
 * greeting, example dialogue, and lore entries. It cannot bring world state. There is no
 * field on this type for a location, a routine, a relationship, a thread or a clock, and
 * [CharacterCardImporter] does not read anything that could become one. That is why an
 * import cannot smuggle in a world.
 */
@Serializable
data class CharacterCardPreview(
    /** Derived from the name. Stable, so re-importing the same card updates rather than duplicates. */
    val id: String,
    val name: String,

    val description: String = "",
    val personality: String = "",
    /** The card's `scenario`: the situation the character is *in*. Presentation only. */
    val scenario: String = "",
    /** `first_mes`: what the character says when a story opens. */
    val greeting: String = "",
    /** `mes_example`: example turns, kept verbatim so the style survives. */
    val exampleDialogue: List<String> = emptyList(),
    /** `creator`, `character_version`, `creator_notes` - attribution, shown in the preview. */
    val creator: String = "",
    val characterVersion: String = "",
    val creatorNotes: String = "",
    val tags: List<String> = emptyList(),
    /** `character_book` entries, preserved rather than executed. */
    val loreEntries: List<LoreEntry> = emptyList(),
    /** Where this came from, for the preview's provenance line. */
    val sourceName: String = "",
    /** True when the card was read out of a PNG rather than a .json file. */
    val fromPng: Boolean = false,
) {
    /** How many lore entries the preview should report. Never a raw count with no context. */
    val loreCount: Int get() = loreEntries.size

    /** A one-line summary for a list row, falling back to the greeting's first sentence. */
    fun summaryLine(): String = when {
        description.isNotBlank() -> description.lineSequence().firstOrNull()?.trim().orEmpty()
        personality.isNotBlank() -> personality.lineSequence().firstOrNull()?.trim().orEmpty()
        greeting.isNotBlank() -> greeting.lineSequence().firstOrNull()?.trim().orEmpty()
        else -> "No description in this card."
    }

    /**
     * The character this card describes.
     *
     * Pure, and the only way a card becomes runtime content. It deliberately maps the
     * card's `scenario` onto `background` the way the existing importer does, so a card
     * imported through either entry point produces the same character - two code paths
     * that disagree about the same file is the kind of thing that makes a bug
     * unreproducible.
     */
    fun toCharacterDefinition(): CharacterDefinition = CharacterDefinition(
        id = CharacterId(id),
        name = name,
        description = description,
        personality = personality,
        background = listOf(creatorNotes, scenario)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
            .trim(),
        greeting = greeting,
        exampleDialogue = exampleDialogue,
        tags = tags,
        loreEntries = loreEntries,
        // An imported character has no accent of its own, so the pack's identity supplies
        // one. Leaving it empty is honest: the alternative is a colour invented here and
        // presented as something the card said.
        tagline = summaryLine().take(64),
    )
}

/**
 * A card that could not be read, in words a player can act on.
 *
 * Never an exception message. "Could not read character card" plus the reason is the whole
 * vocabulary: a user cannot fix a malformed PNG, so the useful thing is to say so and
 * point at the next option rather than to show them a parse error.
 */
data class CharacterCardError(
    /** "Could not read character card" */
    val headline: String,
    /** "This file is not a SillyTavern character card." */
    val detail: String,
) {
    companion object {
        val NOT_A_CARD = CharacterCardError(
            headline = "Could not read character card",
            detail = "This file is not a SillyTavern character card.",
        )

        val UNREADABLE = CharacterCardError(
            headline = "Could not read character card",
            detail = "The file could not be opened.",
        )

        fun of(reason: String): CharacterCardError = CharacterCardError(
            headline = NOT_A_CARD.headline,
            detail = reason,
        )
    }
}

/**
 * Reads a character card out of bytes, whatever shape the file is in.
 *
 * ## PNG support, and why it is here at all
 *
 * SillyTavern stores a character card as base64 JSON inside a PNG `tEXt` chunk, so the
 * file a user has is a picture. Refusing those would mean refusing essentially every real
 * card, and Charaly would look broken next to the app the cards came from.
 *
 * Only the text chunks are read. Image data is skipped by its declared length, so a
 * multi-megabyte portrait costs one sequential scan rather than being buffered - the same
 * discipline `GgufMetadataReader` applies to model headers.
 *
 * ## What this is allowed to return
 *
 * A preview or a [CharacterCardError]. Not a partially-populated character: a card that
 * parsed as JSON but named nobody is a failure, because importing it would create a
 * library item the user cannot recognise or delete by name.
 */
object CharacterCardReader {

    /** The PNG text chunk SillyTavern writes card JSON into. */
    const val CHARA_KEYWORD = "chara"

    /** The v2/v3 keyword, which some exporters use instead. */
    const val CCV3_KEYWORD = "ccv3"

    /** A card JSON blob is comfortably under this; anything larger is not a card. */
    private const val MAX_CARD_JSON_BYTES = 4 * 1024 * 1024

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    /**
     * Reads a card from a file's bytes.
     *
     * [sourceName] is only used for the preview's provenance line; it never reaches the
     * character, so importing the same card from two filenames cannot create two
     * different characters.
     */
    fun read(bytes: ByteArray, sourceName: String = ""): Result<CharacterCardPreview> = runCatching {
        if (isPng(bytes)) {
            val json = extractPngCardJson(bytes).getOrThrow()
            parse(json, sourceName, fromPng = true)
        } else {
            parse(bytes.toString(Charsets.UTF_8), sourceName, fromPng = false)
        }
    }

    /** True when the bytes begin with the PNG signature. */
    fun isPng(bytes: ByteArray): Boolean {
        if (bytes.size < PNG_SIGNATURE.size) return false
        return PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }
    }

    /**
     * Pulls the base64 card JSON out of a PNG's text chunks.
     *
     * Walks the chunk stream rather than decoding the image: a card's payload lives in
     * `tEXt`/`iTXt` chunks with the keyword `chara` (or `ccv3`), which are always before
     * `IDAT`. Skipping by declared length means a large image is stepped over, not read.
     */
    fun extractPngCardJson(bytes: ByteArray): Result<String> = runCatching {
        require(isPng(bytes)) { "not a PNG" }

        var offset = PNG_SIGNATURE.size
        while (offset + 8 <= bytes.size) {
            val length = readInt(bytes, offset)
            if (length < 0) break
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            val dataStart = offset + 8
            if (length > bytes.size || dataStart + length > bytes.size) break

            when (type) {
                "IEND" -> break

                "tEXt" -> decodeChunk(bytes, type, dataStart, length)?.let { return@runCatching it }

                "iTXt" -> decodeChunk(bytes, type, dataStart, length)?.let { return@runCatching it }

                // Compressed or unrelated chunks are skipped wholesale.
                else -> Unit
            }

            // 4 bytes of chunk data + 4 bytes of CRC.
            offset = dataStart + length + 4
        }
        error("no character card found in this PNG")
    }

    /** Parses card JSON into a preview. Extracted so the PNG path and the JSON path share it. */
    private fun parse(json: String, sourceName: String, fromPng: Boolean): CharacterCardPreview {
        val character = CharacterCardImporter.importCard(json, fallbackName = "").getOrThrow()
        // A card with no name cannot be shown, recognised or deleted in a list, so it is
        // rejected here rather than imported under a placeholder.
        require(character.name.isNotBlank()) { "this card does not name a character" }
        return CharacterCardPreview(
            id = character.id.value,
            name = character.name,
            description = character.description,
            personality = character.personality,
            scenario = extractScenario(json),
            greeting = character.greeting,
            exampleDialogue = character.exampleDialogue,
            creator = extractField(json, "creator"),
            characterVersion = extractField(json, "character_version"),
            creatorNotes = extractField(json, "creator_notes"),
            tags = character.tags,
            loreEntries = character.loreEntries,
            sourceName = sourceName,
            fromPng = fromPng,
        )
    }

    /**
     * A tEXt chunk is `keyword\0text`; an iTXt chunk adds compression and language tags
     * between the keyword and the text. Both shapes appear in real cards, and the iTXt form
     * is the one that silently produced empty descriptions when only tEXt was handled.
     */
    private fun decodeChunk(bytes: ByteArray, type: String, start: Int, length: Int): String? {
        val end = start + length
        val keywordEnd = (start until end).firstOrNull { bytes[it] == 0.toByte() } ?: return null
        val keyword = String(bytes, start, keywordEnd - start, Charsets.US_ASCII)
        if (keyword != CHARA_KEYWORD && keyword != CCV3_KEYWORD) return null

        val payloadStart = keywordEnd + 1
        if (payloadStart >= end) return null

        // The keyword and the chunk type are independent. Both `chara` and `ccv3` appear in
        // plain `tEXt` chunks in the wild - the newer keyword is not an iTXt marker - so the
        // extra iTXt fields are skipped based on the *chunk type*, not the keyword. Reading
        // them off the keyword instead is what made ccv3 cards fail with a JSON error two
        // bytes into the payload.
        val text = if (type == "iTXt") {
            // compression flag, compression method, language tag\0, translated keyword\0
            var cursor = payloadStart + 2
            cursor = skipUntilNul(bytes, cursor, end) + 1 // language tag
            val translated = skipUntilNul(bytes, cursor, end)
            if (translated + 1 >= end) return null
            // The translated keyword must actually be empty for this to be the card payload;
            // anything else means this is some other iTXt chunk that happened to say "ccv3".
            if (translated > cursor) return null
            translated + 1
        } else {
            payloadStart
        }

        if (text <= 0 || text >= end) return null
        // Not truncated at the first newline.
        //
        // Card exporters MIME-wrap their base64 at 76 characters, so a real card's payload
        // routinely contains newlines. Cutting at the first one produced a JSON document
        // that ended mid-string, and the failure surfaced as a parse error about a quote
        // rather than as "this is not a card" - which is the kind of misdiagnosis that makes
        // an import look broken for reasons the user cannot act on.
        //
        // [decodeBase64] uses the MIME decoder, which ignores line breaks on its own.
        val base64 = String(bytes, text, end - text, Charsets.US_ASCII)
            .trim()
        if (base64.isEmpty()) return null
        return decodeBase64(base64)
    }

    private fun skipUntilNul(bytes: ByteArray, from: Int, end: Int): Int {
        var cursor = from
        while (cursor < end && bytes[cursor] != 0.toByte()) cursor++
        return cursor
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    /**
     * Standard base64, decoded through the JDK.
     *
     * `java.util.Base64` rather than a hand-rolled decoder, and a MIME decoder because card
     * exporters wrap long payloads at 76 characters. `withPadding` is not used because
     * `getMimeDecoder` tolerates missing padding, which real exports do omit.
     */
    private fun decodeBase64(value: String): String {
        require(value.length <= MAX_CARD_JSON_BYTES) { "this file's card data is implausibly large" }
        val decoded = java.util.Base64.getMimeDecoder().decode(value)
        return String(decoded, Charsets.UTF_8)
    }

    /**
     * Reads `scenario` for the preview.
     *
     * The importer folds `scenario` into `background` together with the creator notes,
     * which is right for a character but loses the distinction. The preview shows them
     * apart, because "this character is in this situation" and "the author of this card
     * wrote this" are different things to a user deciding whether to import.
     */
    private fun extractScenario(json: String): String = extractField(json, "scenario")

    /** A single string field from the card's `data` block, or from the root for v1 cards. */
    private fun extractField(json: String, key: String): String = runCatching {
        val root = json5.parseToJsonElement(json) as? JsonObject ?: return@runCatching ""
        // v2 wraps everything in `data`; a v1 card puts the fields at the root.
        val data = root["data"] as? JsonObject ?: root
        data[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    }.getOrDefault("")

    private val json5 = Json { ignoreUnknownKeys = true; isLenient = true }
}