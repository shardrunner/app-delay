package com.androidpause.enforcement

import com.androidpause.domain.RulesEngine
import com.androidpause.domain.ScheduleWindowBoundaries
import com.androidpause.domain.UsageAccounting
import com.androidpause.domain.model.ScheduleConfig
import com.androidpause.domain.model.ScheduleRuntime
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Exercises the span and accounting helpers used by the service with subsecond usage. */
class EnforcementAccountingRegressionTest {
    private val pkg = "com.example.app"
    private val now = ZonedDateTime.parse("2026-06-03T16:59:00Z")

    @Test
    fun `splitting usage into subsecond spans does not lose whole seconds from shared budget`() {
        val schedules = listOf(schedule(1))
        val wallStart = now.toInstant().toEpochMilli()
        var runtime = emptyMap<Long, ScheduleRuntime>()

        // Gaps are not usage: four 750 ms visits consume exactly three seconds.
        repeat(4) { visit ->
            val start = visit * 2_000L
            val span = ForegroundSpan(start, wallStart + start, null)
            runtime =
                UsageAccounting.applyForegroundSpan(
                    pkg = pkg,
                    spanMillis = span.usageMillis(start + 750L),
                    now = now.plusSeconds(visit * 2L),
                    schedules = schedules,
                    runtime = runtime,
                )
        }

        assertEquals(3_000L, runtime[1L]?.consumedBudgetMillis)
    }

    @Test
    fun `subsecond usage is applied before boundary filtering and cross schedule selection`() {
        val schedules = listOf(schedule(1), schedule(2))
        val runtime =
            mapOf(
                2L to
                    ScheduleRuntime(
                        scheduleId = 2,
                        consumedBudgetMillis = 750,
                        pauseClearedAtMillis = null,
                        windowOccurrenceKey = ScheduleWindowBoundaries.windowOccurrenceKey(schedules[1], now),
                    ),
            )

        // Whole-second deadlines equal the window end and would both be filtered out.
        val interrupt =
            assertNotNull(RulesEngine.nextInterrupt(pkg, now, schedules, runtime))
        assertEquals(2L, interrupt.scheduleId)
        assertEquals(59_250L, interrupt.remainingMillis)
    }

    @Test
    fun `subsecond usage never shortens extension when shared budget is also enabled`() {
        val schedule = schedule(1).copy(afterPauseBudgetSeconds = 10)
        val nowMillis = now.toInstant().toEpochMilli()
        val runtime =
            ScheduleRuntime(
                scheduleId = 1,
                consumedBudgetMillis = 750,
                pauseClearedAtMillis = nowMillis - 9_500L,
                windowOccurrenceKey = ScheduleWindowBoundaries.windowOccurrenceKey(schedule, now),
            )
        val interrupt =
            assertNotNull(
                RulesEngine.nextInterrupt(
                    pkg,
                    now,
                    listOf(schedule),
                    mapOf(
                        1L to runtime,
                    ),
                ),
            )
        assertEquals(500L, interrupt.remainingMillis)
    }

    private fun schedule(id: Long) =
        ScheduleConfig(
            id = id,
            enabled = true,
            name = "Schedule $id",
            windowStart = LocalTime.of(9, 0),
            windowEnd = LocalTime.of(17, 0),
            sharedBudgetSeconds = 60,
            pauseDelaySeconds = 5,
            afterPauseBudgetSeconds = 0,
            message = "Pause",
            packages = setOf(pkg),
        )
}
