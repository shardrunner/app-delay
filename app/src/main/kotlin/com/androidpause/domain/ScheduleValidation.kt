package com.androidpause.domain

import com.androidpause.core.SECONDS_PER_MINUTE
import com.androidpause.domain.model.ScheduleConfig

/**
 * Validates user-entered schedule configuration. Fails loud with
 * [IllegalArgumentException] on any invalid field (data-model validation summary).
 */
object ScheduleValidation {
    /** Returns [config] unchanged if valid; throws [IllegalArgumentException] otherwise. */
    fun validate(config: ScheduleConfig): ScheduleConfig {
        require(config.name.isNotBlank()) { "name must not be blank" }
        require(config.packages.isNotEmpty()) { "schedule must cover at least one app" }
        require(
            config.sharedBudgetSeconds != null || config.openingAllowance != null,
        ) { "at least one allowance is required" }
        require(config.openingAllowance == null || config.openingAllowance >= 0) { "openingAllowance must be >= 0" }
        config.sharedBudgetSeconds?.let {
            require(it >= 0) { "sharedBudgetSeconds must be >= 0" }
            require(it % SECONDS_PER_MINUTE == 0) { "sharedBudgetSeconds must be whole minutes" }
        }
        config.pauseDelaySeconds?.let {
            require(it >= 0) { "finite pauseDelaySeconds must be >= 0" }
        }
        require(config.afterPauseBudgetSeconds >= 0) { "afterPauseBudgetSeconds must be >= 0" }
        return config
    }
}
