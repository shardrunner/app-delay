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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OpeningExtensionBudgetRegressionTest {
    private val pkg = "com.example.app"
    private val now = LocalDateTime.of(2026, 10, 24, 12, 0).atZone(ZoneOffset.UTC)
    private val clearedAt = now.toInstant().toEpochMilli()
    private val schedule =
        ScheduleConfig(
            id = 1,
            enabled = true,
            name = "Combined allowances",
            windowStart = LocalTime.of(9, 0),
            windowEnd = LocalTime.of(17, 0),
            sharedBudgetSeconds = 60,
            pauseDelaySeconds = 5,
            afterPauseBudgetSeconds = 300,
            message = "Pause",
            packages = setOf(pkg),
            openingAllowance = 0,
        )

    // State immediately after clearing the opening pause and recording successful entry.
    private fun clearedRuntime(consumed: Int = 0) =
        mapOf(
            1L to
                ScheduleRuntime(
                    scheduleId = 1,
                    consumedBudgetMillis = consumed * 1_000L,
                    pauseClearedAtMillis = clearedAt,
                    windowOccurrenceKey = now.toLocalDate().toEpochDay(),
                    consumedOpenings = 1,
                    sessionPackage = pkg,
                ),
        )

    @Test
    fun `opening extension still interrupts at independent time budget exhaustion`() {
        val initial = clearedRuntime()
        val deadline = assertNotNull(RulesEngine.nextInterrupt(pkg, now, listOf(schedule), initial))
        assertEquals(60_000L, deadline.remainingMillis)

        val before = UsageAccounting.applyForegroundSpan(pkg, 59_000L, now.plusSeconds(59), listOf(schedule), initial)
        assertEquals(clearedAt, before.getValue(1L).pauseClearedAtMillis)
        assertEquals(
            PauseOutcome.Allow,
            RulesEngine.evaluate(pkg, now.plusSeconds(59), listOf(schedule), before),
        )
        val exhausted = UsageAccounting.applyForegroundSpan(pkg, 1_000L, now.plusSeconds(60), listOf(schedule), before)
        assertIs<PauseOutcome.Pause>(
            RulesEngine.evaluate(pkg, now.plusSeconds(60), listOf(schedule), exhausted),
        )
        assertNull(exhausted.getValue(1L).pauseClearedAtMillis)
        assertEquals(
            0L,
            assertNotNull(
                RulesEngine.nextInterrupt(pkg, now.plusSeconds(60), listOf(schedule), exhausted),
            ).remainingMillis,
        )
    }

    @Test
    fun `span crossing exhaustion invalidates only the old opening clearance`() {
        val initial = clearedRuntime(59)
        val updated = UsageAccounting.applyForegroundSpan(pkg, 2_000L, now.plusSeconds(2), listOf(schedule), initial)
        assertEquals(
            initial.getValue(1L).copy(consumedBudgetMillis = 61_000L, pauseClearedAtMillis = null),
            updated.getValue(1L),
        )
    }

    @Test
    fun `ordinary and combined allowance extensions cleared after exhaustion survive further accounting`() {
        for (openingAllowance in listOf(null, 0, 2)) {
            val config = schedule.copy(openingAllowance = openingAllowance)
            val updated =
                UsageAccounting.applyForegroundSpan(
                    pkg,
                    60_000L,
                    now.plusSeconds(60),
                    listOf(config),
                    clearedRuntime(60),
                )
            assertEquals(clearedAt, updated.getValue(1L).pauseClearedAtMillis)
            assertEquals(
                PauseOutcome.Allow,
                RulesEngine.evaluate(pkg, now.plusSeconds(60), listOf(config), updated),
            )
            assertEquals(
                240_000L,
                assertNotNull(
                    RulesEngine.nextInterrupt(pkg, now.plusSeconds(60), listOf(config), updated),
                ).remainingMillis,
            )
            assertIs<PauseOutcome.Pause>(
                RulesEngine.evaluate(pkg, now.plusSeconds(300), listOf(config), updated),
            )
        }
    }

    @Test
    fun `opening only extension survives crossing unused time budget value`() {
        val config = schedule.copy(sharedBudgetSeconds = null)
        val updated =
            UsageAccounting.applyForegroundSpan(
                pkg,
                60_000L,
                now.plusSeconds(60),
                listOf(config),
                clearedRuntime(),
            )
        assertEquals(clearedAt, updated.getValue(1L).pauseClearedAtMillis)
        assertEquals(
            PauseOutcome.Allow,
            RulesEngine.evaluate(pkg, now.plusSeconds(60), listOf(config), updated),
        )
    }

    @Test
    fun `zero time budget clearance survives further usage`() {
        val config = schedule.copy(sharedBudgetSeconds = 0)
        val updated =
            UsageAccounting.applyForegroundSpan(
                pkg,
                1_000L,
                now.plusSeconds(1),
                listOf(config),
                clearedRuntime(),
            )
        assertEquals(clearedAt, updated.getValue(1L).pauseClearedAtMillis)
        assertEquals(
            PauseOutcome.Allow,
            RulesEngine.evaluate(pkg, now.plusSeconds(1), listOf(config), updated),
        )
    }

    @Test
    fun `zero span preserves clearance and all runtime state`() {
        val initial = clearedRuntime(59)
        assertEquals(initial, UsageAccounting.applyForegroundSpan(pkg, 0L, now, listOf(schedule), initial))
    }

    @Test
    fun `remaining opening allowance does not suppress time exhaustion`() {
        val config = schedule.copy(openingAllowance = 2)
        val initial = clearedRuntime().mapValues { (_, value) -> value.copy(pauseClearedAtMillis = null) }
        val updated = UsageAccounting.applyForegroundSpan(pkg, 60_000L, now.plusSeconds(60), listOf(config), initial)
        assertIs<PauseOutcome.Pause>(
            RulesEngine.evaluate(pkg, now.plusSeconds(60), listOf(config), updated),
        )
    }
}
