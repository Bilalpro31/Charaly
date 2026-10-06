package dev.charaly.app.ui

import android.net.Uri
import dev.charaly.runtime.compat.CharacterCardReader

/**
 * How a character card arrived, and how to get its text back out.
 *
 * ## Why the file type is remembered
 *
 * A card can be a `.json` file or a `.png` with base64 card JSON in a text chunk. The
 * runtime reads both from bytes, so it does not care - but the *library* keeps the card's
 * own text for a future importer version, and for a `.png` that text has to be decoded out
 * of the file rather than read from it.
 *
 * So the decision is made once, here, and the decoded text travels with the record. A
 * stored library entry is always the card itself, never a picture of it.
 */
enum class CharCardFileSource {
    /** A `.json` file: the bytes *are* the card. */
    JSON,

    /** A `.png` card: the card is base64 inside a text chunk and has to be decoded. */
    PNG,

    /** The extension was not recognised, so the bytes are sniffed. */
    UNKNOWN,
    ;

    /**
     * The card's own text.
     *
     * Best effort by design: a decode failure returns empty, and the caller falls back to
     * the raw bytes. Losing the forward-compatibility copy is a small cost; failing an
     * import because of it would not be.
     */
    fun jsonOf(bytes: ByteArray): String = when {
        this == JSON -> bytes.toString(Charsets.UTF_8)
        this == PNG -> runCatching { CharacterCardReader.extractPngCardJson(bytes).getOrThrow() }
            .getOrDefault("")
        // A file whose extension says nothing is worth a sniff: a renamed card is common
        // after a download, and refusing on the extension alone would reject a real card.
        else -> if (CharacterCardReader.isPng(bytes)) {
            runCatching { CharacterCardReader.extractPngCardJson(bytes).getOrThrow() }.getOrDefault("")
        } else {
            bytes.toString(Charsets.UTF_8)
        }
    }

    companion object {
        /** Decides from the file's name, falling back to sniffing the bytes. */
        fun of(uri: Uri): CharCardFileSource = when {
            uri.toString().endsWith(".png", ignoreCase = true) -> PNG
            uri.toString().endsWith(".json", ignoreCase = true) -> JSON
            else -> UNKNOWN
        }
    }
}

/**
 * How much a picked file may weigh before it is refused as "not a character card".
 *
 * A card is base64 JSON - a few kilobytes at most - so 16 MB is roughly three orders of
 * magnitude of headroom. The limit exists so a mis-tap on a video cannot bring the process
 * down trying to hold it in memory, and it is enforced while reading rather than after,
 * because by the time a 4 GB file has been read the problem has already happened.
 */
internal const val MAX_CARD_BYTES = 16 * 1024 * 1024