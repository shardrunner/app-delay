package com.androidpause.domain

import com.androidpause.domain.model.ScheduleConfig
import com.androidpause.domain.model.ScheduleRuntime
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** T029/T039 — UsageAccounting.applyForegroundSpan combined summing without double count. */
class UsageAccountingTest {
    private val now = LocalDateTime.of(2026, 5, 31, 10, 0).atZone(ZoneOffset.UTC)

    private fun schedule(
        id: Long = 1,
        packages: Set<String> = setOf("com.a", "com.b"),
        enabled: Boolean = true,
        start: LocalTime = LocalTime.of(9, 0),
        end: LocalTime = LocalTime.of(17, 0),
    ) = ScheduleConfig(
        id = id,
        enabled = enabled,
        name = "s$id",
        windowStart = start,
        windowEnd = end,
        sharedBudgetSeconds = 600,
        pauseDelaySeconds = 15,
        afterPauseBudgetSeconds = 60,
        message = "pause$id",
        packages = packages,
    )

    @Test
    fun `finished spans across covered apps sum into one shared budget`() {
        val schedule = schedule()
        val occurrenceKey = ScheduleWindowBoundaries.windowOccurrenceKey(schedule, now)
        val start =
            mapOf(
                1L to
                    ScheduleRuntime(
                        scheduleId = 1,
                        consumedBudgetMillis = 10_000L,
                        pauseClearedAtMillis = null,
                        windowOccurrenceKey = occurrenceKey,
                    ),
            )

        val afterFirst = UsageAccounting.applyForegroundSpan("com.a", 30_000L, now, listOf(schedule), start)
        assertEquals(40_000L, afterFirst.getValue(1L).consumedBudgetMillis)
        assertEquals(occurrenceKey, afterFirst.getValue(1L).windowOccurrenceKey)
        assertNull(afterFirst.getValue(1L).pauseClearedAtMillis)

        val afterSecond =
            UsageAccounting.applyForegroundSpan(
                "com.b",
                15_000L,
                now.plusMinutes(1),
                listOf(schedule),
                afterFirst,
            )
        assertEquals(55_000L, afterSecond.getValue(1L).consumedBudgetMillis)
    }

    @Test
    fun `overlapping active schedules for one app all receive the finished span`() {
        val activeA = schedule(id = 1, packages = setOf("com.a"))
        val activeB = schedule(id = 2, packages = setOf("com.a", "com.c"))
        val inactive =
            schedule(
                id = 3,
                packages = setOf("com.a"),
                start = LocalTime.of(18, 0),
                end = LocalTime.of(19, 0),
            )
        val start =
            mapOf(
                1L to
                    ScheduleRuntime(
                        1,
                        consumedBudgetMillis = 10_000L,
                        pauseClearedAtMillis = null,
                        windowOccurrenceKey = ScheduleWindowBoundaries.windowOccurrenceKey(activeA, now),
                    ),
                2L to
                    ScheduleRuntime(
                        2,
                        consumedBudgetMillis = 20_000L,
                        pauseClearedAtMillis = 2222L,
                        windowOccurrenceKey = ScheduleWindowBoundaries.windowOccurrenceKey(activeB, now),
                    ),
                3L to
                    ScheduleRuntime(
                        3,
                        consumedBudgetMillis = 30_000L,
                        pauseClearedAtMillis = 3333L,
                        windowOccurrenceKey = now.toLocalDate().toEpochDay(),
                    ),
            )

        val updated =
            UsageAccounting.applyForegroundSpan(
                "com.a",
                45_000L,
                now,
                listOf(activeA, activeB, inactive),
                start,
            )

        assertEquals(55_000L, updated.getValue(1L).consumedBudgetMillis)
        assertEquals(65_000L, updated.getValue(2L).consumedBudgetMillis)
        assertEquals(start.getValue(3L), updated.getValue(3L))
    }

    @Test
    fun `uncovered app span is ignored`() {
        val schedule = schedule(packages = setOf("com.a"))
        val occurrenceKey = ScheduleWindowBoundaries.windowOccurrenceKey(schedule, now)
        val start =
            mapOf(
                1L to
                    ScheduleRuntime(
                        scheduleId = 1,
                        consumedBudgetMillis = 10_000L,
                        pauseClearedAtMillis = 1234L,
                        windowOccurrenceKey = occurrenceKey,
                    ),
            )

        val updated = UsageAccounting.applyForegroundSpan("com.other", 45_000L, now, listOf(schedule), start)

        assertEquals(start, updated)
    }

    @Test
    fun `covered usage is accounted when interruption is after the active window`() {
        val schedule = schedule(end = LocalTime.of(10, 5)).copy(sharedBudgetSeconds = 3_600)
        val occurrenceKey = ScheduleWindowBoundaries.windowOccurrenceKey(schedule, now)
        val runtime =
            mapOf(
                1L to ScheduleRuntime(1, 0, null, occurrenceKey),
            )

        assertNull(RulesEngine.nextInterrupt("com.a", now, listOf(schedule), runtime))
        val updated = UsageAccounting.applyForegroundSpan("com.a", 600_000L, now, listOf(schedule), runtime)

        assertEquals(600_000L, updated.getValue(1L).consumedBudgetMillis)
    }
}
