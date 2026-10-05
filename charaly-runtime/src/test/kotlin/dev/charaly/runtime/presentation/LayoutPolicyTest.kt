package dev.charaly.runtime.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tablet requirement is "do not simply scale phone UI", which is a decision, not a
 * colour. Decisions that can be wrong belong in a test rather than in a composable.
 */
class LayoutPolicyTest {

    @Test
    fun `a phone gets one column and no split pane`() {
        val phone = LayoutPolicy.forWidth(411)
        assertEquals(1, phone.cardColumns)
        assertFalse(phone.storySplitPane)
        assertEquals(0, phone.contextWidthDp(411))
    }

    @Test
    fun `a tablet gets two card columns but not a squeezed conversation`() {
        val tablet = LayoutPolicy.forWidth(800)
        assertEquals(2, tablet.cardColumns)
        assertTrue(tablet.packDetailSplit)
        // 800dp with two panes would leave an unreadable prose column, so the split pane
        // waits for real room.
        assertFalse(tablet.storySplitPane)
    }

    @Test
    fun `a wide tablet gets the story plus a persistent world pane`() {
        val tablet = LayoutPolicy.forWidth(1280)
        assertTrue(tablet.storySplitPane)
        val story = tablet.storyWidthDp(1280)
        val context = tablet.contextWidthDp(1280)
        assertTrue("the conversation must stay the largest element", story > context)
        assertEquals(1280, story + context)
    }

    @Test
    fun `the two pane widths always sum to the whole screen`() {
        listOf(900, 1000, 1280, 1600, 2000).forEach { width ->
            val policy = LayoutPolicy.forWidth(width)
            assertEquals(
                "panes must exactly fill $width dp",
                width,
                policy.storyWidthDp(width) + policy.contextWidthDp(width),
            )
        }
    }

    @Test
    fun `the context pane never steals more than a third of the screen`() {
        listOf(900, 1280, 2560).forEach { width ->
            val policy = LayoutPolicy.forWidth(width)
            assertTrue(
                "context pane is too wide at $width dp",
                policy.contextWidthDp(width) <= width / 3,
            )
        }
    }

    @Test
    fun `the split pane only appears where prose stays readable`() {
        // The whole point: not scaling phone UI, and not squeezing it either.
        val narrow = LayoutPolicy.forWidth(LayoutPolicy.STORY_SPLIT_BREAKPOINT - 1)
        val wide = LayoutPolicy.forWidth(LayoutPolicy.STORY_SPLIT_BREAKPOINT)
        assertFalse(narrow.storySplitPane)
        assertTrue(wide.storySplitPane)
        assertTrue(
            "even at the threshold the story column must be readable",
            wide.storyWidthDp(LayoutPolicy.STORY_SPLIT_BREAKPOINT) >= 600,
        )
    }

    @Test
    fun `an expanded layout uses a navigation rail instead of a bottom bar`() {
        // A bottom bar under a split story would fight the composer for the same edge.
        assertTrue(LayoutPolicy.forWidth(900).navigationRail)
        assertFalse(LayoutPolicy.forWidth(800).navigationRail)
    }

    @Test
    fun `card columns never exceed two, so a card is never unreadably narrow`() {
        listOf(320, 411, 600, 800, 1280, 2560).forEach { width ->
            assertTrue(
                "too many columns at $width dp",
                LayoutPolicy.forWidth(width).cardColumns in 1..2,
            )
        }
    }

    @Test
    fun `layout is monotonic - a wider screen never loses a column`() {
        var previous = 0
        (320..2000 step 40).forEach { width ->
            val columns = LayoutPolicy.forWidth(width).cardColumns
            assertTrue("columns went backwards at $width dp", columns >= previous)
            previous = columns
        }
    }

    @Test
    fun `a degenerate width is clamped rather than laid out`() {
        // A transient zero or negative width during a fold or configuration change must
        // not produce negative pane widths.
        listOf(0, 1, -10).forEach { width ->
            val policy = LayoutPolicy.forWidth(width)
            assertTrue(policy.cardColumns >= 1)
            assertTrue(policy.contextPaneFraction > 0f)
            assertTrue(
                "story width must never be negative (width=$width)",
                policy.storyWidthDp(width) > 0,
            )
            assertTrue(
                "context width must never be negative (width=$width)",
                policy.contextWidthDp(width) >= 0,
            )
        }
    }

    @Test
    fun `a clamped width still lays out as a phone`() {
        val clamped = LayoutPolicy.forWidth(0)
        assertEquals(LayoutPolicy.MIN_WIDTH_DP, clamped.widthDp)
        assertEquals(1, clamped.cardColumns)
        assertFalse(clamped.storySplitPane)
    }
}