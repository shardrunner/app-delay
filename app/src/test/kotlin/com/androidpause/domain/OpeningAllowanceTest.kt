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

class OpeningAllowanceTest {
    private val now = LocalDateTime.of(2026, 9, 19, 12, 0).atZone(ZoneOffset.UTC)
    private val schedule =
        ScheduleConfig(
            id = 1,
            enabled = true,
            name = "Open once",
            windowStart = LocalTime.of(9, 0),
            windowEnd = LocalTime.of(17, 0),
            sharedBudgetSeconds = null,
            pauseDelaySeconds = 5,
            afterPauseBudgetSeconds = 0,
            message = "Pause",
            packages = setOf("com.example.app"),
            openingAllowance = 1,
        )

    @Test
    fun `opening-only usage is counted for statistics without a time deadline`() {
        val schedules = listOf(schedule)
        val admitted = recordEntry("com.example.app", now, schedules, emptyMap(), countsAsOpening = true)
        val used = UsageAccounting.applyForegroundSpan("com.example.app", 90_000, now, schedules, admitted)
        assertEquals(90_000, used.getValue(1L).consumedBudgetMillis)
        assertEquals(90_000, statsDeltas(admitted, used).getValue(1L).usageMillis)
        assertEquals(PauseOutcome.Allow, RulesEngine.evaluate("com.example.app", now, schedules, used))
        assertEquals(null, RulesEngine.nextInterrupt("com.example.app", now, schedules, used))
        assertIs<PauseOutcome.Pause>(
            RulesEngine.evaluate("com.example.app", now, listOf(schedule.copy(sharedBudgetSeconds = 0)), used),
        )
    }

    @Test
    fun `first opening is allowed and the next app session is paused`() {
        val first = runtime(consumedOpenings = 0, sessionPackage = null)
        val exhausted = runtime(consumedOpenings = 1, sessionPackage = null)

        assertEquals(
            PauseOutcome.Allow,
            RulesEngine.evaluate("com.example.app", now, listOf(schedule), mapOf(1L to first)),
        )
        assertIs<PauseOutcome.Pause>(
            RulesEngine.evaluate("com.example.app", now, listOf(schedule), mapOf(1L to exhausted)),
        )
    }

    @Test
    fun `duplicate foreground events do not consume another opening`() {
        val restoredSession = runtime(consumedOpenings = 1, sessionPackage = "com.example.app")

        assertEquals(
            PauseOutcome.Allow,
            RulesEngine.evaluate("com.example.app", now, listOf(schedule), mapOf(1L to restoredSession)),
        )
    }

    @Test
    fun `pause info reports the rule's opening usage`() {
        val outcome =
            RulesEngine.evaluate(
                "com.example.app",
                now,
                listOf(schedule),
                mapOf(1L to runtime(consumedOpenings = 1, sessionPackage = null)),
            )
        val info = assertIs<PauseOutcome.Pause>(outcome).info
        assertEquals(1, info.openingAllowance)
        assertEquals(1, info.consumedOpenings)
    }

    private fun runtime(
        consumedOpenings: Int,
        sessionPackage: String?,
    ) = ScheduleRuntime(
        scheduleId = 1,
        consumedBudgetMillis = 0,
        pauseClearedAtMillis = null,
        windowOccurrenceKey = now.toLocalDate().toEpochDay(),
        consumedOpenings = consumedOpenings,
        sessionPackage = sessionPackage,
    )
}
