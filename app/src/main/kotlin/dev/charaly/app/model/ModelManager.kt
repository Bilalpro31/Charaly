package dev.charaly.app.model

import android.content.Context
import android.net.Uri
import dev.charaly.runtime.compat.GgufMetadataReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * Local model management.
 *
 * Rules this class enforces:
 *  * nothing is downloaded, ever;
 *  * no desktop-style paths are hardcoded - Android storage APIs only;
 *  * the selected model survives an application restart (persisted path);
 *  * a model is referenced by a real file the app can actually read;
 *  * imported files and catalog models end up in ONE registry, so the library has a
 *    single list instead of "imported models" and "downloaded models".
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
     * Reconciles the files on disk with the registry.
     *
     * Called on every launch. A file the user deleted externally disappears from
     * the library; a file that exists but has no registry entry gets added. That is
     * what makes "installed" mean something.
     */
    suspend fun syncRegistry(registry: dev.charaly.runtime.model.ModelRegistry) {
        val onDisk = managedModels().associateBy { it.absolutePath }
        val registered = registry.list().associateBy { it.absolutePath }

        // Drop registry entries whose file is gone.
        registered.keys.filterNot { it in onDisk }.forEach { path ->
            registry.remove(registered.getValue(path).id)
        }

        // Add registry entries for files that are not registered yet.
        onDisk.keys.filterNot { it in registered }.forEach { path ->
            val entry = onDisk.getValue(path)
            registry.register(toInstalled(entry, dev.charaly.runtime.model.ModelOrigin.IMPORTED, registry))
        }
    }

    /**
     * Registers an imported file as an [dev.charaly.runtime.model.InstalledModel].
     *
     * The id is derived from the absolute path, so re-importing the same file is
     * idempotent and a StoryInstance bound to it keeps working.
     */
    suspend fun registerImported(
        entry: ModelEntry,
        registry: dev.charaly.runtime.model.ModelRegistry,
        catalog: dev.charaly.runtime.model.ModelCatalog,
    ): dev.charaly.runtime.model.InstalledModel = toInstalled(
        entry = entry,
        origin = dev.charaly.runtime.model.ModelOrigin.IMPORTED,
        registry = registry,
        catalogHint = catalog.byId(catalogEntryIdFor(entry.displayName)),
    )

    /** Records a successful header read, so the detail screen can show real metadata. */
    suspend fun verifyInRegistry(
        registry: dev.charaly.runtime.model.ModelRegistry,
        modelId: String,
        architecture: String,
        quantization: String,
        contextLength: Int,
    ) {
        val model = registry.get(modelId) ?: return
        registry.update(
            model.copy(
                architecture = architecture.ifBlank { model.architecture },
                quantization = quantization.ifBlank { model.quantization },
                contextLength = contextLength.takeIf { it > 0 } ?: model.contextLength,
                verified = true,
                sha256 = model.sha256.ifBlank { "header-read" },
                compatibility = model.compatibility.copy(loadFailed = false),
            ),
        )
    }

    /** Marks a model as failed, with a human reason rather than an engine error. */
    suspend fun markLoadFailure(
        registry: dev.charaly.runtime.model.ModelRegistry,
        modelId: String,
        reason: String,
    ) {
        val model = registry.get(modelId) ?: return
        registry.update(
            model.copy(
                compatibility = model.compatibility.copy(loadFailed = true, failureReason = reason),
            ),
        )
    }

    /** Marks a model as successfully loaded at least once. */
    suspend fun markLoadSuccess(
        registry: dev.charaly.runtime.model.ModelRegistry,
        modelId: String,
    ) {
        val model = registry.get(modelId) ?: return
        registry.update(
            model.copy(
                compatibility = model.compatibility.copy(
                    loadFailed = false,
                    failureReason = "",
                    lastLoadedAtEpochMs = System.currentTimeMillis(),
                ),
            ),
        )
    }

    /**
     * Best-effort free memory, used only to warn about a model that clearly will
     * not fit. Never used to refuse to try a load.
     */
    fun availableRamBytes(): Long = runCatching {
        val info = android.app.ActivityManager.MemoryInfo()
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        manager.getMemoryInfo(info)
        info.availMem
    }.getOrDefault(0L)

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

    /** Removes the file behind a registry entry, and forgets the selection. */
    fun delete(model: dev.charaly.runtime.model.InstalledModel) {
        val file = File(model.absolutePath)
        if (file.parentFile == managedDir.absoluteFile) {
            file.delete()
        }
        if (selectedModel()?.absolutePath == model.absolutePath) clearSelection()
    }

    fun importedSizeBytes(): Long = managedModels().sumOf { it.sizeBytes }

    // ---- registry mapping ------------------------------------------------

    /**
     * A stable id for a file.
     *
     * Derived from the absolute path, so importing the same model twice reuses the
     * same registry entry and existing story bindings stay valid.
     */
    private fun entryId(entry: ModelEntry): String {
        val slug = entry.displayName.lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .split('-')
            .filter { it.isNotEmpty() }
            .joinToString("-")
            .take(48)
            .ifBlank { "model" }
        return "local-$slug-${abs(entry.absolutePath.hashCode())}"
    }

    private fun catalogEntryIdFor(displayName: String): String {
        val head = displayName.lowercase().substringBefore(' ')
        if (head.isBlank()) return ""
        return dev.charaly.runtime.model.BuiltInModelCatalog.DEFAULT
            .firstOrNull { candidate -> candidate.name.lowercase().contains(head) }
            ?.id
            .orEmpty()
    }

    /**
     * Maps a file on disk to a registry entry.
     *
     * File size is real. Architecture and quantization are filled in from the GGUF
     * header when [verifyInRegistry] runs; until then they stay unknown rather than
     * being guessed from the file name.
     */
    private suspend fun toInstalled(
        entry: ModelEntry,
        origin: dev.charaly.runtime.model.ModelOrigin,
        registry: dev.charaly.runtime.model.ModelRegistry,
        catalogHint: dev.charaly.runtime.model.ModelCatalogItem? = null,
    ): dev.charaly.runtime.model.InstalledModel {
        val id = entryId(entry)
        val existing = registry.get(id)
        val model = existing?.copy(
            displayName = entry.displayName,
            sizeBytes = entry.sizeBytes,
            origin = origin,
        ) ?: dev.charaly.runtime.model.InstalledModel(
            id = id,
            displayName = entry.displayName,
            absolutePath = entry.absolutePath,
            sizeBytes = entry.sizeBytes,
            origin = origin,
            catalogId = catalogHint?.id.orEmpty(),
            contextLength = catalogHint?.contextLength ?: 2048,
            parameterCount = catalogHint?.parameterCount ?: 0L,
            installedAtEpochMs = System.currentTimeMillis(),
            profiles = dev.charaly.runtime.model.ModelProfileLibrary.all
                .map { it.copy(id = "${it.id}-$id", isBuiltIn = false, storyPackId = null) }
                .take(3),
        )
        select(ModelEntry(entry.absolutePath, entry.displayName, entry.sizeBytes))
        return registry.register(model)
    }

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
