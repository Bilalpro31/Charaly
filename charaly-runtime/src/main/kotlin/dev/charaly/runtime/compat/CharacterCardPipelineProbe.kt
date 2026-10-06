package dev.charaly.runtime.compat

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Builds PNG files carrying character cards, byte for byte.
 *
 * ## Why this is in main rather than in a test
 *
 * Because the only way to be sure the reader parses the real format is to hand it a real
 * file. A fixture that mocked the chunk structure would test the parser against the test's
 * own idea of that structure - which is precisely the bug class that produced blank card
 * descriptions before: a reader that only handled the shape its own fixture used.
 *
 * It is also genuinely useful outside tests: an authoring or import screen could offer to
 * export a card, and it would then need exactly this.
 */
object CharacterCardPipelineProbe {

    /** A card as a JSON payload inside a PNG `tEXt` chunk, which is how SillyTavern stores it. */
    fun pngWithCardJson(json: String, keyword: String = CharacterCardReader.CHARA_KEYWORD): ByteArray {
        val base64 = java.util.Base64.getMimeEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        val text = keyword.toByteArray(Charsets.ISO_8859_1) +
            0 +
            base64.toByteArray(Charsets.ISO_8859_1)
        return png(extraChunks = listOf("tEXt" to text))
    }

    /** The same card in an `iTXt` chunk, whose layout has two extra NUL-terminated fields. */
    fun pngWithCardJsonCompressed(json: String, keyword: String = CharacterCardReader.CHARA_KEYWORD): ByteArray {
        val base64 = java.util.Base64.getMimeEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        // iTXt layout, in the spec's order:
        //   keyword \0 compression flag compression method language tag \0
        //   translated keyword \0 text
        val payload = keyword.toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(
                0, // keyword terminator
                0, // compression flag: not compressed
                0, // compression method: deflate, unused when the flag is 0
                0, // language tag terminator (empty language)
                0, // translated keyword terminator (no translation)
            ) +
            base64.toByteArray(Charsets.ISO_8859_1)
        return png(extraChunks = listOf("iTXt" to payload))
    }

    /** A structurally valid PNG that carries no card at all. */
    fun pngWithoutCard(): ByteArray = png(
        extraChunks = listOf("tEXt" to ("Comment".toByteArray(Charsets.ISO_8859_1) + 0 + "holiday".toByteArray())),
    )

    // -------------------------------------------------------------------- private

    private fun png(extraChunks: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

        // IHDR: 1x1, 8-bit, truecolour. Not a meaningful image - nothing decodes it - but a
        // PNG without it is not a PNG, and the reader should not have to care either way.
        val header = ByteArrayOutputStream().apply {
            writeInt(1); writeInt(1); write(8); write(2); write(0); write(0); write(0)
        }.toByteArray()
        out.write(chunk("IHDR", header))

        extraChunks.forEach { (type, data) -> out.write(chunk(type, data)) }

        // A real IDAT, so the reader has to skip image data rather than assume the card is
        // the last chunk it sees.
        out.write(chunk("IDAT", zlib(byteArrayOf(0, 1, 2, 3))))
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
        val body = ByteArrayOutputStream().apply {
            writeInt(data.size)
            write(typeBytes)
            write(data)
        }
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        body.writeInt(crc.value.toInt())
        return body.toByteArray()
    }

    /** Enough of a zlib stream for the bytes to be skipped by length. */
    private fun zlib(bytes: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(bytes)
        deflater.finish()
        val out = ByteArrayOutputStream()
        out.write(0x78)
        out.write(0x01)
        val buffer = ByteArray(64)
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer))
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write(value ushr 24 and 0xFF)
        write(value ushr 16 and 0xFF)
        write(value ushr 8 and 0xFF)
        write(value and 0xFF)
    }
}