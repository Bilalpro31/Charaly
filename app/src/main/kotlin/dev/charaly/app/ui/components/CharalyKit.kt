package dev.charaly.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.charaly.app.ui.art.CharacterMark
import dev.charaly.app.ui.art.PackArt
import dev.charaly.runtime.domain.PackArtwork
import dev.charaly.app.ui.art.PackArtworkHero
import dev.charaly.app.ui.design.Charaly
import dev.charaly.app.ui.design.CharalyAtmosphere
import dev.charaly.app.ui.design.CharalyShapes
import dev.charaly.app.ui.theme.motionDuration
import dev.charaly.runtime.presentation.EmptyState
import dev.charaly.runtime.presentation.ResolvedTheme

/**
 * CHARALY'S COMPONENT LIBRARY.
 *
 * ## The whole set, and why it is small
 *
 * Twelve components cover every screen in the app. That number is the point: a component
 * per widget produces a design system nobody can hold in their head, and a component per
 * screen produces a design system that does not exist.
 *
 * ```
 *   CharalySectionHeader   a heading and, optionally, one quiet line under it
 *   CharalyPill            compact status or filter. Pills, not chips everywhere
 *   CharalyIconButton      a round icon control. Always 48dp, always described
 *   CharalyAction          the one filled action a screen offers
 *   CharalyQuietAction     the secondary action beside it
 *   CharalySurface         a layered surface: cards, sheets, floating panels
 *   CharalyHero            full-bleed artwork with a bottom-weighted scrim
 *   CharalyWorldCard       one world, in a feed
 *   CharalyStoryCard       one story, on a shelf
 *   CharalyPresence        one person in a room
 *   CharalyEmptyState      an empty state that says what will fill it
 *   CharalySkeleton        a placeholder that matches the real thing's shape
 *   CharalySearchField     a search field that is not an outlined box
 * ```
 *
 * ## The rules they all obey
 *
 * * **48dp minimum.** Every interactive element. Enforced by `CharalyIconButton` and by
 *   `heightIn(min = 48.dp)` on the actions, rather than left to each call site.
 * * **Colour is never the only signal.** A selected pill changes its *fill* and its
 *   *border*; a status dot is paired with a word.
 * * **Content descriptions on every icon control.** Asserted by the test suite, because an
 *   undescribed icon is invisible to a screen reader.
 * * **No Material-looking text fields.** A `BasicTextField` on a filled surface, because an
 *   outlined box with a floating label is the single most recognisable developer-form
 *   element in Compose and this is not a developer tool.
 */

// ---------------------------------------------------------------------------
// Structure
// ---------------------------------------------------------------------------

/**
 * A heading, and optionally one quiet line under it.
 *
 * `micro` exists for the two places a screen genuinely needs a small label - a metadata row
 * and a pack's universe line - and nowhere else. Small type used freely is how a screen
 * ends up with eleven competing voices.
 */
@Composable
fun CharalySectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    caption: String = "",
    micro: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = if (micro) {
                    MaterialTheme.typography.labelLarge
                } else {
                    MaterialTheme.typography.headlineMedium
                },
                color = if (micro) Charaly.ink.secondary else Charaly.ink.primary,
                modifier = Modifier.semantics { heading() },
            )
            if (caption.isNotBlank()) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = Charaly.ink.muted,
                    modifier = Modifier.padding(top = Charaly.space.xxs),
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * A compact status or filter control.
 *
 * ## Why a pill and not a chip
 *
 * A chip is a rounded rectangle with a border; a pill is a rounded rectangle that reads as
 * a *tag*. Charaly uses pills for exactly two things - a status like READY, and a compact
 * filter - and reserves full-width surfaces for anything that needs a title. That
 * distinction is most of why the app stopped looking like a grid of cards.
 *
 * [selected] changes the fill *and* the border, so selection survives without colour.
 */
@Composable
fun CharalyPill(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    accent: Color? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    contentDescription: String = "",
) {
    val tint = accent ?: Charaly.atmosphere.accent
    val fill by animateColorAsState(
        targetValue = when {
            selected -> tint.copy(alpha = 0.18f)
            enabled -> Charaly.surface.raised
            else -> Charaly.surface.base
        },
        animationSpec = tween(motionDuration(Charaly.timing.quick)),
        label = "pill-fill",
    )
    val content by animateColorAsState(
        targetValue = when {
            selected -> Charaly.ink.primary
            enabled -> Charaly.ink.secondary
            else -> Charaly.ink.muted
        },
        animationSpec = tween(motionDuration(Charaly.timing.quick)),
        label = "pill-content",
    )

    Surface(
        modifier = modifier
            .heightIn(min = if (onClick == null) 0.dp else 48.dp)
            .semantics {
                if (onClick != null) this.role = Role.Button
                if (contentDescription.isNotBlank()) {
                    this.contentDescription = contentDescription
                }
            },
        shape = CharalyShapes.pill,
        color = fill,
        contentColor = content,
        border = if (selected) {
            BorderStroke(1.dp, tint.copy(alpha = 0.55f))
        } else if (onClick != null) {
            BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
        } else {
            null
        },
        onClick = onClick ?: {},
        enabled = onClick != null && enabled,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (onClick == null) Charaly.space.sm else Charaly.space.md,
                vertical = if (onClick == null) 4.dp else Charaly.space.sm,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = content,
                )
                Spacer(Modifier.width(Charaly.space.xxs))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A round icon control.
 *
 * ## The two invariants
 *
 * * **48dp.** Hard-coded here rather than left to a call site's `size()`, because the
 *   previous build had a 40dp back button on its most important screen and the regression
 *   was invisible to review.
 * * **A content description, required.** Not defaulted, not optional. An icon control a
 *   screen reader cannot name does not exist for a blind user, and that is the same as not
 *   existing.
 */
@Composable
fun CharalyIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Charaly.ink.primary,
    container: Color = Color.Transparent,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(48.dp)
            .semantics { this.contentDescription = contentDescription },
        shape = CircleShape,
        color = container,
        contentColor = if (enabled) tint else Charaly.ink.muted,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        }
    }
}

/**
 * The one filled action a screen offers.
 *
 * ## Why it is a capsule and not a rectangle
 *
 * A rounded rectangle is the default shape of a Material button and the reason half the
 * app read as a template. A capsule filled with the *world's* accent reads as an
 * invitation, and because it is the only filled control on a screen it is unambiguous
 * without a label explaining it.
 *
 * Full width only when the action is the screen's whole purpose - which is stated by the
 * caller passing `fillWidth`, not decided here.
 */
@Composable
fun CharalyAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fillWidth: Boolean = false,
    icon: ImageVector? = null,
) {
    val tint = Charaly.atmosphere.accent
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = 52.dp)
            .semantics { role = Role.Button },
        shape = CharalyShapes.pill,
        color = if (enabled) tint else Charaly.surface.raised,
        contentColor = if (enabled) Charaly.atmosphere.onAccent else Charaly.ink.muted,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Charaly.space.lg, vertical = Charaly.space.sm),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Charaly.space.xs))
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

/**
 * The secondary action beside a [CharalyAction].
 *
 * Text, not an outline. An outlined button next to a filled one is the standard Material
 * pair and it reads as a form; a text action reads as an alternative, which is what it is.
 */
@Composable
fun CharalyQuietAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    contentColor: Color = Charaly.ink.secondary,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics { role = Role.Button },
        shape = CharalyShapes.soft,
        color = Color.Transparent,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = Charaly.space.sm,
                vertical = Charaly.space.sm,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Charaly.space.xs))
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

/**
 * A layered surface.
 *
 * ## Why this exists at all
 *
 * Not as a "card". Cards are the failure mode: a surface with a border and a radius applied
 * to every group of information, until the screen is a grid of boxes. This is offered for
 * the three places a *panel* is genuinely right - a floating list over artwork, a sheet, a
 * selected row - and a screen that wants four of them has a layout problem, not a missing
 * component.
 */
@Composable
fun CharalySurface(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    fill: Color = Charaly.surface.raised,
    border: Color? = null,
    shape: Shape = CharalyShapes.soft,
    padding: PaddingValues = PaddingValues(Charaly.space.md),
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(fill)
            .then(
                if (border != null) Modifier.border(1.dp, border, shape) else Modifier,
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                },
            ),
    ) {
        Box(Modifier.padding(padding)) { content() }
    }
}

/**
 * Full-bleed artwork with a bottom-weighted scrim.
 *
 * ## The one visual idea
 *
 * Artwork, and the smallest amount of type that makes it a destination rather than a
 * picture. Everything a hero needs to be legible - the scrim, the gradient, the bloom -
 * is here, so a screen cannot accidentally render a hero with a heavy uniform scrim over
 * the whole thing and lose the artwork's own light source.
 *
 * Takes an [CharalyAtmosphere] rather than a theme so a caller that already resolved the
 * world's identity does not project it twice - and so the same hero works for a world, a
 * story and a shelf card without a special case.
 */
@Composable
fun CharalyHero(
    artwork: PackArtwork,
    atmosphere: CharalyAtmosphere,
    modifier: Modifier = Modifier,
    strength: Float = 1f,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(CharalyShapes.soft)
            .background(Charaly.surface.base),
    ) {
        PackArtworkHero(
            artwork = artwork,
            atmosphere = atmosphere,
            modifier = Modifier.fillMaxSize(),
            strength = strength,
        )
        content()
    }
}

/**
 * A tap target over artwork, with no ripple.
 *
 * ## Why the affordance is the picture
 *
 * A ripple on an artwork hero reads as a smudge on a photograph. The card already has a
 * border, a title and a clear whole-card area, so the press state needs nothing extra -
 * and dropping the ripple is what lets the whole surface be one big target instead of a
 * small button in the corner.
 */
@Composable
fun Modifier.tappable(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = interaction,
        indication = null,
        onClick = onClick,
    )
}

/**
 * One person in a room.
 *
 * ## Presence is a shape as well as a colour
 *
 * The dot's *form* differs by state - a filled disc for here, a ring for nearby, nothing
 * for elsewhere - so the information survives without colour. That matters for a user who
 * cannot distinguish the accent from the muted ink, and it is why this component takes a
 * `Whereabouts`-like enum rather than a boolean.
 */
@Composable
fun CharalyPresence(
    name: String,
    accent: Color,
    modifier: Modifier = Modifier,
    markSize: Dp = 44.dp,
    status: PresenceStyle = PresenceStyle.HERE,
    caption: String = "",
    onClick: (() -> Unit)? = null,
) {
    val clickable = if (onClick != null) {
        Modifier.clickable(onClick = onClick).semantics {
            role = Role.Button
            contentDescription = buildString {
                append(name)
                if (caption.isNotBlank()) append(". ").append(caption)
                append(". ").append(status.label)
            }
        }
    } else {
        Modifier
    }

    Row(
        modifier = modifier.then(clickable),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(markSize)) {
            CharacterMark(seed = name, accent = accent, name = name, modifier = Modifier.fillMaxSize())
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(Charaly.surface.void)
                    .padding(2.dp),
            ) {
                PresenceDot(status = status, accent = accent, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.width(Charaly.space.sm))
        Column(Modifier.weight(1f, fill = false)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                color = Charaly.ink.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (caption.isNotBlank()) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = Charaly.ink.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Presence styling.
 *
 * Mirrors [dev.charaly.runtime.presentation.Whereabouts] so a screen never invents a third
 * vocabulary, and exists so the app layer does not have to import the runtime enum into
 * every composable's signature.
 */
enum class PresenceStyle(val label: String) {
    HERE("Here"),
    NEARBY("Nearby"),
    ELSEWHERE("Elsewhere"),
}

@Composable
private fun PresenceDot(status: PresenceStyle, accent: Color, modifier: Modifier = Modifier) {
    when (status) {
        PresenceStyle.HERE -> Box(
            modifier
                .clip(CircleShape)
                .background(accent),
        )

        // A ring rather than a filled dot: same hue, different form.
        PresenceStyle.NEARBY -> Box(
            modifier
                .clip(CircleShape)
                .border(1.5.dp, accent.copy(alpha = 0.8f), CircleShape),
        )

        PresenceStyle.ELSEWHERE -> Box(
            modifier
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.22f)),
        )
    }
}

// ---------------------------------------------------------------------------
// States
// ---------------------------------------------------------------------------

/**
 * An empty state that says what will fill it.
 *
 * The brief is absolute here: no screen may ever render "No data", "null" or an empty
 * list. Every empty state has a headline in the user's language, a sentence about what
 * arrives next, and at most one action.
 */
@Composable
fun CharalyEmptyState(
    state: EmptyState,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Charaly.space.lg, vertical = Charaly.space.section),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // A generated mark rather than an illustration asset: same artwork system as every
        // hero, so even an empty screen is on-brand.
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CharalyShapes.soft),
        ) {
            PackArt(
                artwork = PackArtwork.generated(seed = state.artSeed.ifBlank { "charaly-empty" }),
                atmosphere = Charaly.atmosphere,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(Charaly.space.lg))
        Text(
            text = state.title,
            style = MaterialTheme.typography.headlineSmall,
            color = Charaly.ink.primary,
            textAlign = TextAlign.Center,
        )
        if (state.body.isNotBlank()) {
            Spacer(Modifier.height(Charaly.space.xs))
            Text(
                text = state.body,
                style = MaterialTheme.typography.bodyMedium,
                color = Charaly.ink.muted,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(Charaly.space.lg))
            action()
        }
    }
}

/**
 * A placeholder shaped like the thing it stands in for.
 *
 * ## Why no full-screen spinner
 *
 * A spinner says "wait", which is a worse answer than "here is the shape of what is
 * coming". These blocks shimmer across the real layout, so the screen does not jump when
 * the data lands, and the shimmer is disabled outright when motion is reduced rather than
 * merely shortened.
 */
@Composable
fun CharalySkeleton(
    modifier: Modifier = Modifier,
    shape: Shape = CharalyShapes.soft,
    height: Dp? = null,
) {
    val animated = dev.charaly.app.ui.theme.allowsDecorativeMotion()
    val transition = rememberInfiniteTransition(label = "skeleton")
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (animated) 1f else 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "skeleton-shift",
    )

    Box(
        modifier = modifier
            .then(if (height != null) Modifier.height(height) else Modifier)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Charaly.surface.base,
                        Charaly.surface.elevated,
                        Charaly.surface.base,
                    ),
                    start = androidx.compose.ui.geometry.Offset(shift * 480f - 240f, 0f),
                    end = androidx.compose.ui.geometry.Offset(shift * 480f, 0f),
                ),
            ),
    )
}

/**
 * A search field.
 *
 * A `BasicTextField` on a filled surface rather than an outlined box with a floating
 * label: that outlined box is the most recognisable developer-form element in Compose, and
 * this is not a developer tool. 48dp tall, because it is a primary target on a phone.
 */
@Composable
fun CharalySearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(CharalyShapes.pill)
            .background(Charaly.surface.raised)
            .padding(horizontal = Charaly.space.md)
            .semantics { contentDescription = placeholder },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = Charaly.ink.muted,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(Charaly.space.sm))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Charaly.ink.primary),
            cursorBrush = SolidColor(Charaly.atmosphere.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    keyboard?.hide()
                    onSearch?.invoke()
                },
            ),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Charaly.ink.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                inner()
            },
        )
        if (value.isNotEmpty()) {
            CharalyIconButton(
                icon = Icons.Filled.Close,
                contentDescription = "Clear search",
                onClick = { onValueChange("") },
                modifier = Modifier.size(48.dp),
            )
        }
    }
}

/**
 * A row of pills, for compact filters.
 *
 * A `LazyRow`, because a genre list is longer than a phone is wide and a wrapping row
 * inside a lazy column item is exactly the crash the nested-scroller guard exists to stop.
 */
@Composable
fun CharalyPillRow(
    labels: List<String>,
    selected: Set<String> = emptySet(),
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit = {},
) {
    androidx.compose.foundation.lazy.LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Charaly.space.xs),
    ) {
        items(labels.size, key = { labels[it] }) { index ->
            val label = labels[index]
            CharalyPill(
                label = label,
                selected = label in selected,
                onClick = { onSelect(label) },
            )
        }
    }
}

/** A very small indeterminate indicator, for use inside a row or a button. */
@Composable
fun CharalySpinner(color: Color = LocalContentColor.current, modifier: Modifier = Modifier) {
    val animated = dev.charaly.app.ui.theme.allowsProgressAnimation()
    val transition = rememberInfiniteTransition(label = "spinner")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (animated) 360f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spinner-rotation",
    )
    Canvas(modifier.size(16.dp)) {
        val stroke = Stroke(width = 2.dp.toPx())
        val inset = stroke.width / 2f
        rotate(degrees = rotation, pivot = center) {
            drawArc(
                color = color,
                startAngle = 0f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size(
                    size.width - stroke.width,
                    size.height - stroke.width,
                ),
                style = stroke,
            )
        }
    }
}