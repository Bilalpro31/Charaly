package dev.charaly.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the invariant whose violation caused the P0 "Story Packs crashes" bug:
 * **a vertically scrollable Compose layout must never be nested inside a lazy
 * list's item.**
 *
 * Compose measures `LazyColumn` items with `maxHeight = Infinity`, so a nested
 * `LazyColumn` / `LazyVerticalGrid` throws at measure time:
 *
 * ```
 * IllegalStateException: Vertically scrollable component was measured with an
 * infinity maximum height constraints, which is disallowed.
 * ```
 *
 * The bug was width-dependent: the two-column branch was compiled in only on
 * tablets, so it crashed on the target device and not on a phone. That is why
 * this is checked structurally over the source rather than by eyeballing one
 * screen - the same mistake is easy to reintroduce in the next screen.
 *
 * ## Why the old `ResponsiveRows.chunk` assertions are gone
 *
 * They tested a chunking helper that existed only to build the old responsive grids. The
 * redesign replaced every grid with a single weighted vertical feed and a `LazyRow` of
 * pills, so the helper and its tests were deleted together. What remains is the part that
 * is *not* about any particular layout: the structural scan.
 *
 * This runs on the plain JVM with no device and no emulator.
 */
class NestedScrollGuardTest {

    @Test
    fun `no screen nests a vertically scrollable layout inside a lazy list item`() {
        val offenders = findNestedVerticalScrollers(sourceRoot())
        assertTrue(
            "Nested vertical scroller inside a lazy item (crashes with an infinite " +
                "max-height constraint):\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the scan actually reads the app sources`() {
        // A guard that silently matches nothing passes forever. This asserts the file
        // root it is scanning is the real one, so a moved source tree cannot turn this
        // test into a no-op.
        val root = sourceRoot()
        assertTrue(
            "could not locate src/main/kotlin from ${File(".").absolutePath}; " +
                "the nested-scroller guard would pass without checking anything",
            File(root, "dev/charaly/app/ui/screens").isDirectory,
        )
    }

    /**
     * A deliberately small structural scan rather than a full parser.
     *
     * It finds each `item {` / `item(...) {` block, then walks forward to the block's
     * closing brace and reports any `LazyColumn`, `LazyVerticalGrid` or
     * `LazyHorizontalGrid` inside it. False negatives are acceptable for a guard; a
     * false positive would be reported here immediately and be trivially narrowed.
     */
    private fun findNestedVerticalScrollers(root: File): List<String> {
        if (!root.isDirectory) return emptyList()
        val offenders = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .forEach { file ->
                val lines = file.readLines()
                lines.forEachIndexed { index, line ->
                    // `containsMatchIn`, not `matches`: these patterns are unanchored
                    // on purpose, so they find a call anywhere on an indented line.
                    if (!ITEM_BLOCK.containsMatchIn(line)) return@forEachIndexed
                    var depth = 0
                    var opened = false
                    for (j in index until minOf(index + MAX_SCAN_LINES, lines.size)) {
                        depth += lines[j].count { it == '{' } - lines[j].count { it == '}' }
                        if (lines[j].contains('{')) opened = true
                        val match = VERTICAL_SCROLLER.find(lines[j])
                        if (match != null) {
                            offenders += "${file.name}:${j + 1} nests ${match.groupValues[1]} " +
                                "inside a lazy item opened at line ${index + 1}"
                        }
                        if (opened && depth <= 0) break
                    }
                }
            }
        return offenders
    }

    /** The Kotlin source tree, located by walking up from the working directory. */
    private fun sourceRoot(): File {
        val marker = "src/main/kotlin"
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, marker)
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        // Also handle the case where we are already inside the source root.
        val direct = File(System.getProperty("user.dir") ?: ".", "src/main/kotlin")
        return if (direct.isDirectory) direct else File(".")
    }

    private companion object {
        const val MAX_SCAN_LINES = 500

        val ITEM_BLOCK = Regex("""\bitem\s*(\([^)]*\))?\s*\{""")
        val VERTICAL_SCROLLER = Regex("""(LazyColumn|LazyVerticalGrid|LazyHorizontalGrid)\(""")
    }
}
