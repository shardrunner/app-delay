package com.androidpause.domain

import com.androidpause.domain.model.ScheduleConfig
import com.androidpause.domain.model.ScheduleRuntime
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DstOccurrenceRegressionTest {
    private val pkg = "com.example.app"
    private val schedule =
        ScheduleConfig(
            id = 1,
            enabled = true,
            name = "Overnight fallback",
            windowStart = LocalTime.of(23, 0),
            windowEnd = LocalTime.of(2, 30),
            sharedBudgetSeconds = 600,
            pauseDelaySeconds = 5,
            afterPauseBudgetSeconds = 300,
            message = "Pause",
            packages = setOf(pkg),
            openingAllowance = 2,
        )
    private val first0245 = ZonedDateTime.parse("2026-10-25T02:45:00+02:00[Europe/Berlin]")
    private val second0210 = ZonedDateTime.parse("2026-10-25T02:10:00+01:00[Europe/Berlin]")
    private val end = ZonedDateTime.parse("2026-10-25T02:30:00+01:00[Europe/Berlin]")
    private val startKey = LocalDate.of(2026, 10, 24).toEpochDay()
    private val initial =
        mapOf(
            1L to
                ScheduleRuntime(
                    scheduleId = 1,
                    consumedBudgetMillis = 120_000L,
                    pauseClearedAtMillis = first0245.minusMinutes(1).toInstant().toEpochMilli(),
                    windowOccurrenceKey = startKey,
                    consumedOpenings = 2,
                    sessionPackage = pkg,
                    sessionTimeGranted = true,
                ),
        )

    @Test
    fun `zoned occurrence key follows resolved overnight end through both offsets`() {
        for (now in listOf(first0245, second0210, end.minusNanos(1))) {
            assertEquals(
                startKey,
                ScheduleWindowBoundaries.windowOccurrenceKey(schedule, now),
                "Active occurrence at $now",
            )
        }
        assertFailsWith<IllegalArgumentException> { ScheduleWindowBoundaries.windowOccurrenceKey(schedule, end) }
    }

    @Test
    fun `spring gap overnight key stays on previous date until resolved end`() {
        val now = ZonedDateTime.parse("2026-03-29T03:00:00+02:00[Europe/Berlin]")
        val beforeEnd = now.minusNanos(1)
        assertTrue(ScheduleWindowBoundaries.isActive(schedule, beforeEnd))
        assertEquals(
            LocalDate.of(2026, 3, 28).toEpochDay(),
            ScheduleWindowBoundaries.windowOccurrenceKey(schedule, beforeEnd),
        )
        assertEquals(
            now.toInstant().toEpochMilli(),
            assertNotNull(ScheduleWindowBoundaries.next(pkg, beforeEnd, listOf(schedule))),
        )
        assertFailsWith<IllegalArgumentException> { ScheduleWindowBoundaries.windowOccurrenceKey(schedule, now) }
    }

    @Test
    fun `fallback overnight reset preserves the same occurrence and all runtime state`() {
        assertTrue(first0245.toInstant().isBefore(second0210.toInstant()))
        var runtime = initial
        for (now in listOf(first0245, second0210)) {
            assertTrue(ScheduleWindowBoundaries.isActive(schedule, now))
            val boundary = assertNotNull(ScheduleWindowBoundaries.next(pkg, now, listOf(schedule)))
            assertEquals(end.toInstant().toEpochMilli(), boundary)
            runtime = UsageAccounting.resetExpiredWindows(now, listOf(schedule), runtime)
            assertEquals(initial, runtime, "No reset within the October 24 occurrence at $now")
        }
    }

    @Test
    fun `fallback foreground accounting keeps accumulating within the same occurrence`() {
        val first = UsageAccounting.applyForegroundSpan(pkg, 10_000L, first0245, listOf(schedule), initial)
        assertEquals(initial.getValue(1L).copy(consumedBudgetMillis = 130_000L), first.getValue(1L))
        val second = UsageAccounting.applyForegroundSpan(pkg, 20_000L, second0210, listOf(schedule), first)
        assertEquals(initial.getValue(1L).copy(consumedBudgetMillis = 150_000L), second.getValue(1L))
    }

    @Test
    fun `fallback missing runtime receives the active overnight start key`() {
        for (now in listOf(first0245, second0210)) {
            val updated = UsageAccounting.applyForegroundSpan(pkg, 1_000L, now, listOf(schedule), emptyMap())
            assertEquals(startKey, updated.getValue(1L).windowOccurrenceKey, "Active occurrence at $now")
            assertEquals(1_000L, updated.getValue(1L).consumedBudgetMillis)
        }
    }

    @Test
    fun `next overnight start still resets both allowances and grants`() {
        val nextStart = ZonedDateTime.parse("2026-10-25T23:00:00+01:00[Europe/Berlin]")
        val updated = UsageAccounting.resetExpiredWindows(nextStart, listOf(schedule), initial)
        assertEquals(
            ScheduleRuntime(
                scheduleId = 1,
                consumedBudgetMillis = 0,
                pauseClearedAtMillis = null,
                windowOccurrenceKey = LocalDate.of(2026, 10, 25).toEpochDay(),
            ),
            updated.getValue(1L),
        )
    }
}
