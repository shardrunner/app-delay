package com.androidpause.domain.model

import java.time.LocalTime

/**
 * Pure-domain configuration of a single friction schedule.
 *
 * No Android dependencies — unit-testable on the JVM (Constitution I & III).
 * A window crosses midnight when [windowEnd] `<=` [windowStart]. The end time is
 * exclusive (FR-003a).
 */
data class ScheduleConfig(
    val id: Long,
    val enabled: Boolean,
    val name: String,
    val windowStart: LocalTime,
    val windowEnd: LocalTime,
    /** `null` means no time allowance; zero restricts immediately. */
    val sharedBudgetSeconds: Int?,
    /** `null` => infinite/total block (FR-005, FR-012). */
    val pauseDelaySeconds: Int?,
    val afterPauseBudgetSeconds: Int,
    val message: String,
    /** Enabled state and packages are owned by the rule's profile. */
    val packages: Set<String>,
    val openingAllowance: Int? = null,
)

/**
 * Per-schedule runtime enforcement state for the current active-window occurrence.
 *
 * Stored with per-rule usage; in-progress time can be lost on abrupt termination.
 */
data class ScheduleRuntime(
    val scheduleId: Long,
    /** Covered foreground usage in this occurrence, persisted in milliseconds so short visits add up exactly. */
    val consumedBudgetMillis: Long,
    /** Epoch millis when the last finite pause cleared; anchors the after-pause timer (FR-013b). */
    val pauseClearedAtMillis: Long?,
    /** Epoch-day of the currently persisted window occurrence; drives lazy budget reset. */
    val windowOccurrenceKey: Long,
    val consumedOpenings: Int = 0,
    val sessionPackage: String? = null,
    val sessionTimeGranted: Boolean = false,
)

/** Time until the earliest active covering rule interrupts the current foreground entry. */
data class UpcomingInterrupt(
    val scheduleId: Long,
    val remainingMillis: Long,
)

/** User-facing context for the schedule that produced the effective pause/block. */
data class PauseInfo(
    val scheduleId: Long,
    val message: String,
    val sharedBudgetSeconds: Int?,
    val consumedBudgetSeconds: Int,
    val afterPauseBudgetSeconds: Int,
    val windowEnd: LocalTime,
    /** `null` when the rule has no opening allowance. */
    val openingAllowance: Int? = null,
    val consumedOpenings: Int = 0,
)

/**
 * Severity-ordered outcome of evaluating a launched package.
 *
 * Severity: [Allow] < [Pause] < [Block] (FR-014).
 */
sealed interface PauseOutcome {
    data object Allow : PauseOutcome

    /** A pause or block, with the context of the rule behind it. */
    sealed interface Restriction : PauseOutcome {
        val info: PauseInfo
    }

    data class Pause(
        val delaySeconds: Int,
        override val info: PauseInfo,
    ) : Restriction

    data class Block(
        override val info: PauseInfo,
    ) : Restriction
}

/** Per-rule activity for a day or summed over days. */
data class RuleStats(
    val usageMillis: Long = 0,
    val openings: Int = 0,
    val extensions: Int = 0,
    val notNow: Int = 0,
) {
    operator fun plus(other: RuleStats) =
        RuleStats(
            usageMillis + other.usageMillis,
            openings + other.openings,
            extensions + other.extensions,
            notNow + other.notNow,
        )
}

/** Statistics of the rule behind a pause, shown on the pause screen. */
data class PauseStats(
    val today: RuleStats = RuleStats(),
    val week: RuleStats = RuleStats(),
)
