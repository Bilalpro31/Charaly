package dev.charaly.app.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Reads a file the user picked through the system picker.
 *
 * ## Why this is a port
 *
 * A Storage Access Framework read needs a `Context`, a content provider and a real
 * permission grant. The character import flow's *logic* - read, parse, preview, confirm,
 * cancel, and the guarantee that cancelling writes nothing - needs none of those, and is
 * worth testing without any of them. So the view model takes this interface and the
 * Android implementation supplies the resolver.
 *
 * It also means a test can hand in a file that is not a character card and assert on the
 * sentence the user is shown, which is the part most likely to be wrong.
 */
interface CardFileReader {

    /**
     * The file's display name, or empty when the provider will not say.
     *
     * Only ever used for the preview's provenance line. It never reaches the character's
     * id, so importing the same card from two filenames cannot create two characters.
     */
    fun displayName(uri: Uri): String

    /**
     * The file's bytes, or throws.
     *
     * Bounded by [maxBytes]: a card is base64 JSON measured in kilobytes, so the cap is
     * three orders of magnitude of headroom, and its job is to make a mis-tap on a video a
     * clean refusal rather than an out-of-memory crash.
     */
    fun read(uri: Uri): ByteArray
}

/** The real implementation: the Storage Access Framework, with no storage permission asked for. */
class ContentResolverCardFileReader(
    private val context: Context,
    private val maxBytes: Int = MAX_CARD_BYTES,
) : CardFileReader {

    override fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull().orEmpty()

    override fun read(uri: Uri): ByteArray {
        val stream = context.contentResolver.openInputStream(uri)
            ?: error("could not open the selected file")
        return stream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                require(total <= maxBytes) { "that file is too large to be a character card" }
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
    }
}