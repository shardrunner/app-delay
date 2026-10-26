package com.androidpause.ui.config

import com.androidpause.core.SECONDS_PER_MINUTE
import com.androidpause.domain.model.ScheduleConfig
import java.time.LocalTime

private const val DECIMAL_RADIX = 10
private const val DEFAULT_WINDOW_START_HOUR = 23
private const val DEFAULT_WINDOW_END_HOUR = 7
private const val DEFAULT_SHARED_BUDGET_MINUTES = 10
private const val DEFAULT_PAUSE_DELAY_SECONDS = 5
private const val DEFAULT_AFTER_PAUSE_BUDGET_SECONDS = SECONDS_PER_MINUTE

/** Time budgets are whole minutes whose seconds fit an Int. */
internal const val MAX_BUDGET_MINUTES = Int.MAX_VALUE / SECONDS_PER_MINUTE

/**
 * Editor draft with raw numeric input as the sole source of numeric values.
 * Converted to a validated [ScheduleConfig] on save.
 */
data class ScheduleDraft(
    val id: Long = 0,
    val name: String = "",
    val windowStart: LocalTime = LocalTime.of(DEFAULT_WINDOW_START_HOUR, 0),
    val windowEnd: LocalTime = LocalTime.of(DEFAULT_WINDOW_END_HOUR, 0),
    val message: String = "Take a break.",
    val timeBudgetEnabled: Boolean = true,
    val openingAllowanceEnabled: Boolean = false,
    val infinitePause: Boolean = false,
    val budgetInput: String = DEFAULT_SHARED_BUDGET_MINUTES.toString(),
    val delayInput: String = DEFAULT_PAUSE_DELAY_SECONDS.toString(),
    val afterPauseInput: String = DEFAULT_AFTER_PAUSE_BUDGET_SECONDS.toString(),
    val openingsInput: String = "1",
) : java.io.Serializable {
    val numbersValid: Boolean
        get() =
            (!timeBudgetEnabled || boundedNumber(budgetInput, MAX_BUDGET_MINUTES) != null) &&
                (infinitePause || boundedNumber(delayInput) != null) &&
                (infinitePause || boundedNumber(afterPauseInput) != null) &&
                (!openingAllowanceEnabled || boundedNumber(openingsInput) != null)

    val canSave: Boolean
        get() =
            numbersValid &&
                name.isNotBlank() &&
                (timeBudgetEnabled || openingAllowanceEnabled)

    /** [enabled] and [packages] belong to the rule's profile. */
    fun toConfig(
        enabled: Boolean,
        packages: Set<String>,
    ): ScheduleConfig {
        require(numbersValid) { "Numeric fields must be whole numbers within the displayed bounds" }
        return ScheduleConfig(
            id = id,
            enabled = enabled,
            name = name.trim(),
            windowStart = windowStart,
            windowEnd = windowEnd,
            sharedBudgetSeconds =
                if (timeBudgetEnabled) {
                    requireNotNull(boundedNumber(budgetInput, MAX_BUDGET_MINUTES)) * SECONDS_PER_MINUTE
                } else {
                    null
                },
            pauseDelaySeconds = if (infinitePause) null else boundedNumber(delayInput),
            afterPauseBudgetSeconds =
                if (infinitePause) {
                    boundedNumber(
                        afterPauseInput,
                    ) ?: 0
                } else {
                    requireNotNull(boundedNumber(afterPauseInput))
                },
            message = message,
            packages = packages,
            openingAllowance = if (openingAllowanceEnabled) boundedNumber(openingsInput) else null,
        )
    }

    companion object {
        private const val serialVersionUID: Long = 1L

        fun from(config: ScheduleConfig): ScheduleDraft =
            ScheduleDraft(
                id = config.id,
                name = config.name,
                windowStart = config.windowStart,
                windowEnd = config.windowEnd,
                budgetInput = ((config.sharedBudgetSeconds ?: 0) / SECONDS_PER_MINUTE).toString(),
                infinitePause = config.pauseDelaySeconds == null,
                delayInput = (config.pauseDelaySeconds ?: DEFAULT_PAUSE_DELAY_SECONDS).toString(),
                afterPauseInput = config.afterPauseBudgetSeconds.toString(),
                message = config.message,
                timeBudgetEnabled = config.sharedBudgetSeconds != null,
                openingAllowanceEnabled = config.openingAllowance != null,
                openingsInput = (config.openingAllowance ?: 1).toString(),
            )
    }
}

/** Strict parsing: never strip characters, clamp overflow, or turn blank input into zero. */
internal fun boundedNumber(
    text: String,
    maximum: Int = Int.MAX_VALUE,
): Int? =
    text
        .takeIf { it.isNotEmpty() && it.all { char -> Character.digit(char, DECIMAL_RADIX) >= 0 } }
        ?.map { Character.digit(it, DECIMAL_RADIX) }
        ?.joinToString("")
        ?.toIntOrNull()
        ?.takeIf { it in 0..maximum }
