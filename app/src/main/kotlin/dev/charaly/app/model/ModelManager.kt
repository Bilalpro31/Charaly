package dev.charaly.app.model

import android.content.Context
import android.net.Uri
import android.util.Log
import dev.charaly.runtime.compat.GgufMetadataReader
import dev.charaly.runtime.model.gguf.GgufCompatibility
import dev.charaly.runtime.model.gguf.GgufReadResult
import dev.charaly.runtime.model.gguf.GgufReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

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
    /**
     * The ONE directory every GGUF lives in.
     *
     * ## Why this is a shared constant and not two defaults
     *
     * Import and download used to write to different directories:
     *
     * ```
     *   ModelManager         filesDir/models            (SAF import)
     *   HuggingFaceServices  filesDir/charaly/models    (catalog download)
     * ```
     *
     * That is not a cosmetic difference. [syncRegistry] reconciles the registry against
     * the files *in this directory* and REMOVES any registry entry whose file is not
     * found - which is correct in isolation, and catastrophic in combination: a model
     * downloaded from the Hub was registered with a path one directory over, so the very
     * next app launch "reconciled" it away. The file stayed on disk, the registry entry
     * did not, and the model silently vanished from the library - the exact failure this
     * whole chain of files exists to prevent.
     *
     * Both writers now resolve this same directory, so "a registered model whose file is
     * gone" means exactly that, and nothing else.
     */
    private val modelsDir: File = CharalyPaths.models(context),
) {

    private val settings = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Directory Charaly owns and can always read without extra permissions. */
    val managedDir: File get() = modelsDir.also { it.mkdirs() }

    /**
     * Models imported into the app's own storage.
     *
     * `.part` files are excluded on purpose. They exist only while a copy or a download is
     * in flight, and letting one through would register a truncated model as installed.
     */
    fun managedModels(): List<ModelEntry> = managedDir.listFiles()
        ?.filter { it.isFile && it.name.endsWith(".gguf", ignoreCase = true) }
        ?.filterNot { it.name.endsWith(PART_SUFFIX, ignoreCase = true) }
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
                sha256 = model.sha256,
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
     * ## Why the file is COPIED rather than referenced
     *
     * A `content://` URI is a *permission*, not a path. The grant dies with the process
     * unless it is persisted, a persisted grant dies when the user revokes access, and a
     * user who picked a file from a removable SD card can physically remove it tomorrow.
     * llama.cpp needs a real, readable, app-owned filesystem path that survives process
     * death - so the bytes are copied into app-private storage, and that copy is the model.
     *
     * ## Why the copy is atomic
     *
     * The bytes go to `<name>.gguf.part` and are renamed into place only after three
     * separate checks pass. This matters because the three ways a large copy fails on a
     * phone all end the same way if you are careless:
     *
     * ```
     *   process killed mid-copy  -> a truncated .gguf in the models directory
     *   storage fills mid-copy    -> a truncated .gguf
     *   user picks a text file   -> a .gguf that is not a model
     * ```
     *
     * Any of those, written straight to the final name, becomes a file the registry
     * registers on the next launch and llama.cpp then tries to load. The `.part` suffix is
     * excluded from [managedModels] for the same reason, so a copy interrupted by process
     * death leaves nothing a later launch could mistake for an installed model - and
     * [cleanupPartialCopies] reclaims the disk it occupied.
     */
    suspend fun importFrom(uri: Uri): Result<ModelEntry> = withContext(Dispatchers.IO) {
        runCatching { importInto(uri) }
    }

    /**
     * The body of [importFrom]: returns the entry, or throws one of the `REASON_*`
     * constants.
     *
     * Split out as its own function so the "this file is already installed" early return
     * can simply *return* an entry, rather than having to rebuild a `Result` to satisfy the
     * enclosing `withContext`.
     */
    private fun importInto(uri: Uri): ModelEntry {
        managedDir.mkdirs()
        val displayName = queryDisplayName(uri) ?: "model.gguf"
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .let { if (it.endsWith(".gguf", ignoreCase = true)) it else "$it.gguf" }

        // Best effort, and deliberately not load-bearing: the copy below does not
        // depend on the grant surviving, so this only helps a later *delete* address
        // the original file. Many providers simply do not offer a persistable grant.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }

        // What the provider claims the file is. Used as an early warning, as the
        // duplicate check below, and as a post-copy cross-check; 0 or -1 just means
        // "this provider will not say".
        val declaredBytes = querySize(uri)
        if (declaredBytes > 0L && !hasRoomFor(declaredBytes)) error(REASON_NO_SPACE)

        // Idempotent re-import.
        //
        // Picking the same file twice used to produce `name-1.gguf` beside `name.gguf`:
        // a second full copy of the same multi-gigabyte weights, a second registry entry
        // with a *different* id, and therefore two "installed" rows for one model. A file
        // of the same name and the same length is the same model, so the existing entry
        // is returned - which also preserves the id a story may already be bound to.
        //
        // Checked against the *canonical* name, before [uniqueFile] has a chance to
        // invent a `-1` suffix, which is what made the duplicates in the first place.
        val canonical = File(managedDir, safeName)
        if (declaredBytes > 0L) {
            managedModels().firstOrNull { existing ->
                existing.displayName.equals(canonical.nameWithoutExtension, ignoreCase = true) &&
                    existing.sizeBytes == declaredBytes
            }?.let { existing ->
                return existing
            }
        }

        val destination = uniqueFile(canonical)
        val partial = File(managedDir, destination.name + PART_SUFFIX)
        Log.i(TAG, "MODEL_IMPORT_START name=${destination.name} declaredBytes=$declaredBytes")

        // SHA-256 is computed from the same stream, one pass, so importing a 4 GB
        // model never buffers the weights in the Java heap: the digest folds each
        // chunk as it is written and nothing but the digest survives.
        val digest = MessageDigest.getInstance("SHA-256")
        val copied = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        total += read
                    }
                    output.flush()
                    // fsync before the rename. Without it a crash in the window between
                    // rename and flush can leave a correctly-named file full of zeroes,
                    // which is a far more confusing failure than a missing one.
                    runCatching { output.fd.sync() }
                    total
                }
            } ?: error(REASON_UNREADABLE)
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }

        // 1. Not empty. A zero-byte .gguf is what a provider hands back when the user
        //    picked something it cannot actually read.
        if (copied <= 0L) {
            partial.delete()
            error(REASON_UNREADABLE)
        }
        // 2. If a size was declared, the copy must match it. A short read is the
        //    signature of an interrupted transfer, and llama.cpp would surface it as an
        //    unreadable file several screens later, with none of this context.
        if (declaredBytes > 0L && copied != declaredBytes) {
            partial.delete()
            error(REASON_PARTIAL)
        }
        // 3. Actually a GGUF, and not just the magic: the whole header must
        //    parse, and the architecture must be classifiable. A file that fails
        //    here must never become a registry entry that a later launch hands
        //    to the native loader.
        val sha256Hex = digest.digest().joinToString("") { "%02x".format(it) }
        Log.i(TAG, "MODEL_COPY_COMPLETE bytes=$copied sha256=${sha256Hex.take(12)}")
        when (val parsed = GgufReader.read(partial.inputStream())) {
            is GgufReadResult.Success -> {
                Log.i(TAG, "MODEL_VALIDATION_COMPLETE architecture=${parsed.metadata.architecture}")
                val verdict = GgufCompatibility.classify(parsed, fileSizeBytes = copied)
                if (verdict.compatibility == dev.charaly.runtime.model.gguf.CharalyCompatibility.UNSUPPORTED) {
                    partial.delete()
                    Log.w(TAG, "MODEL_IMPORT_REJECTED reason=unsupported-architecture architecture=${parsed.metadata.architecture}")
                    error(REASON_UNSUPPORTED)
                }
            }
            is GgufReadResult.Failure -> {
                partial.delete()
                Log.w(TAG, "MODEL_IMPORT_REJECTED reason=invalid-gguf detail=${parsed.reason}")
                error(REASON_INVALID_GGUF)
            }
        }

            if (!partial.renameTo(destination)) {
            partial.delete()
            error(REASON_UNREADABLE)
        }
        Log.i(TAG, "MODEL_READY path=${destination.name}")
        return destination.toEntry(sha256Hex)
    }

    /**
     * Deletes `.part` files left behind by a copy the process did not survive.
     *
     * Called on launch. Without it, every process death during a multi-gigabyte import
     * leaks the entire partial file - on a phone that is the difference between being able
     * to import a second model and running out of storage.
     */
    fun cleanupPartialCopies(): Int = managedDir.listFiles()
        ?.filter { it.isFile && it.name.endsWith(PART_SUFFIX, ignoreCase = true) }
        ?.count { it.delete() }
        ?: 0

    /** Whether there is room for [bytes], or the platform would not say. */
    private fun hasRoomFor(bytes: Long): Boolean {
        val usable = runCatching { managedDir.usableSpace }.getOrDefault(-1L)
        // -1 means "unknown", and unknown must not mean "refuse" - a device that declines
        // to report free space would otherwise be unable to import anything at all.
        return usable < 0L || usable >= bytes
    }

    private fun querySize(uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index) else -1L
        } ?: -1L
    }.getOrDefault(-1L)

    /** Reads the GGUF header for the model picker. Header only, never weights. */
    suspend fun readMetadata(model: ModelEntry): Result<GgufMetadataReader.Metadata> = withContext(Dispatchers.IO) {
        runCatching {
            File(model.absolutePath).inputStream().use { stream ->
                GgufMetadataReader.read(stream).getOrThrow()
            }
        }
    }

    fun delete(model: ModelEntry) {
        deleteFileBehind(model.absolutePath)
        if (selectedModel()?.absolutePath == model.absolutePath) clearSelection()
    }

    /** Removes the file behind a registry entry, and forgets the selection. */
    fun delete(model: dev.charaly.runtime.model.InstalledModel) {
        deleteFileBehind(model.absolutePath)
        if (selectedModel()?.absolutePath == model.absolutePath) clearSelection()
    }

    /**
     * Deletes a file, but only if Charaly actually owns it.
     *
     * The guard used to be `file.parentFile == managedDir.absoluteFile`, which compares two
     * `File` instances with `==` - reference equality, not path equality. [managedDir]
     * returns a freshly constructed `File` on every access, so that comparison was
     * **always false**: `delete()` removed the registry entry and left the multi-gigabyte
     * file on disk. A user removing a model freed zero bytes and could not remove it again.
     *
     * `absoluteFile` yields the same instance for the same path, so `==` here finally means
     * what the check was always trying to express.
     */
    private fun deleteFileBehind(absolutePath: String) {
        val file = File(absolutePath)
        if (file.absoluteFile.parentFile == managedDir.absoluteFile) {
            file.delete()
        }
    }

    fun importedSizeBytes(): Long = managedModels().sumOf { it.sizeBytes }

    // ---- registry mapping ------------------------------------------------

    /**
     * A stable id for a file.
     *
     * Derived from the content hash, so importing the same weights under a
     * different path (or after a storage migration) resolves to the same
     * registry entry, and a story bound to it keeps working. The old scheme
     * (`local-<slug>-<abs(path.hashCode())>`) changed the identity whenever the
     * file was renamed or moved, orphaning every story binding in the process.
     */
    internal fun entryId(entry: ModelEntry): String = ModelIds.idFor(entry.displayName, entry.sha256, entry.absolutePath)

    /** Streams the file through SHA-256. Never buffers the file in memory. */
    private suspend fun sha256Of(file: File): String = withContext(Dispatchers.IO) {
        runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrDefault("")
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
        val hashed = if (entry.sha256.isBlank()) {
            entry.copy(sha256 = sha256Of(File(entry.absolutePath)))
        } else {
            entry
        }
        val id = entryId(hashed)
        val existing = registry.get(id)
        val model = existing?.copy(
            displayName = hashed.displayName,
            sizeBytes = hashed.sizeBytes,
            origin = origin,
            sha256 = hashed.sha256.ifBlank { existing.sha256 },
        ) ?: dev.charaly.runtime.model.InstalledModel(
            id = id,
            displayName = hashed.displayName,
            absolutePath = hashed.absolutePath,
            sizeBytes = hashed.sizeBytes,
            sha256 = hashed.sha256,
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

    private fun File.toEntry(sha256: String = "") = ModelEntry(
        absolutePath = absolutePath,
        displayName = nameWithoutExtension,
        sizeBytes = length(),
        sha256 = sha256,
    )

    /**
     * The id is derived from the content hash, so importing the same file twice reuses the
     * same registry entry and existing story bindings stay valid.
     */
    companion object {
        const val TAG = "ModelManager"
        const val PREFS = "charaly_models"
        const val KEY_SELECTED = "selected_model"

        /**
         * Suffix for a transfer that has not finished.
         *
         * Shared with the download pipeline so an interrupted download and an interrupted
         * import are cleaned up by the same rule, and so neither can be registered as a
         * model by the launch-time reconciliation.
         */
        const val PART_SUFFIX = ".part"

        /**
         * Failure reasons, as constants rather than free-text `error(...)` strings.
         *
         * The user never sees these - `CharalyViewModel.importErrorMessage` maps them onto
         * localised sentences - but matching on a literal typed in a throw site is how a
         * wording tweak silently turns an import failure into "something went wrong".
         */
        const val REASON_NOT_GGUF = "not-a-gguf"
        const val REASON_INVALID_GGUF = "invalid-gguf"
        const val REASON_UNSUPPORTED = "unsupported-architecture"
        const val REASON_UNREADABLE = "unreadable"
        const val REASON_PARTIAL = "partial-copy"
        const val REASON_NO_SPACE = "insufficient-storage"
    }
}

data class ModelEntry(
    val absolutePath: String,
    val displayName: String,
    val sizeBytes: Long,
    /** Content hash from the import copy. Blank only for legacy entries. */
    val sha256: String = "",
) {
    fun sizeLabel(): String = when {
        sizeBytes >= 1L shl 30 -> "%.1f GB".format(sizeBytes / (1L shl 30))
        sizeBytes >= 1L shl 20 -> "%.0f MB".format(sizeBytes / (1L shl 20))
        else -> "%.0f kB".format(sizeBytes / 1024.0)
    }
}
