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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.charaly.runtime.presentation.Loc
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.charaly.app.ui.components.CharalyAction
import dev.charaly.app.ui.components.CharalyEmptyState
import dev.charaly.app.ui.components.CharalyIconButton
import dev.charaly.app.ui.components.CharalyPill
import dev.charaly.app.ui.components.CharalySpinner
import dev.charaly.app.ui.design.Charaly
import dev.charaly.app.ui.design.CharalyShapes
import dev.charaly.runtime.presentation.CharacterImportSnapshot
import dev.charaly.runtime.presentation.CharacterImportStep
import dev.charaly.runtime.presentation.EmptyState

/**
 * IMPORT A CHARACTER.
 *
 * ```
 *   IDLE  -> READING -> PREVIEW -> IMPORTED
 *              |          |
 *              +----------+--> FAILED
 * ```
 *
 * ## Why the preview is a whole screen
 *
 * Because the decision is not small. A card carries a description, a personality, a
 * scenario, a greeting, example dialogue and lore entries, and asking someone to consent to
 * storing all of that while showing them a name would be consent without disclosure. So
 * every field is shown, including the ones that are empty, marked as empty rather than
 * hidden - a user who cannot see that a card has no scenario cannot tell whether they are
 * looking at a thin card or a broken reader.
 *
 * ## Nothing is written until Confirm
 *
 * The screen has no access to the library. Confirming is the call that writes, and
 * cancelling writes nothing - not "writes then undoes", which is how a cancel button ends
 * up leaving an orphan behind. The runtime enforces this too: the only commit path is
 * `commitImportedCharacter`.
 *
 * ## Failures are sentences
 *
 * "Could not read character card", with one line of detail. Never a `Result` message,
 * never a `FileNotFoundException` class name. A user cannot repair a malformed PNG, so the
 * useful response is to say so and offer the next action.
 */
@Composable
fun CharacterImportScreen(
    snapshot: CharacterImportSnapshot,
    onBack: () -> Unit,
    onPick: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
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
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CharalyIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = Loc.t("import.back"),
                    onClick = onBack,
                )
                Spacer(Modifier.width(Charaly.space.xs))
                Text(
                    text = snapshot.title,
                    style = MaterialTheme.typography.headlineLarge,
                    color = Charaly.ink.primary,
                    modifier = Modifier.semantics { heading() },
                )
            }
        }

        when (snapshot.step) {
            CharacterImportStep.IDLE -> item(key = "idle") {
                CharalyEmptyState(
                    state = EmptyState(
                        title = Loc.t("import.intro_title"),
                        body = Loc.t("import.intro_body"),
                        actionLabel = Loc.t("import.choose_file"),
                        artSeed = "charaly-import-idle",
                    ),
                    action = { CharalyAction(label = Loc.t("import.choose_file"), onClick = onPick) },
                )
            }

            CharacterImportStep.READING -> item(key = "reading") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CharalyShapes.soft)
                        .background(Charaly.surface.raised)
                        .padding(Charaly.space.md),
                    verticalArrangement = Arrangement.spacedBy(Charaly.space.sm),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Charaly.space.sm),
                    ) {
                        CharalySpinner()
                        Text(
                            // The only spinner in the app that is not tied to a percentage.
                            // It is honest here because there is genuinely nothing to report:
                            // parsing a few kilobytes of JSON has no intermediate steps.
                            text = snapshot.progressLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Charaly.ink.secondary,
                        )
                    }
                    Text(
                        text = snapshot.sourceName,
                        style = MaterialTheme.typography.bodySmall,
                        color = Charaly.ink.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            CharacterImportStep.FAILED -> item(key = "failed") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CharalyShapes.soft)
                        .background(Charaly.surface.raised)
                        .padding(Charaly.space.md),
                    verticalArrangement = Arrangement.spacedBy(Charaly.space.sm),
                ) {
                    Text(
                        text = snapshot.error?.detail.orEmpty().ifBlank {
                            "Bu dosya bir SillyTavern karakter kartı değil."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = Charaly.ink.primary,
                    )
                    if (snapshot.sourceName.isNotBlank()) {
                        Text(
                            text = snapshot.sourceName,
                            style = MaterialTheme.typography.bodySmall,
                            color = Charaly.ink.muted,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Charaly.space.xs)) {
                        CharalyAction(label = Loc.t("import.choose_another"), onClick = onPick)
                        CharalyAction(label = Loc.t("import.close"), onClick = onCancel)
                    }
                }
            }

            CharacterImportStep.PREVIEW -> {
                val card = snapshot.preview
                if (card == null) {
                    item(key = "preview-missing") {
                        CharalyEmptyState(
                            state = EmptyState(
                                title = Loc.t("import.preview_empty_title"),
                                body = Loc.t("import.preview_empty_body"),
                                actionLabel = Loc.t("import.choose_another"),
                                artSeed = "charaly-import-missing",
                            ),
                            action = { CharalyAction(label = Loc.t("import.choose_another"), onClick = onPick) },
                        )
                    }
                } else {
                    item(key = "preview-source") {
                        Text(
                            text = snapshot.sourceName,
                            style = MaterialTheme.typography.bodySmall,
                            color = Charaly.ink.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    item(key = "preview-name") {
                        Text(
                            text = card.name,
                            style = MaterialTheme.typography.titleLarge,
                            color = Charaly.ink.primary,
                        )
                    }

                    item(key = "preview-summary") {
                        Text(
                            text = card.summaryLine(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Charaly.ink.secondary,
                        )
                    }

                    if (card.tags.isNotEmpty()) {
                        item(key = "preview-tags") {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(Charaly.space.xxs)) {
                                items(card.tags) { tag -> CharalyPill(label = tag) }
                            }
                        }
                    }

                    // Every field, with an explicit "not in this card" when empty. A blank
                    // space and a missing value look identical, and only one of them tells
                    // the user something.
                    item(key = "preview-description") {
                        CardField(title = Loc.t("import.field_description"), body = card.description)
                    }
                    item(key = "preview-personality") {
                        CardField(title = Loc.t("import.field_personality"), body = card.personality)
                    }
                    item(key = "preview-scenario") {
                        CardField(title = Loc.t("import.field_scenario"), body = card.scenario)
                    }
                    item(key = "preview-greeting") {
                        CardField(title = Loc.t("import.field_greeting"), body = card.greeting, italic = true)
                    }

                    if (card.exampleDialogue.isNotEmpty()) {
                        item(key = "preview-examples") {
                            CardField(
                                title = Loc.t("import.field_examples"),
                                body = card.exampleDialogue.joinToString("\n\n") { it.trim() },
                            )
                        }
                    }

                    item(key = "preview-lore") {
                        CardField(
                            title = Loc.t("import.field_lore"),
                            body = if (card.loreCount == 0) {
                                ""
                            } else {
                                "${card.loreCount} " +
                                    (if (card.loreCount == 1) "entry" else "entries") +
                                    " imported with this character."
                            },
                            emptyText = "Bu kartın dünya bilgisi yok.",
                        )
                    }

                    if (card.creator.isNotBlank() || card.characterVersion.isNotBlank()) {
                        item(key = "preview-credits") {
                            CardField(
                                title = Loc.t("import.field_from"),
                                body = listOfNotNull(
                                    card.creator.takeIf { it.isNotBlank() },
                                    card.characterVersion.takeIf { it.isNotBlank() }
                                        ?.let { "version $it" },
                                ).joinToString(" · "),
                            )
                        }
                    }

                    if (card.creatorNotes.isNotBlank()) {
                        item(key = "preview-notes") {
                            CardField(title = Loc.t("import.field_notes"), body = card.creatorNotes)
                        }
                    }

                    item(key = "preview-actions") {
                        Row(horizontalArrangement = Arrangement.spacedBy(Charaly.space.xs)) {
                            CharalyAction(label = Loc.t("import.confirm"), onClick = onConfirm)
                            CharalyAction(label = Loc.t("import.cancel"), onClick = onCancel)
                        }
                    }
                }
            }

            CharacterImportStep.IMPORTED -> item(key = "imported") {
                CharalyEmptyState(
                    state = EmptyState(
                        title = Loc.t("import.done_title"),
                        body = Loc.t("import.done_body", snapshot.preview?.name.orEmpty()),
                        actionLabel = Loc.t("import.done"),
                        artSeed = "charaly-import-done",
                    ),
                    action = { CharalyAction(label = Loc.t("import.done"), onClick = onDone) },
                )
            }
        }
    }
}

/**
 * One labelled field.
 *
 * [emptyText] replaces [body] when the card carried nothing, so the row stays visible and
 * says why it is blank.
 */
@Composable
private fun CardField(
    title: String,
    body: String,
    italic: Boolean = false,
    emptyText: String = "Not in this card.",
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CharalyShapes.soft)
            .background(Charaly.surface.base)
            .padding(Charaly.space.md),
        verticalArrangement = Arrangement.spacedBy(Charaly.space.xxs),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = Charaly.ink.muted,
        )
        Text(
            text = body.ifBlank { emptyText },
            style = if (italic) {
                MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic)
            } else {
                MaterialTheme.typography.bodyMedium
            },
            // A blank field is not an error, so it reads muted rather than primary.
            color = if (body.isBlank()) Charaly.ink.muted else Charaly.ink.primary,
        )
    }
}