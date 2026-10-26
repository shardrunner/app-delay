package com.androidpause.domain

import com.androidpause.core.MILLIS_PER_SECOND
import com.androidpause.domain.model.ScheduleConfig
import com.androidpause.domain.model.ScheduleRuntime

/** The time allowance is used up, regardless of a current-entry grant. */
internal fun ScheduleConfig.timeUsedUp(runtime: ScheduleRuntime?): Boolean =
    sharedBudgetSeconds?.let { (runtime?.consumedBudgetMillis ?: 0) >= it * MILLIS_PER_SECOND } ?: false

internal fun ScheduleConfig.timeAllowanceExhausted(
    runtime: ScheduleRuntime?,
    pkg: String,
): Boolean = timeUsedUp(runtime) && !(runtime?.sessionTimeGranted == true && runtime.sessionPackage == pkg)

internal fun ScheduleConfig.openingAllowanceExhausted(
    runtime: ScheduleRuntime?,
    pkg: String,
): Boolean = openingAllowance?.let { (runtime?.consumedOpenings ?: 0) >= it && runtime?.sessionPackage != pkg } ?: false

internal fun ScheduleConfig.extensionExpired(
    runtime: ScheduleRuntime?,
    nowMillis: Long,
): Boolean =
    runtime?.pauseClearedAtMillis?.let {
        afterPauseBudgetSeconds > 0 && nowMillis - it >= afterPauseBudgetSeconds * MILLIS_PER_SECOND
    } ?: false
