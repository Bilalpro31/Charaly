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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.PersonAddAlt1
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.charaly.app.R
import dev.charaly.app.ui.components.tappable
import dev.charaly.app.ui.design.Charaly
import dev.charaly.app.ui.design.CharalyShapes

/**
 * CREATE.
 *
 * ## The V5 shape
 *
 * Four doors, each a whole-surface target with a name and one honest sentence:
 *
 * ```
 *   Write your own world        the authoring flow, three steps
 *   Import a character          a SillyTavern card, previewed in full first
 *   Import a story pack         a .charaly-pack, reviewed in full before anything installs
 *   Story catalog               what is installed, and what can be downloaded
 * ```
 *
 * Each card is 48dp+ tall, described for screen readers, and the whole surface is the
 * target rather than a small arrow in the corner. Every door opens onto something real:
 * the rule is that a row without a real action shows no button, and that applies to a whole
 * card more than to a row.
 *
 * The two pack doors take their labels as plain Turkish rather than through a resource,
 * matching the neighbouring screens' hard-coded copy and keeping this pass's strings out of
 * the resource pipeline entirely.
 */
@Composable
fun CreateScreen(
    onWriteWorld: () -> Unit,
    onImportCharacter: () -> Unit,
    onImportPack: () -> Unit,
    onOpenPackStore: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Charaly.surface.base),
        contentPadding = PaddingValues(
            start = Charaly.space.gutter,
            end = Charaly.space.gutter,
            top = Charaly.space.xxl,
            bottom = Charaly.space.section,
        ),
        verticalArrangement = Arrangement.spacedBy(Charaly.space.md),
    ) {
        item(key = "title") {
            Text(
                text = stringResource(R.string.create_title),
                style = MaterialTheme.typography.displaySmall,
                color = Charaly.ink.primary,
                modifier = Modifier.semantics { heading() },
            )
        }

        item(key = "write-world") {
            CreateCard(
                title = stringResource(R.string.create_write_world),
                body = stringResource(R.string.create_write_world_desc),
                icon = Icons.Outlined.EditNote,
                onClick = onWriteWorld,
            )
        }

        item(key = "import-character") {
            CreateCard(
                title = stringResource(R.string.create_import_character),
                body = stringResource(R.string.create_import_character_desc),
                icon = Icons.Filled.PersonAddAlt1,
                onClick = onImportCharacter,
            )
        }

        item(key = "import-pack") {
            CreateCard(
                title = "Hikâye Paketi İçe Aktar",
                body = "Bir .charaly-pack dosyası seçin. Kurulmadan önce içeriği incelenir.",
                icon = Icons.Outlined.Inventory2,
                onClick = onImportPack,
            )
        }

        item(key = "pack-catalog") {
            CreateCard(
                title = "Hikâye Kataloğu",
                body = "Yüklü paketleri yönetin, yeni dünyalar indirin.",
                icon = Icons.Outlined.CollectionsBookmark,
                onClick = onOpenPackStore,
            )
        }
    }
}

/**
 * One door.
 *
 * A tall surface1 card, the icon at reading weight, the title in ink, the sentence in
 * muted. No border, no badge, no second action - the card *is* the action.
 */
@Composable
private fun CreateCard(
    title: String,
    body: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CharalyShapes.soft)
            .background(Charaly.surface.raised)
            .tappable(onClick)
            .semantics { role = Role.Button }
            .padding(Charaly.space.lg)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Charaly.ink.secondary,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.size(Charaly.space.md))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = Charaly.ink.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = Charaly.ink.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.size(Charaly.space.sm))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = Charaly.ink.muted,
            modifier = Modifier.size(20.dp),
        )
    }
}
