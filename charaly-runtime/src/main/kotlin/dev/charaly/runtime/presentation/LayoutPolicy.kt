package dev.charaly.runtime.presentation

/**
 * Layout decisions, decided before Compose measures anything.
 *
 * ## Why this is a plain data class on the runtime module
 *
 * The brief's tablet requirement is not "make the chat wider" - it is "do not simply
 * scale phone UI". That distinction is a *decision*, and decisions that can be wrong
 * should be testable on the JVM rather than discovered on a tablet.
 *
 * So the layout for a given width is computed here, rendered by the app layer, and
 * asserted below. The alternative - branching on `LocalConfiguration` inside a
 * composable - produces exactly the kind of "it looked fine on my phone" defect that
 * started this whole audit.
 */
data class LayoutPolicy(
    val widthDp: Int,
    /** Two-column card lists. */
    val cardColumns: Int,
    /** Whether the story screen shows a persistent right-hand context pane. */
    val storySplitPane: Boolean,
    /** The pane's share of the width, as a fraction. */
    val contextPaneFraction: Float,
    /** Whether the pack detail screen uses a split layout. */
    val packDetailSplit: Boolean,
    /** Whether navigation becomes a side rail instead of a bottom bar. */
    val navigationRail: Boolean,
) {
    companion object {
        /** The usual phone/tablet boundary; 840dp is the expanded-width one. */
        const val TABLET_BREAKPOINT = 600
        const val EXPANDED_BREAKPOINT = 840

        /**
         * The story screen needs real horizontal room before a second pane is useful:
         * a prose column squeezed to 380dp is worse reading than one full-width column.
         * 900dp is where both columns can hold a comfortable measure.
         */
        const val STORY_SPLIT_BREAKPOINT = 900

        /**
         * The narrowest screen the layout is designed for.
         *
         * A reported width below this is clamped rather than trusted: a transient zero
         * during a fold or a configuration change must not produce negative pane widths
         * and a crash at measure time.
         */
        const val MIN_WIDTH_DP = 280

        fun forWidth(widthDp: Int): LayoutPolicy {
            // Clamp first, then decide. A negative width cannot be laid out, and every
            // downstream number is derived from this one.
            val width = widthDp.coerceAtLeast(MIN_WIDTH_DP)
            val tablet = width >= TABLET_BREAKPOINT
            val expanded = width >= EXPANDED_BREAKPOINT
            return LayoutPolicy(
                widthDp = width,
                cardColumns = if (tablet) 2 else 1,
                storySplitPane = widthDp >= STORY_SPLIT_BREAKPOINT,
                // A wider screen gives the story more room and the context less, so the
                // conversation stays the largest thing on screen.
                contextPaneFraction = if (expanded) 0.3f else 0.34f,
                packDetailSplit = tablet,
                // A bottom bar under a split-pane story would fight the composer for the
                // same edge, so the shell moves to a rail once the panes appear.
                navigationRail = expanded,
            )
        }
    }

    /**
     * Splits a screen width into a conversation column and a context column.
     *
     * Both helpers clamp independently of how the policy was built, because the caller
     * passes the *screen* width while the policy may have been created from a different
     * one. A zero or negative width during a fold must not yield a negative pane.
     */
    private fun safeWidth(widthDp: Int): Int = widthDp.coerceAtLeast(MIN_WIDTH_DP)

    /** The width left for the conversation, given a total width. */
    fun storyWidthDp(totalWidthDp: Int): Int {
        val total = safeWidth(totalWidthDp)
        return if (storySplitPane) (total * (1f - contextPaneFraction)).toInt() else total
    }

    fun contextWidthDp(totalWidthDp: Int): Int {
        val total = safeWidth(totalWidthDp)
        return if (storySplitPane) total - storyWidthDp(total) else 0
    }
}