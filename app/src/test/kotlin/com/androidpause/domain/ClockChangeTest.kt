package com.androidpause.domain

import com.androidpause.domain.model.PauseOutcome
import com.androidpause.domain.model.ScheduleConfig
import com.androidpause.domain.model.ScheduleRuntime
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** T042 — local-time evaluation stays correct after manual clock / timezone changes (FR-016). */
class ClockChangeTest {
    private fun schedule(
        start: LocalTime = LocalTime.of(9, 0),
        end: LocalTime = LocalTime.of(17, 0),
    ) = ScheduleConfig(
        id = 1,
        enabled = true,
        name = "clock",
        windowStart = start,
        windowEnd = end,
        sharedBudgetSeconds = 0,
        pauseDelaySeconds = 15,
        afterPauseBudgetSeconds = 0,
        message = "Take a break",
        packages = setOf("com.example.covered"),
    )

    @Test
    fun `timezone change is reflected by the new local time immediately`() {
        val schedule = schedule()
        val beforeShift = LocalDateTime.of(2026, 6, 1, 8, 30).atZone(ZoneOffset.UTC)
        val afterShift = LocalDateTime.of(2026, 6, 1, 9, 30).atZone(ZoneOffset.UTC)
        val runtime =
            mapOf(
                1L to
                    ScheduleRuntime(
                        scheduleId = 1,
                        consumedBudgetMillis = 0,
                        pauseClearedAtMillis = null,
                        windowOccurrenceKey = ScheduleWindowBoundaries.windowOccurrenceKey(schedule, afterShift),
                    ),
            )

        assertEquals(
            PauseOutcome.Allow,
            RulesEngine.evaluate(
                pkg = "com.example.covered",
                now = beforeShift,
                schedules = listOf(schedule),
                runtime = runtime,
            ),
        )

        val afterOutcome =
            RulesEngine.evaluate(
                pkg = "com.example.covered",
                now = afterShift,
                schedules = listOf(schedule),
                runtime = runtime,
            )
        val pause = assertIs<PauseOutcome.Pause>(afterOutcome)
        assertEquals(15, pause.delaySeconds)
        assertEquals("Take a break", pause.info.message)
    }

    @Test
    fun `clock rollback to another local occurrence resets runtime against the new local date`() {
        val schedule = schedule(start = LocalTime.of(23, 0), end = LocalTime.of(2, 0))
        val afterClockChange = LocalDateTime.of(2026, 6, 1, 0, 30).atZone(ZoneOffset.UTC)
        val staleRuntime =
            mapOf(
                1L to
                    ScheduleRuntime(
                        scheduleId = 1,
                        consumedBudgetMillis = 120_000L,
                        pauseClearedAtMillis = 9_999L,
                        windowOccurrenceKey =
                            LocalDateTime
                                .of(2026, 6, 2, 0, 30)
                                .toLocalDate()
                                .minusDays(1)
                                .toEpochDay(),
                    ),
            )

        val updated = UsageAccounting.resetExpiredWindows(afterClockChange, listOf(schedule), staleRuntime)

        assertEquals(0L, updated.getValue(1L).consumedBudgetMillis)
        assertEquals(
            ScheduleWindowBoundaries.windowOccurrenceKey(schedule, afterClockChange),
            updated.getValue(1L).windowOccurrenceKey,
        )
        assertNull(updated.getValue(1L).pauseClearedAtMillis)
    }
}
