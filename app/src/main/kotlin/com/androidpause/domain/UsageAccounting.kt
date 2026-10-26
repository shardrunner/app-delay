package com.androidpause.domain

import com.androidpause.core.MILLIS_PER_SECOND
import com.androidpause.domain.model.PauseOutcome
import com.androidpause.domain.model.ScheduleConfig
import com.androidpause.domain.model.ScheduleRuntime
import java.time.ZonedDateTime

/**
 * Pure accounting of covered-app foreground usage against per-schedule shared budgets.
 * Combined time sums per schedule with no double counting (SC-007); pause-screen time excluded.
 */
object UsageAccounting {
    /** Add a finished foreground span to every active schedule covering [pkg]. Returns updated runtime. */
    fun applyForegroundSpan(
        pkg: String,
        spanMillis: Long,
        now: ZonedDateTime,
        schedules: List<ScheduleConfig>,
        runtime: Map<Long, ScheduleRuntime>,
    ): Map<Long, ScheduleRuntime> {
        require(spanMillis >= 0) { "spanMillis must be >= 0" }

        val resetRuntime = resetExpiredWindows(now, schedules, runtime)
        if (spanMillis == 0L) return resetRuntime

        val updated = resetRuntime.toMutableMap()
        schedules
            .activeCovering(pkg, now)
            .forEach { schedule ->
                val current = updated[schedule.id] ?: initialRuntime(schedule, now)
                val budgetMillis = schedule.sharedBudgetSeconds?.let { it * MILLIS_PER_SECOND }
                val consumed = current.consumedBudgetMillis + spanMillis
                val firstTimeExhaustion =
                    budgetMillis != null &&
                        current.consumedBudgetMillis < budgetMillis &&
                        consumed >= budgetMillis
                updated[schedule.id] =
                    current.copy(
                        consumedBudgetMillis = consumed,
                        pauseClearedAtMillis = if (firstTimeExhaustion) null else current.pauseClearedAtMillis,
                        windowOccurrenceKey = ScheduleWindowBoundaries.windowOccurrenceKey(schedule, now),
                    )
            }
        return updated.toMap()
    }

    /**
     * Anchors the after-pause extension of each active covering rule that pauses [pkg]. A zero
     * extension instead grants the current entry an exhausted time allowance (FR-013b).
     */
    fun clearPause(
        pkg: String,
        now: ZonedDateTime,
        schedules: List<ScheduleConfig>,
        runtime: Map<Long, ScheduleRuntime>,
    ): Map<Long, ScheduleRuntime> {
        val nowMillis = now.toInstant().toEpochMilli()
        val pausing =
            schedules.activeCovering(pkg, now).filter {
                RulesEngine.evaluate(pkg, now, listOf(it), runtime) is PauseOutcome.Pause
            }
        if (pausing.isEmpty()) return runtime
        val updated = runtime.toMutableMap()
        pausing.forEach { schedule ->
            val current = runtime[schedule.id] ?: initialRuntime(schedule, now)
            updated[schedule.id] =
                current.copy(
                    pauseClearedAtMillis = nowMillis,
                    sessionTimeGranted = schedule.afterPauseBudgetSeconds == 0 && schedule.timeUsedUp(current),
                    windowOccurrenceKey = ScheduleWindowBoundaries.windowOccurrenceKey(schedule, now),
                )
        }
        return updated.toMap()
    }

    /** Reset consumed budget for schedules whose window occurrence changed since the stored key. */
    fun resetExpiredWindows(
        now: ZonedDateTime,
        schedules: List<ScheduleConfig>,
        runtime: Map<Long, ScheduleRuntime>,
    ): Map<Long, ScheduleRuntime> {
        val updated = runtime.toMutableMap()
        var changed = false

        schedules
            .asSequence()
            .filter { it.enabled && ScheduleWindowBoundaries.isActive(it, now) }
            .forEach { schedule ->
                val current = updated[schedule.id] ?: return@forEach
                val occurrenceKey = ScheduleWindowBoundaries.windowOccurrenceKey(schedule, now)
                if (current.windowOccurrenceKey == occurrenceKey) return@forEach

                updated[schedule.id] =
                    current.copy(
                        consumedBudgetMillis = 0,
                        consumedOpenings = 0,
                        sessionPackage = null,
                        sessionTimeGranted = false,
                        pauseClearedAtMillis = null,
                        windowOccurrenceKey = occurrenceKey,
                    )
                changed = true
            }

        return if (changed) updated.toMap() else runtime
    }
}
