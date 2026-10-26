package com.androidpause.domain

import com.androidpause.domain.model.RuleStats
import com.androidpause.domain.model.ScheduleRuntime
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * Daily statistics derived from persisted runtime changes: added usage and openings, and each new
 * extension anchor. Occurrence resets lower the counters and are ignored.
 */
fun statsDeltas(
    previous: Map<Long, ScheduleRuntime>,
    updated: Map<Long, ScheduleRuntime>,
): Map<Long, RuleStats> =
    updated
        .mapValues { (id, now) ->
            val before = previous[id]
            val cleared = now.pauseClearedAtMillis
            RuleStats(
                usageMillis = (now.consumedBudgetMillis - (before?.consumedBudgetMillis ?: 0)).coerceAtLeast(0),
                openings = (now.consumedOpenings - (before?.consumedOpenings ?: 0)).coerceAtLeast(0),
                extensions = if (cleared != null && cleared != before?.pauseClearedAtMillis) 1 else 0,
            )
        }.filterValues { it != RuleStats() }

fun weekStart(
    day: LocalDate,
    firstDayOfWeek: DayOfWeek,
): LocalDate = day.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
