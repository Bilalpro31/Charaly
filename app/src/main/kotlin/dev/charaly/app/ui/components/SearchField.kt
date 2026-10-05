package dev.charaly.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.charaly.app.ui.theme.Charaly

/**
 * Charaly's search field.
 *
 * A `BasicTextField` on a filled surface rather than a Material `OutlinedTextField`:
 * an outlined box with a floating label is the single most recognisable "developer
 * form" element, and it is exactly what a consumer app should not look like.
 */
@Composable
fun CharalySearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Search,
    onSearch: (() -> Unit)? = null,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(Charaly.tokens.radii.shapePill)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 14.dp)
            .semantics { contentDescription = placeholder },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Charaly.tokens.icons.small),
        )
        Spacer(Modifier.width(10.dp))
        BoxedText(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            imeAction = imeAction,
            onImeAction = {
                keyboard?.hide()
                onSearch?.invoke()
            },
            modifier = Modifier.weight(1f),
        )
        if (value.isNotEmpty()) {
            CharalyIconButton(
                icon = Icons.Filled.Close,
                contentDescription = "Clear",
                onClick = { onValueChange("") },
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

@Composable
private fun BoxedText(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    imeAction: ImeAction,
    onImeAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.heightIn(min = 44.dp), contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = LocalTextStyle.current.merge(
                MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            ),
            cursorBrush = SolidColor(Charaly.accent.primary),
            keyboardOptions = KeyboardOptions(imeAction = imeAction),
            keyboardActions = KeyboardActions(
                onSearch = { onImeAction() },
                onDone = { onImeAction() },
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

