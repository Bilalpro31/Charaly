package dev.charaly.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.charaly.app.ui.components.CharalyAction
import dev.charaly.app.ui.components.CharalyEmptyState
import dev.charaly.app.ui.components.CharalyIconButton
import dev.charaly.app.ui.components.CharalySectionHeader
import dev.charaly.app.ui.design.Charaly
import dev.charaly.app.ui.design.CharalyShapes

/**
 * THE ENGINE'S OWN VIEW.
 *
 * ## Developer Mode only, and that is a gate rather than a convention
 *
 * This screen shows world state as JSON, the assembled prompt, section budgets, the event
 * log, the causal graph and the story health report. All of it is genuinely useful - for
 * pack authoring, for debugging a world that behaved strangely, for understanding *why*
 * something happened - and all of it is noise to somebody who wants to be in a story.
 *
 * So it is unreachable unless someone turns Developer Mode on, and the route is re-checked
 * at render time rather than only where the button is hidden. Navigation state is saved: a
 * user can open this panel, turn the setting off, and restart - and without the render-time
 * check the panel would come back for someone who had explicitly turned it off.
 *
 * ## Monospace, and nothing else
 *
 * Every value here is either prose or a dump. Nothing is a control, because nothing here
 * can be changed from here: the world is authoritative and this is a window onto it.
 */
@Composable
fun DeveloperScreen(
    onBack: () -> Unit,
    worldState: String,
    contextPreview: String,
    contextSections: String,
    promptEstimate: Int,
    modelDiagnostics: String,
    eventLog: String,
    storyHealth: String,
    storyHealthStatus: String,
    causality: String,
    threads: String,
    minds: String,
    commitments: String,
    stories: List<String>,
    instanceId: String,
    /**
     * "18:42 · Day 3", read from the world clock. Shown because a developer panel is the
     * right place to be able to *see* that the clock moved.
     */
    clockLine: String = "",
    /** How many story minutes a plain turn costs, from the runtime's own policy. */
    turnCostLabel: String = "",
    /**
     * Steps the clock forward, for testing a routine or a scheduled consequence without
     * having to hold a conversation long enough to reach that hour.
     *
     * This goes through the same `CharalyRuntime.advance` a turn uses, so it exercises the
     * real event engine rather than setting a field. It is a debug control and stays
     * behind Developer Mode.
     */
    onAdvanceTime: (Long) -> Unit = {},
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Charaly.surface.void),
        contentPadding = PaddingValues(
            start = Charaly.space.gutter,
            end = Charaly.space.gutter,
            top = Charaly.space.xxl,
            bottom = Charaly.space.section,
        ),
        verticalArrangement = Arrangement.spacedBy(Charaly.space.md),
    ) {
        item(key = "back") {
            Row {
                CharalyIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    onClick = onBack,
                )
                Spacer(Modifier.width(Charaly.space.xs))
                Text(
                    text = "Developer",
                    style = MaterialTheme.typography.headlineLarge,
                    color = Charaly.ink.primary,
                    modifier = Modifier.semantics { heading() },
                )
            }
        }

        item(key = "status") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CharalyShapes.soft)
                    .background(Charaly.surface.raised)
                    .padding(Charaly.space.md),
            ) {
                Text(
                    text = "Open story: ${instanceId.ifBlank { "none" }}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Charaly.ink.secondary,
                )
                Text(
                    text = "Stories on this device: ${stories.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Charaly.ink.secondary,
                )
                Text(
                    text = "Prompt estimate: $promptEstimate characters",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Charaly.ink.secondary,
                )
                Text(
                    text = "Story health: $storyHealthStatus",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Charaly.ink.secondary,
                )
            }
        }

        item(key = "clock") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CharalyShapes.soft)
                    .background(Charaly.surface.raised)
                    .padding(Charaly.space.md),
            ) {
                Text(
                    text = "World clock",
                    style = MaterialTheme.typography.titleSmall,
                    color = Charaly.ink.primary,
                )
                Text(
                    text = clockLine.ifBlank { "No story open." },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Charaly.ink.secondary,
                )
                Text(
                    // The cost a plain turn charges, so it is visible that the clock moves
                    // on its own rather than only when a developer presses something.
                    text = turnCostLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = Charaly.ink.muted,
                )
                Spacer(Modifier.height(Charaly.space.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(Charaly.space.xs)) {
                    listOf(15L, 60L, 240L).forEach { minutes ->
                        CharalyAction(
                            label = if (minutes < 60) "+${minutes}m" else "+${minutes / 60}h",
                            onClick = { onAdvanceTime(minutes) },
                        )
                    }
                }
            }
        }

        item(key = "model-header") { CharalySectionHeader(title = "Model", micro = true) }
        item(key = "model") { Dump(modelDiagnostics) }

        item(key = "health-header") { CharalySectionHeader(title = "Story health", micro = true) }
        item(key = "health") { Dump(storyHealth) }

        item(key = "threads-header") { CharalySectionHeader(title = "Story threads", micro = true) }
        item(key = "threads") { Dump(threads) }

        item(key = "commitments-header") { CharalySectionHeader(title = "Commitments", micro = true) }
        item(key = "commitments") { Dump(commitments) }

        item(key = "minds-header") { CharalySectionHeader(title = "Character minds", micro = true) }
        item(key = "minds") { Dump(minds) }

        item(key = "causality-header") { CharalySectionHeader(title = "Causality", micro = true) }
        item(key = "causality") { Dump(causality) }

        item(key = "events-header") { CharalySectionHeader(title = "Event log", micro = true) }
        item(key = "events") { Dump(eventLog) }

        item(key = "budget-header") { CharalySectionHeader(title = "Context budget", micro = true) }
        item(key = "budget") { Dump(contextSections) }

        item(key = "prompt-header") { CharalySectionHeader(title = "Assembled prompt", micro = true) }
        item(key = "prompt") { Dump(contextPreview, long = true) }

        item(key = "state-header") { CharalySectionHeader(title = "World state", micro = true) }
        item(key = "state") { Dump(worldState, long = true) }
    }
}

/**
 * One dump.
 *
 * Monospace, horizontally scrollable, and capped in height with its own vertical scroll -
 * so a four-thousand-line JSON document cannot push every other section off the screen. A
 * developer panel that only shows the first thing in it is not a panel.
 */
@Composable
private fun Dump(text: String, long: Boolean = false) {
    Text(
        text = text.ifBlank { "Nothing recorded yet." },
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = Charaly.ink.secondary,
        modifier = Modifier
            .fillMaxWidth()
            .clip(CharalyShapes.soft)
            .background(Charaly.surface.base)
            .padding(Charaly.space.sm)
            .then(
                if (long) {
                    Modifier.height(320.dp)
                } else {
                    Modifier
                },
            )
            .horizontalScroll(rememberScrollState()),
    )
}

/** Shown when the route is restored but Developer Mode is now off. */
@Composable
fun DeveloperLockedScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Charaly.surface.void)
            .padding(Charaly.space.gutter),
    ) {
        CharalyIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            onClick = onBack,
        )
        CharalyEmptyState(
            state = dev.charaly.runtime.presentation.EmptyState(
                title = "Developer mode is off.",
                body = "Turn it on in Settings · Advanced to look at the engine's own view.",
                artSeed = "charaly-empty-developer",
            ),
            action = { CharalyAction(label = "Back", onClick = onBack) },
        )
    }
}
