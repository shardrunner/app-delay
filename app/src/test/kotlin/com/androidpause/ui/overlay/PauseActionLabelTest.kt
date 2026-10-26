package com.androidpause.ui.overlay

import kotlin.test.Test
import kotlin.test.assertEquals

class PauseActionLabelTest {
    @Test
    fun `zero extension uses proceed label`() {
        assertEquals("Proceed", extendLabel(0, remainingSeconds = 0))
        assertEquals("Proceed in 0:05", extendLabel(0, remainingSeconds = 5))
    }

    @Test
    fun `positive extension describes its duration and shows the wait`() {
        assertEquals("Extend by 1 minute", extendLabel(60, remainingSeconds = 0))
        assertEquals("Extend in 2:05", extendLabel(60, remainingSeconds = 125))
    }

    @Test
    fun `time usage is shown in minutes and never exceeds the allowance`() {
        assertEquals("3 / 10 min", timeUsageText(227, 600))
        assertEquals("10 / 10 min", timeUsageText(615, 600))
    }

    @Test
    fun `statistics durations use minutes and hours`() {
        assertEquals("0 min", durationText(59_999))
        assertEquals("12 min", durationText(12 * 60_000L))
        assertEquals("1 h 5 min", durationText(65 * 60_000L))
    }
}
