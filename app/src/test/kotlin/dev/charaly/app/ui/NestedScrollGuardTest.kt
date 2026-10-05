package dev.charaly.app.ui

import dev.charaly.app.ui.components.ResponsiveRows
import org.junit.Assert.assertEquals
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
 * This runs on the plain JVM with no device and no emulator.
 */
class NestedScrollGuardTest {

    @Test
    fun `chunk splits a list into rows of the requested width`() {
        val rows = ResponsiveRows.chunk(listOf(1, 2, 3, 4, 5), columns = 2)
        assertEquals(listOf(listOf(1, 2), listOf(3, 4), listOf(5)), rows)
    }

    @Test
    fun `chunk of an exact multiple has no short final row`() {
        assertEquals(
            listOf(listOf("a", "b"), listOf("c", "d")),
            ResponsiveRows.chunk(listOf("a", "b", "c", "d"), columns = 2),
        )
    }

    @Test
    fun `chunk of an empty list is empty`() {
        assertTrue(ResponsiveRows.chunk(emptyList<String>(), columns = 2).isEmpty())
    }

    @Test
    fun `chunk of a single column is one row per item`() {
        assertEquals(
            listOf(listOf(1), listOf(2)),
            ResponsiveRows.chunk(listOf(1, 2), columns = 1),
        )
    }

    @Test
    fun `a non-positive column count degrades to one column instead of dropping cards`() {
        val items = listOf(1, 2, 3)
        for (columns in listOf(0, -1, Int.MIN_VALUE)) {
            val rows = ResponsiveRows.chunk(items, columns)
            assertEquals("columns=$columns", items, rows.flatten())
        }
    }

    @Test
    fun `chunk never loses or duplicates an item`() {
        val items = (1..37).toList()
        for (columns in 1..5) {
            val rows = ResponsiveRows.chunk(items, columns)
            assertEquals("columns=$columns", items, rows.flatten())
            assertTrue("columns=$columns", rows.all { it.size in 1..columns })
        }
    }

    @Test
    fun `no screen nests a vertically scrollable layout inside a lazy list item`() {
        val offenders = findNestedVerticalScrollers(sourceRoot())
        assertTrue(
            "Nested vertical scroller inside a lazy item (crashes with an infinite " +
                "max-height constraint):\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
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