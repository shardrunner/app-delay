package com.androidpause.data

import com.androidpause.core.MINUTES_PER_HOUR
import com.androidpause.data.entity.INFINITE_PAUSE_DELAY_SENTINEL
import com.androidpause.data.entity.ScheduleEntity
import com.androidpause.data.entity.ScheduleUsageEntity
import com.androidpause.domain.model.ScheduleConfig
import com.androidpause.domain.model.ScheduleRuntime
import java.time.LocalTime

/** Maps between Room entities and pure-domain models (data-model.md). */

private fun LocalTime.toMinutesOfDay(): Int = hour * MINUTES_PER_HOUR + minute

private fun minutesOfDayToLocalTime(minutes: Int): LocalTime =
    LocalTime.of(minutes / MINUTES_PER_HOUR, minutes % MINUTES_PER_HOUR)

/** Domain config -> schedule row of [profileId] (infinite delay -> sentinel). */
fun ScheduleConfig.toEntity(profileId: Long): ScheduleEntity =
    ScheduleEntity(
        id = id,
        profileId = profileId,
        name = name,
        windowStartMinutes = windowStart.toMinutesOfDay(),
        windowEndMinutes = windowEnd.toMinutesOfDay(),
        sharedBudgetSeconds = sharedBudgetSeconds,
        pauseDelaySeconds = pauseDelaySeconds ?: INFINITE_PAUSE_DELAY_SENTINEL,
        afterPauseBudgetSeconds = afterPauseBudgetSeconds,
        message = message,
        openingAllowance = openingAllowance,
    )

/** Schedule row + its profile's enabled state and apps -> domain config (sentinel -> infinite/null). */
fun ScheduleEntity.toConfig(
    enabled: Boolean,
    packages: Set<String>,
): ScheduleConfig =
    ScheduleConfig(
        id = id,
        enabled = enabled,
        name = name,
        windowStart = minutesOfDayToLocalTime(windowStartMinutes),
        windowEnd = minutesOfDayToLocalTime(windowEndMinutes),
        sharedBudgetSeconds = sharedBudgetSeconds,
        pauseDelaySeconds = pauseDelaySeconds.takeIf { it != INFINITE_PAUSE_DELAY_SENTINEL },
        afterPauseBudgetSeconds = afterPauseBudgetSeconds,
        message = message,
        openingAllowance = openingAllowance,
        packages = packages,
    )

/** Runtime usage row -> domain runtime. */
fun ScheduleUsageEntity.toRuntime(): ScheduleRuntime =
    ScheduleRuntime(
        scheduleId = scheduleId,
        consumedBudgetMillis = consumedBudgetMillis,
        consumedOpenings = consumedOpenings,
        sessionPackage = sessionPackage,
        sessionTimeGranted = sessionTimeGranted,
        pauseClearedAtMillis = pauseClearedAtMillis,
        windowOccurrenceKey = windowOccurrenceKey,
    )

/** Domain runtime -> usage row. */
fun ScheduleRuntime.toEntity(): ScheduleUsageEntity =
    ScheduleUsageEntity(
        scheduleId = scheduleId,
        windowOccurrenceKey = windowOccurrenceKey,
        consumedBudgetMillis = consumedBudgetMillis,
        consumedOpenings = consumedOpenings,
        sessionPackage = sessionPackage,
        sessionTimeGranted = sessionTimeGranted,
        pauseClearedAtMillis = pauseClearedAtMillis,
    )
