package dev.charaly.app.storypack

import dev.charaly.app.ui.packstore.PackOpenAction
import dev.charaly.app.ui.packstore.toGateUi
import dev.charaly.runtime.storypack.PackCompatibility
import dev.charaly.runtime.storypack.PackCompatibilityReport
import dev.charaly.runtime.storypack.PackOpenDecision
import dev.charaly.runtime.storypack.PackRecoveryAction
import dev.charaly.runtime.storypack.openDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE STORY-OPEN GATE'S USER-FACING CONTRACT.
 *
 * The verdict types belong to the compatibility contract (`dev.charaly.runtime.storypack`)
 * and are tested against real installer parts in `StoryOpenGateTest`. What this file owns
 * is the half a player sees - the same split the pack store uses between validator and
 * screen - and it exists because the dangerous failure here is not a wrong enum, it is a
 * WRONG PROMISE: a "geçiş yap" (migrate) button for an operation the app does not have, a
 * recovery path that cannot recover, or an explanation that leaks pack ids and says
 * nothing a person can do.
 *
 * Three rules, asserted rather than reviewed:
 *  1. the explanation names the situation and the way back, in Turkish;
 *  2. only real recoveries appear as buttons - there is no migration button, ever;
 *  3. nothing a person reads carries engineering vocabulary (pack ids, id@version pairs).
 */
class StoryOpenGateUiTest {

    private val report = PackCompatibilityReport.evaluate(
        pinnedPackId = "pack.secret.canon",
        pinnedVersion = "1.1.0",
        installedVersion = "2.0.0",
    )

    private fun blocked(): PackOpenDecision.Blocked {
        val decision = report.openDecision()
        assertTrue(decision is PackOpenDecision.Blocked)
        return decision as PackOpenDecision.Blocked
    }

    @Test
    fun `a missing pinned edition is blocked with import as its only recovery`() {
        assertEquals(PackCompatibility.MIGRATION_REQUIRED, report.status)
        val gate = blocked().toGateUi(storyId = "story-1", storyTitle = "Kayıp Bölüm")

        assertEquals("story-1", gate.storyId)
        assertEquals("Kayıp Bölüm", gate.storyTitle)
        assertEquals(listOf(PackOpenAction.IMPORT_ARCHIVE), gate.actions)
    }

    @Test
    fun `there is no migrate button because there is no migration`() {
        val gate = blocked().toGateUi("story-1", "Kayıp Bölüm")

        // Every button the dialog can render, by construction and by scan: none of them
        // promises the one operation the app does not perform.
        val buttons = gate.actions.map { it.label } + "Kapat"
        assertTrue(buttons.none { it.contains("geçiş", ignoreCase = true) || it.contains("migrat", ignoreCase = true) })
        // And the explanation says out loud that no migration happened.
        assertTrue(
            "the player must be told the story was not migrated: ${gate.explanation}",
            gate.explanation.contains("sessizce") && gate.explanation.contains("geçiş uygulanmış değildir"),
        )
    }

    @Test
    fun `the explanation names the situation and the way back in Turkish`() {
        val gate = blocked().toGateUi("story-1", "Kayıp Bölüm")

        // Situation: the editions involved, in the contract's own sentence.
        assertTrue(gate.explanation.contains("1.1.0"))
        assertTrue(gate.explanation.contains("2.0.0"))
        // Promise: the save is untouched.
        assertTrue(gate.explanation.contains(".charaly-pack"))
        // Way back: restoring the pinned edition resumes the story.
        assertTrue(gate.explanation.contains("eksiksiz devam eder"))
    }

    @Test
    fun `nothing a person reads carries engineering vocabulary`() {
        val gate = blocked().toGateUi("story-1", "Kayıp Bölüm")

        listOf(gate.storyTitle, gate.explanation).forEach { text ->
            assertFalse("pack id leaked into \"$text\"", text.contains("pack.secret.canon"))
            assertFalse("id@version token leaked into \"$text\"", text.contains("@"))
            assertFalse("a path leaked into \"$text\"", text.contains('/'))
        }
    }

    @Test
    fun `a missing pack offers catalog and import, and promises the save survives`() {
        val missing = PackCompatibilityReport.evaluate(
            pinnedPackId = "pack.secret.canon",
            pinnedVersion = "1.1.0",
            installedVersion = null,
        )
        assertEquals(PackCompatibility.PACK_MISSING, missing.status)

        val gate = (missing.openDecision() as PackOpenDecision.Blocked).toGateUi("story-2", "Kayıp Bölüm")
        assertEquals(listOf(PackOpenAction.OPEN_CATALOG, PackOpenAction.IMPORT_ARCHIVE), gate.actions)
        assertTrue(
            "the save-survives promise is the whole point of this dialog: ${gate.explanation}",
            gate.explanation.contains("Kaydınız") && gate.explanation.contains("korunur"),
        )
    }

    @Test
    fun `every recovery button that can appear is labelled, in Turkish, exactly once`() {
        val labels = PackOpenAction.entries.map { it.label }
        assertEquals(labels.size, labels.toSet().size)
        assertTrue(labels.all { it.isNotBlank() })
        assertEquals("Arşivden İçe Aktar", PackOpenAction.IMPORT_ARCHIVE.label)
        assertEquals("Paket Mağazası", PackOpenAction.OPEN_CATALOG.label)

        // And the enum cannot express a recovery the decision layer never offers: the
        // mapping is total over [PackRecoveryAction] and nothing else.
        assertEquals(PackRecoveryAction.entries.size, PackOpenAction.entries.size)
    }

    @Test
    fun `playable verdicts never produce a gate at all`() {
        val compatible = PackCompatibilityReport.evaluate(
            pinnedPackId = "pack.secret.canon",
            pinnedVersion = "1.1.0",
            installedVersion = "1.1.0",
        )
        val legacy = PackCompatibilityReport.evaluate(
            pinnedPackId = "pack.secret.canon",
            pinnedVersion = "",
            installedVersion = "2.0.0",
        )

        assertTrue(compatible.openDecision() is PackOpenDecision.Open)
        assertEquals(PackCompatibility.COMPATIBLE, (compatible.openDecision() as PackOpenDecision.Open).report.status)
        assertTrue(
            "legacy saves keep opening - pinning must not break what predates it",
            legacy.openDecision() is PackOpenDecision.Open,
        )
    }
}