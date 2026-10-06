package dev.charaly.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A Compose `LazyColumn`/`LazyRow` crashes the running app the moment two sibling items
 * share a key - `IllegalArgumentException: Key "import" was already used`. It is not a
 * native, GGUF or engine problem; it is the UI recomposition that follows an import
 * completing and turning a conditional `item(key = "import")` on while the always-present
 * `item(key = "import")` action is still in the same list scope.
 *
 * There is no Robolectric in this build, so the screen cannot be rendered in a unit test.
 * What regressed is knowable without rendering, though: the same literal key appearing
 * twice in one file's lazy list. This test asserts the property across every screen and
 * sheet that declares keyed items, and names the two import surfaces in particular.
 */
class LazyListKeyContractTest {

    @Test
    fun `no file declares the same static item key twice`() {
        val offenders = mutableMapOf<String, List<String>>()
        screenSources().forEach { (path, source) ->
            val literalKeys = Regex("""item\(key\s*=\s*"([^"$]+)"\)""")
                .findAll(source)
                .map { it.groupValues[1] }
                .toList()
            val duplicates = literalKeys.groupBy { it }.filterValues { it.size > 1 }.keys
            if (duplicates.isNotEmpty()) offenders[path] = duplicates.toList()
        }
        assertTrue(
            "static duplicate Lazy item keys (each LazyColumn scope needs unique sibling " +
                "keys, and a repeated literal is the crash above): $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the import surfaces on the model hub have distinct keys`() {
        val hub = screenSources().getValue("ui/screens/ModelHubScreen.kt")
        // The crash: the in-flight ImportSurface and the permanent Import GGUF action both
        // used item(key = "import") in the same LazyColumn, so an import start - which
        // flips importState.isRunning true - rendered both and threw.
        assertTrue(
            "the import progress surface must not reuse the literal key \"import\"",
            !Regex("""item\(key\s*=\s*"import"\)\s*\{[^}]*ImportSurface""").containsMatchIn(hub),
        )
        assertEquals(
            "the literal key \"import\" must not appear at all on the hub screen",
            0,
            Regex("""item\(key\s*=\s*"import"\)""").findAll(hub).count(),
        )
        assertTrue(hub.contains("item(key = \"import-progress\")"))
        assertTrue(hub.contains("item(key = \"import-action\")"))
    }

    @Test
    fun `model cards key off the authoritative card id, not a filename`() {
        val hub = screenSources().getValue("ui/screens/ModelHubScreen.kt")
        // Keying by fileName crashes as soon as two cards share a filename; the card id is
        // the presenter's stable identity for the model.
        assertTrue(hub.contains("item(key = \"installed-${'$'}{card.id}\")"))
        assertTrue(hub.contains("item(key = \"browse-${'$'}{card.id}\")"))
        assertTrue(
            "installer keys must not use anything but the card id",
            !hub.contains("key = \"installed-${'$'}{card.fileName}\""),
        )
    }

    private fun screenSources(): Map<String, String> {
        val roots = listOf(
            File("src/main/kotlin/dev/charaly/app"),
            File("../app/src/main/kotlin/dev/charaly/app"),
        )
        val root = roots.firstOrNull(File::isDirectory)
            ?: error("app sources not found from ${File(".").absolutePath}")
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { file ->
                val relative = file.relativeTo(root).path
                val text = file.readText()
                if (text.contains("item(key") || text.contains("items(") || text.contains("itemsIndexed(")) {
                    relative to text
                } else {
                    null
                }
            }
            .toMap()
    }
}
