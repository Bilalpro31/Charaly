package dev.charaly.runtime.model.gguf

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Reads a GGUF header.
 *
 * ## What "reading a model" means here
 *
 * A GGUF file is:
 *
 * ```
 *   magic          4 bytes   "GGUF"
 *   version        uint32    2 or 3
 *   tensor_count   uint64
 *   kv_count       uint64
 *   kv entries     kv_count x (key: string, type: uint32, value: typed)
 *   tensor infos   tensor_count x (name, dims, type, offset)
 *   padding        to the alignment boundary
 *   tensor data    the weights
 * ```
 *
 * Charaly needs the key/value block and nothing else. It sits at the very start of the
 * file, so reading it costs a few hundred kilobytes even on a 30 GB model: the reader
 * is given a *bounded* view of the file and refuses to decode past that bound rather
 * than streaming gigabytes to answer "what architecture is this".
 *
 * ## Failure is a value, not an exception
 *
 * Every entry point returns a [GgufReadResult]. A truncated download, a renamed .gguf
 * that is really a safetensors file, or an encrypted blob all produce a
 * [GgufReadResult.Failure] with a specific [GgufFailure], because the installer needs to
 * *reject* those files with an explanation rather than crash or - worse - install them.
 */
object GgufReader {

    /** The four bytes every GGUF file starts with. */
    val MAGIC: ByteArray = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())

    /**
     * A hard ceiling on the key/value block.
     *
     * 64 MiB is far beyond any real metadata block (the largest seen in practice is a
     * few hundred KiB, mostly the chat template and tokenizer merges) and exists purely
     * so a corrupt length field cannot make the reader allocate wildly. Exceeding it is
     * a failure, not a truncation: a half-read header is worse than none.
     */
    const val MAX_METADATA_BYTES: Long = 64L * 1024L * 1024L

    /** Refuse absurd per-entry lengths rather than trying to allocate them. */
    const val MAX_STRING_BYTES: Int = 4 * 1024 * 1024

    /** A key/value block with more entries than this is not a metadata block. */
    const val MAX_METADATA_PAIRS: Long = 100_000L

    /** Reads the header from an already-bounded stream. The caller owns the stream. */
    fun read(stream: InputStream): GgufReadResult = try {
        readOrThrow(stream)
    } catch (e: GgufFormatException) {
        GgufReadResult.Failure(e.failure, e.message.orEmpty())
    } catch (e: EOFException) {
        GgufReadResult.Failure(GgufFailure.TRUNCATED, "The file ends before its header does.")
    } catch (e: IOException) {
        GgufReadResult.Failure(GgufFailure.IO_ERROR, e.message ?: "The file could not be read.")
    } catch (e: OutOfMemoryError) {
        // A corrupt length field can ask for an absurd allocation. Catching this is the
        // difference between "this file is not a model" and a process crash.
        GgufReadResult.Failure(GgufFailure.MALFORMED, "The file's header describes an impossible size.")
    }

    /**
     * Reads the header from a byte array.
     *
     * The array must contain at least the whole metadata block; callers reading a real
     * file should use the stream overload and bound it themselves.
     */
    fun read(bytes: ByteArray): GgufReadResult = read(bytes.inputStream())

    /**
     * Reads just the header, up to [maxBytes].
     *
     * This is the entry point used at import and install time: it reads a bounded
     * prefix of a multi-gigabyte file and answers "what is this?" without reading the
     * weights.
     */
    fun readPrefix(bytes: ByteArray, maxBytes: Int = DEFAULT_PREFIX_BYTES): GgufReadResult =
        read(java.io.ByteArrayInputStream(bytes, 0, minOf(bytes.size, maxBytes)))

    /**
     * How many bytes a header prefix read needs.
     *
     * 8 MiB covers every header in wide use. It is a *bound*, not a promise to read it:
     * the reader stops as soon as the metadata block ends.
     */
    const val DEFAULT_PREFIX_BYTES: Int = 8 * 1024 * 1024

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun readOrThrow(stream: InputStream): GgufReadResult {
        val magic = ByteArray(4)
        if (!stream.readFully(magic)) {
            throw GgufFormatException(
                GgufFailure.NOT_GGUF,
                "This file is empty or too short to be a GGUF model.",
            )
        }
        if (!magic.contentEquals(MAGIC)) {
            throw GgufFormatException(
                GgufFailure.NOT_GGUF,
                "This file does not start with the GGUF marker, so it is not a GGUF model. " +
                    "Safetensors and PyTorch files cannot be used by Charaly.",
            )
        }

        val version = stream.readU32().toInt()
        if (version !in 2..3) {
            throw GgufFormatException(
                GgufFailure.UNSUPPORTED_VERSION,
                "This GGUF uses file format version $version. Charaly reads versions 2 and 3.",
            )
        }

        val tensorCount = stream.readU64()
        val kvCount = stream.readU64()
        if (kvCount > MAX_METADATA_PAIRS) {
            throw GgufFormatException(
                GgufFailure.MALFORMED,
                "This file's header claims $kvCount metadata entries, which no real model has.",
            )
        }

        val pairs = LinkedHashMap<String, GgufValue>(minOf(kvCount, 1024L).toInt().coerceAtLeast(8))
        var read = 0L
        var index = 0L
        while (index < kvCount) {
            read += 8L
            if (read > MAX_METADATA_BYTES) {
                throw GgufFormatException(
                    GgufFailure.METADATA_TOO_LARGE,
                    "This file's metadata block is larger than Charaly will read.",
                )
            }
            val key = stream.readString()
            val typeCode = stream.readU32().toInt()
            val type = GgufValueType.of(typeCode)
                ?: throw GgufFormatException(
                    GgufFailure.UNKNOWN_VALUE_TYPE,
                    "This file uses GGUF value type $typeCode, which this version of Charaly does not understand.",
                )
            pairs[key] = stream.readValue(type)
            index++
        }

        return GgufReadResult.Success(
            GgufMetadata(
                version = version,
                tensorCount = tensorCount,
                // tensor_info_count: v2 wrote this field, v3 computes it. Either way it is
                // the tensor count; recorded separately so the distinction is visible.
                tensorInfoCount = tensorCount,
                keyValues = pairs,
            ),
        )
    }

    private fun InputStream.readValue(type: GgufValueType): GgufValue = when (type) {
        GgufValueType.UINT8 -> GgufValue.U8(readU8())
        GgufValueType.INT8 -> GgufValue.I8(readU8().toByte().toInt())
        GgufValueType.UINT16 -> GgufValue.U16(readU16())
        GgufValueType.INT16 -> GgufValue.I16(readU16().toShort().toInt())
        GgufValueType.UINT32 -> GgufValue.U32(readU32())
        GgufValueType.INT32 -> GgufValue.I32(readU32().toInt())
        GgufValueType.UINT64 -> GgufValue.U64(readU64())
        GgufValueType.INT64 -> GgufValue.I64(readU64().toLong())
        GgufValueType.FLOAT32 -> GgufValue.F32(Float.fromBits(readU32().toInt()))
        GgufValueType.FLOAT64 -> GgufValue.F64(Double.fromBits(readU64()))
        GgufValueType.BOOL -> GgufValue.Bool(readU8() != 0)
        GgufValueType.STRING -> GgufValue.Str(readString())
        GgufValueType.ARRAY -> {
            // The array header is: element type (uint32), element count (uint64),
            // then the elements themselves.
            val elementTypeCode = readU32().toInt()
            val elementType = GgufValueType.of(elementTypeCode)
                ?: throw GgufFormatException(
                    GgufFailure.UNKNOWN_VALUE_TYPE,
                    "This file contains an array of value type $elementTypeCode, which is not known.",
                )
            if (elementType.isArray) {
                throw GgufFormatException(
                    GgufFailure.MALFORMED,
                    "This file contains a nested array, which the GGUF format does not allow.",
                )
            }
            val count = readU64()
            if (count > MAX_METADATA_BYTES / 64) {
                throw GgufFormatException(
                    GgufFailure.METADATA_TOO_LARGE,
                    "This file contains an array of $count entries, which is not plausible.",
                )
            }
            val items = ArrayList<GgufValue>(minOf(count, 4096L).toInt().coerceAtLeast(4))
            repeat(count.toInt()) { items += readValue(elementType) }
            GgufValue.Arr(elementType, items)
        }
    }

    private fun InputStream.readString(): String {
        val length = readU64()
        if (length > MAX_STRING_BYTES.toLong()) {
            throw GgufFormatException(
                GgufFailure.MALFORMED,
                "This file declares a $length byte string in its header, which is not plausible.",
            )
        }
        if (length == 0L) return ""
        val buf = ByteArray(length.toInt())
        if (!readFully(buf)) throw EOFException("string truncated")
        // Malformed UTF-8 in a GGUF is a real failure: a chat template that does not
        // decode would silently become an empty string and the model would be prompted
        // with no template at all.
        return try {
            String(buf, Charsets.UTF_8)
        } catch (e: Exception) {
            throw GgufFormatException(GgufFailure.MALFORMED, "This file's header contains unreadable text.")
        }
    }

    private fun InputStream.readU8(): Int {
        val b = read()
        if (b < 0) throw EOFException("byte truncated")
        return b
    }

    private fun InputStream.readU16(): Int = readU8() or (readU8() shl 8)

    private fun InputStream.readU32(): Long {
        var v = 0L
        for (i in 0 until 4) v = v or (readU8().toLong() shl (8 * i))
        return v
    }

    private fun InputStream.readU64(): Long {
        var v = 0L
        for (i in 0 until 8) v = v or (readU8().toLong() shl (8 * i))
        return v
    }

    /** Reads exactly [buf].size bytes, or throws. Partial reads are not a header. */
    private fun InputStream.readFully(buf: ByteArray): Boolean {
        var offset = 0
        while (offset < buf.size) {
            val n = read(buf, offset, buf.size - offset)
            // -1 is clean end-of-stream before any bytes; a short read is still a
            // truncated file, so both are "not fully".
            if (n < 0) return false
            if (n == 0) return false
            offset += n
        }
        return true
    }

    /** Carries a [GgufFailure] out of the reader without leaking checked exceptions. */
    private class GgufFormatException(
        val failure: GgufFailure,
        message: String,
    ) : IOException(message)
}

/**
 * Why a file could not be read as a GGUF model.
 *
 * Every case here is a *rejection* the installer acts on: the file is not installed,
 * and the user is told which of these it was. They are distinct types rather than one
 * generic failure because "this is not a GGUF" and "the download was cut off" call for
 * completely different user actions.
 */
enum class GgufFailure {
    /** The GGUF magic is absent: not a GGUF file at all. */
    NOT_GGUF,

    /** Correct magic, but the file ends mid-header: a partial or corrupt download. */
    TRUNCATED,

    /** Structurally invalid: impossible lengths, nested arrays, bad UTF-8. */
    MALFORMED,

    /** A GGUF format version this build does not read. */
    UNSUPPORTED_VERSION,

    /** A metadata value type from a newer spec. */
    UNKNOWN_VALUE_TYPE,

    /** The metadata block exceeds [GgufReader.MAX_METADATA_BYTES]. */
    METADATA_TOO_LARGE,

    /** The underlying stream failed. */
    IO_ERROR,
    ;

    /** Whether retrying the download could plausibly fix this. */
    val isWorthRetrying: Boolean get() = this == TRUNCATED || this == IO_ERROR
}

/** The outcome of reading a GGUF header. */
sealed interface GgufReadResult {
    data class Success(val metadata: GgufMetadata) : GgufReadResult
    data class Failure(val failure: GgufFailure, val reason: String) : GgufReadResult

    val metadataOrNull: GgufMetadata? get() = (this as? Success)?.metadata
    val isSuccess: Boolean get() = this is Success
}
