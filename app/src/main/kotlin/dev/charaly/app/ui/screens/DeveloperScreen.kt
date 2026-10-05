package dev.charaly.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.charaly.app.ui.components.CharalyCard
import dev.charaly.app.ui.components.CharalyFilterChip
import dev.charaly.app.ui.components.CharalyIconButton
import dev.charaly.app.ui.components.CharalyTextButton
import dev.charaly.app.ui.components.DetailRow
import dev.charaly.app.ui.components.Eyebrow
import dev.charaly.app.ui.components.SectionHeader
import dev.charaly.app.ui.theme.Charaly
import dev.charaly.app.ui.theme.CharalyTypography

/**
 * The developer panel.
 *
 * Unreachable unless the user turns Developer Mode on in Settings. When it is on,
 * this is genuinely useful rather than decorative: it is the only place in the app
 * where you can see what the deterministic engine actually holds, what the model is
 * actually being asked, and what has actually been applied.
 *
 * The design rule here is different from the rest of the app on purpose. Raw output
 * belongs in monospace, unstyled and unabridged, because this screen exists to be
 * trustworthy rather than pretty.
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
) {
    var selectedStory by remember { mutableStateOf(stories.firstOrNull().orEmpty()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Charaly.tokens.spacing.gutter,
            end = Charaly.tokens.spacing.gutter,
            top = Charaly.tokens.spacing.xl,
            bottom = Charaly.tokens.spacing.section,
        ),
        verticalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.sm),
    ) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CharalyIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    onClick = onBack,
                )
                Column(Modifier.padding(start = 4.dp)) {
                    Eyebrow("Developer mode")
                    Text(
                        text = "Diagnostics",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        item(key = "notice") {
            CharalyCard(container = MaterialTheme.colorScheme.surfaceContainer) {
                Text(
                    text = "This panel is for development. Everything below is the runtime's own " +
                        "output, unedited.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (stories.size > 1) {
            item(key = "stories") {
                Column {
                    Eyebrow("Story")
                    LazyRow(
                        modifier = Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(stories.size, key = { stories[it] }) { index ->
                            CharalyFilterChip(
                                label = stories[index],
                                selected = stories[index] == selectedStory,
                                onClick = { selectedStory = stories[index] },
                            )
                        }
                    }
                }
            }
        }

        item(key = "identity") {
            CharalyCard(container = MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    Eyebrow("Instance")
                    DetailRow("StoryInstance id", instanceId.ifBlank { "none open" })
                    DetailRow("Prompt size", "$promptEstimate characters (estimate)")
                    DetailRow("Story health", storyHealthStatus)
                }
            }
        }

        item(key = "model") {
            CharalyCard(container = MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    Eyebrow("Model")
                    Monospace(modelDiagnostics)
                }
            }
        }

        item(key = "eventlog") {
            CharalyCard(container = MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    Eyebrow("Event log")
                    Monospace(eventLog.ifBlank { "no events applied yet" })
                }
            }
        }

        item(key = "worldstate") {
            CharalyCard(container = MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    Eyebrow("WorldState")
                    Monospace(worldState.ifBlank { "open a story to inspect its world" })
                }
            }
        }

        item(key = "threads") {
            CharalyCard(container = MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    Eyebrow("Story threads")
                    Text(
                        text = "Each thread with its priority, progress, what it needs next and what " +
                            "would finish it. A thread that cannot be finished is a thread nobody " +
                            "ever will.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Monospace(threads, maxLines = 60)
                }
            }
        }

        item(key = "minds") {
            CharalyCard(container = MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    Eyebrow("Character minds")
                    Text(
                        text = "What each character has seen, concluded, cannot dismiss, and is " +
                            "wrong about. A character with no mind cannot be anything but a " +
                            "list of facts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Monospace(minds, maxLines = 60)
                }
            }
        }

        item(key = "commitments") {
            CharalyCard(container = MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    Eyebrow("Commitments")
                    Text(
                        text = "Promises, goals and consequences. Kept separately from memory " +
                            "because they have to survive being forgotten.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Monospace(commitments)
                }
            }
        }

        item(key = "causality") {
            CharalyCard(container = MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    Eyebrow("Causal graph")
                    Text(
                        text = "Why each event happened, as recorded by the engine rather than " +
                            "narrated by the model.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Monospace(causality, maxLines = 60)
                }
            }
        }

        item(key = "health") {
            CharalyCard(container = MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    Eyebrow("Story health")
                    Text(
                        text = "Consistency findings. Never shown outside developer mode: " +
                            "being told your story is broken is not something a player can act on.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Monospace(storyHealth)
                }
            }
        }

        item(key = "sections") {
            CharalyCard(container = MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    Eyebrow("Context budget")
                    Text(
                        text = "Which sections survived budgeting, and what was cut. A dropped " +
                            "section is invisible in the assembled prompt, which is exactly why " +
                            "it is worth being able to see.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Monospace(contextSections)
                }
            }
        }

        item(key = "context") {
            CharalyCard(container = MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    Eyebrow("ContextBuilder output")
                    Text(
                        text = "Exactly what would be sent as the system prompt for the next turn.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Monospace(contextPreview)
                }
            }
        }
    }
}

/**
 * Raw text, in monospace, scrollable vertically but capped in height.
 *
 * A developer panel that pushes everything else off the screen is a developer panel
 * nobody can use.
 */
@Composable
private fun Monospace(text: String, maxLines: Int = 40) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(Charaly.tokens.radii.shapeSm)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(Charaly.tokens.spacing.sm),
    ) {
        Column(
            Modifier.heightIn(max = (maxLines * 17).dp),
        ) {
            Text(
                text = text,
                style = CharalyTypography.technical,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
