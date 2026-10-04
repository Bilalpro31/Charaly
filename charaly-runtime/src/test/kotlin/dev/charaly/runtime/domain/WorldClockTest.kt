package dev.charaly.runtime.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The world clock is logical, not wall-clock. Time only moves when the runtime
 * says it does, which is what makes replay and tests deterministic.
 */
class WorldClockTest {

    @Test
    fun `start time is day one nine in the morning`() {
        assertEquals(StoryTime(1, 9, 0), StoryTime.START)
    }

    @Test
    fun `total minutes convert back and forth`() {
        val time = StoryTime(day = 3, hour = 14, minute = 30)
        // Day 1 starts the story, so it is minute zero.
        assertEquals(2 * 24 * 60 + 14 * 60 + 30, time.totalMinutes)
        assertEquals(time, StoryTime.fromTotalMinutes(time.totalMinutes))
        assertEquals(0L, StoryTime(1, 0, 0).totalMinutes)
    }

    @Test
    fun `advancing carries into the next day`() {
        val clock = WorldClock(StoryTime(1, 23, 30)).advance(StoryDuration.hours(1))
        assertEquals(StoryTime(2, 0, 30), clock.now)
    }

    @Test
    fun `advancing multiple days works`() {
        val clock = WorldClock(StoryTime(1, 0, 0)).advance(StoryDuration(60 * 24 * 3))
        assertEquals(StoryTime(4, 0, 0), clock.now)
    }

    @Test
    fun `zero advance is a no-op`() {
        val clock = WorldClock(StoryTime(2, 5, 5))
        assertEquals(clock, clock.advance(StoryDuration.ZERO))
    }

    @Test
    fun `time never moves backwards`() {
        val clock = WorldClock(StoryTime(2, 0, 0))
        val failure = runCatching { clock.advance(StoryDuration(-5)) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `advanceTo ignores a past time but takes the future`() {
        val clock = WorldClock(StoryTime(2, 0, 0))
        assertEquals(clock, clock.advanceTo(StoryTime(1, 0, 0)))
        assertEquals(StoryTime(2, 1, 0), clock.advanceTo(StoryTime(2, 1, 0)).now)
    }

    @Test
    fun `scheduleAfter resolves to a future time`() {
        val clock = WorldClock(StoryTime(1, 10, 0))
        assertEquals(StoryTime(1, 10, 30), clock.scheduleAfter(StoryDuration(30)))
    }

    @Test
    fun `scheduleAt normalises a past time into the future`() {
        val clock = WorldClock(StoryTime(1, 10, 0))
        assertEquals(StoryTime(1, 10, 1), clock.scheduleAt(StoryTime(1, 9, 0)))
        assertEquals(StoryTime(2, 0, 0), clock.scheduleAt(StoryTime(2, 0, 0)))
    }

    @Test
    fun `ordering comparisons work`() {
        val early = StoryTime(1, 0, 0)
        val late = StoryTime(1, 0, 1)
        assertTrue(early.isBefore(late))
        assertTrue(late.isAfter(early))
        assertTrue(early.isAtOrBefore(early))
        assertEquals(-1, early.compareTo(late))
    }

    @Test
    fun `minutes until and since are consistent`() {
        val now = StoryTime(1, 12, 0)
        val later = StoryTime(1, 13, 30)
        val clock = WorldClock(now)
        assertEquals(90, now.minutesUntil(later))
        assertEquals(90, clock.minutesUntil(later))
        assertEquals(90, clock.minutesSince(StoryTime(1, 10, 30)))
        assertEquals(0, clock.minutesSince(clock.now))
    }

    @Test
    fun `minusMinutes clamps at the start of the world`() {
        assertEquals(StoryTime(1, 0, 0), StoryTime(1, 0, 5).minusMinutes(50))
    }

    @Test
    fun `formatting is stable`() {
        assertEquals("Day 2, 07:05", StoryTime(2, 7, 5).format())
        assertEquals("07:05", StoryTime(2, 7, 5).formatClock())
    }

    @Test
    fun `invalid components are rejected`() {
        assertTrue(runCatching { StoryTime(0, 0, 0) }.isFailure)
        assertTrue(runCatching { StoryTime(1, 24, 0) }.isFailure)
        assertTrue(runCatching { StoryTime(1, 0, 60) }.isFailure)
        assertTrue(runCatching { StoryDuration(-1) }.isFailure)
    }

    @Test
    fun `duration helpers`() {
        assertEquals(StoryTime(1, 1, 0), StoryTime(1, 0, 0).plusMinutes(60))
        assertEquals(120, StoryDuration.hours(2).minutes)
        assertFalse(StoryDuration.ZERO.minutes > 0)
    }
}
