package com.androidpause.domain

import com.androidpause.core.MILLIS_PER_SECOND
import com.androidpause.domain.model.PauseInfo
import com.androidpause.domain.model.PauseOutcome
import com.androidpause.domain.model.ScheduleConfig
import com.androidpause.domain.model.ScheduleRuntime
import com.androidpause.domain.model.UpcomingInterrupt
import java.time.ZonedDateTime

/**
 * Pure rules engine: window math, budget exhaustion, after-pause grace, and
 * most-restrictive resolution across overlapping schedules.
 *
 * Unit-tested on the JVM. No Android dependencies.
 */
object RulesEngine {
    /** Decide the outcome for [pkg] at [now] given schedules + runtime state. */
    fun evaluate(
        pkg: String,
        now: ZonedDateTime,
        schedules: List<ScheduleConfig>,
        runtime: Map<Long, ScheduleRuntime>,
    ): PauseOutcome {
        val nowMillis = now.toInstant().toEpochMilli()
        val covering = schedules.activeCovering(pkg, now)
        val outcomes = covering.map { classify(it, runtime[it.id], nowMillis, pkg) }
        return combine(outcomes)
    }

    /** Earliest next interruption for [pkg] at [now], if any active covering schedule exists. */
    fun nextInterrupt(
        pkg: String,
        now: ZonedDateTime,
        schedules: List<ScheduleConfig>,
        runtime: Map<Long, ScheduleRuntime>,
    ): UpcomingInterrupt? {
        val nowMillis = now.toInstant().toEpochMilli()
        return schedules
            .activeCovering(pkg, now)
            .mapNotNull { schedule ->
                val scheduleRuntime = runtime[schedule.id] ?: initialRuntime(schedule, now)
                if (scheduleRuntime.sessionTimeGranted && scheduleRuntime.sessionPackage == pkg) return@mapNotNull null
                val remaining = remainingMillis(schedule, scheduleRuntime, nowMillis) ?: return@mapNotNull null
                val windowEnd = requireNotNull(ScheduleWindowBoundaries.next(pkg, now, listOf(schedule)))
                UpcomingInterrupt(schedule.id, remaining).takeIf { nowMillis + remaining < windowEnd }
            }.minWithOrNull(compareBy<UpcomingInterrupt>({ it.remainingMillis }, { it.scheduleId }))
    }

    /** Lowest openings left (excluding the current entry) among active covering rules with an opening allowance. */
    fun remainingOpenings(
        pkg: String,
        now: ZonedDateTime,
        schedules: List<ScheduleConfig>,
        runtime: Map<Long, ScheduleRuntime>,
    ): Int? =
        schedules
            .activeCovering(pkg, now)
            .mapNotNull { schedule ->
                schedule.openingAllowance?.let { (it - (runtime[schedule.id]?.consumedOpenings ?: 0)).coerceAtLeast(0) }
            }.minOrNull()

    /** Outcome for one active covering schedule given its runtime state. */
    private fun classify(
        schedule: ScheduleConfig,
        runtime: ScheduleRuntime?,
        nowMillis: Long,
        pkg: String,
    ): PauseOutcome {
        val timeExhausted = schedule.timeAllowanceExhausted(runtime, pkg)
        val openingsExhausted = schedule.openingAllowanceExhausted(runtime, pkg)
        val extensionExpired = schedule.extensionExpired(runtime, nowMillis)
        val exhausted = timeExhausted || openingsExhausted || extensionExpired
        if (!exhausted) return PauseOutcome.Allow

        val info =
            PauseInfo(
                scheduleId = schedule.id,
                message = schedule.message,
                sharedBudgetSeconds = schedule.sharedBudgetSeconds,
                consumedBudgetSeconds = ((runtime?.consumedBudgetMillis ?: 0) / MILLIS_PER_SECOND).toInt(),
                afterPauseBudgetSeconds = schedule.afterPauseBudgetSeconds,
                windowEnd = schedule.windowEnd,
                openingAllowance = schedule.openingAllowance,
                consumedOpenings = runtime?.consumedOpenings ?: 0,
            )
        // Exhausted: infinite delay => block, else pause subject to after-pause grace.
        val delay = schedule.pauseDelaySeconds ?: return PauseOutcome.Block(info)

        val clearedAt = runtime?.pauseClearedAtMillis
        val extensionStillActive =
            clearedAt != null &&
                nowMillis - clearedAt < schedule.afterPauseBudgetSeconds * MILLIS_PER_SECOND
        if (extensionStillActive) return PauseOutcome.Allow
        return PauseOutcome.Pause(delay, info)
    }

    /** Combine per-schedule outcomes by max severity: Block > Pause > Allow (FR-014). */
    private fun combine(outcomes: List<PauseOutcome>): PauseOutcome {
        outcomes
            .filterIsInstance<PauseOutcome.Block>()
            .minByOrNull { it.info.scheduleId }
            ?.let { return it }
        val pauses = outcomes.filterIsInstance<PauseOutcome.Pause>()
        if (pauses.isNotEmpty()) {
            // Longest delay wins; lower schedule id makes ties deterministic (FR-014).
            val winner = pauses.maxWith(compareBy<PauseOutcome.Pause>({ it.delaySeconds }, { -it.info.scheduleId }))
            val effectiveExtension = pauses.minOf { it.info.afterPauseBudgetSeconds }
            return winner.copy(info = winner.info.copy(afterPauseBudgetSeconds = effectiveExtension))
        }
        return PauseOutcome.Allow
    }

    /** Time until [schedule] interrupts: its time allowance or a running extension, whichever ends first. */
    private fun remainingMillis(
        schedule: ScheduleConfig,
        runtime: ScheduleRuntime,
        nowMillis: Long,
    ): Long? {
        val extensionRemaining =
            runtime.pauseClearedAtMillis
                ?.takeIf { schedule.afterPauseBudgetSeconds > 0 }
                ?.let { (it + schedule.afterPauseBudgetSeconds * MILLIS_PER_SECOND - nowMillis).coerceAtLeast(0L) }
        val budget = schedule.sharedBudgetSeconds ?: return extensionRemaining
        val timeRemaining = budget * MILLIS_PER_SECOND - runtime.consumedBudgetMillis
        return when {
            timeRemaining > 0 -> minOf(timeRemaining, extensionRemaining ?: Long.MAX_VALUE)
            schedule.pauseDelaySeconds == null -> 0L
            else -> extensionRemaining ?: 0L
        }
    }
}
