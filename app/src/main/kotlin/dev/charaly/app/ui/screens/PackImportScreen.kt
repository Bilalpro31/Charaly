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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.charaly.app.ui.components.CharalyAction
import dev.charaly.app.ui.components.CharalyEmptyState
import dev.charaly.app.ui.components.CharalyIconButton
import dev.charaly.app.ui.components.CharalyPill
import dev.charaly.app.ui.components.CharalyQuietAction
import dev.charaly.app.ui.components.CharalySectionHeader
import dev.charaly.app.ui.components.CharalySkeleton
import dev.charaly.app.ui.components.CharalySpinner
import dev.charaly.app.ui.design.Charaly
import dev.charaly.app.ui.design.CharalyShapes
import dev.charaly.app.ui.packstore.IssueRow
import dev.charaly.app.ui.packstore.PackImportSession
import dev.charaly.runtime.presentation.EmptyState

/**
 * THE IMPORT REVIEW.
 *
 * ## The gap between reading and writing
 *
 * A `.charaly-pack` was picked; nothing has been installed. This screen is the gap - what
 * the archive holds, everything wrong with it, and one deliberate decision. Backing out
 * here mutates nothing, and "Yükle" is disabled the moment anything CRITICAL was found,
 * because a critical finding is the validator saying "this will silently misbehave in
 * play". Showing the button grey rather than hiding it is deliberate: the reason is on the
 * same screen, and a missing button teaches nothing.
 *
 * ## What a player is shown about a finding
 *
 * The finding's own Turkish sentence and its severity. Nothing else. The ids, the paths and
 * the digests behind a problem are developer data; `PackImportReportUi` has no field for
 * them, so there is no route from a validator detail to a player's eyes.
 *
 * ## After the install
 *
 * The success summary shows for a moment and the flow returns to wherever it began. The
 * return is automatic rather than a button the user must find - but the button exists too,
 * because an automatic transition a user cannot anticipate is its own confusion.
 */
@Composable
fun PackImportScreen(
    session: PackImportSession?,
    done: String?,
    busy: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    // The summary holds the floor briefly and then leaves, so finishing an import does not
    // strand the reader on a confirmation screen with one lonely "OK" on it.
    LaunchedEffect(done) {
        if (done != null) {
            kotlinx.coroutines.delay(SUMMARY_HOLD_MS)
            onDone()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Charaly.surface.base),
        contentPadding = PaddingValues(
            start = Charaly.space.gutter,
            end = Charaly.space.gutter,
            top = Charaly.space.sm,
            bottom = Charaly.space.section,
        ),
        verticalArrangement = Arrangement.spacedBy(Charaly.space.md),
    ) {
        item(key = "top-bar") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CharalyIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "İncelemeyi kapat",
                    // Leaving the review is the cancel path - it writes nothing - except
                    // after a successful install, where there is nothing left to cancel.
                    onClick = { if (done == null) onCancel() else onDone() },
                )
                Spacer(Modifier.width(Charaly.space.xs))
                Text(
                    text = session?.title?.takeIf { it.isNotBlank() } ?: "Hikâye Paketi",
                    style = MaterialTheme.typography.displaySmall,
                    color = Charaly.ink.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
            }
        }

        if (done != null) {
            item(key = "done") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CharalyShapes.soft)
                        .background(Charaly.surface.raised)
                        .padding(Charaly.space.lg),
                ) {
                    Text(
                        text = done,
                        style = MaterialTheme.typography.headlineSmall,
                        color = Charaly.ink.primary,
                    )
                    Spacer(Modifier.height(Charaly.space.md))
                    CharalyAction(label = "Kütüphaneye Dön", onClick = onDone)
                }
            }
            return@LazyColumn
        }

        val current = session
        if (current == null) {
            item(key = "missing") {
                if (busy) {
                    CharalySkeleton(
                        Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        shape = CharalyShapes.soft,
                    )
                } else {
                    CharalyEmptyState(
                        state = EmptyState(
                            title = "İncelenecek paket yok",
                            body = "Bir .charaly-pack dosyası seçtiğinizde kurulumdan önce " +
                                "burada incelenir.",
                            artSeed = "charaly-empty-import",
                        ),
                        action = { CharalyQuietAction(label = "Vazgeç", onClick = onCancel) },
                    )
                }
            }
            return@LazyColumn
        }

        // ---- the verdict and the inventory ---------------------------------
        item(key = "report-head") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CharalyShapes.soft)
                    .background(Charaly.surface.raised)
                    .padding(Charaly.space.lg),
            ) {
                Text(
                    text = current.report.headline,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Charaly.ink.primary,
                )
                if (current.report.censusLine.isNotBlank()) {
                    Text(
                        text = current.report.censusLine,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Charaly.ink.secondary,
                        modifier = Modifier.padding(top = Charaly.space.xs),
                    )
                }
                Row(
                    modifier = Modifier.padding(top = Charaly.space.sm),
                    horizontalArrangement = Arrangement.spacedBy(Charaly.space.xs),
                ) {
                    if (current.versionLabel.isNotBlank()) {
                        CharalyPill(label = current.versionLabel)
                    }
                    if (current.sourceName.isNotBlank()) {
                        CharalyPill(label = current.sourceName)
                    }
                }
            }
        }

        // ---- the upgrade notice --------------------------------------------
        //
        // Said *before* the findings, because it changes what installing means: the old
        // edition is retained for stories pinned to it, and a reader worried about a
        // half-finished playthrough deserves that answer above the warnings.
        if (current.isUpgrade) {
            item(key = "upgrade") {
                Text(
                    text = "Bu paketin ${current.previousVersion} sürümü yüklü. Yeni sürüm " +
                        "kurulduğunda eski sürüm korunur.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Charaly.ink.secondary,
                )
            }
        }

        // ---- findings, worst first -----------------------------------------
        if (current.report.errors.isNotEmpty()) {
            item(key = "errors-heading") {
                CharalySectionHeader(
                    title = "Kritik Hatalar",
                    micro = true,
                    caption = "Bu hatalar kurulumu engeller.",
                )
            }
            items(current.report.errors.size, key = { "error-$it" }) { index ->
                IssueRowView(row = current.report.errors[index])
            }
        }
        if (current.report.warnings.isNotEmpty()) {
            item(key = "warnings-heading") {
                CharalySectionHeader(title = "Uyarılar", micro = true)
            }
            items(current.report.warnings.size, key = { "warning-$it" }) { index ->
                IssueRowView(row = current.report.warnings[index])
            }
        }

        // ---- the decision --------------------------------------------------
        item(key = "actions") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(Charaly.space.xs),
            ) {
                if (busy) {
                    CharalySpinner(color = Charaly.signal.amber)
                }
                // Disabled rather than hidden when the report is critical: the findings
                // above are the reason, and a vanished button would leave the reader
                // wondering what the screen wants.
                CharalyAction(
                    label = "Yükle",
                    onClick = onConfirm,
                    enabled = current.report.canInstall && !busy,
                    fillWidth = true,
                )
                CharalyQuietAction(
                    label = "Vazgeç",
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                )
            }
        }
    }
}

/**
 * One finding: its sentence and its severity, and nothing else.
 *
 * The severity rides as a word beside the message rather than as a colour, so the
 * difference between a blocker and a warning survives colour-blindness and a greyscale
 * screenshot in a bug report alike.
 */
@Composable
private fun IssueRowView(row: IssueRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CharalyShapes.soft)
            .background(Charaly.surface.base)
            .padding(Charaly.space.md),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = row.message,
            style = MaterialTheme.typography.bodyMedium,
            color = Charaly.ink.primary,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(Charaly.space.sm))
        CharalyPill(label = row.severityLabel)
    }
}

private const val SUMMARY_HOLD_MS = 1400L