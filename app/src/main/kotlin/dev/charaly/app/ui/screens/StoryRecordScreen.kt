package dev.charaly.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.charaly.app.ui.components.CharalyAction
import dev.charaly.app.ui.components.CharalyIconButton
import dev.charaly.app.ui.components.CharalyQuietAction
import dev.charaly.app.ui.components.CharalySectionHeader
import dev.charaly.app.ui.design.Charaly
import dev.charaly.app.ui.design.CharalyShapes
import dev.charaly.runtime.presentation.Beat
import dev.charaly.runtime.presentation.BeatRole
import dev.charaly.runtime.presentation.ChapterCard
import dev.charaly.runtime.presentation.Loc

/**
 * One story, in full.
 *
 * The transcript as prose, with the world moments interleaved. There is no composer here:
 * this is a story you are *reading*, not one you are playing, and offering a text field
 * would blur that distinction.
 */
@Composable
fun StoryRecordScreen(
    title: String,
    moment: String,
    contextLine: String,
    beats: List<Beat>,
    chapters: List<ChapterCard>,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onDelete: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Charaly.space.gutter,
            end = Charaly.space.gutter,
            top = Charaly.space.xxl,
            bottom = Charaly.space.section,
        ),
        verticalArrangement = Arrangement.spacedBy(Charaly.space.md),
    ) {
        item(key = "back") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CharalyIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = Loc.t("a11y.back_to_library"),
                    onClick = onBack,
                )
                Spacer(Modifier.height(Charaly.space.xs))
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Charaly.ink.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
            }
        }

        item(key = "summary") {
            Column {
                Text(
                    text = "“$moment”",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Charaly.ink.secondary,
                )
                if (contextLine.isNotBlank()) {
                    Text(
                        text = contextLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = Charaly.ink.muted,
                    )
                }
            }
        }

        item(key = "actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(Charaly.space.xs)) {
                CharalyAction(
                    label = Loc.t("action.continue"),
                    onClick = onContinue,
                    icon = Icons.Filled.PlayArrow,
                )
                CharalyQuietAction(
                    label = Loc.t("library.delete"),
                    onClick = onDelete,
                    contentColor = Charaly.ink.muted,
                )
            }
        }

        if (chapters.isNotEmpty()) {
            item(key = "chapters-header") {
                CharalySectionHeader(
                    title = Loc.t("library.chapters"),
                    micro = true,
                    caption = if (chapters.size == 1) "1 chapter" else "${chapters.size} chapters",
                )
            }
            chapters.forEach { chapter ->
                item(key = "chapter-${chapter.index}") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CharalyShapes.soft)
                            .background(if (chapter.isCurrent) Charaly.surface.raised else Charaly.surface.base)
                            .padding(Charaly.space.md),
                    ) {
                        Text(
                            text = chapter.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = Charaly.ink.primary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (chapter.summary.isNotBlank()) {
                            Text(
                                text = chapter.summary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Charaly.ink.secondary,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = listOfNotNull(
                                chapter.locationName.takeIf { it.isNotBlank() },
                                chapter.timeLabel.takeIf { it.isNotBlank() },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = Charaly.ink.muted,
                        )
                    }
                }
            }
        }

        if (beats.isNotEmpty()) {
            item(key = "transcript-header") {
                CharalySectionHeader(title = Loc.t("library.what_happened"), micro = true)
            }
            beats.forEach { beat ->
                item(key = "beat-${beat.id}") {
                    val text = beat.dialogue.ifBlank { beat.narration }.ifBlank { beat.action }
                    if (text.isNotBlank()) {
                        Text(
                            text = if (beat.dialogue.isNotBlank()) "“$text”" else text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (beat.role == BeatRole.PLAYER) {
                                Charaly.ink.muted
                            } else {
                                Charaly.ink.prose
                            },
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                }
            }
        }
    }
}
