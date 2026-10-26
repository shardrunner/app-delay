package com.androidpause.domain

import com.androidpause.domain.model.ScheduleConfig
import com.androidpause.domain.model.ScheduleRuntime
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class NextInterruptTest {
    private val now = LocalDateTime.of(2026, 6, 5, 12, 0).atZone(ZoneOffset.UTC)
    private val nowMillis = now.toInstant().toEpochMilli()

    private fun schedule(
        id: Long,
        sharedBudgetSeconds: Int,
        pauseDelaySeconds: Int? = 15,
        afterPauseBudgetSeconds: Int = 120,
    ) = ScheduleConfig(
        id = id,
        enabled = true,
        name = "s$id",
        windowStart = LocalTime.of(9, 0),
        windowEnd = LocalTime.of(17, 0),
        sharedBudgetSeconds = sharedBudgetSeconds,
        pauseDelaySeconds = pauseDelaySeconds,
        afterPauseBudgetSeconds = afterPauseBudgetSeconds,
        message = "msg$id",
        packages = setOf("com.example.app"),
    )

    private fun runtime(
        scheduleId: Long,
        consumedBudgetMillis: Long,
        pauseClearedAtMillis: Long?,
    ) = ScheduleRuntime(
        scheduleId = scheduleId,
        consumedBudgetMillis = consumedBudgetMillis,
        pauseClearedAtMillis = pauseClearedAtMillis,
        windowOccurrenceKey = now.toLocalDate().toEpochDay(),
    )

    @Test
    fun `shared budget last minute returns shared budget pause deadline`() {
        val result =
            RulesEngine.nextInterrupt(
                pkg = "com.example.app",
                now = now,
                schedules = listOf(schedule(id = 1, sharedBudgetSeconds = 600)),
                runtime =
                    mapOf(
                        1L to
                            runtime(
                                scheduleId = 1,
                                consumedBudgetMillis = 540_000L,
                                pauseClearedAtMillis = null,
                            ),
                    ),
            )

        assertNotNull(result)
        assertEquals(1L, result.scheduleId)
        assertEquals(60_000L, result.remainingMillis)
    }

    @Test
    fun `shared budget last minute returns shared budget block deadline for infinite delay`() {
        val result =
            RulesEngine.nextInterrupt(
                pkg = "com.example.app",
                now = now,
                schedules = listOf(schedule(id = 1, sharedBudgetSeconds = 600, pauseDelaySeconds = null)),
                runtime =
                    mapOf(
                        1L to
                            runtime(
                                scheduleId = 1,
                                consumedBudgetMillis = 540_000L,
                                pauseClearedAtMillis = null,
                            ),
                    ),
            )

        assertNotNull(result)
        assertEquals(1L, result.scheduleId)
        assertEquals(60_000L, result.remainingMillis)
    }

    @Test
    fun `after-pause last minute returns after-pause deadline`() {
        val result =
            RulesEngine.nextInterrupt(
                pkg = "com.example.app",
                now = now,
                schedules = listOf(schedule(id = 1, sharedBudgetSeconds = 0, afterPauseBudgetSeconds = 120)),
                runtime =
                    mapOf(
                        1L to
                            runtime(
                                scheduleId = 1,
                                consumedBudgetMillis = 0,
                                pauseClearedAtMillis = nowMillis - 75_000L,
                            ),
                    ),
            )

        assertNotNull(result)
        assertEquals(1L, result.scheduleId)
        assertEquals(45_000L, result.remainingMillis)
    }

    @Test
    fun `overlapping schedules choose earliest upcoming interruption`() {
        val sharedBudgetSchedule = schedule(id = 1, sharedBudgetSeconds = 600)
        val afterPauseSchedule = schedule(id = 2, sharedBudgetSeconds = 0, afterPauseBudgetSeconds = 120)

        val result =
            RulesEngine.nextInterrupt(
                pkg = "com.example.app",
                now = now,
                schedules = listOf(sharedBudgetSchedule, afterPauseSchedule),
                runtime =
                    mapOf(
                        1L to runtime(scheduleId = 1, consumedBudgetMillis = 550_000L, pauseClearedAtMillis = null),
                        2L to
                            runtime(
                                scheduleId = 2,
                                consumedBudgetMillis = 0,
                                pauseClearedAtMillis = nowMillis - 90_000L,
                            ),
                    ),
            )

        assertNotNull(result)
        assertEquals(2L, result.scheduleId)
        assertEquals(30_000L, result.remainingMillis)
    }

    @Test
    fun `equal deadlines tie break to lower scheduleId`() {
        val result =
            RulesEngine.nextInterrupt(
                pkg = "com.example.app",
                now = now,
                schedules =
                    listOf(
                        schedule(id = 5, sharedBudgetSeconds = 600),
                        schedule(id = 2, sharedBudgetSeconds = 600),
                    ),
                runtime =
                    mapOf(
                        5L to runtime(scheduleId = 5, consumedBudgetMillis = 540_000L, pauseClearedAtMillis = null),
                        2L to runtime(scheduleId = 2, consumedBudgetMillis = 540_000L, pauseClearedAtMillis = null),
                    ),
            )

        assertNotNull(result)
        assertEquals(2L, result.scheduleId)
        assertEquals(60_000L, result.remainingMillis)
    }

    @Test
    fun `exhausted finite delay with null pauseClearedAtMillis returns immediate pause deadline`() {
        val result =
            RulesEngine.nextInterrupt(
                pkg = "com.example.app",
                now = now,
                schedules = listOf(schedule(id = 1, sharedBudgetSeconds = 0, pauseDelaySeconds = 10)),
                runtime = mapOf(1L to runtime(scheduleId = 1, consumedBudgetMillis = 0, pauseClearedAtMillis = null)),
            )

        assertNotNull(result)
        assertEquals(0L, result.remainingMillis)
    }

    @Test
    fun `interruption after active window end is not a real deadline`() {
        val result =
            RulesEngine.nextInterrupt(
                pkg = "com.example.app",
                now = now,
                schedules = listOf(schedule(id = 1, sharedBudgetSeconds = 6 * 60 * 60)),
                runtime = mapOf(1L to runtime(scheduleId = 1, consumedBudgetMillis = 0, pauseClearedAtMillis = null)),
            )

        kotlin.test.assertNull(result)
    }

    @Test
    fun `already due block returns zero remaining and block outcome`() {
        val result =
            RulesEngine.nextInterrupt(
                pkg = "com.example.app",
                now = now,
                schedules = listOf(schedule(id = 1, sharedBudgetSeconds = 0, pauseDelaySeconds = null)),
                runtime = mapOf(1L to runtime(scheduleId = 1, consumedBudgetMillis = 0, pauseClearedAtMillis = null)),
            )

        assertNotNull(result)
        assertEquals(0L, result.remainingMillis)
    }
}
