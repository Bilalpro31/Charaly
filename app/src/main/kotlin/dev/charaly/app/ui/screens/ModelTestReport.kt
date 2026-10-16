package dev.charaly.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.charaly.app.model.ModelTestPhase
import dev.charaly.app.model.ModelTestState
import dev.charaly.app.model.TestFailure
import dev.charaly.app.model.asMillisLabel
import dev.charaly.app.model.asSizeLabel
import dev.charaly.app.ui.ErrorMessages
import dev.charaly.app.ui.components.CharalyAction
import dev.charaly.app.ui.components.CharalyQuietAction
import dev.charaly.app.ui.components.CharalySpinner
import dev.charaly.app.ui.design.Charaly
import dev.charaly.app.ui.design.CharalyShapes
import dev.charaly.runtime.presentation.Loc

/**
 * THE MODEL TEST REPORT.
 *
 * ## Every row here is measured
 *
 * Load time comes from a clock around the real `loadModel`. First-token time from a clock
 * around the first decoded chunk. Token count from the count of chunks the engine emitted.
 * Architecture and context come from the header llama.cpp actually parsed. Backend comes
 * from ggml's own device registry.
 *
 * There is no row that could be filled in without the engine having run, and no test that
 * can pass without text coming back. That is the whole design: a diagnostics screen that
 * says "OK" because it checked a file exists is worse than no diagnostics screen, because it
 * costs the user the one check they needed.
 *
 * ## Why the speed figure is labelled
 *
 * The test decodes 24 tokens from a 6-word prompt. That is enough to prove decoding works
 * and far too little to compare against a story turn. So the figure is shown *and* marked
 * "duration only", rather than being presented next to the benchmark's real tok/s where a
 * reader would reasonably assume they were comparable.
 */
@Composable
fun ModelTestReport(
    phase: ModelTestPhase,
    subjectName: String,
    onRun: () -> Unit,
    /**
     * Opens the model library.
     *
     * A parameter rather than a hardcoded navigation call, because the report is a
     * component and the route belongs to the shell. Defaults to a no-op so previews keep
     * compiling; the shell always supplies it, and [LocalModelTestWiringTest] asserts it
     * does.
     */
    onOpenModels: () -> Unit = {},
    onClear: () -> Unit,
    /**
     * Whether the engine's own internals are printed below the verdict.
     *
     * Off by default and driven by Settings' Developer Mode. The verdict itself - does
     * this model actually produce text on this device - is a *product* answer and belongs
     * to everybody; the ggml backend name, the engine build, and token accounting are the
     * machinery that produced it, and a story reader is not debugging anything. The rows
     * exist, they are simply not dressed as product.
     */
    showInternals: Boolean = false,
) {
    val running = phase.state.isRunning
    // "Can we test anything?" is the question the empty state answers. Decided from the
    // registry - "there is no model" - rather than from whether a particular model happens
    // to be loaded, because a model that is installed and merely still resident-eligible
    // can absolutely be tested.
    val nothingInstalled = subjectName.isBlank()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CharalyShapes.soft)
            .background(Charaly.surface.raised)
            .padding(Charaly.space.md),
        verticalArrangement = Arrangement.spacedBy(Charaly.space.xs),
    ) {
        // ---- the control --------------------------------------------------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = Loc.t("model-test.title"),
                    style = MaterialTheme.typography.titleSmall,
                    color = Charaly.ink.primary,
                )
                Text(
                    text = subjectName.ifBlank { Loc.t("model-test.no_model") },
                    style = MaterialTheme.typography.bodySmall,
                    color = Charaly.ink.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.size(Charaly.space.xs))
            when {
                // A running test replaces the button, rather than offering a button that
                // would start a second load into the same native handle.
                running -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CharalySpinner(color = Charaly.atmosphere.accent)
                    Spacer(Modifier.size(Charaly.space.xs))
                    Text(
                        text = Loc.t(phase.detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = Charaly.atmosphere.accent,
                    )
                }

                // Nothing installed: the useful action is not "Run test" (which could only
                // fail) but the one that makes a test possible. A disabled button here
                // would be a dead control, and this codebase bans those.
                nothingInstalled -> CharalyQuietAction(
                    label = Loc.t("action.choose_model"),
                    onClick = onOpenModels,
                )

                else -> CharalyAction(
                    label = if (phase.state == ModelTestState.IDLE) {
                        Loc.t("model-test.action")
                    } else {
                        Loc.t("action.retry")
                    },
                    onClick = onRun,
                )
            }
        }

        // ---- the result ---------------------------------------------------
        when (phase.state) {
            ModelTestState.IDLE -> Text(
                text = Loc.t("model-test.blurb"),
                style = MaterialTheme.typography.bodySmall,
                color = Charaly.ink.muted,
            )

            ModelTestState.LOADING, ModelTestState.GENERATING -> TestProgress(phase)

            ModelTestState.PASSED -> {
                TestVerdict(
                    headline = Loc.t("model-test.passed"),
                    tone = VerdictTone.GOOD,
                )
                // THE REPORT, as the specification lists it: model, architecture, file size,
                // status, load time, first token, tokens/sec, backend.
                //
                // Every one of these eight is on the product surface, not behind Developer
                // Mode. A user whose model is slow or unstable needs "Load time" and "First
                // token" exactly as much as a developer does - they are the difference
                // between "this model is broken" and "this model is big".
                //
                // A figure that was not measurable says "Not measured". It is never
                // synthesised, and never quietly replaced with a zero: "0 tok/s" reads as a
                // measurement of a broken model where "-" is the honest "we did not measure".
                val measured = phase.tokens > 0 && phase.decodeMillis > 0L
                phase.info?.let { info ->
                    ReportRow(Loc.t("model-test.model"), info.displayName.ifBlank { subjectName })
                    ReportRow(Loc.t("model-test.architecture"), measuredOrNot(info.metadata["general.architecture"]))
                    ReportRow(Loc.t("model-test.size"), measuredOrNot(info.parameterSizeBytes.takeIf { it > 0 }?.asSizeLabel()))
                }
                ReportRow(Loc.t("model-test.status"), Loc.t("model-test.passed"))
                ReportRow(Loc.t("model-test.load_time"), measuredOrNot(phase.loadMillis.takeIf { it > 0L }?.asMillisLabel()))
                ReportRow(Loc.t("model-test.first_token"), measuredOrNot(phase.firstTokenMillis.takeIf { it > 0L }?.asMillisLabel()))
                ReportRow(Loc.t("model-test.speed"), measuredOrNot(phase.tokensPerSecondLabel.takeIf { it.isNotBlank() }))
                // Backend comes from ggml's own device registry. "unknown" means ggml did
                // not answer, and that is reported as not measured rather than as CPU.
                ReportRow(Loc.t("model-test.backend"), measuredOrNot(phase.backend.takeIf { it.isNotBlank() && it != "unknown" }))
                if (showInternals) {
                    phase.info?.let { info ->
                        ReportRow(Loc.t("models.context"), info.contextSize.toString())
                    }
                    ReportRow(Loc.t("model-test.engine"), phase.version.ifBlank { Loc.t("models.not_measured") })
                    ReportRow(Loc.t("model-test.tokens"), phase.tokens.toString())
                }
                Text(
                    text = Loc.t("model-test.duration_only"),
                    style = MaterialTheme.typography.bodySmall,
                    color = Charaly.ink.muted,
                )

                // The proof. Rendered as a quote because it is the model's own words, not
                // Charaly's description of them.
                Spacer(Modifier.height(Charaly.space.xs))
                Text(
                    text = Loc.t("model-test.output"),
                    style = MaterialTheme.typography.labelMedium,
                    color = Charaly.ink.muted,
                )
                Text(
                    text = phase.output,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Charaly.ink.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CharalyShapes.soft)
                        .background(Charaly.surface.base)
                        .padding(Charaly.space.sm)
                        .semantics {
                            contentDescription = Loc.t("model-test.output") + ": " + phase.output
                        },
                )
            }

            ModelTestState.FAILED -> {
                TestVerdict(
                    headline = Loc.t("model-test.failed"),
                    tone = VerdictTone.BAD,
                )
                Text(
                    text = failureSentence(phase.failure),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Charaly.ink.secondary,
                )
                // The identity of what was tested still matters on a failure: "the file is
                // unsupported" and "the file is missing" are different problems.
                // The full report on a failure too - and this is where "Not measured" earns
                // its place. A model that never loaded has no first token and no tokens/sec,
                // and printing dashes there used to read like "zero", which is a claim about
                // the model rather than about the test.
                phase.info?.let { info ->
                    ReportRow(Loc.t("model-test.model"), info.displayName.ifBlank { subjectName })
                    ReportRow(Loc.t("model-test.architecture"), measuredOrNot(info.metadata["general.architecture"]))
                    ReportRow(Loc.t("model-test.size"), measuredOrNot(info.parameterSizeBytes.takeIf { it > 0 }?.asSizeLabel()))
                }
                ReportRow(Loc.t("model-test.status"), Loc.t("model-test.failed"))
                ReportRow(Loc.t("model-test.load_time"), measuredOrNot(phase.loadMillis.takeIf { it > 0L }?.asMillisLabel()))
                ReportRow(Loc.t("model-test.first_token"), Loc.t("models.not_measured"))
                ReportRow(Loc.t("model-test.speed"), Loc.t("models.not_measured"))
                ReportRow(Loc.t("model-test.backend"), measuredOrNot(phase.backend.takeIf { it.isNotBlank() && it != "unknown" }))
            }
        }

        // A finished test is a fact about the past, so it is dismissable - but a running
        // one is not, or the row would vanish mid-load with no way back.
        if (phase.state != ModelTestState.IDLE && !running) {
            CharalyQuietAction(
                label = Loc.t("action.dismiss"),
                onClick = onClear,
            )
        }
    }
}

@Composable
private fun TestProgress(phase: ModelTestPhase) {
    Text(
        text = Loc.t(phase.detail),
        style = MaterialTheme.typography.bodyMedium,
        color = Charaly.ink.secondary,
    )
    if (phase.loadMillis > 0L) {
        ReportRow(Loc.t("model-test.load_time"), phase.loadMillis.asMillisLabel())
    }
    phase.info?.let { info ->
        ReportRow(Loc.t("model-test.model"), info.displayName.ifBlank { "—" })
        ReportRow(Loc.t("model-test.architecture"), info.metadata["general.architecture"] ?: "-")
    }
}

/**
 * A failed test's reason, as a sentence the user can act on.
 *
 * An `InferenceError` goes through the same mapper the chat screen uses, so "not enough
 * memory" reads the same here as it does in a story. Anything else is a short detail string
 * from the engine, which is not shown raw unless it is already a sentence - a native
 * exception's `message` is not.
 */
private fun failureSentence(failure: TestFailure?): String = when (failure) {
    null -> Loc.t("error.unexpected")
    is TestFailure.Of -> ErrorMessages.of(failure.error)
    is TestFailure.Other -> Loc.t("error.generation_failed")
}

/**
 * The one spelling of "we did not measure this".
 *
 * A bare "-" is ambiguous - it reads as "none", which for "tokens/sec" is a claim the model
 * is broken. "Not measured" says what is actually true: the figure does not exist.
 */
@Composable
private fun measuredOrNot(value: String?): String =
    value?.takeIf { it.isNotBlank() && it != "-" } ?: Loc.t("models.not_measured")

private enum class VerdictTone { GOOD, BAD }

@Composable
private fun TestVerdict(headline: String, tone: VerdictTone) {
    Text(
        text = headline,
        style = MaterialTheme.typography.titleSmall,
        color = if (tone == VerdictTone.GOOD) Charaly.ink.primary else Charaly.atmosphere.accent,
    )
}

@Composable
private fun ReportRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label: $value" },
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Charaly.ink.muted,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = Charaly.ink.secondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}