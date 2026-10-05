package dev.charaly.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import dev.charaly.app.ui.CharalyLayout
import dev.charaly.app.ui.theme.Charaly

/**
 * Multi-column card layout for cards that live *inside* a vertical lazy list.
 *
 * ## Why this exists
 *
 * A `LazyVerticalGrid` - or any other vertically scrollable layout - nested inside a
 * `LazyColumn`'s `item {}` crashes at measure time:
 *
 * ```
 * IllegalStateException: Vertically scrollable component was measured with an
 * infinity maximum height constraints, which is disallowed.
 * ```
 *
 * `LazyColumn` measures its items with `maxHeight = Infinity`, so a nested vertical
 * scroller has no bounded height to lay out against. This is what made the
 * "Story Packs" tab crash: the two-column branch only existed on wide layouts, so the
 * bug appeared on tablets and not on phones.
 *
 * The rule this file exists to enforce: **one vertical scroll owner per screen.**
 *
 * ## The approach
 *
 * Chunk the list into rows of `columns` and emit each row as an ordinary
 * non-scrolling item of the outer list. Only `columns` cards are composed at a time,
 * so this composes as cheaply as a real grid, keeps the outer list as the single
 * scroll owner, and cannot measure against an infinite height.
 */
object ResponsiveRows {

    /**
     * Splits [items] into consecutive rows of at most [columns] entries.
     *
     * The last row is short when the count is not a multiple of [columns]; a
     * non-positive [columns] is coerced to one so a bad width can never divide by
     * zero or silently drop cards.
     */
    fun <T> chunk(items: List<T>, columns: Int): List<List<T>> =
        if (items.isEmpty()) emptyList() else items.chunked(columns.coerceAtLeast(1))
}

/** How many columns of cards a layout of this width gets. */
@Composable
fun cardColumnCount(): Int = if (CharalyLayout.isTablet()) 2 else 1

/**
 * Emits [items] as rows of at most [columns] cards.
 *
 * Call this from inside a `LazyColumn`'s `item {}` - never nest another scrollable
 * layout inside that list. [keyOf] must be stable across recompositions so the outer
 * list preserves scroll position and item identity.
 */
@Composable
fun <T> ChunkedCardRows(
    items: List<T>,
    modifier: Modifier = Modifier,
    columns: Int = cardColumnCount(),
    spacing: Dp = Charaly.tokens.spacing.sm,
    keyOf: (T) -> Any = { it.hashCode() },
    content: @Composable (T) -> Unit,
) {
    val rows = ResponsiveRows.chunk(items, columns)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing),
            ) {
                row.forEach { item ->
                    CardCell(content = { content(item) })
                }
                // A short final row keeps its cards the same width as the rows above,
                // rather than stretching the last card across the whole row.
                PlaceholderCells(count = columns - row.size)
            }
        }
    }
}

/** One card's worth of width inside a [Row]. */
@Composable
private fun RowScope.CardCell(content: @Composable () -> Unit) {
    Column(modifier = Modifier.weight(1f)) {
        content()
    }
}

/** Invisible cells that keep the final row aligned with the rows above it. */
@Composable
private fun RowScope.PlaceholderCells(count: Int) {
    repeat(count.coerceAtLeast(0)) {
        Column(modifier = Modifier.weight(1f)) {}
    }
}