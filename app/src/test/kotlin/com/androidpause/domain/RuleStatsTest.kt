package com.androidpause.domain

import com.androidpause.domain.model.RuleStats
import com.androidpause.domain.model.ScheduleRuntime
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class RuleStatsTest {
    private fun runtime(
        millis: Long = 0,
        openings: Int = 0,
        clearedAt: Long? = null,
    ) = ScheduleRuntime(1, millis, clearedAt, 0, openings)

    @Test
    fun `usage and openings increases become stats`() {
        val deltas = statsDeltas(mapOf(1L to runtime(1_000, 1)), mapOf(1L to runtime(4_000, 2)))
        assertEquals(mapOf(1L to RuleStats(usageMillis = 3_000, openings = 1)), deltas)
    }

    @Test
    fun `a new rule's first usage counts from zero`() {
        assertEquals(
            mapOf(1L to RuleStats(usageMillis = 500, openings = 1)),
            statsDeltas(emptyMap(), mapOf(1L to runtime(500, 1))),
        )
    }

    @Test
    fun `a new extension anchor counts one extension`() {
        val deltas = statsDeltas(mapOf(1L to runtime(clearedAt = 10)), mapOf(1L to runtime(clearedAt = 20)))
        assertEquals(mapOf(1L to RuleStats(extensions = 1)), deltas)
        assertEquals(emptyMap(), statsDeltas(mapOf(1L to runtime(clearedAt = 10)), mapOf(1L to runtime())))
    }

    @Test
    fun `occurrence resets are not negative usage`() {
        assertEquals(emptyMap(), statsDeltas(mapOf(1L to runtime(9_000, 3)), mapOf(1L to runtime())))
    }

    @Test
    fun `week starts on the locale's first day`() {
        val thursday = LocalDate.of(2026, 10, 1)
        assertEquals(LocalDate.of(2026, 9, 28), weekStart(thursday, DayOfWeek.MONDAY))
        assertEquals(LocalDate.of(2026, 9, 27), weekStart(thursday, DayOfWeek.SUNDAY))
        assertEquals(thursday, weekStart(thursday, DayOfWeek.THURSDAY))
    }
}
