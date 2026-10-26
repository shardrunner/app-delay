package com.androidpause.domain

import com.androidpause.domain.model.ScheduleConfig
import com.androidpause.domain.model.ScheduleRuntime
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SubsecondUsageInterruptTest {
    private val now = ZonedDateTime.parse("2026-06-05T12:00:00Z")
    private val nowMillis = now.toInstant().toEpochMilli()
    private val pkg = "com.example.app"

    private fun schedule(id: Long = 1) =
        ScheduleConfig(
            id = id,
            enabled = true,
            name = "s$id",
            windowStart = LocalTime.of(9, 0),
            windowEnd = LocalTime.of(17, 0),
            sharedBudgetSeconds = 2,
            pauseDelaySeconds = 15,
            afterPauseBudgetSeconds = 1,
            message = "Pause",
            packages = setOf(pkg),
        )

    private fun runtime(
        id: Long = 1,
        consumedMillis: Long = 750,
    ) = ScheduleRuntime(
        scheduleId = id,
        consumedBudgetMillis = consumedMillis,
        pauseClearedAtMillis = nowMillis - 500,
        windowOccurrenceKey = now.toLocalDate().toEpochDay(),
    )

    @Test
    fun `subsecond shared usage does not shorten winning wallclock extension`() {
        val result =
            assertNotNull(
                RulesEngine.nextInterrupt(
                    pkg,
                    now,
                    listOf(schedule()),
                    mapOf(1L to runtime()),
                ),
            )

        assertEquals(500L, result.remainingMillis)
    }

    @Test
    fun `subsecond usage brings shared deadline before window end`() {
        val config = schedule().copy(windowEnd = LocalTime.of(12, 0, 2))
        val state = mapOf(1L to runtime().copy(pauseClearedAtMillis = null))
        val unused = mapOf(1L to runtime(consumedMillis = 0).copy(pauseClearedAtMillis = null))
        assertNull(RulesEngine.nextInterrupt(pkg, now, listOf(config), unused))

        val result =
            assertNotNull(
                RulesEngine.nextInterrupt(
                    pkg,
                    now,
                    listOf(config),
                    state,
                ),
            )

        assertEquals(1_250L, result.remainingMillis)
    }

    @Test
    fun `overlapping rules compare corrected shared candidate against unchanged extension`() {
        val schedules = listOf(schedule(), schedule(2).copy(sharedBudgetSeconds = 1))
        val state = mapOf(1L to runtime(), 2L to runtime(2).copy(pauseClearedAtMillis = null))
        val result =
            assertNotNull(
                RulesEngine.nextInterrupt(
                    pkg,
                    now,
                    schedules,
                    state,
                ),
            )

        assertEquals(2L, result.scheduleId)
        assertEquals(250L, result.remainingMillis)
    }
}
