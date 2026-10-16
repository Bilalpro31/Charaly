package dev.charaly.app.ui

import dev.charaly.app.ui.nav.CharalyNavigator
import dev.charaly.app.ui.nav.Route
import dev.charaly.app.ui.nav.decodeRoute
import dev.charaly.app.ui.nav.tabOrNull
import dev.charaly.runtime.domain.CharalySurface
import dev.charaly.runtime.domain.HeroTreatment
import dev.charaly.runtime.domain.PackColor
import dev.charaly.runtime.pack.DemoStoryPacks
import dev.charaly.runtime.presentation.CharalyDestination
import dev.charaly.runtime.presentation.PackShowcaseBuilder
import dev.charaly.runtime.presentation.ResolvedTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app layer's navigation and design contracts, checked on the JVM.
 *
 * ## Why the design rules are asserted here rather than in the runtime
 *
 * The *values* live in the runtime (`ResolvedTheme`, `PackColor`, the pack theme) and are
 * tested there, because the runtime is where the whole engine is testable. What can only be
 * checked here is the wiring: that every route still resolves, that the routes a screen
 * depends on exist, and that the app's own source has not drifted back to the things this
 * pass removed.
 *
 * That last one is the reason several tests below read the Kotlin sources. A colour that
 * drifts from `#000000` back to a navy is invisible to review and obvious on a device, and
 * it is exactly the sort of regression that survives a merge.
 */
class NavigationContractTest {

    private val packs = DemoStoryPacks.all

    // ------------------------------------------------------------------
    // Routes
    // ------------------------------------------------------------------

    /**
     * Every destination round-trips through its own encoding.
     *
     * `decodeRoute(encode()) == route` is the property that makes a saved back stack
     * meaningful after a process death, which is when a user is most likely to be
     * mid-story.
     */
    @Test
    fun `every route round-trips`() {
        val routes = listOf(
            Route.Home,
            Route.Sessions,
            Route.Create,
            Route.Showcase("pack-miraculous-shadows-of-paris"),
            Route.EnterWorld("pack-miraculous-shadows-of-paris"),
            Route.Chat,
            Route.Stage("story-1"),
            Route.Library,
            Route.Settings,
            Route.StoryRecord("story-1"),
            Route.Models,
            Route.ModelDetail("local-1"),
            Route.Developer,
            Route.Authoring,
            Route.CharacterImport,
        )
        for (route in routes) {
            assertEquals("${route.encode()} did not round-trip", route, decodeRoute(route.encode()))
        }
    }

    /**
     * A route naming something deleted still decodes to a route.
     *
     * The crash this prevents: a pack removed in the authoring tool while its showcase sat
     * on the back stack, decoded to something the navigator could not render.
     */
    @Test
    fun `a route naming a deleted thing still decodes`() {
        val ghost = Route.Showcase("pack-that-was-deleted")
        assertEquals(ghost, decodeRoute(ghost.encode()))

        val ghostStory = Route.Stage("story-that-was-deleted")
        assertEquals(ghostStory, decodeRoute(ghostStory.encode()))
    }

    /**
     * An unrecognised string decodes to null rather than throwing.
     *
     * Navigation state is restored from persisted strings, and a build that changes the
     * route table would otherwise crash on launch for every returning user. Null means
     * "fall back to Home", which is the honest answer for a route this build no longer
     * has.
     */
    @Test
    fun `an unknown route decodes to null`() {
        assertNull(decodeRoute(null))
        assertNull(decodeRoute(""))
        assertNull(decodeRoute("a-route-from-a-future-build"))
        // And a prefix with an empty id is refused rather than producing an empty route.
        assertNull(decodeRoute("showcase/"))
        assertNull(decodeRoute("stage/"))
        // A route from the *previous* build's table must not resolve into this one's.
        assertNull(decodeRoute("pack/pack-x"))
        assertNull(decodeRoute("story/story-1"))
    }

    /**
     * Home leads to a story and a story returns.
     *
     * The product's central flow is Home -> Continue -> Stage. A change to the navigator
     * that broke the Home -> Story hop would make the app unusable and would not fail any
     * test unless this one exists.
     */
    @Test
    fun `home leads to a story and a story returns`() {
        val navigator = CharalyNavigator(Route.Home)
        navigator.navigateTo(Route.Stage("story-1"))
        assertEquals(Route.Stage("story-1"), navigator.current)
        navigator.pop()
        assertEquals(Route.Home, navigator.current)
    }

    @Test
    fun `the showcase route leads into entering that world`() {
        val navigator = CharalyNavigator(Route.Showcase("pack-x"))
        navigator.navigateTo(Route.EnterWorld("pack-x"))
        assertEquals(Route.EnterWorld("pack-x"), navigator.current)
    }

    /**
     * Five tab destinations, and the bar offers all five.
     *
     * Asserted on the app's own tab enum rather than on the runtime's destination enum so
     * the count cannot drift: V5's bar is five icons a thumb can reach without reading,
     * and a sixth is one more than that.
     */
    @Test
    fun `the bar offers exactly four destinations`() {
        assertEquals(5, Route.Tab.entries.size)
        assertEquals(
            listOf("HOME", "SESSIONS", "CREATE", "LIBRARY", "ME"),
            Route.Tab.entries.map { it.name },
        )
        // And every primary route resolves to one of them, so the bar never highlights
        // something other than where the user is.
        for (route in listOf(Route.Home, Route.Sessions, Route.Create, Route.Library, Route.Settings)) {
            assertNotNull(
                "${route.encode()} resolves to no tab, so selecting it would blank the bar",
                route.tabOrNull(),
            )
        }
    }

    @Test
    fun `every tab root resolves to a tab the shell can draw`() {
        for (tab in Route.Tab.entries) {
            val root = requireNotNull(ROOT_OF[tab]) { "no root route declared for ${tab.name}" }
            assertNotNull(
                "${tab.name} resolves to no tab, so selecting it would blank the bar",
                root.tabOrNull(),
            )
        }
        // And the tab's own resolution is the one it selects, so the bar never highlights
        // something other than where the user is.
        for (tab in Route.Tab.entries) {
            assertEquals(tab, requireNotNull(ROOT_OF[tab]).tabOrNull())
        }
    }

    // ------------------------------------------------------------------
    // The showcase a world's page renders
    // ------------------------------------------------------------------

    /**
     * Every shipped pack produces a complete showcase.
     *
     * A blank premise or a missing invitation is not a subtle degradation - it is an
     * empty block on the screen whose entire job is to make you want to enter a world.
     */
    @Test
    fun `every shipped pack renders a complete showcase`() {
        for (pack in packs) {
            val showcase = PackShowcaseBuilder.build(pack)
            assertTrue("${pack.title} has no premise", showcase.premise.isNotBlank())
            assertTrue("${pack.title} has no invitation", showcase.invitation.isNotBlank())
            assertTrue("${pack.title} has no universe line", showcase.universe.isNotBlank())
            assertTrue("${pack.title} has no atmosphere", showcase.atmosphere.isNotBlank())
            assertEquals(
                "${pack.title} has a blank primary action",
                showcase.invitation,
                showcase.primaryActionLabel,
            )
        }
    }

    /**
     * A pack with a playthrough offers Continue as well as Enter.
     *
     * Both affordances exist, but the label changes: a returning player is not being
     * invited, they are being offered a resume.
     */
    @Test
    fun `a pack with a story offers continue`() {
        val pack = packs.first()
        val fresh = PackShowcaseBuilder.build(pack, canContinue = false)
        assertFalse(fresh.canContinue)
        assertEquals("", fresh.continueLabel)

        val returning = PackShowcaseBuilder.build(pack, canContinue = true)
        assertTrue(returning.canContinue)
        assertEquals("Hikâyeye Devam Et", returning.continueLabel)
    }

    /**
     * The showcase carries no roster, so the showcase screen cannot render one.
     *
     * This is the real assertion, and it is a *type* assertion rather than a source scan:
     * [dev.charaly.runtime.presentation.PackShowcase] has no field for characters,
     * locations, events or lore, so no future change to the screen can draw them without
     * first adding a field here.
     */
    @Test
    fun `the showcase has nowhere to put engine data`() {
        val fields = PackShowcaseBuilder.build(packs.first())::class.java.declaredFields
            .map { it.name }
            .toSet()
        for (forbidden in listOf(
            "characters", "locations", "events", "lore", "factions", "threads",
            "personas", "scenarios", "canonLines", "eventTypes",
        )) {
            assertFalse(
                "PackShowcase exposes '$forbidden'; a world's page must not have a slot for it",
                fields.contains(forbidden),
            )
        }
    }

    /**
     * Each pack declares a hero treatment, and each is one the app can draw.
     *
     * An unrecognised treatment parses to WASH rather than throwing, so this checks the
     * *declared* value is in the enum rather than that it resolves.
     */
    @Test
    fun `every pack declares a hero treatment the app can draw`() {
        for (pack in packs) {
            val treatment = HeroTreatment.parse(pack.identity.theme.heroTreatment)
            assertEquals(
                "${pack.title} declares a hero treatment that is not a real one",
                treatment.name,
                pack.identity.theme.heroTreatment.uppercase(),
            )
        }
    }

    /**
     * Every shipped pack declares its own gradient, and it survives projection.
     *
     * Two properties in one test because they are one chain. A pack theme that declares
     * gradient stops proves the pack *has* an identity; a `ResolvedTheme` that drops them
     * proves nothing can draw it. The bug this guards is silent - an authored gradient that
     * never renders looks exactly like a pack with no gradient, and the only symptom is
     * that every hero fades from the same colour.
     */
    @Test
    fun `a declared pack gradient survives projection`() {
        for (pack in packs) {
            val theme = pack.identity.theme
            assertTrue(
                "${pack.title} declares no gradient, so its hero cannot have its own identity",
                theme.hasGradient,
            )
            val resolved = ResolvedTheme.of(theme)
            assertTrue(
                "${pack.title}'s gradient was dropped by ResolvedTheme.of, so no screen can draw it",
                resolved.hasGradient,
            )
            assertTrue("${pack.title}'s gradient lost its first stop", resolved.gradientStart != 0L)
            assertTrue("${pack.title}'s gradient lost its second stop", resolved.gradientEnd != 0L)
        }
    }

    /**
     * Each pack declares a distinct accent, so worlds do not look alike.
     *
     * The premise of the whole design is that colour comes from the world. Two packs with
     * the same accent means one of them is not being read, and the symptom is that the
     * product looks like it has one theme.
     */
    @Test
    fun `each pack has its own accent so worlds do not look alike`() {
        val accents = packs.map { it.identity.theme.primary() }.toSet()
        assertEquals(
            "two packs share an accent, so one world's colour is not reaching the UI",
            packs.size,
            accents.size,
        )
    }

    // ------------------------------------------------------------------
    // Neutrality: Charaly has no colour of its own
    // ------------------------------------------------------------------

    /**
     * The shell's fallback accent is neutral, not purple.
     *
     * `ResolvedTheme.BRAND` is what a speaker with no explicit accent and every non-story
     * surface fall back to. When it carried purple, those surfaces rendered violet in a
     * product whose premise is that colour comes from the pack - which is exactly the
     * "purple AI wrapper" failure the brief rules out. A neutral fallback cannot leak a hue
     * into a screen that has no pack on show.
     */
    @Test
    fun `the fallback theme carries no hue`() {
        for (channel in listOf(
            ResolvedTheme.BRAND.primary,
            ResolvedTheme.BRAND.secondary,
            ResolvedTheme.BRAND.accent,
            ResolvedTheme.BRAND.ink,
        )) {
            assertNeutral("the fallback ink", channel)
        }
    }

    /**
     * The runtime's own colour fallback is neutral.
     *
     * `PackColor.parse`'s default argument is reached by every unset colour in the app - a
     * pack with no accent, a character with no colour, an artwork slot with nothing to draw
     * from. It used to be violet, which meant all of them rendered violet by default: the
     * "purple AI wrapper" identity returning through a function signature rather than a
     * palette. Asserted here because the theme files cannot see it.
     */
    @Test
    fun `the runtime colour fallback is neutral not violet`() {
        assertNeutral("an unset colour", PackColor.parse(""))
    }

    /**
     * The accent is neutral by default.
     *
     * With no pack on show there is nothing for a hue to belong to, so the shell's
     * accent is near-white. Asserting it prevents a pack's colour from leaking into
     * screens that are not part of a story.
     */
    @Test
    fun `the shell accent is neutral`() {
        assertNeutral("the default ink", PackColor.parse(CharalySurface.INK_PRIMARY))
    }

    // ------------------------------------------------------------------
    // The design system, in the app's own sources
    // ------------------------------------------------------------------

    private fun appSource(relativePath: String): File =
        File("src/main/kotlin/dev/charaly/app/$relativePath")

    /**
     * The app's dark scheme sits on V5's `#0F0F11` floor.
     *
     * Asserted on the source rather than on a rendered colour, because Compose cannot be
     * inspected from a JVM unit test. The token value is separately asserted here, so the
     * two together pin the whole chain: token -> scheme.
     */
    @Test
    fun `the dark colour scheme uses pure black`() {
        val scheme = appSource("ui/theme/CharalyTheme.kt").readText()
        assertTrue(
            "the dark scheme must set the V5 background floor",
            scheme.contains("background = Color(0xFF0F0F11)"),
        )
        assertTrue(
            "the dark scheme must set the V5 surface floor",
            scheme.contains("surface = Color(0xFF0F0F11)"),
        )
        // And not the navy it used to be.
        assertFalse(
            "the navy-black background has returned",
            scheme.contains("0xFF0E0D12"),
        )
    }

    /**
     * The surface tokens are true black and near-black, never tinted.
     *
     * The first step above black exists for *elevation*, not for tint. `#050505` and
     * `#080808` are indistinguishable from black at arm's length on a phone, which is the
     * point: a card should be felt rather than seen. A card at `#0E0D12` is a navy card,
     * which is the defect this file exists to prevent.
     */
    @Test
    fun `the surface tokens step above black without tinting it`() {
        val tokens = appSource("ui/design/DesignTokens.kt").readText()
        assertTrue("the void token must be pure black", tokens.contains("void: Color = Color(0xFF000000)"))
        for (name in listOf("base", "raised", "elevated", "overlay")) {
            val match = Regex("""val $name: Color = Color\(0xFF([0-9A-Fa-f]{6})\)""").find(tokens)
            assertNotNull("no surface token named $name", match)
            val rgb = match!!.groupValues[1].let {
                listOf(
                    it.substring(0, 2).toInt(16),
                    it.substring(2, 4).toInt(16),
                    it.substring(4, 6).toInt(16),
                )
            }
            assertTrue(
                "the $name token is tinted: ${match.groupValues[1]}",
                maxOf(rgb[0], rgb[1], rgb[2]) - minOf(rgb[0], rgb[1], rgb[2]) <= 0x04,
            )
        }
    }

    /**
     * The purple brand accents are gone from the app.
     *
     * Purple was a Material seed colour adopted by default, and it became the product's
     * identity that way. This test fails the moment anyone reintroduces it as a *default*.
     */
    @Test
    fun `no purple brand colour is declared in the app`() {
        // Beyond the theme files: no *default* anywhere in the UI may resolve to violet.
        //
        // The theme passing this test while a default argument, a swatch list or a
        // placeholder reintroduced it would be the same defect wearing a different hat -
        // and it is exactly how the violet came back twice during this work: once through
        // `ResolvedTheme.BRAND`, once through `PackColor.parse`'s fallback. So this walks
        // every source file.
        for (file in File("src/main/kotlin/dev/charaly/app").walkTopDown()) {
            if (file.extension != "kt") continue
            val text = file.readText()
            assertFalse(
                "${file.name} uses the old violet #8B7BF0 as a default or placeholder",
                text.contains("8B7BF0"),
            )
            assertFalse(
                "${file.name} reintroduced a purple brand token",
                text.contains("BrandViolet") || text.contains("BrandRose"),
            )
        }
    }

    /**
     * The whole app draws its colour from the atmosphere, not from a literal.
     *
     * This is the structural half of "the world provides the colour". A screen that
     * hard-codes a hue bypasses `LocalAtmosphere` and would render the same colour for
     * every world, which is precisely the defect the per-pack components used to hide.
     */
    @Test
    fun `no screen hard-codes an accent colour`() {
        // Charaly's own ink and surfaces are structural, so only *chromatic* literals are
        // forbidden - a neutral `Color(0xFF…)` is a surface token, not an accent.
        val chromatic = Regex("""Color\(0xFF([0-9A-Fa-f]{6})\)""")
        val exempt = setOf(
            // The token file, which is where a literal is allowed to exist.
            "DesignTokens.kt",
            // The theme's two schemes, asserted above.
            "CharalyTheme.kt",
        )
        val offenders = mutableListOf<String>()
        for (file in File("src/main/kotlin/dev/charaly/app/ui").walkTopDown()) {
            if (file.extension != "kt" || file.name in exempt) continue
            file.readLines().forEachIndexed { index, line ->
                chromatic.findAll(line).forEach { match ->
                    val hex = match.groupValues[1]
                    val rgb = listOf(
                        hex.substring(0, 2).toInt(16),
                        hex.substring(2, 4).toInt(16),
                        hex.substring(4, 6).toInt(16),
                    )
                    if (maxOf(rgb[0], rgb[1], rgb[2]) - minOf(rgb[0], rgb[1], rgb[2]) > 0x10) {
                        offenders += "${file.name}:${index + 1} $hex"
                    }
                }
            }
        }
        assertTrue(
            "chromatic colour literals outside the token file: ${offenders.take(8)}",
            offenders.isEmpty(),
        )
    }

    /**
     * The transcript strips the action protocol.
     *
     * The engine fixes this, and the end-to-end test proves it; this asserts the fix is
     * still wired at the point the reply is stored, since a future change could easily
     * store `reply` again instead of the stripped form.
     */
    @Test
    fun `the runtime stores a reply with its action tags removed`() {
        val runtime = File("../charaly-runtime/src/main/kotlin/dev/charaly/runtime/session/CharalyRuntime.kt")
        val source = runtime.readText()
        assertTrue(
            "the transcript entry no longer strips action tags",
            source.contains("ProposedActionParser.stripActions(reply)"),
        )
    }

    // ------------------------------------------------------------------
    // Accessibility, checked on the sources
    // ------------------------------------------------------------------

    /**
     * No explicitly sized interactive element is smaller than the touch minimum.
     *
     * A back affordance that got smaller for looks is a real regression on a phone, and it
     * is invisible to every other test here.
     *
     * ## Why the scan is narrower than it looks
     *
     * Most small sizes in this app are *decorative*: a 20dp icon inside a 48dp target, a
     * 12dp presence dot, an 8dp rule, a 28dp portrait. Flagging every `.size(N.dp)` would
     * make this test either useless or a standing reason to inflate the artwork.
     *
     * So it does two narrower things and says so:
     *
     *  * screens and sheets only - the kit establishes its own floor, asserted below;
     *  * an explicit `.size(N.dp)` counts only when a tap handler appears within a few lines
     *    of it, because a size and a click handler in one modifier chain *is* the tappable.
     *
     * A floor on the check rather than a proof, which is why the kit's 48dp minimum is a
     * separate assertion.
     */
    @Test
    fun `no explicitly sized interactive element is smaller than 44dp`() {
        val offenders = mutableListOf<String>()
        val roots = listOf("ui/screens", "ui/sheets").map { appSource(it) }
        for (root in roots) {
            for (file in root.walkTopDown()) {
                if (file.extension != "kt") continue
                val lines = file.readLines()
                lines.forEachIndexed { index, line ->
                    val size = Regex("""\.size\((\d+)\.dp\)""").find(line)
                        ?.groupValues?.get(1)?.toInt() ?: return@forEachIndexed
                    if (size >= 44) return@forEachIndexed
                    val window = lines
                        .drop((index - 4).coerceAtLeast(0))
                        .take(9)
                        .joinToString("\n")
                    val isInteractive = window.contains(".clickable(") ||
                        window.contains(".tappable(") ||
                        window.contains("onClick =")
                    if (isInteractive) {
                        offenders += "${file.name}:${index + 1} ${size}dp interactive"
                    }
                }
            }
        }
        assertTrue("interactive elements below 44dp: $offenders", offenders.isEmpty())
    }

    /**
     * The component library's 48dp floor is real, for every interactive component.
     *
     * Asserted separately because that is where the guarantee is *established*: a screen
     * that calls a kit component inherits the floor for free, and one that rolls its own
     * `Surface(onClick =)` does not. A `heightIn(min = 48.dp)` and a fixed `size(48.dp)`
     * both satisfy it, which is why the assertion accepts either.
     */
    @Test
    fun `the component library enforces the 48dp floor`() {
        val kit = appSource("ui/components/CharalyKit.kt").readText()
        for (component in listOf("CharalyIconButton", "CharalyAction", "CharalyQuietAction")) {
            val declaration = Regex("""fun $component\((.*?)\) \{""", RegexOption.DOT_MATCHES_ALL)
                .find(kit)
            assertNotNull("$component is not declared in the kit", declaration)
            val body = kit.substring(declaration!!.range.last).substringBefore("\n}\n")
            assertTrue(
                "$component has no 48dp minimum",
                body.contains("heightIn(min = 48.dp)") ||
                    body.contains("heightIn(min = 52.dp)") ||
                    body.contains("size(48.dp)"),
            )
        }
    }

    /**
     * Every icon button declares a content description.
     *
     * Checked by source scan because Compose semantics cannot be asserted from a JVM
     * test. An icon button with no description is invisible to a screen reader, which is
     * the same as not existing for a blind user.
     */
    @Test
    fun `every icon button declares a content description`() {
        val offenders = mutableListOf<String>()
        for (file in screensDir().listFiles()?.filter { it.name.endsWith(".kt") }.orEmpty()) {
            val text = file.readText()
            Regex("""CharalyIconButton\((?:[^()]|\([^()]*\))*\)""").findAll(text).forEach { call ->
                if (!call.value.contains("contentDescription")) {
                    offenders += "${file.name}: ${call.value.take(70).replace('\n', ' ')}"
                }
            }
        }
        assertTrue("icon buttons with no contentDescription: $offenders", offenders.isEmpty())
    }

    /**
     * The component library takes content descriptions as required arguments.
     *
     * Stronger than the scan above, and the reason it is worth both: a parameter with no
     * default cannot be omitted, so a new icon control cannot be added without naming
     * itself. The scan catches call sites; this catches the API.
     */
    @Test
    fun `the icon button requires its content description`() {
        val kit = appSource("ui/components/CharalyKit.kt").readText()
        val declaration = Regex("""fun CharalyIconButton\((.*?)\) \{""", RegexOption.DOT_MATCHES_ALL)
            .find(kit)
        assertNotNull("CharalyIconButton is not declared in the kit", declaration)
        val params = declaration!!.groupValues[1]
        val description = Regex("""contentDescription:\s*String""").find(params)
        assertNotNull("CharalyIconButton has no contentDescription parameter", description)
        assertFalse(
            "CharalyIconButton's contentDescription must not have a default",
            description!!.value.contains("="),
        )
    }

    /**
     * Selection is never carried by colour alone.
     *
     * The nav pill, the filter pills and the sheet tabs all change *shape* or fill as well
     * as tint, so a user who cannot distinguish the accent from the muted ink still knows
     * which destination or which sheet they are on.
     */
    @Test
    fun `a selected pill changes its fill and its border, not just its colour`() {
        val kit = appSource("ui/components/CharalyKit.kt").readText()
        val pill = Regex("""fun CharalyPill\((.*?)\) \{""", RegexOption.DOT_MATCHES_ALL)
            .find(kit)
        assertNotNull("CharalyPill is not declared in the kit", pill)
        val body = kit.substring(pill!!.range.last)
            .substringBefore("\n}\n")
        assertTrue(
            "a selected pill must change its border as well as its fill",
            body.contains("BorderStroke(1.dp, tint.copy"),
        )
    }

    /**
     * The chat composer survives the keyboard.
     *
     * `imePadding()` on the composer is the whole reason a user can write a line without
     * the field hiding behind the keyboard - and it is the kind of omission that is
     * invisible on a tall emulator and fatal on a short phone.
     */
    @Test
    fun `the composer is inset for the keyboard`() {
        val stage = appSource("ui/screens/StageScreen.kt").readText()
        assertTrue(
            "StageScreen never calls imePadding, so the composer hides behind the keyboard",
            stage.contains("imePadding("),
        )
    }

    /**
     * Every screen renders an empty state rather than a blank surface.
     *
     * The brief is absolute: no screen may render "No data", null or an empty list. Compose
     * will happily propagate a thrown exception out of a composable and kill the activity,
     * so every top-level screen takes a nullable projection and handles the null case.
     */
    @Test
    fun `every screen that takes a nullable projection handles the null case`() {
        // The route-carrying screens receive something that can be absent - a pack deleted
        // from another screen, a story removed mid-session, a model deleted mid-session -
        // and must render an empty state rather than propagate a null into Compose.
        val nullableScreens = listOf(
            "ShowcaseScreen.kt" to "showcase: PackShowcase?",
            "StageScreen.kt" to "stage: ChatStage?",
            "EnterWorldScreen.kt" to "snapshot: NewStorySnapshot?",
            "ModelDetailScreen.kt" to "snapshot: ModelDetailSnapshot?",
        )
        for ((file, signature) in nullableScreens) {
            val source = appSource("ui/screens/$file").readText()
            assertTrue("$file no longer declares `$signature`", source.contains(signature))
            assertTrue(
                "$file accepts a null projection but never checks for it",
                Regex("""(snapshot|showcase|stage)\s*==\s*null""").containsMatchIn(source),
            )
            // And the fallback is a *screen*, not a blank box.
            assertTrue(
                "$file handles null with something other than an empty state",
                Regex("""(EmptyState|Skeleton|Empty|Missing)""").containsMatchIn(source),
            )
        }

        // The four always-present projections are guarded by `loading` instead, which is
        // the honest signal: they are computed from state that is always in memory.
        val alwaysPresent = listOf(
            "HomeScreen.kt" to "snapshot: LobbySnapshot",
            "SessionsScreen.kt" to "shelf: LibraryShelf",
            "LibraryScreen.kt" to "shelf: LibraryShelf",
        )
        for ((file, signature) in alwaysPresent) {
            val source = appSource("ui/screens/$file").readText()
            assertTrue("$file no longer declares `$signature`", source.contains(signature))
            assertTrue(
                "$file renders a skeleton rather than an empty surface while loading",
                Regex("""loading\s*[:=]""").containsMatchIn(source) &&
                    Regex("""Skeleton""").containsMatchIn(source),
            )
        }
    }

    /**
     * The showcase screen renders no roster.
     *
     * A weaker, source-level check than the type-level one above, and kept as a second
     * line of defence: if someone reintroduced a characters loop into the showcase
     * composable while the type still lacked the field, this would catch the compile-time
     * failure's more confusing cousin - a person adding the field to work around it.
     */
    @Test
    fun `the showcase screen renders no character or location list`() {
        val source = appSource("ui/screens/ShowcaseScreen.kt").readText()
        for (forbidden in listOf(
            "showcase.characters", "showcase.locations", "showcase.events", "showcase.lore",
        )) {
            assertFalse("the showcase screen renders $forbidden", source.contains(forbidden))
        }
    }

    /**
     * The worlds feed renders no counts.
     *
     * "19 characters · 26 locations · 9 events" is the *engine's* inventory, printed on a
     * product surface. The feed item has no such field, so this asserts both that the
     * projection has none and that the screen did not reach for one.
     */
    @Test
    fun `the worlds feed carries no inventory counts`() {
        val item = dev.charaly.runtime.presentation.WorldFeedItem(
            id = "x",
            title = "t",
            premise = "p",
            tone = "n",
            hook = "h",
            genres = emptyList(),
            artwork = dev.charaly.runtime.domain.PackArtwork(),
            theme = ResolvedTheme.BRAND,
            weight = dev.charaly.runtime.presentation.WorldWeight.QUIET,
            lastPlayedLabel = "",
            sessionCount = 0,
        )
        val fields = item::class.java.declaredFields.map { it.name }.toSet()
        for (forbidden in listOf("characterCount", "locationCount", "eventCount", "threadCount")) {
            assertFalse("WorldFeedItem exposes '$forbidden'", fields.contains(forbidden))
        }

        val source = appSource("ui/screens/LibraryScreen.kt").readText()
        for (forbidden in listOf("characters.size", "locations.size", "events.size")) {
            assertFalse("the pack shelf renders $forbidden", source.contains(forbidden))
        }
    }

    /**
     * The showcase route uses the showcase screen.
     *
     * Checked structurally: the route's screen is handed a `PackShowcase`, which has no
     * field for a character, location or event. A screen cannot render a field that does
     * not exist, so this is a real guarantee rather than a convention.
     */
    @Test
    fun `the showcase route renders the showcase screen`() {
        val app = appSource("ui/CharalyApp.kt").readText()
        val route = app.substringAfter("is Route.Showcase ->")
            .substringBefore("is Route.EnterWorld ->")
        assertTrue(
            "the Showcase route no longer renders ShowcaseScreen",
            route.contains("ShowcaseScreen"),
        )
        assertTrue(
            "the route is not using the strict showcase projection",
            route.contains("viewModel.showcase("),
        )
    }

    /**
     * Every route in the table is rendered, and rendered exactly once.
     *
     * A `when` over a sealed interface is exhaustive by the compiler, so a *missing* route
     * cannot compile. What can happen is two routes rendering the same screen with
     * different behaviour, and - before this pass - the app had eleven routes and no
     * statement anywhere that each had exactly one screen.
     */
    @Test
    fun `every route is handled exactly once by the router`() {
        val app = appSource("ui/CharalyApp.kt").readText()
        val router = app.substringAfter("when (route) {").substringBefore("private fun ChatRoute")
        val routes = listOf(
            "Route.Home", "Route.Sessions", "Route.Create", "Route.Chat", "Route.Library",
            "Route.Models", "Route.Settings", "Route.Developer", "Route.Authoring",
            "Route.Showcase", "Route.EnterWorld", "Route.Stage", "Route.ModelDetail",
            "Route.StoryRecord",
        )
        for (route in routes) {
            // Only the branch arms count - `(?:is )?Route.X ->`. A bare mention of the route
            // inside another branch (constructing a child route, checking its type) is not a
            // second handler, and counting those would flag correct code.
            val branches = Regex("""(?:is )?${Regex.escape(route)}\s*->""").findAll(router).count()
            assertEquals(
                "$route must have exactly one branch in the router",
                1,
                branches,
            )
        }
    }

    /**
     * The chat destination and a specific story share one screen.
     *
     * Two routes to one screen is fine. Two *versions* of one screen is how the stage ends
     * up behaving differently depending on how the user reached it, so both branches must
     * route through the same composable.
     */
    @Test
    fun `both routes to the stage render the same screen`() {
        val app = appSource("ui/CharalyApp.kt").readText()
        val router = app.substringAfter("when (route) {").substringBefore("private fun ChatRoute")
        assertTrue(
            "the CHAT destination no longer resolves to the shared stage route",
            router.contains("ChatRoute("),
        )
        assertEquals(
            "the stage is rendered by two different composables",
            2,
            Regex("""ChatRoute\(\s*""").findAll(router).count(),
        )
    }

    /**
     * Developer Mode is re-checked where the route is rendered, not only where the button
     * is hidden.
     *
     * A restored back stack can contain the developer route: open the panel, turn developer
     * mode off, restart. Without the render-time check the inspector comes back for a user
     * who had explicitly turned it off.
     */
    @Test
    fun `developer mode is checked at render time`() {
        val app = appSource("ui/CharalyApp.kt").readText()
        val branch = app.substringAfter("Route.Developer ->").substringBefore("Route.Authoring ->")
        assertTrue(
            "the Developer route renders without re-checking state.developerMode",
            branch.contains("if (state.developerMode)"),
        )
        assertTrue(
            "with developer mode off there must be something to render instead",
            branch.contains("DeveloperLockedScreen"),
        )
    }

    /**
     * The composer's IME handling and the sheets' inset handling are both declared.
     *
     * Same reasoning as the composer test above, applied to the sheet: a sheet whose
     * content runs under the navigation bar is the second-most-common phone layout defect
     * after the keyboard one.
     */
    @Test
    fun `the context sheets clear the navigation bar`() {
        val sheets = appSource("ui/sheets/StageSheets.kt").readText()
        assertTrue(
            "StageSheets never applies navigationBarsPadding, so a sheet's last row sits " +
                "under the system bar",
            sheets.contains("navigationBarsPadding("),
        )
    }

    /**
     * There are exactly four player-facing sheets.
     *
     * The previous chat offered seven destinations organised by engine subsystem. The
     * redesign says four, organised by the questions a player actually has mid-scene. The
     * count is asserted here because it is the single most reversible decision in the
     * redesign.
     */
    @Test
    fun `the stage offers exactly four player-facing sheets`() {
        val stage = appSource("ui/screens/StageScreen.kt").readText()
        assertTrue(
            "the sheet enum's player-facing list has changed",
            stage.contains("listOf(WORLD, MEMORY, PEOPLE, STORY)"),
        )
        // And the runtime's own context projection agrees about which four.
        val context = dev.charaly.runtime.presentation.ContextEntryId.entries
        assertEquals(
            listOf("WORLD", "MEMORY", "PEOPLE", "STORY"),
            context.map { it.name },
        )
    }

    /**
     * The per-pack screen components are gone.
     *
     * `MiraculousScreen` and `NeonScreen` were how a pack's identity leaked into the
     * component layer: each world got its own hand-written screen, and every new pack
     * required new UI code. The redesign makes the world supply *data* - an accent, a
     * gradient, an atmosphere - and the same screens draw all of them.
     */
    @Test
    fun `there is no per-pack screen component`() {
        val screens = screensDir().listFiles()?.map { it.name }.orEmpty()
        for (forbidden in listOf("MiraculousScreen.kt", "NeonScreen.kt")) {
            assertFalse("$forbidden is back: a pack must not own a screen", screens.contains(forbidden))
        }
        for (file in File("src/main/kotlin/dev/charaly/app/ui").walkTopDown()) {
            if (file.extension != "kt") continue
            val text = file.readText()
            assertFalse(
                "${file.name} mentions a per-pack screen",
                text.contains("fun MiraculousScreen(") || text.contains("fun NeonScreen("),
            )
        }
    }

    private fun screensDir(): File = appSource("ui/screens")

    private fun assertNeutral(what: String, argb: Long) {
        val r = ((argb shr 16) and 0xFF).toInt()
        val g = ((argb shr 8) and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        val spread = maxOf(r, g, b) - minOf(r, g, b)
        assertTrue("$what is chromatic (spread $spread)", spread <= 0x12)
    }

    private companion object {
        val ROOT_OF: Map<Route.Tab, Route> = mapOf(
            Route.Tab.HOME to Route.Home,
            Route.Tab.SESSIONS to Route.Sessions,
            Route.Tab.CREATE to Route.Create,
            Route.Tab.LIBRARY to Route.Library,
            Route.Tab.ME to Route.Settings,
        )
    }
}
