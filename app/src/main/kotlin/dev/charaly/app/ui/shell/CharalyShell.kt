package dev.charaly.app.ui.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.charaly.app.ui.design.Charaly
import dev.charaly.app.ui.design.CharalyShapes
import dev.charaly.app.ui.theme.motionDuration
import dev.charaly.runtime.presentation.CharalyDestination
import dev.charaly.runtime.presentation.LayoutPolicy

/**
 * THE SHELL.
 *
 * ## One scrolling owner, one navigation surface
 *
 * The shell does three things and nothing else: it holds the system insets, it draws the
 * navigation, and it hosts the current screen. Every screen inside it is free to scroll
 * without coordinating with a bar, because the bar's height is a fixed input rather than
 * something each screen has to measure.
 *
 * ## Phone and tablet are intentionally different
 *
 * ```
 *   phone    a floating pill, inset from the edges, over the content
 *   tablet   a persistent rail on the leading edge
 * ```
 *
 * Never both. A rail *and* a bar on the same screen is the layout mistake this replaces:
 * two navigation surfaces competing for the same attention, with the bar sitting directly
 * above the composer.
 *
 * The choice is made by [LayoutPolicy], which is unit tested on the JVM rather than
 * branching on `LocalConfiguration` inside a composable - so "a tablet gets a rail" is a
 * fact a test can assert rather than a detail to rediscover on hardware.
 */
@Composable
fun CharalyScaffold(
    policy: LayoutPolicy,
    destination: CharalyDestination?,
    onSelect: (CharalyDestination) -> Unit,
    modifier: Modifier = Modifier,
    /** Drawn behind the content: the world's atmosphere. */
    atmosphere: (@Composable () -> Unit)? = null,
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        atmosphere?.invoke()

        val rail = destination != null && policy.navigationRail

        Row(Modifier.fillMaxSize()) {
            if (rail) {
                CharalyNavigationRail(
                    selected = destination,
                    onSelect = onSelect,
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                content(Modifier.fillMaxSize())
            }
        }

        if (destination != null && !rail) {
            CharalyFloatingNav(
                selected = destination,
                onSelect = onSelect,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(Charaly.space.md),
            )
        }

        snackbarHost()
    }
}

/**
 * The floating navigation pill.
 *
 * ## Why it floats
 *
 * A bar pinned to the bottom edge with a solid fill says "this is a utility". A pill
 * inset from the edges, over the content, says "you are looking at something" - and it
 * keeps the artwork beneath it visible, which is the difference between a cinema poster
 * with a caption and a form with a footer.
 *
 * Labels rather than glyphs alone: four destinations are ambiguous as icons, and the words
 * are the product's vocabulary.
 */
@Composable
private fun CharalyFloatingNav(
    selected: CharalyDestination?,
    onSelect: (CharalyDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(Charaly.surface.overlay)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CharalyDestination.PRIMARY.forEach { destination ->
            NavItem(
                destination = destination,
                isSelected = selected == destination,
                onClick = { onSelect(destination) },
                modifier = Modifier.width(74.dp),
            )
        }
    }
}

/** The persistent rail, for expanded widths. */
@Composable
private fun CharalyNavigationRail(
    selected: CharalyDestination?,
    onSelect: (CharalyDestination) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(104.dp)
            .background(Charaly.surface.base)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(
                vertical = Charaly.space.lg,
                horizontal = Charaly.space.xs,
            ),
        verticalArrangement = Arrangement.spacedBy(Charaly.space.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CharalyDestination.PRIMARY.forEach { destination ->
            RailItem(
                destination = destination,
                isSelected = selected == destination,
                onClick = { onSelect(destination) },
            )
        }
    }
}

@Composable
private fun NavItem(
    destination: CharalyDestination,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint by animateColorAsState(
        targetValue = if (isSelected) Charaly.ink.primary else Charaly.ink.muted,
        animationSpec = tween(motionDuration(Charaly.timing.standard)),
        label = "nav-tint",
    )

    Column(
        modifier = modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = destination.label }
            .padding(vertical = Charaly.space.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(38.dp)) {
            // Selection is carried by *shape* as well as colour: a lit disc behind the icon.
            // Colour alone would leave the destination unreadable to a user who cannot
            // tell the accent from the muted ink.
            if (isSelected) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Charaly.atmosphere.accent.copy(alpha = 0.16f)),
                )
            }
            Icon(
                imageVector = iconFor(destination),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(if (isSelected) 20.dp else 19.dp),
            )
        }
        Text(
            text = destination.label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun RailItem(
    destination: CharalyDestination,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val tint by animateColorAsState(
        targetValue = if (isSelected) Charaly.ink.primary else Charaly.ink.muted,
        animationSpec = tween(motionDuration(Charaly.timing.standard)),
        label = "rail-tint",
    )
    val lift by animateColorAsState(
        targetValue = if (isSelected) {
            Charaly.atmosphere.accent.copy(alpha = 0.12f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(motionDuration(Charaly.timing.standard)),
        label = "rail-lift",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CharalyShapes.soft)
            .background(lift)
            .clickable(onClick = onClick)
            .semantics { contentDescription = destination.label }
            .padding(vertical = Charaly.space.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(iconFor(destination), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Text(
            text = destination.label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

private fun iconFor(destination: CharalyDestination): ImageVector = when (destination) {
    CharalyDestination.HOME -> Icons.Outlined.Home
    CharalyDestination.WORLDS -> Icons.Outlined.Public
    CharalyDestination.CHAT -> Icons.AutoMirrored.Outlined.Chat
    CharalyDestination.LIBRARY -> Icons.AutoMirrored.Outlined.MenuBook
}

/**
 * A screen's vertical padding, including the status bar.
 *
 * Applied by every top-level screen so the safe area is one decision rather than five
 * slightly different ones.
 */
@Composable
fun Modifier.statusBarInset(): Modifier =
    this.windowInsetsPadding(WindowInsets.statusBars)

/**
 * Bottom padding that clears the floating navigation pill.
 *
 * A screen needs this only if its last element would otherwise sit *under* the pill. The
 * stage does not: its composer is already at the bottom edge, so it hosts no navigation
 * and needs no such padding.
 */
@Composable
fun Modifier.navigationInset(policy: LayoutPolicy): Modifier = if (policy.navigationRail) {
    this
} else {
    this.padding(bottom = 84.dp)
}

/**
 * The scale-and-fade pair used when a sheet expands.
 *
 * A sheet that only slid looks like a dialog; one that grows very slightly from 96% reads
 * as a *layer over* the scene, which is what it is. The durations come from the motion
 * policy, so "Reduce motion" removes the growth as well as the travel.
 */
@Composable
internal fun sheetEnter(): androidx.compose.animation.EnterTransition =
    scaleIn(
        initialScale = 0.96f,
        animationSpec = tween(motionDuration(Charaly.timing.standard)),
    ) + fadeIn(animationSpec = tween(motionDuration(Charaly.timing.quick)))

@Composable
internal fun sheetExit(): androidx.compose.animation.ExitTransition =
    scaleOut(
        targetScale = 0.98f,
        animationSpec = tween(motionDuration(Charaly.timing.quick)),
    ) + fadeOut(animationSpec = tween(motionDuration(Charaly.timing.quick)))

/** Optical spacing between major sections on a scrolling screen. */
@Composable
internal fun SectionGap(height: androidx.compose.ui.unit.Dp = Charaly.space.xl) {
    Spacer(Modifier.height(height))
}