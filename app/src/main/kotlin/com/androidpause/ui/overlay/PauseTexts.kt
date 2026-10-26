package com.androidpause.ui.overlay

import com.androidpause.core.MINUTES_PER_HOUR
import com.androidpause.core.SECONDS_PER_MINUTE

private const val MILLIS_PER_MINUTE = 60_000L

/** The finite pause's wait is shown on its action until the action is allowed. */
internal fun extendLabel(
    extensionSeconds: Int,
    remainingSeconds: Int,
): String {
    val verb = if (extensionSeconds == 0) "Proceed" else "Extend"
    return when {
        remainingSeconds > 0 -> "$verb in ${clockText(remainingSeconds)}"
        extensionSeconds == 0 -> verb
        else -> "$verb by ${formatDuration(extensionSeconds)}"
    }
}

/** Whole-minute consumption rounds down and is capped, so it reads as the allowance only once exhausted. */
internal fun timeUsageText(
    consumedSeconds: Int,
    budgetSeconds: Int,
): String =
    "${consumedSeconds.coerceIn(0, budgetSeconds) / SECONDS_PER_MINUTE} / ${budgetSeconds / SECONDS_PER_MINUTE} min"

/** Whole minutes, rounded down, with hours once there are any. */
internal fun durationText(millis: Long): String {
    val minutes = millis / MILLIS_PER_MINUTE
    val hours = minutes / MINUTES_PER_HOUR
    return if (hours == 0L) "$minutes min" else "$hours h ${minutes % MINUTES_PER_HOUR} min"
}

internal fun clockText(seconds: Int): String =
    "${seconds / SECONDS_PER_MINUTE}:${(seconds % SECONDS_PER_MINUTE).toString().padStart(2, '0')}"

private fun formatDuration(seconds: Int): String =
    when {
        seconds % SECONDS_PER_MINUTE == 0 -> {
            val minutes = seconds / SECONDS_PER_MINUTE
            "$minutes ${if (minutes == 1) "minute" else "minutes"}"
        }
        else -> "$seconds seconds"
    }
