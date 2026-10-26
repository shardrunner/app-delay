package com.androidpause.domain

import com.androidpause.domain.model.RuleStats
import com.androidpause.domain.model.ScheduleConfig
import com.androidpause.domain.model.ScheduleRuntime
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/** Clearing a finite pause anchors the after-pause extension (FR-013b). */
class PauseClearingTest {
    private val pkg = "com.example.app"
    private val now = LocalDateTime.of(2026, 5, 31, 12, 0).atZone(ZoneOffset.UTC)
    private val nowMillis = now.toInstant().toEpochMilli()
    private val occurrence = now.toLocalDate().toEpochDay()

    private fun schedule(
        id: Long = 1,
        afterPause: Int,
        delay: Int? = 15,
    ) = ScheduleConfig(
        id = id,
        enabled = true,
        name = "s$id",
        windowStart = LocalTime.of(9, 0),
        windowEnd = LocalTime.of(17, 0),
        sharedBudgetSeconds = 60,
        pauseDelaySeconds = delay,
        afterPauseBudgetSeconds = afterPause,
        message = "pause",
        packages = setOf(pkg),
    )

    private fun exhausted(id: Long = 1) = ScheduleRuntime(id, 60_000, null, occurrence)

    @Test
    fun `positive extension anchors the wall-clock deadline without an entry grant`() {
        val cleared =
            UsageAccounting.clearPause(
                pkg,
                now,
                listOf(schedule(afterPause = 30)),
                mapOf(
                    1L to exhausted(),
                ),
            )

        assertEquals(exhausted().copy(pauseClearedAtMillis = nowMillis), cleared.getValue(1L))
    }

    @Test
    fun `zero extension grants only the current entry of an exhausted time allowance`() {
        val cleared =
            UsageAccounting.clearPause(
                pkg,
                now,
                listOf(schedule(afterPause = 0)),
                mapOf(
                    1L to exhausted(),
                ),
            )

        assertEquals(
            exhausted().copy(pauseClearedAtMillis = nowMillis, sessionTimeGranted = true),
            cleared.getValue(1L),
        )
    }

    @Test
    fun `blocking and allowing rules are left unchanged`() {
        val block = schedule(id = 1, afterPause = 30, delay = null)
        val allow = schedule(id = 2, afterPause = 30)
        val runtime = mapOf(1L to exhausted(1), 2L to ScheduleRuntime(2, 0, null, occurrence))

        assertEquals(runtime, UsageAccounting.clearPause(pkg, now, listOf(block, allow), runtime))
    }

    @Test
    fun `clearance and admission can be persisted together without counting an opening or losing extension stats`() {
        for (afterPause in listOf(0, 30)) {
            val time = schedule(id = 1, afterPause = afterPause)
            val openings =
                schedule(id = 2, afterPause = afterPause).copy(sharedBudgetSeconds = null, openingAllowance = 1)
            val unaffected = schedule(id = 3, afterPause = afterPause).copy(packages = setOf("other"))
            val schedules = listOf(time, openings, unaffected)
            val previous =
                mapOf(
                    1L to exhausted(),
                    2L to ScheduleRuntime(2, 12_000, null, occurrence, consumedOpenings = 1),
                    3L to exhausted(3),
                )
            val cleared = UsageAccounting.clearPause(pkg, now, schedules, previous)
            val entered = recordEntry(pkg, now, schedules, cleared, countsAsOpening = false)

            assertEquals(pkg, entered.getValue(1L).sessionPackage)
            assertEquals(pkg, entered.getValue(2L).sessionPackage)
            assertEquals(afterPause == 0, entered.getValue(1L).sessionTimeGranted)
            assertEquals(false, entered.getValue(2L).sessionTimeGranted)
            assertEquals(1, entered.getValue(2L).consumedOpenings)
            assertEquals(previous.getValue(3L), entered.getValue(3L))
            assertEquals(emptyMap(), statsDeltas(cleared, entered))
            assertEquals(
                mapOf(1L to RuleStats(extensions = 1), 2L to RuleStats(extensions = 1)),
                statsDeltas(previous, entered),
            )
        }
    }

    @Test
    fun `remaining openings are the lowest across active covering rules and exclude the current entry`() {
        val one = schedule(id = 1, afterPause = 0).copy(sharedBudgetSeconds = null, openingAllowance = 3)
        val two = schedule(id = 2, afterPause = 0).copy(sharedBudgetSeconds = null, openingAllowance = 2)
        val timeOnly = schedule(id = 3, afterPause = 0)
        val runtime = mapOf(1L to ScheduleRuntime(1, 0, null, occurrence, consumedOpenings = 1))

        assertEquals(2, RulesEngine.remainingOpenings(pkg, now, listOf(one, two, timeOnly), runtime))
        assertEquals(null, RulesEngine.remainingOpenings(pkg, now, listOf(timeOnly), runtime))
    }
}
