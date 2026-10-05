package dev.charaly.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import dev.charaly.app.ui.components.CharalyPrimaryButton
import dev.charaly.app.ui.theme.Charaly

/**
 * Shown when the developer panel is reached while developer mode is off.
 *
 * ## Why this exists
 *
 * The "Open developer panel" button is only rendered when developer mode is on, so on a
 * fresh launch the route is unreachable. But navigation state is *saved*: a user can open
 * the panel, switch developer mode off, and have the route still sitting in the restored
 * back stack.
 *
 * Without this, that path would render world state and raw prompts to someone who had
 * just asked not to see them - which is the exact failure the brief's "normal users
 * should never see it by accident" is about. So the gate is enforced where the route is
 * rendered, not only where it is linked.
 */
@Composable
fun DeveloperLockedScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Charaly.tokens.spacing.gutter)
            .semantics {
                contentDescription = "Developer panel is turned off. " +
                    "Switch on developer mode in Settings to use it."
            },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Developer mode is off",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "This panel shows world state, prompts and generation timings. " +
                "Turn developer mode on in Settings to use it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Charaly.tokens.spacing.xs),
        )
        CharalyPrimaryButton(
            label = "Back",
            onClick = onBack,
            modifier = Modifier.padding(top = Charaly.tokens.spacing.lg),
        )
    }
}