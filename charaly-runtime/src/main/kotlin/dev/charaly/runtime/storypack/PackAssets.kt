package dev.charaly.runtime.storypack

import java.io.File

/**
 * Resolving artwork and sound *inside an installed pack*.
 *
 * ## The rule
 *
 * `InstalledPackRoot + "/" + relativeAssetPath`. Full stop. There is no absolute path, no
 * URI, no `..`, no symlink out. A pack's assets live in the pack's own directory and can
 * reach nothing else on the device - which is the same rule in two forms: what makes packs
 * portable across devices, and what stops a pack from reading the app's own save files.
 *
 * ## Misses are not errors
 *
 * A missing optional asset falls back to generated artwork (see
 * `dev.charaly.runtime.domain.VisualAssetChain`), so a pack that ships no images is a
 * *styling* choice rather than a broken frame. A missing *required* asset is refused at
 * installation instead, which is why this class is also the answer to "does the file
 * exist?" during validation.
 */
class PackAssetRoot(val root: File) {

    /**
     * Resolves a pack-relative path to a file, or null when the path is illegal.
     *
     * Null rather than exception because the caller is validating hostile input and a
     * refusal is data, not a fault.
     */
    fun resolve(relativePath: String): File? {
        val normalised = relativePath.trim().replace('\\', '/')
        if (!StoryPackFormat.isSafeRelativePath(normalised)) return null
        val target = File(root, normalised)
        // Belt and braces: even a "safe" spelling must land inside the root after the OS
        // has had its say about symlinks and case folding.
        val canonicalRoot = runCatching { root.canonicalFile }.getOrDefault(root)
        val canonicalTarget = runCatching { target.canonicalFile }.getOrDefault(target)
        if (!canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)) return null
        return target
    }

    fun exists(relativePath: String): Boolean = resolve(relativePath)?.isFile == true

    fun bytes(relativePath: String): ByteArray? =
        resolve(relativePath)?.takeIf { it.isFile }?.readBytes()

    fun text(relativePath: String): String? =
        resolve(relativePath)?.takeIf { it.isFile }?.readText()

    /** Every file inside the root, as pack-relative paths. */
    fun list(): List<String> {
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile }
            .mapNotNull { file -> file.relativeToOrNull(root)?.path?.replace('\\', '/') }
            .filter { StoryPackFormat.isSafeRelativePath(it) }
            .sorted()
            .toList()
    }

    /** Everything under `assets/`, which is what a pack's artwork and sound live in. */
    fun listAssets(): List<String> = list().filter { StoryPackFormat.isAssetPath(it) }

    /**
     * The archive representation of this installed pack: `path -> bytes`.
     *
     * Used by export. Reading through [list] keeps the export honest: an export can only
     * ever contain what is actually installed, in the same shape it was installed in.
     */
    fun readAll(): Map<String, ByteArray> =
        list().mapNotNull { path -> bytes(path)?.let { path to it } }.toMap(LinkedHashMap())

    companion object {
        /** An empty root: every lookup misses, every fallback fires. */
        val EMPTY = PackAssetRoot(File("."))
    }
}

/**
 * Checks asset *references* - the strings a pack writes into its content.
 *
 * Kept separate from [PackAssetRoot] because the two questions are different: "is this a
 * legal portable reference?" is about the pack, "is the file there?" is about the install.
 */
object PackAssetReferences {

    /** `assets/...`, relative, no traversal. Blank and `generated:` refs are legal absences. */
    fun isPortable(ref: String): Boolean {
        val trimmed = ref.trim()
        if (trimmed.isBlank() || trimmed.startsWith("generated:")) return true
        return StoryPackFormat.isSafeRelativePath(trimmed) && trimmed.startsWith("${StoryPackFormat.ASSET_DIR}/")
    }

    /** What a reference looks like when an author wrote a device path by habit. */
    fun looksLikeDevicePath(ref: String): Boolean {
        val trimmed = ref.trim()
        return trimmed.startsWith("/") ||
            trimmed.startsWith("file:") ||
            trimmed.startsWith("content:") ||
            trimmed.contains("/data/") ||
            trimmed.contains(":\\") ||
            trimmed.contains("../")
    }

    /** The canonical form used as a key inside an installed pack. */
    fun normalise(ref: String): String = ref.trim().replace('\\', '/')
}