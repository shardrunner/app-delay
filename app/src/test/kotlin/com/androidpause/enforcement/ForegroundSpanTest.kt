package com.androidpause.enforcement

import kotlin.test.Test
import kotlin.test.assertEquals

class ForegroundSpanTest {
    @Test
    fun `forward and backward wall adjustments preserve foreground time`() {
        for (adjustment in listOf(-3_600_000L, 3_600_000L)) {
            val wallStart = 10_000_000L
            val beforeChange = ForegroundSpan(1_000L, wallStart, wallStart + 120_000L)
            assertEquals(20_000L, beforeChange.usageMillis(21_000L))
            assertEquals(40_000L, beforeChange.remainingMillis(60_000L, 21_000L))

            // Time-change reevaluation closes the old span and maps the new wall schedule afresh.
            val newWall = wallStart + 20_000L + adjustment
            val afterChange = ForegroundSpan(21_000L, newWall, newWall + 120_000L)
            assertEquals(30_000L, beforeChange.usageMillis(21_000L) + afterChange.usageMillis(31_000L))
        }
    }

    @Test
    fun `ordinary boundary clips delayed delivery in the monotonic clock domain`() {
        val span = ForegroundSpan(500L, 10_000L, 70_000L)
        assertEquals(30_000L, span.usageMillis(30_500L))
        assertEquals(60_000L, span.usageMillis(60_500L))
        assertEquals(60_000L, span.usageMillis(90_500L))
    }

    @Test
    fun `no boundary counts full span and warnings expire without negative remaining`() {
        val span = ForegroundSpan(500L, 10_000L, null)
        assertEquals(90_000L, span.usageMillis(90_500L))
        assertEquals(0L, span.remainingMillis(60_000L, 90_500L))
    }
}
