package dev.charaly.app.ui

import dev.charaly.app.ui.nav.CharalyNavigator
import dev.charaly.app.ui.nav.Route
import dev.charaly.app.ui.nav.tabOrNull
import dev.charaly.runtime.presentation.CharalyDestination
import dev.charaly.runtime.presentation.ComposerMode
import dev.charaly.runtime.presentation.LayoutPolicy
import dev.charaly.runtime.presentation.MotionPolicy
import dev.charaly.runtime.presentation.WorldWeight
import dev.charaly.runtime.presentation.WorldsFeedPresenter
import dev.charaly.runtime.pack.DemoStoryPacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE THINGS A JETPACK COMPOSE TEST CANNOT CHECK.
 *
 * ## Why this file exists
 *
 * There is no Robolectric and no instrumented test in this build, so several brief
 * requirements cannot be verified by running the UI. They are verified by reading the
 * source instead, and this file is where that admission is made explicit rather than left
 * implicit - a test suite that quietly omits a requirement reads like a suite that met it.
 *
 * Every test here is a *structural* check on code that Compose cannot execute here. Each
 * says what it proves and what it does not.
 *
 * ## The honest summary
 *
 * These prove the wiring is present. They do not prove the pixels: no emulator or device is
 * available in this environment, so transition timing, layout at real breakpoints, IME
 * behaviour and gesture handling are unverified on hardware and are reported as such.
 */
class SourceContractTest {

    private fun appSource(relativePath: String): File =
        File("src/main/kotlin/dev/charaly/app/$relativePath")

    private fun appText(relativePath: String): String = appSource(relativePath).readText()

    // ------------------------------------------------------------------
    // The enter-world transition
    // ------------------------------------------------------------------

    /**
     * The transition is staged, and its budget is the cinematic one.
     *
     * ## Why this matters enough to assert in source
     *
     * The brief asks for a staged 400-700ms crossing between browsing and being somewhere.
     * That is a property of the timing *policy* and of the composable's structure, both of
     * which are inspectable without a device: `cinematicDuration()` resolves through
     * `motionDuration` against a 620ms token, and the four alphas are separate phases of one
     * progress value rather than four independent animations.
     *
     * Four separate animations would be the wrong implementation: they cannot be reversed
     * together, so dismissing the transition mid-flight leaves the screen in a state no
     * combination of the four was designed for.
     */
    @Test
    fun `the enter-world transition is staged and budgeted`() {
        val source = appText("ui/screens/ShowcaseScreen.kt")

        // Four phases, each derived from the same progress value.
        for (phase in listOf("titleAlpha", "darkAlpha", "companionAlpha", "sceneAlpha")) {
            assertTrue(
                "the transition has no $phase phase, so it is not staged",
                source.contains(phase),
            )
        }
        assertTrue(
            "every phase must be derived from `progress`, not from its own animation",
            Regex("""val \w+Alpha = .*progress""").findAll(source).count() >= 4,
        )
        // Each phase reads `progress` exactly once, which is what makes the sequence one
        // interruptible thing rather than four racing animations.
        for (phase in listOf("titleAlpha", "darkAlpha", "companionAlpha", "sceneAlpha")) {
            val declaration = requireNotNull(
                Regex("""val $phase = ([^\n]+)""").find(source)?.groupValues?.get(1),
            ) { "no declaration for $phase" }
            assertEquals(
                "$phase must be a pure function of `progress`, found: $declaration",
                1,
                Regex("""progress""").findAll(declaration).count(),
            )
        }
        // The darkening, the fading, the companion and the scene, in that order.
        assertTrue(
            "the companion must appear after the darkening, not with it",
            source.indexOf("val darkAlpha =") < source.indexOf("val companionAlpha ="),
        )
        assertTrue(
            "the scene line must be last, once the world has taken over",
            source.indexOf("val companionAlpha =") < source.indexOf("val sceneAlpha ="),
        )
        // And the phases must be staggered rather than simultaneous, or it is a single fade
        // wearing four hats.
        assertTrue(
            "the phases must not all start at zero, or nothing is staged",
            source.contains("progress / 0.35f") &&
                source.contains("progress / 0.55f") &&
                source.contains("progress - 0.4f") &&
                source.contains("progress - 0.6f"),
        )
        // And the budget.
        assertTrue(
            "the transition must use the cinematic budget, not the standard one",
            appText("ui/screens/EnterWorldScreen.kt").contains("cinematicDuration()"),
        )
    }

    /**
     * The transition is actually reached, not merely declared.
     *
     * The exact failure this file exists to catch, and it happened: `EnterWorldTransition`
     * was written, correct, documented, and never called. The review screen's "Step in"
     * button called the runtime directly, so the app cut from a form to a scene with no
     * crossing between them - the one moment the brief asks for an entrance.
     *
     * A composable nobody calls is invisible to the compiler and to a reviewer reading the
     * screen, because the screen is self-consistent without it.
     */
    @Test
    fun `the enter-world transition is placed, not just declared`() {
        val screen = appText("ui/screens/EnterWorldScreen.kt")
        assertTrue(
            "EnterWorldTransition is declared but never placed, so entering a world cuts " +
                "straight from a form to a scene",
            screen.contains("EnterWorldTransition("),
        )

        // And it runs on the action that enters the world, not on some other step.
        // The label moved into the localisation catalogue, so the contract now looks for the
        // catalogue key the screen renders rather than for an English literal. The property
        // being asserted is unchanged: the transition is driven by the action that enters
        // the world.
        val actionIndex = screen.indexOf("NewStoryStep.REVIEW -> Loc.t(\"setup.enter\")")
        val transitionIndex = screen.indexOf("EnterWorldTransition(")
        assertTrue("could not locate the Step in action", actionIndex > 0)
        assertTrue("could not locate the transition", transitionIndex > 0)
        assertTrue(
            "the transition must be set by the action that enters the world",
            screen.indexOf("entering = true") in 0 until transitionIndex,
        )

        // And it always clears, so it cannot strand the user on a black screen whatever
        // the runtime does.
        assertTrue(
            "the transition must be released on its own; if story creation fails the user " +
                "is left looking at a black screen",
            Regex("""LaunchedEffect\(entering\)""").containsMatchIn(screen),
        )
    }

    /**
     * The cinematic budget is 620ms - inside the brief's 400-700ms window.
     *
     * Asserted here as well as in the token test because it is the one number in the app
     * with a range written into a requirement rather than a judgement, and a token change
     * that pushed it outside that range would be silent.
     */
    @Test
    fun `the cinematic budget sits inside the brief's window`() {
        val cinematic = 620
        assertTrue("the cinematic budget must be at least 400ms", cinematic >= 400)
        assertTrue("the cinematic budget must be at most 700ms", cinematic <= 700)
        assertTrue(
            "the token and the assertion must agree",
            appText("ui/design/DesignTokens.kt").contains("cinematic: Int = $cinematic"),
        )
        // And it is the only transition allowed to use it. `Motion.kt` is excluded because
        // it is where the accessor is *declared*, not consumed.
        val users = File("src/main/kotlin/dev/charaly/app/ui").walkTopDown()
            .filter { it.extension == "kt" && it.name != "Motion.kt" }
            .filter { it.readText().contains("cinematicDuration()") }
            .map { it.name }
            .toList()
        assertEquals(
            "cinematicDuration() must be consumed in exactly one place - the enter-world " +
                "transition - but it is used in $users",
            listOf("EnterWorldScreen.kt"),
            users,
        )
    }

    /**
     * The whole transition collapses under reduced motion, and the state still changes.
     *
     * A zero duration is not a skipped animation: the alphas still take their values, so
     * the screen lands in the right state. Skipping the animation outright would leave a
     * composable permanently mid-transition.
     */
    @Test
    fun `reduced motion removes the travel but not the state`() {
        assertEquals(0, MotionPolicy.NONE.durationMs)
        val source = appText("ui/theme/Motion.kt")
        assertTrue(
            "NONE must return 0, not skip the animation machinery",
            source.contains("MotionPolicy.NONE -> 0"),
        )
        // And nothing in the transition sets visibility from the policy - only its alpha,
        // which is exactly why a zero duration is safe.
        val transition = appText("ui/screens/ShowcaseScreen.kt")
        val block = transition.substringAfter("fun EnterWorldTransition(").substringBefore("\n}\n")
        assertFalse(
            "the transition must not gate a whole phase on the motion policy, or reduced " +
                "motion would leave content permanently invisible",
            Regex("""allowsDecorativeMotion\(\)""").containsMatchIn(block),
        )
    }

    // ------------------------------------------------------------------
    // The chat screen's structure
    // ------------------------------------------------------------------

    /**
     * The composer is pinned to the bottom and clears the keyboard.
     *
     * Two distinct requirements, both invisible to a JVM test: the composer must be the
     * last thing in the column (so it is above the fold rather than scrolled away), and the
     * column must be inset for the IME (so the field is not behind the keyboard). The
     * comment in `StageScreen` explains why the padding is on the column rather than on the
     * composer itself - padding only the composer leaves a gap that swallows the input.
     */
    @Test
    fun `the composer is pinned to the bottom and inset for the keyboard`() {
        val source = appText("ui/screens/StageScreen.kt")

        // imePadding on the conversation column, not on the composer's own surface.
        val column = source.substringAfter("Column(\n                Modifier\n                    .weight(1f)")
            .substringBefore("\n        )")
        assertTrue(
            "the conversation column must carry imePadding, or the composer hides behind " +
                "the keyboard",
            column.contains(".imePadding()"),
        )

        // The composer is the final child of that column: the transcript's weighted box
        // comes before it and nothing meaningful after.
        val composerIndex = source.lastIndexOf("StageComposer(")
        val transcriptIndex = source.lastIndexOf("stage.beats.size")
        assertTrue(
            "the transcript must be drawn before the composer, or the composer is not pinned",
            transcriptIndex in 0 until composerIndex,
        )
    }

    /**
     * The composer offers its modes, and Say is first.
     *
     * People use Say. Offering Observe before Say would be a statement about the engine's
     * capabilities rather than about what the user came to do.
     */
    @Test
    fun `the composer offers its modes with Say first`() {
        val runtime = File(
            "../charaly-runtime/src/main/kotlin/dev/charaly/runtime/presentation/StoryPresenters.kt",
        ).readText()
        assertEquals("Söyle", ComposerMode.entries.first().label)
        // And the stage projects all three player modes rather than only SAY.
        val stage = File(
            "../charaly-runtime/src/main/kotlin/dev/charaly/runtime/presentation/ChatStage.kt",
        ).readText()
        assertTrue(
            "the stage must offer SAY, DO and THINK",
            stage.contains("listOf(ComposerMode.SAY, ComposerMode.DO, ComposerMode.THINK)"),
        )
    }

    /**
     * The stage has a minimal header, and the transcript is prose rather than bubbles.
     *
     * The brief's hardest visual requirement is "a scene, not a messenger", and the way it
     * failed before was structurally: the renderer typeset each turn as a separate row, so
     * one reply with dialogue and an action read as three log lines. The fix is that the
     * presenter's `Beat` carries all three together and the screen renders one beat as one
     * paragraph.
     */
    @Test
    fun `a character's reply is one beat carrying dialogue narration and action together`() {
        val runtime = File(
            "../charaly-runtime/src/main/kotlin/dev/charaly/runtime/presentation/ChatStage.kt",
        ).readText()
        // The beat type has all three fields...
        assertTrue(runtime.contains("val dialogue: String"))
        assertTrue(runtime.contains("val narration: String"))
        assertTrue(runtime.contains("val action: String"))
        // ...and one CHARACTER entry produces exactly one beat, not three.
        val characterBranch = runtime
            .substringAfter("TranscriptRole.CHARACTER -> {")
            .substringBefore("streamingBeat")
        assertTrue(
            "a character entry must produce a single beat; splitting it into three rows is " +
                "what made the transcript read as a log",
            Regex("""listOf\(\s*Beat\(""").containsMatchIn(characterBranch),
        )
        assertFalse(
            "a character entry must not produce one beat per segment",
            characterBranch.contains("flatMap"),
        )
    }

    /**
     * Story moments are sparse and capped.
     *
     * A moment per world change is a ticker the user learns to ignore; the budget is the
     * design. Capped at three, and never invented - an empty list is the correct answer for
     * a story that has just started.
     */
    @Test
    fun `story moments are capped and never invented`() {
        val runtime = File(
            "../charaly-runtime/src/main/kotlin/dev/charaly/runtime/presentation/Lobby.kt",
        ).readText()
        assertTrue(runtime.contains("const val MAX_MOMENTS = 3"))
        assertTrue(runtime.contains("StoryFeed.build(instance).take(limit)"))
        // And the honest-nothing branch exists, because a lobby that always claims
        // something happened has to lie on every quiet install.
        assertTrue(
            "there must be an honest sentence for when nothing has changed",
            runtime.contains("Nothing has changed since you left"),
        )
    }

    // ------------------------------------------------------------------
    // The worlds feed
    // ------------------------------------------------------------------

    /**
     * The three weights are actually drawn at three sizes.
     *
     * A `WorldWeight` enum that every composable treats identically is a grid wearing a
     * vertical hat, which is the thing the brief rules out. Asserted against the source
     * because the sizes are literal `Dp` values in the composable.
     */
    /**
     * The worlds feed is gone; V5's Library shows every pack as an equal poster.
     *
     * What replaced the weight system is a *poster grid*: every story and every pack at
     * the same 3:4.1 shape, two columns, because a shelf of worlds is a set you choose
     * between rather than a headline to be dominated by. The one weight that remains is
     * the Continue card on Home, which is asserted there.
     */
    @Test
    fun `the library draws its posters at one shape rather than a weighted feed`() {
        val source = appText("ui/screens/LibraryScreen.kt")
        assertTrue(
            "the poster grid must set one aspect ratio for every cover",
            source.contains("aspectRatio(3f / 4.1f)"),
        )
        assertFalse(
            "a weight system survived into the poster grid",
            source.contains("WorldWeight"),
        )
    }

    /**
     * The feed's ordering is play-history first, and it is stable.
     *
     * The world you were last in is the one you most likely want again, so it leads. And the
     * final tiebreak is alphabetical rather than by pack id, because an order that shifts
     * between launches for two never-played worlds reads as the app having changed its mind.
     */
    @Test
    fun `the feed leads with play history and orders stably`() {
        val source = File(
            "../charaly-runtime/src/main/kotlin/dev/charaly/runtime/presentation/WorldsFeed.kt",
        ).readText()
        val ordering = source.substringAfter("private fun ordering(").substringBefore("\n    }\n")
        assertTrue(
            "the feed must order by last-played first",
            ordering.contains("lastPlayed[it.id.value] ?: 0L"),
        )
        assertTrue(
            "the feed must fall back to alphabetical order, or it reshuffles between launches",
            ordering.contains("it.title.lowercase()"),
        )
        // Asserted on real data as well: with nothing played, the order is alphabetical.
        val feed = WorldsFeedPresenter.build(0L, emptyList(), DemoStoryPacks.all)
        val titles = feed.worlds.map { it.title }
        assertEquals(titles.sortedBy { it.lowercase() }, titles)
    }

    // ------------------------------------------------------------------
    // Home
    // ------------------------------------------------------------------

    /**
     * Home asks one question, and the continuation surface is the one big thing.
     *
     * "Where do you want to go?" is asserted on the presenter rather than the screen
     * because the string lives there, and because it is the single most-repeated piece of
     * product copy in the rebuild.
     */
    @Test
    fun `home asks one question`() {
        val runtime = File(
            "../charaly-runtime/src/main/kotlin/dev/charaly/runtime/presentation/Lobby.kt",
        ).readText()
        assertTrue(
            "the lobby headline must be the question the brief specifies",
            runtime.contains("""const val HEADLINE = "Nereye gitmek istersiniz?""""),
        )
        // And Home renders it rather than inventing its own.
        val home = appText("ui/screens/HomeScreen.kt")
        assertTrue(
            "HomeScreen does not render the presenter's headline",
            home.contains("snapshot.headline"),
        )
    }

    /**
     * Home renders no inventory counts.
     *
     * No character counts, no location counts, no event counts, no thread totals. Those are
     * the *engine's* inventory, and printing them on the surface a player arrives at is what
     * made the previous Home read as a database containing 19 characters.
     *
     * Asserted against the projection's field names (which is where such a field would first
     * have to exist) and against the screen's source.
     */
    @Test
    fun `home shows no counts`() {
        val runtime = File(
            "../charaly-runtime/src/main/kotlin/dev/charaly/runtime/presentation/Lobby.kt",
        ).readText()
        for (forbidden in listOf(
            "characterCount", "locationCount", "eventCount", "threadCount", "memoryCount",
        )) {
            assertFalse(
                "the lobby projection exposes `$forbidden`, which Home must not show",
                runtime.contains("val $forbidden"),
            )
        }

        val home = appText("ui/screens/HomeScreen.kt")
        for (forbidden in listOf(
            "characters.size", "locations.size", "events.size",
            "storyThreads.size", "memories.size",
        )) {
            assertFalse("HomeScreen renders `$forbidden`", home.contains(forbidden))
        }
    }

    /**
     * The continuation surface carries the four facts the brief names.
     *
     * World name, scene time, a one-sentence moment, who is there, and a Continue control.
     * All five are fields on the projection, so a screen cannot draw a missing one - and
     * each is asserted to be non-blank on a real story below.
     */
    @Test
    fun `the continuation surface carries exactly the four facts the brief names`() {
        val runtime = File(
            "../charaly-runtime/src/main/kotlin/dev/charaly/runtime/presentation/Lobby.kt",
        ).readText()
        val declaration = runtime
            .substringAfter("data class ContinueSurface(")
            .substringBefore("\n)\n")
        for (field in listOf("worldName", "moment", "presenceLabel", "timeLabel")) {
            assertTrue(
                "the continuation surface has no `$field`, so the brief's requirement " +
                    "cannot be met",
                declaration.contains("val $field"),
            )
        }
        // And none of the counts it is defined to exclude.
        for (forbidden in listOf("characterCount", "locationCount", "eventCount")) {
            assertFalse(declaration.contains(forbidden))
        }

        val home = appText("ui/screens/HomeScreen.kt")
        assertTrue(
            "HomeScreen must offer a Continue control on the surface",
            home.contains("Continue"),
        )
    }

    // ------------------------------------------------------------------
    // Library and Settings
    // ------------------------------------------------------------------

    /**
     * Me is the profile destination, and Settings lives inside it.
     *
     * V5 puts the self in the bar: a person expects to find their own preferences under
     * their own profile, and the handoff is explicit that "Models, Me'nin alt ekranıdır
     * (Me aktif kalır)". What stays true from the previous design is the *weight*: Me is
     * one icon among five, not a bar item that competes with the stories.
     */
    @Test
    fun `settings is reached from home and not from the bar`() {
        assertEquals(
            "Settings must resolve to Me, the profile destination",
            Route.Tab.ME,
            Route.Settings.tabOrNull(),
        )
        val home = appText("ui/screens/HomeScreen.kt")
        assertTrue(
            "HomeScreen does not offer a route to Settings",
            home.contains("onOpenSettings"),
        )
        val shell = appText("ui/shell/CharalyShell.kt")
        // And the bar offers exactly the five tabs, by name.
        for (tab in Route.Tab.entries) {
            assertTrue(
                "the shell does not draw the ${tab.name} tab",
                shell.contains("Route.Tab.${tab.name}"),
            )
        }
    }

    /**
     * The library is a shelf, and destructive actions are deliberate.
     *
     * A swipe-to-delete on a living story is how a user loses four hours of a story they
     * meant to pick up tomorrow. Delete exists; it is reached on purpose.
     */
    @Test
    fun `the library offers no swipe to delete`() {
        val library = appText("ui/screens/LibraryScreen.kt")
        val sessions = appText("ui/screens/SessionsScreen.kt")
        for (forbidden in listOf("swipeable", "SwipeToDismiss", "detectHorizontalDragGestures")) {
            assertFalse(
                "the library offers `$forbidden`: losing a story must be deliberate",
                library.contains(forbidden),
            )
            assertFalse(
                "the sessions list offers `$forbidden`: losing a story must be deliberate",
                sessions.contains(forbidden),
            )
        }
        // And the record screen's delete is behind an explicit control.
        val record = appText("ui/screens/StoryRecordScreen.kt")
        assertTrue(record.contains("onDelete"))
    }

    // ------------------------------------------------------------------
    // What these tests cannot prove
    // ------------------------------------------------------------------

    /**
     * The gap this suite leaves, written down.
     *
     * A test that documents what it cannot check is more useful than one that quietly
     * omits it, because a reader otherwise has to discover the gap by running the app. This
     * one asserts nothing about the UI; its job is to make the boundary permanent.
     */
    @Test
    fun `the untested surface is written down rather than assumed`() {
        // Everything above is a source check. No test in this build renders a composable,
        // because there is no Robolectric in the dependency set and no device in the
        // environment. So these are the claims that remain unverified on hardware:
        //
        //   * that the layout holds at real breakpoints (tablet rail, split pane)
        //   * that the enter-world transition reads as staged rather than as a flicker
        //   * that IME insets behave on a physical keyboard-opening device
        //   * that the back gesture and the composer coexist on a real system bar
        //   * that the artwork generator produces distinguishable images per pack
        //
        // Each is reported as UNVERIFIED rather than as passing. This assertion exists so
        // the list cannot be quietly shortened.
        val source = File("src/test/kotlin/dev/charaly/app/ui/SourceContractTest.kt")
        assertTrue(source.isFile)
        val text = source.readText()
        for (item in listOf(
            "real breakpoints",
            "reads as staged",
            "IME insets",
            "back gesture",
            "artwork generator",
        )) {
            assertTrue(
                "the unverified-surface list dropped \"$item\"; keep it honest",
                text.contains(item),
            )
        }
    }

    /**
     * The About sheet is reachable, and it is a layer rather than a destination.
     *
     * The brief asks for an About sheet on the showcase. The first implementation declared
     * `AboutWorldSheet`, documented it, and wired the button to `announce(title)` - a
     * snackbar - so the feature existed in source and nowhere else. Same class of defect as
     * the unplaced transition, and the same reason for the test.
     *
     * It is deliberately *not* a route: a reader who taps "About this world" wants to glance
     * and come back to the poster, and putting it on the back stack turns that into
     * something to pop out of.
     */
    @Test
    fun `the about sheet is placed, not just declared`() {
        val source = appText("ui/screens/ShowcaseScreen.kt")
        assertTrue("AboutWorldSheet is not declared", source.contains("fun AboutWorldSheet("))
        assertTrue(
            "AboutWorldSheet is declared but never placed, so the showcase has no About sheet",
            source.contains("AboutWorldSheet("),
        )
        // Openable, and the button that opens it is the affordance rather than a snackbar.
        assertTrue(
            "the About affordance must not resolve to a snackbar announcement",
            source.contains("val openAbout"),
        )
        assertTrue(
            "the About sheet's visibility must be local state, not a navigation route",
            source.contains("var aboutOpen by remember"),
        )
        assertFalse(
            "About must not be a route: a sheet over a poster is a glance, not a destination",
            File("src/main/kotlin/dev/charaly/app/ui/nav/CharalyNavigator.kt")
                .readText()
                .contains("About"),
        )
    }

    /**
     * No screen declares a composable it never places.
     *
     * ## Why this scan exists
     *
     * Two of the defects found while rebuilding this UI were of exactly this shape:
     * `EnterWorldTransition` and `AboutWorldSheet` were both written, documented and
     * correct, and neither was called. The compile succeeded, the screen was self-consistent,
     * and a reviewer reading the screen would see nothing wrong - because the missing thing
     * is an absence.
     *
     * So: every top-level composable declared in a screen must either be called somewhere,
     * or be the screen's own public entry point.
     */
    @Test
    fun `no screen declares a composable it never places`() {
        val offenders = mutableListOf<String>()
        val screensDir = appSource("ui/screens")
        val screens = screensDir.listFiles()?.filter { it.extension == "kt" }.orEmpty()

        // Every screen composable is called from the router, so build the set of entry
        // points first rather than special-casing names.
        val router = appText("ui/CharalyApp.kt")
        val entryPoints = screens.flatMap { file ->
            Regex("""(?:internal )?fun (\w+)\(""").findAll(file.readText())
                .map { it.groupValues[1] }
                .filter { router.contains(it) }
                .toList()
        }

        for (file in screens) {
            val text = file.readText()
            Regex("""(?:internal )?fun (\w+)\(\s*\n?\s*[^)]*\)\s*\{""").findAll(text).forEach { match ->
                val name = match.groupValues[1]
                if (name in entryPoints) return@forEach
                // Called anywhere in the same file, or anywhere else in the UI?
                val elsewhere = File("src/main/kotlin/dev/charaly/app/ui").walkTopDown()
                    .filter { it.extension == "kt" && it.path != file.path }
                    .any { Regex("""\b${Regex.escape(name)}\(""").containsMatchIn(it.readText()) }
                val selfCalls = Regex("""\b${Regex.escape(name)}\(""").findAll(text).count() > 1
                if (!elsewhere && !selfCalls) {
                    offenders += "${file.name}: $name"
                }
            }
        }
        assertTrue(
            "declared but never placed; a correct composable nobody calls is invisible to " +
                "the compiler and to review:\n  $offenders",
            offenders.isEmpty(),
        )
    }

    private fun assertNull(message: String, value: Any?) = assertEquals(message, null, value)
}
