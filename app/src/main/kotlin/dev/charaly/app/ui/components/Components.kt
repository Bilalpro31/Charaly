package dev.charaly.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.charaly.app.ui.art.CharacterAvatar
import dev.charaly.app.ui.theme.Charaly
import dev.charaly.app.ui.theme.CharalyTypography
import dev.charaly.app.ui.theme.toColor
import dev.charaly.runtime.presentation.EmptyState
import dev.charaly.runtime.presentation.ResolvedTheme

/**
 * Charaly's component library.
 *
 * These are the only building blocks the screens use. Keeping them in one place
 * is what makes the app look like one product instead of nine screens: the same
 * button, the same card, the same empty state, everywhere.
 */

// ---------------------------------------------------------------------------
// Buttons
// ---------------------------------------------------------------------------

/**
 * The primary action.
 *
 * One per screen, filled with the current story accent. Nothing glows: the accent
 * colour is the only signal.
 */
@Composable
fun CharalyPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    loading: Boolean = false,
    container: Color = Charaly.accent.primary,
    contentColor: Color = Charaly.accent.onAccent,
) {
    val alpha by animateFloatAsState(if (enabled && !loading) 1f else 0.45f, label = "primary-alpha")
    Surface(
        onClick = { if (enabled && !loading) onClick() },
        enabled = enabled && !loading,
        modifier = modifier.heightIn(min = 48.dp),
        shape = Charaly.tokens.radii.shapePill,
        color = if (enabled) container else container.copy(alpha = 0.16f),
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 22.dp, vertical = 13.dp)
                .alpha(alpha),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                TinySpinner(color = contentColor)
            } else if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            if (loading || icon != null) Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A secondary action: outlined, quiet, same height as the primary. */
@Composable
fun CharalyGhostButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = Charaly.tokens.radii.shapePill,
        color = Color.Transparent,
        contentColor = contentColor,
        border = if (enabled) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A tertiary action: text only. Used inside cards and sheets. */
@Composable
fun CharalyTextButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 44.dp),
        shape = Charaly.tokens.radii.shapeSm,
        color = Color.Transparent,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(Charaly.tokens.icons.small))
                Spacer(Modifier.width(6.dp))
            }
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** A round icon-only action. Always has a content description. */
@Composable
fun CharalyIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    container: Color = Color.Transparent,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(44.dp)
            .semantics { this.contentDescription = contentDescription },
        shape = CircleShape,
        color = container,
        contentColor = tint,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(Charaly.tokens.icons.medium),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Structure
// ---------------------------------------------------------------------------

/**
 * A section title with an optional trailing action.
 *
 * Sections are the app's only navigation cue inside a scrolling screen, so they
 * are generous: label, optional subtitle, optional "See all".
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        action?.invoke()
    }
}

/** Small uppercase label used above groups of settings and filters. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = Charaly.accent.accent) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier,
    )
}

/**
 * The standard content card.
 *
 * One elevation step, generous radius, and a hairline border instead of a shadow:
 * a story app full of drop shadows reads as cluttered.
 */
@Composable
fun CharalyCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    container: Color = MaterialTheme.colorScheme.surfaceContainer,
    border: Color? = null,
    contentPadding: PaddingValues = PaddingValues(Charaly.tokens.spacing.md),
    content: @Composable () -> Unit,
) {
    val shape = Charaly.tokens.radii.shapeLg
    Box(
        modifier = modifier
            .clip(shape)
            .background(container)
            .then(
                if (border != null) {
                    Modifier.androidxBorderStroke(border, shape)
                } else {
                    Modifier
                },
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable { onClick() }
                } else {
                    Modifier
                },
            ),
    ) {
        Box(Modifier.padding(contentPadding)) { content() }
    }
}

private fun Modifier.androidxBorderStroke(color: Color, shape: androidx.compose.ui.graphics.Shape): Modifier =
    this.border(1.dp, color, shape)

// ---------------------------------------------------------------------------
// Chips and pills
// ---------------------------------------------------------------------------

/** A filter chip. Selected chips use the accent, unselected use the surface. */
@Composable
fun CharalyFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container by animateColorAsState(
        if (selected) Charaly.accent.primary.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceContainer,
        label = "chip-container",
    )
    val content by animateColorAsState(
        if (selected) Charaly.accent.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "chip-content",
    )
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 36.dp),
        shape = Charaly.tokens.radii.shapePill,
        color = container,
        contentColor = content,
        border = if (selected) {
            BorderStroke(1.dp, Charaly.accent.primary.copy(alpha = 0.5f))
        } else {
            null
        },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            maxLines = 1,
        )
    }
}

/** A genre chip: static, non-interactive. */
@Composable
fun GenreChip(label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = Charaly.tokens.radii.shapePill,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            maxLines = 1,
        )
    }
}

/** A labelled statistic, used in pack detail and the model screen. */
@Composable
fun StatPill(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = Charaly.tokens.radii.shapeSm,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A horizontal list of stat pills. */
@Composable
fun StatRow(stats: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    androidx.compose.foundation.lazy.LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.xs),
        contentPadding = PaddingValues(horizontal = Charaly.tokens.spacing.gutter),
    ) {
        items(stats.size) { index ->
            StatPill(label = stats[index].first, value = stats[index].second)
        }
    }
}

/** Status dot + label. Colour is never the only signal: the label says it too. */
@Composable
fun StatusDot(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    pulsing: Boolean = false,
) {
    val transition = rememberInfiniteTransition(label = "status-pulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (pulsing) 0.35f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "status-alpha",
    )
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .alpha(alpha)
                .background(color),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A very small indeterminate spinner. Used inside buttons and rows. */
@Composable
fun TinySpinner(color: Color = LocalContentColor.current, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(900), repeatMode = RepeatMode.Restart),
        label = "spinner-rotation",
    )
    Canvas(modifier = modifier.size(16.dp)) {
        rotate(degrees = rotation) {
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
        val inset = stroke.width / 2
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
            size = androidx.compose.ui.geometry.Size(size.width - stroke.width, size.height - stroke.width),
            style = stroke,
        )
        }
    }
}

// ---------------------------------------------------------------------------
// Empty states and loading
// ---------------------------------------------------------------------------

/**
 * An empty state that looks designed.
 *
 * Every empty state in Charaly has a headline in the user's language, a sentence
 * explaining what will fill it, and one action. There is no "No data" anywhere.
 */
@Composable
fun EmptyStateView(
    state: EmptyState,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Charaly.tokens.spacing.xl, vertical = Charaly.tokens.spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // A small generated mark instead of an illustration asset.
        PackArtGlyph(seed = state.artSeed, accent = Charaly.accent.primary)
        Spacer(Modifier.height(Charaly.tokens.spacing.lg))
        Text(
            text = state.title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(Charaly.tokens.spacing.xs))
        Text(
            text = state.body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(Charaly.tokens.spacing.lg))
            action()
        }
    }
}

/**
 * A generated mark for empty states.
 *
 * Same deterministic system as the covers, so even an empty screen is on-brand.
 */
@Composable
fun PackArtGlyph(seed: String, accent: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(96.dp)
            .clip(Charaly.tokens.radii.shapeXl),
    ) {
        dev.charaly.app.ui.art.PackArt(
            artwork = dev.charaly.runtime.domain.PackArtwork.generated(seed = seed),
            theme = ResolvedTheme(
                primary = accent.value.toLong(),
                secondary = accent.value.toLong(),
                accent = accent.value.toLong(),
                ink = 0xFFFFFFFF,
                surface = 0xFF15141B,
                mood = "",
            ),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** A shimmer placeholder. Used while a screen's data is loading. */
@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = Charaly.tokens.radii.shapeMd,
) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(1200), repeatMode = RepeatMode.Restart),
        label = "skeleton-shift",
    )
    val base = MaterialTheme.colorScheme.surfaceContainer
    val highlight = MaterialTheme.colorScheme.surfaceContainerHigh
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(base, highlight, base),
                    start = androidx.compose.ui.geometry.Offset(shift * 400f - 200f, 0f),
                    end = androidx.compose.ui.geometry.Offset(shift * 400f, 0f),
                ),
            ),
    )
}

/** A list of skeleton cards: what a loading screen looks like. */
@Composable
fun SkeletonList(
    count: Int = 3,
    modifier: Modifier = Modifier,
    cardHeight: androidx.compose.ui.unit.Dp = 120.dp,
) {
    Column(
        modifier = modifier.padding(horizontal = Charaly.tokens.spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(Charaly.tokens.spacing.sm),
    ) {
        repeat(count) {
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cardHeight),
                shape = Charaly.tokens.radii.shapeLg,
            )
        }
    }
}

/** A small circular avatar stack for cast lists. */
@Composable
fun CastAvatars(
    names: List<String>,
    accents: List<Long>,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 26.dp,
    max: Int = 4,
) {
    val shown = names.take(max)
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy((-size * 0.28f))) {
        shown.forEachIndexed { index, name ->
            CharacterAvatar(
                seed = name,
                accent = accents.getOrNull(index)?.toColor() ?: Charaly.accent.primary,
                name = name,
                modifier = Modifier
                    .size(size)
                    .androidxBorderStroke(
                        MaterialTheme.colorScheme.background,
                        CircleShape,
                    ),
            )
        }
        if (names.size > max) {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .androidxBorderStroke(MaterialTheme.colorScheme.background, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+${names.size - max}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A thin divider that respects the theme instead of shipping a grey line. */
@Composable
fun HairLine(modifier: Modifier = Modifier, color: Color = Charaly.colors.hairline) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color),
    )
}

/** Prose text used for narration and descriptions. */
@Composable
fun BodyProse(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Charaly.colors.narration,
) {
    Text(
        text = text,
        style = CharalyTypography.narration,
        color = color,
        modifier = modifier,
    )
}

/** A label/value row for detail sheets. */
@Composable
fun DetailRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(132.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Clickable text with a clear role, for keyboard and TalkBack. */
@Composable
fun ClickableText(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Charaly.accent.primary,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = modifier
            .clip(Charaly.tokens.radii.shapeXs)
            .clickable(role = Role.Button) { onClick() }
            .padding(vertical = 6.dp, horizontal = 4.dp),
    )
}

/** Full-height filler used to push a sheet's action row to the bottom. */
@Composable
fun VerticalSpacer(height: androidx.compose.ui.unit.Dp) {
    Spacer(Modifier.height(height))
}

@Composable
fun FillSpacer(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize())
}

/** A bold emphasis used for numbers inside prose. */
@Composable
fun Emphasis(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}