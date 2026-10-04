package dev.charaly.app.model

import android.content.Context
import android.net.Uri
import dev.charaly.runtime.compat.GgufMetadataReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Local model management.
 *
 * Rules this class enforces:
 *  * nothing is downloaded, ever;
 *  * no desktop-style paths are hardcoded - Android storage APIs only;
 *  * the selected model survives an application restart (persisted path);
 *  * a model is referenced by a real file the app can actually read.
 */
class ModelManager(
    private val context: Context,
    private val modelsDir: File = File(context.filesDir, "models"),
) {

    private val settings = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Directory Charaly owns and can always read without extra permissions. */
    val managedDir: File get() = modelsDir.also { it.mkdirs() }

    /** Models imported into the app's own storage. */
    fun managedModels(): List<ModelEntry> = managedDir.listFiles()
        ?.filter { it.isFile && it.name.endsWith(".gguf", ignoreCase = true) }
        ?.map { it.toEntry() }
        ?.sortedBy { it.displayName.lowercase() }
        .orEmpty()

    /**
     * The currently selected model, or null.
     *
     * A previously selected file that has since been deleted resolves to null
     * rather than crashing: the UI then prompts the user to pick another one.
     */
    fun selectedModel(): ModelEntry? {
        val path = settings.getString(KEY_SELECTED, null) ?: return null
        val file = File(path)
        if (!file.isFile || !file.canRead()) return null
        return file.toEntry()
    }

    fun select(model: ModelEntry) {
        settings.edit().putString(KEY_SELECTED, model.absolutePath).apply()
    }

    fun clearSelection() {
        settings.edit().remove(KEY_SELECTED).apply()
    }

    /**
     * Imports a GGUF the user picked through the Storage Access Framework.
     *
     * The file is COPIED into app-private storage rather than referenced in
     * place: a persisted reference to a content:// URI breaks as soon as the
     * grant is revoked, which would silently break the story engine later.
     */
    suspend fun importFrom(uri: Uri): Result<ModelEntry> = withContext(Dispatchers.IO) {
        runCatching {
            managedDir.mkdirs()
            val displayName = queryDisplayName(uri) ?: "model.gguf"
            val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                .let { if (it.endsWith(".gguf", ignoreCase = true)) it else "$it.gguf" }

            val destination = uniqueFile(File(managedDir, safeName))
            context.contentResolver.openInputStream(uri)?.use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE)
                }
            } ?: error("could not open the selected file")

            if (!isGguf(destination)) {
                destination.delete()
                error("that file is not a GGUF model")
            }
            destination.toEntry()
        }
    }

    /** Reads the GGUF header for the model picker. Header only, never weights. */
    suspend fun readMetadata(model: ModelEntry): Result<GgufMetadataReader.Metadata> = withContext(Dispatchers.IO) {
        runCatching {
            File(model.absolutePath).inputStream().use { stream ->
                GgufMetadataReader.read(stream).getOrThrow()
            }
        }
    }

    fun delete(model: ModelEntry) {
        val file = File(model.absolutePath)
        if (file.parentFile == managedDir.absoluteFile) {
            file.delete()
        }
        if (selectedModel()?.absolutePath == model.absolutePath) clearSelection()
    }

    fun importedSizeBytes(): Long = managedModels().sumOf { it.sizeBytes }

    private fun uniqueFile(candidate: File): File {
        if (!candidate.exists()) return candidate
        val base = candidate.nameWithoutExtension
        val extension = candidate.extension
        var index = 1
        while (true) {
            val next = File(candidate.parentFile, "$base-$index.$extension")
            if (!next.exists()) return next
            index++
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    private fun isGguf(file: File): Boolean = runCatching {
        file.inputStream().use { stream ->
            val header = ByteArray(4)
            if (stream.read(header) != 4) return false
            header[0] == 'G'.code.toByte() &&
                header[1] == 'G'.code.toByte() &&
                header[2] == 'U'.code.toByte() &&
                header[3] == 'F'.code.toByte()
        }
    }.getOrDefault(false)

    private fun File.toEntry() = ModelEntry(
        absolutePath = absolutePath,
        displayName = nameWithoutExtension,
        sizeBytes = length(),
    )

    companion object {
        const val PREFS = "charaly_models"
        const val KEY_SELECTED = "selected_model"
    }
}

data class ModelEntry(
    val absolutePath: String,
    val displayName: String,
    val sizeBytes: Long,
) {
    fun sizeLabel(): String = when {
        sizeBytes >= 1L shl 30 -> "%.1f GB".format(sizeBytes / (1L shl 30))
        sizeBytes >= 1L shl 20 -> "%.0f MB".format(sizeBytes / (1L shl 20))
        else -> "%.0f kB".format(sizeBytes / 1024.0)
    }
}
