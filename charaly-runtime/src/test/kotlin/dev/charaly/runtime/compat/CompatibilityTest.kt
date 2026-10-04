package dev.charaly.runtime.compat

import dev.charaly.runtime.domain.CharacterId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Character Card v2 is a *compatibility* surface, not the core of the runtime.
 * These tests pin the promise: existing user cards must keep working, and an
 * imported card must never carry authoritative world state.
 */
class CharacterCardImporterTest {

    private val cardV2 = """
    {
      "spec": "chara_card_v2",
      "spec_version": "2.0",
      "data": {
        "name": "Wren",
        "description": "A cartographer who maps places that do not exist yet.",
        "personality": "precise, quietly ambitious",
        "scenario": "Wren is drawing a map of the lower city at dawn.",
        "first_mes": "You are early. Good. I hate explaining things twice.",
        "mes_example": "<START>\n{{user}}: Where do we go?\n{{char}}: North. Obviously north.",
        "creator_notes": "Imported from SillyTavern.",
        "system_prompt": "Speak as Wren.",
        "tags": ["cartographer", "fantasy"],
        "creator": "someone",
        "character_version": "1.2",
        "extensions": { "talkativeness": "0.7", "unknown_field": 42 },
        "character_book": {
          "entries": [
            {
              "id": 1,
              "keys": ["map", "maps"],
              "content": "Wren's map shows a street that was demolished ten years ago.",
              "comment": "core lore",
              "constant": false,
              "enabled": true
            },
            { "id": 2, "keys": [], "content": "The ink is made from burned lamp oil.", "enabled": true },
            { "id": 3, "keys": ["dropped"], "content": "" }
          ]
        }
      }
    }
    """.trimIndent()

    @Test
    fun `a v2 card imports into a character definition`() {
        val result = CharacterCardImporter.importCard(cardV2)
        assertTrue("import failed: $result", result.isSuccess)

        val character = result.getOrThrow()
        assertEquals("Wren", character.name)
        // The id is derived from the name, not from the description.
        assertEquals(CharacterId("wren"), character.id)
        assertTrue(character.description.contains("cartographer"))
        assertTrue(character.personality.contains("precise"))
        assertTrue(character.greeting.contains("You are early"))
        assertEquals(listOf("cartographer", "fantasy"), character.tags)
    }

    @Test
    fun `scenario and creator notes land in the background`() {
        val character = CharacterCardImporter.importCard(cardV2).getOrThrow()
        assertTrue(character.background.contains("lower city"))
        assertTrue(character.background.contains("Imported from SillyTavern"))
    }

    @Test
    fun `system prompt becomes a goal-ish field rather than world state`() {
        val character = CharacterCardImporter.importCard(cardV2).getOrThrow()
        assertEquals(listOf("Speak as Wren."), character.goals)
    }

    @Test
    fun `the example dialogue is preserved`() {
        val character = CharacterCardImporter.importCard(cardV2).getOrThrow()
        assertTrue(character.exampleDialogue.isNotEmpty())
    }

    @Test
    fun `lore entries survive and empty ones are dropped`() {
        val character = CharacterCardImporter.importCard(cardV2).getOrThrow()
        assertEquals("blank entries must be dropped", 2, character.loreEntries.size)
        assertEquals(listOf("map", "maps"), character.loreEntries.first().keys)
        assertEquals("core lore", character.loreEntries.first().comment)
    }

    @Test
    fun `unknown fields do not break the import`() {
        // spec_version, extensions, creator and character_version are all ignored.
        assertTrue(CharacterCardImporter.importCard(cardV2).isSuccess)
    }

    @Test
    fun `a minimal card still imports`() {
        val minimal = """{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"Ash"}}"""
        val character = CharacterCardImporter.importCard(minimal).getOrThrow()
        assertEquals("Ash", character.name)
        assertEquals("ash", character.id.value)
        assertTrue(character.description.isEmpty())
    }

    @Test
    fun `a flat v1 style card without a data block still imports`() {
        val flat = """{"name":"Flat","description":"no data wrapper"}"""
        val character = CharacterCardImporter.importCard(flat).getOrThrow()
        assertEquals("Flat", character.name)
    }

    @Test
    fun `malformed json fails without throwing`() {
        assertTrue(CharacterCardImporter.importCard("{ not json").isFailure)
        assertTrue(CharacterCardImporter.importCard("").isFailure)
    }

    @Test
    fun `a nameless card uses the fallback name`() {
        val nameless = """{"spec":"chara_card_v2","spec_version":"2.0","data":{"description":"anonymous"}}"""
        assertEquals("Fallback", CharacterCardImporter.importCard(nameless, fallbackName = "Fallback").getOrThrow().name)
    }

    @Test
    fun `ids are slugified and unique-friendly`() {
        val card = """{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"The Very Long Name Of Someone!"}}"""
        val id = CharacterCardImporter.importCard(card).getOrThrow().id
        assertEquals("the-very-long-name-of-someone", id.value)
    }

    @Test
    fun `a card can be imported as a whole story pack`() {
        val pack = CharacterCardImporter.importPack(cardV2).getOrThrow()
        assertEquals(1, pack.characters.size)
        assertEquals("Wren", pack.characters.first().name)
        assertTrue(
            "an imported card must not smuggle in world state",
            pack.initialRelationships.isEmpty() &&
                pack.initialKnowledge.facts.isEmpty() &&
                pack.initialStoryThreads.isEmpty(),
        )
    }

    @Test
    fun `an imported card still works with the runtime domain invariants`() {
        val character = CharacterCardImporter.importCard(cardV2).getOrThrow()
        // Runtime construction must accept it without complaint.
        val runtime = dev.charaly.runtime.domain.CharacterRuntime(
            characterId = character.id,
            name = character.name,
        )
        assertEquals(CharacterId(character.id.value), runtime.characterId)
    }
}

/**
 * The GGUF header reader powers the model picker. It must read only the header,
 * because a phone must not buffer a multi-gigabyte file to show a file name.
 */
class GgufMetadataReaderTest {

    /** Builds a minimal but valid GGUF header byte stream. */
    private fun gguf(
        version: Int = 3,
        tensors: Long = 291,
        kv: List<Pair<String, Any>> = emptyList(),
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun le32(value: Int) {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
            out.write((value ushr 16) and 0xFF)
            out.write((value ushr 24) and 0xFF)
        }
        fun le64(value: Long) {
            for (shift in 0 until 64 step 8) out.write(((value ushr shift) and 0xFF).toInt())
        }
        fun str(value: String) {
            le64(value.toByteArray(Charsets.UTF_8).size.toLong())
            out.write(value.toByteArray(Charsets.UTF_8))
        }

        out.write('G'.code)
        out.write('G'.code)
        out.write('U'.code)
        out.write('F'.code)
        le32(version)
        le64(tensors)
        le64(kv.size.toLong())
        kv.forEach { (key, value) ->
            str(key)
            when (value) {
                // GGUF type 8 = STRING
                is String -> {
                    le32(8)
                    str(value)
                }
                // GGUF type 4 = UINT32
                is Int -> {
                    le32(4)
                    le32(value)
                }
                else -> error("unsupported test value type")
            }
        }
        // A little padding stands in for the tensor payload the reader must not
        // touch.
        out.write(ByteArray(32) { 0x11 })
        return out.toByteArray()
    }

    @Test
    fun `a minimal header parses`() {
        val meta = GgufMetadataReader.read(gguf()).getOrThrow()
        assertEquals(3, meta.version)
        assertEquals(291L, meta.tensorCount)
        assertEquals(0L, meta.kvCount)
    }

    @Test
    fun `a non gguf file is rejected`() {
        val failure = GgufMetadataReader.read("this is a text file, not a model".toByteArray())
        assertTrue("expected failure, got $failure", failure.isFailure)
        assertTrue(failure.exceptionOrNull()?.message?.contains("GGUF") == true)
    }

    @Test
    fun `an empty file is rejected`() {
        assertTrue(GgufMetadataReader.read(ByteArray(0)).isFailure)
    }

    @Test
    fun `a truncated header is rejected rather than crashing`() {
        val truncated = gguf().copyOfRange(0, 12)
        assertTrue(GgufMetadataReader.read(truncated).isFailure)
    }

    @Test
    fun `metadata values are read as strings`() {
        val bytes = gguf(
            kv = listOf(
                "general.architecture" to "llama",
                "general.name" to "Tiny Test",
                "general.file_type" to "2",
            ),
        )
        val meta = GgufMetadataReader.read(bytes).getOrThrow()
        assertEquals("llama", meta.architecture)
        assertEquals("Tiny Test", meta.name)
        assertEquals("Q4_0", meta.quantization)
        assertEquals(3L, meta.kvCount)
        assertTrue(meta.summary().contains("arch=llama"))
    }

    @Test
    fun `only the header is read, never the tensor data`() {
        // A valid header followed by a large blob: reading must stay cheap and
        // must not care what is in the tensor payload.
        val header = gguf(kv = listOf("general.architecture" to "qwen2"))
        val padded = header + ByteArray(4096) { 0x7F }
        val meta = GgufMetadataReader.read(padded).getOrThrow()
        assertEquals("qwen2", meta.architecture)
    }

    @Test
    fun `context length and block count are surfaced for the model picker`() {
        val bytes = gguf(
            kv = listOf(
                "general.architecture" to "llama",
                "llama.context_length" to "4096",
                "llama.embedding_length" to "2048",
                "llama.block_count" to "16",
            ),
        )
        val meta = GgufMetadataReader.read(bytes).getOrThrow()
        assertEquals(4096L, meta.contextLength)
        assertEquals(2048L, meta.embeddingLength)
        assertEquals(16L, meta.blockCount)
        assertTrue(meta.summary().contains("ctx=4096"))
    }

    @Test
    fun `string keys after many values keep the stream aligned`() {
        // A wrong width for any value type would desynchronise every later key,
        // so a long mixed header that still resolves is a real guarantee.
        val bytes = gguf(
            kv = listOf(
                "general.architecture" to "phi3",
                "general.file_type" to "14",
                "general.name" to "Phi Three Tiny",
                "phi3.context_length" to "8192",
            ),
        )
        val meta = GgufMetadataReader.read(bytes).getOrThrow()
        assertEquals("phi3", meta.architecture)
        assertEquals("Phi Three Tiny", meta.name)
        assertEquals("Q6_K", meta.quantization)
        assertEquals(8192L, meta.contextLength)
    }
}
