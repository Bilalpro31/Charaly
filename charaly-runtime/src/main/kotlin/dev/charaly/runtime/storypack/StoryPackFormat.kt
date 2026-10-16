package dev.charaly.runtime.storypack

import dev.charaly.runtime.domain.StoryPack
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The `.charaly-pack` distribution format.
 *
 * A Story Pack is Charaly's *content distribution* format - not a database row, not a
 * JSON blob, not a demo fixture. It is the unit a story is authored in, validated as,
 * shipped as, installed from, and played from:
 *
 * ```
 * CONTENT  ->  PACKAGE  ->  VALIDATE  ->  INSTALL  ->  LIBRARY  ->  INSTANCE  ->  PLAY  ->  SAVE
 * ```
 *
 * ## Shape
 *
 * ```
 * mirac.paris-afterdark.charaly-pack        (a ZIP archive)
 *   manifest.json                           IDENTITY + file inventory + digests
 *   content/world.json                      setting, factions, lore, starting world
 *   content/canon.json                      immutable truths
 *   content/characters.json                 the cast
 *   content/locations.json                  the places
 *   content/relationships.json              initial relationships
 *   content/scenes.json                     authored scenes and branches
 *   content/events.json                     triggers, conditions, effects
 *   content/threads.json                    story threads
 *   content/objectives.json                 what the player is asked to do
 *   content/scenarios.json                  openings
 *   content/personas.json                   roles the player may take
 *   content/knowledge.json                  facts and starting knowledge
 *   content/assets.json                     visual + audio inventory
 *   content/presentation.json               identity, theme, hooks
 *   localization/tr.json                    key -> string overrides
 *   localization/en.json
 *   assets/ (binary payloads)                               portable binary payloads
 * ```
 *
 * ## Laws
 *
 * 1. **Pack identity is not the filename.** Renaming the archive changes nothing.
 *    Identity lives in `manifest.json`, and only there.
 * 2. **Every path inside the archive is relative.** Absolute device paths are illegal,
 *    `..` is illegal, and a `:` in the first segment is illegal. Assets resolve as
 *    `InstalledPackRoot + "/" + relativePath`, never any other way.
 * 3. **The pack is immutable authoring content.** It carries no clock, no player state,
 *    no memories, no relationship state. Those live in a StoryInstance.
 * 4. **Nothing partial is ever installed.** An install is staged, re-read, and committed
 *    atomically; the registry is written last.
 */
object StoryPackFormat {

    /** The file extension, without the dot. */
    const val FILE_EXTENSION = "charaly-pack"

    /** The MIME type advertised when writing the file. */
    const val MEDIA_TYPE = "application/vnd.charaly.storypack+zip"

    const val MANIFEST_PATH = "manifest.json"
    const val CONTENT_DIR = "content"
    const val LOCALIZATION_DIR = "localization"
    const val ASSET_DIR = "assets"

    /**
     * The version of the *container* schema: which files exist and what they hold.
     *
     * Independent of [PackManifest.packVersion], which versions the story itself. A pack
     * can tell a newer story (`2.0.0`) in the same container (`1`), or the same story in a
     * newer container.
     */
    const val SCHEMA_VERSION = 1

    /** The oldest container schema this build can still read. */
    const val MINIMUM_SCHEMA_VERSION = 1

    /**
     * The engine version this format was cut against.
     *
     * The installer takes the *actual* engine version as a parameter - this constant is
     * the default for tests and for callers that have no build metadata at hand.
     */
    const val ENGINE_VERSION = "0.8.0"

    // ---- content file names ---------------------------------------------

    const val WORLD_FILE = "world.json"
    const val CANON_FILE = "canon.json"
    const val CHARACTERS_FILE = "characters.json"
    const val LOCATIONS_FILE = "locations.json"
    const val RELATIONSHIPS_FILE = "relationships.json"
    const val SCENES_FILE = "scenes.json"
    const val EVENTS_FILE = "events.json"
    const val THREADS_FILE = "threads.json"
    const val OBJECTIVES_FILE = "objectives.json"
    const val SCENARIOS_FILE = "scenarios.json"
    const val PERSONAS_FILE = "personas.json"
    const val KNOWLEDGE_FILE = "knowledge.json"
    const val ASSETS_FILE = "assets.json"
    const val PRESENTATION_FILE = "presentation.json"

    /** Every content file, in write order. */
    val CONTENT_FILES: List<String> = listOf(
        WORLD_FILE,
        CANON_FILE,
        CHARACTERS_FILE,
        LOCATIONS_FILE,
        RELATIONSHIPS_FILE,
        SCENES_FILE,
        EVENTS_FILE,
        THREADS_FILE,
        OBJECTIVES_FILE,
        SCENARIOS_FILE,
        PERSONAS_FILE,
        KNOWLEDGE_FILE,
        ASSETS_FILE,
        PRESENTATION_FILE,
    )

    /** Languages this build will look for, most preferred first. */
    val SUPPORTED_LANGUAGES: List<String> = listOf("tr", "en")

    // ---- hostile-input limits -------------------------------------------
    //
    // A `.charaly-pack` is a file that arrived from somewhere. Every one of these bounds
    // exists because an attacker picked the value on the other side of it.

    const val MAX_ENTRIES = 4_096
    const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
    const val MAX_JSON_BYTES = 32L * 1024 * 1024

    /** A ratio above this is a zip bomb until proven otherwise. */
    const val MAX_COMPRESSION_RATIO = 250.0

    /** Floor on what counts as compressible before the ratio guard applies. */
    const val COMPRESSION_RATIO_GUARD_BYTES = 1024L * 1024

    /**
     * The codec used for every document inside a pack.
     *
     * `ignoreUnknownKeys` is what lets a container stay readable across builds: a pack
     * written by a build that knows more fields still installs here. Unknown *files* are
     * a warning rather than an error for the same reason.
     */
    val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
        classDiscriminator = "type"
    }

    /** The codec used for the manifest specifically (kept strict about its envelope). */
    val manifestJson: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    fun fileNameFor(packId: String, packVersion: String): String =
        "${sanitizePackId(packId)}-$packVersion.$FILE_EXTENSION"

    /**
     * Normalises a pack id for use as a filename.
     *
     * Identity never *comes from* this string - it only ever goes into it. That
     * direction is safe; the other one is how "change the file name, change the story"
     * bugs happen.
     */
    fun sanitizePackId(packId: String): String =
        packId.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "pack" }

    /**
     * Whether a path may be written into or read out of an archive.
     *
     * Rejects absolute paths, Windows drive letters, backslashes, `.`/`..` segments and
     * empty segments. This is the single chokepoint for path traversal: a pack cannot
     * reach outside itself no matter how the path was spelled.
     */
    fun isSafeRelativePath(path: String): Boolean {
        if (path.isBlank()) return false
        if (path.startsWith("/") || path.startsWith("\\")) return false
        if (path.contains('\\')) return false
        if (path.contains('\u0000')) return false
        if (path.length > 512) return false
        val segments = path.split('/')
        if (segments.any { it.isBlank() }) return false
        if (segments.any { it == "." || it == ".." }) return false
        if (segments.first().contains(':')) return false
        return true
    }

    /** Whether the path sits inside the asset subtree. */
    fun isAssetPath(path: String): Boolean =
        isSafeRelativePath(path) && path.startsWith("$ASSET_DIR/")
}

/**
 * Semantic versioning for pack content.
 *
 * Deliberately lenient about the tail and strict about the principle: two packs with the
 * same id are the same story, and the triple is what says whether one supersedes the
 * other. Anything unparseable sorts below everything parseable, so a nonsense version can
 * never masquerade as "the newest one".
 */
object SemVer {

    data class Version(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val prerelease: String = "",
        val original: String,
    ) : Comparable<Version> {

        override fun compareTo(other: Version): Int {
            if (major != other.major) return major.compareTo(other.major)
            if (minor != other.minor) return minor.compareTo(other.minor)
            if (patch != other.patch) return patch.compareTo(other.patch)
            // A release outranks its own pre-release: 1.2.0 > 1.2.0-rc.1
            return when {
                prerelease.isEmpty() && other.prerelease.isNotEmpty() -> 1
                prerelease.isNotEmpty() && other.prerelease.isEmpty() -> -1
                else -> prerelease.compareTo(other.prerelease)
            }
        }

        override fun toString(): String = original
    }

    /** Parses `1.2.3`, `1.2.3-rc.1`, `1.2.3+build`. Null when it is not a version at all. */
    fun parse(raw: String): Version? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val core = trimmed.substringBefore('+').substringBefore('-')
        val parts = core.split('.')
        if (parts.isEmpty() || parts.size > 3) return null
        val numbers = parts.map { it.toIntOrNull() ?: return null }
        if (numbers.any { it < 0 }) return null
        val major = numbers[0]
        val minor = numbers.getOrElse(1) { 0 }
        val patch = numbers.getOrElse(2) { 0 }
        val prerelease = trimmed.substringBefore('+').substringAfter('-', "")
        return Version(major, minor, patch, prerelease, trimmed)
    }

    fun isValid(raw: String): Boolean = parse(raw) != null

    /** Compare two version strings. Unparseable loses, ties are equal. */
    fun compare(a: String, b: String): Int {
        val left = parse(a)
        val right = parse(b)
        return when {
            left == null && right == null -> 0
            left == null -> -1
            right == null -> 1
            else -> left.compareTo(right)
        }
    }

    /** Whether [candidate] satisfies `[minimum, maximum]` (either bound may be blank). */
    fun satisfies(candidate: String, minimum: String = "", maximum: String = ""): Boolean {
        val version = parse(candidate) ?: return false
        if (minimum.isNotBlank()) {
            val floor = parse(minimum) ?: return false
            if (version < floor) return false
        }
        if (maximum.isNotBlank()) {
            val ceiling = parse(maximum) ?: return false
            if (version > ceiling) return false
        }
        return true
    }

    /** "1.2.3 is newer than 1.1.0", stated in the direction a human means it. */
    fun isNewer(candidate: String, than: String): Boolean = compare(candidate, than) > 0
}

/**
 * `manifest.json`: a pack's identity, its requirements, and an inventory of itself.
 *
 * The manifest is the *only* authoritative statement of pack identity. It is also the
 * only file in the archive not covered by [sha256], precisely because the digest lives
 * here: what `sha256` covers is everything else, computed from [files].
 */
@Serializable
data class PackManifest(
    val schemaVersion: Int = StoryPackFormat.SCHEMA_VERSION,
    /** Reverse-DNS-ish stable id: `miraculous.paris.afterdark`. Never derived from a file name. */
    val packId: String,
    /** Semantic version of the story content. */
    val packVersion: String,
    val title: String,
    val description: String = "",
    val premise: String = "",
    val author: String = "",
    val publisher: String = "",
    /** Language the content is authored in. */
    val language: String = "tr",
    val license: String = "",
    val tags: List<String> = emptyList(),
    /** A *relative asset path* or a generated seed. Never a device path, never a URL. */
    val cover: String = "",
    /** The scenario a story opens with when the player does not choose. */
    val entryPoint: String = "",
    val minimumEngineVersion: String = "",
    val maximumEngineVersion: String = "",
    val createdAtEpochMs: Long = 0L,
    val updatedAtEpochMs: Long = 0L,
    /** Total uncompressed size of every file listed in [files]. */
    val sizeBytes: Long = 0L,
    /** Pack digest over [files]. See [PackDigest.of]. */
    val sha256: String = "",
    val files: List<PackFileEntry> = emptyList(),
) {
    init {
        require(packId.isNotBlank()) { "PackManifest needs a packId" }
        require(packVersion.isNotBlank()) { "PackManifest $packId needs a packVersion" }
        require(title.isNotBlank()) { "PackManifest $packId needs a title" }
        require(!packId.contains('/') && !packId.contains('\\') && !packId.contains("..")) {
            "packId is an identity, not a path: $packId"
        }
    }

    fun file(path: String): PackFileEntry? = files.firstOrNull { it.path == path }

    /** Content files, i.e. everything under `content/`. */
    fun contentFiles(): List<PackFileEntry> =
        files.filter { it.path.startsWith("${StoryPackFormat.CONTENT_DIR}/") }

    fun assetFiles(): List<PackFileEntry> =
        files.filter { it.path.startsWith("${StoryPackFormat.ASSET_DIR}/") }

    fun localizationFiles(): List<PackFileEntry> =
        files.filter { it.path.startsWith("${StoryPackFormat.LOCALIZATION_DIR}/") }

    /** Whether an older engine may install this at all. */
    fun allowsEngine(engineVersion: String): Boolean =
        SemVer.satisfies(engineVersion, minimumEngineVersion, maximumEngineVersion)
}

/** One file inside a pack, with its digest and whether its absence would be fatal. */
@Serializable
data class PackFileEntry(
    val path: String,
    val sizeBytes: Long = 0L,
    val sha256: String = "",
    /** A missing required file blocks installation. A missing optional file warns. */
    val required: Boolean = true,
)

/**
 * The pack digest: one hash over the whole inventory.
 *
 * Computed from the sorted `path / size / sha256` triples rather than from archive bytes,
 * because archive bytes are not stable - compression levels, entry order and timestamps
 * all move them around. The inventory does not: the same content always digests the same,
 * whether it was exported here, exported there, or re-zipped on another machine.
 */
object PackDigest {

    private const val LINE_SEPARATOR = "\n"

    fun of(files: List<PackFileEntry>): String {
        val canonical = files
            .filterNot { it.path == StoryPackFormat.MANIFEST_PATH }
            .sortedBy { it.path }
            .joinToString(LINE_SEPARATOR) { entry ->
                "${entry.path}$LINE_SEPARATOR${entry.sizeBytes}$LINE_SEPARATOR${entry.sha256}"
            }
        return Sha256.of(canonical.toByteArray(Charsets.UTF_8))
    }

    /** Digest of the archive's own bytes - what a catalog's `sha256` claims. */
    fun ofBytes(bytes: ByteArray): String = Sha256.of(bytes)
}

/** Thin SHA-256 wrapper so hashing lives in exactly one place. */
object Sha256 {

    private val HEX = "0123456789abcdef".toCharArray()

    fun of(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = CharArray(digest.size * 2)
        digest.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xFF
            out[index * 2] = HEX[value ushr 4]
            out[index * 2 + 1] = HEX[value and 0x0F]
        }
        return String(out)
    }

    fun ofStream(stream: java.io.InputStream): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
        val hash = digest.digest()
        val out = CharArray(hash.size * 2)
        hash.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xFF
            out[index * 2] = HEX[value ushr 4]
            out[index * 2 + 1] = HEX[value and 0x0F]
        }
        return String(out)
    }
}

/**
 * How one pack's content relates to a running story that was opened from it.
 *
 * The anti-corruption rule of the whole system: a StoryInstance pins the pack edition it
 * started with, and installing a new edition never rewrites an old story. Either the old
 * content is still on the device (the story carries on exactly as before), or it is not,
 * and the honest answer is `MIGRATION_REQUIRED` - never a silent re-point.
 */
enum class PackCompatibility {
    /** The story's own pack edition is still installed. Nothing has changed. */
    COMPATIBLE,

    /** The pack is installed, but at a different edition than the story pinned. */
    MIGRATION_REQUIRED,

    /** The pack is gone. The save is intact but cannot be played as intended. */
    PACK_MISSING,

    /** The save predates pack pinning and simply follows whatever is installed. */
    UNPINNED_LEGACY,
}

/**
 * The verdict itself: what to tell the user, and what the engine may do about it.
 *
 * Carries the Turkish sentence because this is a decision a player has to understand -
 * "the story you started is from 1.1.0 and the pack is now 1.2.0" is not a stack trace.
 */
data class PackCompatibilityReport(
    val status: PackCompatibility,
    val packId: String,
    val pinnedVersion: String,
    val installedVersion: String,
    val message: String,
    /** Whether [dev.charaly.runtime.session.CharalyRuntime.respond] may run for this story. */
    val isPlayable: Boolean,
) {
    companion object {
        fun evaluate(
            pinnedPackId: String,
            pinnedVersion: String,
            installedVersion: String?,
        ): PackCompatibilityReport {
            val hasPinned = pinnedVersion.isNotBlank()
            return when {
                installedVersion == null -> PackCompatibilityReport(
                    status = PackCompatibility.PACK_MISSING,
                    packId = pinnedPackId,
                    pinnedVersion = pinnedVersion,
                    installedVersion = "",
                    message = "Bu hikâyenin paketi artık yüklü değil. Kayıt korunuyor ama hikâye paket geri yüklenene kadar oynatılamaz.",
                    isPlayable = false,
                )
                !hasPinned -> PackCompatibilityReport(
                    status = PackCompatibility.UNPINNED_LEGACY,
                    packId = pinnedPackId,
                    pinnedVersion = "",
                    installedVersion = installedVersion,
                    message = "Bu kayıt eski bir sürümden: paket sürümü sabitlenmemiş. Yüklü sürümle devam edilecek.",
                    isPlayable = true,
                )
                installedVersion == pinnedVersion -> PackCompatibilityReport(
                    status = PackCompatibility.COMPATIBLE,
                    packId = pinnedPackId,
                    pinnedVersion = pinnedVersion,
                    installedVersion = installedVersion,
                    message = "Hikâye kendi paket sürümüyle ($pinnedVersion) devam ediyor.",
                    isPlayable = true,
                )
                else -> PackCompatibilityReport(
                    status = PackCompatibility.MIGRATION_REQUIRED,
                    packId = pinnedPackId,
                    pinnedVersion = pinnedVersion,
                    installedVersion = installedVersion,
                    message = "Bu hikâye $pinnedVersion sürümüyle başladı; paket şimdi $installedVersion. " +
                        "Geçiş sunulmadan hikâye sessizce yeni sürüme taşınmaz.",
                    isPlayable = false,
                )
            }
        }
    }
}

/** Convenience: a [StoryPack]'s own notion of its edition. */
fun StoryPack.edition(): String = semanticVersion

/**
 * What story-open does with a [PackCompatibilityReport].
 *
 * Not a second compatibility taxonomy: the verdict itself stays [PackCompatibility], and
 * this only answers the one question the story-open flow has - "may this story be opened,
 * and if not, what can the user actually do about it?".
 */
sealed interface PackOpenDecision {
    /** Open/resume normally: the pinned edition is here, or the save predates pinning. */
    data class Open(val report: PackCompatibilityReport) : PackOpenDecision

    /**
     * Refuse to open. [recovery] lists only the actions the app genuinely supports -
     * never a button that pretends the problem is solved.
     */
    data class Blocked(
        val report: PackCompatibilityReport,
        val recovery: List<PackRecoveryAction>,
    ) : PackOpenDecision
}

/**
 * Recovery the app really has.
 *
 * There is NO pack-edition migration operation in this codebase - a story is never
 * re-pointed at a newer edition - so the honest recovery for a missing pinned edition is
 * to bring that edition back through the flow content always arrives through: import.
 */
enum class PackRecoveryAction {
    /** Bring the right edition back via the existing `.charaly-pack` import flow. */
    IMPORT_ARCHIVE,

    /** Browse the story catalog. Helps when the whole pack is gone, not when one edition is. */
    OPEN_CATALOG,
}

/**
 * The story-open gate, applied to a compatibility verdict before a story is resumed.
 *
 * `COMPATIBLE` and `UNPINNED_LEGACY` open ([PackCompatibilityReport.isPlayable] is exactly
 * that permission). Everything else blocks with its recovery options:
 *
 *  - `PACK_MISSING` may return through the catalog (any edition of the pack can be
 *    reinstalled and then import supplies the exact one);
 *  - `MIGRATION_REQUIRED` offers import alone, because only the pinned edition restores
 *    this story and migration is neither implemented nor silently performed.
 */
fun PackCompatibilityReport.openDecision(): PackOpenDecision = when {
    isPlayable -> PackOpenDecision.Open(this)
    status == PackCompatibility.PACK_MISSING -> PackOpenDecision.Blocked(
        this,
        listOf(PackRecoveryAction.OPEN_CATALOG, PackRecoveryAction.IMPORT_ARCHIVE),
    )
    else -> PackOpenDecision.Blocked(
        this,
        listOf(PackRecoveryAction.IMPORT_ARCHIVE),
    )
}