package dev.charaly.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE MODEL TEST IS WIRED TO THE REAL ENGINE.
 *
 * ## Why this file is source-level
 *
 * `ModelTestRunnerTest` proves the *sequence* is right using a scripted engine. It cannot
 * prove production passes the real one - that is a wiring claim about a lambda in a
 * view model, and the only thing that can check it is reading the wiring.
 *
 * So these are source assertions, which is a weaker kind of test and says so. They exist
 * because the specific failure they guard against is invisible at runtime: a test that
 * passes against a stand-in engine while production quietly hands it
 * `FallbackEchoEngine` would report "the model works" for a device with no native
 * inference at all.
 *
 * Each assertion below names a failure mode rather than a string.
 */
class LocalModelTestWiringTest {

    private fun appSource(relativePath: String): File =
        File("src/main/kotlin/dev/charaly/app/$relativePath")

    private val viewModel = appSource("ui/CharalyViewModel.kt").readText()

    /** The runner must be handed the engine the story runtime uses. */
    @Test
    fun `the test runs on the same engine the story uses`() {
        assertTrue(
            "the model test must not build its own engine - a second llama.cpp context " +
                "would compete for RAM with the story's and measure the wrong thing",
            viewModel.contains("engineProvider = {\n                (inferenceEngine as? LocalLlamaInferenceEngine) ?: LocalLlamaInferenceEngine()\n            }"),
        )
        assertTrue(
            "the runner must be built through modelTestRunner()",
            viewModel.contains("private fun modelTestRunner(): dev.charaly.app.model.ModelTestRunner"),
        )
    }

    /** It must never be wired to the echo engine. */
    @Test
    fun `the test is never wired to the fallback engine`() {
        val runner = appSource("model/ModelTestReport.kt").readText()
        assertFalse(
            "the model test must not reference the offline echo engine",
            runner.contains("FallbackEchoEngine"),
        )
        assertFalse(
            "the model test must not reference the mock engine",
            runner.contains("MockInferenceEngine"),
        )
    }

    /** The settings screen must actually expose the control. */
    @Test
    fun `settings offers the test without developer mode`() {
        val settings = appSource("ui/screens/SettingsScreen.kt").readText()
        assertTrue(
            "the model test must be reachable from Settings",
            settings.contains("ModelTestReport("),
        )
        // And the section it lives in is its own, not inside the developer gate.
        assertTrue(
            "the test must not be gated behind Developer Mode",
            settings.contains("SettingsSection.DIAGNOSTICS"),
        )
        assertTrue(
            "the test screen must not live in the developer-only block",
            !settings.substringAfter("item(key = \"diagnostics\")")
                .substringBefore("item(key = \"advanced\")")
                .contains("if (developerMode)"),
        )
    }

    /** The shell must pass real callbacks, not empty lambdas. */
    @Test
    fun `the shell wires the test to the view model`() {
        val app = appSource("ui/CharalyApp.kt").readText()
        assertTrue(
            "the run control must reach the view model",
            app.contains("onRunModelTest = viewModel::runModelTest"),
        )
        assertTrue(
            "the dismiss control must reach the view model",
            app.contains("onClearModelTest = viewModel::clearModelTest"),
        )
        assertTrue(
            "the screen must be told which model it will test",
            app.contains("modelTestSubjectName = viewModel.modelTestSubject()?.displayName.orEmpty()"),
        )
    }

    /** A passing result must require text. */
    @Test
    fun `a pass requires real generated text`() {
        val runner = appSource("model/ModelTestReport.kt").readText()
        assertTrue(
            "the runner must fail on an empty completion",
            runner.contains("the model produced no text"),
        )
        assertTrue(
            "the runner must trim and then check the output",
            runner.contains("val output = text.toString().trim()"),
        )
    }

    /** The brief's prompt, verbatim, so the test is the one that was asked for. */
    @Test
    fun `the test prompt is the one the brief specifies`() {
        val runner = appSource("model/ModelTestReport.kt").readText()
        assertTrue(
            "the test prompt must be 'Say hello in one short sentence.'",
            runner.contains("Say hello in one short sentence."),
        )
    }

    /** Every user-visible string in the report must be localised. */
    @Test
    fun `the report has no hardcoded english sentences`() {
        val report = appSource("ui/screens/ModelTestReport.kt").readText()
        // No bare string literal carrying a full sentence. Labels go through Loc; the only
        // permitted literals are separators, units and a status label, which are formatted
        // numbers rather than prose.
        val proseLiterals = Regex("""Loc\.t\("([^"]+)"\)""")
            .findAll(report)
            .map { it.groupValues[1] }
            .filter { !it.startsWith("models.") && !it.startsWith("model-test.") && !it.startsWith("action.") && !it.startsWith("error.") }
            .toList()
        assertTrue("every label must come from the catalogue, found: $proseLiterals", proseLiterals.isEmpty())
    }
}