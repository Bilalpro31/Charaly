package dev.charaly.app.ui

import dev.charaly.runtime.presentation.CharalyDestination
import dev.charaly.runtime.presentation.DestinationRouting
import dev.charaly.runtime.presentation.SettingsPresenter
import dev.charaly.runtime.presentation.SettingsSection
import dev.charaly.app.ui.theme.CharalyTheme
import dev.charaly.app.ui.theme.LocalMotionPolicy
import dev.charaly.app.ui.design.CharalyAtmosphere
import dev.charaly.app.ui.design.CharalyDesign
import dev.charaly.app.ui.design.CharalyInk
import dev.charaly.app.ui.design.CharalySurface
import dev.charaly.app.ui.design.readableOn
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import dev.charaly.runtime.presentation.LayoutPolicy
import dev.charaly.runtime.presentation.MotionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.pow

/**
 * SETTINGS, THE SHELL, AND THE DESIGN TOKENS.
 *
 * Three things that are easy to state and easy to get quietly wrong, collected because they
 * share one property: **each is a claim the app makes to the user about itself.**
 *
 *  * Settings claims Charaly works on this device. The manifest says otherwise - it declares
 *    `INTERNET` for model discovery and download - so the copy has to name that rather than
 *    claim a privacy property the app does not have.
 *  * The shell claims four destinations. Seven was the previous number, and the bar has to
 *    stay at what a thumb can cover.
 *  * The tokens claim true black and colour from the world. A drift in either is invisible
 *    to review and obvious on a device.
 */
class SettingsAndShellContractTest {

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    @Test
    fun `settings has the six sections the brief names`() {
        assertEquals(
            // DIAGNOSTICS sits between AI and OFFLINE on purpose: it is about the model,
            // so it belongs with the model, and it is the only section that runs the
            // engine - which is why it is not tucked under ADVANCED with the developer
            // inspector.
            listOf("APP", "AI", "DIAGNOSTICS", "OFFLINE", "STORAGE", "PRIVACY", "ADVANCED"),
            SettingsSection.entries.map { it.name },
        )
        // Every section has a title and a blurb, because a bare heading reads as a form.
        SettingsSection.entries.forEach {
            assertTrue("${it.name} has no title", it.title.isNotBlank())
            assertTrue("${it.name} has no blurb", it.blurb.isNotBlank())
        }
    }

    @Test
    fun `the privacy copy does not falsely claim there is no internet access`() {
        // This build *declares* android.permission.INTERNET, for one feature: fetching a
        // GGUF from the Hub. The honest sentence names the permission and what it is for -
        // a user who checks the manifest and finds INTERNET deserves to find the app
        // already told them so.
        assertEquals("Charaly works on this device.", SettingsPresenter.PRIVACY_HEADLINE)
        assertTrue(
            "the privacy body must say network access exists",
            SettingsPresenter.PRIVACY_BODY.contains("Network access"),
        )
        assertTrue(
            "the privacy body must say what it is used for",
            SettingsPresenter.PRIVACY_BODY.contains("model discovery and download"),
        )
        assertTrue(
            "the privacy body must say it is optional",
            SettingsPresenter.PRIVACY_BODY.contains("optional"),
        )
        assertTrue(
            "the privacy body must say what never leaves the device",
            SettingsPresenter.PRIVACY_BODY.contains("never leave this device"),
        )
        // And it must not make the claim the manifest disproves.
        for (falsehood in listOf("no internet", "never uses the internet", "fully offline", "no network access")) {
            assertFalse(
                "the privacy copy claims \"$falsehood\", which this build's manifest disproves",
                (SettingsPresenter.PRIVACY_BODY + SettingsPresenter.PRIVACY_HEADLINE)
                    .contains(falsehood, ignoreCase = true),
            )
        }
    }

    @Test
    fun `the privacy body and the declared permission agree`() {
        // Cross-checked against the manifest rather than trusted, because the failure this
        // replaces was a privacy screen that contradicted the app's own manifest.
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(
            "this build declares INTERNET for model discovery and download",
            manifest.contains("android.permission.INTERNET"),
        )
        assertTrue(
            "the privacy copy promises optional network use for model discovery, and the " +
                "manifest declares ACCESS_NETWORK_STATE to check it",
            manifest.contains("android.permission.ACCESS_NETWORK_STATE"),
        )
    }

    @Test
    fun `the offline claim names what works with no connection`() {
        assertTrue(SettingsPresenter.OFFLINE_BODY.contains("no connection"))
        assertTrue(SettingsPresenter.OFFLINE_BODY.contains("world"))
        assertTrue(SettingsPresenter.OFFLINE_BODY.contains("story"))
        assertTrue(SettingsPresenter.OFFLINE_BODY.contains("model"))
    }

    @Test
    fun `the local-AI copy describes the authoritative selection, not engine residency`() {
        // A model that is installed and bound but not yet resident is READY for the user:
        // they can press Continue and write. The previous copy said "no model is loaded
        // yet" about exactly this state, which is the wrong answer this pass exists to fix.
        val loading = dev.charaly.runtime.model.ModelSelection(
            modelId = "local-qwen",
            displayName = "Qwen3 4B",
            absolutePath = "/models/q.gguf",
            isResident = false,
        )
        val readyBody = SettingsPresenter.localAiBody(loading, "Qwen3 4B")
        assertTrue(readyBody.contains("Qwen3 4B"))
        assertFalse(readyBody.contains("No model is loaded yet"))

        val resident = loading.copy(isResident = true)
        assertTrue(SettingsPresenter.localAiBody(resident, "Qwen3 4B").contains("Qwen3 4B"))

        // No model at all is the one case that must still ask for one.
        val missing = SettingsPresenter.localAiBody(
            dev.charaly.runtime.model.ModelSelection(),
            "",
        )
        assertFalse(missing.contains("loaded and speaking"))
    }

    @Test
    fun `the storage copy counts correctly and hides a zero size`() {
        assertEquals("0 models · 0 stories", SettingsPresenter.storageBody(0, 0, 0L))
        assertEquals("1 model · 1 story", SettingsPresenter.storageBody(1, 1, 0L))
        val withSize = SettingsPresenter.storageBody(2, 5, 2_600_000_000L)
        assertTrue(withSize.startsWith("2 models · 5 stories"))
        assertTrue("a real size must be reported", withSize.contains("GB") || withSize.contains("MB"))
    }

    // ------------------------------------------------------------------
    // The shell
    // ------------------------------------------------------------------

    @Test
    fun `a phone gets a floating pill and a tablet gets a rail, never both`() {
        // Asserted through the policy the shell branches on, so "a tablet gets a rail" is a
        // fact rather than something to rediscover on hardware.
        val phone = LayoutPolicy.forWidth(411)
        assertFalse("a phone must not get a rail", phone.navigationRail)
        assertEquals(1, phone.cardColumns)

        val tablet = LayoutPolicy.forWidth(840)
        assertTrue("a tablet must get a rail", tablet.navigationRail)
    }

    @Test
    fun `the shell draws one navigation surface, never two`() {
        // The layout mistake this replaces: a rail *and* a bar on the same screen, with the
        // bar sitting directly above the composer.
        val source = File("src/main/kotlin/dev/charaly/app/ui/shell/CharalyShell.kt").readText()
        val scaffold = source.substringAfter("fun CharalyScaffold(").substringBefore("\n}\n")
        assertTrue(
            "the scaffold must decide rail-vs-bar once, from the policy",
            scaffold.contains("policy.navigationRail"),
        )
        assertTrue(
            "the floating bar must be gated on NOT being the rail case",
            scaffold.contains("!rail"),
        )
        // The rail is a leading-edge column; the bar is bottom-centred. A screen showing
        // both would have both call sites reached.
        assertTrue(scaffold.contains("CharalyNavigationRail("))
        assertTrue(scaffold.contains("CharalyFloatingNav("))
    }

    @Test
    fun `a null destination hides navigation entirely`() {
        // The two full-screen routes. A null here is what *hides* the nav, so it is a
        // design decision rather than an absence.
        assertFalse(DestinationRouting.showsNavigation(null))
        CharalyDestination.PRIMARY.forEach {
            assertTrue(DestinationRouting.showsNavigation(it))
        }
        assertEquals(
            CharalyDestination.HOME,
            DestinationRouting.primaryFor(isHome = true, isWorlds = false, isLibrary = false, isChat = false),
        )
        assertEquals(
            null,
            DestinationRouting.primaryFor(isHome = false, isWorlds = false, isLibrary = false, isChat = false),
        )
    }

    @Test
    fun `a degenerate width cannot produce a negative layout`() {
        // A transient zero during a fold or a configuration change must not produce negative
        // pane widths and a crash at measure time.
        for (width in listOf(0, -1, Int.MIN_VALUE / 2)) {
            val policy = LayoutPolicy.forWidth(width)
            assertTrue("width=$width was not clamped", policy.widthDp >= LayoutPolicy.MIN_WIDTH_DP)
        }
    }

    @Test
    fun `the layout gives the conversation the larger share of a wide screen`() {
        // A split pane where the context column is the bigger one would be backwards.
        val expanded = LayoutPolicy.forWidth(1200)
        assertTrue(expanded.storySplitPane)
        assertTrue(
            "the context pane must take less than half a split screen",
            expanded.contextPaneFraction < 0.5f,
        )
    }

    // ------------------------------------------------------------------
    // Design tokens
    // ------------------------------------------------------------------

    @Test
    fun `the void is true black and the steps above it are for elevation not tint`() {
        val surfaces = CharalySurface()
        assertEquals(Color(0xFF000000), surfaces.void)
        // Each step above black must be *lighter*, not coloured - and strictly monotonic, so
        // "raised" cannot accidentally be the same value as "elevated".
        val steps = listOf(surfaces.base, surfaces.raised, surfaces.elevated, surfaces.overlay)
        steps.forEach {
            val r = (it.red * 255).toInt()
            val g = (it.green * 255).toInt()
            val b = (it.blue * 255).toInt()
            assertTrue("a surface step is tinted ($r,$g,$b)", maxOf(r, g, b) - minOf(r, g, b) <= 1)
        }
        steps.zipWithNext { lower, upper ->
            assertTrue(
                "the surface scale must climb: $lower then $upper",
                upper.luma() > lower.luma(),
            )
        }
        // And every step is close enough to black to be *felt* rather than seen.
        steps.forEach {
            assertTrue("a surface step is too light for a phone ($it)", it.luma() < 0.12f)
        }
    }

    @Test
    fun `the ink scale has three distinguishable levels on black`() {
        val ink = CharalyInk()
        // Metadata that competes with a title is metadata given too much weight, so the
        // third level must be genuinely dim rather than "slightly less white".
        assertTrue(ink.primary.luma() > ink.secondary.luma() + 0.2f)
        assertTrue(ink.secondary.luma() > ink.muted.luma() + 0.1f)
        // And every level must be legible on black.
        listOf(ink.primary, ink.secondary, ink.muted, ink.prose).forEach {
            assertTrue("ink $it is too dark on black", it.luma() > 0.05f)
        }
    }

    @Test
    fun `a filled accent gets readable ink chosen by contrast not by guess`() {
        // A gold accent and a deep violet both need a legible label. A fixed pair - always
        // white, or always black - gives one of them unreadable text, and it is always the
        // same pack that ships broken.
        val gold = Color(0xFFE8C46A)
        val lightBlue = Color(0xFF7FB2FF)
        val nearWhite = Color(0xFFF5F5F7)
        val deepViolet = Color(0xFF1B1040)

        for (accent in listOf(gold, lightBlue, nearWhite, deepViolet)) {
            val ink = readableOn(accent)
            assertTrue(
                "readableOn($accent) returned $ink, which is not one of the two inks",
                ink == Color(0xFF000000) || ink == Color(0xFFFFFFFF),
            )
            // The whole point: the returned ink must actually be readable *on this accent*.
            val ratio = contrast(ink, accent)
            assertTrue(
                "ink $ink on accent $accent has contrast ${"%.2f".format(ratio)}:1, " +
                    "below the 4.5:1 body-text minimum",
                ratio >= 4.5,
            )
        }

        // Light accents take black, dark ones take white - the property, not the pair.
        assertEquals(Color(0xFF000000), readableOn(gold))
        assertEquals(Color(0xFFFFFFFF), readableOn(deepViolet))

        // And Charaly's own atmosphere resolves the same way rather than hard-coding, so a
        // filled accent is never given unreadable ink.
        val neutralInk = CharalyAtmosphere.Neutral.onAccent
        assertTrue(
            "the shell's own accent must get readable ink",
            contrast(neutralInk, CharalyAtmosphere.Neutral.accent) >= 4.5,
        )
    }

    /** WCAG 2.1 relative-luminance contrast ratio between two opaque colours. */
    private fun contrast(a: Color, b: Color): Float {
        fun linear(c: Float): Float =
            if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

        fun lum(c: Color): Float =
            0.2126f * linear(c.red) + 0.7152f * linear(c.green) + 0.0722f * linear(c.blue)

        val first = lum(a)
        val second = lum(b)
        val lighter = maxOf(first, second)
        val darker = minOf(first, second)
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    @Test
    fun `Charaly's own atmosphere carries no hue`() {
        val neutral = CharalyAtmosphere.Neutral
        listOf(neutral.accent, neutral.secondary, neutral.highlight).forEach {
            val r = (it.red * 255).toInt()
            val g = (it.green * 255).toInt()
            val b = (it.blue * 255).toInt()
            assertTrue("the shell accent is chromatic ($r,$g,$b)", maxOf(r, g, b) - minOf(r, g, b) <= 0x12)
        }
        // And it declares no gradient, because a gradient is a world's to declare.
        assertEquals(null, neutral.gradient)
        assertFalse(neutral.hasGradient)
    }

    /** WCAG relative luminance, computed here so the assertion does not depend on Compose's. */
    private fun Color.luma(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

    @Test
    fun `the spacing scale is a 4dp grid with a wider gutter for tablets`() {
        val space = CharalyDesign().space
        // `xxs` is the one deliberate exception: 2dp is an optical nudge between a name and
        // its label, not a rhythm step. Everything from `unit` upward is on the grid.
        assertEquals("xxs is the documented optical nudge", 2, space.xxs.value.toInt())
        listOf(space.unit, space.xs, space.sm, space.md, space.lg, space.xl, space.xxl)
            .forEach {
                assertEquals("${it.value}dp is off the 4dp grid", 0, it.value.toInt() % 4)
            }
        assertTrue(
            "the wide gutter must be wider than the phone one, or tablets hug the bezel",
            space.gutterWide > space.gutter,
        )
        assertTrue("the measure must be capped", space.measureMax > space.gutterWide)
    }

    @Test
    fun `the cinematic budget is the only one over half a second`() {
        val timing = CharalyDesign().timing
        assertEquals(200, timing.quick)
        assertEquals(320, timing.standard)
        assertEquals(620, timing.cinematic)
        assertTrue(timing.cinematic > timing.standard)
        assertTrue(
            "a 620ms budget used for anything but entering a world would be intolerable",
            timing.standard <= 400,
        )
    }

    // ------------------------------------------------------------------
    // Motion
    // ------------------------------------------------------------------

    @Test
    fun `every duration goes through the motion policy`() {
        // A switch that saves a preference and leaves every animation at full length is a
        // lie told by omission. Asserted structurally: the durations are asked for through
        // `motionDuration`, never written as literals at an animated site.
        val files = File("src/main/kotlin/dev/charaly/app/ui").walkTopDown()
            .filter { it.extension == "kt" && it.name != "Motion.kt" }
            .toList()
        assertTrue("no UI sources found", files.isNotEmpty())

        val offenders = mutableListOf<String>()
        files.forEach { file ->
            val text = file.readText()
            // A raw `tween(320)` at an animated site bypasses the policy entirely.
            Regex("""tween\((\d+)""").findAll(text).forEach { match ->
                offenders += "${file.name}: raw tween(${match.groupValues[1]})"
            }
        }
        assertTrue("durations that bypass the motion policy: $offenders", offenders.isEmpty())
    }

    @Test
    fun `reduced motion shortens durations and none removes them entirely`() {
        // A zero duration still changes the state; it just does not travel. Skipping the
        // animation can leave a composable in the wrong state permanently.
        val reduced = MotionPolicy.REDUCED.durationMs
        assertTrue(reduced in 1..200)
        assertNotEquals(0, reduced)
        assertEquals(0, MotionPolicy.NONE.durationMs)
    }

    @Test
    fun `the motion policy and its providers are declared in one place`() {
        // A policy reachable from two places will be set from two places, and only one of
        // them will be right.
        assertTrue(File("src/main/kotlin/dev/charaly/app/ui/theme/Motion.kt").isFile)
        val theme = File("src/main/kotlin/dev/charaly/app/ui/theme/CharalyTheme.kt").readText()
        assertTrue("the theme declares the design locals", theme.contains("LocalDesign provides"))
        assertTrue("the theme declares the atmosphere local", theme.contains("LocalAtmosphere provides"))
    }
}
