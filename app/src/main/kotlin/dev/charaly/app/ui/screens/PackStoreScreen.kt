package dev.charaly.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.window.Dialog
import dev.charaly.app.ui.art.PackArt
import dev.charaly.app.ui.components.CharalyAction
import dev.charaly.app.ui.components.CharalyEmptyState
import dev.charaly.app.ui.components.CharalyIconButton
import dev.charaly.app.ui.components.CharalyPill
import dev.charaly.app.ui.components.CharalyQuietAction
import dev.charaly.app.ui.components.CharalySectionHeader
import dev.charaly.app.ui.components.CharalySkeleton
import dev.charaly.app.ui.design.Charaly
import dev.charaly.app.ui.design.CharalyShapes
import dev.charaly.app.ui.packstore.PackDeletePrompt
import dev.charaly.app.ui.packstore.PackDownloadUi
import dev.charaly.app.ui.packstore.PackInstallState
import dev.charaly.app.ui.packstore.PackOpenAction
import dev.charaly.app.ui.packstore.PackOpenGate
import dev.charaly.app.ui.packstore.PackStoreItem
import dev.charaly.app.ui.packstore.StoryCatalogCard
import dev.charaly.runtime.domain.PackArtwork
import dev.charaly.runtime.presentation.EmptyState

/**
 * THE STORY PACK STORE.
 *
 * ## Two shelves, one question each
 *
 * ```
 *   Yüklü Paketler    what is on this device, and what can be done with it
 *   Mevcut Paketler   what the catalog offers, and what downloading it involves
 * ```
 *
 * Installed rows carry their real inventory ("3 karakter · 5 mekân · 2 açılış") rather than
 * a file size, because that is the fact that helps someone decide whether to keep a pack -
 * and it is counted from the record the installer wrote, never recomputed here.
 *
 * ## The progress bar cannot lie
 *
 * A download draws `PackDownloadUi.fraction`, which is null unless bytes are genuinely
 * moving toward a known total. In every other state the card says what is happening
 * ("Denetleniyor", "Kuruluyor") instead of animating a percentage that measures nothing -
 * the same rule the model library's transfer surface obeys.
 *
 * ## Delete is deliberate, and its dialog is not here
 *
 * "Sil" raises a confirmation through the view model and the shell-owned
 * [PackDeleteDialogs], because deleting a pack must look the same from this screen and from
 * a world's showcase - one deliberate path, one set of promises about saves surviving.
 */
@Composable
fun PackStoreScreen(
    installed: List<PackStoreItem>,
    catalog: List<StoryCatalogCard>,
    catalogSourceNote: String,
    loading: Boolean,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onExport: (PackStoreItem) -> Unit,
    onUpdate: (PackStoreItem) -> Unit,
    onDelete: (PackStoreItem) -> Unit,
    onDownload: (StoryCatalogCard) -> Unit,
    onPauseDownload: (String) -> Unit,
    onResumeDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
) {
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
                    contentDescription = "Paket listesinden çık",
                    onClick = onBack,
                )
                Spacer(Modifier.width(Charaly.space.xs))
                Text(
                    text = "Hikâye Paketleri",
                    style = MaterialTheme.typography.displaySmall,
                    color = Charaly.ink.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                // The only editor affordance up here: bringing a .charaly-pack in from
                // storage is the one action that is not about a row in a list.
                CharalyQuietAction(label = "İçe Aktar", onClick = onImport)
            }
        }

        if (loading && installed.isEmpty() && catalog.isEmpty()) {
            item(key = "loading") {
                CharalySkeleton(
                    Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    shape = CharalyShapes.soft,
                )
            }
            return@LazyColumn
        }

        // ---- installed -----------------------------------------------------
        item(key = "installed-heading") {
            CharalySectionHeader(title = "Yüklü Paketler", micro = true)
        }
        if (installed.isEmpty()) {
            item(key = "installed-empty") {
                CharalyEmptyState(
                    state = EmptyState(
                        title = "Henüz paket yüklenmedi",
                        body = "İçe aktardığınız ya da aşağıdan indirdiğiniz hikâye paketleri " +
                            "burada listelenir.",
                        artSeed = "charaly-empty-packs",
                    ),
                )
            }
        }
        items(installed.size, key = { "installed-${installed[it].packId}" }) { index ->
            InstalledPackRow(
                item = installed[index],
                onExport = onExport,
                onUpdate = onUpdate,
                onDelete = onDelete,
            )
        }

        // ---- catalog -------------------------------------------------------
        item(key = "catalog-heading") {
            CharalySectionHeader(title = "Mevcut Paketler", micro = true)
        }
        if (catalog.isEmpty()) {
            item(key = "catalog-empty") {
                CharalyEmptyState(
                    state = EmptyState(
                        title = "Katalogda paket yok",
                        body = "Bağlantı kurulduğunda burada yeni dünyalar görünür. " +
                            "Yüklü paketler çevrimdışı da çalışır.",
                        artSeed = "charaly-empty-catalog",
                    ),
                )
            }
        }
        items(catalog.size, key = { "catalog-${catalog[it].itemId}" }) { index ->
            CatalogPackRow(
                card = catalog[index],
                onDownload = onDownload,
                onPauseDownload = onPauseDownload,
                onResumeDownload = onResumeDownload,
                onCancelDownload = onCancelDownload,
                onRetryDownload = onRetryDownload,
            )
        }

        // Where these listings came from, said in one line. A catalog showing fallback
        // entries must admit it rather than presenting them as the live selection.
        if (catalogSourceNote.isNotBlank()) {
            item(key = "catalog-note") {
                Text(
                    text = catalogSourceNote,
                    style = MaterialTheme.typography.labelSmall,
                    color = Charaly.ink.muted,
                    modifier = Modifier.navigationBarsPadding(),
                )
            }
        }
    }
}

/**
 * One installed pack.
 *
 * Cover block, the four facts a keeper cares about (who made it, which edition, what it is
 * about, what it holds), then the three actions. Export and Update are ordinary text
 * actions beside Delete rather than styled differently: a red button invites accidental
 * destruction and a confirmation dialog is what actually prevents it.
 */
@Composable
private fun InstalledPackRow(
    item: PackStoreItem,
    onExport: (PackStoreItem) -> Unit,
    onUpdate: (PackStoreItem) -> Unit,
    onDelete: (PackStoreItem) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CharalyShapes.soft)
            .background(Charaly.surface.raised)
            .padding(Charaly.space.md),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            PackCoverBlock(seed = item.packId)
            Spacer(Modifier.width(Charaly.space.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Charaly.ink.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        item.author.takeIf { it.isNotBlank() },
                        item.versionLabel.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Charaly.ink.secondary,
                )
                if (item.premise.isNotBlank()) {
                    Text(
                        text = item.premise,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Charaly.ink.muted,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = Charaly.space.xxs),
                    )
                }
                if (item.countsLine.isNotBlank()) {
                    Text(
                        text = item.countsLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = Charaly.ink.muted,
                        modifier = Modifier.padding(top = Charaly.space.xxs),
                    )
                }
                if (item.tags.isNotEmpty()) {
                    Text(
                        text = item.tags.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = Charaly.ink.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = Charaly.space.xxs),
                    )
                }
            }
        }
        Row(
            modifier = Modifier.padding(top = Charaly.space.xs),
            horizontalArrangement = Arrangement.spacedBy(Charaly.space.xs),
        ) {
            CharalyQuietAction(label = "Dışa Aktar", onClick = { onExport(item) })
            CharalyQuietAction(label = "Güncelle", onClick = { onUpdate(item) })
            CharalyQuietAction(label = "Sil", onClick = { onDelete(item) })
        }
    }
}

/**
 * One catalog listing.
 *
 * A listing is a shop-window claim, so this row shows only what the catalog actually
 * declared - title, maker, premise, size - and the download state once a transfer exists.
 * A pack already installed shows a "Yüklü" pill instead of a Download button: a control
 * that would reinstall over itself is worse than an honest label.
 */
@Composable
private fun CatalogPackRow(
    card: StoryCatalogCard,
    onDownload: (StoryCatalogCard) -> Unit,
    onPauseDownload: (String) -> Unit,
    onResumeDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CharalyShapes.soft)
            .background(Charaly.surface.base)
            .padding(Charaly.space.md),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            PackCoverBlock(seed = card.packId)
            Spacer(Modifier.width(Charaly.space.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = card.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Charaly.ink.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        card.author.takeIf { it.isNotBlank() },
                        card.sizeLabel.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Charaly.ink.secondary,
                )
                if (card.premise.isNotBlank()) {
                    Text(
                        text = card.premise,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Charaly.ink.muted,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = Charaly.space.xxs),
                    )
                }
                if (card.tags.isNotEmpty()) {
                    Text(
                        text = card.tags.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = Charaly.ink.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = Charaly.space.xxs),
                    )
                }
            }
        }

        val download = card.download
        when {
            download != null && !download.isReady -> PackDownloadSurface(
                ui = download,
                itemId = card.itemId,
                onPauseDownload = onPauseDownload,
                onResumeDownload = onResumeDownload,
                onCancelDownload = onCancelDownload,
                onRetryDownload = onRetryDownload,
            )

            download != null || card.installState == PackInstallState.INSTALLED ->
                CharalyPill(label = "Yüklü", modifier = Modifier.padding(top = Charaly.space.xs))

            else -> Row(
                modifier = Modifier.padding(top = Charaly.space.xs),
                horizontalArrangement = Arrangement.spacedBy(Charaly.space.xs),
            ) {
                // Both "not installed" and "an update is offered" mean the same press here;
                // the pipeline installs the edition the listing names either way.
                CharalyQuietAction(label = "İndir", onClick = { onDownload(card) })
                if (card.installState == PackInstallState.UPDATE_AVAILABLE) {
                    CharalyPill(label = "Yeni sürüm")
                }
            }
        }
    }
}

/**
 * A download in flight, honestly.
 *
 * The bar shows a determinate fraction only when one exists. Everywhere else it is
 * Compose's indeterminate indicator, which admits "working, extent unknown" - a
 * determinate 0% would claim knowledge the pipeline does not have. The failure's own two
 * sentences are shown verbatim: what happened, and what can be done instead.
 */
@Composable
private fun PackDownloadSurface(
    ui: PackDownloadUi,
    itemId: String,
    onPauseDownload: (String) -> Unit,
    onResumeDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(top = Charaly.space.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = ui.stateLabel,
                style = MaterialTheme.typography.labelMedium,
                color = Charaly.signal.amber,
            )
            if (ui.transferredLabel.isNotBlank()) {
                Text(
                    text = " · ${ui.transferredLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Charaly.ink.muted,
                )
            }
        }
        Spacer(Modifier.height(Charaly.space.xxs))
        val fraction = ui.fraction
        if (fraction == null) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = Charaly.signal.amber,
                trackColor = Charaly.surface.overlay,
            )
        } else {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
                color = Charaly.signal.amber,
                trackColor = Charaly.surface.overlay,
            )
        }
        if (ui.message.isNotBlank()) {
            Text(
                text = ui.message,
                style = MaterialTheme.typography.bodySmall,
                color = Charaly.ink.muted,
                modifier = Modifier.padding(top = Charaly.space.xxs),
            )
        }
        if (ui.failureMessage.isNotBlank()) {
            Text(
                text = ui.failureMessage,
                style = MaterialTheme.typography.bodySmall,
                color = Charaly.ink.primary,
                modifier = Modifier.padding(top = Charaly.space.xxs),
            )
        }
        if (ui.failureAlternative.isNotBlank()) {
            Text(
                text = ui.failureAlternative,
                style = MaterialTheme.typography.bodySmall,
                color = Charaly.ink.muted,
            )
        }
        Row(
            modifier = Modifier.padding(top = Charaly.space.xs),
            horizontalArrangement = Arrangement.spacedBy(Charaly.space.xs),
        ) {
            if (ui.canPause) {
                CharalyQuietAction(label = "Duraklat", onClick = { onPauseDownload(itemId) })
            }
            if (ui.canRetry) {
                // "Devam" where a pause left bytes on disk, "Yeniden Dene" where the
                // transfer has to be reissued. Same button, two different promises.
                val label = if (ui.isPaused) "Devam" else "Yeniden Dene"
                val action = if (ui.isPaused) onResumeDownload else onRetryDownload
                CharalyQuietAction(label = label, onClick = { action(itemId) })
            }
            if (ui.canCancel) {
                CharalyQuietAction(label = "İptal", onClick = { onCancelDownload(itemId) })
            }
        }
    }
}

/**
 * The cover stand-in: generated art seeded by the pack's id.
 *
 * Deterministic per pack and offline by construction - the store must render its whole
 * self with no network and no pack assets decoded. A real cover arrives through the pack's
 * own artwork tree; until then this is honest art rather than a grey box.
 */
@Composable
private fun PackCoverBlock(seed: String) {
    PackArt(
        artwork = PackArtwork.generated(seed = seed),
        atmosphere = Charaly.atmosphere,
        modifier = Modifier
            .width(56.dp)
            .height(74.dp)
            .clip(CharalyShapes.cut),
    )
}

/**
 * THE DELETE CONFIRMATION, in its two stages.
 *
 * ```
 *   Sil?                                the plain confirmation
 *   Bu pakete bağlı N hikâye var        the installer's refusal, and one more chance
 * ```
 *
 * ## Why this lives here and not inside either screen
 *
 * A pack can be deleted from this store and from a world's showcase, and a destructive
 * action must look and mean the same thing from both. One dialog owner is how the promise
 * is one promise - "your saves survive; the pack can come back" - instead of two wordings
 * that drift. The shell places this composable once, above every route.
 *
 * ## What is said, and why the second dialog exists
 *
 * Both stages promise the same hard truth from the installer: a delete removes *content*,
 * never saves. The second stage is `DeleteOutcome.Blocked` made visible - stories are
 * running on this pack, so the delete needs insisting on ("Yine de Sil" → `force = true`),
 * and the user is told exactly how many stories that affects before insisting.
 */
@Composable
fun PackDeleteDialogs(
    prompt: PackDeletePrompt?,
    onConfirm: () -> Unit,
    onForceDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (prompt == null) return
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CharalyShapes.sheet)
                .background(Charaly.surface.elevated)
                .padding(Charaly.space.lg),
        ) {
            val heading = if (prompt.blocked) {
                "Bu pakete bağlı ${prompt.activeStories} hikâye var"
            } else {
                "Paket silinsin mi?"
            }
            val body = if (prompt.blocked) {
                "“${prompt.title}” silinirse bu hikâyeler yeni bölümler üretemez. " +
                    "Kayıtlarınız silinmez; paket geri yüklendiğinde hikâyeler kaldığı yerden devam eder."
            } else {
                "“${prompt.title}” bu cihazdan kaldırılır. Bu paketten başlamış hikâyelerin " +
                    "kayıtları silinmez."
            }
            Text(
                text = heading,
                style = MaterialTheme.typography.headlineMedium,
                color = Charaly.ink.primary,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = Charaly.ink.secondary,
                modifier = Modifier.padding(top = Charaly.space.sm),
            )
            Row(
                modifier = Modifier.padding(top = Charaly.space.md),
                horizontalArrangement = Arrangement.spacedBy(Charaly.space.xs),
            ) {
                CharalyQuietAction(label = "Vazgeç", onClick = onDismiss)
                CharalyAction(
                    label = if (prompt.blocked) "Yine de Sil" else "Sil",
                    onClick = if (prompt.blocked) onForceDelete else onConfirm,
                )
            }
        }
    }
}

/**
 * THE STORY-OPEN GATE, shown instead of the stage.
 *
 * The state pair `MIGRATION_REQUIRED` / `PACK_MISSING` made visible at the one moment the
 * player meets it: tapping a story whose pinned pack edition is not on this device. Its
 * twin promise, in the same voice as the delete dialog: **your save is not touched** - not
 * opened, not migrated, not silently re-pointed at the newer edition - and play resumes
 * the moment the right edition is back.
 *
 * There is deliberately no "geçiş yap / migrate" button. No migration operation exists in
 * the app, and a button that promises one would be a lie with a tap target. The recovery
 * actions are only the doors content really comes through: the `.charaly-pack` importer
 * and the story catalog.
 */
@Composable
fun PackOpenGateDialog(
    gate: PackOpenGate?,
    onAction: (PackOpenAction) -> Unit,
    onDismiss: () -> Unit,
) {
    if (gate == null) return
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CharalyShapes.sheet)
                .background(Charaly.surface.elevated)
                .padding(Charaly.space.lg),
        ) {
            Text(
                text = "Bu hikâye açılamadı",
                style = MaterialTheme.typography.headlineMedium,
                color = Charaly.ink.primary,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = gate.storyTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Charaly.ink.secondary,
                modifier = Modifier.padding(top = Charaly.space.xs),
            )
            Text(
                text = gate.explanation,
                style = MaterialTheme.typography.bodyMedium,
                color = Charaly.ink.secondary,
                modifier = Modifier.padding(top = Charaly.space.sm),
            )
            Row(
                modifier = Modifier.padding(top = Charaly.space.md),
                horizontalArrangement = Arrangement.spacedBy(Charaly.space.xs),
            ) {
                CharalyQuietAction(label = "Kapat", onClick = onDismiss)
                gate.actions.forEach { action ->
                    CharalyAction(label = action.label, onClick = { onAction(action) })
                }
            }
        }
    }
}