package dev.charaly.runtime.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE ASSET MANIFEST.
 *
 * ## Why a manifest, and why this test
 *
 * The resolver returns a *path*, and the drawing layer opens it. If a path is wrong the
 * only symptom is a silently missing picture - which looks identical to "the app is fine,
 * that location just has no art". Nobody files a bug for that, so nothing catches it.
 *
 * So the shipped files are declared in one place ([AssetResolver.declaredFiles]) and this
 * test asserts the declaration matches the repository. Two failure modes, both real:
 *
 *  * a declared file that does not exist - the resolver confidently returns a path to
 *    nothing, and the fallback draws forever;
 *  * a file that exists but is not declared - the art is in the APK and unreachable,
 *    which is worse than missing because it looks like a wiring bug at runtime.
 *
 * This is the only test in the project that reads the filesystem for asset *existence*,
 * and it is deliberately the one that can: everything about asset *selection* is pure and
 * is tested in [AssetResolutionTest].
 */
class AssetManifestTest {

    /**
 * The assets root, relative to this module's project directory.
     *
     * A plain constructor rather than `File("a") / "b"`, because `File.div` is the Kotlin
 * *extension* `kotlin.io.path`, which is not on this module's classpath - and importing it
     * for one concatenation would obscure what the test is actually doing, which is reading
     * the assets tree off disk.
     */
    private val assetsRoot = File("src/main/assets")

    /** The pack's artwork root. */
    private val root = File(assetsRoot, AssetResolver.MIRACULOUS_ROOT)

    @Test
    fun `every declared asset exists in the repository`() {
        val declared = AssetResolver.declaredFiles
        assertTrue("the manifest must not be empty", declared.isNotEmpty())

        val missing = declared.filterNot { File(assetsRoot, it).isFile }
        assertTrue(
            "these assets are declared but do not exist:\n  ${missing.joinToString("\n  ")}",
            missing.isEmpty(),
        )
    }

    @Test
    fun `every shipped asset is declared`() {
        val onDisk = root.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(assetsRoot).path }
            .toSet()
        assertTrue("the artwork root should exist", onDisk.isNotEmpty())

        val undeclared = onDisk - AssetResolver.declaredFiles
        assertTrue(
            "these assets are shipped but unreachable, so no resolver can return them:\n" +
                "  ${undeclared.joinToString("\n  ")}",
            undeclared.isEmpty(),
        )
    }

    @Test
    fun `every shipped asset is a readable png`() {
        // A truncated file is not caught by an existence check: `File.isFile` is true for a
        // PNG whose IDAT stream stops halfway. The header and the IEND chunk are the two
        // cheap facts that catch it.
        val bad = root.walkTopDown()
            .filter { it.isFile }
            .filter { file ->
                val bytes = file.readBytes()
                val hasMagic = bytes.size > 8 &&
                    bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
                    bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
                val hasEnd = bytes.size > 12 &&
                    String(bytes, bytes.size - 8, 4, Charsets.ISO_8859_1) == "IEND"
                !hasMagic || !hasEnd
            }
            .map { it.name }
            .toList()
        assertTrue("these assets are not complete PNG files: $bad", bad.isEmpty())
    }

    @Test
    fun `the manifest is not dominated by one thing`() {
        // A sanity check on the generator itself: if the artwork set were all portraits and
        // no backgrounds, the app would look complete in a test and wrong on a device.
        val backgrounds = AssetResolver.declaredFiles.count { it.contains("/backgrounds/") }
        val characters = AssetResolver.declaredFiles.count { it.contains("/characters/") }
        assertTrue("the pack needs backgrounds, got $backgrounds", backgrounds >= 20)
        assertTrue("the pack needs portraits, got $characters", characters >= 8)
        assertTrue("the pack needs a cover and a banner", AssetResolver.declaredFiles.count { it.contains("/scenes/") } >= 2)
    }

    @Test
    fun `the fallback files the resolver can name are the ones that exist`() {
        // The two paths the resolver substitutes when a character or a place has no art of
        // its own. If these are missing, the "deterministic fallback" is not a picture.
        val generic = File(assetsRoot, AssetResolver.MIRACULOUS_ROOT + "/characters/generic.png")
        assertTrue("the generic portrait must exist", generic.isFile)

        for (time in listOf("day", "night", "sunset", "dawn")) {
            val fallback = File(
                assetsRoot,
                AssetResolver.MIRACULOUS_ROOT + "/backgrounds/rooftop_$time.png",
            )
            assertTrue("the unclassified-location fallback must exist for $time", fallback.isFile)
        }
    }
}