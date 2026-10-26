package com.androidpause.enforcement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CountdownBubbleLabelTest {
    private fun label(
        remainingMillis: Long?,
        lastMinuteOnly: Boolean = false,
        openings: Int? = null,
    ) = countdownBubbleState(remainingMillis, lastMinuteOnly, openings)?.label

    private fun level(
        remainingMillis: Long?,
        openings: Int? = null,
    ) = countdownBubbleState(remainingMillis, false, openings)?.level

    @Test
    fun `full countdown shows rounded-up minutes above one minute`() {
        assertEquals("2m", label(61_001))
        assertEquals("2m", label(120_000))
        assertEquals("3m", label(120_001))
        assertEquals("120m", label(7_200_000))
    }

    @Test
    fun `final minute shows rounded-up seconds`() {
        assertEquals("1:00", label(60_000))
        assertEquals("0:01", label(1))
    }

    @Test
    fun `openings are shown next to the countdown`() {
        assertEquals("3x · 2m", label(61_001, openings = 3))
        assertEquals("3x · 1:00", label(60_000, openings = 3))
    }

    @Test
    fun `opening-only rules show openings without a countdown`() {
        assertEquals("2x", label(null, openings = 2))
        assertNull(label(null))
    }

    @Test
    fun `last minute mode hides everything until threshold`() {
        assertNull(label(60_001, lastMinuteOnly = true, openings = 3))
        assertNull(label(null, lastMinuteOnly = true, openings = 0))
        assertEquals("3x · 1:00", label(60_000, lastMinuteOnly = true, openings = 3))
    }

    @Test
    fun `reached deadlines hide the bubble`() {
        assertNull(label(0, openings = 3))
        assertNull(label(-1))
    }

    @Test
    fun `warning levels escalate with the last opening and the final minutes`() {
        assertEquals(BubbleLevel.NORMAL, level(300_001, openings = 2))
        assertEquals(BubbleLevel.WARNING, level(300_000))
        assertEquals(BubbleLevel.WARNING, level(null, openings = 1))
        assertEquals(BubbleLevel.CRITICAL, level(60_000, openings = 2))
        assertEquals(BubbleLevel.CRITICAL, level(600_000, openings = 0))
    }

    @Test
    fun `next update lands when the label changes`() {
        assertEquals(1_001L, countdownBubbleNextUpdateMillis(61_001, false))
        assertEquals(60_000L, countdownBubbleNextUpdateMillis(120_000, false))
        assertEquals(1L, countdownBubbleNextUpdateMillis(120_001, false))
        assertEquals(1_000L, countdownBubbleNextUpdateMillis(60_000, false))
        assertEquals(500L, countdownBubbleNextUpdateMillis(59_500, false))
        assertEquals(1L, countdownBubbleNextUpdateMillis(1, false))
    }

    @Test
    fun `hidden bubble sleeps until the final minute`() {
        assertEquals(600_000L, countdownBubbleNextUpdateMillis(660_000, true))
        assertEquals(1_000L, countdownBubbleNextUpdateMillis(60_000, true))
    }
}
