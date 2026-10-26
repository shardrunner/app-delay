package com.androidpause.enforcement

import com.androidpause.core.MILLIS_PER_SECOND

internal const val LAST_MINUTE_MILLIS = 60_000L
private const val WARNING_MILLIS = 5 * LAST_MINUTE_MILLIS
private const val SECONDS_PER_MINUTE = 60

internal enum class BubbleLevel { NORMAL, WARNING, CRITICAL }

internal data class BubbleState(
    val label: String,
    val level: BubbleLevel,
)

/**
 * Remaining openings (excluding the current entry) beside the countdown to the next interruption;
 * either may be absent. Minutes above the final minute keep redraws rare; the final minute shows
 * `m:ss`. The last opening and the final five minutes warn; no opening left and the final minute
 * are critical.
 */
internal fun countdownBubbleState(
    remainingMillis: Long?,
    lastMinuteOnly: Boolean,
    remainingOpenings: Int?,
): BubbleState? {
    if (remainingMillis != null && remainingMillis <= 0) return null
    if (lastMinuteOnly && (remainingMillis ?: Long.MAX_VALUE) > LAST_MINUTE_MILLIS) return null
    val countdown = remainingMillis?.let(::countdownLabel)
    val label = listOfNotNull(remainingOpenings?.let { "${it}x" }, countdown).joinToString(" · ")
    if (label.isEmpty()) return null
    val millis = remainingMillis ?: Long.MAX_VALUE
    val level =
        when {
            remainingOpenings == 0 || millis <= LAST_MINUTE_MILLIS -> BubbleLevel.CRITICAL
            remainingOpenings == 1 || millis <= WARNING_MILLIS -> BubbleLevel.WARNING
            else -> BubbleLevel.NORMAL
        }
    return BubbleState(label, level)
}

private fun countdownLabel(remainingMillis: Long): String {
    if (remainingMillis > LAST_MINUTE_MILLIS) return "${ceilDiv(remainingMillis, LAST_MINUTE_MILLIS)}m"
    val seconds = ceilDiv(remainingMillis, MILLIS_PER_SECOND)
    return "${seconds / SECONDS_PER_MINUTE}:${(seconds % SECONDS_PER_MINUTE).toString().padStart(2, '0')}"
}

/** Delay until [countdownBubbleState] next changes; a hidden bubble waits for the final minute. */
internal fun countdownBubbleNextUpdateMillis(
    remainingMillis: Long,
    lastMinuteOnly: Boolean,
): Long {
    if (lastMinuteOnly && remainingMillis > LAST_MINUTE_MILLIS) return remainingMillis - LAST_MINUTE_MILLIS
    val step = if (remainingMillis > LAST_MINUTE_MILLIS) LAST_MINUTE_MILLIS else MILLIS_PER_SECOND
    return (remainingMillis - 1) % step + 1
}

private fun ceilDiv(
    value: Long,
    divisor: Long,
): Long = (value - 1) / divisor + 1
