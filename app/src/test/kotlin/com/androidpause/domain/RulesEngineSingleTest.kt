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

/** T021 — RulesEngine.evaluate allow/pause/block for a single schedule (FR-009/010/012). */
class RulesEngineSingleTest {
    private val now = LocalDateTime.of(2026, 5, 30, 12, 0).atZone(ZoneOffset.UTC)

    private fun schedule(
        budget: Int,
        delay: Int? = 5,
        enabled: Boolean = true,
        afterPause: Int = 0,
    ) = ScheduleConfig(
        id = 1,
        enabled = enabled,
        name = "s",
        windowStart = LocalTime.of(9, 0),
        windowEnd = LocalTime.of(17, 0),
        sharedBudgetSeconds = budget,
        pauseDelaySeconds = delay,
        afterPauseBudgetSeconds = afterPause,
        message = "take a break",
        packages = setOf("com.x"),
    )

    private fun runtime(
        consumed: Int,
        pauseClearedAt: Long? = null,
    ) = mapOf(
        1L to
            ScheduleRuntime(
                scheduleId = 1,
                consumedBudgetMillis = consumed * 1_000L,
                pauseClearedAtMillis = pauseClearedAt,
                windowOccurrenceKey = now.toLocalDate().toEpochDay(),
            ),
    )

    private fun eval(
        schedule: ScheduleConfig,
        runtime: Map<Long, ScheduleRuntime>,
    ) = RulesEngine.evaluate("com.x", now, listOf(schedule), runtime)

    @Test
    fun `budget remaining allows`() {
        assertEquals(PauseOutcome.Allow, eval(schedule(budget = 600), runtime(consumed = 60)))
    }

    @Test
    fun `zero budget pauses on first launch`() {
        val outcome = eval(schedule(budget = 0), runtime(consumed = 0))
        val pause = assertIs<PauseOutcome.Pause>(outcome)
        assertEquals(5, pause.delaySeconds)
        assertEquals("take a break", pause.info.message)
        assertEquals(0, pause.info.sharedBudgetSeconds)
        assertEquals(0, pause.info.consumedBudgetSeconds)
        assertEquals(0, pause.info.afterPauseBudgetSeconds)
        assertEquals(LocalTime.of(17, 0), pause.info.windowEnd)
    }

    @Test
    fun `exhausted budget pauses`() {
        assertIs<PauseOutcome.Pause>(eval(schedule(budget = 600), runtime(consumed = 600)))
    }

    @Test
    fun `infinite delay exhausted blocks`() {
        val outcome = eval(schedule(budget = 0, delay = null), runtime(consumed = 0))
        val block = assertIs<PauseOutcome.Block>(outcome)
        assertEquals("take a break", block.info.message)
    }

    @Test
    fun `uncovered package allows`() {
        val outcome = RulesEngine.evaluate("com.other", now, listOf(schedule(budget = 0)), runtime(0))
        assertEquals(PauseOutcome.Allow, outcome)
    }

    @Test
    fun `inactive window allows even when exhausted`() {
        val outside = LocalDateTime.of(2026, 5, 30, 20, 0).atZone(ZoneOffset.UTC)
        val outcome = RulesEngine.evaluate("com.x", outside, listOf(schedule(budget = 0)), runtime(0))
        assertEquals(PauseOutcome.Allow, outcome)
    }

    @Test
    fun `disabled schedule allows`() {
        val outcome = eval(schedule(budget = 0, enabled = false), runtime(0))
        assertEquals(PauseOutcome.Allow, outcome)
    }
}
