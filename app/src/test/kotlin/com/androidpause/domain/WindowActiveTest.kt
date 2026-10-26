package com.androidpause.domain

import com.androidpause.domain.model.ScheduleConfig
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T020 — RulesEngine.isActive incl. midnight-crossing + window-end-exclusive (FR-003/FR-003a). */
class WindowActiveTest {
    private fun schedule(
        start: LocalTime,
        end: LocalTime,
    ) = ScheduleConfig(
        id = 1,
        enabled = true,
        name = "s",
        windowStart = start,
        windowEnd = end,
        sharedBudgetSeconds = 0,
        pauseDelaySeconds = 5,
        afterPauseBudgetSeconds = 0,
        message = "m",
        packages = setOf("com.x"),
    )

    private fun at(
        h: Int,
        m: Int,
    ) = LocalDateTime.of(2026, 5, 30, h, m).atZone(ZoneOffset.UTC)

    // --- normal same-day window 09:00-17:00 ---

    @Test
    fun `inside same-day window is active`() {
        assertTrue(ScheduleWindowBoundaries.isActive(schedule(LocalTime.of(9, 0), LocalTime.of(17, 0)), at(12, 0)))
    }

    @Test
    fun `start boundary inclusive`() {
        assertTrue(ScheduleWindowBoundaries.isActive(schedule(LocalTime.of(9, 0), LocalTime.of(17, 0)), at(9, 0)))
    }

    @Test
    fun `end boundary exclusive`() {
        assertFalse(ScheduleWindowBoundaries.isActive(schedule(LocalTime.of(9, 0), LocalTime.of(17, 0)), at(17, 0)))
    }

    @Test
    fun `before window inactive`() {
        assertFalse(ScheduleWindowBoundaries.isActive(schedule(LocalTime.of(9, 0), LocalTime.of(17, 0)), at(8, 59)))
    }

    // --- midnight-crossing window 23:00-02:00 ---

    @Test
    fun `midnight window active late night`() {
        assertTrue(ScheduleWindowBoundaries.isActive(schedule(LocalTime.of(23, 0), LocalTime.of(2, 0)), at(23, 30)))
    }

    @Test
    fun `midnight window active after midnight`() {
        assertTrue(ScheduleWindowBoundaries.isActive(schedule(LocalTime.of(23, 0), LocalTime.of(2, 0)), at(1, 30)))
    }

    @Test
    fun `midnight window start inclusive`() {
        assertTrue(ScheduleWindowBoundaries.isActive(schedule(LocalTime.of(23, 0), LocalTime.of(2, 0)), at(23, 0)))
    }

    @Test
    fun `midnight window end exclusive`() {
        assertFalse(ScheduleWindowBoundaries.isActive(schedule(LocalTime.of(23, 0), LocalTime.of(2, 0)), at(2, 0)))
    }

    @Test
    fun `midnight window inactive midday`() {
        assertFalse(ScheduleWindowBoundaries.isActive(schedule(LocalTime.of(23, 0), LocalTime.of(2, 0)), at(12, 0)))
    }

    @Test
    fun `disabled schedule reports active by window math but evaluate filters it`() {
        // isActive concerns window only; enabled filtering happens in evaluate.
        val disabled = schedule(LocalTime.of(9, 0), LocalTime.of(17, 0)).copy(enabled = false)
        assertTrue(ScheduleWindowBoundaries.isActive(disabled, at(12, 0)))
    }
}
