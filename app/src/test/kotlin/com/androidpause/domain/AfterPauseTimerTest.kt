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

/** T031 — after-pause wall-clock re-pause incl. zero-value grace (FR-013b). */
class AfterPauseTimerTest {
    private val now = LocalDateTime.of(2026, 5, 31, 12, 0).atZone(ZoneOffset.UTC)
    private val nowMillis = now.toInstant().toEpochMilli()

    private fun schedule(afterPause: Int) =
        ScheduleConfig(
            id = 1,
            enabled = true,
            name = "s",
            windowStart = LocalTime.of(9, 0),
            windowEnd = LocalTime.of(17, 0),
            sharedBudgetSeconds = 0,
            pauseDelaySeconds = 15,
            afterPauseBudgetSeconds = afterPause,
            message = "pause",
            packages = setOf("com.a"),
        )

    private fun runtime(clearedAtMillis: Long?) =
        mapOf(
            1L to
                ScheduleRuntime(
                    1,
                    windowOccurrenceKey = now.toLocalDate().toEpochDay(),
                    consumedBudgetMillis = 0,
                    pauseClearedAtMillis = clearedAtMillis,
                ),
        )

    @Test
    fun `re-pause waits for wall-clock grace then triggers`() {
        val beforeDeadline =
            RulesEngine.evaluate(
                pkg = "com.a",
                now = now,
                schedules = listOf(schedule(afterPause = 60)),
                runtime = runtime(clearedAtMillis = nowMillis - 59_000L),
            )
        assertEquals(PauseOutcome.Allow, beforeDeadline)

        val atDeadline =
            RulesEngine.evaluate(
                pkg = "com.a",
                now = now,
                schedules = listOf(schedule(afterPause = 60)),
                runtime = runtime(clearedAtMillis = nowMillis - 60_000L),
            )
        val pause = assertIs<PauseOutcome.Pause>(atDeadline)
        assertEquals(15, pause.delaySeconds)
    }

    @Test
    fun `zero after-pause budget re-pauses immediately`() {
        val outcome =
            RulesEngine.evaluate(
                pkg = "com.a",
                now = now,
                schedules = listOf(schedule(afterPause = 0)),
                runtime = runtime(clearedAtMillis = nowMillis),
            )

        assertIs<PauseOutcome.Pause>(outcome)
    }
}
