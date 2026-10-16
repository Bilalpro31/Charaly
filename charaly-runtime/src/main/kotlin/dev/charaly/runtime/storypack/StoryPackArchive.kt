package dev.charaly.runtime.storypack

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Reading and writing the `.charaly-pack` container: a ZIP with laws.
 *
 * ## Threat model
 *
 * This file arrived from a browser, a messenger, a USB stick or a catalog mirror. It is
 * hostile input. Concretely, an archive is trying to:
 *
 *  * escape its extraction root (`../../`, absolute paths, `C:\`, backslashes, `\0`);
 *  * decompress into gigabytes (ratio bombs, oversize entries, endless entry counts);
 *  * name the same file twice so the validator and the extractor disagree;
 *  * carry JSON that decodes into megabytes of allocations;
 *  * look like a pack while containing none of a pack.
 *
 * Every one of those is answered by a hard bound and a refusal. Refusals are exceptions
 * with *reasons*, which the validation layer turns into `Kritik Hata` rows - never into a
 * crash and never into a partially installed pack.
 *
 * ## Why entries carry fixed timestamps
 *
 * So the same content exports to the same bytes. Not for the digest (which is computed
 * over the inventory precisely because ZIP bytes are unstable), but so an export is
 * diffable and a re-export is not a spurious change in every version-control system it
 * lands in.
 */
object StoryPackArchive {

    /** Everything an archive held, in memory. JSON texts and binary payloads kept apart. */
    data class Contents(
        val manifest: String?,
        val textFiles: Map<String, String>,
        val assets: Map<String, ByteArray>,
        val entryNames: List<String>,
        /** Non-fatal oddities: unknown files, unexpected shapes. */
        val warnings: List<String>,
    ) {
        /** Every path the archive carried, in the order it appeared. */
        val paths: List<String> get() = entryNames

        fun text(path: String): String? = textFiles[path]
    }

    /** A refused archive. One per hostile-input strategy above. */
    class RefusedException(
        val code: Refusal,
        message: String,
    ) : Exception(message)

    enum class Refusal {
        NOT_AN_ARCHIVE,
        PATH_TRAVERSAL,
        ABSOLUTE_PATH,
        ENTRY_TOO_LARGE,
        ARCHIVE_TOO_LARGE,
        TOO_MANY_ENTRIES,
        ZIP_BOMB,
        DUPLICATE_ENTRY,
        MANIFEST_INVALID,
        MALFORMED_ENTRY,
        ;

        val isSecurity: Boolean
            get() = this == PATH_TRAVERSAL || this == ABSOLUTE_PATH || this == ZIP_BOMB
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /** Serialises `path -> bytes` into archive bytes. Paths must already be safe. */
    fun write(files: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        writeTo(files, out)
        return out.toByteArray()
    }

    /** Streaming export, so a pack with large assets never needs one giant buffer twice. */
    fun writeTo(files: Map<String, ByteArray>, target: OutputStream) {
        val zip = ZipOutputStream(target)
        try {
            files.entries.sortedBy { it.key }.forEach { (path, bytes) ->
                require(StoryPackFormat.isSafeRelativePath(path)) { "unsafe path in pack: $path" }
                val entry = ZipEntry(path)
                // Fixed timestamp: the same content is the same archive.
                entry.time = 0L
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        } finally {
            zip.finish()
        }
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /**
     * Reads an archive defensively.
     *
     * Streams entry by entry and enforces every bound *while* reading, which is the only
     * way a 4 KB file can legitimately claim to expand to 4 GB: it never gets the chance.
     */
    fun read(bytes: ByteArray): Contents = read(ByteArrayInputStream(bytes))

    fun read(source: InputStream): Contents {
        var manifestText: String? = null
        val textFiles = linkedMapOf<String, String>()
        val assets = linkedMapOf<String, ByteArray>()
        val names = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        var totalBytes = 0L

        // Counting the *compressed* bytes consumed is how a ratio bomb is recognised while
        // it is still streaming: ZIP entry headers are allowed to lie about sizes, and a
        // reader that believes them is a reader that allocates before it verifies.
        val counter = CountingInputStream(source)
        val stream = try {
            ZipInputStream(counter.buffered())
        } catch (cause: Exception) {
            throw RefusedException(Refusal.NOT_AN_ARCHIVE, "dosya açılamadı: ${cause.message}")
        }

        try {
            var exhausted = false
            while (!exhausted) {
                val entry = try {
                    stream.nextEntry
                } catch (cause: Exception) {
                    throw RefusedException(Refusal.MALFORMED_ENTRY, "arşiv bozuk: ${cause.message}")
                }
                if (entry == null) {
                    exhausted = true
                    continue
                }
                if (entry.isDirectory) continue

                val path = entry.name.replace('\\', '/')
                if (!StoryPackFormat.isSafeRelativePath(path)) {
                    val refusal = when {
                        path.startsWith("/") || path.contains(':') -> Refusal.ABSOLUTE_PATH
                        else -> Refusal.PATH_TRAVERSAL
                    }
                    throw RefusedException(refusal, "arşivde izin verilmeyen yol: $path")
                }
                if (!seen.add(path)) {
                    throw RefusedException(Refusal.DUPLICATE_ENTRY, "arşivde aynı yola sahip iki dosya var: $path")
                }
                if (names.size >= StoryPackFormat.MAX_ENTRIES) {
                    throw RefusedException(Refusal.TOO_MANY_ENTRIES, "arşivde çok fazla dosya var")
                }
                names += path

                if (entry.size > StoryPackFormat.MAX_ENTRY_BYTES) {
                    throw RefusedException(
                        Refusal.ENTRY_TOO_LARGE,
                        "arşivdeki tek bir dosya izin verilenden büyük: $path",
                    )
                }

                val consumedBefore = counter.count
                val data = readEntry(stream, path)
                val consumed = (counter.count - consumedBefore).coerceAtLeast(1L)
                totalBytes += data.size
                if (totalBytes > StoryPackFormat.MAX_TOTAL_BYTES) {
                    throw RefusedException(Refusal.ARCHIVE_TOO_LARGE, "arşiv açıldığında izin verilenden büyük")
                }

                if (data.size > StoryPackFormat.COMPRESSION_RATIO_GUARD_BYTES) {
                    val ratio = data.size.toDouble() / consumed.toDouble()
                    if (ratio > StoryPackFormat.MAX_COMPRESSION_RATIO) {
                        throw RefusedException(Refusal.ZIP_BOMB, "arşiv olağandışı biçimde sıkışmış ($path)")
                    }
                }

                when {
                    path == StoryPackFormat.MANIFEST_PATH -> {
                        if (data.size > StoryPackFormat.MAX_JSON_BYTES) {
                            throw RefusedException(Refusal.ENTRY_TOO_LARGE, "manifest beklenenden büyük")
                        }
                        manifestText = String(data, Charsets.UTF_8)
                    }
                    path.startsWith("${StoryPackFormat.CONTENT_DIR}/") ||
                        path.startsWith("${StoryPackFormat.LOCALIZATION_DIR}/") -> {
                        if (data.size > StoryPackFormat.MAX_JSON_BYTES) {
                            throw RefusedException(Refusal.ENTRY_TOO_LARGE, "$path beklenenden besar")
                        }
                        textFiles[path] = String(data, Charsets.UTF_8)
                    }
                    path.startsWith("${StoryPackFormat.ASSET_DIR}/") -> assets[path] = data
                    else -> warnings += "Bilinmeyen dosya yok sayıldı: $path"
                }
            }
        } finally {
            runCatching { stream.close() }
        }

        val manifest = manifestText
            ?: throw RefusedException(Refusal.MANIFEST_INVALID, "arşivde manifest.json yok")

        return Contents(
            manifest = manifest,
            textFiles = textFiles,
            assets = assets,
            entryNames = names,
            warnings = warnings,
        )
    }

    /**
     * Reads exactly one entry, enforcing its own size bound on the way.
     *
     * Reading into a growing buffer with a check on every chunk is what stops the entry
     * that lies about its size in the header and tells the truth only in the data.
     */
    private fun readEntry(stream: ZipInputStream, path: String): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(STREAM_CHUNK)
        var total = 0L
        var exhausted = false
        while (!exhausted) {
            val read = try {
                stream.read(buffer)
            } catch (cause: Exception) {
                throw RefusedException(Refusal.MALFORMED_ENTRY, "$path okunamadı: ${cause.message}")
            }
            if (read < 0) {
                exhausted = true
                continue
            }
            total += read
            if (total > StoryPackFormat.MAX_ENTRY_BYTES) {
                throw RefusedException(Refusal.ENTRY_TOO_LARGE, "$path izin verilenden büyük")
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private const val STREAM_CHUNK = 64 * 1024

    /** How many compressed bytes have come out of the source so far. */
    private class CountingInputStream(private val delegate: InputStream) : InputStream() {
        var count: Long = 0L
            private set

        override fun read(): Int {
            val value = delegate.read()
            if (value >= 0) count += 1
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val read = delegate.read(b, off, len)
            if (read > 0) count += read
            return read
        }

        override fun close() {
            delegate.close()
        }
    }
}

/**
 * Turning a raw archive into a *pack*: manifest, content, translations, assets - and the
 * validation report that decides whether it may be installed at all.
 *
 * Split from [StoryPackArchive] deliberately. The archive layer answers "is this a safe
 * ZIP"; this answers "is this a Story Pack". Neither is allowed to imply the other.
 */
object StoryPackDecoder {

    /**
     * Decodes and validates an archive in one pass.
     *
     * Never throws for content problems: a rejected pack is a report, not an exception.
     * The only exceptions that escape are archive-level refusals, which the caller turns
     * into the corresponding `Kritik Hata`.
     */
    fun decode(
        bytes: ByteArray,
        engineVersion: String = StoryPackFormat.ENGINE_VERSION,
        assetPresence: (String) -> Boolean = { false },
    ): DecodedPack {
        val contents = StoryPackArchive.read(bytes)
        val issues = mutableListOf<ValidationIssue>()

        contents.warnings.forEach { issues += ValidationIssue.archiveWarning(it) }

        val manifest = runCatching {
            StoryPackFormat.manifestJson.decodeFromString(PackManifest.serializer(), contents.manifest.orEmpty())
        }.getOrElse {
            issues += ValidationIssue.critical(
                code = IssueCode.INVALID_MANIFEST,
                message = "Paket tanımı (manifest.json) okunamadı.",
                detail = it.message.orEmpty(),
            )
            return DecodedPack.failed(issues)
        }

        issues += StoryPackValidator.validateManifest(manifest, engineVersion)

        val decoded = StoryPackCodec.decode(contents.textFiles)
        decoded.problems.forEach { problem ->
            issues += ValidationIssue.critical(
                code = IssueCode.INVALID_SCHEMA,
                message = "Paket içeriği eksik ya da bozuk.",
                detail = problem,
            )
        }

        val bundle = decoded.bundle
        issues += StoryPackValidator.validateBundle(bundle)
        if (issues.any { it.severity == Severity.CRITICAL }) {
            return DecodedPack(manifest, null, bundle, emptyMap(), contents, issues)
        }
        val pack = runCatching {
            bundle.compile(
                packId = manifest.packId,
                identity = ManifestIdentity(
                    packId = manifest.packId,
                    title = manifest.title,
                    description = manifest.description,
                    premise = manifest.premise,
                    author = manifest.author,
                    publisher = manifest.publisher,
                    language = manifest.language,
                    license = manifest.license,
                    tags = manifest.tags,
                    cover = manifest.cover,
                    entryPoint = manifest.entryPoint,
                    packVersion = manifest.packVersion,
                    minimumEngineVersion = manifest.minimumEngineVersion,
                    maximumEngineVersion = manifest.maximumEngineVersion,
                    createdAtEpochMs = manifest.createdAtEpochMs,
                    updatedAtEpochMs = manifest.updatedAtEpochMs,
                ),
            )
        }.getOrElse {
            issues += ValidationIssue.critical(
                code = IssueCode.INVALID_SCHEMA,
                message = "Paket içeriği bir hikâye paketi oluşturmuyor.",
                detail = it.message.orEmpty(),
            )
            return DecodedPack(manifest, null, bundle, emptyMap(), contents, issues)
        }

        val localization = decodeLocalization(contents.textFiles, issues)
        issues += StoryPackValidator.validateContent(pack, bundle)
        issues += StoryPackValidator.validateReferences(pack)
        issues += StoryPackValidator.validateReachability(pack)
        issues += StoryPackValidator.validateBranching(pack)
        issues += StoryPackValidator.validateAssets(pack, contents, assetPresence)
        issues += StoryPackValidator.validateLocalization(pack, localization)
        issues += StoryPackValidator.validateInventory(manifest, contents)

        return DecodedPack(
            manifest = manifest,
            pack = pack,
            bundle = bundle,
            localization = localization,
            contents = contents,
            issues = issues,
        )
    }

    private fun decodeLocalization(
        textFiles: Map<String, String>,
        issues: MutableList<ValidationIssue>,
    ): Map<String, Map<String, String>> {
        val prefix = "${StoryPackFormat.LOCALIZATION_DIR}/"
        val tables = linkedMapOf<String, Map<String, String>>()
        textFiles.entries.filter { it.key.startsWith(prefix) }.forEach { (path, raw) ->
            val language = path.removePrefix(prefix).removeSuffix(".json").lowercase()
            val decoded = LocalizationCodec.decode(StoryPackFormat.json, raw)
            if (decoded == null) {
                issues += ValidationIssue.warning(
                    code = IssueCode.INVALID_LOCALIZATION,
                    message = "$language çevirisi okunamadı.",
                    detail = "decode failed",
                )
                return@forEach
            }
            tables[language] = decoded
        }
        return tables
    }
}

/**
 * A decoded pack: what it is, what it says, and everything wrong with it.
 *
 * The verdict is derived rather than stored, so a caller can never see `CRITICAL` and
 * `installable = true` in the same breath.
 */
data class DecodedPack(
    val manifest: PackManifest?,
    val pack: dev.charaly.runtime.domain.StoryPack?,
    val bundle: StoryPackBundle?,
    val localization: Map<String, Map<String, String>>,
    val contents: StoryPackArchive.Contents?,
    val issues: List<ValidationIssue>,
) {
    val verdict: Severity get() = ValidationIssue.verdictOf(issues)

    val installable: Boolean get() = verdict != Severity.CRITICAL && pack != null && manifest != null

    companion object {
        fun failed(issues: List<ValidationIssue>): DecodedPack =
            DecodedPack(null, null, null, emptyMap(), null, issues)
    }
}