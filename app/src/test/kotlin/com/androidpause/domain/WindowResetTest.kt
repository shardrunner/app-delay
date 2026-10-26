package com.androidpause.domain

import com.androidpause.domain.model.ScheduleConfig
import com.androidpause.domain.model.ScheduleRuntime
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** T030 — UsageAccounting.resetExpiredWindows reset-on-new-occurrence incl. midnight window. */
class WindowResetTest {
    private fun schedule(
        start: LocalTime,
        end: LocalTime,
    ) = ScheduleConfig(
        id = 1,
        enabled = true,
        name = "s",
        windowStart = start,
        windowEnd = end,
        sharedBudgetSeconds = 600,
        pauseDelaySeconds = 15,
        afterPauseBudgetSeconds = 60,
        message = "pause",
        packages = setOf("com.a"),
    )

    @Test
    fun `active same-day window with new occurrence resets usage and pause anchor`() {
        val schedule = schedule(LocalTime.of(9, 0), LocalTime.of(17, 0))
        val now = LocalDateTime.of(2026, 5, 31, 9, 5).atZone(ZoneOffset.UTC)
        val old =
            mapOf(
                1L to
                    ScheduleRuntime(
                        1,
                        windowOccurrenceKey = now.toLocalDate().minusDays(1).toEpochDay(),
                        consumedBudgetMillis = 120_000L,
                        pauseClearedAtMillis = 9999L,
                    ),
            )

        val updated = UsageAccounting.resetExpiredWindows(now, listOf(schedule), old)

        assertEquals(0L, updated.getValue(1L).consumedBudgetMillis)
        assertEquals(
            ScheduleWindowBoundaries.windowOccurrenceKey(schedule, now),
            updated.getValue(1L).windowOccurrenceKey,
        )
        assertNull(updated.getValue(1L).pauseClearedAtMillis)
    }

    @Test
    fun `midnight window after midnight resets to prior day start key`() {
        val schedule = schedule(LocalTime.of(23, 0), LocalTime.of(2, 0))
        val now = LocalDateTime.of(2026, 6, 1, 1, 30).atZone(ZoneOffset.UTC)
        val old =
            mapOf(
                1L to
                    ScheduleRuntime(
                        1,
                        windowOccurrenceKey = now.toLocalDate().minusDays(2).toEpochDay(),
                        consumedBudgetMillis = 180_000L,
                        pauseClearedAtMillis = 1234L,
                    ),
            )

        val updated = UsageAccounting.resetExpiredWindows(now, listOf(schedule), old)

        assertEquals(0L, updated.getValue(1L).consumedBudgetMillis)
        assertEquals(now.toLocalDate().minusDays(1).toEpochDay(), updated.getValue(1L).windowOccurrenceKey)
        assertNull(updated.getValue(1L).pauseClearedAtMillis)
    }

    @Test
    fun `inactive schedule keeps runtime until next occurrence starts`() {
        val schedule = schedule(LocalTime.of(9, 0), LocalTime.of(17, 0))
        val now = LocalDateTime.of(2026, 5, 31, 8, 30).atZone(ZoneOffset.UTC)
        val start =
            mapOf(
                1L to
                    ScheduleRuntime(
                        1,
                        windowOccurrenceKey = now.toLocalDate().minusDays(1).toEpochDay(),
                        consumedBudgetMillis = 120_000L,
                        pauseClearedAtMillis = 9999L,
                    ),
            )

        val updated = UsageAccounting.resetExpiredWindows(now, listOf(schedule), start)

        assertEquals(start, updated)
    }
}
