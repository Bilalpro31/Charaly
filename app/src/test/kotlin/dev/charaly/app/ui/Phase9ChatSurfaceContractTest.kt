package dev.charaly.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PHASE 9 - the chat-surface fixes, pinned at the source-contract level.
 *
 * ## Why these are source assertions and not Compose UI tests
 *
 * The four things fixed this phase are all *wiring*, not pixels: which composable owns a
 * piece of state, which branch a callback takes, which string a label reads. A Compose UI
 * test would need Robolectric plus a device-shaped runtime to observe them, and this module
 * has neither - `unitTests.isReturnDefaultValues = true` and no instrumentation harness.
 *
 * Asserting the wiring in source is what this codebase already does for exactly this class
 * of contract (`NavigationContractTest`, `SourceContractTest`, `SettingsAndShellContractTest`),
 * and it fails the moment someone reintroduces the defect - which a screenshot review does
 * not.
 *
 * ## What each assertion is actually protecting
 *
 *  - **back closes the overlay**: the sheet state must be visible to the app's single
 *    `BackHandler`. Held as local state inside the chat route, it is invisible to it, and
 *    back skips the overlay and leaves the story behind it.
 *  - **the composer can actually send**: `ComposerField` took an `onSend` that no code
 *    path read, and the keyboard action was `Default`, so the IME key was inert.
 *  - **no untranslated literals left in the sheets**: five English strings survived inside
 *    a Turkish UI even though their `Loc` keys already carried Turkish text.
 */
class Phase9ChatSurfaceContractTest {

    private fun source(path: String): String =
        File("src/main/kotlin/dev/charaly/app/$path").readText()

    private val app = source("ui/CharalyApp.kt")
    private val stage = source("ui/screens/StageScreen.kt")
    private val sheets = source("ui/sheets/StageSheets.kt")
    private val loc = File("../charaly-runtime/src/main/kotlin/dev/charaly/runtime/presentation/Loc.kt").readText()

    // ------------------------------------------------------------------
    // Back button
    // ------------------------------------------------------------------

    @Test
    fun `the open sheet is owned above the single BackHandler, not inside the chat route`() {
        assertTrue(
            "the sheet must be state in CharalyApp so the app's BackHandler can see it",
            app.contains("var stageSheet by remember { mutableStateOf<StageSheet?>(null) }"),
        )
        assertTrue(
            "CharalyApp must still install the one BackHandler that owns system back",
            app.contains("BackHandler(enabled = true)"),
        )
    }

    @Test
    fun `back closes an open sheet before it leaves the story`() {
        val handler = app.substringAfter("BackHandler(enabled = true) {")
            .substringBefore("\n    }")
        assertTrue(
            "BackHandler must branch on the open sheet",
            handler.contains("stageSheet"),
        )
        assertTrue(
            "an open sheet must be dismissed by back",
            handler.contains("stageSheet = null"),
        )
        assertTrue(
            "back must still leave the stage for the lobby once nothing is layered",
            handler.contains("leaveImmersive"),
        )
    }

    @Test
    fun `leaving a story closes its sheet`() {
        assertTrue(
            "a sheet must not survive into the next story",
            app.contains("LaunchedEffect(state.story?.id)"),
        )
    }

    // ------------------------------------------------------------------
    // Composer
    // ------------------------------------------------------------------

    @Test
    fun `the composer keyboard action sends instead of being inert`() {
        assertTrue(
            "the IME action must be Send, not the inert Default",
            stage.contains("imeAction = ImeAction.Send"),
        )
        assertTrue(
            "the IME action must be wired to the send callback",
            stage.contains("keyboardActions = KeyboardActions("),
        )
        assertTrue(
            "the send must be guarded so an empty field cannot start a generation",
            stage.contains("if (value.isNotBlank() && enabled) onSend()"),
        )
    }

    // ------------------------------------------------------------------
    // Localisation
    // ------------------------------------------------------------------

    @Test
    fun `the sheets contain no untranslated English literals`() {
        // The five PHASE 9 offenders, each of which had a translated Loc key all along.
        listOf(
            "Just you",
            "Memories appear here as the story accumulates them",
            "People arrive as the story needs them",
            "Elsewhere in this world",
            "Nothing is pressing yet.",
        ).forEach { literal ->
            assertTrue(
                "the sheet still hardcodes the English literal \"$literal\"",
                !sheets.contains("\"$literal\""),
            )
        }
    }

    @Test
    fun `each replaced literal reads through Loc and has both languages`() {
        listOf(
            "sheet.just_you",
            "sheet.no_memories_body_long",
            "sheet.nobody_body_long",
            "sheet.elsewhere_in_world",
            "sheet.nothing_pressing",
        ).forEach { key ->
            assertTrue("StageSheets must read $key through Loc", sheets.contains("Loc.t(\"$key\")"))
            assertTrue(
                "$key must exist in the catalogue",
                loc.contains("\"$key\" to "),
            )
        }
        // Both catalogues define every key: Loc holds an English map and a Turkish one, and
        // a key present in only one of them silently falls back to the other language.
        listOf(
            "sheet.just_you",
            "sheet.no_memories_body_long",
            "sheet.nobody_body_long",
            "sheet.elsewhere_in_world",
            "sheet.nothing_pressing",
        ).forEach { key ->
            assertTrue(
                "$key must be defined TWICE (English + Turkish)",
                loc.split("\"$key\" to ").size - 1 == 2,
            )
        }
    }

    // ------------------------------------------------------------------
    // The layout the reader approved must not have moved
    // ------------------------------------------------------------------

    @Test
    fun `the approved composer layout and control positions are unchanged`() {
        // Guards against "polish" quietly becoming redesign: these are the measurements and
        // the arrangement the reader signed off on.
        //
        // Every fragment is a SINGLE line that really does appear in the source. Matching a
        // multi-line expression as one string fails the moment a formatter wraps it, which
        // is how this very test first failed on a source that had not moved at all - a
        // layout guard that cries wolf gets deleted, and then the layout really can move.
        listOf(
            ".imePadding()",
            ".navigationBarsPadding()",
            ".heightIn(min = 52.dp, max = 112.dp)",
            "verticalArrangement = Arrangement.spacedBy(",
            "Alignment.Bottom,",
            "Charaly.atmosphere.accent",
            ".align(Alignment.CenterStart)",
            ".align(Alignment.CenterEnd)",
        ).forEach { fragment ->
            assertTrue(
                "the approved stage layout changed - missing: $fragment",
                stage.contains(fragment),
            )
        }
    }
}
