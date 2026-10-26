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

/** T037 — multi-schedule resolution: block > pause > allow; only active schedules apply. */
class RulesEngineMultiTest {
    private val now = LocalDateTime.of(2026, 5, 31, 12, 0).atZone(ZoneOffset.UTC)

    private fun schedule(
        id: Long,
        budget: Int,
        delay: Int? = 5,
        message: String,
        start: LocalTime = LocalTime.of(9, 0),
        end: LocalTime = LocalTime.of(17, 0),
        enabled: Boolean = true,
        afterPause: Int = 0,
    ) = ScheduleConfig(
        id = id,
        enabled = enabled,
        name = "s$id",
        windowStart = start,
        windowEnd = end,
        sharedBudgetSeconds = budget,
        pauseDelaySeconds = delay,
        afterPauseBudgetSeconds = afterPause,
        message = message,
        packages = setOf("com.x"),
    )

    private fun runtime(vararg states: Pair<Long, Int>) =
        states.associate { (id, consumed) ->
            id to
                ScheduleRuntime(
                    scheduleId = id,
                    consumedBudgetMillis = consumed * 1_000L,
                    pauseClearedAtMillis = null,
                    windowOccurrenceKey = now.toLocalDate().toEpochDay(),
                )
        }

    @Test
    fun `pause beats allow across overlapping active schedules`() {
        val pause = schedule(id = 1, budget = 0, delay = 5, message = "pause")
        val allow = schedule(id = 2, budget = 600, delay = 15, message = "allow")

        val outcome = RulesEngine.evaluate("com.x", now, listOf(pause, allow), runtime(1L to 0, 2L to 60))

        val result = assertIs<PauseOutcome.Pause>(outcome)
        assertEquals(5, result.delaySeconds)
        assertEquals("pause", result.info.message)
    }

    @Test
    fun `longest exhausted pause delay wins with its own message`() {
        val short = schedule(id = 1, budget = 0, delay = 5, message = "short")
        val long = schedule(id = 2, budget = 0, delay = 15, message = "long")

        val outcome = RulesEngine.evaluate("com.x", now, listOf(short, long), runtime(1L to 0, 2L to 0))

        val result = assertIs<PauseOutcome.Pause>(outcome)
        assertEquals(15, result.delaySeconds)
        assertEquals("long", result.info.message)
    }

    @Test
    fun `pause reports earliest effective extension across overlapping schedules`() {
        val longestPause = schedule(id = 1, budget = 0, delay = 15, message = "long", afterPause = 300)
        val earliestExtension = schedule(id = 2, budget = 0, delay = 5, message = "short", afterPause = 60)

        val outcome =
            RulesEngine.evaluate(
                "com.x",
                now,
                listOf(longestPause, earliestExtension),
                runtime(1L to 0, 2L to 0),
            )

        val result = assertIs<PauseOutcome.Pause>(outcome)
        assertEquals("long", result.info.message)
        assertEquals(60, result.info.afterPauseBudgetSeconds)
    }

    @Test
    fun `block beats pause across overlapping active schedules`() {
        val pause = schedule(id = 1, budget = 0, delay = 15, message = "pause")
        val block = schedule(id = 2, budget = 0, delay = null, message = "block")

        val outcome = RulesEngine.evaluate("com.x", now, listOf(pause, block), runtime(1L to 0, 2L to 0))

        val result = assertIs<PauseOutcome.Block>(outcome)
        assertEquals("block", result.info.message)
    }

    @Test
    fun `inactive overlapping schedule is ignored`() {
        val inactivePause =
            schedule(
                id = 1,
                budget = 0,
                delay = 15,
                message = "inactive",
                start = LocalTime.of(18, 0),
                end = LocalTime.of(19, 0),
            )
        val activeAllow = schedule(id = 2, budget = 600, delay = 5, message = "active")

        val outcome =
            RulesEngine.evaluate(
                "com.x",
                now,
                listOf(inactivePause, activeAllow),
                runtime(1L to 0, 2L to 60),
            )

        assertEquals(PauseOutcome.Allow, outcome)
    }
}
