package dev.charaly.app.ui.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.charaly.app.R
import dev.charaly.app.ui.design.Charaly
import dev.charaly.app.ui.nav.Route
import dev.charaly.app.ui.theme.motionDuration
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
 *   phone    the V5 bar: five icons over a gradient, at the bottom edge
 *   tablet   a persistent rail on the leading edge
 * ```
 *
 * Never both. A rail *and* a bar on the same screen is the layout mistake this replaces:
 * two navigation surfaces competing for the same attention.
 *
 * ## V5's bar is icons-only
 *
 * Five destinations, each a 48dp target with a content description and no label. The words
 * are the screen's own vocabulary; the bar's job is to be found by thumb, and an icon row
 * with five labels under it is a legend, not a control. The Sessions item carries the blue
 * unread dot - the one colour on the bar that means "new for you".
 */
@Composable
fun CharalyScaffold(
    policy: LayoutPolicy,
    tab: Route.Tab?,
    onSelectTab: (Route.Tab) -> Unit,
    modifier: Modifier = Modifier,
    /** Drawn behind the content: the world's atmosphere. */
    atmosphere: (@Composable () -> Unit)? = null,
    /** Whether Sessions shows the blue unread dot. */
    sessionsUnread: Boolean = false,
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        atmosphere?.invoke()

        val rail = tab != null && policy.navigationRail

        Row(Modifier.fillMaxSize()) {
            if (rail) {
                CharalyNavigationRail(
                    selected = tab,
                    onSelect = onSelectTab,
                    sessionsUnread = sessionsUnread,
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

        if (tab != null && !rail) {
            CharalyBottomNav(
                selected = tab,
                onSelect = onSelectTab,
                sessionsUnread = sessionsUnread,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            )
        }

        snackbarHost()
    }
}

/**
 * The V5 bottom bar.
 *
 * ## Why it is a gradient and not a solid bar
 *
 * A solid bar draws a horizon across the artwork; a gradient lets the content dissolve
 * into the bar's floor so the bar reads as *the bottom of the screen* rather than as a
 * control laid over it. Icons sit clear of it, on 48dp targets.
 *
 * ## The unread dot
 *
 * Sessions carries V5's blue dot when something is new. It is the only colour on the bar
 * that is not white-on-dark, and it is reserved for "new for you" everywhere in the app -
 * so its meaning is learned once.
 */
@Composable
private fun CharalyBottomNav(
    selected: Route.Tab,
    onSelect: (Route.Tab) -> Unit,
    modifier: Modifier = Modifier,
    sessionsUnread: Boolean = false,
) {
    Column(modifier = modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0x000F0F11),
                            Charaly.surface.base,
                        ),
                    ),
                ),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Charaly.surface.base)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = Charaly.space.xl, vertical = Charaly.space.xs),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Route.Tab.entries.forEach { tab ->
                NavIcon(
                    tab = tab,
                    isSelected = tab == selected,
                    onClick = { onSelect(tab) },
                    unread = tab == Route.Tab.SESSIONS && sessionsUnread,
                )
            }
        }
    }
}

/** The persistent rail, for expanded widths. */
@Composable
private fun CharalyNavigationRail(
    selected: Route.Tab?,
    onSelect: (Route.Tab) -> Unit,
    sessionsUnread: Boolean = false,
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
        Route.Tab.entries.forEach { tab ->
            RailItem(
                tab = tab,
                isSelected = selected == tab,
                onClick = { onSelect(tab) },
                unread = tab == Route.Tab.SESSIONS && sessionsUnread,
            )
        }
    }
}

/** One icon destination in the bar. 48dp, described, no label. */
@Composable
private fun NavIcon(
    tab: Route.Tab,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    unread: Boolean = false,
) {
    val tint by animateColorAsState(
        targetValue = if (isSelected) Charaly.ink.primary else Color(0xFFCFCFD6),
        animationSpec = tween(motionDuration(Charaly.timing.standard)),
        label = "nav-tint",
    )
    val description = stringResource(tab.labelRes())

    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = iconFor(tab, filled = isSelected),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(if (isSelected) 28.dp else 26.dp),
        )
        if (unread) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 6.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Charaly.signal.blue),
            )
        }
    }
}

@Composable
private fun RailItem(
    tab: Route.Tab,
    isSelected: Boolean,
    onClick: () -> Unit,
    unread: Boolean = false,
) {
    val tint by animateColorAsState(
        targetValue = if (isSelected) Charaly.ink.primary else Charaly.ink.muted,
        animationSpec = tween(motionDuration(Charaly.timing.standard)),
        label = "rail-tint",
    )
    val lift by animateColorAsState(
        targetValue = if (isSelected) {
            Charaly.ink.primary.copy(alpha = 0.10f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(motionDuration(Charaly.timing.standard)),
        label = "rail-lift",
    )
    val description = stringResource(tab.labelRes())

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(lift)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description }
            .padding(vertical = Charaly.space.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = iconFor(tab, filled = isSelected),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp),
            )
            if (unread) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Charaly.signal.blue),
                )
            }
        }
        Text(
            text = description,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

private fun Route.Tab.labelRes(): Int = when (this) {
    Route.Tab.HOME -> R.string.nav_home
    Route.Tab.SESSIONS -> R.string.nav_sessions
    Route.Tab.CREATE -> R.string.nav_create
    Route.Tab.LIBRARY -> R.string.nav_library
    Route.Tab.ME -> R.string.nav_me
}

/**
 * The bar's icons.
 *
 * V5 fills the icon for the two "self" destinations - Home and Me - when they are active,
 * and outlines the rest. Create is a plus in a ring, unmistakably "make something".
 */
private fun iconFor(tab: Route.Tab, filled: Boolean): ImageVector = when (tab) {
    Route.Tab.HOME -> if (filled) Icons.Filled.Home else Icons.Outlined.Home
    Route.Tab.SESSIONS -> Icons.AutoMirrored.Outlined.Chat
    Route.Tab.CREATE -> Icons.Filled.Add
    Route.Tab.LIBRARY -> Icons.AutoMirrored.Outlined.MenuBook
    Route.Tab.ME -> if (filled) Icons.Filled.Person else Icons.Outlined.Person
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
 * Bottom padding that clears the bottom navigation bar.
 *
 * A screen needs this only if its last element would otherwise sit *under* the bar. The
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
    androidx.compose.animation.scaleIn(
        initialScale = 0.96f,
        animationSpec = tween(motionDuration(Charaly.timing.standard)),
    ) + androidx.compose.animation.fadeIn(
        animationSpec = tween(motionDuration(Charaly.timing.quick)),
    )

@Composable
internal fun sheetExit(): androidx.compose.animation.ExitTransition =
    androidx.compose.animation.scaleOut(
        targetScale = 0.98f,
        animationSpec = tween(motionDuration(Charaly.timing.quick)),
    ) + androidx.compose.animation.fadeOut(
        animationSpec = tween(motionDuration(Charaly.timing.quick)),
    )

/** Optical spacing between major sections on a scrolling screen. */
@Composable
internal fun SectionGap(height: androidx.compose.ui.unit.Dp = Charaly.space.xl) {
    Spacer(Modifier.height(height))
}
