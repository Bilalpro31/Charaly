package dev.charaly.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.charaly.app.ui.theme.Charaly

/**
 * Layout responsiveness.
 *
 * The rule is the one every consumer app ends up with: never stretch, cap the measure.
 * A phone gets one column and a full-bleed gutter; a tablet gets a wider gutter, a
 * capped content width and two columns where a card benefits from it.
 *
 * Deliberately not "stretch the phone layout sideways": on a tablet the pack library
 * becomes two columns of the *same* cards, not one enormous row.
 */
object CharalyLayout {

    /** 600dp is the usual phone/tablet boundary; 840dp is the expanded-width one. */
    val TABLET_BREAKPOINT: Dp = 600.dp
    val EXPANDED_BREAKPOINT: Dp = 840.dp

    /** A comfortable measure for prose and for card columns. */
    val CONTENT_MAX_WIDTH: Dp = 760.dp

    @Composable
    fun widthDp(): Int = with(LocalDensity.current) {
        LocalConfiguration.current.screenWidthDp
    }

    @Composable
    fun isTablet(): Boolean = widthDp() >= TABLET_BREAKPOINT.value.toInt()

    @Composable
    fun isExpanded(): Boolean = widthDp() >= EXPANDED_BREAKPOINT.value.toInt()

    /** Page gutter: wider on a tablet so the content does not hug the bezel. */
    @Composable
    fun gutter(): Dp =
        if (isTablet()) Charaly.tokens.spacing.xl else Charaly.tokens.spacing.gutter

    /**
     * Caps the content width and centres it.
     *
     * Applied to the scrolling body of every top-level screen, so a phone and a
     * tablet render the *same* content at a sane measure.
     */
    @Composable
    fun contentWidth(max: Dp = CONTENT_MAX_WIDTH): Modifier {
        val tablet = isTablet()
        return if (!tablet) {
            Modifier.fillMaxWidth()
        } else {
            Modifier
                .fillMaxWidth()
                .width(max)
        }
    }

    /** Grid cells for a card list: one column on a phone, two on a tablet. */
    @Composable
    fun cardCells(): GridCells = if (isTablet()) GridCells.Fixed(2) else GridCells.Fixed(1)

    @Composable
    fun gridSpacing(): Dp = if (isTablet()) 16.dp else 12.dp

    /** Content padding for a scrolling screen using the responsive gutter. */
    @Composable
    fun contentPadding(top: Dp = 0.dp, bottom: Dp = 32.dp): PaddingValues =
        PaddingValues(
            start = gutter(),
            end = gutter(),
            top = top,
            bottom = bottom,
        )
}

/**
 * A responsive grid of cards.
 *
 * Extracted so the library, the sessions list and any future card collection share
 * one breakpoint behaviour instead of three copies of it.
 */
@Composable
fun <T> CardGrid(
    items: List<T>,
    spacing: Dp = CharalyLayout.gridSpacing(),
    key: (T) -> Any = { it.hashCode() },
    content: @Composable (T) -> Unit,
) {
    LazyVerticalGrid(
        columns = CharalyLayout.cardCells(),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalArrangement = Arrangement.spacedBy(spacing),
        contentPadding = CharalyLayout.contentPadding(bottom = 40.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(items = items, key = key) { item ->
            content(item)
        }
    }
}
/**
 * Centres and caps a screen's content on a tablet.
 *
 * Applied to scrolling bodies so a wide screen shows a comfortable measure instead of
 * one enormous column of stretched text.
 */
@Composable
fun Modifier.devCenteredContent(max: androidx.compose.ui.unit.Dp = CharalyLayout.CONTENT_MAX_WIDTH): Modifier {
    val tablet = CharalyLayout.isTablet()
    return if (tablet) {
        this.fillMaxWidth().width(max)
    } else {
        this
    }
}
