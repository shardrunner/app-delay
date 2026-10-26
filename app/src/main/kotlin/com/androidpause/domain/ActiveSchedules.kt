package com.androidpause.domain

import com.androidpause.domain.model.ScheduleConfig
import com.androidpause.domain.model.ScheduleRuntime
import java.time.ZonedDateTime

/** Enabled schedules covering [pkg] whose window is active at [now]. */
internal fun List<ScheduleConfig>.activeCovering(
    pkg: String,
    now: ZonedDateTime,
): List<ScheduleConfig> = filter { it.enabled && pkg in it.packages && ScheduleWindowBoundaries.isActive(it, now) }

/** Fresh runtime for the occurrence of [schedule] active at [now]. */
internal fun initialRuntime(
    schedule: ScheduleConfig,
    now: ZonedDateTime,
): ScheduleRuntime =
    ScheduleRuntime(
        scheduleId = schedule.id,
        consumedBudgetMillis = 0,
        pauseClearedAtMillis = null,
        windowOccurrenceKey = ScheduleWindowBoundaries.windowOccurrenceKey(schedule, now),
    )
